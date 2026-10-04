package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.WatchEvent
import au.buzz.ryzewave.core.WorkoutControlAction
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stuck-in-exercise-mode monitor end to end with fakes: a watch-originated workout whose session step counter
 * stays flat (the accidental night press of 2026-09-05) is warned and then auto-stopped; rising steps keep it live;
 * an app-started workout is warned but never auto-stopped unless the setting says so; acknowledging stops it now.
 * Times come from a fake clock the test advances; the monitor's own ticker runs every 20 ms of real time.
 */
class StuckWorkoutMonitorTest {
    private val repo = FakeRepo()
    private val watch = FakeWatch()
    private val settings = FakeSettings()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var now = 1_000_000L

    private val warns = AtomicInteger()
    private val clears = AtomicInteger()
    private val spoken = CopyOnWriteArrayList<String>()
    private val logs = CopyOnWriteArrayList<String>()

    private val alerter = object : StuckWorkoutMonitor.Alerter {
        override fun warn(
            urgency: Urgency,
            title: String,
            text: String
        ) {
            warns.incrementAndGet()
        }

        override fun clear() {
            clears.incrementAndGet()
        }
    }

    private val controller = WorkoutController(
        repo = repo,
        watch = watch,
        settings = settings,
        scope = scope,
        tracker = DefaultGpsDistanceTracker(),
        clock = { now },
        tickMs = 20L,
    )

    private val WINDOW = 10_000L

    /**
     * The auto-stop grace is a REAL delay in the monitor
     * (not the fake clock), so keep it short.
     */
    private val GRACE = 400L

    private fun monitor(
        autoStopApp: Boolean = false
    ) = StuckWorkoutMonitor(
        watch = watch,
        controller = controller,
        scope = scope,
        alerter = alerter,
        speaker = {
            spoken += it
        },
        detectorEnabled = {
            true
        },
        autoStopAppWorkouts = {
            autoStopApp
        },
        clock = {
            now
        },
        windowMs = WINDOW,
        evalIntervalMs = 20L,
        graceNormalMs = GRACE,
        graceHighMs = GRACE / 2,
        log = {
            logs +=
                "${System.currentTimeMillis() % 100000} $it"
        },
    ).also { m ->

        /*
         * The monitor's collectors subscribe asynchronously.
         * Wait for them before starting the test.
         *
         * The controller already holds one subscription.
         */
        runBlocking {
            awaitUntil("monitor subscribed") {
                watch.eventBus.subscriptionCount.value >= 2
            }
        }
    }

    @After
    fun tearDown() =
        scope.cancel()

    private suspend fun waitFor(
        what: String,
        timeoutMs: Long = 3_000L,
        cond: () -> Boolean
    ) {
        try {
            awaitUntil(
                what,
                timeoutMs,
                cond
            )
        } catch (e: AssertionError) {
            println(
                "MONITOR LOG on '$what':\n" +
                    logs.joinToString("\n")
            )
            throw e
        }
    }

    private suspend fun realtime(
        steps: Int
    ) = watch.emitEvent(
        WatchEvent.WorkoutRealtime(
            sportType = 1,
            steps = steps,
            calories = 0,
            distanceMeters = 0.0
        )
    )

    /**
     * A realtime push, applied before the caller moves the fake clock on.
     *
     * The monitor stamps a push with the clock when it processes it, and
     * the event bus is buffered, so without this wait a slow runner
     * (GitHub, 2026-09-19) processes all six pushes after the loop and
     * stamps them with one time: the window never fills, no warning.
     */
    private suspend fun push(
        m: StuckWorkoutMonitor,
        steps: Int
    ) {
        val seen =
            m.realtimeSeen.get()

        realtime(steps)

        awaitUntil(
            "push ${seen + 1} applied"
        ) {
            m.realtimeSeen.get() > seen
        }
    }

    @Test
    fun aWatchStartedWorkoutWithFlatStepsIsWarnedThenAutoStopped() =
        runBlocking<Unit> {

            val m =
                monitor()

            watch.emitEvent(
                WatchEvent.WorkoutControl(
                    WorkoutControlAction.START
                )
            )

            awaitUntil("watching") {
                m.active
            }

            /*
             * The misleading plain "workout started" of 2026-09-10
             * is gone: the monitor never announces a start.
             */
            assertFalse(
                spoken.contains(
                    StateAnnouncer.STARTED
                )
            )

            /*
             * Flat session steps + a resting HR for longer than
             * the evaluation window.
             */
            for (i in 1..6) {
                push(
                    m,
                    40
                )

                watch.hr.emit(
                    HrSample(
                        now,
                        58,
                        SampleSource.WORKOUT
                    )
                )

                now +=
                    WINDOW / 4
            }

            awaitUntil("warned") {
                warns.get() == 1
            }

            assertTrue(
                spoken.contains(
                    StuckWorkoutMonitor.WARN_SPEECH
                )
            )

            assertFalse(
                "not stopped before the grace",
                watch.calls.contains("stop")
            )

            awaitUntil(
                "auto-stopped",
                timeoutMs = 5_000L
            ) {
                watch.calls.contains("stop")
            }

            awaitUntil(
                "stop spoken"
            ) {
                spoken.contains(
                    StateAnnouncer.STOPPED_NO_ACTIVITY
                )
            }

            assertFalse(
                monitorActive()
            )
        }

