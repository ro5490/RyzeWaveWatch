package au.buzz.ryzewave.protocol

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import kotlin.math.min
import kotlin.math.round

/** Unsigned byte at [i]. */
internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

/** Big-endian unsigned 16-bit value at [i]. */
internal fun ByteArray.u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

/** Big-endian unsigned 32-bit value at [i], represented as a Long. */
internal fun ByteArray.u32(i: Int): Long =
    (u8(i).toLong() shl 24) or
        (u8(i + 1).toLong() shl 16) or
        (u8(i + 2).toLong() shl 8) or
        u8(i + 3).toLong()

/** Builds a ByteArray from int literals; every value is masked to 8 bits. */
internal fun bytesOf(vararg v: Int): ByteArray = ByteArray(v.size) { (v[it] and 0xFF).toByte() }

/**
 * Ryze Wave (UTE / GloryFit family) BLE protocol: GATT UUIDs, opcodes, encoders and decoders.
 *
 * Pure functions only; no Bluetooth and no Android here. This is a faithful port of
 * `ryzewave/protocol.py` (the executable spec); see `docs/PROTOCOL.md` for the packet layouts and
 * what has been verified against the watch. Function names mirror the Python ones
 * (`enc_set_time` -> [encSetTime], `dec_hr24_record` -> [decHr24Record]) so the two can be diffed.
 *
 * Packets are raw bytes without framing or checksum; byte 0 is the opcode. Dates inside packets are
 * `yyyy(BE) MM dd` — the `0x07` that Gadgetbridge treats as a marker is really the year's high byte
 * (0x07E9 = 2025, 0x07EA = 2026). All decoded times are wall-clock [LocalDateTime]s in the watch's
 * (= phone's) local time; convert with `toEpochMillis()` from Packets.kt.
 */
data class WatchFaceConfigPacket(
    val dialNumber: Long,
    val width: Int,
    val height: Int,
    val screenType: Int,
    val maxDataSize: Long,
    val compatibleLevel: Int?,
    val cornerAngle: Int?,
)

data class WatchFaceBinInfo(
    val payloadSize: Int,
    val crc32: Long,
)

object Protocol {

    // ------------------------------------------------------------------ GATT
    const val SVC_CMD = "000055ff-0000-1000-8000-00805f9b34fb"
    /** Commands phone -> watch (write without response). A GATT *read* returns the 20-byte feature bitmap. */
    const val CH_CMD_WRITE = "000033f1-0000-1000-8000-00805f9b34fb"
    /** Responses / events watch -> phone (notify). */
    const val CH_CMD_NOTIFY = "000033f2-0000-1000-8000-00805f9b34fb"
    const val SVC_DATA = "000056ff-0000-1000-8000-00805f9b34fb"
    /** "Data" channel (contacts, canned SMS, sleep stages, BT3…). A GATT *read* returns the max packet length (u16 BE). */
    const val CH_DATA_WRITE = "000034f1-0000-1000-8000-00805f9b34fb"
    const val CH_DATA_NOTIFY = "000034f2-0000-1000-8000-00805f9b34fb"
    /** Client Characteristic Configuration descriptor (enable notifications). */
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
    /** MTU the vendor app and Gadgetbridge request; the watch grants it. */
    const val MTU = 247

    val SVC_CMD_UUID: UUID = UUID.fromString(SVC_CMD)
    val CH_CMD_WRITE_UUID: UUID = UUID.fromString(CH_CMD_WRITE)
    val CH_CMD_NOTIFY_UUID: UUID = UUID.fromString(CH_CMD_NOTIFY)
    val SVC_DATA_UUID: UUID = UUID.fromString(SVC_DATA)
    val CH_DATA_WRITE_UUID: UUID = UUID.fromString(CH_DATA_WRITE)
    val CH_DATA_NOTIFY_UUID: UUID = UUID.fromString(CH_DATA_NOTIFY)
    val CCCD_UUID: UUID = UUID.fromString(CCCD)

    /** The two logical channels: 33F1/33F2 (commands) and 34F1/34F2 (data). */
    enum class Channel { CMD, DATA }

    fun channelOf(uuid: UUID): Channel? = when (uuid) {
        CH_CMD_WRITE_UUID, CH_CMD_NOTIFY_UUID -> Channel.CMD
        CH_DATA_WRITE_UUID, CH_DATA_NOTIFY_UUID -> Channel.DATA
        else -> null
    }

    // ------------------------------------------------------------------ opcodes
    const val CMD_VERSION = 0xA1
    const val CMD_BATTERY = 0xA2
    const val CMD_TIME = 0xA3
    const val CMD_USER_INFO = 0xA9
    const val CMD_STEP_STATUS = 0xAA
    const val CMD_VIBRATE = 0xAB
    /** Factory reset / delete all. Never send casually. */
    const val CMD_FACTORY_RESET = 0xAD
    const val CMD_LANGUAGE = 0xAF
    const val CMD_RT_STEPS = 0xB1
    const val CMD_STEPS = 0xB2
    const val CMD_CALL_STATUS = 0xC1
    const val CMD_CAMERA = 0xC4
    const val CMD_NOTIFICATION = 0xC5
    const val CMD_ACTION = 0xD1
    const val CMD_SEDENTARY = 0xD3
    const val CMD_DRINK_WATER = 0xD4
    const val CMD_PASSWORD = 0xD5
    const val CMD_HR_MODE = 0xD6
    const val CMD_RT_HR = 0xE5
    const val CMD_HR_SINGLE = 0xE6
    const val CMD_HR24 = 0xF7
    const val CMD_SPORT = 0xFD
    const val CMD_SLEEP_INFO = 0x31
    const val CMD_SLEEP_STAGES = 0x32
    const val CMD_SPO2 = 0x34
    const val CMD_GOALS = 0x3F
    /** Classic-Bluetooth (audio/calls) control; lives on the DATA channel (34F1/34F2). */
    const val CMD_BT3 = 0x38
    const val CMD_TIME_FORMAT = 0xA0
    const val CMD_MOOD = 0x44
    /** Classic GloryFit/UTE watch-face control/query opcode. */
    const val CMD_WATCH_FACE = 0x26
    /** Classic GloryFit/UTE watch-face binary section opcode. */
    const val CMD_WATCH_FACE_DATA = 0x27
    const val WATCH_FACE_HEADER_BYTES = 24

