package au.buzz.ryzewave.protocol

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.WatchEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * Decoded packet structures (pure data, no Android), the [Packet] dispatcher for incoming notifications,
 * and the conversions from the watch's wall-clock LocalDateTimes to the core models' epoch millis.
 */

// ---------------------------------------------------------------------- decoded records

/** `A2` reply. */
data class BatteryInfo(val percent: Int, val charging: Boolean)

/** One `B2` history record / `B1` realtime push: an hour of steps. [time] is the start of the hour. */
data class StepsRecord(
    val time: LocalDateTime,
    val total: Int,
    val runStart: Int, val runEnd: Int, val runSteps: Int,
    val walkStart: Int, val walkEnd: Int, val walkSteps: Int,
)

/** One 10-minute HR bin from an `F7` record (or an `F7 03` push). [time] is the bin's slot time (e.g. 18:40). */
data class HrRecord(val time: LocalDateTime, val bpm: Int)

/** One 10-minute SpO2 bin from a `34 FA` record. */
data class Spo2Record(val time: LocalDateTime, val percent: Int)

/** One entry of a `32` sleep-stage packet: [stage] as delivered (1..4), [minutes] duration. */
data class SleepStageRecord(val time: LocalDateTime, val stage: Int, val minutes: Int)

/** `31 01 yyyy MM dd n`: the sleep session (its morning date) and the number of stage entries announced. */
data class SleepSessionInfo(val date: LocalDate, val count: Int)

/** Unsolicited `F7 03` / `F7 04` pushes. */
sealed class HrPush {
    data class Sample(val time: LocalDateTime, val bpm: Int) : HrPush()
    data class Summary(val time: LocalDateTime, val max: Int, val min: Int, val avg: Int) : HrPush()
}

/** Entry of the watch's sport-mode menu (`FD 48 AA 00 …`). */
data class SportListEntry(val id: Int, val enabled: Boolean, val order: Int)

/**
 * `FD <type> <hr> cal16 pace_min pace_sec steps24 count16 km km_frac2` (14 B): realtime workout data from the watch.
 * [sportType] is the sport id ([SportTypes]) — byte 1 is not a constant 0x01: an Outdoor Walking workout pushes
 * `FD 23 5C …` (verified on the wrist 2026-09-05). The Ryze Wave sends zeros for everything but [hr] during a
 * phone-driven workout; the other offsets follow the vendor SDK. [raw] is the packet as hex.
 */
data class SportRtData(
    val sportType: Int,
    val hr: Int,
    val calories: Int,
    val paceSecPerKm: Int,
    val steps: Int,
    val count: Int,
    val distanceMeters: Double,
    val raw: String,
)

/** `FD AA <state> <type>`. */
data class SportState(val state: Int, val sportType: Int)

/** `FD <state> <type> <interval>` echo (state = Protocol.SPORT_*). */
data class SportControl(val state: Int, val sportType: Int, val hrIntervalS: Int)

/**
 * `FD 00 <part> <payload>`: one chunk of a workout-history record (reply to `FD FA`, PROTOCOL.md §5). Observed
 * 2026-09-04: part 0 = `01 <u16> yyyy MM dd HH mm ss …` (sport type, ?, start time), part 1 = summary (layout
 * unknown), parts 2.. = HR values, then `FD FD xx`. Only [part] and the raw payload are exposed; see
 * [Protocol.decSportHistoryHeader] for the best-effort part-0 read.
 */
data class SportHistoryChunk(val part: Int, val payloadHex: String) {
    val payload: ByteArray get() = Protocol.fromHex(payloadHex)
}

/** Best-effort read of a part-0 workout-history chunk (see [Protocol.decSportHistoryHeader]). */
data class SportHistoryHeader(val sportType: Int, val value: Int, val start: LocalDateTime)

enum class Spo2Phase { STARTED, STOPPED, MEASURING, FINAL }

/** `34 11 …` / `34 00 …` spot-test packets; [spo2] is set only when [err] == 0. */
data class Spo2Result(val phase: Spo2Phase, val spo2: Int?, val err: Int?)

