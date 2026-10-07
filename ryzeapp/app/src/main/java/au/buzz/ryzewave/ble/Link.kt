package au.buzz.ryzewave.ble

import au.buzz.ryzewave.protocol.Features
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pure-Kotlin part of the BLE layer (no Android imports so it can be unit tested): GATT channels, raw packets,
 * link states, the [WatchLink] transport contract, the reply matching shared by every transport
 * ([BaseWatchLink]: `request` / `collect` / `waitFor` like ryzewave/client.py) and the reconnect backoff.
 * `WatchGatt` is the Android implementation; the unit tests use a fake link built on the same base class.
 */

/** The two notify/write pairs of the UTE protocol (see docs/PROTOCOL.md §1). */
enum class WatchChannel(val writeName: String, val notifyName: String, val writeUuid: UUID, val notifyUuid: UUID) {
    CMD("33F1", "33F2", uuid16("33f1"), uuid16("33f2")),
    DATA("34F1", "34F2", uuid16("34f1"), uuid16("34f2"));

    companion object {
        fun forNotifyUuid(uuid: UUID): WatchChannel? = entries.firstOrNull { it.notifyUuid == uuid }
        fun forWriteUuid(uuid: UUID): WatchChannel? = entries.firstOrNull { it.writeUuid == uuid }
    }
}

/** 16-bit Bluetooth SIG UUID to the full 128-bit form. */
fun uuid16(short: String): UUID = UUID.fromString("0000${short.lowercase(Locale.ROOT)}-0000-1000-8000-00805f9b34fb")

/** Client Characteristic Configuration Descriptor. */
val CCCD_UUID: UUID = uuid16("2902")

/**
 * One notification from the watch. [consumed] is true when a pending `request`/`collect`/`waitFor` took it; the
 * API layer still sees consumed packets on the raw flow (so pushes that arrive mid-fetch are not lost and the
 * battery / version replies also update the status).
 */
class RawPacket(val channel: WatchChannel, val data: ByteArray, val time: Long, val consumed: Boolean) {
    val opcode: Int get() = if (data.isEmpty()) -1 else data[0].toInt() and 0xFF
    val sub: Int get() = if (data.size < 2) -1 else data[1].toInt() and 0xFF
    val hex: String get() = data.toHex()
    override fun toString(): String = "RX ${channel.notifyName} ${data.toHex()}${if (consumed) " (consumed)" else ""}"
}

/** State of the GATT link as seen by [WatchLink]. */
sealed class LinkState {
    /** [status] is the GATT status of the last disconnect (0 = clean / never connected). */
    data class Disconnected(val status: Int, val message: String? = null) : LinkState()

    data class Connecting(val mac: String, val attempt: Int) : LinkState()

    /**
     * Link is up, notifications enabled, feature bitmap read. [generation] increases on every successful
     * connect so collectors of a StateFlow never miss a reconnect; [reconnected] is true when the link was
     * restored by the automatic backoff loop rather than by an explicit `connect()`.
     */
    data class Ready(val mac: String, val mtu: Int, val reconnected: Boolean, val generation: Int) : LinkState()
}

/**
 * Any failure of a GATT operation. [partial] carries the packets a `collect` had gathered before it failed;
 * [timeout] is true when the failure is a missing reply rather than a broken link.
 */
class GattException(
    message: String,
    val partial: List<ByteArray> = emptyList(),
    cause: Throwable? = null,
    val timeout: Boolean = false,
) : Exception(message, cause)

/** Exponential reconnect backoff: 2 s, 4 s, 8 s … capped at 60 s. */
object Backoff {
    const val BASE_MS = 2_000L
    const val MAX_MS = 60_000L

    fun delayMs(attempt: Int): Long {
        val n = (attempt - 1).coerceIn(0, 16)
        val v = BASE_MS shl n
        return if (v <= 0 || v > MAX_MS) MAX_MS else v
    }
}