    const val FETCH_START = 0xFA
    const val FETCH_DATA = 0x07
    const val FETCH_END = 0xFD
    const val QUERY = 0xAA

    // sport (FD) sub-codes
    const val SPORT_STOP = 0x00
    const val SPORT_START = 0x11
    const val SPORT_PAUSE = 0x22
    const val SPORT_RESUME = 0x33
    const val SPORT_UPDATE = 0x44
    /** Length of the realtime workout push `FD <type> <hr> …`: byte 1 is the sport id, so only the length identifies it. */
    const val SPORT_RT_LEN = 14
    const val SPORT_LIST = 0x48

    // D1 actions the watch pushes
    const val ACTION_HANG_UP = 0x02
    const val ACTION_MUSIC_PLAY = 0x07
    const val ACTION_MUSIC_NEXT = 0x08
    const val ACTION_MUSIC_PREV = 0x09
    const val ACTION_FIND_PHONE = 0x0A
    const val ACTION_MUSIC_VOL_UP = 0x0D
    const val ACTION_MUSIC_VOL_DOWN = 0x0E

    /** Value meaning "no sample" in the 10-minute history bins. */
    const val NO_SAMPLE = 0xFF

    val PASSWORD_XOR: ByteArray = "UTE8".toByteArray(Charsets.US_ASCII)
    const val DEFAULT_PASSWORD = "1234"

    // ------------------------------------------------------------------ helpers
    private const val HEX_DIGITS = "0123456789abcdef"