/** `38 01` reply on the data channel: classic-Bluetooth name / MAC / state. */
data class Bt3Info(val name: String, val mac: String, val bt3On: Boolean, val b29: Int, val b30: Int)

/** GATT read of 34F1. */
data class DataChannelInfo(val maxPacketLen: Int, val word8: Int)

// ---------------------------------------------------------------------- notification dispatcher

/**
 * Typed view of one incoming packet (33F2 or 34F2). `Packet.parse(bytes)` never throws: anything it does not
 * understand (including Ryze Fit's traffic when it shares the link) comes back as [Unknown] with the raw hex,
 * so the BLE layer can dispatch on the type and only react to what it asked for plus the known pushes.
 */
sealed class Packet {
    /** The whole packet as lower-case hex. */
    abstract val raw: String

    /** `A1 <ascii>`; [dsp] for `A1 01 <ascii>`. */
    data class Version(val version: String, val dsp: Boolean, override val raw: String) : Packet()
    /** `A2 <pct> [01]` — reply or charging push. */
    data class Battery(val percent: Int, val charging: Boolean, override val raw: String) : Packet()
    /** `A3 …` echo. */
    data class TimeAck(override val raw: String) : Packet()
    /** `A9` echo. */
    data class UserInfoAck(override val raw: String) : Packet()
    /** 18-byte `B2` record ([realtime] = false) or `B1` push ([realtime] = true). */
    data class Steps(val record: StepsRecord, val realtime: Boolean, override val raw: String) : Packet()
    /** `B2 FD xx`. */
    data class StepsEnd(val count: Int, override val raw: String) : Packet()
    /** 18-byte `F7` history record, already expanded into its non-empty 10-minute bins. */
    data class Hr24(val samples: List<HrRecord>, override val raw: String) : Packet()
    /** `F7 FD xx`. */
    data class Hr24End(val count: Int, override val raw: String) : Packet()
    /** `F7 03 …` automatic sample push. */
    data class HrAutoSample(val sample: HrRecord, override val raw: String) : Packet()
    /** `F7 04 …` daily summary push. */
    data class HrSummary(val time: LocalDateTime, val max: Int, val min: Int, val avg: Int, override val raw: String) : Packet()
    /** `F7 01` / `F7 02` echo. */
    data class HrContinuousAck(val on: Boolean, override val raw: String) : Packet()
    /** `D6 xx …` echo. */
    data class HrModeAck(val sub: Int, override val raw: String) : Packet()
    /** 20-byte `34 FA` history record, expanded. */
    data class Spo2History(val samples: List<Spo2Record>, override val raw: String) : Packet()
    /** `34 FA FD xx`. */
    data class Spo2HistoryEnd(val count: Int, override val raw: String) : Packet()
    /** `34 11 …` / `34 00 …` spot-test progress or result (also the `34 00 00 xx` auto-sample push). */
    data class Spo2Test(val result: Spo2Result, override val raw: String) : Packet()
    /** `34 AA <status>`. */
    data class Spo2Status(val status: Int, override val raw: String) : Packet()
    /** `34 03 …` / `34 04 …` echo. */
    data class Spo2SettingAck(val sub: Int, override val raw: String) : Packet()
    /** `31 01 yyyy MM dd n`. */
    data class SleepInfo(val sessionDate: LocalDate, val count: Int, override val raw: String) : Packet()
    /** `32 …` on 34F2; decode with the session date from the preceding [SleepInfo]. */
    data class SleepStages(val count: Int, override val raw: String) : Packet() {
        fun decode(sessionDate: LocalDate): List<SleepStageRecord> =
            Protocol.decSleepStages(sessionDate, Protocol.fromHex(raw))
    }
    /** `31 02`. */
    data class SleepEnd(override val raw: String) : Packet()
    /** `E5 11 00 <hr>`. */
    data class LiveHr(val bpm: Int, override val raw: String) : Packet()
    /** `E5 11` / `E5 00` echo. */
    data class LiveHrAck(val on: Boolean, override val raw: String) : Packet()
    /** 14-byte `FD <type> <hr> …` realtime workout data, any sport type (see [SportRtData]). */
    data class SportRt(
        val sportType: Int, val hr: Int, val calories: Int, val paceSecPerKm: Int, val steps: Int, val count: Int,
        val distanceMeters: Double, override val raw: String,
    ) : Packet()
    /** `FD 11/22/33/00/44 <type> <interval>` echo. */
    data class SportControlEcho(val state: Int, val sportType: Int, val hrIntervalS: Int, override val raw: String) : Packet()
    /** `FD AA <state> <type>`. */
    data class SportQuery(val state: Int, val sportType: Int, override val raw: String) : Packet()
    /** `FD 48 AA …` sport list chunk; [end] for the terminating `FD 48 AA FD …`. */
    data class SportList(val entries: List<SportListEntry>, val end: Boolean, override val raw: String) : Packet()
    /** `FD FA <since6> <u16>`: first reply to a workout-history fetch. */
    data class SportHistoryStart(val count: Int, override val raw: String) : Packet()
    /** `FD 00 <part> …` with 5+ bytes: a workout-history chunk (never a stop echo). */
    data class SportHistory(val chunk: SportHistoryChunk, override val raw: String) : Packet()
    /** `FD FD xx`: end of a workout-history fetch. */
    data class SportHistoryEnd(val count: Int, override val raw: String) : Packet()
    /** `38 01 …` classic-Bluetooth info. */
    data class Bt3(val info: Bt3Info, override val raw: String) : Packet()
    /** `38 02 xx` echo. */
    data class Bt3Ack(val on: Boolean, override val raw: String) : Packet()
    /** `D1 0A 01` (start) / `D1 0A 00` (stop). */
    data class FindPhone(val start: Boolean, override val raw: String) : Packet()
    /** `D1 02`. */
    data class HangUp(override val raw: String) : Packet()
    /** `D1 07/08/09/0D/0E` (Protocol.ACTION_MUSIC_*). */
    data class MusicControl(val action: Int, override val raw: String) : Packet()
    /** `C4 02`. */
    data class CameraShutter(override val raw: String) : Packet()
    /** `D5 <code>`: 01 ok, 02 required, 03 timeout, FF wrong. */
    data class Password(val code: Int, override val raw: String) : Packet()
    /** Anything else (or malformed). */
    data class Unknown(override val raw: String) : Packet()

