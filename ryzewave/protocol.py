"""Ryze Wave (UTE / GloryFit family) BLE protocol: constants, encoders, decoders.

Pure functions only; no Bluetooth here. Sources: Gadgetbridge GloryFit driver,
the decompiled Ryze Fit SDK (com.yc.pedometer.sdk) and our own captures.
"""
from __future__ import annotations
import datetime as _dt
from dataclasses import dataclass, field

# --- GATT ---------------------------------------------------------------
SVC_CMD = "000055ff-0000-1000-8000-00805f9b34fb"
CH_CMD_WRITE = "000033f1-0000-1000-8000-00805f9b34fb"   # also: GATT read -> feature bitmap
CH_CMD_NOTIFY = "000033f2-0000-1000-8000-00805f9b34fb"
SVC_DATA = "000056ff-0000-1000-8000-00805f9b34fb"
CH_DATA_WRITE = "000034f1-0000-1000-8000-00805f9b34fb"  # also: GATT read -> max packet len (u16 BE)
CH_DATA_NOTIFY = "000034f2-0000-1000-8000-00805f9b34fb"

# --- opcodes -------------------------------------------------------------
CMD_VERSION = 0xA1
CMD_BATTERY = 0xA2
CMD_TIME = 0xA3
CMD_USER_INFO = 0xA9
CMD_STEP_STATUS = 0xAA
CMD_VIBRATE = 0xAB
CMD_FACTORY_RESET = 0xAD      # never send casually
CMD_LANGUAGE = 0xAF
CMD_RT_STEPS = 0xB1
CMD_STEPS = 0xB2
CMD_CALL_STATUS = 0xC1
CMD_CAMERA = 0xC4
CMD_NOTIFICATION = 0xC5
CMD_ACTION = 0xD1
CMD_SEDENTARY = 0xD3
CMD_PASSWORD = 0xD5
CMD_HR_MODE = 0xD6
CMD_RT_HR = 0xE5
CMD_HR_SINGLE = 0xE6
CMD_HR24 = 0xF7
CMD_SPORT = 0xFD
CMD_SLEEP_INFO = 0x31
CMD_SLEEP_STAGES = 0x32
CMD_SPO2 = 0x34
CMD_GOALS = 0x3F
CMD_BT3 = 0x38                # classic-Bluetooth (audio/calls) control, data channel 34F1

FETCH_START = 0xFA
FETCH_DATA = 0x07
FETCH_END = 0xFD
QUERY = 0xAA

# sport (FD) states
SPORT_STOP, SPORT_START, SPORT_PAUSE, SPORT_RESUME, SPORT_UPDATE = 0x00, 0x11, 0x22, 0x33, 0x44
SPORT_RT_DATA = 0x01

# Sport-mode ids (`FD 11 <type>`, `FD 48` list id, byte 1 of the realtime push), as numbered by the Ryze Wave itself:
# the FD 48 AA reply lists (id, enabled, menu position) for 70 sports and the names below are the watch's own menu,
# in menu order, transcribed from the wrist on 2026-09-05. Ids have gaps (they are the vendor's global sport ids).
SPORT_TYPES: dict[int, str] = {
    0x01: "Outdoor Running", 0x02: "Cycling", 0x04: "Swimming", 0x05: "Badminton", 0x07: "Tennis",
    0x08: "Hiking", 0x09: "Walking", 0x0A: "Basketball", 0x0B: "Soccer", 0x0C: "Baseball",
    0x0D: "Volleyball", 0x0E: "Cricket", 0x0F: "Rugby", 0x10: "Hockey", 0x12: "Spinning",
    0x13: "Yoga", 0x14: "Sit-ups", 0x15: "Treadmill", 0x17: "Boating", 0x18: "Jumping Jacks",
    0x19: "Free Training", 0x1B: "Indoor Running", 0x1C: "Strength Training", 0x1E: "Horse Riding", 0x1F: "Elliptical",
    0x22: "Boxing", 0x23: "Outdoor Walking", 0x24: "Trail Running", 0x25: "Skiing", 0x27: "Taekwondo",
    0x28: "VO2 max Test", 0x29: "Rower", 0x2C: "Athletics", 0x2D: "Waist Training", 0x2E: "Karate",
    0x34: "Physical Training", 0x35: "Archery", 0x37: "Aerobic Combo", 0x39: "Street Dancing", 0x3A: "Kick Boxing",
    0x3F: "Handball", 0x40: "Bowling", 0x41: "Racquetball", 0x44: "Snowboarding", 0x46: "American Football",
    0x48: "Fishing", 0x4B: "Golf", 0x4D: "Downhill Skiing", 0x4E: "Snow Sports", 0x50: "Core Training",
    0x51: "Skating", 0x55: "Kickboxing Aerobics", 0x56: "Lacrosse", 0x58: "Wrestling", 0x59: "Fencing",
    0x5A: "Softball", 0x60: "Pickleball", 0x61: "HIIT", 0x62: "Shooting", 0x63: "Judo",
    0x65: "Skateboarding", 0x68: "Parkour", 0x6A: "Surfing", 0x6B: "Snorkeling", 0x6C: "Pull-up",
    0x6D: "Push-up", 0x6F: "Rock Climbing", 0x71: "Bungee Jumping", 0x72: "Long Jump", 0x73: "Marathon",
}
# The ones a runner/walker/cyclist actually picks; the app's short list.
SPORT_TYPES_POPULAR = (0x01, 0x23, 0x09, 0x02, 0x08, 0x24, 0x15, 0x1B, 0x04, 0x19)

