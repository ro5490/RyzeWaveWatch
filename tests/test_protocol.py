"""Offline codec tests using packets from Gadgetbridge's dissector comments and our own captures."""

import datetime as dt

from ryzewave import protocol as P


def test_version_battery():
    assert (
        P.dec_version(
            bytes.fromhex("a152483238314c574f56303038343734")
        )
        == "RH281LWOV008474"
    )

    # A1 01 <ascii>: DSP version, sub-byte stripped
    assert (
        P.dec_version(
            bytes.fromhex("a10156312e30")
        )
        == "V1.0"
    )

    assert P.dec_battery(
        bytes.fromhex("a248")
    ) == (0x48, False)

    assert P.dec_battery(
        bytes.fromhex("a25201")
    ) == (0x52, True)


def test_time():
    b = P.enc_set_time(
        dt.datetime(
            2025,
            7,
            3,
            21,
            38,
            0,
        )
    )

    assert b.hex() == "a307e90703152600"


def test_user_info():
    b = P.enc_user_info(
        height_cm=170,
        weight_kg=70,
        step_goal=8000,
        age=56,
        male=False,
    )

    # byte 11 = ff = high alarm off
    assert (
        b.hex()
        == "a900aa00460500001f4001ff00380200020100"
    )

    # alarms on:
    # high 160 (0xa0) at byte 11,
    # low 45 (0x2d) at byte 18
    assert (
        P.enc_user_info(
            170,
            70,
            8000,
            56,
            False,
            hr_high=160,
            hr_low=45,
        ).hex()
        == "a900aa00460500001f4001a000380200020" + "12d"
    )


def test_steps_record():
    r = P.dec_steps_record(
        bytes.fromhex(
            "b207e907021700712d31000043313b00002e"
        )
    )

    assert (
        r.when
        == dt.datetime(
            2025,
            7,
            2,
            23,
        )
    )

    assert r.total == 0x71


def test_hr24():
    out = P.dec_hr24_record(
        bytes.fromhex(
            "f707e90703083030315c2f2f2e2f2f353331"
        )
    )

    # 07:40 -> 53 (0x35), 07:20 -> 47?
    # per Gadgetbridge comment;
    # last value 0x31 = 49 at 08:00
    assert out[-1] == (
        dt.datetime(
            2025,
            7,
            3,
            8,
            0,
        ),
        0x31,
    )

    assert out[0] == (
        dt.datetime(
            2025,
            7,
            3,
            6,
            10,
        ),
        0x30,
    )


def test_spo2():
    out = P.dec_spo2_record(
        bytes.fromhex(
            "34fa07e907030400ffff60ffff61ffff61ffff60"
        )
    )

    assert out[-1] == (
        dt.datetime(
            2025,
            7,
            3,
            4,
            0,
        ),
        0x60,
    )

    assert len(out) == 4


def test_features():
    f = P.Features.parse(
        bytes.fromhex(
            "080a"
            + "642a21"
            + "0c3943"
            + "756edf"
            + "fed921"
            + "005d78"
            + "000001"
        )
    )

    assert f.words[7] == 0x080A
    assert f.words[6] == 0x642A21
    assert f.words[1] == 1

    assert f.password
    assert f.sync_timestamp
    assert f.sleep_v2
    assert f.sport_control_sync
    assert not f.account_id


def test_password():
    assert (
        P.enc_password_auth("1234").hex()
        == "d501"
        + bytes(
            a ^ b
            for a, b in zip(
                b"1234",
                b"UTE8",
            )
        ).hex()
    )

    assert (
        P.enc_password_input("5678").hex()
        == "d50235363738"
    )