    companion object {
        fun parse(b: ByteArray): Packet {
            val raw = Protocol.hex(b)
            if (b.isEmpty()) return Unknown(raw)
            return try {
                parseChecked(b, raw)
            } catch (e: RuntimeException) {   // impossible dates, short packets: treat as unknown, never crash the link
                Unknown(raw)
            }
        }

        fun parseHex(hex: String): Packet = parse(Protocol.fromHex(hex))

        private fun parseChecked(b: ByteArray, raw: String): Packet {
            val op = b.u8(0)
            val n = b.size
            val sub = if (n > 1) b.u8(1) else -1
            return when (op) {
                Protocol.CMD_VERSION -> Version(Protocol.decVersion(b), sub == 0x01 && n > 2, raw)
                Protocol.CMD_BATTERY -> if (n >= 2) {
                    val bi = Protocol.decBattery(b)
                    Battery(bi.percent, bi.charging, raw)
                } else Unknown(raw)
                Protocol.CMD_TIME -> TimeAck(raw)
                Protocol.CMD_USER_INFO -> UserInfoAck(raw)
                Protocol.CMD_RT_STEPS -> if (n == 18) Steps(Protocol.decStepsRecord(b), true, raw) else Unknown(raw)
                Protocol.CMD_STEPS -> when {
                    n == 18 -> Steps(Protocol.decStepsRecord(b), false, raw)
                    n == 3 && sub == Protocol.FETCH_END -> StepsEnd(b.u8(2), raw)
                    else -> Unknown(raw)
                }
                Protocol.CMD_HR24 -> when {
                    n == 18 -> Hr24(Protocol.decHr24Record(b), raw)
                    n == 3 && sub == Protocol.FETCH_END -> Hr24End(b.u8(2), raw)
                    sub == 0x03 || sub == 0x04 -> when (val p = Protocol.decHrPush(b)) {
                        is HrPush.Sample -> HrAutoSample(HrRecord(p.time, p.bpm), raw)
                        is HrPush.Summary -> HrSummary(p.time, p.max, p.min, p.avg, raw)
                        null -> Unknown(raw)
                    }
                    n == 2 && (sub == 0x01 || sub == 0x02) -> HrContinuousAck(sub == 0x01, raw)
                    else -> Unknown(raw)
                }
                Protocol.CMD_HR_MODE -> if (n >= 2) HrModeAck(sub, raw) else Unknown(raw)
                Protocol.CMD_SPO2 -> when {
                    sub == Protocol.FETCH_START && n == 20 -> Spo2History(Protocol.decSpo2Record(b), raw)
                    sub == Protocol.FETCH_START && n >= 3 && b.u8(2) == Protocol.FETCH_END ->
                        Spo2HistoryEnd(if (n > 3) b.u8(3) else 0, raw)
                    sub == 0x11 || sub == 0x00 -> Spo2Test(Protocol.decSpo2Result(b), raw)
                    sub == Protocol.QUERY -> Spo2Status(if (n > 2) b.u8(2) else -1, raw)
                    sub == 0x03 || sub == 0x04 -> Spo2SettingAck(sub, raw)
                    else -> Unknown(raw)
                }
                Protocol.CMD_SLEEP_INFO -> when {
                    sub == 0x01 -> Protocol.decSleepInfo(b)?.let { SleepInfo(it.date, it.count, raw) } ?: Unknown(raw)
                    sub == 0x02 -> SleepEnd(raw)
                    else -> Unknown(raw)
                }
                Protocol.CMD_SLEEP_STAGES -> if ((n - 1) % 6 == 0) SleepStages((n - 1) / 6, raw) else Unknown(raw)
                Protocol.CMD_RT_HR -> when {
                    sub == 0x11 && n == 4 -> LiveHr(b.u8(3), raw)
                    sub == 0x11 -> LiveHrAck(true, raw)
                    sub == 0x00 -> LiveHrAck(false, raw)
                    else -> Unknown(raw)
                }
                Protocol.CMD_SPORT -> when {
                    // Realtime workout data is identified by its length alone: byte 1 is the sport type, so it can
                    // look like any sub-command (control echoes are 4 B, the pause echo 13 B, history chunks longer).
                    Protocol.isSportRt(b) -> Protocol.decSportRt(b).let {
                        SportRt(it.sportType, it.hr, it.calories, it.paceSecPerKm, it.steps, it.count, it.distanceMeters, raw)
                    }
                    sub == Protocol.QUERY -> Protocol.decSportState(b)?.let { SportQuery(it.state, it.sportType, raw) } ?: Unknown(raw)
                    sub == Protocol.SPORT_LIST -> SportList(Protocol.decSportList(b), Protocol.isSportListEnd(b), raw)
                    sub == Protocol.FETCH_START -> Protocol.decSportHistoryStart(b)?.let { SportHistoryStart(it, raw) } ?: Unknown(raw)
                    sub == Protocol.FETCH_END && n == 3 -> SportHistoryEnd(b.u8(2), raw)
                    sub == Protocol.SPORT_STOP && n >= 5 -> Protocol.decSportHistoryChunk(b)?.let { SportHistory(it, raw) } ?: Unknown(raw)
                    else -> Protocol.decSportControl(b)?.let { SportControlEcho(it.state, it.sportType, it.hrIntervalS, raw) } ?: Unknown(raw)
                }
                Protocol.CMD_BT3 -> when {
                    sub == 0x01 -> Protocol.decBt3Info(b)?.let { Bt3(it, raw) } ?: Unknown(raw)
                    sub == 0x02 && n >= 3 -> Bt3Ack(b.u8(2) == 0x01, raw)
                    else -> Unknown(raw)
                }
                Protocol.CMD_ACTION -> when (sub) {
                    Protocol.ACTION_FIND_PHONE -> FindPhone(n > 2 && b.u8(2) == 0x01, raw)
                    Protocol.ACTION_HANG_UP -> HangUp(raw)
                    Protocol.ACTION_MUSIC_PLAY, Protocol.ACTION_MUSIC_NEXT, Protocol.ACTION_MUSIC_PREV,
                    Protocol.ACTION_MUSIC_VOL_UP, Protocol.ACTION_MUSIC_VOL_DOWN -> MusicControl(sub, raw)
                    else -> Unknown(raw)
                }
                Protocol.CMD_CAMERA -> if (sub == 0x02) CameraShutter(raw) else Unknown(raw)
                Protocol.CMD_PASSWORD -> if (n >= 2) Password(sub, raw) else Unknown(raw)
                else -> Unknown(raw)
            }
        }
    }
}