PASSWORD_XOR = b"UTE8"
DEFAULT_PASSWORD = "1234"


# --- helpers -------------------------------------------------------------
def _date(b: bytes, o: int) -> _dt.date:
    return _dt.date((b[o] << 8) | b[o + 1], b[o + 2], b[o + 3])


def _ts_bytes(since: _dt.datetime | None) -> bytes:
    """6-byte 'sync since' stamp: yyyy(BE) MM dd HH mm. Zeros = everything."""
    if since is None:
        return bytes(6)
    return bytes([since.year >> 8, since.year & 0xFF, since.month, since.day, since.hour, since.minute])


# --- feature bitmap (GATT read of 33F1) ------------------------------------
@dataclass
class Features:
    raw: bytes
    words: dict = field(default_factory=dict)  # 1..7

    @classmethod
    def parse(cls, raw: bytes) -> "Features":
        if len(raw) != 20:
            raise ValueError(f"feature bitmap should be 20 bytes, got {len(raw)}: {raw.hex()}")
        w = {7: int.from_bytes(raw[0:2], "big")}
        for i, n in enumerate((6, 5, 4, 3, 2, 1)):
            o = 2 + 3 * i
            w[n] = int.from_bytes(raw[o:o + 3], "big")
        return cls(raw, w)

    def has(self, word: int, bit: int) -> bool:
        return (self.words.get(word, 0) & bit) == bit

    # named bits we have identified in the SDK
    @property
    def password(self) -> bool: return self.has(1, 1)
    @property
    def running(self) -> bool: return self.has(1, 256)
    @property
    def sleep_1h_merge(self) -> bool: return self.has(1, 1024)
    @property
    def sport_control_sync(self) -> bool: return self.has(5, 2)      # FD 44 metrics push
    @property
    def account_id(self) -> bool: return self.has(5, 4)
    @property
    def sync_timestamp(self) -> bool: return self.has(4, 8192)      # fetch cmds carry a since-date
    @property
    def sleep_v2(self) -> bool: return self.has(4, 262144)          # 31 01 instead of B3 FA
    @property
    def rt_hr_24h(self) -> bool: return self.has(2, 16384)

    def __str__(self) -> str:
        return " ".join(f"FL{n}=0x{self.words[n]:06X}" for n in range(1, 8)) + \
            f" password={self.password} sync_ts={self.sync_timestamp} sleep_v2={self.sleep_v2} sport_sync={self.sport_control_sync}"


# --- encoders --------------------------------------------------------------
def enc_version() -> bytes: return bytes([CMD_VERSION])
def enc_dsp_version() -> bytes: return bytes([CMD_VERSION, 0x01])
def enc_battery() -> bytes: return bytes([CMD_BATTERY])