/** Reply/end-of-fetch predicates, ported from ryzewave/client.py. */
object Matchers {
    const val OP_SLEEP_INFO = 0x31
    const val OP_SPO2 = 0x34
    const val OP_SPORT = 0xFD
    const val FETCH_START = 0xFA
    const val FETCH_END = 0xFD

    private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

    /** `xx FD nn` — end of a B2 / F7 fetch. */
    fun isFetchEnd(p: ByteArray): Boolean = p.size == 3 && p.u8(1) == FETCH_END

    /** `34 FA FD nn` — end of the SpO2 fetch. */
    fun isSpo2FetchEnd(p: ByteArray): Boolean =
        p.size >= 3 && p.u8(1) == FETCH_START && p.u8(2) == FETCH_END

    /** `31 02` — end of the sleep fetch (the `32` stage packets on 34F2 never end it). */
    fun isSleepEnd(p: ByteArray): Boolean =
        p.size >= 2 && p.u8(0) == OP_SLEEP_INFO && p.u8(1) == 0x02

    /** `31 01 yyyy MM dd n` — the sleep session date (the morning). */
    fun sleepSessionDate(p: ByteArray): LocalDate? {
        if (p.size < 7 || p.u8(0) != OP_SLEEP_INFO || p.u8(1) != 0x01) return null
        val year = (p.u8(2) shl 8) or p.u8(3)
        return try {
            LocalDate.of(year, p.u8(4), p.u8(5))
        } catch (e: RuntimeException) {
            null
        }
    }

    /**
     * Final SpO2 spot-test packet `34 00 <err> <pct>`. The watch emits a bogus `34 00 FF FF` within ~0.3 s of
     * the `34 11` ack; that one is ignored while [elapsedMs] < [SPO2_BOGUS_WINDOW_MS]. A later `34 00 FF FF` is a
     * real failure. Only opcode 0x34 packets qualify.
     */
    fun isSpo2Final(p: ByteArray, elapsedMs: Long): Boolean {
        if (p.size < 4 || p.u8(0) != OP_SPO2 || p.u8(1) != 0x00) return false
        if (p.u8(2) == 0xFF && elapsedMs < SPO2_BOGUS_WINDOW_MS) return false
        return true
    }

    const val SPO2_BOGUS_WINDOW_MS = 3_000L

    /** Percentage from a final `34 00 00 <pct>`; null for a failure packet. */
    fun spo2Percent(p: ByteArray): Int? =
        if (p.size >= 4 && p.u8(0) == OP_SPO2 && p.u8(1) == 0x00 && p.u8(2) == 0x00) p.u8(3) else null

    /** The watch echoes `FD <state> <type> <ivl>` for every sport control command. */
    fun isSportEcho(state: Int): (ByteArray) -> Boolean =
        { p -> p.size >= 2 && p.u8(0) == OP_SPORT && p.u8(1) == (state and 0xFF) }

    /** `C5 <idx>`: the watch acknowledging notification chunk [idx] (captures/bridge_20260905_073950.txt). */
    fun isNotifyAck(idx: Int): (ByteArray) -> Boolean =
        { p -> p.size >= 2 && p.u8(0) == 0xC5 && p.u8(1) == (idx and 0xFF) }

    /** `C5 FD <type> <total>`: the watch acknowledging the end of a notification. */
    fun isNotifyEnd(p: ByteArray): Boolean =
        p.size >= 2 && p.u8(0) == 0xC5 && p.u8(1) == 0xFD

    fun opcodeIs(opcode: Int): (ByteArray) -> Boolean =
        { p -> p.isNotEmpty() && p.u8(0) == (opcode and 0xFF) }

    /** Opcode and sub-code (byte 1) both match — for the `34 03` / `34 04` / `F7 01` style echoes. */
    fun opcodeAndSub(opcode: Int, sub: Int): (ByteArray) -> Boolean =
        { p -> p.size >= 2 && p.u8(0) == (opcode and 0xFF) && p.u8(1) == (sub and 0xFF) }