    /** Lower-case hex without separators (same as Python's `bytes.hex()`). */
    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /** Parses hex; whitespace and ':' are ignored, case does not matter. */
    fun fromHex(s: String): ByteArray {
        val clean = s.filterNot { it.isWhitespace() || it == ':' }
        require(clean.length % 2 == 0) { "odd-length hex string: $s" }
        return ByteArray(clean.length / 2) { i ->
            val hi = Character.digit(clean[2 * i], 16)
            val lo = Character.digit(clean[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "bad hex digit in: $s" }
            ((hi shl 4) or lo).toByte()
        }
    }

    /** Opcode (byte 0) or -1 for an empty packet. */
    fun opcode(b: ByteArray): Int = if (b.isEmpty()) -1 else b.u8(0)

    /** Sub-code (byte 1) or -1 when absent. */
    fun sub(b: ByteArray): Int = if (b.size < 2) -1 else b.u8(1)

    /** `yyyy(BE) MM dd` at offset [o]. Throws `DateTimeException` for an impossible date. */
    fun date(b: ByteArray, o: Int): LocalDate = LocalDate.of(b.u16(o), b.u8(o + 2), b.u8(o + 3))

    /** 6-byte "sync since" stamp: yyyy(BE) MM dd HH mm. Zeros = everything. */
    fun sinceStamp(since: LocalDateTime?): ByteArray =
        if (since == null) ByteArray(6)
        else bytesOf(since.year shr 8, since.year, since.monthValue, since.dayOfMonth, since.hour, since.minute)

    // ------------------------------------------------------------------ encoders
    fun encVersion(): ByteArray = bytesOf(CMD_VERSION)
    fun encDspVersion(): ByteArray = bytesOf(CMD_VERSION, 0x01)
    fun encBattery(): ByteArray = bytesOf(CMD_BATTERY)

    /** `26 01`: query classic GloryFit/UTE watch-face capabilities. Read-only. */
    fun encWatchFaceConfigQuery(): ByteArray = bytesOf(CMD_WATCH_FACE, 0x01)

    /** `26 02`: prepare the classic online-dial destination for an upload. */
    fun encWatchFacePrepare(): ByteArray = bytesOf(CMD_WATCH_FACE, 0x02)

    /** `26 03 00`: tell the watch that all `27` sections have been sent. */
    fun encWatchFaceFinish(): ByteArray = bytesOf(CMD_WATCH_FACE, 0x03, 0x00)

    /**
     * One classic online-dial data section: `27 <index-hi> <index-lo> <payload>`.
     * The section index is unsigned 16-bit big-endian.
     */
    fun encWatchFaceDataSection(index: Int, payload: ByteArray): ByteArray {
        require(index in 0..0xFFFF) { "watch-face section index out of range: $index" }
        require(payload.isNotEmpty()) { "watch-face section payload is empty" }

        return ByteArray(3 + payload.size).also { out ->
            out[0] = CMD_WATCH_FACE_DATA.toByte()
            out[1] = (index ushr 8).toByte()
            out[2] = index.toByte()
            payload.copyInto(out, destinationOffset = 3)
        }
    }

    /**
     * Validates the GloryFit `.BIN` container used by the P32 before any destructive
     * `26 02` prepare command is allowed.
     *
     * Confirmed container fields:
     *  - bytes 0..3: ASCII ".BIN"
     *  - bytes 4..7: payload length, little-endian u32
     *  - bytes 8..11: CRC-32 of bytes 24..end, little-endian u32
     *  - bytes 24..end: dial payload
     */
    fun validateWatchFaceBin(data: ByteArray): WatchFaceBinInfo {
        require(data.size >= WATCH_FACE_HEADER_BYTES) {
            "watch-face BIN is too short: ${data.size} bytes"
        }
        require(
            data[0] == '.'.code.toByte() &&
                data[1] == 'B'.code.toByte() &&
                data[2] == 'I'.code.toByte() &&
                data[3] == 'N'.code.toByte()
        ) {
            "watch-face file does not start with .BIN"
        }

        fun u32le(offset: Int): Long =
            data[offset].toLong().and(0xFF) or
                (data[offset + 1].toLong().and(0xFF) shl 8) or
                (data[offset + 2].toLong().and(0xFF) shl 16) or
                (data[offset + 3].toLong().and(0xFF) shl 24)

        val payloadSize = u32le(4)
        val actualPayloadSize = data.size - WATCH_FACE_HEADER_BYTES
        require(payloadSize == actualPayloadSize.toLong()) {
            "watch-face BIN length mismatch: header=$payloadSize actual=$actualPayloadSize"
        }

        val expectedCrc = u32le(8)
        val crc = CRC32()
        crc.update(data, WATCH_FACE_HEADER_BYTES, actualPayloadSize)
        val actualCrc = crc.value
        require(expectedCrc == actualCrc) {
            "watch-face BIN CRC mismatch: header=${expectedCrc.toString(16)} actual=${actualCrc.toString(16)}"
        }

        return WatchFaceBinInfo(
            payloadSize = actualPayloadSize,
            crc32 = actualCrc,
        )
    }

    /** `A3 yyyy MM dd HH mm ss` — echoed back by the watch as the ack. */
    fun encSetTime(t: LocalDateTime = LocalDateTime.now()): ByteArray =
        bytesOf(CMD_TIME, t.year shr 8, t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)

    /**
     * `A9`: 19 bytes (Gadgetbridge layout): height u16 cm, weight u16 kg, 05 00 00, step goal u16, raise-wrist,
     * HR-high alert, 00, age, gender (1 = male, 2 = female), 00, temperature unit (2 = °C, 1 = °F), 01, HR-low alert.
     */
    fun encUserInfo(
        heightCm: Int, weightKg: Int, stepGoal: Int, age: Int, male: Boolean,
        raiseWrist: Boolean = true, hrHigh: Int = 0, hrLow: Int = 0, celsius: Boolean = true,
    ): ByteArray = bytesOf(
        CMD_USER_INFO,
        heightCm shr 8, heightCm, weightKg shr 8, weightKg,
        0x05, 0x00, 0x00, stepGoal shr 8, stepGoal,
        // byte 11 = high-HR alarm threshold, 0xFF disables it; byte 18 = low-HR alarm threshold, 0 disables it.
        if (raiseWrist) 0x01 else 0x00, if (hrHigh > 0) hrHigh else 0xFF, 0x00, age,
        if (male) 0x01 else 0x02, 0x00, if (celsius) 0x02 else 0x01, 0x01, hrLow.coerceAtLeast(0),
    )

    fun encPasswordQuery(): ByteArray = bytesOf(CMD_PASSWORD, 0x01)
    fun encPasswordRequestCode(): ByteArray = bytesOf(CMD_PASSWORD, 0x02)

    /** `D5 01 k0..k3` with `k = ascii(password) XOR "UTE8"`. Used on reconnect (not needed on the Ryze Wave). */
    fun encPasswordAuth(password: String): ByteArray {
        val p = password.toByteArray(Charsets.US_ASCII)
        require(p.size == 4) { "password must be 4 ASCII chars" }
        val out = ByteArray(6)
        out[0] = CMD_PASSWORD.toByte()
        out[1] = 0x01
        for (i in 0 until 4) out[2 + i] = (p[i].toInt() xor PASSWORD_XOR[i].toInt()).toByte()
        return out
    }

    /** `D5 02 <ascii>` = input the code shown on the watch; `D5 03 <ascii>` = set a new one. */
    fun encPasswordInput(password: String, setNew: Boolean = false): ByteArray =
        bytesOf(CMD_PASSWORD, if (setNew) 0x03 else 0x02) + password.toByteArray(Charsets.US_ASCII)

    /** `B2 FA` -> 18-byte `B2` records -> `B2 FD xx`. */
    fun encFetchSteps(): ByteArray = bytesOf(CMD_STEPS, FETCH_START)
    /** `34 FA` -> 20-byte `34 FA` records -> `34 FA FD xx`. */
    fun encFetchSpo2(): ByteArray = bytesOf(CMD_SPO2, FETCH_START)
    /** `31 01` -> `31 01 yyyy MM dd n` (33F2), `32 …` stage packets (34F2), `31 02` (33F2). */
    fun encFetchSleep(): ByteArray = bytesOf(CMD_SLEEP_INFO, 0x01)

    /** `F7 FA [since6]` -> 18-byte `F7` records -> `F7 FD xx`. The stamp is only sent if `withTs` (feature FL4 & 0x2000). */
    fun encFetchHr24(since: LocalDateTime? = null, withTs: Boolean = true): ByteArray =
        bytesOf(CMD_HR24, FETCH_START) + (if (withTs) sinceStamp(since) else ByteArray(0))

    /** `E6 FA [since6]`: single (spot) HR measurements. */
    fun encFetchHrSingle(since: LocalDateTime? = null, withTs: Boolean = true): ByteArray =
        bytesOf(CMD_HR_SINGLE, FETCH_START) + (if (withTs) sinceStamp(since) else ByteArray(0))

    /** `FD FA [since6]`: workout history (seen in the vendor app's connect burst). */
    fun encFetchSportHistory(since: LocalDateTime? = null, withTs: Boolean = true): ByteArray =
        bytesOf(CMD_SPORT, FETCH_START) + (if (withTs) sinceStamp(since) else ByteArray(0))

    /** `F7 01` / `F7 02`: continuous (10-minute) HR sampling on / off. */
    fun encHrContinuous(on: Boolean): ByteArray = bytesOf(CMD_HR24, if (on) 0x01 else 0x02)

    /** `D6 01` = static/spot HR test, `D6 02` = dynamic/continuous. The vendor app sends this 1.5 s before `E5 11`. */
    fun encHrMode(dynamic: Boolean): ByteArray = bytesOf(CMD_HR_MODE, if (dynamic) 0x02 else 0x01)

    /** `D6 10 <n>`: automatic extra HR spot test interval (vendor menu: 1/2/6/12 hours; 0 = off). The Ryze Wave neither echoes nor acks it (docs/PROTOCOL.md), so it is sent fire-and-forget. */
    fun encHrTimed(interval: Int): ByteArray = bytesOf(CMD_HR_MODE, 0x10, interval)

    /** `E5 11` start live HR (`E5 11 00 <hr>` per second follows), `E5 00` stop. */
    fun encRtHr(on: Boolean): ByteArray = bytesOf(CMD_RT_HR, if (on) 0x11 else 0x00)

    /** `34 11` start a spot SpO2 test (result `34 00 00 <pct>` ~60 s later), `34 00` stop. */
    fun encSpo2Test(on: Boolean): ByteArray = bytesOf(CMD_SPO2, if (on) 0x11 else 0x00)
    /** `34 AA` -> `34 AA FF` when idle. */
    fun encSpo2Status(): ByteArray = bytesOf(CMD_SPO2, QUERY)

    /** `34 03 <on> <interval16 BE minutes>`: automatic SpO2 sampling (vendor menu 10..360; 5 was acknowledged). */
    fun encSpo2Auto(on: Boolean, intervalMin: Int = 10): ByteArray =
        bytesOf(CMD_SPO2, 0x03, if (on) 0x01 else 0x00, intervalMin shr 8, intervalMin)

    /** `34 04 <on> HH mm HH mm`: time window for automatic SpO2 sampling (vendor default 00:01-23:59). */
    fun encSpo2Period(on: Boolean, startHh: Int = 0, startMm: Int = 1, endHh: Int = 23, endMm: Int = 59): ByteArray =
        bytesOf(CMD_SPO2, 0x04, if (on) 0x01 else 0x00, startHh, startMm, endHh, endMm)

    enum class Goal(val code: Int) { CALORIES(0x03), STEPS(0x04), DISTANCE(0x05) }

    /** `3F <kind> 01 <u32 BE>`: daily goal (steps, calories, distance in metres). */
    fun encGoal(kind: Goal, value: Int): ByteArray =
        bytesOf(CMD_GOALS, kind.code, 0x01, value ushr 24, value ushr 16, value ushr 8, value)

    fun encStepGoal(steps: Int): ByteArray = encGoal(Goal.STEPS, steps)

    /** `AB 00 00 00 01 02 07 01`: make the watch vibrate ("find watch"). */
    fun encFindWatch(): ByteArray = bytesOf(CMD_VIBRATE, 0, 0, 0, 1, 0x02, 0x07, 0x01)

    /** `AB 00 00 00 01 <count> 00 00`: a short silent buzz to alert on a notification (no ring, unlike find-watch).
     * Mirrors the vendor's sendQQWeChatVibrationCommand, which pairs one of these with every C5 notification. */
    fun encNotifyVibrate(count: Int = 1): ByteArray =
        bytesOf(CMD_VIBRATE, 0, 0, 0, 1, count.coerceIn(1, 255), 0, 0)

    /** `38 02 <01|00>` on the DATA channel: classic Bluetooth (HFP/A2DP) on / off. Echoed as ack. */
    fun encBt3Enable(on: Boolean): ByteArray = bytesOf(CMD_BT3, 0x02, if (on) 0x01 else 0x00)

    /** `38 01 02 00 <4 nonce bytes>` on the DATA channel; the watch answers with `38 01 <name:20> <mac6> <bt3_on> b29 b30`. */
    fun encBt3Query(nonce: ByteArray = ByteArray(4)): ByteArray {
        val n = ByteArray(4)
        for (i in 0 until min(4, nonce.size)) n[i] = nonce[i]
        return bytesOf(CMD_BT3, 0x01, 0x02, 0x00) + n
    }

    /** `FD AA` -> `FD AA <state> <type>`: current workout. */
    fun encSportQuery(): ByteArray = bytesOf(CMD_SPORT, QUERY)

    /** `FD <state> <type> <interval>`; state = [SPORT_START] / [SPORT_PAUSE] / [SPORT_RESUME] / [SPORT_STOP]. The watch echoes it. */
    fun encSportControl(state: Int, sportType: Int, hrIntervalS: Int = 1): ByteArray =
        bytesOf(CMD_SPORT, state, sportType, hrIntervalS.coerceIn(1, 255))

    /**
     * `FD 44 …`: phone -> watch live metrics during a connected-GPS workout, once per second (SDK setMultipleSportsModes2).
     * Exact 13-byte layout: `FD state type interval hh mm ss cal_hi cal_lo km_int km_frac2 pace_min pace_sec`
     * (7 bytes when [gps] is false). Pace is seconds per km; anything above 99:59 min/km is sent as 0:00.
     */
    fun encSportUpdate(
        sportType: Int, durationS: Int, calories: Int = 0, distanceM: Double = 0.0, paceSPerKm: Double = 0.0,
        state: Int = SPORT_UPDATE, hrIntervalS: Int = 1, gps: Boolean = true,
    ): ByteArray {
        val dur = durationS.coerceAtLeast(0)
        val h = dur / 3600
        val m = (dur % 3600) / 60
        val s = dur % 60
        val head = bytesOf(CMD_SPORT, state, sportType, hrIntervalS.coerceIn(1, 255), h, m, s)
        if (!gps) return head
        val km = (if (distanceM.isFinite()) distanceM.coerceAtLeast(0.0) else 0.0) / 1000.0
        var kmInt = km.toInt()
        var kmFrac = round((km - kmInt) * 100).toInt()
        if (kmFrac >= 100) {          // x.995 km rounds up to the next whole km (the Python reference wraps to .00)
            kmInt += 1
            kmFrac -= 100
        }
        val pace = if (paceSPerKm.isFinite() && paceSPerKm > 0) min(paceSPerKm, 1_000_000.0).toInt() else 0
        var pm = pace / 60
        var ps = pace % 60
        if (pm * 60 + ps > 5999) {
            pm = 0
            ps = 0
        }
        val cal = calories.coerceIn(0, 0xFFFF)
        return head + bytesOf(cal shr 8, cal, kmInt, kmFrac, pm, ps)
    }

    // ------------------------------------------------------------------ notifications (C5)

    /** Payload bytes per `C5` chunk (2-byte header, or 4 bytes for chunk 0): 8 UTF-16 characters. */
    const val NOTIFY_CHUNK_BYTES = 16
    /** The total-length field is one byte, so at most 255 bytes = 127 UTF-16 code units of text. */
    const val NOTIFY_MAX_CHARS = 127
    /** Sub-code of the `C5 FD` end-of-message packet; the watch acks it with `C5 FD <type> <total>`. */
    const val NOTIFY_END = 0xFD

    /**
     * Notification text as `C5` chunks (verified 2026-09-05 07:39, captures/bridge_20260905_07395*.txt):
     * `C5 00 <type> <total_bytes> <16 B>` then `C5 <idx> <16 B>` per chunk (the last one shorter when the text is
     * not a multiple of 8 characters), each acked by the watch with `C5 <idx>`; send [encNotificationEnd] after
     * the last chunk. The text is passed through [NotificationText.sanitize] (emoji / control characters
     * stripped, whitespace collapsed) and cut at [NOTIFY_MAX_CHARS]; an empty result gives an empty list (nothing
     * to send). [type] is the app icon per docs/PROTOCOL.md §6 ([NotificationType]); 0 (call) is refused here
     * because calls are the classic-Bluetooth link's job.
     */
    fun encNotification(type: Int, text: String): List<ByteArray> {
        require(type in 1..0xFF) { "notification type $type: calls (0) are not sent over C5" }
        val clean = NotificationText.sanitize(text).let { if (it.length > NOTIFY_MAX_CHARS) it.substring(0, NOTIFY_MAX_CHARS) else it }
        if (clean.isEmpty()) return emptyList()
        val payload = clean.toByteArray(Charsets.UTF_16BE)
        val out = ArrayList<ByteArray>((payload.size + NOTIFY_CHUNK_BYTES - 1) / NOTIFY_CHUNK_BYTES)
        var pos = 0
        var idx = 0
        while (pos < payload.size) {
            val end = min(payload.size, pos + NOTIFY_CHUNK_BYTES)
            val head = if (idx == 0) bytesOf(CMD_NOTIFICATION, 0, type, payload.size) else bytesOf(CMD_NOTIFICATION, idx)
            out += head + payload.copyOfRange(pos, end)
            pos = end
            idx++
        }
        return out
    }

    /** `C5 FD`: end of the notification text; the watch answers `C5 FD <type> <total_bytes>`. */
    fun encNotificationEnd(): ByteArray = bytesOf(CMD_NOTIFICATION, NOTIFY_END)

    // ------------------------------------------------------------------ decoders

    /** `A1 <ascii>` (or `A1 01 <ascii>` for the DSP version). */
    fun decVersion(b: ByteArray): String {
        if (b.size < 2) return ""
        val from = if (b.u8(1) == 0x01 && b.size > 2) 2 else 1
        return String(b, from, b.size - from, Charsets.US_ASCII)
    }

    /** `A2 <pct> [01 = charging]`; also pushed spontaneously while charging. */
    fun decBattery(b: ByteArray): BatteryInfo {
        require(b.size >= 2) { "short battery packet: ${hex(b)}" }
        return BatteryInfo(b.u8(1), b.size > 2 && b.u8(2) == 0x01)
    }

    /**
     * Decodes the classic GloryFit/UTE `26 01` watch-face capability response.
     *
     * Layout:
     *  0      26
     *  1      01
     *  2..5   dial number, u32 BE
     *  6..7   width, u16 BE
     *  8..9   height, u16 BE
     *  10     screen type
     *  11..14 maximum dial data size, u32 BE
     *  15     compatible level
     *  16     reserved/unknown
     *  17     corner angle
     */
    fun decWatchFaceConfig(b: ByteArray): WatchFaceConfigPacket {
        require(b.size >= 15) {
            "watch-face config must be at least 15 bytes, got ${b.size}: ${hex(b)}"
        }
        require(b.u8(0) == CMD_WATCH_FACE && b.u8(1) == 0x01) {
            "not a 26 01 watch-face config packet: ${hex(b)}"
        }

        return WatchFaceConfigPacket(
            dialNumber = b.u32(2),
            width = b.u16(6),
            height = b.u16(8),
            screenType = b.u8(10),
            maxDataSize = b.u32(11),
            // RB112UDG/P32 returns a valid 15-byte base packet. Some
            // GloryFit-family firmwares append these extension fields.
            compatibleLevel = if (b.size >= 16) b.u8(15) else null,
            cornerAngle = if (b.size >= 18) b.u8(17) else null,
        )
    }

    /**
     * `B2` (history) / `B1` (realtime push), 18 bytes: `op yyyy MM dd HH total16 rs re ? run16 ws we ? walk16`.
     * HH is the *start* of the hour.
     */
    fun decStepsRecord(b: ByteArray): StepsRecord {
        require(b.size == 18) { "steps record must be 18 bytes, got ${b.size}: ${hex(b)}" }
        val d = date(b, 1)
        return StepsRecord(
            time = LocalDateTime.of(d, LocalTime.of(b.u8(5) % 24, 0)),
            total = b.u16(6),
            runStart = b.u8(8), runEnd = b.u8(9), runSteps = b.u16(11),
            walkStart = b.u8(13), walkEnd = b.u8(14), walkSteps = b.u16(16),
        )
    }

    /** Expands `count` ten-minute bins that *end* at [end]; 0 and 0xFF mean "no sample". */
    private fun tenMinuteBins(b: ByteArray, from: Int, end: LocalDateTime): List<Pair<LocalDateTime, Int>> {
        val n = b.size - from
        var t = end.minusMinutes(10L * n - 10)
        val out = ArrayList<Pair<LocalDateTime, Int>>(n)
        for (i in from until b.size) {
            val v = b.u8(i)
            if (v != 0 && v != NO_SAMPLE) out += t to v
            t = t.plusMinutes(10)
        }
        return out
    }

    /**
     * `F7 yyyy MM dd HH <12 x hr>` (18 bytes): 10-minute bins ending at HH:00, i.e. HH-1:10 … HH:00 (PROTOCOL.md §6a).
     * HH = 00 means midnight at the end of the previous day (the date byte is already the next day).
     * Gadgetbridge reads byte 1 as a 0x07 "data" marker; it is really the year's high byte.
     */
    fun decHr24Record(b: ByteArray): List<HrRecord> {
        require(b.size == 18) { "bad hr24 record: ${hex(b)}" }
        val d = date(b, 1)
        val end = LocalDateTime.of(d, LocalTime.of(b.u8(5) % 24, 0))
        return tenMinuteBins(b, 6, end).map { HrRecord(it.first, it.second) }
    }

    /** `34 FA yyyy MM dd HH mm <12 x spo2>` (20 bytes): 10-minute bins ending at HH:mm (same year-byte caveat as F7). */
    fun decSpo2Record(b: ByteArray): List<Spo2Record> {
        require(b.size == 20) { "bad spo2 record: ${hex(b)}" }
        val d = date(b, 2)
        val end = LocalDateTime.of(d, LocalTime.of(b.u8(6) % 24, b.u8(7) % 60))
        return tenMinuteBins(b, 8, end).map { Spo2Record(it.first, it.second) }
    }

    /** `31 01 yyyy MM dd <n>`: the sleep session's date (the morning) and the number of stage entries to follow. */
    fun decSleepInfo(b: ByteArray): SleepSessionInfo? {
        if (b.size < 7 || b.u8(0) != CMD_SLEEP_INFO || b.u8(1) != 0x01) return null
        return SleepSessionInfo(date(b, 2), b.u8(6))
    }

    /**
     * `32 <HH mm stage 01 dur16> x n` on the data channel. Times after noon (HH > 12) before the first
     * post-midnight entry belong to the evening *before* [sessionDate]; stage codes as delivered (1..4).
     */
    fun decSleepStages(sessionDate: LocalDate, b: ByteArray): List<SleepStageRecord> {
        require(b.isNotEmpty() && (b.size - 1) % 6 == 0) { "bad sleep stages payload: ${hex(b)}" }
        val out = ArrayList<SleepStageRecord>((b.size - 1) / 6)
        var afterMidnight = false
        var o = 1
        while (o + 5 < b.size) {
            val hh = b.u8(o)
            val mm = b.u8(o + 1)
            val stage = b.u8(o + 2)
            val dur = b.u16(o + 4)
            var day = sessionDate
            if (hh > 12 && !afterMidnight) day = sessionDate.minusDays(1) else afterMidnight = true
            out += SleepStageRecord(LocalDateTime.of(day, LocalTime.of(hh % 24, mm % 60)), stage, dur)
            o += 6
        }
        return out
    }

    /**
     * Unsolicited `F7` pushes:
     *  - `F7 03 yyyy MM dd HH <bin> <hr>`: automatic sample. Byte 7 is the 10-minute bin index (0-5) within hour HH
     *    (pushed 18:48 as `12 04` = 18:40, 19:58 as `13 05` = 19:50); values above 5 are treated as minutes.
     *  - `F7 04 yyyy MM dd HH mm <max> <min> <avg>`: today's summary.
     */
    fun decHrPush(b: ByteArray): HrPush? {
        if (b.size < 9 || b.u8(0) != CMD_HR24 || (b.u8(1) != 0x03 && b.u8(1) != 0x04)) return null
        val d = date(b, 2)
        val hh = b.u8(6).toLong()
        val mm = b.u8(7)
        if (b.u8(1) == 0x03) {
            val minutes = if (mm <= 5) mm * 10 else mm
            return HrPush.Sample(d.atStartOfDay().plusHours(hh).plusMinutes(minutes.toLong()), b.u8(8))
        }
        val time = d.atStartOfDay().plusHours(hh).plusMinutes(mm.toLong())
        return if (b.size >= 11) HrPush.Summary(time, b.u8(8), b.u8(9), b.u8(10)) else null
    }

    /**
     * `FD 48 AA 00 <(id, enabled, order) x n>` on the data channel: the watch's sport-mode menu. ids are 1-based,
     * order is the position on the watch (0 = hidden). A final `FD 48 AA FD ..` ends the list (returns empty).
     */
    fun decSportList(b: ByteArray): List<SportListEntry> {
        if (b.size < 4 || b.u8(0) != CMD_SPORT || b.u8(1) != SPORT_LIST || b.u8(2) != QUERY || b.u8(3) == FETCH_END) {
            return emptyList()
        }
        val out = ArrayList<SportListEntry>()
        var i = 4
        while (i + 2 < b.size) {
            out += SportListEntry(b.u8(i), b.u8(i + 1) == 1, b.u8(i + 2))
            i += 3
        }
        return out
    }

    /**
     * `E5 11 00 <hr>` (live HR, one per second). Null if the layout is something else (e.g. the `E5 11` echo).
     * The watch sends `E5 11 00 00` (= 0 bpm) during its ~10 s warm-up; callers should drop zeros.
     */
    fun decRtHr(b: ByteArray): Int? = if (b.size == 4 && b.u8(1) == 0x11) b.u8(3) else null

    /**
     * `FD <type> <hr> cal16 pace_min pace_sec steps24 count16 km km_frac2` (exactly 14 bytes, irregular ~1/s during a
     * workout): realtime workout data from the watch (docs/PROTOCOL.md §5). Byte 1 is the sport id ([SportTypes]),
     * not a constant 0x01 — verified on the wrist 2026-09-05: an Outdoor Walking workout (`FD 11 23 01`) pushes
     * `FD 23 5C 00 …` (HR 92) — so only the length tells this packet apart, see [isSportRt]. The Ryze Wave zeros
     * everything but the HR during a phone-driven workout; the other offsets follow the vendor SDK's realtime parser.
     */
    fun decSportRt(b: ByteArray): SportRtData {
        require(isSportRt(b)) { "not a $SPORT_RT_LEN-byte sport rt packet: ${hex(b)}" }
        return SportRtData(
            sportType = b.u8(1),
            hr = b.u8(2),
            calories = b.u16(3),
            paceSecPerKm = b.u8(5) * 60 + b.u8(6),
            steps = (b.u8(7) shl 16) or (b.u8(8) shl 8) or b.u8(9),
            count = b.u16(10),
            distanceMeters = b.u8(12) * 1000.0 + b.u8(13) * 10.0,
            raw = hex(b),
        )
    }

    /** True for the 14-byte realtime workout push, whatever its sport type. */
    fun isSportRt(b: ByteArray): Boolean = b.size == SPORT_RT_LEN && b.u8(0) == CMD_SPORT

    /** `FD AA <state> <type>`: reply to [encSportQuery]. */
    fun decSportState(b: ByteArray): SportState? =
        if (b.size >= 4 && b.u8(0) == CMD_SPORT && b.u8(1) == QUERY) SportState(b.u8(2), b.u8(3)) else null

    /**
     * `FD <state> <type> <interval>` echo of a control command (state 00/11/33: exactly 4 bytes; pause 22: 4 bytes or
     * 13 with `hh mm ss …` appended, docs/PROTOCOL.md §5) or of an `FD 44` metrics push (7 or 13 bytes, the bytes
     * we sent). The 14-byte realtime push ([isSportRt]) is never an echo. Longer `FD 00 …` packets are workout-history
     * chunks ([decSportHistoryChunk]), never echoes. Caveat: a 4-byte history chunk holding a single HR value
     * (`FD 00 03 76`, seen 2026-09-04) cannot be told from a stop echo without knowing what was requested.
     */
    fun decSportControl(b: ByteArray): SportControl? {
        if (b.size < 4 || b.u8(0) != CMD_SPORT) return null
        val ok = when (b.u8(1)) {
            // The app's own echoes are 4 B; anything the WATCH originates (a button press on its paused screen) —
            // and its echo of the app's resume — come as the 13-byte form `FD <op> <type> <ivl> hh mm ss cal16 …`:
            // verified 2026-09-05 (six 13-byte FD 33 and seven 13-byte FD 22 from the wrist). Accept both for every
            // control op, otherwise a watch resume is dropped as Unknown and the phone stays paused (bug, 2026-09-06).
            SPORT_STOP, SPORT_START, SPORT_RESUME, SPORT_PAUSE -> b.size == 4 || b.size == 13
            SPORT_UPDATE -> b.size == 7 || b.size == 13
            else -> false
        }
        return if (ok) SportControl(b.u8(1), b.u8(2), b.u8(3)) else null
    }

    /**
     * `FD FA <since6> <u16>`: the watch's first reply to [encFetchSportHistory] (`FDFA 07EA0904 0000 0003` on
     * 2026-09-04). The trailing value is returned as [count]; its meaning is unverified (that reply was followed by a
     * single 4-part record).
     */
    fun decSportHistoryStart(b: ByteArray): Int? {
        if (b.size < 3 || b.u8(0) != CMD_SPORT || b.u8(1) != FETCH_START) return null
        return if (b.size >= 10) b.u16(8) else b.u8(b.size - 1)
    }

    /** `FD 00 <part> <payload…>` (5+ bytes): one chunk of a workout-history record; see [SportHistoryChunk]. */
    fun decSportHistoryChunk(b: ByteArray): SportHistoryChunk? {
        if (b.size < 5 || b.u8(0) != CMD_SPORT || b.u8(1) != SPORT_STOP) return null
        return SportHistoryChunk(b.u8(2), hex(b.copyOfRange(3, b.size)))
    }

    /**
     * Part-0 payload `01 <u16> yyyy MM dd HH mm ss …` -> sport type, the u16 (duration? record id) and the start
     * time. Consistent with the three records captured on 2026-09-04 (15:33:20, 16:17:06, 17:35:47), otherwise unverified.
     */
    fun decSportHistoryHeader(chunk: SportHistoryChunk): SportHistoryHeader? {
        val p = chunk.payload
        if (chunk.part != 0 || p.size < 10) return null
        val d = date(p, 3)
        return SportHistoryHeader(p.u8(0), p.u16(1), LocalDateTime.of(d, LocalTime.of(p.u8(7) % 24, p.u8(8) % 60, p.u8(9) % 60)))
    }

    /**
     * `34 11 <err> <spo2>` during a test, `34 00 <err> <spo2>` = final; 2-byte forms are just acks.
     * The watch emits a spurious `34 00 FF FF` ~0.3 s after the `34 11` ack — ignore a FINAL with err 0xFF
     * that arrives within ~3 s of starting; the real result comes ~60 s later.
     */
    fun decSpo2Result(b: ByteArray): Spo2Result {
        require(b.size >= 2) { "short SpO2 packet: ${hex(b)}" }
        if (b.size < 4) {
            return Spo2Result(if (b.u8(1) == 0x11) Spo2Phase.STARTED else Spo2Phase.STOPPED, null, null)
        }
        val err = b.u8(2)
        val phase = if (b.u8(1) == 0x11) Spo2Phase.MEASURING else Spo2Phase.FINAL
        return Spo2Result(phase, if (err == 0) b.u8(3) else null, err)
    }

    /**
     * `38 01 <name:20> <mac6> <bt3_on> <b29> <b30>` (31 bytes) on the data channel: classic-Bluetooth info.
     * Seen: `… "Ryze Wave(ID-91E5)" 00 00 7802B73791E5 01 00 00` (radio on, phone not paired).
     */
    fun decBt3Info(b: ByteArray): Bt3Info? {
        if (b.size < 28 || b.u8(0) != CMD_BT3 || b.u8(1) != 0x01) return null
        var end = 2
        while (end < 22 && b.u8(end) != 0) end++
        val name = String(b, 2, end - 2, Charsets.UTF_8).trim()
        val mac = (22 until 28).joinToString(":") { String.format(Locale.ROOT, "%02X", b.u8(it)) }
        return Bt3Info(
            name = name,
            mac = mac,
            bt3On = b.size > 28 && b.u8(28) == 0x01,
            b29 = if (b.size > 29) b.u8(29) else 0,
            b30 = if (b.size > 30) b.u8(30) else 0,
        )
    }

    /** GATT read of 34F1: max data-channel packet length (u16 BE) plus an 8th feature word in the last 3 bytes. */
    fun decDataChannelRead(b: ByteArray): DataChannelInfo {
        require(b.size >= 2) { "short 34F1 read: ${hex(b)}" }
        val word8 = if (b.size >= 20) (b.u8(17) shl 16) or (b.u8(18) shl 8) or b.u8(19) else 0
        return DataChannelInfo(b.u16(0), word8)
    }

    // ------------------------------------------------------------------ fetch-end predicates (for collect())
    /** `B2 FD xx` ends a steps fetch. */
    fun isStepsEnd(d: ByteArray): Boolean = d.size == 3 && d.u8(0) == CMD_STEPS && d.u8(1) == FETCH_END
    /** `F7 FD xx` ends a 24 h HR fetch. */
    fun isHr24End(d: ByteArray): Boolean = d.size == 3 && d.u8(0) == CMD_HR24 && d.u8(1) == FETCH_END
    /** `34 FA FD xx` ends a SpO2 fetch. */
    fun isSpo2End(d: ByteArray): Boolean =
        d.size >= 3 && d.u8(0) == CMD_SPO2 && d.u8(1) == FETCH_START && d.u8(2) == FETCH_END
    /** `31 02` ends a sleep fetch. */
    fun isSleepEnd(d: ByteArray): Boolean = d.size >= 2 && d.u8(0) == CMD_SLEEP_INFO && d.u8(1) == 0x02
    /** `FD 48 AA FD …` ends the sport list. */
    fun isSportListEnd(d: ByteArray): Boolean =
        d.size >= 4 && d.u8(0) == CMD_SPORT && d.u8(1) == SPORT_LIST && d.u8(2) == QUERY && d.u8(3) == FETCH_END
    /** `FD FD xx` ends a workout-history fetch (`FD FA …`). */
    fun isSportHistoryEnd(d: ByteArray): Boolean = d.size == 3 && d.u8(0) == CMD_SPORT && d.u8(1) == FETCH_END

    // ------------------------------------------------------------------ names for logging
    val OPNAME: Map<Int, String> = mapOf(
        CMD_VERSION to "VERSION", CMD_BATTERY to "BATTERY", CMD_TIME to "TIME", CMD_USER_INFO to "USER_INFO",
        CMD_STEP_STATUS to "STEP_STATUS", CMD_VIBRATE to "VIBRATE", CMD_RT_STEPS to "RT_STEPS", CMD_STEPS to "STEPS",
        CMD_PASSWORD to "PASSWORD", CMD_HR_MODE to "HR_MODE", CMD_RT_HR to "RT_HR", CMD_HR24 to "HR24",
        CMD_HR_SINGLE to "HR", CMD_SPORT to "SPORT", CMD_SLEEP_INFO to "SLEEP_INFO", CMD_SLEEP_STAGES to "SLEEP_STAGES",
        CMD_SPO2 to "SPO2", CMD_GOALS to "GOALS", CMD_ACTION to "ACTION", CMD_CAMERA to "CAMERA",
        CMD_CALL_STATUS to "CALL", CMD_NOTIFICATION to "NOTIFY", CMD_BT3 to "BT3", CMD_SEDENTARY to "SEDENTARY",
        CMD_DRINK_WATER to "DRINK_WATER", CMD_LANGUAGE to "LANGUAGE", CMD_FACTORY_RESET to "FACTORY_RESET",
        CMD_TIME_FORMAT to "TIME_FORMAT", CMD_MOOD to "MOOD",
    )

    fun opName(op: Int): String = OPNAME[op] ?: String.format(Locale.ROOT, "%02X", op and 0xFF)

    /** One-line description of a packet for logs: `HR24 f707ea…`. */
    fun describe(b: ByteArray): String = if (b.isEmpty()) "<empty>" else "${opName(b.u8(0))} ${hex(b)}"
}

/**
 * Feature bitmap: the 20-byte GATT read of 33F1, split from the *end* into seven 24-bit words
 * (word 7 is the leading 16 bits). `has(word, bit)` mirrors the SDK's `isSupportFunction`.
 * Observed on the Ryze Wave: FL1=0x4BA1D4 FL2=0x005D78 FL3=0xFED921 FL4=0x756EDF FL5=0x0C3943 FL6=0x642A21 FL7=0x00080A.
 */
data class Features(val words: Map<Int, Int>, val rawHex: String) {