def enc_set_time(t: _dt.datetime | None = None) -> bytes:
    t = t or _dt.datetime.now()
    return bytes([CMD_TIME, t.year >> 8, t.year & 0xFF, t.month, t.day, t.hour, t.minute, t.second])


def enc_user_info(height_cm: int, weight_kg: int, step_goal: int, age: int, male: bool,
                  raise_wrist: bool = True, hr_high: int = 0, hr_low: int = 0, celsius: bool = True) -> bytes:
    """A9: 19 bytes (Gadgetbridge layout)."""
    return bytes([CMD_USER_INFO,
                  height_cm >> 8, height_cm & 0xFF, weight_kg >> 8, weight_kg & 0xFF,
                  0x05, 0x00, 0x00, step_goal >> 8, step_goal & 0xFF,
                  # byte 11 = high-HR alarm threshold, 0xFF disables it; byte 18 = low-HR alarm, 0 disables it
                  0x01 if raise_wrist else 0x00, (hr_high & 0xFF) if hr_high > 0 else 0xFF, 0x00, age & 0xFF,
                  0x01 if male else 0x02, 0x00, 0x02 if celsius else 0x01, 0x01, max(hr_low, 0) & 0xFF])


def enc_password_query() -> bytes: return bytes([CMD_PASSWORD, 0x01])
def enc_password_request_code() -> bytes: return bytes([CMD_PASSWORD, 0x02])


def enc_password_auth(password: str) -> bytes:
    """D5 01 k0..k3 with k = ascii(password) XOR 'UTE8'. Used on reconnect."""
    p = password.encode("ascii")
    if len(p) != 4:
        raise ValueError("password must be 4 ASCII chars")
    return bytes([CMD_PASSWORD, 0x01]) + bytes(a ^ b for a, b in zip(p, PASSWORD_XOR))


def enc_password_input(password: str, set_new: bool = False) -> bytes:
    """D5 02 <ascii> = input the code shown on the watch; D5 03 <ascii> = set a new one."""
    return bytes([CMD_PASSWORD, 0x03 if set_new else 0x02]) + password.encode("ascii")


def enc_fetch_steps() -> bytes: return bytes([CMD_STEPS, FETCH_START])
def enc_fetch_spo2() -> bytes: return bytes([CMD_SPO2, FETCH_START])
def enc_fetch_sleep() -> bytes: return bytes([CMD_SLEEP_INFO, 0x01])


def enc_fetch_hr24(since: _dt.datetime | None = None, with_ts: bool = True) -> bytes:
    return bytes([CMD_HR24, FETCH_START]) + (_ts_bytes(since) if with_ts else b"")


def enc_fetch_hr_single(since: _dt.datetime | None = None, with_ts: bool = True) -> bytes:
    return bytes([CMD_HR_SINGLE, FETCH_START]) + (_ts_bytes(since) if with_ts else b"")


def enc_hr_continuous(on: bool) -> bytes: return bytes([CMD_HR24, 0x01 if on else 0x02])
def enc_hr_mode(dynamic: bool) -> bytes:
    """D6 01 = static/spot HR test, D6 02 = dynamic/continuous. The vendor app sends this 1.5 s before E5 11."""
    return bytes([CMD_HR_MODE, 0x02 if dynamic else 0x01])


def enc_hr_timed(interval_min: int) -> bytes:
    """D6 10 <minutes>: automatic HR test interval (0 = off)."""
    return bytes([CMD_HR_MODE, 0x10, interval_min & 0xFF])


def enc_rt_hr(on: bool) -> bytes: return bytes([CMD_RT_HR, 0x11 if on else 0x00])
def enc_spo2_test(on: bool) -> bytes: return bytes([CMD_SPO2, 0x11 if on else 0x00])
def enc_spo2_status() -> bytes: return bytes([CMD_SPO2, QUERY])


def enc_spo2_auto(on: bool, interval_min: int = 10) -> bytes:
    """34 03 <on> <interval16 BE minutes>: automatic SpO2 sampling (vendor menu 10..360; 5 accepted on the Ryze Wave)."""
    return bytes([CMD_SPO2, 0x03, 0x01 if on else 0x00, (interval_min >> 8) & 0xFF, interval_min & 0xFF])