    // ------------------------------------------------------------------------------------------
    // Classic GloryFit watch-face control protocol (opcode 0x26).

    /** Any classic GloryFit watch-face control packet: `26 ...`. */
    fun isWatchFaceControl(p: ByteArray): Boolean =
        p.isNotEmpty() && p.u8(0) == 0x26

    /** `26 <sub>` watch-face control response. */
    fun isWatchFaceControl(sub: Int): (ByteArray) -> Boolean =
        { p ->
            p.size >= 2 &&
                p.u8(0) == 0x26 &&
                p.u8(1) == (sub and 0xFF)
        }

    /** `26 03 <status>` watch-face transfer state. */
    fun isWatchFaceTransferStatus(status: Int): (ByteArray) -> Boolean =
        { p ->
            p.size >= 3 &&
                p.u8(0) == 0x26 &&
                p.u8(1) == 0x03 &&
                p.u8(2) == (status and 0xFF)
        }

    /** `26 03 00` — watch-face transfer completed successfully. */
    fun isWatchFaceTransferSuccess(p: ByteArray): Boolean =
        isWatchFaceTransferStatus(0x00)(p)

    /** `26 03 04` — watch-face transfer flow-control/continue state. */
    fun isWatchFaceTransferContinue(p: ByteArray): Boolean =
        isWatchFaceTransferStatus(0x04)(p)

    /**
     * `26 03 03 <index-hi> <index-lo>` — watch requests that transfer resume/retry
     * from the supplied big-endian section index.
     */
    fun watchFaceResumeIndex(p: ByteArray): Int? {
        if (
            p.size < 5 ||
            p.u8(0) != 0x26 ||
            p.u8(1) != 0x03 ||
            p.u8(2) != 0x03
        ) {
            return null
        }

        return (p.u8(3) shl 8) or p.u8(4)
    }
}

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        sb.append(String.format(Locale.ROOT, "%02x", b.toInt() and 0xFF))
    }
    return sb.toString()
}

fun String.hexToBytes(): ByteArray {
    val s = replace(" ", "").replace(":", "")
    require(s.length % 2 == 0) { "odd hex length" }
    return ByteArray(s.length / 2) {
        i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte()
    }
}

fun Int.toHex2(): String =
    String.format(Locale.ROOT, "%02X", this and 0xFF)

// ---------------------------------------------------------------------------------------------- transport contract

/**
 * The watch transport as the API layer sees it: one GATT operation at a time, replies matched by opcode or
 * predicate, every notification on [packets]. Implemented by `WatchGatt` (Android) and by the test fake.
 */
interface WatchLink {
    val state: StateFlow<LinkState>

    /** Every notification from 33F2 / 34F2, consumed or not, in arrival order. */
    val packets: SharedFlow<RawPacket>

    /** The 20-byte feature bitmap read from 33F1 on connect (kept across reconnects), null before the first read. */
    val features: Features?

    val isReady: Boolean
        get() = state.value is LinkState.Ready

    /** Connects (discover, MTU 247, notifications, feature read) or throws [GattException]. Idempotent when up. */
    suspend fun connect(mac: String)

    /** Drops the link and stops any reconnect loop. */
    suspend fun disconnect()

    /**
     * Drops a link that is Ready but no longer answers (writes fail "not connected") and lets the transport's
     * reconnect logic restore it. Transports without a reconnect loop may ignore it.
     */
    suspend fun resetLink(reason: String) {}

    /** Writes one packet (write-without-response when the characteristic supports it) and waits for the write callback. */
    suspend fun write(
        cmd: ByteArray,
        channel: WatchChannel = WatchChannel.CMD,
    )

    /** Waits for the next packet on [channel] matching [pred] without writing anything. */
    suspend fun waitFor(
        pred: (ByteArray) -> Boolean,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        channel: WatchChannel = WatchChannel.CMD,
    ): ByteArray