def test_sport():
    assert (
        P.enc_sport_control(
            P.SPORT_START,
            1,
            1,
        ).hex()
        == "fd110101"
    )

    assert (
        P.enc_sport_update(
            sport_type=1,
            duration_s=27,
        ).hex()
        == "fd440101"
        + "00001b"
        + "000000000000"
    )

    # km fraction carries into the whole-km byte:
    # 2999.9 m -> 3.00 km
    # 999.5 m -> 1.00 km
    assert (
        P.enc_sport_update(
            1,
            0,
            0,
            2999.9,
            0.0,
        ).hex()
        == "fd440101"
        + "000000"
        + "0000"
        + "0300"
        + "0000"
    )

    assert (
        P.enc_sport_update(
            1,
            0,
            0,
            999.5,
            0.0,
        ).hex()
        == "fd440101"
        + "000000"
        + "0000"
        + "0100"
        + "0000"
    )

    assert (
        P.enc_sport_update(
            1,
            3725,
            123,
            2340.0,
            330.0,
        ).hex()
        == "fd440101"
        + "010205"
        + "007b"
        + "0222"
        + "051e"
    )

    assert (
        P.dec_sport_rt(
            bytes.fromhex(
                "fd015d0000000000000000000000"
            )
        )["hr"]
        == 93
    )


def test_sleep_stages():
    st = P.dec_sleep_stages(
        dt.date(
            2025,
            7,
            3,
        ),
        bytes.fromhex(
            "32"
            + "1700020100b4"
            + "0100010100b4"
        ),
    )

    assert (
        st[0].when
        == dt.datetime(
            2025,
            7,
            2,
            23,
            0,
        )
    )

    assert (
        st[1].when
        == dt.datetime(
            2025,
            7,
            3,
            1,
            0,
        )
    )


def test_hr_push():
    r = P.dec_hr_push(
        bytes.fromhex(
            "F70307EA0904120462"
        )
    )

    assert r == {
        "kind": "sample",
        "when": dt.datetime(
            2026,
            9,
            4,
            18,
            40,
        ),
        "hr": 98,
    }

    r = P.dec_hr_push(
        bytes.fromhex(
            "F70407EA0904122B693A4B"
        )
    )

    assert r["kind"] == "summary"

    assert (
        r["max"],
        r["min"],
        r["avg"],
    ) == (
        105,
        58,
        75,
    )


def test_sport_list():
    lst = P.dec_sport_list(
        bytes.fromhex(
            "fd48aa00"
            + "010101"
            + "020102"
            + "040103"
            + "050104"
        )
    )

    assert lst == [
        (1, True, 1),
        (2, True, 2),
        (4, True, 3),
        (5, True, 4),
    ]

    assert (
        P.dec_sport_list(
            bytes.fromhex(
                "fd48aafd0900040046"
            )
        )
        == []
    )


def test_hr_mode():
    assert (
        P.enc_hr_mode(False).hex()
        == "d601"
    )

    assert (
        P.enc_hr_mode(True).hex()
        == "d602"
    )

    assert (
        P.enc_hr_timed(10).hex()
        == "d6100a"
    )


def test_spo2_auto():
    assert (
        P.enc_spo2_auto(
            True,
            10,
        ).hex()
        == "340301000a"
    )

    assert (
        P.enc_spo2_auto(
            True,
            5,
        ).hex()
        == "3403010005"
    )

    assert (
        P.enc_spo2_auto(
            False,
            30,
        ).hex()
        == "340300001e"
    )

    assert (
        P.enc_spo2_period(True).hex()
        == "3404010001173b"
    )

    assert (
        P.enc_hr_continuous(True).hex()
        == "f701"
    )


def test_sport_types_match_watch_menu():
    from ryzewave.protocol import (
        SPORT_TYPES,
        dec_sport_list,
        CMD_SPORT,
    )

    # FD 48 AA reply captured from the watch
    # (70 triples: id, enabled, menu position)
    ids = [
        0x01,
        0x02,
        0x04,
        0x05,
        0x07,
        0x08,
        0x09,
        0x0A,
        0x0B,
        0x0C,
        0x0D,
        0x0E,
        0x0F,
        0x10,
        0x12,
        0x13,
        0x14,
        0x15,
        0x17,
        0x18,
        0x19,
        0x1B,
        0x1C,
        0x1E,
        0x1F,
        0x22,
        0x23,
        0x24,
        0x25,
        0x27,
        0x28,
        0x29,
        0x2C,
        0x2D,
        0x2E,
        0x34,
        0x35,
        0x37,
        0x39,
        0x3A,
        0x3F,
        0x40,
        0x41,
        0x44,
        0x46,
        0x48,
        0x4B,
        0x4D,
        0x4E,
        0x50,
        0x51,
        0x55,
        0x56,
        0x58,
        0x59,
        0x5A,
        0x60,
        0x61,
        0x62,
        0x63,
        0x65,
        0x68,
        0x6A,
        0x6B,
        0x6C,
        0x6D,
        0x6F,
        0x71,
        0x72,
        0x73,
    ]

    pkt = (
        bytes(
            [
                CMD_SPORT,
                0x48,
                0xAA,
                0x00,
            ]
        )
        + b"".join(
            bytes(
                [
                    i,
                    1,
                    n + 1,
                ]
            )
            for n, i in enumerate(ids)
        )
    )

    lst = dec_sport_list(pkt)

    assert len(lst) == 70
    assert [x[0] for x in lst] == ids
    assert [x[2] for x in lst] == list(
        range(
            1,
            71,
        )
    )

    assert set(SPORT_TYPES) == set(ids)

    assert (
        SPORT_TYPES[0x01]
        == "Outdoor Running"
    )

    assert (
        SPORT_TYPES[0x23]
        == "Outdoor Walking"
    )

    assert (
        SPORT_TYPES[0x73]
        == "Marathon"
    )