def enc_spo2_period(on: bool, start_hh: int = 0, start_mm: int = 1, end_hh: int = 23, end_mm: int = 59) -> bytes:
    """34 04 <on> HH mm HH mm: time window for automatic SpO2 sampling."""
    return bytes([CMD_SPO2, 0x04, 0x01 if on else 0x00, start_hh, start_mm, end_hh, end_mm])


def enc_goal(kind: str, value: int) -> bytes:
    k = {"calories": 0x03, "steps": 0x04, "distance": 0x05}[kind]
    return bytes([CMD_GOALS, k, 0x01]) + value.to_bytes(4, "big")


def enc_find_watch() -> bytes: return bytes([CMD_VIBRATE, 0, 0, 0, 1, 0x02, 0x07, 0x01])


def enc_bt3_enable(on: bool) -> bytes:
    """38 02 <01|00> on the DATA channel: turn the watch's classic Bluetooth (HFP/A2DP) on/off (SDK openDeviceBt3)."""
    return bytes([CMD_BT3, 0x02, 0x01 if on else 0x00])


def enc_bt3_query(nonce: bytes = b"\x00\x00\x00\x00") -> bytes:
    """38 01 02 00 <4 random bytes> on the DATA channel: watch replies 38 01 <name> 00 00 <mac> <bt3_on> 00 00."""
    return bytes([CMD_BT3, 0x01, 0x02, 0x00]) + bytes(nonce[:4]).ljust(4, b"\x00")


def enc_notification(text: str, ntype: int = 4) -> list[bytes]:
    """C5 phone notification, verified 2026-09-05: chunk 0 = `C5 00 <type> <total_bytes>` + 16 B of UTF-16BE, then
    `C5 <idx>` + 16 B each, then `C5 FD`. The watch acks every chunk with `C5 <idx>` and the end with `C5 FD <type> <total>`;
    send the next chunk only after its ack. total is one byte, so at most 127 UTF-16 code units; type 0 = incoming call
    (never use for app text), 4 = generic, 3 = SMS (docs/PROTOCOL.md §6)."""
    if ntype == 0:
        raise ValueError("type 0 is an incoming call")
    payload = text[:127].encode("utf-16-be")
    chunks = [payload[i:i + 16] for i in range(0, len(payload), 16)] or [b""]
    out = [bytes([CMD_NOTIFICATION, 0x00, ntype & 0xFF, len(payload)]) + chunks[0]]
    out += [bytes([CMD_NOTIFICATION, i]) + c for i, c in enumerate(chunks[1:], start=1)]
    out.append(bytes([CMD_NOTIFICATION, 0xFD]))
    return out


def enc_sport_query() -> bytes: return bytes([CMD_SPORT, QUERY])


def enc_sport_control(state: int, sport_type: int, hr_interval_s: int = 1) -> bytes:
    """FD <state> <type> <interval>. state: SPORT_START/PAUSE/RESUME/STOP."""
    return bytes([CMD_SPORT, state, sport_type & 0xFF, max(1, min(255, hr_interval_s))])


def enc_sport_update(sport_type: int, duration_s: int, calories: int = 0, distance_m: float = 0.0,
                     pace_s_per_km: float = 0.0, state: int = SPORT_UPDATE, hr_interval_s: int = 1,
                     gps: bool = True) -> bytes:
    """FD 44 …: phone -> watch live metrics during a connected-GPS workout (SDK setMultipleSportsModes2).
    Layout: FD state type interval hh mm ss [cal_hi cal_lo dist_km dist_frac2 pace_min pace_sec]"""
    h, rem = divmod(int(duration_s), 3600)
    m, s = divmod(rem, 60)
    out = bytearray([CMD_SPORT, state, sport_type & 0xFF, max(1, min(255, hr_interval_s)), h & 0xFF, m, s])
    if gps:
        km = distance_m / 1000.0
        km_int = int(km)
        km_frac = int(round((km - km_int) * 100))
        if km_frac >= 100:          # x.995 km rounds up to the next whole km (matches the Kotlin encoder)
            km_int += 1
            km_frac -= 100
        pm, ps = divmod(int(pace_s_per_km), 60)
        if pm * 60 + ps > 5999:
            pm = ps = 0
        cal = int(calories)
        out += bytes([cal >> 8, cal & 0xFF, km_int & 0xFF, km_frac, pm & 0xFF, ps])
    return bytes(out)