    /**
     * Registers a reply waiter first, then runs [action], then returns the first matching packet.
     *
     * [BaseWatchLink] overrides this atomically so fast watch-face flow-control notifications cannot
     * arrive in the gap between a DATA write burst and a subsequent `waitFor`.
     * The default keeps third-party/test implementations source-compatible.
     */
    suspend fun waitForDuring(
        action: suspend () -> Unit,
        pred: (ByteArray) -> Boolean,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        channel: WatchChannel = WatchChannel.CMD,
    ): ByteArray {
        action()
        return waitFor(pred, timeoutMs, channel)
    }

    /** Writes [cmd] and returns the first packet on [replyChannel] whose opcode is [opcode]. */
    suspend fun request(
        cmd: ByteArray,
        opcode: Int,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        channel: WatchChannel = WatchChannel.CMD,
        replyChannel: WatchChannel = channel,
    ): ByteArray

    /** Writes [cmd] and returns the first packet on [replyChannel] matching [pred]. */
    suspend fun request(
        cmd: ByteArray,
        pred: (ByteArray) -> Boolean,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        channel: WatchChannel = WatchChannel.CMD,
        replyChannel: WatchChannel = channel,
    ): ByteArray

    /**
     * Writes a fetch command and gathers every packet with opcode [opcode] (and [extraOpcode] on [extraChannel],
     * for the `32` sleep stages on 34F2) until [isEnd] accepts one. Throws [GattException] with the partial list
     * on timeout or link loss.
     */
    suspend fun collect(
        cmd: ByteArray,
        opcode: Int,
        isEnd: (ByteArray) -> Boolean,
        timeoutMs: Long = FETCH_TIMEOUT_MS,
        channel: WatchChannel = WatchChannel.CMD,
        extraChannel: WatchChannel? = null,
        extraOpcode: Int? = null,
    ): List<ByteArray>

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val FETCH_TIMEOUT_MS = 30_000L
    }
}

/**
 * Reply matching shared by every transport. A transport calls [dispatch] for each notification (any thread);
 * the first registered waiter whose predicate accepts the packet consumes it, exactly like the Python client's
 * `_waiters` list. Subclasses provide [write], [connect], [disconnect] and [state].
 */
abstract class BaseWatchLink : WatchLink {

    private class Waiter(
        val channel: WatchChannel,
        val take: (ByteArray) -> Boolean,
        val fail: (Throwable) -> Unit,
    )

    private val waiters = CopyOnWriteArrayList<Waiter>()

    private val packetFlow = MutableSharedFlow<RawPacket>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val packets: SharedFlow<RawPacket>
        get() = packetFlow

    /** Number of pending waiters (for diagnostics / tests). */
    val pendingWaiters: Int
        get() = waiters.size

    /** Offers one notification to the waiters, then publishes it on [packets]. Safe from any thread. */
    protected fun dispatch(
        channel: WatchChannel,
        data: ByteArray,
        time: Long = System.currentTimeMillis(),
    ): RawPacket {
        var consumed = false

        for (w in waiters) {
            if (w.channel != channel) continue

            val took = try {
                w.take(data)
            } catch (e: RuntimeException) {
                false
            }

            if (took) {
                consumed = true
                break
            }
        }

        val p = RawPacket(channel, data, time, consumed)
        packetFlow.tryEmit(p)
        return p
    }

    /** Fails every pending request / collect (link lost). The owners remove themselves. */
    protected fun failWaiters(e: Throwable) {
        for (w in waiters) {
            w.fail(e)
        }
    }

    override suspend fun waitFor(
        pred: (ByteArray) -> Boolean,
        timeoutMs: Long,
        channel: WatchChannel,
    ): ByteArray {
        val d = CompletableDeferred<ByteArray>()

        val w = Waiter(
            channel,
            { p -> !d.isCompleted && pred(p) && d.complete(p) },
            { e -> d.completeExceptionally(e) },
        )

        waiters += w

        try {
            return withTimeoutOrNull(timeoutMs) {
                d.await()
            } ?: throw GattException(
                "no matching packet on ${channel.notifyName} within $timeoutMs ms",
                timeout = true,
            )
        } finally {
            waiters -= w
        }
    }

