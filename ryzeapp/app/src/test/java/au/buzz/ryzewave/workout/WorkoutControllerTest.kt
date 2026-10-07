package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.WatchEvent
import au.buzz.ryzewave.core.WorkoutControlAction
import au.buzz.ryzewave.ui.ChartData
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.junit.Test

class WorkoutControllerTest {
    private val repo = FakeRepo()
    private val watch = FakeWatch()
    private val settings = FakeSettings(UserProfile(weightKg = 80))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val errors = CopyOnWriteArrayList<String>()

    @Volatile
    private var now = 1_000_000L

    private val finished = CopyOnWriteArrayList<au.buzz.ryzewave.core.Workout>()
    private val watchStartRequests = java.util.concurrent.atomic.AtomicInteger()

    private fun controller(tickMs: Long = 20L) = WorkoutController(
        repo = repo, watch = watch, settings = settings, scope = scope,
        tracker = DefaultGpsDistanceTracker(), clock = { now }, tickMs = tickMs,
        stopHandshakeMs = HANDSHAKE_MS,
        onError = { message, _ -> errors += message },
        onFinished = { finished += it },
        onWatchStartRequested = { watchStartRequests.incrementAndGet() },
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun fullSessionStartHrGpsPauseResumeStop() = runBlocking<Unit> {
        val ctl = controller()
        val start = now
        val id = ctl.start(1)
        assertEquals(1L, id)
        val s0 = ctl.state.value
        assertEquals(WorkoutPhase.RUNNING, s0.state)
        assertEquals(1L, s0.workoutId)
        assertEquals(start, s0.startTime)
        assertEquals(1, s0.sportType)
        assertEquals(listOf("start:1"), watch.calls.toList())
        assertEquals(1, repo.inserted().size)
        assertNull(repo.inserted()[0].end)
        assertEquals(start, repo.inserted()[0].start)

        // live HR from the watch is tagged WORKOUT, averaged and persisted
        watch.hr.emit(HrSample(now, 120, SampleSource.LIVE))
        awaitUntil("first HR in state") { ctl.state.value.lastHr == 120 }
        watch.hr.emit(HrSample(now + 1, 140, SampleSource.LIVE))
        awaitUntil("second HR in state") { ctl.state.value.hrSamples.size == 2 }
        assertEquals(140, ctl.state.value.lastHr)
        assertEquals(130, ctl.state.value.avgHr)
        assertEquals(140, ctl.state.value.maxHr)
        assertTrue(ctl.state.value.hrSamples.all { it.source == SampleSource.WORKOUT })
        awaitUntil("HR persisted") { repo.hr().size == 2 }
        assertTrue(repo.hr().all { it.source == SampleSource.WORKOUT })

        // GPS: two good fixes 100 m apart, then a poor one (stored, not counted)
        now += 5_000L
        ctl.onLocation(now, LAT, LON, 5f, 10f, 10.0)
        now += 10_000L
        ctl.onLocation(now, LAT + 100.0 / DEG_LAT_M, LON, 5f, 10f, 10.0)     // 100 m in 10 s: a fast run
        ctl.onLocation(now + 500L, LAT + 100.0 / DEG_LAT_M, LON, 40f, 0f, null)
        awaitUntil("track points persisted") { repo.points().size == 3 }
        val st = ctl.state.value
        assertEquals(100.0, st.distanceMeters, 0.5)
        assertEquals(3, st.trackPointCount)
        assertEquals(2, st.acceptedPointCount)
        assertEquals(40f, st.gpsAccuracyM)
        assertTrue(st.gpsAvailable)
        assertEquals(listOf(true, true, false), repo.points().sortedBy { it.time }.map { it.accepted })
        assertEquals(1L, repo.points()[0].workoutId)
        assertEquals(10.0, repo.points()[0].altitudeM!!, 0.0)

        // the watch face gets the numbers once per tick
        awaitUntil("FD 44 with the distance") { watch.updates.any { it.distance > 99.0 } }
        val update = watch.updates.last()
        assertEquals(15, update.duration)
        assertEquals(100.0, update.distance, 0.5)
        assertEquals(15, ctl.state.value.elapsedSeconds)

        // pause: the clock keeps going, the elapsed time and the distance do not
        ctl.pause()
        assertEquals(WorkoutPhase.PAUSED, ctl.state.value.state)
        assertEquals("pause", watch.calls.last())
        assertEquals(15, ctl.state.value.elapsedSeconds)
        awaitUntil("paused row persisted") { repo.updates().any { it.durationSeconds == 15 && it.end == null } }
        now += 60_000L
        ctl.onLocation(now, LAT + 500.0 / DEG_LAT_M, LON, 5f, 1.5f, null)
        assertEquals(15, ctl.state.value.elapsedSeconds)
        assertEquals(100.0, ctl.state.value.distanceMeters, 0.5)
        assertEquals(5f, ctl.state.value.gpsAccuracyM)

        // the fix taken while paused is stored (track stays continuous) but not counted and adds no distance
        awaitUntil("paused fix stored") { repo.points().size == 4 }
        val pausedPt = repo.points().maxByOrNull { it.time }!!
        assertTrue(pausedPt.paused)
        assertEquals(false, pausedPt.accepted)

        ctl.resume()
        assertEquals(WorkoutPhase.RUNNING, ctl.state.value.state)
        assertEquals("resume", watch.calls.last())
        now += 5_000L
        ctl.onLocation(now, LAT + 500.0 / DEG_LAT_M, LON, 5f, 1.5f, null)   // re-anchors after the pause
        assertEquals(100.0, ctl.state.value.distanceMeters, 0.5)
        awaitUntil("re-anchor point persisted") { repo.points().size == 5 }
        // the stored track carries the tracker's running total, so the detail screen's "GPS track" agrees
        // with the workout distance instead of adding the 400 m straight line walked during the pause
        val stored = repo.points().sortedBy { it.time }
        assertEquals(listOf(0.0, 100.0, 100.0, 100.0, 100.0), stored.map { it.cumulativeM!! }.map { Math.round(it * 10) / 10.0 })
        val cum = ChartData.cumulativeDistance(stored)
        assertEquals(3, cum.size)                                            // the accepted fixes only
        assertEquals(100.0, cum.last().value, 0.5)

        val final = ctl.stop()
        assertNotNull(final)
        final!!
        assertEquals(WorkoutPhase.STOPPED, ctl.state.value.state)
        assertEquals("stop", watch.calls.last())
        assertEquals(1L, final.id)
        assertEquals(start, final.start)
        assertEquals(now, final.end)
        assertEquals(20, final.durationSeconds)
        assertEquals(100.0, final.distanceMeters, 0.5)
        assertEquals(130, final.avgHr)
        assertEquals(140, final.maxHr)
        assertTrue(final.calories >= 0)
        assertEquals(final, repo.updates().last())
        assertEquals(20, ctl.state.value.elapsedSeconds)
        assertEquals(100.0, ctl.state.value.distanceMeters, 0.5)
        assertNull(ctl.stop())
    }

    @Test
    fun watchFailureDoesNotAbortTheWorkout() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        watch.failStart = true
        val id = ctl.start(1)
        assertEquals(1L, id)
        assertEquals(WorkoutPhase.RUNNING, ctl.state.value.state)
        val error = ctl.state.value.error
        assertNotNull(error)
        assertTrue(error!!.startsWith("startWorkout"))
        assertTrue(errors.any { it.startsWith("startWorkout") })
        // the next successful watch call clears it
        ctl.pause()
        assertNull(ctl.state.value.error)
        assertEquals(WorkoutPhase.PAUSED, ctl.state.value.state)
        ctl.stop()
    }

