package au.buzz.ryzewave.ble

import au.buzz.ryzewave.core.BloodPressureReading
import au.buzz.ryzewave.core.ConnectionState
import au.buzz.ryzewave.core.HealthRepository
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.SamplingSettings
import au.buzz.ryzewave.core.SettingsStore
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.SyncResult
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.WatchApi
import au.buzz.ryzewave.core.WatchEvent
import au.buzz.ryzewave.core.WatchStatus
import au.buzz.ryzewave.core.WatchFaceConfig
import au.buzz.ryzewave.core.WatchFaceUploadProgress
import au.buzz.ryzewave.core.WorkoutControlAction
import au.buzz.ryzewave.protocol.Packet
import au.buzz.ryzewave.protocol.Protocol
import au.buzz.ryzewave.protocol.SportTypes
import au.buzz.ryzewave.protocol.SportState
import au.buzz.ryzewave.protocol.Spo2Phase
import au.buzz.ryzewave.protocol.encFetchHr24Since
import au.buzz.ryzewave.protocol.encUserInfo
import au.buzz.ryzewave.protocol.toEvent
import au.buzz.ryzewave.protocol.toHrSample
import au.buzz.ryzewave.protocol.toSleepStage
import au.buzz.ryzewave.protocol.toSpo2Sample
import au.buzz.ryzewave.protocol.toStepsHour
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

/**
 * [WatchApi] on top of a [WatchLink], persisting through the [HealthRepository].
 *
 * P32 compatibility:
 * Firmware beginning RB112UDG uses the UTE protocol but does not respond to
 * the Ryze Wave SpO2 command family used by this application.
 *
 * The same P32 exposes its legacy UTE/GloryFit blood-pressure measurement
 * through the C7 command family.
 */