def test_sport_rt_carries_sport_type():
    from ryzewave.protocol import (
        dec_sport_rt,
        is_sport_rt,
    )

    # Captured 2026-09-05 08:23:06 during
    # an FD 11 23 01 (Outdoor Walking) workout.
    b = bytes.fromhex(
        "fd235c0000000000000000000000"
    )

    assert is_sport_rt(b)

    d = dec_sport_rt(b)

    assert d["sport_type"] == 0x23
    assert d["sport"] == "Outdoor Walking"
    assert d["hr"] == 92
    assert d["calories"] == 0
    assert d["steps"] == 0
    assert d["distance_m"] == 0.0

    # Synthetic full packet:
    # type 1,
    # HR 150,
    # 123 kcal,
    # pace 5:30,
    # 4321 steps,
    # count 7,
    # 2.34 km
    b2 = bytes(
        [
            0xFD,
            0x01,
            150,
            0x00,
            123,
            5,
            30,
            0x00,
            0x10,
            0xE1,
            0x00,
            7,
            2,
            34,
        ]
    )

    d2 = dec_sport_rt(b2)

    assert (
        d2["calories"],
        d2["pace_s_per_km"],
        d2["steps"],
        d2["count"],
        d2["distance_m"],
    ) == (
        123,
        330,
        4321,
        7,
        2340.0,
    )

    assert not is_sport_rt(
        bytes.fromhex(
            "fd112301"
        )
    )

    assert not is_sport_rt(
        bytes.fromhex(
            "fd220101000000000000000000"
        )
    )


def test_enc_notification_chunks_and_limits():
    from ryzewave.protocol import enc_notification

    # "Dapper's SmartTrax: test" is 24 characters.
    # UTF-16BE therefore produces 48 bytes.
    #
    # C5 packets:
    #   C5 00 04 30 + 16 bytes
    #   C5 01       + 16 bytes
    #   C5 02       + 16 bytes
    #   C5 FD
    pk = enc_notification(
        "Dapper's SmartTrax: test"
    )

    assert [
        p[:2].hex()
        for p in pk
    ] == [
        "c500",
        "c501",
        "c502",
        "c5fd",
    ]

    # 0x30 = 48 UTF-16BE payload bytes.
    assert pk[0][2] == 4
    assert pk[0][3] == 48

    # First packet:
    # C5 00 + type + length + 16 payload bytes.
    assert len(pk[0]) == 20

    # Third packet:
    # C5 02 + final 16 payload bytes.
    assert len(pk[2]) == 18

    reconstructed = b"".join(
        [pk[0][4:]]
        + [
            p[2:]
            for p in pk[1:-1]
        ]
    ).decode("utf-16-be")

    assert (
        reconstructed
        == "Dapper's SmartTrax: test"
    )

    long = enc_notification(
        "x" * 200
    )

    # Protocol length field is one byte.
    # Limit to 127 UTF-16BE characters
    # = 254 payload bytes
    # = 16 data chunks.
    assert long[0][3] == 254

    # One initial packet + 15 continuation
    # packets + C5 FD.
    assert len(long) == 1 + 15 + 1

    import pytest

    with pytest.raises(ValueError):
        enc_notification(
            "hi",
            ntype=0,
        )