// ---------------------------------------------------------------------- time conversion (java.time, system zone)

/** Wall-clock time from the watch -> epoch millis in [zone] (the phone's zone by default). */
fun LocalDateTime.toEpochMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    atZone(zone).toInstant().toEpochMilli()

/** Epoch millis -> wall-clock time in [zone]; used to build the 6-byte since-stamps from sync cursors. */
fun localDateTimeOf(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()

/** `F7 FA <since6>` from a sync cursor in epoch millis (null = everything). */
fun Protocol.encFetchHr24Since(sinceEpochMillis: Long?, withTs: Boolean = true, zone: ZoneId = ZoneId.systemDefault()): ByteArray =
    encFetchHr24(sinceEpochMillis?.let { localDateTimeOf(it, zone) }, withTs)

/** `A9` from the app's profile (raise-wrist / HR alert thresholds are not part of [UserProfile]; defaults apply). */
fun Protocol.encUserInfo(profile: UserProfile, raiseWrist: Boolean = true, hrHigh: Int = 0, hrLow: Int = 0, displaySeconds: Int = 5): ByteArray =
    encUserInfo(profile.heightCm, profile.weightKg, profile.stepGoal, profile.age, profile.male, raiseWrist, hrHigh, hrLow, true, displaySeconds)

// ---------------------------------------------------------------------- conversions to the core models

fun StepsRecord.toStepsHour(zone: ZoneId = ZoneId.systemDefault()): StepsHour =
    StepsHour(hourStart = time.toEpochMillis(zone), total = total, walk = walkSteps, run = runSteps)

fun HrRecord.toHrSample(source: SampleSource = SampleSource.HISTORY, zone: ZoneId = ZoneId.systemDefault()): HrSample =
    HrSample(time = time.toEpochMillis(zone), bpm = bpm, source = source)

fun Spo2Record.toSpo2Sample(source: SampleSource = SampleSource.HISTORY, zone: ZoneId = ZoneId.systemDefault()): Spo2Sample =
    Spo2Sample(time = time.toEpochMillis(zone), percent = percent, source = source)

fun SleepStageRecord.toSleepStage(zone: ZoneId = ZoneId.systemDefault()): SleepStage =
    SleepStage(start = time.toEpochMillis(zone), stage = stage, minutes = minutes)

fun HrPush.Sample.toHrSample(zone: ZoneId = ZoneId.systemDefault()): HrSample =
    HrSample(time = time.toEpochMillis(zone), bpm = bpm, source = SampleSource.AUTO)

fun HrPush.Summary.toEvent(zone: ZoneId = ZoneId.systemDefault()): WatchEvent.HrSummary =
    WatchEvent.HrSummary(time = time.toEpochMillis(zone), max = max, min = min, avg = avg)

fun Packet.HrSummary.toEvent(zone: ZoneId = ZoneId.systemDefault()): WatchEvent.HrSummary =
    WatchEvent.HrSummary(time = time.toEpochMillis(zone), max = max, min = min, avg = avg)

fun Packet.HrAutoSample.toHrSample(zone: ZoneId = ZoneId.systemDefault()): HrSample =
    sample.toHrSample(SampleSource.AUTO, zone)

fun Packet.Steps.toEvent(zone: ZoneId = ZoneId.systemDefault()): WatchEvent.RealtimeSteps =
    WatchEvent.RealtimeSteps(record.toStepsHour(zone))