class WatchApiImpl(
    private val link: WatchLink,
    private val repo: HealthRepository,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val autoSetupOnConnect: Boolean = true,
    private val liveHrWarmupMs: Long = LIVE_HR_WARMUP_MS,
    private val fetchTimeoutMs: Long = WatchLink.FETCH_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val log: (String, Throwable?) -> Unit = { msg, t ->
        println("WatchApi: $msg${if (t != null) " ($t)" else ""}")
    },
) : WatchApi {

    private val _status = MutableStateFlow(WatchStatus())
    override val status: StateFlow<WatchStatus> = _status.asStateFlow()

    private val _liveHr = MutableSharedFlow<HrSample>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val liveHr: SharedFlow<HrSample> = _liveHr

    private val _events = MutableSharedFlow<WatchEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<WatchEvent> = _events

    private val syncMutex = Mutex()

    @Volatile
    private var sportType = 1

    @Volatile
    private var spo2TestStartedAt = 0L

    @Volatile
    private var lastLivePersist = 0L

    /**
     * P32 / RB112UDG compatibility.
     *
     * This firmware speaks the normal UTE/GloryFit protocol used by the
     * application, but testing shows that it does not answer the Ryze Wave
     * 34xx SpO2 commands.
     */
    private fun supportsRyzeSpo2(): Boolean =
        !_status.value.firmware
            .orEmpty()
            .startsWith("RB112UDG", ignoreCase = true)

    private data class ExpectedEcho(
        val state: Int,
        val at: Long
    )

    private val expectedEchoes =
        java.util.concurrent.CopyOnWriteArrayList<ExpectedEcho>()

    @Volatile
    private var linkSuspect: String? = null

    private val setupPending = AtomicBoolean(false)

    @Volatile
    private var pushedSleepDate: LocalDate? = null

    init {
        scope.launch {
            link.packets.collect {
                onPacket(it)
            }
        }

        scope.launch {
            link.state.collect {
                onLinkState(it)
            }
        }

        scope.launch {
            try {
                repo.lastSyncTime(SYNC_ALL)?.let { t ->
                    _status.update {
                        it.copy(lastSyncTime = t)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("could not read the last sync time", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------

    override suspend fun connect(mac: String) {
        _status.update {
            it.copy(
                mac = mac,
                message = null
            )
        }

        link.connect(mac)
    }

    override suspend fun disconnect() {
        link.disconnect()
    }

    private fun onLinkState(s: LinkState) {
        when (s) {
            is LinkState.Disconnected -> {
                _status.update {
                    it.copy(
                        state = if (s.status == 0) {
                            ConnectionState.DISCONNECTED
                        } else {
                            ConnectionState.ERROR
                        },
                        message = s.message,
                        charging = false,
                    )
                }
            }

            is LinkState.Connecting -> {
                _status.update {
                    it.copy(
                        state = ConnectionState.CONNECTING,
                        mac = s.mac,
                        message = if (s.attempt > 1) {
                            "reconnecting (attempt ${s.attempt})"
                        } else {
                            "connecting"
                        },
                    )
                }
            }

            is LinkState.Ready -> {
                _status.update {
                    it.copy(
                        state = ConnectionState.CONNECTED,
                        mac = s.mac,
                        message = null
                    )
                }

                if (
                    autoSetupOnConnect &&
                    setupPending.compareAndSet(false, true)
                ) {
                    scope.launch {
                        try {
                            setupAndSyncInternal(coalesced = true)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log("connect-time setup failed", e)
                        }
                    }
                }
            }
        }
    }

    suspend fun setupAndSync(): SyncResult =
        setupAndSyncInternal(coalesced = false)

    private suspend fun setupAndSyncInternal(
        coalesced: Boolean
    ): SyncResult = syncMutex.withLock {

        if (coalesced) {
            setupPending.set(false)
        }

        if (!link.isReady) {
            return SyncResult(
                0,
                0,
                0,
                0,
                "not connected"
            )
        }

        attempt("version") {
            readVersion()
        }

        attempt("battery") {
            readBatteryLocked()
        }

        val profile =
            attempt("profile") {
                settings.profile.first()
            } ?: UserProfile()

        val sampling =
            attempt("sampling") {
                settings.sampling.first()
            } ?: SamplingSettings()

        attempt("applySettings") {
            applySettingsLocked(
                profile,
                sampling
            )
        }

        syncAllLocked()
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    override suspend fun applySettings(
        profile: UserProfile,
        sampling: SamplingSettings
    ) = syncMutex.withLock {

        applySettingsLocked(
            profile,
            sampling
        )
    }

    private suspend fun applySettingsLocked(
        profile: UserProfile,
        sampling: SamplingSettings
    ) {
        requireReady()

        val interval =
            sampling.spo2IntervalMin
                .coerceIn(1, 0xFFFF)

        ack(
            Protocol.encSetTime(
                LocalDateTime.now(zone)
            ),
            Matchers.opcodeIs(
                Protocol.CMD_TIME
            ),
            "A3 set time"
        )

        ack(
            Protocol.encUserInfo(
                profile,
                raiseWrist = sampling.raiseWristWake,
                hrHigh = sampling.hrHighAlarmBpm,
                hrLow = sampling.hrLowAlarmBpm,
            ),
            Matchers.opcodeIs(
                Protocol.CMD_USER_INFO
            ),
            "A9 user info",
        )

        ack(
            Protocol.encHrContinuous(
                sampling.continuousHr
            ),
            Matchers.opcodeAndSub(
                Protocol.CMD_HR24,
                if (sampling.continuousHr) {
                    0x01
                } else {
                    0x02
                }
            ),
            "F7 continuous HR",
        )

        /*
         * P32 / RB112UDG:
         *
         * Do NOT send 34 03 or 34 04.
         *
         * The P32 does not answer the Ryze Wave SpO2 command
         * implementation. Other supported watches retain the original
         * behaviour.
         */
        if (supportsRyzeSpo2()) {

            ack(
                Protocol.encSpo2Auto(
                    sampling.spo2AutoEnabled,
                    interval
                ),
                Matchers.opcodeAndSub(
                    Protocol.CMD_SPO2,
                    0x03
                ),
                "34 03 SpO2 auto",
            )

            ack(
                Protocol.encSpo2Period(
                    sampling.spo2AutoEnabled
                ),
                Matchers.opcodeAndSub(
                    Protocol.CMD_SPO2,
                    0x04
                ),
                "34 04 SpO2 period",
            )

        } else {

            log(
                "SpO2 settings skipped: unsupported by ${_status.value.firmware}",
                null
            )
        }

        log(
            "settings applied: continuousHr=${sampling.continuousHr} " +
                "spo2Auto=${sampling.spo2AutoEnabled}/${interval} min " +
                "goal=${profile.stepGoal}",
            null
        )
    }

    private suspend fun ack(
        cmd: ByteArray,
        pred: (ByteArray) -> Boolean,
        what: String
    ) {
        try {
            link.request(
                cmd,
                pred,
                ACK_TIMEOUT_MS
            )
        } catch (e: GattException) {

            if (!link.isReady) {
                throw e
            }

            if (!e.timeout) {
                linkSuspect =
                    e.message ?: "write failed"

                throw e
            }

            log(
                "$what: no echo (${e.message})",
                null
            )
        }
    }

    // ------------------------------------------------------------------
    // Sync
    // ------------------------------------------------------------------

    override suspend fun syncAll(): SyncResult =
        syncMutex.withLock {
            syncAllLocked()
        }

    private suspend fun syncAllLocked(): SyncResult {

        if (!link.isReady) {
            return SyncResult(
                0,
                0,
                0,
                0,
                "not connected"
            )
        }

        _status.update {
            it.copy(
                state = ConnectionState.SYNCING,
                message = "syncing"
            )
        }

        linkSuspect = null

        val errors =
            ArrayList<String>()

        val now =
            clock()

        var stepsN = 0
        var hrN = 0
        var spo2N = 0
        var sleepN = 0

        var error: String? =
            "cancelled"

        try {

            // ----------------------------------------------------------
            // Steps
            // B2 FA -> B2 records -> B2 FD
            // ----------------------------------------------------------

            val steps =
                fetch(
                    Protocol.encFetchSteps(),
                    Protocol.CMD_STEPS,
                    Protocol::isStepsEnd
                )

            stepsN =
                persist(
                    "steps",
                    errors
                ) {

                    val hours =
                        steps.packets
                            .filter {
                                it.size == 18
                            }
                            .mapNotNull { p ->
                                decode(p) {
                                    Protocol
                                        .decStepsRecord(it)
                                        .toStepsHour(zone)
                                }
                            }

                    repo.upsertSteps(hours)

                    hours.size
                }

            if (steps.error != null) {
                errors +=
                    "steps: ${steps.error}"
            } else {
                cursor(
                    SYNC_STEPS,
                    now
                )
            }

            // ----------------------------------------------------------
            // Heart rate
            // F7 FA <since> -> F7 records -> F7 FD
            // ----------------------------------------------------------

            val hrCursor =
                attempt("hr cursor") {
                    repo.lastSyncTime(
                        SYNC_HR
                    )
                }

            val hrSince =
                hrCursor?.let {
                    it - HR_RESYNC_OVERLAP_MS
                }

            val withTs =
                link.features
                    ?.syncTimestamp
                    ?: true

            val hr =
                fetch(
                    Protocol.encFetchHr24Since(
                        hrSince,
                        withTs,
                        zone
                    ),
                    Protocol.CMD_HR24,
                    Protocol::isHr24End
                )

            var newestHr: Long? =
                null

            hrN =
                persist(
                    "hr",
                    errors
                ) {

                    val samples =
                        ArrayList<HrSample>()

                    for (p in hr.packets) {

                        if (p.size != 18) {
                            continue
                        }

                        decode(p) {
                            Protocol.decHr24Record(it)
                        }?.let { recs ->

                            samples +=
                                recs.map {
                                    it.toHrSample(
                                        SampleSource.HISTORY,
                                        zone
                                    )
                                }
                        }
                    }

                    repo.upsertHr(samples)

                    newestHr =
                        samples.maxOfOrNull {
                            it.time
                        }

                    samples.size
                }

            if (hr.error != null) {

                errors +=
                    "hr: ${hr.error}"

            } else {

                newestHr?.let {
                    cursor(
                        SYNC_HR,
                        min(it, now)
                    )
                }
            }

            // ----------------------------------------------------------
            // SpO2
            //
            // Reference Ryze Wave:
            //     34 FA -> history records -> 34 FA FD
            //
            // P32 / RB112UDG:
            //     no response
            // ----------------------------------------------------------

            if (supportsRyzeSpo2()) {

                val spo2 =
                    fetch(
                        Protocol.encFetchSpo2(),
                        Protocol.CMD_SPO2,
                        Protocol::isSpo2End,
                    )

                spo2N =
                    persist(
                        "spo2",
                        errors
                    ) {

                        val samples =
                            ArrayList<Spo2Sample>()

                        for (p in spo2.packets) {

                            if (
                                p.size != 20 ||
                                Protocol.sub(p) != Protocol.FETCH_START
                            ) {
                                continue
                            }

                            decode(p) {
                                Protocol.decSpo2Record(it)
                            }?.let { recs ->

                                samples +=
                                    recs.map {
                                        it.toSpo2Sample(
                                            SampleSource.HISTORY,
                                            zone
                                        )
                                    }
                            }
                        }

                        repo.upsertSpo2(samples)

                        samples.size
                    }

                if (spo2.error != null) {

                    errors +=
                        "spo2: ${spo2.error}"

                } else {

                    cursor(
                        SYNC_SPO2,
                        now
                    )
                }

            } else {

                log(
                    "SpO2 history skipped: unsupported by ${_status.value.firmware}",
                    null
                )
            }

            // ----------------------------------------------------------
            // Sleep
            // 31 01 -> 31 info + 32 stage packets -> 31 02
            // ----------------------------------------------------------

            val sleep =
                fetch(
                    Protocol.encFetchSleep(),
                    Protocol.CMD_SLEEP_INFO,
                    Protocol::isSleepEnd,
                    extraChannel =
                        WatchChannel.DATA,
                    extraOpcode =
                        Protocol.CMD_SLEEP_STAGES,
                )

            sleepN =
                persist(
                    "sleep",
                    errors
                ) {

                    val stages =
                        decodeSleep(
                            sleep.packets
                        )

                    repo.upsertSleep(
                        stages
                    )

                    stages.size
                }

            if (sleep.error != null) {

                errors +=
                    "sleep: ${sleep.error}"

            } else {

                cursor(
                    SYNC_SLEEP,
                    now
                )
            }

            error =
                errors
                    .joinToString("; ")
                    .ifEmpty {
                        null
                    }

            if (error == null) {

                cursor(
                    SYNC_ALL,
                    now
                )
            }

        } finally {

            val finalError =
                error

            _status.update {

                it.copy(
                    state =
                        if (link.isReady) {
                            ConnectionState.CONNECTED
                        } else {
                            it.state
                        },

                    lastSyncTime =
                        if (finalError == null) {
                            now
                        } else {
                            it.lastSyncTime
                        },

                    message =
                        finalError?.let { e ->
                            "sync: $e"
                        },
                )
            }

            log(
                "sync: steps=$stepsN " +
                    "hr=$hrN " +
                    "spo2=$spo2N " +
                    "sleep=$sleepN" +
                    (
                        finalError?.let {
                            " error=$it"
                        } ?: ""
                    ),
                null
            )

            val suspect =
                linkSuspect

            if (
                suspect != null &&
                link.isReady
            ) {

                linkSuspect = null

                log(
                    "link is Ready but writes fail ($suspect): resetting it",
                    null
                )

                attempt(
                    "reset link"
                ) {
                    link.resetLink(
                        "dead link: $suspect"
                    )
                }
            }
        }

        return SyncResult(
            stepsN,
            hrN,
            spo2N,
            sleepN,
            error
        )
    }

    private class Fetch(
        val packets: List<ByteArray>,
        val error: String?
    )

    private suspend fun fetch(
        cmd: ByteArray,
        opcode: Int,
        isEnd: (ByteArray) -> Boolean,
        extraChannel: WatchChannel? = null,
        extraOpcode: Int? = null,
    ): Fetch =
        try {

            Fetch(
                link.collect(
                    cmd,
                    opcode,
                    isEnd,
                    fetchTimeoutMs,
                    WatchChannel.CMD,
                    extraChannel,
                    extraOpcode
                ),
                null
            )

        } catch (e: GattException) {

            log(
                "fetch ${cmd.toHex()} failed after ${e.partial.size} packets: ${e.message}",
                null
            )

            if (
                !e.timeout &&
                link.isReady
            ) {
                linkSuspect =
                    e.message ?: "write failed"
            }

            Fetch(
                e.partial,
                e.message ?: "fetch failed"
            )
        }

    private suspend fun persist(
        what: String,
        errors: MutableList<String>,
        block: suspend () -> Int
    ): Int =
        try {

            block()

        } catch (e: CancellationException) {

            throw e

        } catch (e: Exception) {

            log(
                "persisting $what failed",
                e
            )

            errors +=
                "$what: ${e.message ?: e.javaClass.simpleName}"

            0
        }

    private suspend fun cursor(
        kind: String,
        time: Long
    ) {

        attempt(
            "cursor $kind"
        ) {
            repo.setLastSyncTime(
                kind,
                time
            )
        }
    }

    private fun decodeSleep(
        packets: List<ByteArray>
    ): List<SleepStage> {

        val out =
            ArrayList<SleepStage>()

        var session: LocalDate? =
            null

        for (p in packets) {

            when (
                Protocol.opcode(p)
            ) {

                Protocol.CMD_SLEEP_INFO -> {

                    val info =
                        decode(p) {
                            Protocol.decSleepInfo(it)
                        }

                    if (info != null) {
                        session =
                            info.date
                    }
                }

                Protocol.CMD_SLEEP_STAGES -> {

                    val date =
                        session ?: continue

                    decode(p) {
                        Protocol.decSleepStages(
                            date,
                            it
                        )
                    }?.let { recs ->

                        out +=
                            recs.map {
                                it.toSleepStage(zone)
                            }
                    }
                }
            }
        }

        return out
    }

    private inline fun <T> decode(
        p: ByteArray,
        block: (ByteArray) -> T
    ): T? =
        try {

            block(p)

        } catch (e: RuntimeException) {

            log(
                "cannot decode ${p.toHex()}: ${e.message}",
                null
            )

            null
        }

    // ------------------------------------------------------------------
    // Live HR
    // ------------------------------------------------------------------

    override suspend fun startLiveHr() {

        requireReady()

        link.write(
            Protocol.encHrMode(
                dynamic = true
            )
        )

        delay(
            liveHrWarmupMs
        )

        link.write(
            Protocol.encRtHr(true)
        )
    }

    override suspend fun stopLiveHr() {

        if (!link.isReady) {
            return
        }

        link.write(
            Protocol.encRtHr(false)
        )
    }

    // ------------------------------------------------------------------
    // SpO2 spot test
    // ------------------------------------------------------------------

    override suspend fun spo2SpotTest(): Int? {

        if (!link.isReady) {
            return null
        }

        /*
         * P32 / RB112UDG:
         *
         * Do not transmit 34 11. Testing showed no response from either
         * BLE or the watch UI.
         */
        if (!supportsRyzeSpo2()) {

            log(
                "SpO2 spot test skipped: unsupported by ${_status.value.firmware}",
                null
            )

            return null
        }

        val t0 =
            clock()

        spo2TestStartedAt =
            t0

        try {

            val final =
                link.request(
                    Protocol.encSpo2Test(true),
                    { p ->
                        Matchers.isSpo2Final(
                            p,
                            clock() - t0
                        )
                    },
                    SPO2_TIMEOUT_MS,
                )

            val pct =
                Matchers.spo2Percent(
                    final
                )

            log(
                "SpO2 spot test: ${
                    pct?.let {
                        "$it %"
                    } ?: "failed (${final.toHex()})"
                } after ${(clock() - t0) / 1000} s",
                null
            )

            return pct

        } catch (e: GattException) {

            log(
                "SpO2 spot test: ${e.message}",
                null
            )

            if (
                link.isReady &&
                e.timeout
            ) {

                attempt(
                    "spo2 stop"
                ) {
                    link.write(
                        Protocol.encSpo2Test(false)
                    )
                }
            }

            return null
        }
    }

    // ------------------------------------------------------------------
    // Blood pressure spot test — P32 / legacy UTE / GloryFit
    // ------------------------------------------------------------------

    override suspend fun bloodPressureSpotTest(): BloodPressureReading? {

        if (!link.isReady) {
            return null
        }

        /*
         * Protocol observed on this P32:
         *
         * Start:
         *     C7 11
         *
         * Measuring/no result:
         *     C7 00 FF 00 00
         *
         * Final:
         *     C7 00 00 SS DD
         *
         * SS = systolic, DD = diastolic.
         *
         * Captured examples:
         *
         *     C7 00 00 74 4A
         *              116 74
         *
         *     C7 00 00 75 4A
         *              117 74
         */

        val startedAt =
            clock()

        try {

            val final =
                link.request(
                    byteArrayOf(
                        0xC7.toByte(),
                        0x11.toByte(),
                    ),
                    { p ->
                        isBloodPressureFinal(p)
                    },
                    BP_TIMEOUT_MS,
                )

            val systolic =
                final[3].toInt() and 0xFF

            val diastolic =
                final[4].toInt() and 0xFF

            log(
                "BP spot test: $systolic/$diastolic mmHg " +
                    "after ${(clock() - startedAt) / 1000} s",
                null
            )

            return BloodPressureReading(
                systolic = systolic,
                diastolic = diastolic,
                time = clock(),
            )

        } catch (e: GattException) {

            log(
                "BP spot test: ${e.message}",
                null
            )

            return null

        } finally {

            /*
             * Leave blood-pressure measurement mode after either a
             * completed result or timeout.
             *
             * Failure to send the cleanup command must not discard a
             * result that has already been received.
             */
            if (link.isReady) {

                attempt(
                    "bp stop"
                ) {

                    link.write(
                        byteArrayOf(
                            0xC7.toByte(),
                            0x00.toByte(),
                        )
                    )
                }
            }
        }
    }

    /**
     * Match a completed P32 C7 blood-pressure packet.
     *
     * C7 00 FF 00 00 is an in-progress/no-result packet and therefore
     * intentionally does not satisfy this matcher.
     */
    private fun isBloodPressureFinal(
        p: ByteArray
    ): Boolean {

        if (p.size < 5) {
            return false
        }

        if (
            (p[0].toInt() and 0xFF) !=
            0xC7
        ) {
            return false
        }

        if (
            (p[1].toInt() and 0xFF) !=
            0x00
        ) {
            return false
        }

        if (
            (p[2].toInt() and 0xFF) !=
            0x00
        ) {
            return false
        }

        val systolic =
            p[3].toInt() and 0xFF

        val diastolic =
            p[4].toInt() and 0xFF

        /*
         * These are protocol sanity checks, not medical ranges.
         * We merely reject an empty result.
         */
        return systolic in 1..255 &&
            diastolic in 1..255
    }

    // ------------------------------------------------------------------
    // Workouts
    // ------------------------------------------------------------------

    override suspend fun startWorkout(
        sportType: Int
    ) {

        requireReady()

        this.sportType =
            sportType

        expectEcho(
            Protocol.SPORT_START
        )

        link.request(
            Protocol.encSportControl(
                Protocol.SPORT_START,
                sportType,
                1
            ),
            Matchers.isSportEcho(
                Protocol.SPORT_START
            ),
            CONTROL_TIMEOUT_MS,
        )

        val state =
            runCatching {
                queryWorkout()
            }.getOrNull()

        when {

            state == null -> {
                log(
                    "watch did not answer the sport query after start (type $sportType)",
                    null
                )
            }

            state.state != 0 &&
                state.sportType == sportType -> {

                log(
                    "watch confirms sport $sportType open (${SportTypes.name(sportType)})",
                    null
                )
            }

            else -> {

                log(
                    "watch reports state=${state.state} type=${state.sportType} after starting $sportType",
                    null
                )
            }
        }
    }

    override suspend fun queryWorkout(): SportState? {

        requireReady()

        val reply =
            link.request(
                Protocol.encSportQuery(),
                {
                    Protocol.decSportState(it) != null
                },
                QUERY_TIMEOUT_MS
            )

        return Protocol.decSportState(
            reply
        )
    }

    override suspend fun updateWorkout(
        durationSeconds: Int,
        distanceMeters: Double,
        paceSecPerKm: Double,
        calories: Int
    ) {

        requireReady()

        val cmd =
            Protocol.encSportUpdate(
                sportType,
                durationSeconds,
                calories,
                distanceMeters,
                paceSecPerKm
            )

        try {

            link.request(
                cmd,
                Matchers.isSportEcho(
                    Protocol.SPORT_UPDATE
                ),
                UPDATE_ECHO_TIMEOUT_MS
            )

        } catch (e: GattException) {

            if (
                !link.isReady ||
                !e.timeout
            ) {
                throw e
            }

            log(
                "FD 44 update not echoed within $UPDATE_ECHO_TIMEOUT_MS ms",
                null
            )
        }
    }

    override suspend fun pauseWorkout() {

        requireReady()

        expectEcho(
            Protocol.SPORT_PAUSE
        )

        link.request(
            Protocol.encSportControl(
                Protocol.SPORT_PAUSE,
                sportType,
                1
            ),
            Matchers.isSportEcho(
                Protocol.SPORT_PAUSE
            ),
            CONTROL_TIMEOUT_MS
        )
    }

    override suspend fun resumeWorkout() {

        requireReady()

        expectEcho(
            Protocol.SPORT_RESUME
        )

        link.request(
            Protocol.encSportControl(
                Protocol.SPORT_RESUME,
                sportType,
                1
            ),
            Matchers.isSportEcho(
                Protocol.SPORT_RESUME
            ),
            CONTROL_TIMEOUT_MS
        )
    }

    override suspend fun stopWorkout() {

        requireReady()

        expectEcho(
            Protocol.SPORT_STOP
        )

        link.request(
            Protocol.encSportControl(
                Protocol.SPORT_STOP,
                sportType,
                1
            ),
            Matchers.isSportEcho(
                Protocol.SPORT_STOP
            ),
            CONTROL_TIMEOUT_MS
        )
    }

    private fun expectEcho(
        state: Int
    ) {

        val now =
            clock()

        expectedEchoes.removeAll {
            now - it.at >
                ECHO_WINDOW_MS
        }

        expectedEchoes.add(
            ExpectedEcho(
                state,
                now
            )
        )
    }

    private fun consumeExpectedEcho(
        state: Int,
        now: Long
    ): Boolean {

        val match =
            expectedEchoes.firstOrNull {
                it.state == state &&
                    now - it.at <=
                    ECHO_WINDOW_MS
            }

        expectedEchoes.removeAll {
            now - it.at >
                ECHO_WINDOW_MS ||
                it === match
        }

        return match != null
    }

    fun injectEvent(
        e: WatchEvent
    ) {

        _events.tryEmit(e)
    }

    fun injectHr(
        bpm: Int
    ) {

        _liveHr.tryEmit(
            HrSample(
                System.currentTimeMillis(),
                bpm,
                SampleSource.WORKOUT
            )
        )
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private val notifyMutex =
        Mutex()

    override suspend fun sendNotification(
        type: Int,
        text: String
    ): Boolean {

        if (!link.isReady) {

            log(
                "notification skipped: watch not connected",
                null
            )

            return false
        }

        val chunks =
            Protocol.encNotification(
                type,
                text
            )

        if (chunks.isEmpty()) {

            log(
                "notification skipped: nothing left to send after sanitising",
                null
            )

            return false
        }

        notifyMutex.withLock {

            requireReady()

            for (
                (idx, chunk)
                in chunks.withIndex()
            ) {

                link.request(
                    chunk,
                    Matchers.isNotifyAck(idx),
                    NOTIFY_ACK_TIMEOUT_MS
                )
            }

            link.request(
                Protocol.encNotificationEnd(),
                Matchers::isNotifyEnd,
                NOTIFY_ACK_TIMEOUT_MS
            )

            runCatching {
                link.write(
                    Protocol.encNotifyVibrate()
                )
            }
        }

        log(
            "notification sent: type $type, ${chunks.size} chunks",
            null
        )

        return true
    }

    // ------------------------------------------------------------------
    // Watch-face capabilities — read-only 26 01 query
    // ------------------------------------------------------------------

    override suspend fun getWatchFaceConfig(): WatchFaceConfig? {

        if (!link.isReady) {
            return null
        }

        return try {

            val reply =
                link.request(
                    Protocol.encWatchFaceConfigQuery(),
                    { p ->
                        p.size >= 15 &&
                            Protocol.opcode(p) == Protocol.CMD_WATCH_FACE &&
                            Protocol.sub(p) == 0x01
                    },
                    QUERY_TIMEOUT_MS
                )

            val config =
                Protocol.decWatchFaceConfig(reply)

            WatchFaceConfig(
                dialNumber = config.dialNumber,
                width = config.width,
                height = config.height,
                screenType = config.screenType,
                maxDataSize = config.maxDataSize,
                compatibleLevel = config.compatibleLevel,
                cornerAngle = config.cornerAngle,
            ).also {
                log(
                    "watch-face config: dial=${it.dialNumber} " +
                        "${it.width}x${it.height} screenType=${it.screenType} " +
                        "maxDataSize=${it.maxDataSize} compatibleLevel=${it.compatibleLevel} " +
                        "cornerAngle=${it.cornerAngle}",
                    null
                )
            }

        } catch (e: GattException) {

            log(
                "watch-face config: ${e.message}",
                null
            )

            null

        } catch (e: RuntimeException) {

            log(
                "watch-face config decode failed: ${e.message}",
                e
            )

            null
        }
    }

    /**
     * Installs a classic GloryFit online-dial `.BIN`.
     *
     * The file is fully validated before `26 02` is sent. Data is written to 34F1 as
     * `27 <section> <payload>` packets. The P32 reports flow control on 33F2 with
     * `26 03 xx`; GloryFit flow-controls at 4096-byte flash erase boundaries.
     * Each boundary-crossing group is sent with its waiter already registered.
     */
    override suspend fun uploadWatchFace(
        data: ByteArray,
        onProgress: (WatchFaceUploadProgress) -> Unit,
    ): Boolean = syncMutex.withLock {
        requireReady()

        Protocol.validateWatchFaceBin(data)

        val config = getWatchFaceConfig()
            ?: throw GattException("watch did not return 26 01 watch-face configuration")

        require(data.size.toLong() <= config.maxDataSize) {
            "watch-face BIN is ${data.size} bytes; watch limit is ${config.maxDataSize} bytes"
        }

        val ready = link.state.value as? LinkState.Ready
            ?: throw GattException("watch is not connected")

        // ATT writes carry at most MTU - 3 bytes. The 27 packet consumes three
        // more bytes for opcode + 16-bit section index.
        val payloadBytes = (ready.mtu - 6).coerceAtLeast(1)
        val sectionCount = (data.size + payloadBytes - 1) / payloadBytes
        require(sectionCount <= 0x10000) {
            "watch-face requires $sectionCount sections; protocol limit is 65536"
        }

        log(
            "watch-face upload: ${data.size} bytes, mtu=${ready.mtu}, " +
                "payload=$payloadBytes, sections=$sectionCount",
            null
        )

        link.request(
            Protocol.encWatchFacePrepare(),
            Matchers.isWatchFaceControl(0x02),
            WATCH_FACE_CONTROL_TIMEOUT_MS,
            WatchChannel.CMD,
            WatchChannel.CMD,
        )

        var section = 0
        var sent = 0
        var retries = 0

        onProgress(WatchFaceUploadProgress(0, data.size.toLong()))

        /*
         * GloryFit does not wait after a fixed number of sections.  Its
         * currErase/lastErase flow control is tied to 4096-byte flash erase
         * regions.  Keep sending until the data stream crosses the next 4 KiB
         * boundary; the packet that crosses it causes the watch to emit
         * `26 03 04`.  Register the waiter before sending that group so the
         * fast acknowledgement cannot race us.
         *
         * With the P32's MTU 247 this is 241 BIN bytes per section, therefore
         * the first acknowledgement is after section 16 (17 packets / 4097
         * BIN bytes), not after 16 packets.  Waiting after exactly 16 packets
         * leaves the watch at 3856 bytes and produces no acknowledgement.
         */
        while (sent < data.size) {
            val eraseBlock = sent / WATCH_FACE_ERASE_BLOCK_BYTES
            val nextBoundary =
                (eraseBlock + 1L) * WATCH_FACE_ERASE_BLOCK_BYTES.toLong()

            // If the remainder never reaches another erase boundary, there is
            // no 26 03 04 to wait for.  Stream the tail and then send finish.
            if (data.size.toLong() <= nextBoundary) {
                while (sent < data.size) {
                    val end = min(sent + payloadBytes, data.size)
                    val payload = data.copyOfRange(sent, end)
                    link.write(
                        Protocol.encWatchFaceDataSection(section, payload),
                        WatchChannel.DATA,
                    )
                    sent = end
                    section += 1
                    onProgress(
                        WatchFaceUploadProgress(
                            bytesSent = sent.toLong(),
                            totalBytes = data.size.toLong(),
                        )
                    )
                }
                break
            }

            val status = link.waitForDuring(
                action = {
                    // Send through the first section whose payload takes the
                    // stream into the next 4096-byte erase region.
                    while (sent < data.size && sent.toLong() <= nextBoundary) {
                        val end = min(sent + payloadBytes, data.size)
                        val payload = data.copyOfRange(sent, end)
                        link.write(
                            Protocol.encWatchFaceDataSection(section, payload),
                            WatchChannel.DATA,
                        )
                        sent = end
                        section += 1
                        onProgress(
                            WatchFaceUploadProgress(
                                bytesSent = sent.toLong(),
                                totalBytes = data.size.toLong(),
                            )
                        )
                    }
                },
                pred = { p ->
                    p.size >= 3 &&
                        Protocol.opcode(p) == Protocol.CMD_WATCH_FACE &&
                        Protocol.sub(p) == 0x03
                },
                timeoutMs = WATCH_FACE_FLOW_TIMEOUT_MS,
                channel = WatchChannel.CMD,
            )

            when (status.getOrNull(2)?.toInt()?.and(0xFF)) {
                0x04 -> {
                    retries = 0
                }

                0x03 -> {
                    val requested = Matchers.watchFaceResumeIndex(status)
                        ?: throw GattException(
                            "watch requested malformed watch-face resume: ${Protocol.hex(status)}"
                        )
                    require(requested < sectionCount) {
                        "watch requested invalid watch-face section $requested of $sectionCount"
                    }
                    retries += 1
                    if (retries > WATCH_FACE_MAX_RESUME_RETRIES) {
                        throw GattException("watch-face transfer exceeded resume retry limit")
                    }
                    section = requested
                    sent = requested * payloadBytes
                    onProgress(
                        WatchFaceUploadProgress(
                            bytesSent = sent.toLong(),
                            totalBytes = data.size.toLong(),
                        )
                    )
                }

                0x01 -> throw GattException("watch rejected watch-face transfer")
                0x02 -> throw GattException("watch reported watch-face CRC/data failure")
                0x00 -> {
                    // Some firmware may report success immediately after the final data block.
                    return@withLock true
                }

                else -> throw GattException(
                    "unknown watch-face transfer status: ${Protocol.hex(status)}"
                )
            }
        }

        val finish = link.request(
            Protocol.encWatchFaceFinish(),
            Matchers::isWatchFaceTransferSuccess,
            WATCH_FACE_FINISH_TIMEOUT_MS,
            WatchChannel.CMD,
            WatchChannel.CMD,
        )

        log("watch-face upload complete: ${Protocol.hex(finish)}", null)
        true
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    override suspend fun findWatch() {

        requireReady()

        link.write(
            Protocol.encFindWatch()
        )
    }

    override suspend fun readBattery(): Int? {

        if (!link.isReady) {
            return null
        }

        return try {

            readBatteryLocked()

        } catch (e: GattException) {

            log(
                "battery: ${e.message}",
                null
            )

            null
        }
    }

    private suspend fun readBatteryLocked(): Int? {

        val reply =
            link.request(
                Protocol.encBattery(),
                Protocol.CMD_BATTERY,
                WatchLink.DEFAULT_TIMEOUT_MS
            )

        val b =
            decode(reply) {
                Protocol.decBattery(it)
            } ?: return null

        _status.update {
            it.copy(
                batteryPercent = b.percent,
                charging = b.charging
            )
        }

        return b.percent
    }

    private suspend fun readVersion(): String? {

        val reply =
            link.request(
                Protocol.encVersion(),
                Protocol.CMD_VERSION,
                WatchLink.DEFAULT_TIMEOUT_MS
            )

        val v =
            Protocol
                .decVersion(reply)
                .ifEmpty {
                    return null
                }

        _status.update {
            it.copy(
                firmware = v
            )
        }

        return v
    }

    private fun requireReady() {

        if (!link.isReady) {

            throw GattException(
                "watch not connected"
            )
        }
    }

    private suspend fun <T> attempt(
        what: String,
        block: suspend () -> T
    ): T? =
        try {

            block()

        } catch (e: CancellationException) {

            throw e

        } catch (e: Exception) {

            log(
                "$what failed: ${e.message ?: e.javaClass.simpleName}",
                if (e is GattException) {
                    null
                } else {
                    e
                }
            )

            null
        }

    // ------------------------------------------------------------------
    // Incoming packets
    // ------------------------------------------------------------------

    private suspend fun onPacket(
        raw: RawPacket
    ) {

        val now =
            raw.time

        when (
            val p =
                Packet.parse(
                    raw.data
                )
        ) {

            is Packet.LiveHr -> {

                if (p.bpm > 0) {

                    val s =
                        HrSample(
                            now,
                            p.bpm,
                            SampleSource.LIVE
                        )

                    _liveHr.tryEmit(s)

                    if (
                        now - lastLivePersist >=
                        LIVE_HR_PERSIST_EVERY_MS
                    ) {

                        lastLivePersist =
                            now

                        attempt(
                            "persist live hr"
                        ) {

                            repo.upsertHr(
                                listOf(s)
                            )
                        }
                    }
                }
            }

            is Packet.SportRt -> {

                if (p.hr > 0) {

                    _liveHr.tryEmit(
                        HrSample(
                            now,
                            p.hr,
                            SampleSource.WORKOUT
                        )
                    )
                }

                _events.tryEmit(
                    WatchEvent.WorkoutRealtime(
                        p.sportType,
                        p.steps,
                        p.calories,
                        p.distanceMeters
                    )
                )
            }

            is Packet.SportControlEcho -> {

                onSportControlEcho(
                    p,
                    raw.consumed,
                    now
                )
            }

            is Packet.HrAutoSample -> {

                attempt(
                    "persist auto hr"
                ) {

                    repo.upsertHr(
                        listOf(
                            p.toHrSample(zone)
                        )
                    )
                }
            }

            is Packet.HrSummary -> {

                _events.tryEmit(
                    p.toEvent(zone)
                )
            }

            /*
             * Keep receiving SpO2 packets.
             *
             * We only disable active SpO2 requests for RB112UDG.
             * If the P32 ever pushes a valid packet itself, we still
             * want to record it.
             */
            is Packet.Spo2Test -> {

                onSpo2Packet(
                    p,
                    now
                )
            }

            is Packet.Steps -> {

                if (p.realtime) {

                    val hour =
                        p.record
                            .toStepsHour(zone)

                    attempt(
                        "persist realtime steps"
                    ) {

                        if (
                            realtimeStepsSupersedeStored(
                                hour
                            )
                        ) {

                            repo.upsertSteps(
                                listOf(hour)
                            )

                        } else {

                            log(
                                "ignored realtime steps ${hour.total} below the stored total for hour ${hour.hourStart}",
                                null
                            )
                        }
                    }

                    _events.tryEmit(
                        p.toEvent(zone)
                    )

                } else if (
                    !raw.consumed
                ) {

                    attempt(
                        "persist foreign steps record"
                    ) {

                        repo.upsertSteps(
                            listOf(
                                p.record
                                    .toStepsHour(zone)
                            )
                        )
                    }
                }
            }

            is Packet.Hr24 -> {

                if (
                    !raw.consumed &&
                    p.samples.isNotEmpty()
                ) {

                    attempt(
                        "persist foreign hr record"
                    ) {

                        repo.upsertHr(
                            p.samples.map {
                                it.toHrSample(
                                    SampleSource.HISTORY,
                                    zone
                                )
                            }
                        )
                    }
                }
            }

            is Packet.Spo2History -> {

                if (
                    !raw.consumed &&
                    p.samples.isNotEmpty()
                ) {

                    attempt(
                        "persist foreign spo2 record"
                    ) {

                        repo.upsertSpo2(
                            p.samples.map {
                                it.toSpo2Sample(
                                    SampleSource.HISTORY,
                                    zone
                                )
                            }
                        )
                    }
                }
            }

            is Packet.SleepInfo -> {

                if (!raw.consumed) {

                    pushedSleepDate =
                        p.sessionDate
                }
            }

            is Packet.SleepStages -> {

                if (!raw.consumed) {

                    val date =
                        pushedSleepDate

                    if (date != null) {

                        decode(
                            raw.data
                        ) {

                            Protocol.decSleepStages(
                                date,
                                it
                            )

                        }?.let { recs ->

                            attempt(
                                "persist foreign sleep stages"
                            ) {

                                repo.upsertSleep(
                                    recs.map {
                                        it.toSleepStage(
                                            zone
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            is Packet.Battery -> {

                _status.update {

                    it.copy(
                        batteryPercent =
                            p.percent,
                        charging =
                            p.charging
                    )
                }
            }

            is Packet.Version -> {

                if (
                    !p.dsp &&
                    p.version.isNotEmpty()
                ) {

                    _status.update {

                        it.copy(
                            firmware =
                                p.version
                        )
                    }
                }
            }

            is Packet.FindPhone -> {

                _events.tryEmit(
                    WatchEvent.FindPhone(
                        p.start
                    )
                )
            }

            is Packet.Unknown -> {

                if (!raw.consumed) {

                    _events.tryEmit(
                        WatchEvent.Raw(
                            raw.channel.notifyName,
                            p.raw
                        )
                    )
                }
            }

            else -> Unit
        }
    }

    private suspend fun realtimeStepsSupersedeStored(
        hour: StepsHour
    ): Boolean {

        val dayStart =
            Instant
                .ofEpochMilli(
                    hour.hourStart
                )
                .atZone(zone)
                .toLocalDate()
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()

        val stored =
            repo
                .stepsForDay(dayStart)
                .first()
                .firstOrNull {
                    it.hourStart ==
                        hour.hourStart
                }
                ?: return true

        return hour.total >=
            stored.total
    }

    private fun onSportControlEcho(
        p: Packet.SportControlEcho,
        consumed: Boolean,
        now: Long
    ) {

        if (
            p.state ==
            Protocol.SPORT_UPDATE
        ) {
            return
        }

        val expected =
            consumeExpectedEcho(
                p.state,
                now
            )

        if (
            consumed ||
            expected
        ) {
            return
        }

        val action =
            when (p.state) {

                Protocol.SPORT_PAUSE ->
                    WorkoutControlAction.PAUSE

                Protocol.SPORT_RESUME ->
                    WorkoutControlAction.RESUME

                Protocol.SPORT_STOP ->
                    WorkoutControlAction.STOP

                Protocol.SPORT_START ->
                    WorkoutControlAction.START

                else ->
                    return
            }

        log(
            "watch-originated workout control $action (${p.raw})",
            null
        )

        _events.tryEmit(
            WatchEvent.WorkoutControl(
                action
            )
        )
    }

    private suspend fun onSpo2Packet(
        p: Packet.Spo2Test,
        now: Long
    ) {

        val r =
            p.result

        if (
            r.phase !=
            Spo2Phase.FINAL
        ) {
            return
        }

        val started =
            spo2TestStartedAt

        val testing =
            started != 0L &&
                now - started in
                0..(
                    SPO2_TIMEOUT_MS +
                        5_000L
                )

        if (
            r.spo2 == null &&
            testing &&
            now - started <
            Matchers.SPO2_BOGUS_WINDOW_MS
        ) {
            return
        }

        val pct =
            r.spo2

        if (
            pct != null &&
            pct in 1..100
        ) {

            attempt(
                "persist spo2"
            ) {

                repo.upsertSpo2(
                    listOf(
                        Spo2Sample(
                            now,
                            pct,
                            if (testing) {
                                SampleSource.LIVE
                            } else {
                                SampleSource.AUTO
                            }
                        )
                    )
                )
            }
        }

        _events.tryEmit(
            WatchEvent.Spo2Result(
                now,
                pct
            )
        )
    }

    companion object {

        const val SYNC_ALL =
            "watch"

        const val SYNC_STEPS =
            "steps"

        const val SYNC_HR =
            "hr"

        const val SYNC_SPO2 =
            "spo2"

        const val SYNC_SLEEP =
            "sleep"

        const val LIVE_HR_WARMUP_MS =
            1_500L

        const val LIVE_HR_PERSIST_EVERY_MS =
            10_000L

        const val HR_RESYNC_OVERLAP_MS =
            2 * 60 * 60_000L

        const val ACK_TIMEOUT_MS =
            5_000L

        const val QUERY_TIMEOUT_MS =
            2_000L

        const val WATCH_FACE_CONTROL_TIMEOUT_MS =
            8_000L

        const val WATCH_FACE_FLOW_TIMEOUT_MS =
            8_000L

        const val WATCH_FACE_FINISH_TIMEOUT_MS =
            15_000L

        const val WATCH_FACE_ERASE_BLOCK_BYTES =
            4_096

        const val WATCH_FACE_MAX_RESUME_RETRIES =
            3

        const val CONTROL_TIMEOUT_MS =
            8_000L

        const val UPDATE_ECHO_TIMEOUT_MS =
            2_000L

        const val ECHO_WINDOW_MS =
            2_000L

        const val NOTIFY_ACK_TIMEOUT_MS =
            3_000L

        const val SPO2_TIMEOUT_MS =
            90_000L

        const val BP_TIMEOUT_MS =
            90_000L
    }
}