# --- watch screen list ("Add widget" / the swipeable cards), opcode F9 -------
# The watch owns two 48-bit bitmaps, read/written over the command channel:
#  - "supported": which screen indices the firmware can render at all (fixed capability).
#  - "enabled":   which of those the user currently has switched on.
# Query is `F9 AA`; the reply carries both bitmaps. `F9 01 <bitmap>` switches a screen on,
# `F9 02 <bitmap>` switches it off. Indices 0-31 live in the first word pair of the reply,
# 32-47 in the second (this mirrors WriteCommandToBLE.displayOrHideBraceletInterface and
# BandInterfaceSetActivity in the vendor app). Enabling a screen only works when its
# "supported" bit is set; the firmware has no page to show otherwise.
CMD_INTERFACE = 0xF9

# Index -> name, from the vendor app's R.array.bracelet_interface_text (48 entries).
# Indices 43-47 are unused placeholders in this build.
SCREEN_NAMES = [
    "bp", "heart_rate", "blood_oxygen", "calories", "distance", "weather", "sms", "features",
    "step_count", "skipping", "swim", "riding", "pingpong", "badminton", "tennis", "run",
    "gps", "device_information", "find_phone", "donot_disturb", "shut_down", "restore_settings",
    "ecg", "blood_sugar", "hiking", "basketball", "football", "baseball", "volleyball", "cricket",
    "rugby", "dancing", "mountaineering", "spinning_bike", "yoga", "sit_ups", "treadmill",
    "gymnastics", "boating", "jumping_jack", "stopwatch", "body_fat", "hockey",
    "unused43", "unused44", "unused45", "unused46", "unused47",
]


def screen_name(index: int) -> str:
    return SCREEN_NAMES[index] if 0 <= index < len(SCREEN_NAMES) else f"screen_{index}"


def enc_interface_query() -> bytes:
    """F9 AA — ask the watch which screens it supports and which are enabled."""
    return bytes([CMD_INTERFACE, QUERY])