    /**
     * The stop-cancellation race seen on 2026-09-07: publishing STOPPED makes WorkoutService stop itself, and
     * its onDestroy cancels the scope the stop command runs on — while stop() is still waiting for the watch's
     * ack. The final row write (the one that sets `end`) must survive that cancellation, or the workout stays
     * "in progress / not finished" forever.
     */
    @Test
    fun stopFinalWriteSurvivesScopeCancellation() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.start(1)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        watch.stopGate = gate
        scope.launch { ctl.stop() }
        awaitUntil("STOPPED published") { ctl.state.value.state == WorkoutPhase.STOPPED }
        scope.cancel()               // what WorkoutService.onDestroy does the moment STOPPED is seen
        gate.complete(Unit)          // the watch's ack arrives after the cancellation
        awaitUntil("final row written") { repo.updates().lastOrNull()?.end != null }
        assertTrue(watch.calls.contains("stop"))
        awaitUntil("onFinished ran") { finished.size == 1 }
        assertNotNull(finished[0].end)
    }

    @Test
    fun ticksPushCurrentMetrics() = runBlocking<Unit> {
        val ctl = controller()
        ctl.start(2)
        assertEquals("start:2", watch.calls.first())
        now += 3_000L
        awaitUntil("update at 3 s") { watch.updates.any { it.duration == 3 } }
        val u = watch.updates.first { it.duration == 3 }
        assertEquals(0.0, u.distance, 0.0)
        assertEquals(0.0, u.pace, 0.0)
        assertEquals(2, ctl.state.value.sportType)
        val w = ctl.stop()!!
        assertEquals(2, w.sportType)
        assertEquals(3, w.durationSeconds)
    }

    @Test
    fun startIsIdempotentWhileActive() = runBlocking<Unit> {
        val ctl = controller()
        val a = ctl.start(1)
        val b = ctl.start(1)
        assertEquals(a, b)
        assertEquals(1, repo.inserted().size)
        assertEquals(1, watch.calls.count { it.startsWith("start") })
        ctl.stop()
    }

    @Test
    fun databaseFailureIsReportedNotFatal() = runBlocking<Unit> {
        val ctl = controller()
        repo.failInsertPoints = true
        ctl.start(1)
        ctl.onLocation(now, LAT, LON, 5f, 1f, null)
        awaitUntil("insert failure reported") { errors.any { it.startsWith("insertTrackPoints") } }
        assertEquals(WorkoutPhase.RUNNING, ctl.state.value.state)
        assertEquals(1, ctl.state.value.trackPointCount)
        ctl.stop()
    }

    @Test
    fun locationIsIgnoredWhenStopped() {
        val ctl = controller()
        ctl.onLocation(now, LAT, LON, 5f, 1f, null)
        assertEquals(0, ctl.state.value.trackPointCount)
        assertNull(ctl.state.value.gpsAccuracyM)
        ctl.onGpsAvailability(true)
        assertTrue(ctl.state.value.gpsAvailable)
    }

    @Test
    fun metBasedCalories() {
        assertEquals(1.3, WorkoutController.metForSpeed(0.0), 1e-9)
        assertEquals(1.0 + 0.1 * 84.0 / 3.5, WorkoutController.metForSpeed(1.4), 1e-9)    // 5 km/h walk ≈ 3.4 MET
        assertEquals(1.0 + 0.2 * 180.0 / 3.5, WorkoutController.metForSpeed(3.0), 1e-9)   // 10.8 km/h run ≈ 11.3 MET
        assertEquals(3.4 * 75.0, WorkoutController.caloriesFor(1.4, 75.0, 3600.0), 1e-6)
        assertEquals(1.3 * 75.0 / 60.0, WorkoutController.caloriesFor(0.0, 75.0, 60.0), 1e-9)
        // walk/run blend: no 80 % step at 2.0 m/s, the midpoint is the mean of the two equations
        val walk2 = 1.0 + 0.1 * 120.0 / 3.5
        val run2 = 1.0 + 0.2 * 120.0 / 3.5
        assertEquals((walk2 + run2) / 2, WorkoutController.metForSpeed(2.0), 1e-9)
        assertTrue(WorkoutController.metForSpeed(2.01) - WorkoutController.metForSpeed(1.99) < 0.5)
        // a segment credited at its own speed equals the tick formula
        assertEquals(WorkoutController.caloriesFor(3.0, 75.0, 60.0), WorkoutController.caloriesForSegment(180.0, 60.0, 3.0, 75.0), 1e-9)
        // Keytel: 150 bpm, 75 kg, 40 y male ≈ 14.9 kcal/min
        assertEquals(14.9, WorkoutController.hrCaloriesPerMinute(150, 75.0, 40, true), 0.1)
    }

    @Test
    fun gpsOutageIsCreditedForTheBridgedDistanceOnce() = runBlocking<Unit> {
        // ticks are driven by the clock; use a slow ticker and call onLocation between manual clock steps
        val ctl = controller(tickMs = 30L)
        ctl.start(1)
        // 5:00/km runner: 3.33 m/s, one fix per second for 10 s
        for (i in 0..10) {
            now += 1_000L
            ctl.onLocation(now, LAT + 3.33 * i / DEG_LAT_M, LON, 5f, 3.33f, null)
            awaitUntil("tick $i") { ctl.state.value.elapsedSeconds >= i + 1 }
        }
        val beforeGap = ctl.state.value.calories
        // 5-minute tunnel: no fixes; then one fix 1000 m further along
        now += 300_000L
        awaitUntil("tick after gap") { ctl.state.value.elapsedSeconds >= 311 }
        val duringGap = ctl.state.value.calories - beforeGap
        ctl.onLocation(now, LAT + (3.33 * 10 + 1000.0) / DEG_LAT_M, LON, 5f, 3.33f, null)
        now += 1_000L
        awaitUntil("bridged distance credited") { ctl.state.value.calories > beforeGap + 50 }
        val afterGap = ctl.state.value.calories - beforeGap
        // 80 kg at 3.33 m/s: ACSM running ≈ 16.5 kcal/min -> ~83 kcal for the 5 min; resting alone would be ~9
        assertTrue("gap credit $duringGap", duringGap < 20)
        assertTrue("bridged credit $afterGap", afterGap in 60..110)
        ctl.stop()
    }

    @Test
    fun hostErrorSurvivesWatchCallsAndStart() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.reportError("Location permission missing: no GPS distance")
        ctl.start(1)
        assertEquals("Location permission missing: no GPS distance", ctl.state.value.hostError)
        ctl.pause()                                        // a successful watch call clears `error`, not `hostError`
        assertNull(ctl.state.value.error)
        assertEquals("Location permission missing: no GPS distance", ctl.state.value.hostError)
        assertTrue(ctl.state.value.message!!.startsWith("Location permission"))
        ctl.clearReportedError()
        assertNull(ctl.state.value.hostError)
        ctl.stop()
    }

    companion object {
        const val LAT = -27.4698
        const val LON = 153.0251
        const val DEG_LAT_M = DefaultGpsDistanceTrackerTest.DEG_LAT_M

        const val HANDSHAKE_MS = 100L
    }

    /**
     * A wrist press acts AT ONCE (Buzz, 2026-09-15). This used to be delayed 8 s to swallow a supposed junk
     * flood; measurement showed the watch never emits pause/resume unprompted (85 s untouched, including 40 s
     * with no `FD 44`, produced zero — captures/bridge_20260915_183348.txt), so every press is a real one.
     * pause,resume,pause,resume must therefore produce all four transitions, each announced.
     */
    @Test
    fun watchPauseResumeBurstIsAppliedImmediatelyInOrder() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.start(1)
        val phases = CopyOnWriteArrayList<WorkoutPhase>()
        val job = scope.launch {
            ctl.state.collect { if (phases.lastOrNull() != it.state) phases += it.state }
        }
        awaitUntil("collector sees RUNNING") { phases.contains(WorkoutPhase.RUNNING) }

        for (a in listOf(
            WorkoutControlAction.PAUSE, WorkoutControlAction.RESUME,
            WorkoutControlAction.PAUSE, WorkoutControlAction.RESUME,
        )) {
            val want = if (a == WorkoutControlAction.PAUSE) WorkoutPhase.PAUSED else WorkoutPhase.RUNNING
            watch.emitEvent(WatchEvent.WorkoutControl(a))
            awaitUntil("$a applies at once") {
                ctl.state.value.state == want && phases.lastOrNull() == want
            }
        }

        assertEquals(
            "every press produces its transition",
            listOf(WorkoutPhase.RUNNING, WorkoutPhase.PAUSED, WorkoutPhase.RUNNING, WorkoutPhase.PAUSED, WorkoutPhase.RUNNING),
            phases.toList(),
        )
        assertFalse("must not echo pause back to the watch", watch.calls.contains("pause"))
        assertFalse("must not echo resume back to the watch", watch.calls.contains("resume"))

        // Each transition is spoken, so the user hears the press they just made.
        val announcer = StateAnnouncer()
        val spoken = phases.mapNotNull { announcer.onPhase(it) }
        assertEquals(
            listOf(StateAnnouncer.STARTED, StateAnnouncer.PAUSED, StateAnnouncer.RESUMED, StateAnnouncer.PAUSED, StateAnnouncer.RESUMED),
            spoken,
        )

        job.cancel()
        ctl.stop()
    }

    /** A watch pause is applied on the spot — no settle window to wait out. */
    @Test
    fun watchPauseAppliesImmediately() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.start(1)
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.PAUSE))
        awaitUntil("pause applies at once") { ctl.state.value.state == WorkoutPhase.PAUSED }
        assertFalse("a watch pause is not echoed back", watch.calls.contains("pause"))
        ctl.stop()
    }

    /** A watch resume is applied on the spot. */
    @Test
    fun watchResumeAppliesImmediately() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.start(1)
        ctl.pause()                                        // get to PAUSED first (app button)
        assertEquals(WorkoutPhase.PAUSED, ctl.state.value.state)
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.RESUME))
        awaitUntil("resume applies at once") { ctl.state.value.state == WorkoutPhase.RUNNING }
        assertFalse("a watch resume is not echoed back", watch.calls.contains("resume"))
        ctl.stop()
    }

    /** A watch control asking for the state we are already in is a no-op (the only guard that remains). */
    @Test
    fun watchControlForTheCurrentStateDoesNothing() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.start(1)
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.RESUME))   // already RUNNING
        Thread.sleep(50)
        assertEquals(WorkoutPhase.RUNNING, ctl.state.value.state)
        assertFalse("no echo back to the watch", watch.calls.contains("resume"))
        ctl.stop()
    }

    /** App-button pause/resume are immediate too. */
    @Test
    fun appPauseAndResumeAreImmediate() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.start(1)
        ctl.pause()
        assertEquals(WorkoutPhase.PAUSED, ctl.state.value.state)
        assertEquals("pause", watch.calls.last())
        ctl.resume()
        assertEquals(WorkoutPhase.RUNNING, ctl.state.value.state)
        assertEquals("resume", watch.calls.last())
        ctl.stop()
    }

    /** Session strides are read from settings at start so the UI can build the step-based estimate. */
    @Test
    fun startPopulatesSessionStrides() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.start(1)
        val s = ctl.state.value
        // FakeSettings has no calibration -> DefaultStrideModel derives from the 175 cm male profile.
        assertEquals(175 * 0.410 / 100.0, s.walkStrideMeters, 1e-9)
        assertEquals(175 * 0.546 / 100.0, s.runStrideMeters, 1e-9)
        ctl.stop()
    }

    @Test
    fun stopReportsTheFinalRowOnceEvenWithZeroDistance() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.start(1)
        now += 30_000L
        val w = ctl.stop()!!
        assertEquals(listOf(w), finished.toList())
        assertEquals(0.0, w.distanceMeters, 0.0)
        assertNotNull(w.end)
        assertEquals(repo.updates().last(), w)     // the row is written before the hook fires
        assertNull(ctl.stop())
        assertEquals(1, finished.size)
    }

    /**
     * The 2026-09-10 field bug: a watch-button START was announced but nothing tracked. The controller must hand
     * the press to the host (which starts the foreground service) — and only while nothing is active.
     */
    @Test
    fun watchStartWhileStoppedRequestsAHostStart() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.START))
        awaitUntil("host start requested") { watchStartRequests.get() == 1 }
        assertEquals(WorkoutPhase.STOPPED, ctl.state.value.state)   // the host starts the session, not this event

        // while a workout is active a watch START is ignored (the watch echoes its own state)
        ctl.start(1)
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.STOP))
        awaitUntil("stopped from the watch") { ctl.state.value.state == WorkoutPhase.STOPPED }
        assertEquals(1, watchStartRequests.get())
        ctl.start(1)
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.START))
        Thread.sleep(50)
        assertEquals("no request while active", 1, watchStartRequests.get())
        ctl.stop()
    }

    /**
     * The 2026-09-11 restart loop: the watch answers the app's `FD 00` stop with a 4-byte `FD 11`, which parses
     * as a watch START. Within the stop-handshake window it is noise, not a press; after it, a real press.
     */
    @Test
    fun watchStartRightAfterAStopIsIgnoredAsHandshakeNoise() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.start(1)
        now += 5_000L
        ctl.stop()
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.START))
        Thread.sleep(50)
        assertEquals("the FD 11 stop reply must not restart", 0, watchStartRequests.get())
        now += HANDSHAKE_MS + 1
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.START))
        awaitUntil("a later real press is honoured") { watchStartRequests.get() == 1 }
    }

    /**
     * 2026-09-12: a real wrist press a few seconds after stopping the previous workout was swallowed because the
     * handshake window was 8 s long. It is now short and covers only the watch's `FD 11` reply to our `FD 00`,
     * so a press just past it is honoured.
     */
    @Test
    fun watchStartShortlyAfterAStopIsARealPress() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.start(1)
        now += 5_000L
        ctl.stop()
        now += HANDSHAKE_MS + 1
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.START))
        awaitUntil("a press between the two windows is honoured") { watchStartRequests.get() == 1 }
    }

    /** A fromWatch start must not echo `FD 11` (the watch is already in exercise mode). */
    @Test
    fun fromWatchStartDoesNotSendStartToTheWatch() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        val id = ctl.start(sportType = 1, fromWatch = true)
        assertEquals(1L, id)
        assertEquals(WorkoutPhase.RUNNING, ctl.state.value.state)
        assertFalse("FD 11 must not be echoed", watch.calls.any { it.startsWith("start") })
        // a normal stop from the app still tells the watch
        ctl.stop()
        assertTrue(watch.calls.contains("stop"))
    }

    /** The START control names no sport: a fromWatch session adopts it from the first realtime push. */
    @Test
    fun fromWatchSessionAdoptsTheSportFromTheRealtimePush() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.start(sportType = 1, fromWatch = true)
        watch.emitEvent(WatchEvent.WorkoutRealtime(sportType = 3, steps = 10, calories = 1, distanceMeters = 5.0))
        awaitUntil("sport adopted") { ctl.state.value.sportType == 3 }
        val final = ctl.stop()!!
        assertEquals(3, final.sportType)
    }

    /** An app-started session keeps the sport the user picked, whatever the watch pushes. */
    @Test
    fun appStartedSessionKeepsItsSport() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.start(sportType = 2)
        watch.emitEvent(WatchEvent.WorkoutRealtime(sportType = 3, steps = 10, calories = 1, distanceMeters = 5.0))
        awaitUntil("steps arrived") { ctl.state.value.steps == 10 }
        assertEquals(2, ctl.state.value.sportType)
        assertEquals(2, ctl.stop()!!.sportType)
    }

    @Test
    fun watchOriginatedPausePausesWithoutSendingPauseBack() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.start(1)
        assertEquals(listOf("start:1"), watch.calls.toList())
        // the watch itself paused (an unsolicited FD 22): the controller must pause but NOT echo FD 22 back
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.PAUSE))
        awaitUntil("controller paused from the watch") { ctl.state.value.state == WorkoutPhase.PAUSED }
        assertFalse("must not re-send pause to the watch", watch.calls.contains("pause"))
        assertEquals(listOf("start:1"), watch.calls.toList())

        // and resume from the watch resumes without echoing FD 33
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.RESUME))
        awaitUntil("controller resumed from the watch") { ctl.state.value.state == WorkoutPhase.RUNNING }
        assertFalse("must not re-send resume to the watch", watch.calls.contains("resume"))

        // stop from the watch finalises the row without echoing FD 00
        watch.emitEvent(WatchEvent.WorkoutControl(WorkoutControlAction.STOP))
        awaitUntil("controller stopped from the watch") { ctl.state.value.state == WorkoutPhase.STOPPED }
        assertFalse("must not re-send stop to the watch", watch.calls.contains("stop"))
        awaitUntil("row finalised") { repo.updates().lastOrNull()?.end != null }
        assertEquals(1, finished.size)
    }

    @Test
    fun appPauseStillSendsPauseToTheWatch() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.start(1)
        ctl.pause()
        assertEquals("pause", watch.calls.last())
        assertEquals(WorkoutPhase.PAUSED, ctl.state.value.state)
        ctl.stop()
    }

    @Test
    fun stopFromPausedFinalisesTheRow() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.start(1)
        watch.hr.emit(HrSample(now, 120, SampleSource.LIVE))
        awaitUntil("hr in state") { ctl.state.value.lastHr == 120 }
        now += 20_000L
        ctl.pause()
        assertEquals(WorkoutPhase.PAUSED, ctl.state.value.state)
        now += 60_000L                                   // 60 s paused: not counted in the active duration
        val final = ctl.stop()!!
        assertEquals(WorkoutPhase.STOPPED, ctl.state.value.state)
        assertNotNull(final.end)
        assertEquals(20, final.durationSeconds)          // active time only, pause excluded
        assertEquals(120, final.avgHr)
        assertEquals(120, final.maxHr)
        assertEquals(final, repo.updates().last())
        assertNotNull(repo.updates().last().end)
    }

    @Test
    fun watchStepsAreRecordedAsTheWorkoutStepMaximum() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.start(1)
        watch.emitEvent(WatchEvent.WorkoutRealtime(sportType = 1, steps = 100, calories = 5, distanceMeters = 80.0))
        awaitUntil("first step count") { ctl.state.value.steps == 100 }
        watch.emitEvent(WatchEvent.WorkoutRealtime(sportType = 1, steps = 3933, calories = 40, distanceMeters = 3000.0))
        awaitUntil("rising step count") { ctl.state.value.steps == 3933 }
        watch.emitEvent(WatchEvent.WorkoutRealtime(sportType = 1, steps = 10, calories = 0, distanceMeters = 0.0))
        // a lower value (a fresh push) never lowers the recorded maximum
        Thread.sleep(30)
        assertEquals(3933, ctl.state.value.steps)
        val final = ctl.stop()!!
        assertEquals(3933, final.steps)
        assertNull(final.phoneSteps)
    }

    @Test
    fun phoneStepsAreRecordedOnTheRow() = runBlocking<Unit> {
        val ctl = controller(tickMs = 10_000L)
        ctl.start(1)
        ctl.onPhoneSteps(250)
        awaitUntil("phone steps in state") { ctl.state.value.phoneSteps == 250 }
        val final = ctl.stop()!!
        assertEquals(250, final.phoneSteps)
    }

    @Test
    fun fixesDuringPauseAreStoredNotCountedAndResumeContinuesTheTrack() = runBlocking<Unit> {
        val ctl = controller(tickMs = 20L)
        ctl.start(1)
        now += 5_000L
        ctl.onLocation(now, LAT, LON, 5f, 10f, 10.0)
        now += 10_000L
        ctl.onLocation(now, LAT + 100.0 / DEG_LAT_M, LON, 5f, 10f, 10.0)   // 100 m in 10 s: a fast run, accepted
        awaitUntil("two running fixes") { repo.points().size == 2 }
        val distanceBeforePause = ctl.state.value.distanceMeters
        assertEquals(100.0, distanceBeforePause, 0.5)

        ctl.pause()
        now += 60_000L
        ctl.onLocation(now, LAT + 500.0 / DEG_LAT_M, LON, 5f, 1.5f, null)  // far, but paused: not counted
        awaitUntil("paused fix stored") { repo.points().size == 3 }
        assertEquals("distance must not grow while paused", distanceBeforePause, ctl.state.value.distanceMeters, 0.01)
        val paused = repo.points().sortedBy { it.time }.last()
        assertTrue(paused.paused)
        assertFalse(paused.accepted)

        ctl.resume()
        now += 5_000L
        ctl.onLocation(now, LAT + 500.0 / DEG_LAT_M, LON, 5f, 1.5f, null)  // re-anchors after the pause
        awaitUntil("resume fix stored") { repo.points().size == 4 }
        // exactly one paused point, and the distance is continuous (no jump across the 400 m walked while paused)
        assertEquals(1, repo.points().count { it.paused })
        assertEquals(distanceBeforePause, ctl.state.value.distanceMeters, 0.5)
        ctl.stop()
    }
}
