package au.buzz.ryzewave.ble

import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LinkTest {

    @Test
    fun backoffDoublesAndCaps() {
        assertEquals(2_000L, Backoff.delayMs(1))
        assertEquals(4_000L, Backoff.delayMs(2))
        assertEquals(8_000L, Backoff.delayMs(3))
        assertEquals(32_000L, Backoff.delayMs(5))
        assertEquals(60_000L, Backoff.delayMs(6))
        assertEquals(60_000L, Backoff.delayMs(40))
        assertEquals(2_000L, Backoff.delayMs(0))
    }

    @Test
    fun hexRoundTrip() {
        val b = "f7fa07ea09041226".hexToBytes()
        assertEquals(8, b.size)
        assertEquals("f7fa07ea09041226", b.toHex())
        assertEquals(
            "f7fa07ea09041226",
            "F7:FA:07:EA:09:04:12:26".hexToBytes().toHex(),
        )
        assertEquals("0A", 10.toHex2())
    }

    @Test
    fun uuidsAndChannels() {
        assertEquals(
            "000033f2-0000-1000-8000-00805f9b34fb",
            WatchChannel.CMD.notifyUuid.toString(),
        )
        assertEquals(
            WatchChannel.DATA,
            WatchChannel.forNotifyUuid(uuid16("34F2")),
        )
        assertEquals(
            WatchChannel.CMD,
            WatchChannel.forWriteUuid(uuid16("33f1")),
        )
        assertNull(
            WatchChannel.forNotifyUuid(uuid16("35f2")),
        )
        assertEquals(
            "00002902-0000-1000-8000-00805f9b34fb",
            CCCD_UUID.toString(),
        )
    }

    @Test
    fun fetchEndMatchers() {
        assertTrue(
            Matchers.isFetchEnd(
                "b2fd05".hexToBytes(),
            ),
        )
        assertTrue(
            Matchers.isFetchEnd(
                "f7fda5".hexToBytes(),
            ),
        )
        assertFalse(
            Matchers.isFetchEnd(
                "b207ea09031400840000000000062a010084".hexToBytes(),
            ),
        )
        assertTrue(
            Matchers.isSpo2FetchEnd(
                "34fafd01".hexToBytes(),
            ),
        )
        assertFalse(
            Matchers.isSpo2FetchEnd(
                "34fa07ea09031600ffff63ffffffffffffff61ff".hexToBytes(),
            ),
        )
        assertTrue(
            Matchers.isSleepEnd(
                "3102".hexToBytes(),
            ),
        )
        assertFalse(
            Matchers.isSleepEnd(
                "310107ea09041e".hexToBytes(),
            ),
        )
        assertEquals(
            LocalDate.of(2026, 9, 4),
            Matchers.sleepSessionDate(
                "310107ea09041e".hexToBytes(),
            ),
        )
        assertNull(
            Matchers.sleepSessionDate(
                "3102".hexToBytes(),
            ),
        )
    }

    @Test
    fun spo2FinalIgnoresTheEarlyBogusFailure() {
        val bogus = "3400ffff".hexToBytes()
        val result = "34000061".hexToBytes()

        assertFalse(
            Matchers.isSpo2Final(
                bogus,
                300,
            ),
        )
        assertTrue(
            Matchers.isSpo2Final(
                bogus,
                5_000,
            ),
        )
        assertTrue(
            Matchers.isSpo2Final(
                result,
                100,
            ),
        )
        assertFalse(
            Matchers.isSpo2Final(
                "3411".hexToBytes(),
                100,
            ),
        )
        assertFalse(
            Matchers.isSpo2Final(
                "f7fda5".hexToBytes(),
                100,
            ),
        )
        assertEquals(
            97,
            Matchers.spo2Percent(result),
        )
        assertNull(
            Matchers.spo2Percent(bogus),
        )
    }

    @Test
    fun sportEchoAndOpcodeMatchers() {
        assertTrue(
            Matchers.isSportEcho(0x11)(
                "fd110101".hexToBytes(),
            ),
        )
        assertFalse(
            Matchers.isSportEcho(0x11)(
                "fd01550000000000000000000000".hexToBytes(),
            ),
        )
        assertTrue(
            Matchers.isSportEcho(0x00)(
                "fd000101".hexToBytes(),
            ),
        )
        assertTrue(
            Matchers.opcodeIs(0xA2)(
                "a24f".hexToBytes(),
            ),
        )
        assertFalse(
            Matchers.opcodeIs(0xA2)(
                ByteArray(0),
            ),
        )
        assertTrue(
            Matchers.opcodeAndSub(
                0x34,
                0x03,
            )(
                "340301000a".hexToBytes(),
            ),
        )
        assertFalse(
            Matchers.opcodeAndSub(
                0x34,
                0x03,
            )(
                "3404010001173b".hexToBytes(),
            ),
        )
    }

    @Test
    fun watchFaceControlMatchers() {
        assertTrue(
            Matchers.isWatchFaceControl(
                "2602".hexToBytes(),
            ),
        )
        assertTrue(
            Matchers.isWatchFaceControl(
                "260304".hexToBytes(),
            ),
        )
        assertFalse(
            Matchers.isWatchFaceControl(
                "270001".hexToBytes(),
            ),
        )

        assertTrue(
            Matchers.isWatchFaceControl(0x02)(
                "2602".hexToBytes(),
            ),
        )
        assertFalse(
            Matchers.isWatchFaceControl(0x02)(
                "260304".hexToBytes(),
            ),
        )

        assertTrue(
            Matchers.isWatchFaceTransferContinue(
                "260304".hexToBytes(),
            ),
        )
        assertFalse(
            Matchers.isWatchFaceTransferContinue(
                "260300".hexToBytes(),
            ),
        )

        assertTrue(
            Matchers.isWatchFaceTransferSuccess(
                "260300".hexToBytes(),
            ),
        )
        assertFalse(
            Matchers.isWatchFaceTransferSuccess(
                "260304".hexToBytes(),
            ),
        )
    }

    @Test
    fun watchFaceResumeIndexIsBigEndian() {
        assertEquals(
            0x0123,
            Matchers.watchFaceResumeIndex(
                "2603030123".hexToBytes(),
            ),
        )

        assertEquals(
            0,
            Matchers.watchFaceResumeIndex(
                "2603030000".hexToBytes(),
            ),
        )

        assertNull(
            Matchers.watchFaceResumeIndex(
                "260304".hexToBytes(),
            ),
        )

        assertNull(
            Matchers.watchFaceResumeIndex(
                "2603".hexToBytes(),
            ),
        )

        assertNull(
            Matchers.watchFaceResumeIndex(
                "2703030123".hexToBytes(),
            ),
        )
    }

    // ---- BaseWatchLink request / collect semantics through the fake

    @Test
    fun requestReturnsFirstPacketWithOpcodeAndMarksItConsumed() = runBlocking {
        val link = FakeWatchLink()
        link.connect("78:02:B7:37:91:E5")
        link.on(
            "a2",
            "a24f",
        )

        val reply = link.request(
            "a2".hexToBytes(),
            0xA2,
            500,
        )

        assertEquals(
            "a24f",
            reply.toHex(),
        )
        assertEquals(
            listOf("a2"),
            link.txHex(),
        )
        assertEquals(
            0,
            link.pendingWaiters,
        )
    }

    @Test
    fun requestTimesOutWithTimeoutFlag() = runBlocking {
        val link = FakeWatchLink()
        link.connect("78:02:B7:37:91:E5")

        try {
            link.request(
                "a1".hexToBytes(),
                0xA1,
                100,
            )
            fail("expected a timeout")
        } catch (e: GattException) {
            assertTrue(e.timeout)
        }

        assertEquals(
            0,
            link.pendingWaiters,
        )
    }

    @Test
    fun requestFailsWhenTheLinkDrops() = runBlocking {
        val link = FakeWatchLink()
        link.connect("78:02:B7:37:91:E5")

        val pending = async(Dispatchers.Default) {
            try {
                link.request(
                    "a1".hexToBytes(),
                    0xA1,
                    5_000,
                )
                null
            } catch (e: GattException) {
                e
            }
        }

        assertTrue(
            eventually {
                link.pendingWaiters == 1
            },
        )

        link.dropLink(19)

        val e = pending.await()

        assertNotNull(e)
        assertFalse(e!!.timeout)
        assertTrue(
            e.message!!.contains("link lost"),
        )
    }

    @Test
    fun collectGathersUntilEndAndConsumesOnlyThatOpcode() = runBlocking {
        val link = FakeWatchLink()
        link.connect("78:02:B7:37:91:E5")

        val seen = ArrayList<RawPacket>()

        val collector =
            CoroutineScope(Dispatchers.Unconfined).async {
                link.packets.collect {
                    seen += it
                }
            }

        link.on(
            "b2fa",
            "b207ea09031400840000000000062a010084",
            "a24d", // an unrelated battery push in the middle
            "b207ea09031500210000000000303a000021",
            "b2fd05",
        )

        val pkts = link.collect(
            "b2fa".hexToBytes(),
            0xB2,
            Matchers::isFetchEnd,
            500,
        )

        assertEquals(
            3,
            pkts.size,
        )
        assertEquals(
            "b2fd05",
            pkts.last().toHex(),
        )
        assertTrue(
            eventually {
                seen.size == 4
            },
        )
        assertTrue(
            seen[0].consumed,
        )
        assertFalse(
            seen[1].consumed,
        )
        assertEquals(
            0xA2,
            seen[1].opcode,
        )
        assertTrue(
            seen[3].consumed,
        )
        assertEquals(
            0,
            link.pendingWaiters,
        )

        collector.cancel()
    }

    @Test
    fun collectTimeoutCarriesThePartialPackets() = runBlocking {
        val link = FakeWatchLink()
        link.connect("78:02:B7:37:91:E5")

        link.on(
            "f7fa000000000000",
            "f707ea090314ffffffffffffffffffffff56",
        )

        try {
            link.collect(
                "f7fa000000000000".hexToBytes(),
                0xF7,
                Matchers::isFetchEnd,
                150,
            )
            fail("expected a timeout")
        } catch (e: GattException) {
            assertTrue(e.timeout)
            assertEquals(
                1,
                e.partial.size,
            )
        }

        assertEquals(
            0,
            link.pendingWaiters,
        )
    }

    @Test
    fun collectTakesTheExtraChannelForSleepStages() = runBlocking {
        val link = FakeWatchLink()
        link.connect("78:02:B7:37:91:E5")

        link.on(
            "3101",
            "310107ea09041e",
            "data:32173a010100170015020100",
            "3102",
        )

        val pkts = link.collect(
            "3101".hexToBytes(),
            0x31,
            Matchers::isSleepEnd,
            500,
            WatchChannel.CMD,
            WatchChannel.DATA,
            0x32,
        )

        assertEquals(
            3,
            pkts.size,
        )
        assertEquals(
            0x32,
            pkts[1][0].toInt() and 0xFF,
        )
    }

    @Test
    fun waitForDoesNotWrite() = runBlocking {
        val link = FakeWatchLink()
        link.connect("78:02:B7:37:91:E5")

        val waiting =
            async(Dispatchers.Default) {
                link.waitFor(
                    Matchers.opcodeIs(0xD1),
                    2_000,
                )
            }

        assertTrue(
            eventually {
                link.pendingWaiters == 1
            },
        )

        delay(20)

        link.rx("d10a01")

        assertEquals(
            "d10a01",
            waiting.await().toHex(),
        )
        assertTrue(
            link.txHex().isEmpty(),
        )
    }
}