    override suspend fun waitForDuring(
        action: suspend () -> Unit,
        pred: (ByteArray) -> Boolean,
        timeoutMs: Long,
        channel: WatchChannel,
    ): ByteArray {
        val d = CompletableDeferred<ByteArray>()

        val w = Waiter(
            channel,
            { p -> !d.isCompleted && pred(p) && d.complete(p) },
            { e -> d.completeExceptionally(e) },
        )

        waiters += w

        try {
            action()

            return withTimeoutOrNull(timeoutMs) {
                d.await()
            } ?: throw GattException(
                "no matching packet on ${channel.notifyName} within $timeoutMs ms",
                timeout = true,
            )
        } finally {
            waiters -= w
        }
    }

    override suspend fun request(
        cmd: ByteArray,
        opcode: Int,
        timeoutMs: Long,
        channel: WatchChannel,
        replyChannel: WatchChannel,
    ): ByteArray =
        request(
            cmd,
            Matchers.opcodeIs(opcode),
            timeoutMs,
            channel,
            replyChannel,
        )

    override suspend fun request(
        cmd: ByteArray,
        pred: (ByteArray) -> Boolean,
        timeoutMs: Long,
        channel: WatchChannel,
        replyChannel: WatchChannel,
    ): ByteArray {
        val d = CompletableDeferred<ByteArray>()

        val w = Waiter(
            replyChannel,
            { p -> !d.isCompleted && pred(p) && d.complete(p) },
            { e -> d.completeExceptionally(e) },
        )

        waiters += w

        try {
            write(cmd, channel)

            return withTimeoutOrNull(timeoutMs) {
                d.await()
            } ?: throw GattException(
                "no reply to ${cmd.toHex()} on ${replyChannel.notifyName} within $timeoutMs ms",
                timeout = true,
            )
        } finally {
            waiters -= w
        }
    }

    override suspend fun collect(
        cmd: ByteArray,
        opcode: Int,
        isEnd: (ByteArray) -> Boolean,
        timeoutMs: Long,
        channel: WatchChannel,
        extraChannel: WatchChannel?,
        extraOpcode: Int?,
    ): List<ByteArray> {
        val got = ArrayList<ByteArray>()
        val done = CompletableDeferred<Unit>()
        val wanted = opcode and 0xFF
        val wantedExtra = extraOpcode?.and(0xFF)

        val take: (ByteArray) -> Boolean = { p ->
            val op =
                if (p.isEmpty()) {
                    -1
                } else {
                    p[0].toInt() and 0xFF
                }

            if (done.isCompleted || (op != wanted && op != wantedExtra)) {
                false
            } else {
                synchronized(got) {
                    got += p
                }

                if (isEnd(p)) {
                    done.complete(Unit)
                }

                true
            }
        }

        val fail: (Throwable) -> Unit = { e ->
            done.completeExceptionally(e)
        }

        val ws = ArrayList<Waiter>(2)
        ws += Waiter(channel, take, fail)

        if (extraChannel != null && extraChannel != channel) {
            ws += Waiter(extraChannel, take, fail)
        }

        waiters.addAll(ws)

        fun snapshot(): List<ByteArray> =
            synchronized(got) {
                ArrayList(got)
            }

        try {
            write(cmd, channel)

            val finished = withTimeoutOrNull(timeoutMs) {
                done.await()
                true
            } ?: false

            if (!finished) {
                throw GattException(
                    "fetch ${cmd.toHex()} did not finish within $timeoutMs ms (${got.size} packets)",
                    partial = snapshot(),
                    timeout = true,
                )
            }
        } catch (e: GattException) {
            if (e.partial.isEmpty() && got.isNotEmpty()) {
                throw GattException(
                    e.message ?: "fetch failed",
                    snapshot(),
                    e,
                    e.timeout,
                )
            }

            throw e
        } finally {
            waiters.removeAll(ws.toSet())
        }

        return snapshot()
    }
}