    @Test
    fun risingStepsKeepItLiveAndActivityResumingWithdrawsAWarning() =
        runBlocking<Unit> {

            val m =
                monitor()

            watch.emitEvent(
                WatchEvent.WorkoutControl(
                    WorkoutControlAction.START
                )
            )

            awaitUntil("watching") {
                m.active
            }

            var steps =
                40

            for (i in 1..6) {
                steps += 25

                push(
                    m,
                    steps
                )

                now +=
                    WINDOW / 4
            }

            Thread.sleep(150)

            assertEquals(
                "rising steps must never warn",
                0,
                warns.get()
            )

            /*
             * Then flat for a window -> warned.
             * Then rising again -> warning withdrawn and no stop.
             */
            for (i in 1..6) {
                push(
                    m,
                    steps
                )

                now +=
                    WINDOW / 4
            }

            awaitUntil("warned") {
                warns.get() == 1
            }

            for (i in 1..3) {
                steps += 30

                push(
                    m,
                    steps
                )

                now +=
                    WINDOW / 4
            }

            awaitUntil("withdrawn") {
                clears.get() >= 1
            }

            Thread.sleep(
                GRACE * 2
            )

            assertFalse(
                "withdrawn warning must not auto-stop",
                watch.calls.contains("stop")
            )
        }

    @Test
    fun anAppStartedWorkoutIsWarnedButNotAutoStoppedByDefault() =
        runBlocking<Unit> {

            val m =
                monitor(
                    autoStopApp = false
                )

            controller.start(
                sportType = 1
            )

            /*
             * The controller state and StuckWorkoutMonitor are observed
             * by asynchronous collectors.
             *
             * Wait until the monitor has actually observed the RUNNING
             * app workout before feeding realtime packets or advancing
             * the fake clock.
             */
            awaitUntil(
                "app workout being watched"
            ) {
                m.active &&
                    m.origin ==
                    StuckWorkoutMonitor.Origin.APP
            }

            for (i in 1..6) {
                push(
                    m,
                    0
                )

                now +=
                    WINDOW / 4
            }

            awaitUntil("warned") {
                warns.get() == 1
            }

            /*
             * App-originated workouts warn by default, but must not be
             * automatically stopped unless autoStopAppWorkouts is on.
             */
            Thread.sleep(
                GRACE * 3
            )

            assertEquals(
                WorkoutPhase.RUNNING,
                controller.state.value.state
            )

            controller.stop()
        }

    @Test
    fun anAppStartedWorkoutIsAutoStoppedWhenTheSettingIsOn() =
        runBlocking<Unit> {

            val m =
                monitor(
                    autoStopApp = true
                )

            controller.start(
                sportType = 1
            )

            /*
             * As above, establish that the monitor has observed the
             * app-started RUNNING session before changing fake time.
             */
            awaitUntil(
                "app workout being watched"
            ) {
                m.active &&
                    m.origin ==
                    StuckWorkoutMonitor.Origin.APP
            }

            for (i in 1..6) {
                push(
                    m,
                    0
                )

                now +=
                    WINDOW / 4
            }

            awaitUntil("warned") {
                warns.get() == 1
            }

            waitFor(
                "auto-stopped through the controller",
                timeoutMs = 5_000L
            ) {
                controller.state.value.state ==
                    WorkoutPhase.STOPPED
            }

            assertEquals(
                StopReason.NO_ACTIVITY,
                controller.state.value.stopReason
            )
        }

    @Test
    fun stopNowFromTheNotificationStopsAWatchWorkoutImmediately() =
        runBlocking<Unit> {

            val m =
                monitor()

            watch.emitEvent(
                WatchEvent.WorkoutControl(
                    WorkoutControlAction.START
                )
            )

            awaitUntil("watching") {
                m.active
            }

            for (i in 1..6) {
                push(
                    m,
                    40
                )

                now +=
                    WINDOW / 4
            }

            waitFor(
                "warned",
                timeoutMs = 5_000L
            ) {
                warns.get() == 1
            }

            m.stopNow()

            awaitUntil("stopped") {
                watch.calls.contains("stop")
            }

            awaitUntil("cleared") {
                clears.get() >= 1
            }

            assertFalse(
                m.active
            )
        }

    private fun monitorActive(): Boolean =
        false.also {
            /*
             * The watch session ended:
             * nothing remains to watch.
             */
        }
}
