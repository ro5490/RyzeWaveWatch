package au.buzz.ryzewave.ble

import au.buzz.ryzewave.core.ConnectionState
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.SamplingSettings
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.WatchEvent
import au.buzz.ryzewave.core.WorkoutControlAction
import au.buzz.ryzewave.protocol.Protocol
import au.buzz.ryzewave.protocol.Features
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Replays the hex captures from docs/PROTOCOL.md and the captures directory through [WatchApiImpl]. */
class WatchApiImplTest {

    private val zone: ZoneId = ZoneId.of("Australia/Brisbane")
    private var now = 1_800_000_000_000L
    private val clock: () -> Long = { now }

    private lateinit var link: FakeWatchLink
    private lateinit var repo: FakeRepo
    private lateinit var settings: FakeSettings
    private lateinit var scope: CoroutineScope
    private lateinit var api: WatchApiImpl
    private val logLines = ArrayList<String>()

    @Before
    fun setUp() {
        link = FakeWatchLink(clock)
        repo = FakeRepo()
        settings = FakeSettings()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        api = WatchApiImpl(
            link, repo, settings, scope,
            autoSetupOnConnect = false,
            liveHrWarmupMs = 5,
            clock = clock,
            zone = zone,
            log = { m, t ->
                logLines += m + (t?.let { " ($it)" } ?: "")
            },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun scriptSync() {
        link.on(
            "b2fa",
            "b207ea09031400840000000000062a010084",
            "b207ea09031500210000000000303a000021",
            "b207ea090316007200000000001630000072",
            "b207ea090317007700000000000522000077",
            "b207ea090402003e0000000000081500003e",
            "b207ea090409010800000000000d31030108",
            "b207ea09040c007100000000002a2e000071",
            "b207ea09040d022c00000000000f2e07022c",
            "b207ea09040e00ed00000000000d370100ed",
            "b207ea09040f00500000000000283b000050",
            "b207ea09041000c000000000000b110200c0",
            "b207ea09041101a00000000000071d0301a0",
            "b2fd05",
        )

        link.onPrefix(
            "f7fa",
            "f707ea090314ffffffffffffffffffffff56",
            "f707ea09031654596f929564776c5e6566ff",
            "f707ea090400ff5957595751575855504f47",
            "f707ea09040246474a534c48494847474440",
            "f707ea09040441463d3e3d413a44413c423f",
            "f707ea090406403d413b3e3f423e333f3a41",
            "f707ea0904083d383c3a3e41423e3e3f4543",
            "f707ea09040a414448483f42586f50545554",
            "f707ea09040effffff585a576d6e56535554",
            "f707ea0904105f51545955596161665e625b",
            "f707ea090412789a68766971736962595c59",
            "f707ea09041460625c62ffffffffffffffff",
            "f7fda5",
        )

        link.on(
            "34fa",
            "34fa07ea09031600ffff63ffffffffffffff61ff",
            "34fafd01",
        )

        link.on(
            "3101",
            "310107ea09041e",
            "data:$SLEEP_STAGES",
            "3102",
        )
    }

    @Test
    fun syncAllPersistsStepsHrSpo2AndSleepAndStoresCursors() = runBlocking {
        scriptSync()
        link.connect(MAC)

        val r = api.syncAll()

        assertNull(r.error)
        assertEquals(12, r.steps)
        assertEquals(120, r.hr)
        assertEquals(2, r.spo2)
        assertEquals(30, r.sleep)

        assertEquals(12, repo.steps.size)
        assertEquals(120, repo.hr.size)
        assertEquals(2, repo.spo2.size)
        assertEquals(30, repo.sleep.size)

        assertEquals(
            "f7fa000000000000",
            link.txHex().first { it.startsWith("f7fa") },
        )

        assertEquals(
            listOf(
                "b2fa",
                "f7fa000000000000",
                "34fa",
                "3101",
            ),
            link.txHex(),
        )

        val h20 = LocalDateTime.of(
            2026,
            9,
            3,
            20,
            0,
        ).atZone(zone).toInstant().toEpochMilli()

        assertEquals(
            132,
            repo.steps[h20]!!.total,
        )

        assertEquals(
            132,
            repo.steps[h20]!!.walk,
        )

        assertEquals(
            0,
            repo.steps[h20]!!.run,
        )

        assertEquals(
            86,
            repo.hr[h20 to "HISTORY"]!!.bpm,
        )

        val spo2At = LocalDateTime.of(
            2026,
            9,
            3,
            20,
            30,
        ).atZone(zone).toInstant().toEpochMilli()

        assertEquals(
            99,
            repo.spo2[spo2At to "HISTORY"]!!.percent,
        )

        val firstStage = LocalDateTime.of(
            2026,
            9,
            3,
            23,
            58,
        ).atZone(zone).toInstant().toEpochMilli()

        assertEquals(
            1,
            repo.sleep[firstStage]!!.stage,
        )

        assertEquals(
            23,
            repo.sleep[firstStage]!!.minutes,
        )

        for (
            k in listOf(
                WatchApiImpl.SYNC_STEPS,
                WatchApiImpl.SYNC_SPO2,
                WatchApiImpl.SYNC_SLEEP,
                WatchApiImpl.SYNC_ALL,
            )
        ) {
            assertEquals(
                "cursor $k",
                now,
                repo.cursors[k],
            )
        }

        val newestHr = repo.hr.keys.maxOf { it.first }

        assertEquals(
            LocalDateTime.of(
                2026,
                9,
                4,
                18,
                40,
            ).atZone(zone).toInstant().toEpochMilli(),
            newestHr,
        )

        assertEquals(
            "cursor hr",
            newestHr,
            repo.cursors[WatchApiImpl.SYNC_HR],
        )

        assertEquals(
            ConnectionState.CONNECTED,
            api.status.value.state,
        )

        assertEquals(
            now,
            api.status.value.lastSyncTime,
        )
    }

    @Test
    fun hrCursorStaysWhenTheFetchDeliversNothing() = runBlocking {
        link.onPrefix(
            "f7fa",
            "f7fd00",
        )

        scriptSync()

        val last = LocalDateTime.of(
            2026,
            9,
            4,
            18,
            38,
        ).atZone(zone).toInstant().toEpochMilli()

        repo.cursors[WatchApiImpl.SYNC_HR] = last

        val r = api.let {
            link.connect(MAC)
            it.syncAll()
        }

        assertNull(r.error)
        assertEquals(0, r.hr)
        assertEquals(
            last,
            repo.cursors[WatchApiImpl.SYNC_HR],
        )
        assertEquals(
            now,
            repo.cursors[WatchApiImpl.SYNC_ALL],
        )
    }

    @Test
    fun foreignHistoryPacketsArePersistedEvenThoughNobodyAskedForThem() =
        runBlocking {

            link.connect(MAC)

            link.rx(
                "f707ea09031654596f929564776c5e6566ff",
            )

            link.rx(
                "b207ea09031400840000000000062a010084",
            )

            link.rx(
                "34fa07ea09031600ffff63ffffffffffffff61ff",
            )

            link.rx(
                "310107ea09041e",
            )

            link.rx(
                SLEEP_STAGES,
                WatchChannel.DATA,
            )

            assertTrue(
                eventually {
                    repo.hr.size == 11
                },
            )

            assertTrue(
                eventually {
                    repo.steps.size == 1
                },
            )

            assertTrue(
                eventually {
                    repo.spo2.size == 2
                },
            )

            assertTrue(
                eventually {
                    repo.sleep.size == 30
                },
            )

            assertEquals(
                132,
                repo.steps.values.first().total,
            )

            assertNull(
                repo.cursors[WatchApiImpl.SYNC_HR],
            )
        }

    @Test
    fun deadLinkIsResetAfterASyncWhoseWritesFail() = runBlocking {
        link.connect(MAC)
        link.failWrites = true

        val r = api.syncAll()

        assertNotNull(r.error)

        assertTrue(
            r.error!!.contains("not connected"),
        )

        assertTrue(
            eventually {
                link.resets.isNotEmpty()
            },
        )

        assertTrue(
            link.resets.first().contains("dead link"),
        )

        assertFalse(
            link.isReady,
        )

        assertTrue(
            eventually {
                api.status.value.state == ConnectionState.ERROR
            },
        )
    }

    @Test
    fun connectTimeSetupIsCoalescedAcrossQuickReadyFlaps() =
        runBlocking {

            scriptSync()

            link.on(
                "a2",
                "a24f",
            )

            link.onPrefix(
                "a3",
                "a3",
            )

            link.onPrefix(
                "a9",
                "a9",
            )

            link.on(
                "f701",
                "f701",
            )

            link.on(
                "340301000a",
                "340301000a",
            )

            link.on(
                "3404010001173b",
                "3404010001173b",
            )

            val version =
                "a1524832383052474156303038393439"

            val auto = WatchApiImpl(
                link,
                repo,
                settings,
                scope,
                autoSetupOnConnect = true,
                liveHrWarmupMs = 1,
                clock = clock,
                zone = zone,
                log = { _, _ -> },
            )

            auto.connect(MAC)

            assertTrue(
                eventually {
                    link.txHex() == listOf("a1")
                },
            )

            link.connect(MAC)
            link.connect(MAC)

            link.rx(version)

            assertTrue(
                eventually {
                    link.txHex().count { it == "a1" } == 2
                },
            )

            link.rx(version)

            assertTrue(
                eventually {
                    link.txHex().count { it == "b2fa" } == 2
                },
            )

            Thread.sleep(150)

            assertEquals(
                2,
                link.txHex().count { it == "a1" },
            )

            assertEquals(
                "RH280RGAV008949",
                auto.status.value.firmware,
            )
        }

    @Test
    fun secondSyncSendsSinceStampTwoHoursBeforeTheLastHrSync() =
        runBlocking {

            scriptSync()
            link.connect(MAC)

            val last = LocalDateTime.of(
                2026,
                9,
                4,
                18,
                38,
            ).atZone(zone).toInstant().toEpochMilli()

            repo.cursors[WatchApiImpl.SYNC_HR] = last

            api.syncAll()

            assertEquals(
                "f7fa07ea09041026",
                link.txHex().first {
                    it.startsWith("f7fa")
                },
            )
        }

    @Test
    fun sinceStampFollowsTheFeatureBitmap() = runBlocking {
        scriptSync()

        link.features = null

        link.connect(MAC)
        api.syncAll()

        assertEquals(
            "f7fa000000000000",
            link.txHex().first {
                it.startsWith("f7fa")
            },
        )

        link.tx.clear()

        link.features = Features(
            emptyMap(),
            "",
        )

        api.syncAll()

        assertEquals(
            "f7fa",
            link.txHex().first {
                it.startsWith("f7fa")
            },
        )
    }

    @Test
    fun syncReportsErrorsButKeepsPartialData() = runBlocking {
        link.onPrefix(
            "f7fa",
            "f707ea090314ffffffffffffffffffffff56",
        )

        scriptSync()

        val short = WatchApiImpl(
            link,
            repo,
            settings,
            scope,
            autoSetupOnConnect = false,
            liveHrWarmupMs = 1,
            fetchTimeoutMs = 150,
            clock = clock,
            zone = zone,
            log = { _, _ -> },
        )

        link.connect(MAC)

        val r = short.syncAll()

        assertNotNull(r.error)
        assertTrue(r.error!!.startsWith("hr:"))
        assertEquals(12, r.steps)
        assertEquals(1, r.hr)
        assertEquals(2, r.spo2)
        assertEquals(30, r.sleep)

        assertNull(
            repo.cursors[WatchApiImpl.SYNC_HR],
        )

        assertNull(
            repo.cursors[WatchApiImpl.SYNC_ALL],
        )

        assertEquals(
            now,
            repo.cursors[WatchApiImpl.SYNC_STEPS],
        )

        assertEquals(
            now,
            repo.cursors[WatchApiImpl.SYNC_SPO2],
        )

        assertTrue(
            short.status.value.message!!.startsWith(
                "sync: hr:",
            ),
        )

        assertEquals(
            ConnectionState.CONNECTED,
            short.status.value.state,
        )

        assertNull(
            short.status.value.lastSyncTime,
        )
    }

    @Test
    fun syncPersistFailureIsReportedPerKind() = runBlocking {
        scriptSync()

        repo.failUpserts = true

        link.connect(MAC)

        val r = api.syncAll()

        assertEquals(0, r.steps)
        assertEquals(0, r.hr)

        assertTrue(
            r.error!!.contains("steps: db down"),
        )

        assertTrue(
            r.error!!.contains("sleep: db down"),
        )

        assertNull(
            repo.cursors[WatchApiImpl.SYNC_ALL],
        )
    }

    @Test
    fun syncWhenDisconnectedReturnsNotConnected() =
        runBlocking {

            val r = api.syncAll()

            assertEquals(
                "not connected",
                r.error,
            )

            assertTrue(
                link.txHex().isEmpty(),
            )
        }

    @Test
    fun applySettingsSendsTheVerifiedOrder() = runBlocking {
        link.connect(MAC)

        link.onPrefix(
            "a3",
            "a307ea0904123238",
        )

        link.onPrefix(
            "a9",
            "a9",
        )

        link.on(
            "f701",
            "f701",
        )

        link.on(
            "340301000a",
            "340301000a",
        )

        link.on(
            "3404010001173b",
            "3404010001173b",
        )

        val profile = UserProfile(
            heightCm = 175,
            weightKg = 75,
            age = 40,
            male = true,
            stepGoal = 8000,
        )

        api.applySettings(
            profile,
            SamplingSettings(
                continuousHr = true,
                spo2AutoEnabled = true,
                spo2IntervalMin = 10,
                raiseWristWake = true,
            ),
        )

        val tx = link.txHex()

        assertEquals(
            5,
            tx.size,
        )

        assertTrue(
            tx[0].startsWith("a307ea"),
        )

        assertEquals(
            Protocol.hex(
                Protocol.encUserInfo(
                    175,
                    75,
                    8000,
                    40,
                    true,
                ),
            ),
            tx[1],
        )

        assertEquals(
            "a900af004b0500001f4001ff00280100020100",
            tx[1],
        )

        assertEquals(
            "f701",
            tx[2],
        )

        assertEquals(
            "340301000a",
            tx[3],
        )

        assertEquals(
            "3404010001173b",
            tx[4],
        )
    }

    @Test
    fun applySettingsOffVariantsAndMissingEchoesAreTolerated() =
        runBlocking {

            link.connect(MAC)

            val done = Job()

            CoroutineScope(
                Dispatchers.Default,
            ).launch {
                try {
                    api.applySettings(
                        UserProfile(),
                        SamplingSettings(
                            continuousHr = false,
                            spo2AutoEnabled = false,
                            spo2IntervalMin = 30,
                        ),
                    )
                } finally {
                    done.complete()
                }
            }

            assertTrue(
                eventually {
                    link.txHex().isNotEmpty()
                },
            )

            link.rx("a3")

            assertTrue(
                eventually {
                    link.txHex().size >= 2
                },
            )

            link.rx("a9")

            assertTrue(
                eventually {
                    link.txHex().size >= 3
                },
            )

            assertEquals(
                "f702",
                link.txHex()[2],
            )

            link.rx("f702")

            assertTrue(
                eventually {
                    link.txHex().size >= 4
                },
            )

            assertEquals(
                "340300001e",
                link.txHex()[3],
            )

            link.rx("340300001e")

            assertTrue(
                eventually {
                    link.txHex().size >= 5
                },
            )

            assertEquals(
                "3404000001173b",
                link.txHex()[4],
            )

            link.rx(
                "3404000001173b",
            )

            assertTrue(
                eventually {
                    done.isCompleted
                },
            )
        }

    @Test
    fun setupAndSyncRunsVersionBatterySettingsThenSync() =
        runBlocking {

            scriptSync()

            link.on(
                "a1",
                "a1524832383052474156303038393439",
            )

            link.on(
                "a2",
                "a24f",
            )

            link.onPrefix(
                "a3",
                "a3",
            )

            link.onPrefix(
                "a9",
                "a9",
            )

            link.on(
                "f701",
                "f701",
            )

            link.on(
                "340301000a",
                "340301000a",
            )

            link.on(
                "3404010001173b",
                "3404010001173b",
            )

            link.connect(MAC)

            val r = api.setupAndSync()

            assertNull(r.error)

            val tx = link.txHex()

            assertEquals(
                "a1",
                tx[0],
            )

            assertEquals(
                "a2",
                tx[1],
            )

            assertTrue(
                tx[2].startsWith("a3"),
            )

            assertTrue(
                tx[3].startsWith("a9"),
            )

            assertEquals(
                "f701",
                tx[4],
            )

            assertEquals(
                "340301000a",
                tx[5],
            )

            assertEquals(
                "3404010001173b",
                tx[6],
            )

            assertEquals(
                "b2fa",
                tx[7],
            )

            assertEquals(
                "RH280RGAV008949",
                api.status.value.firmware,
            )

            assertEquals(
                79,
                api.status.value.batteryPercent,
            )

            assertEquals(
                12,
                r.steps,
            )
        }

    @Test
    fun autoSetupRunsOnEveryReadyGeneration() = runBlocking {
        scriptSync()

        link.on(
            "a1",
            "a1524832383052474156303038393439",
        )

        link.on(
            "a2",
            "a24f",
        )

        link.onPrefix(
            "a3",
            "a3",
        )

        link.onPrefix(
            "a9",
            "a9",
        )

        link.on(
            "f701",
            "f701",
        )

        link.on(
            "340301000a",
            "340301000a",
        )

        link.on(
            "3404010001173b",
            "3404010001173b",
        )

        val auto = WatchApiImpl(
            link,
            repo,
            settings,
            scope,
            autoSetupOnConnect = true,
            liveHrWarmupMs = 1,
            clock = clock,
            zone = zone,
            log = { _, _ -> },
        )

        auto.connect(MAC)

        assertTrue(
            eventually {
                repo.cursors[WatchApiImpl.SYNC_ALL] != null
            },
        )

        assertTrue(
            eventually {
                auto.status.value.state ==
                    ConnectionState.CONNECTED
            },
        )

        assertEquals(
            12,
            repo.steps.size,
        )

        val firstCount =
            link.txHex().size

        link.dropLink(19)

        assertTrue(
            eventually {
                auto.status.value.state ==
                    ConnectionState.ERROR
            },
        )

        link.connect(MAC)

        assertTrue(
            eventually {
                link.txHex().size >=
                    firstCount * 2
            },
        )

        assertEquals(
            "a1",
            link.txHex()[firstCount],
        )
    }

    @Test
    fun liveHrStartsWithDynamicModeThenStreams() = runBlocking {
        link.connect(MAC)

        val got =
            ArrayList<HrSample>()

        val job =
            scope.launch {
                api.liveHr.collect {
                    got += it
                }
            }

        api.startLiveHr()

        assertEquals(
            listOf(
                "d602",
                "e511",
            ),
            link.txHex(),
        )

        link.rx(
            "e511005e",
        )

        link.rx(
            "e511005a",
        )

        assertTrue(
            eventually {
                got.size == 2
            },
        )

        assertEquals(
            94,
            got[0].bpm,
        )

        assertEquals(
            SampleSource.LIVE,
            got[0].source,
        )

        assertEquals(
            90,
            got[1].bpm,
        )

        assertTrue(
            eventually {
                repo.hr.size == 1
            },
        )

        now += 11_000

        link.rx(
            "e5110058",
        )

        assertTrue(
            eventually {
                repo.hr.size == 2
            },
        )

        api.stopLiveHr()

        assertEquals(
            "e500",
            link.txHex().last(),
        )

        job.cancel()
    }

    @Test
    fun spo2SpotTestIgnoresTheEarlyFailureAndReturnsTheResult() =
        runBlocking {

            link.connect(MAC)

            val events =
                ArrayList<WatchEvent>()

            val job =
                scope.launch {
                    api.events.collect {
                        events += it
                    }
                }

            link.on(
                "3411",
                "3411",
                "3400ffff",
                "3411",
                "34000061",
            )

            val pct =
                api.spo2SpotTest()

            assertEquals(
                97,
                pct,
            )

            assertEquals(
                listOf("3411"),
                link.txHex(),
            )

            assertTrue(
                eventually {
                    events.any {
                        it is WatchEvent.Spo2Result
                    }
                },
            )

            val results =
                events.filterIsInstance<WatchEvent.Spo2Result>()

            assertEquals(
                1,
                results.size,
            )

            assertEquals(
                97,
                results[0].percent,
            )

            assertTrue(
                eventually {
                    repo.spo2.size == 1
                },
            )

            assertEquals(
                SampleSource.LIVE,
                repo.spo2.values.first().source,
            )

            job.cancel()
        }

    @Test
    fun spo2SpotTestLateFailureReturnsNull() = runBlocking {
        link.connect(MAC)

        link.responder = { hex, _ ->
            if (hex == "3411") {
                link.rx("3411")
                link.rx("3400ffff")

                now += 5_000

                link.rx("3400ffff")
            }
        }

        assertNull(
            api.spo2SpotTest(),
        )

        assertTrue(
            repo.spo2.isEmpty(),
        )
    }

    @Test
    fun spo2AutoPushIsPersistedAsAutoAndEmitted() = runBlocking {
        link.connect(MAC)

        val events =
            ArrayList<WatchEvent>()

        val job =
            scope.launch {
                api.events.collect {
                    events += it
                }
            }

        link.rx(
            "34000062",
        )

        assertTrue(
            eventually {
                repo.spo2.size == 1
            },
        )

        assertEquals(
            98,
            repo.spo2.values.first().percent,
        )

        assertEquals(
            SampleSource.AUTO,
            repo.spo2.values.first().source,
        )

        assertTrue(
            eventually {
                events.any {
                    it is WatchEvent.Spo2Result &&
                        it.percent == 98
                }
            },
        )

        job.cancel()
    }

    @Test
    fun workoutControlWaitsForEchoesAndStreamsHr() = runBlocking {
        link.connect(MAC)

        val got =
            ArrayList<HrSample>()

        val job =
            scope.launch {
                api.liveHr.collect {
                    got += it
                }
            }

        link.on(
            "fd110101",
            "fd110101",
        )

        link.on(
            "fd220101",
            "fd220101",
        )

        link.on(
            "fd330101",
            "fd330101",
        )

        link.on(
            "fd000101",
            "fd000101",
        )

        link.on(
            "fdaa",
            "fdaa0101",
        )

        link.onPrefix(
            "fd44",
            "fd440101000001000000000000",
        )

        api.startWorkout(1)

        link.rx(
            "fd01550000000000000000000000",
        )

        link.rx(
            "fd01000000000000000000000000",
        )

        assertTrue(
            eventually {
                got.size == 1
            },
        )

        assertEquals(
            85,
            got[0].bpm,
        )

        assertEquals(
            SampleSource.WORKOUT,
            got[0].source,
        )

        api.updateWorkout(
            1,
            0.0,
            0.0,
            0,
        )

        api.pauseWorkout()
        api.resumeWorkout()
        api.stopWorkout()

        assertEquals(
            listOf(
                "fd110101",
                "fdaa",
                "fd440101000001000000000000",
                "fd220101",
                "fd330101",
                "fd000101",
            ),
            link.txHex(),
        )

        assertTrue(
            repo.hr.isEmpty(),
        )

        job.cancel()
    }

    @Test
    fun theWatchsOwn13ByteResumeAndPauseBecomeWorkoutControls() =
        runBlocking {

            link.connect(MAC)

            val events =
                ArrayList<WatchEvent>()

            val job =
                scope.launch {
                    api.events.collect {
                        events += it
                    }
                }

            link.rx(
                "fd22010100000a000000000000",
            )

            assertTrue(
                eventually {
                    events.any {
                        it == WatchEvent.WorkoutControl(
                            WorkoutControlAction.PAUSE,
                        )
                    }
                },
            )

            link.rx(
                "fd33010100000a000000000000",
            )

            assertTrue(
                eventually {
                    events.any {
                        it == WatchEvent.WorkoutControl(
                            WorkoutControlAction.RESUME,
                        )
                    }
                },
            )

            assertEquals(
                2,
                events.filterIsInstance<WatchEvent.WorkoutControl>().size,
            )

            job.cancel()
        }

    @Test
    fun appControlEchoesAreIgnoredButWatchButtonPressesBecomeWorkoutControl() =
        runBlocking {

            link.connect(MAC)

            val events =
                ArrayList<WatchEvent>()

            val job =
                scope.launch {
                    api.events.collect {
                        events += it
                    }
                }

            link.on(
                "fd220101",
                "fd220101",
            )

            api.pauseWorkout()

            assertTrue(
                eventually {
                    link.txHex().contains(
                        "fd220101",
                    )
                },
            )

            Thread.sleep(30)

            assertTrue(
                "an app echo must not become a WorkoutControl",
                events.none {
                    it is WatchEvent.WorkoutControl
                },
            )

            now +=
                WatchApiImpl.ECHO_WINDOW_MS + 1

            link.rx(
                "fd220101",
            )

            assertTrue(
                eventually {
                    events.any {
                        it == WatchEvent.WorkoutControl(
                            WorkoutControlAction.PAUSE,
                        )
                    }
                },
            )

            link.rx(
                "fd330101",
            )

            assertTrue(
                eventually {
                    events.any {
                        it == WatchEvent.WorkoutControl(
                            WorkoutControlAction.RESUME,
                        )
                    }
                },
            )

            link.rx(
                "fd000101",
            )

            assertTrue(
                eventually {
                    events.any {
                        it == WatchEvent.WorkoutControl(
                            WorkoutControlAction.STOP,
                        )
                    }
                },
            )

            val controls =
                events.filterIsInstance<WatchEvent.WorkoutControl>()

            assertEquals(
                3,
                controls.size,
            )

            job.cancel()
        }

    @Test
    fun realtimeSportPacketSurfacesSessionSteps() = runBlocking {
        link.connect(MAC)

        val events =
            ArrayList<WatchEvent>()

        val job =
            scope.launch {
                api.events.collect {
                    events += it
                }
            }

        link.rx(
            "fd015c00000000000f5d00000300",
        )

        assertTrue(
            eventually {
                events.any {
                    it is WatchEvent.WorkoutRealtime &&
                        it.steps == 3933
                }
            },
        )

        val rt =
            events.filterIsInstance<WatchEvent.WorkoutRealtime>().first()

        assertEquals(
            1,
            rt.sportType,
        )

        assertEquals(
            3933,
            rt.steps,
        )

        job.cancel()
    }

    @Test
    fun workoutUpdateEncodesDistancePaceAndCalories() = runBlocking {
        link.connect(MAC)

        link.on(
            "fd110301",
            "fd110301",
        )

        api.startWorkout(3)

        link.responder = { hex, _ ->
            if (hex.startsWith("fd44")) {
                link.rx(hex)
            }
        }

        api.updateWorkout(
            durationSeconds = 3_725,
            distanceMeters = 2_350.0,
            paceSecPerKm = 372.0,
            calories = 210,
        )

        assertEquals(
            "fd440301010205" +
                "00d2" +
                "0223" +
                "060c",
            link.txHex().last(),
        )
    }

    @Test
    fun workoutStartWithoutEchoFails() = runBlocking {
        link.connect(MAC)

        val outcome =
            AtomicReference<Throwable?>(null)

        val job =
            CoroutineScope(
                Dispatchers.Default,
            ).launch {
                try {
                    api.startWorkout(1)
                } catch (e: Throwable) {
                    outcome.set(e)
                }
            }

        assertTrue(
            eventually {
                link.txHex() ==
                    listOf("fd110101")
            },
        )

        link.dropLink(8)

        assertTrue(
            eventually {
                job.isCompleted
            },
        )

        val e =
            outcome.get()

        assertTrue(
            "expected a GattException, got $e",
            e is GattException,
        )

        assertTrue(
            e!!.message!!.contains("link lost"),
        )
    }

    @Test
    fun pushesAreHandledAtAnyTime() = runBlocking {
        link.connect(MAC)

        val events =
            ArrayList<WatchEvent>()

        val job =
            scope.launch {
                api.events.collect {
                    events += it
                }
            }

        link.rx(
            "f70307ea0904130559",
        )

        assertTrue(
            eventually {
                repo.hr.size == 1
            },
        )

        val s =
            repo.hr.values.first()

        assertEquals(
            89,
            s.bpm,
        )

        assertEquals(
            SampleSource.AUTO,
            s.source,
        )

        assertEquals(
            LocalDateTime.of(
                2026,
                9,
                4,
                19,
                50,
            ).atZone(zone).toInstant().toEpochMilli(),
            s.time,
        )

        link.rx(
            "f70407ea09041327693a4c",
        )

        assertTrue(
            eventually {
                events.any {
                    it is WatchEvent.HrSummary
                }
            },
        )

        val sum =
            events.filterIsInstance<WatchEvent.HrSummary>().first()

        assertEquals(
            105,
            sum.max,
        )

        assertEquals(
            58,
            sum.min,
        )

        assertEquals(
            76,
            sum.avg,
        )

        link.rx(
            "b107ea090414001500000000000909000015",
        )

        link.rx(
            "b107ea090414001500000000000909000015",
        )

        assertTrue(
            eventually {
                repo.steps.size == 1
            },
        )

        assertEquals(
            21,
            repo.steps.values.first().total,
        )

        assertTrue(
            eventually {
                events.count {
                    it is WatchEvent.RealtimeSteps
                } == 2
            },
        )

        link.rx(
            "a24d",
        )

        assertTrue(
            eventually {
                api.status.value.batteryPercent == 77
            },
        )

        assertFalse(
            api.status.value.charging,
        )

        link.rx(
            "a24e01",
        )

        assertTrue(
            eventually {
                api.status.value.charging
            },
        )

        assertEquals(
            78,
            api.status.value.batteryPercent,
        )

        link.rx(
            "d10a01",
        )

        assertTrue(
            eventually {
                events.any {
                    it == WatchEvent.FindPhone(
                        start = true,
                    )
                }
            },
        )

        assertFalse(
            events.any {
                it == WatchEvent.FindPhone(
                    start = false,
                )
            },
        )

        link.rx(
            "d10a00",
        )

        assertTrue(
            eventually {
                events.any {
                    it == WatchEvent.FindPhone(
                        start = false,
                    )
                }
            },
        )

        link.rx(
            "440f0001000456503630",
        )

        assertTrue(
            eventually {
                events.any {
                    it is WatchEvent.Raw &&
                        it.hex ==
                        "440f0001000456503630"
                }
            },
        )

        assertEquals(
            1,
            repo.hr.size,
        )

        job.cancel()
    }

    @Test
    fun statusFollowsTheLinkState() = runBlocking {
        assertEquals(
            ConnectionState.DISCONNECTED,
            api.status.value.state,
        )

        api.connect(MAC)

        assertTrue(
            eventually {
                api.status.value.state ==
                    ConnectionState.CONNECTED
            },
        )

        assertEquals(
            MAC,
            api.status.value.mac,
        )

        link.dropLink(19)

        assertTrue(
            eventually {
                api.status.value.state ==
                    ConnectionState.ERROR
            },
        )

        assertTrue(
            api.status.value.message!!.contains(
                "link lost",
            ),
        )

        api.disconnect()

        assertTrue(
            eventually {
                api.status.value.state ==
                    ConnectionState.DISCONNECTED
            },
        )

        assertNull(
            api.readBattery(),
        )

        assertNull(
            api.spo2SpotTest(),
        )

        try {
            api.findWatch()
            fail(
                "expected a GattException",
            )
        } catch (e: GattException) {
            assertTrue(
                e.message!!.contains(
                    "not connected",
                ),
            )
        }
    }

    /**
     * captures/bridge_20260905_073950.txt:
     * each `C5 <idx>` chunk is acked with `C5 <idx>`.
     * `C5 FD` is acknowledged with `C5 FD 04 50`.
     * The next chunk must only go out after the previous ack.
     */
    @Test
    fun notificationChunksWaitForEachAckThenTheEnd() = runBlocking {
        link.connect(MAC)

        val acks =
            ArrayList<String>()

        link.responder = { hex, _ ->
            if (hex.startsWith("c5fd")) {
                acks += hex
                link.rx("c5fd0450")
            } else if (hex.startsWith("c5")) {
                acks += hex
                link.rx(
                    hex.substring(
                        0,
                        4,
                    ),
                )
            }
        }

        /*
         * "Dapper's SmartTrax: hello from the new app"
         *
         * 42 characters
         * = 84 UTF-16BE bytes
         * = 0x54 bytes.
         *
         * C5 carries 16 payload bytes per full chunk:
         *
         *   C5 00 + 16 bytes
         *   C5 01 + 16 bytes
         *   C5 02 + 16 bytes
         *   C5 03 + 16 bytes
         *   C5 04 + 16 bytes
         *   C5 05 + 4 bytes
         *   C5 FD
         *   AB... notification buzz
         */
        assertTrue(
            api.sendNotification(
                4,
                "Dapper's SmartTrax: hello from the new app",
            ),
        )

        assertEquals(
            listOf(
                "c500045400440061007000700065007200270073",
                "c50100200053006d00610072007400540072",
                "c50200610078003a002000680065006c006c",
                "c503006f002000660072006f006d00200074",
                "c504006800650020006e0065007700200061",
                "c50500700070",
                "c5fd",
                "ab00000001010000",
            ),
            link.txHex(),
        )

        // Six C5 data packets plus C5 FD.
        assertEquals(
            7,
            acks.size,
        )

        assertTrue(
            logLines.any {
                it ==
                    "notification sent: type 4, 6 chunks"
            },
        )
    }

    @Test
    fun notificationWithoutAckFailsAndSkipsWhenDisconnectedOrEmpty() =
        runBlocking {

            assertFalse(
                api.sendNotification(
                    4,
                    "x",
                ),
            )

            assertTrue(
                link.txHex().isEmpty(),
            )

            link.connect(MAC)

            assertFalse(
                api.sendNotification(
                    4,
                    "\uD83D\uDE00",
                ),
            )

            assertTrue(
                link.txHex().isEmpty(),
            )

            link.responder = { _, _ -> }

            val api2 = WatchApiImpl(
                link,
                repo,
                settings,
                scope,
                autoSetupOnConnect = false,
                clock = clock,
                zone = zone,
            )

            try {
                api2.sendNotification(
                    3,
                    "SMS",
                )

                fail(
                    "expected a timeout",
                )
            } catch (e: GattException) {
                assertTrue(
                    e.timeout,
                )
            }

            assertEquals(
                listOf(
                    "c50003060053004d0053",
                ),
                link.txHex(),
            )
        }

    @Test
    fun readBatteryAndFindWatch() = runBlocking {
        link.connect(MAC)

        link.on(
            "a2",
            "a24f",
        )

        assertEquals(
            79,
            api.readBattery(),
        )

        assertEquals(
            79,
            api.status.value.batteryPercent,
        )

        api.findWatch()

        assertEquals(
            "ab00000001020701",
            link.txHex().last(),
        )
    }

    /**
     * captures/bridge_passive_20260904_195347.txt:
     * at 20:55:35 the watch pushes B1 hour 20 = 279 steps.
     * A later zero or lower value for the same hour must not
     * overwrite the stored total.
     */
    @Test
    fun realtimeStepsPushWithAZeroTotalDoesNotOverwriteTheStoredHour() =
        runBlocking {

            link.connect(MAC)

            val events =
                ArrayList<WatchEvent>()

            val job =
                scope.launch {
                    api.events.collect {
                        events += it
                    }
                }

            val h20 =
                LocalDateTime.of(
                    2026,
                    9,
                    4,
                    20,
                    0,
                ).atZone(zone).toInstant().toEpochMilli()

            val h21 =
                LocalDateTime.of(
                    2026,
                    9,
                    4,
                    21,
                    0,
                ).atZone(zone).toInstant().toEpochMilli()

            link.rx(
                "b107ea090414011700000000000937020117",
            )

            assertTrue(
                eventually {
                    repo.steps[h20]?.total == 279
                },
            )

            assertEquals(
                279,
                repo.steps[h20]!!.walk,
            )

            link.rx(
                "b107ea090414000000000000000000000000",
            )

            assertTrue(
                eventually {
                    events.count {
                        it is WatchEvent.RealtimeSteps
                    } == 2
                },
            )

            assertEquals(
                279,
                repo.steps[h20]!!.total,
            )

            assertEquals(
                1,
                repo.steps.size,
            )

            assertTrue(
                logLines.any {
                    it.startsWith(
                        "ignored realtime steps 0",
                    )
                },
            )

            link.rx(
                "b107ea090414010f00000000000937020117",
            )

            assertTrue(
                eventually {
                    events.count {
                        it is WatchEvent.RealtimeSteps
                    } == 3
                },
            )

            assertEquals(
                279,
                repo.steps[h20]!!.total,
            )

            link.rx(
                "b107ea090414012c00000000000937020117",
            )

            assertTrue(
                eventually {
                    repo.steps[h20]?.total == 300
                },
            )

            link.rx(
                "b107ea090415000500000000000909000005",
            )

            assertTrue(
                eventually {
                    repo.steps[h21]?.total == 5
                },
            )

            assertEquals(
                300,
                repo.steps[h20]!!.total,
            )

            assertEquals(
                2,
                repo.steps.size,
            )

            job.cancel()
        }

    companion object {
        const val MAC =
            "78:02:B7:37:91:E5"

        /** The 30-entry `32` packet from captures/reconnect_20260904_185054.md (session 2026-09-04). */
        const val SLEEP_STAGES =
            "32173a0101001700150201001e00330401000400370201001501100301000101110201000301140401000501190201002b02080301000102090201000a021303010006021902010003021c0401000502210201001702380401000503010201001103120401000203140201002c04040101001e042202010038051e0401000905270201003e062903010001062a02010001062b01010002062d04010005063202010030072604010003072901010013080004010008"
    }
}