def _interface_bitmap(index: int) -> bytearray:
    """The 18-byte payload region: the target screen's bit sits at byte (index//8)*2+1 within it."""
    body = bytearray(18)
    body[(index // 8) * 2 + 1] = 1 << (index % 8)
    return body


def enc_interface_show(index: int) -> bytes:
    """F9 01 … — switch screen [index] on (only takes effect if the firmware supports it)."""
    return bytes([CMD_INTERFACE, 0x01]) + bytes(_interface_bitmap(index))


def enc_interface_hide(index: int) -> bytes:
    """F9 02 … — switch screen [index] off. Payload is all-ones with the target bit cleared."""
    body = bytearray([0xFF]) * 18
    body[(index // 8) * 2 + 1] &= ~(1 << (index % 8)) & 0xFF
    return bytes([CMD_INTERFACE, 0x02]) + bytes(body)


@dataclass
class Screen:
    index: int
    name: str
    enabled: bool


def dec_interface(data: bytes) -> list["Screen"]:
    """Decode an `F9 AA` reply into the list of supported screens, each flagged enabled or not.
    Layout (from BandInterfaceSetActivity): supported bytes at 2,4,6,8 (0-31) and 10,12 (32-47);
    enabled bytes at 3,5,7,9 and 11,13. A screen is listed only when its supported bit is set."""
    if len(data) < 14 or data[0] != CMD_INTERFACE:
        raise ValueError("not an F9 interface reply")
    b = data
    supported = (b[2] | (b[4] << 8) | (b[6] << 16) | (b[8] << 24)) | ((b[10] | (b[12] << 8)) << 32)
    enabled = (b[3] | (b[5] << 8) | (b[7] << 16) | (b[9] << 24)) | ((b[11] | (b[13] << 8)) << 32)
    out: list[Screen] = []
    for i in range(48):
        if supported & (1 << i):
            out.append(Screen(i, screen_name(i), bool(enabled & (1 << i))))
    return out


# --- decoders --------------------------------------------------------------
@dataclass
class StepsRecord:
    when: _dt.datetime
    total: int
    run_start: int; run_end: int; run_steps: int
    walk_start: int; walk_end: int; walk_steps: int


def dec_steps_record(b: bytes) -> StepsRecord:
    """B2 / B1: 18 bytes: op yyyy MM dd HH total16 rs re ? run16 ws we ? walk16."""
    if len(b) != 18:
        raise ValueError(f"steps record must be 18 bytes, got {len(b)}")
    d = _date(b, 1)
    return StepsRecord(_dt.datetime(d.year, d.month, d.day, b[5] % 24), (b[6] << 8) | b[7],
                       b[8], b[9], (b[11] << 8) | b[12], b[13], b[14], (b[16] << 8) | b[17])


def dec_hr24_record(b: bytes) -> list[tuple[_dt.datetime, int]]:
    """F7 yyyy MM dd HH <12 x hr>: 10-minute bins ending at HH:00.
    (Gadgetbridge reads byte 1 as a 0x07 'data' marker; it is really the year's high byte, 0x07E9 = 2025.)"""
    if len(b) != 18:
        raise ValueError("bad hr24 record")
    d = _date(b, 1)
    end = _dt.datetime(d.year, d.month, d.day, b[5] % 24)
    vals = b[6:]
    t = end - _dt.timedelta(minutes=10 * len(vals) - 10)
    out = []
    for v in vals:
        if v not in (0, 0xFF):
            out.append((t, v))
        t += _dt.timedelta(minutes=10)
    return out


def dec_spo2_record(b: bytes) -> list[tuple[_dt.datetime, int]]:
    """34 FA yyyy MM dd HH mm <12 x spo2>: 10-minute bins ending at HH:mm (same year-byte caveat as F7)."""
    if len(b) != 20:
        raise ValueError("bad spo2 record")
    d = _date(b, 2)
    end = _dt.datetime(d.year, d.month, d.day, b[6] % 24, b[7] % 60)
    vals = b[8:]
    t = end - _dt.timedelta(minutes=10 * len(vals) - 10)
    out = []
    for v in vals:
        if v not in (0, 0xFF):
            out.append((t, v))
        t += _dt.timedelta(minutes=10)
    return out


@dataclass
class SleepStage:
    when: _dt.datetime
    stage: int
    minutes: int


def dec_sleep_stages(session_date: _dt.date, b: bytes) -> list[SleepStage]:
    """32 <HH mm stage 01 dur16>*n on the data channel; times after noon belong to the previous day."""
    if (len(b) - 1) % 6:
        raise ValueError("bad sleep stages payload")
    out, after_midnight = [], False
    for o in range(1, len(b), 6):
        hh, mm, stage, dur = b[o], b[o + 1], b[o + 2], (b[o + 4] << 8) | b[o + 5]
        day = session_date
        if hh > 12 and not after_midnight:
            day = session_date - _dt.timedelta(days=1)
        else:
            after_midnight = True
        out.append(SleepStage(_dt.datetime(day.year, day.month, day.day, hh % 24, mm % 60), stage, dur))
    return out


def dec_hr_push(b: bytes) -> dict | None:
    """Unsolicited F7 pushes: F7 03 date HH mm hr (auto sample) / F7 04 date HH mm max min avg (daily summary)."""
    if len(b) < 9 or b[1] not in (0x03, 0x04):
        return None
    d = _date(b, 2)
    hh, mm = b[6], b[7]
    if b[1] == 0x03:
        # F7 03: byte 7 is the 10-minute bin index within the hour (0-5), observed 2026-09-04:
        # pushed 18:48 -> "18 04" (18:40 bin), pushed 19:58 -> "19 05" (19:50 bin)
        when = _dt.datetime(d.year, d.month, d.day) + _dt.timedelta(hours=hh, minutes=(mm * 10 if mm <= 5 else mm))
        return {"kind": "sample", "when": when, "hr": b[8]}
    when = _dt.datetime(d.year, d.month, d.day) + _dt.timedelta(hours=hh, minutes=mm)
    if len(b) >= 11:
        return {"kind": "summary", "when": when, "max": b[8], "min": b[9], "avg": b[10]}
    return None


def dec_sport_list(b: bytes) -> list[tuple[int, bool, int]]:
    """FD 48 AA 00 <(id, enabled, order) x n> on the data channel: the watch's sport-mode menu.
    ids are 1-based (SDK sends id+1); order is the position on the watch (0 = hidden). A final `FD 48 AA FD ..` ends the list."""
    if len(b) < 4 or b[:3] != bytes([CMD_SPORT, 0x48, 0xAA]) or b[3] == 0xFD:
        return []
    body = b[4:]
    return [(body[i], body[i + 1] == 1, body[i + 2]) for i in range(0, len(body) - 2, 3)]


def dec_version(b: bytes) -> str:
    """A1 <ascii>, or A1 01 <ascii> for the DSP-version reply (the 0x01 sub-byte is not part of the string)."""
    start = 2 if len(b) > 2 and b[1] == 0x01 else 1
    return b[start:].decode("ascii", "replace")


def dec_battery(b: bytes) -> tuple[int, bool]:
    return b[1], (len(b) > 2 and b[2] == 0x01)


def dec_rt_hr(b: bytes) -> int | None:
    """E5 11 00 <hr> (Gadgetbridge). Returns None if the layout is unknown."""
    if len(b) == 4 and b[1] == 0x11:
        return b[3]
    return None


def dec_sport_rt(b: bytes) -> dict:
    """FD <type> <hr> cal16 pace_min pace_sec steps24 count16 km km_frac2 (14 B): realtime workout data from the watch.

    Byte 1 is the SPORT TYPE (the FD 48 id, see SPORT_TYPES), not a fixed 0x01: verified on the Ryze Wave with
    `FD 11 23 01` (Outdoor Walking) -> pushes `FD 23 5C 00 ...`. Field offsets follow the vendor SDK
    (MultipleSportsModesUtils.*Real). The Ryze Wave sends zeros for everything but HR during a phone-driven workout.
    Control echoes are 4 B (start/stop/resume) or 13 B (pause); only 14-byte FD packets are realtime data."""
    if len(b) < 3 or b[0] != CMD_SPORT:
        raise ValueError(f"not a sport packet: {b.hex()}")
    out = {"sport_type": b[1], "sport": SPORT_TYPES.get(b[1], f"type {b[1]}"), "hr": b[2], "raw": b.hex()}
    if len(b) >= 14:
        out.update({
            "calories": (b[3] << 8) | b[4],
            "pace_s_per_km": b[5] * 60 + b[6],
            "steps": (b[7] << 16) | (b[8] << 8) | b[9],
            "count": (b[10] << 8) | b[11],
            "distance_m": (b[12] + b[13] / 100.0) * 1000.0,
        })
    return out


def is_sport_rt(b: bytes) -> bool:
    """True for the 14-byte realtime workout packet (any sport type)."""
    return len(b) == 14 and b[0] == CMD_SPORT


def dec_spo2_result(b: bytes) -> dict:
    """34 11 <err> <spo2> during a test, 34 00 <err> <spo2> = final; 2-byte forms are just acks."""
    if len(b) < 4:
        return {"phase": "started" if b[1] == 0x11 else "stopped", "spo2": None}
    return {"phase": "measuring" if b[1] == 0x11 else "final", "spo2": b[3] if b[2] == 0 else None, "err": b[2]}


OPNAME = {
    CMD_VERSION: "VERSION", CMD_BATTERY: "BATTERY", CMD_TIME: "TIME", CMD_USER_INFO: "USER_INFO",
    CMD_STEP_STATUS: "STEP_STATUS", CMD_VIBRATE: "VIBRATE", CMD_RT_STEPS: "RT_STEPS", CMD_STEPS: "STEPS",
    CMD_PASSWORD: "PASSWORD", CMD_RT_HR: "RT_HR", CMD_HR24: "HR24", CMD_HR_SINGLE: "HR", CMD_SPORT: "SPORT",
    CMD_SLEEP_INFO: "SLEEP_INFO", CMD_SLEEP_STAGES: "SLEEP_STAGES", CMD_SPO2: "SPO2", CMD_GOALS: "GOALS",
    CMD_ACTION: "ACTION", CMD_CAMERA: "CAMERA", CMD_CALL_STATUS: "CALL", CMD_NOTIFICATION: "NOTIFY",
    CMD_INTERFACE: "INTERFACE",
}
