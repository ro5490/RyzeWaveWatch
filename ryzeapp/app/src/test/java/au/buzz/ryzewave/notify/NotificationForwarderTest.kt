package au.buzz.ryzewave.notify

import au.buzz.ryzewave.ble.FakeRepo
import au.buzz.ryzewave.ble.FakeSettings
import au.buzz.ryzewave.ble.FakeWatchLink
import au.buzz.ryzewave.ble.WatchApiImpl
import au.buzz.ryzewave.ble.eventually
import au.buzz.ryzewave.core.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The forwarder on top of the real [WatchApiImpl] and the fake link that acks every `C5` chunk like the watch. */
class NotificationForwarderTest {
    private var now = 1_800_000_000_000L
    private lateinit var link: FakeWatchLink
    private lateinit var settings: FakeSettings
    private lateinit var scope: CoroutineScope
    private lateinit var api: WatchApiImpl
    private lateinit var forwarder: NotificationForwarder
    private val logLines = ArrayList<String>()

    @Before
    fun setUp() {
        link = FakeWatchLink { now }
        settings = FakeSettings()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        api =
            WatchApiImpl(
                link,
                FakeRepo(),
                settings,
                scope,
                autoSetupOnConnect = false,
                clock = { now },
            )

        forwarder =
            NotificationForwarder(
                settings,
                api,
                scope,
                "au.buzz.ryzewave",
                { now },
            ) { m, _ ->
                logLines += m
            }

        // The watch acks every chunk with `C5 <idx>`
        // and the end with `C5 FD <type> <total>`.
        link.responder = { hex, _ ->
            if (hex.startsWith("c5fd")) {
                link.rx("c5fd0450")
            } else if (hex.startsWith("c5")) {
                link.rx(hex.substring(0, 4))
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun post(
        pkg: String = "com.android.shell",
        key: String = "0|$pkg|1|tag1|2000",
        text: String = "Hello from adb",
    ) =
        PostedNotification(
            pkg,
            key,
            "Test title",
            text,
            "Shell",
        )

    @Test
    fun forwardsAnAllowedAppWhenEnabledAndConnected() = runBlocking {
        link.connect("78:02:B7:37:91:E5")

        assertTrue(
            eventually {
                api.status.value.state ==
                    ConnectionState.CONNECTED
            }
        )

        settings.setNotificationsEnabled(true)
        settings.setAllowedPackages(
            setOf("com.android.shell")
        )

        assertTrue(
            eventually {
                forwarder.enabled.value &&
                    forwarder.allowedPackages.value.isNotEmpty()
            }
        )

        assertTrue(
            forwarder.offer(
                post()
            )
        )

        assertTrue(
            eventually {
                forwarder.sentCount == 1
            }
        )

        val tx = link.txHex()

        // "Test title: Hello from adb"
        // = 26 chars = 52 bytes
        // = 4 chunks (16+16+16+4) + end + a buzz.
        assertEquals(
            6,
            tx.size,
        )

        assertEquals(
            "c5000434" +
                "00540065007300740020007400690074",
            tx[0],
        )

        // Notification end, then the alert buzz.
        assertEquals(
            "c5fd",
            tx[4],
        )

        assertEquals(
            "ab00000001010000",
            tx.last(),
        )

        // Last chunk carries the final "db".
        assertEquals(
            "c503" +
                "00640062",
            tx[3],
        )

        // Same key + text again within 10 s:
        // dropped; a new text goes through.
        assertFalse(
            forwarder.offer(
                post()
            )
        )

        now += 1_000

        assertTrue(
            forwarder.offer(
                post(
                    text = "Second"
                )
            )
        )

        assertTrue(
            eventually {
                forwarder.sentCount == 2
            }
        )

        // Exactly one drop: the duplicate.
        assertEquals(
            1,
            forwarder.droppedCount,
        )
    }

    @Test
    fun masterSwitchAllowListAndLinkStateGateTheSend() = runBlocking {
        // Disabled.
        assertFalse(
            forwarder.offer(
                post()
            )
        )

        assertTrue(
            logLines
                .last()
                .endsWith("notifications off")
        )

        settings.setNotificationsEnabled(true)

        assertTrue(
            eventually {
                forwarder.enabled.value
            }
        )

        // Not in the allow-list.
        assertFalse(
            forwarder.offer(
                post()
            )
        )

        assertTrue(
            logLines
                .last()
                .endsWith("package not allowed")
        )

        settings.setForwardAllNotifications(true)

        assertTrue(
            eventually {
                forwarder.forwardAll.value
            }
        )

        // Watch not connected: dropped, not queued.
        assertFalse(
            forwarder.offer(
                post()
            )
        )

        assertTrue(
            logLines
                .last()
                .endsWith("watch not connected")
        )

        assertTrue(
            link.txHex().isEmpty()
        )

        link.connect(
            "78:02:B7:37:91:E5"
        )

        assertTrue(
            eventually {
                api.status.value.state ==
                    ConnectionState.CONNECTED
            }
        )

        assertTrue(
            forwarder.offer(
                post(
                    key = "new"
                )
            )
        )

        assertTrue(
            eventually {
                forwarder.sentCount == 1
            }
        )

        // Off, not allowed, not connected.
        assertEquals(
            3,
            forwarder.droppedCount,
        )
    }

    @Test
    fun testMessageBypassesTheSwitchAndIsTypeFour() = runBlocking {
        link.connect(
            "78:02:B7:37:91:E5"
        )

        assertTrue(
            forwarder.sendTest()
        )

        val tx = link.txHex()

        /*
         * "Dapper's SmartTrax: test"
         *
         * 24 characters
         * = 48 UTF-16BE bytes
         * = 0x30 bytes.
         *
         * Three 16-byte C5 data chunks,
         * followed by C5 FD and the alert buzz.
         */
        assertEquals(
            "c5000430" +
                "004400610070007000650072002700730020",
            tx[0],
        )

        assertEquals(
            "c501" +
                "0053006d00610072007400540072006100",
            tx[1],
        )

        assertEquals(
            "c502" +
                "78003a00200074006500730074",
            tx[2],
        )

        assertEquals(
            "c5fd",
            tx[3],
        )

        assertEquals(
            "ab00000001010000",
            tx[4],
        )

        assertEquals(
            5,
            tx.size,
        )

        link.disconnect()

        assertFalse(
            forwarder.sendTest()
        )

        // Disconnecting must not have added another write.
        assertEquals(
            5,
            link.txHex().size,
        )
    }

    @Test
    fun missingAckIsReportedNotThrown() = runBlocking {
        link.connect(
            "78:02:B7:37:91:E5"
        )

        // The watch never answers.
        link.responder = { _, _ -> }

        val api2 =
            WatchApiImpl(
                link,
                FakeRepo(),
                settings,
                scope,
                autoSetupOnConnect = false,
                clock = { now },
            )

        val f =
            NotificationForwarder(
                settings,
                api2,
                scope,
                "au.buzz.ryzewave",
                { now },
            ) { m, _ ->
                logLines += m
            }

        assertFalse(
            f.sendTest()
        )

        assertTrue(
            f.lastError!!.contains(
                "no reply"
            )
        )

        assertEquals(
            1,
            f.droppedCount,
        )
    }
}