    fun word(n: Int): Int = words[n] ?: 0

    fun has(word: Int, bit: Int): Boolean = (word(word) and bit) == bit

    /** FL1 & 1: the watch requires the `D5` password handshake (clear on the Ryze Wave). */
    val password: Boolean get() = has(1, 1)
    val running: Boolean get() = has(1, 256)
    val sleep1hMerge: Boolean get() = has(1, 1024)
    /** FL5 & 2: the watch accepts `FD 44` workout metric pushes. */
    val sportControlSync: Boolean get() = has(5, 2)
    val accountId: Boolean get() = has(5, 4)
    /** FL4 & 0x2000: fetch commands carry a 6-byte since-stamp. */
    val syncTimestamp: Boolean get() = has(4, 8192)
    /** FL4 & 0x40000: sleep via `31 01` (stages) instead of the legacy `B3 FA`. */
    val sleepV2: Boolean get() = has(4, 262144)
    val rtHr24h: Boolean get() = has(2, 16384)
    /** FL2 & 0x1000: the vendor app uses gender-specific walk/run stride factors (PROTOCOL.md §9). */
    val genderStride: Boolean get() = has(2, 0x1000)

    override fun toString(): String {
        val sb = StringBuilder()
        for (n in 1..7) {
            if (n > 1) sb.append(' ')
            sb.append(String.format(Locale.ROOT, "FL%d=0x%06X", n, word(n)))
        }
        sb.append(" password=").append(password)
            .append(" sync_ts=").append(syncTimestamp)
            .append(" sleep_v2=").append(sleepV2)
            .append(" sport_sync=").append(sportControlSync)
        return sb.toString()
    }

    companion object {
        fun parse(raw: ByteArray): Features {
            require(raw.size == 20) { "feature bitmap should be 20 bytes, got ${raw.size}: ${Protocol.hex(raw)}" }
            val w = HashMap<Int, Int>()
            w[7] = raw.u16(0)
            var o = 2
            for (n in 6 downTo 1) {
                w[n] = (raw.u8(o) shl 16) or (raw.u8(o + 1) shl 8) or raw.u8(o + 2)
                o += 3
            }
            return Features(w, Protocol.hex(raw))
        }

        fun parseHex(hex: String): Features = parse(Protocol.fromHex(hex))
    }
}
