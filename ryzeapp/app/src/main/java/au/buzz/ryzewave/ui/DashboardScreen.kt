package au.buzz.ryzewave.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import au.buzz.ryzewave.core.BloodPressureReading
import au.buzz.ryzewave.core.ConnectionState
import au.buzz.ryzewave.core.DailySummary
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.RestingHr
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.WatchStatus
import au.buzz.ryzewave.core.WatchFaceConfig
import au.buzz.ryzewave.core.WatchFaceUploadProgress
import au.buzz.ryzewave.core.Workout
import au.buzz.ryzewave.protocol.SportTypes
import au.buzz.ryzewave.workout.FitnessBand
import au.buzz.ryzewave.workout.HeartRateRecovery
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    vm: DashboardViewModel = viewModel()
) {
    val status by vm.status.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val sleep by vm.sleep.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val liveHr by vm.liveHr.collectAsStateWithLifecycle()
    val restingHr by vm.restingHr.collectAsStateWithLifecycle()
    val recovery by vm.recovery.collectAsStateWithLifecycle()
    val measuring by vm.measuring.collectAsStateWithLifecycle()

    // P32 legacy UTE/GloryFit blood-pressure result.
    val bloodPressure by vm.bloodPressure.collectAsStateWithLifecycle()

    val watchFaceConfig by vm.watchFaceConfig.collectAsStateWithLifecycle()
    val watchFaceUpload by vm.watchFaceUpload.collectAsStateWithLifecycle()

    val message by vm.message.collectAsStateWithLifecycle()

    val snackbar =
        remember {
            SnackbarHostState()
        }

    MessageSnackbar(
        message,
        vm::clearMessage,
        snackbar
    )

    LaunchedEffect(Unit) {
        vm.refreshDay()
    }

    // A slow clock so "Last sync … ago" and the "(live)" HR label age while the screen stays open.
    var now by remember {
        mutableLongStateOf(
            System.currentTimeMillis()
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000L)
            now =
                System.currentTimeMillis()
        }
    }

    // Connect needs BLUETOOTH_CONNECT (Android 12+): ask for it here when the startup prompt was refused.
    val bluetoothLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
            if (vm.hasBluetoothPermission()) {
                vm.connect()
            }
        }

    val watchFacePicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                vm.installWatchFace(uri)
            }
        }

    val onConnect: () -> Unit = {
        if (
            vm.hasBluetoothPermission() ||
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.S
        ) {
            vm.connect()
        } else {
            bluetoothLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbar)
        }
    ) { padding ->

        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(12.dp),
            verticalArrangement =
                Arrangement.spacedBy(12.dp),
        ) {

            ConnectionCard(
                status,
                busy,
                now,
                onConnect,
                vm::disconnect,
                vm::sync,
                vm::syncLiveWeather,
                vm::testWeatherByte
            )

            StepsCard(
                today,
                profile.stepGoal
            )

            VitalsCard(
                today = today,
                liveHr = liveHr,
                restingHr = restingHr,
                recovery = recovery,
                bloodPressure = bloodPressure,
                measuring = measuring,
                busy = busy,
                now = now,
                connected = status.isConnected(),
                onMeasureHr = vm::measureHr,
                showSpo2 = !status.firmware.orEmpty().startsWith("RB112UDG", ignoreCase = true),
                onSpo2 = vm::spo2Test,
                onBloodPressure = vm::bloodPressureTest,
            )

            WatchFaceCard(
                config = watchFaceConfig,
                upload = watchFaceUpload,
                connected = status.isConnected(),
                busy = busy,
                onRead = vm::readWatchFaceInfo,
                onInstall = {
                    watchFacePicker.launch(
                        arrayOf(
                            "application/octet-stream",
                            "application/x-binary",
                            "*/*",
                        )
                    )
                },
            )

            SleepCard(sleep)
        }
    }
}

/** Shows each non-null [message] once in [host], then calls [onShown]. */
@Composable
fun MessageSnackbar(
    message: String?,
    onShown: () -> Unit,
    host: SnackbarHostState
) {
    LaunchedEffect(message) {
        if (message != null) {
            host.showSnackbar(message)
            onShown()
        }
    }
}

fun WatchStatus.isConnected(): Boolean =
    state == ConnectionState.CONNECTED ||
        state == ConnectionState.SYNCING

fun connectionLabel(
    s: WatchStatus
): String =
    when (s.state) {

        ConnectionState.DISCONNECTED ->
            "Disconnected"

        ConnectionState.CONNECTING ->
            "Connecting…"

        ConnectionState.CONNECTED ->
            "Connected"

        ConnectionState.SYNCING ->
            "Syncing…"

        ConnectionState.ERROR ->
            "Error" +
                (
                    s.message?.let {
                        ": $it"
                    } ?: ""
                )
    }

/** Small label-over-value block used in the cards. */
@Composable
fun StatText(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier) {

        Text(
            label,
            style =
                MaterialTheme.typography.labelMedium,
            color =
                MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            value,
            style =
                MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun ConnectionCard(
    status: WatchStatus,
    busy: Boolean,
    now: Long,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSync: () -> Unit,
    onReplayWeather: () -> Unit,
    onTestWeatherCondition: (Int, Int) -> Unit,
) {
    var testCondition by remember { mutableIntStateOf(1) }
    val working =
        busy ||
            status.state ==
            ConnectionState.CONNECTING ||
            status.state ==
            ConnectionState.SYNCING

    ElevatedCard(
        Modifier.fillMaxWidth()
    ) {

        Column(
            Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(12.dp)
        ) {

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Icon(
                    Icons.Filled.Watch,
                    contentDescription = null,
                    tint =
                        MaterialTheme.colorScheme.primary
                )

                Spacer(
                    Modifier.width(12.dp)
                )

                Column(
                    Modifier.weight(1f)
                ) {

                    Text(
                        "Dapper's P32 SmartTrax",
                        style =
                            MaterialTheme.typography.titleMedium
                    )

                    Text(
                        connectionLabel(status),
                        style =
                            MaterialTheme.typography.bodyMedium
                    )

                    status.mac?.let {

                        Text(
                            it,
                            style =
                                MaterialTheme.typography.labelSmall,
                            color =
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (working) {
                    CircularProgressIndicator(
                        Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(20.dp)
            ) {

                val battery =
                    status.batteryPercent?.let {
                        "$it %" +
                            (
                                if (status.charging) {
                                    " ⚡"
                                } else {
                                    ""
                                }
                            )
                    } ?: "–"

                StatText(
                    "Battery",
                    battery,
                    Modifier.weight(1f)
                )

                StatText(
                    "Last sync",
                    Fmt.relative(
                        status.lastSyncTime,
                        now
                    ),
                    Modifier.weight(1f)
                )
            }

            StatText(
                "Firmware",
                status.firmware ?: "–"
            )

            if (
                status.state !=
                ConnectionState.ERROR
            ) {

                status.message?.let {

                    Text(
                        it,
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {

                if (status.isConnected()) {

                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = !busy
                    ) {
                        Text("Disconnect")
                    }

                } else {

                    Button(
                        onClick = onConnect,
                        enabled = !working
                    ) {
                        Text("Connect")
                    }
                }

                FilledTonalButton(
                    onClick = onSync,
                    enabled =
                        status.isConnected() &&
                            !working
                ) {
                    Text("Sync now")
                }
            }

            // Keep weather actions outside the connection-button Row. A long label
            // inside that Row forces a very narrow button and expands its height.
            OutlinedButton(
                onClick = onReplayWeather,
                enabled = status.isConnected() && !working,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sync weather")
            }

            Text(
                "Condition code tester (GloryFit mapping)",
                style = MaterialTheme.typography.labelMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { if (testCondition > 1) testCondition-- },
                    enabled = !working && testCondition > 1
                ) { Text("−") }
                Text("$testCondition / 12")
                OutlinedButton(
                    onClick = { if (testCondition < 12) testCondition++ },
                    enabled = !working && testCondition < 12
                ) { Text("+") }
            }
            Text("Verified GloryFit condition position: CB01 byte 2.", style = MaterialTheme.typography.labelMedium)
            Text("Diagnostic sends 42°C (high 47°C) with the selected condition code. Normal sync restores live weather.", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { onTestWeatherCondition(2, testCondition) },
                enabled = status.isConnected() && !working,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Test selected condition") }
        }
    }
}

@Composable
private fun StepsCard(
    today: DailySummary?,
    goal: Int
) {
    val steps =
        today?.steps ?: 0

    ElevatedCard(
        Modifier.fillMaxWidth()
    ) {

        Row(
            Modifier.padding(16.dp),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            GoalRing(
                steps,
                goal
            )

            Spacer(
                Modifier.width(20.dp)
            )

            Column(
                verticalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {

                Text(
                    "Today",
                    style =
                        MaterialTheme.typography.titleMedium
                )

                StatText(
                    "Goal",
                    "${Fmt.int(goal)} steps"
                )

                StatText(
                    "Distance",
                    Fmt.km(
                        today?.distanceMeters
                            ?: 0.0
                    )
                )

                StatText(
                    "Walk / run",
                    "${Fmt.int(today?.walkSteps ?: 0)} / ${
                        Fmt.int(
                            today?.runSteps ?: 0
                        )
                    }"
                )

                val workoutSteps =
                    today?.workoutSteps ?: 0

                if (workoutSteps > 0) {

                    StatText(
                        "Workout",
                        "${Fmt.int(workoutSteps)} steps"
                    )
                }
            }
        }
    }
}

/** Steps-vs-goal ring, drawn on a Canvas with the count in the middle. */
@Composable
fun GoalRing(
    steps: Int,
    goal: Int,
    modifier: Modifier = Modifier
) {
    val fraction =
        if (goal > 0) {
            (
                steps.toFloat() /
                    goal
                )
                .coerceIn(
                    0f,
                    1f
                )
        } else {
            0f
        }

    val track =
        MaterialTheme.colorScheme.surfaceVariant

    val fill =
        if (
            steps >= goal &&
            goal > 0
        ) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.primary
        }

    Box(
        modifier.size(124.dp),
        contentAlignment =
            Alignment.Center
    ) {

        Canvas(
            Modifier.fillMaxSize()
        ) {

            val stroke =
                12.dp.toPx()

            val inset =
                stroke / 2f

            val arcSize =
                Size(
                    size.width - stroke,
                    size.height - stroke
                )

            drawArc(
                track,
                0f,
                360f,
                false,
                Offset(
                    inset,
                    inset
                ),
                arcSize,
                style =
                    Stroke(
                        stroke,
                        cap =
                            StrokeCap.Round
                    )
            )

            if (fraction > 0f) {

                drawArc(
                    fill,
                    -90f,
                    360f * fraction,
                    false,
                    Offset(
                        inset,
                        inset
                    ),
                    arcSize,
                    style =
                        Stroke(
                            stroke,
                            cap =
                                StrokeCap.Round
                        )
                )
            }
        }

        Column(
            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Text(
                Fmt.int(steps),
                style =
                    MaterialTheme.typography.titleLarge
            )

            Text(
                "${(fraction * 100).roundToInt()} %",
                style =
                    MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun VitalsCard(
    today: DailySummary?,
    liveHr: HrSample?,
    restingHr: RestingHr?,
    recovery: Workout?,
    bloodPressure: BloodPressureReading?,
    measuring: Boolean,
    busy: Boolean,
    now: Long,
    connected: Boolean,
    onMeasureHr: () -> Unit,
    onSpo2: () -> Unit,
    showSpo2: Boolean,
    onBloodPressure: () -> Unit,
) {
    val live =
        liveHr?.takeIf {
            now - it.time <
                15_000L
        }

    ElevatedCard(
        Modifier.fillMaxWidth()
    ) {

        Column(
            Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(12.dp)
        ) {

            Text(
                "Vitals",
                style =
                    MaterialTheme.typography.titleMedium
            )

            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(24.dp)
            ) {

                val hr =
                    today?.lastHr

                StatText(
                    label =
                        if (live != null) {
                            "Heart rate (live)"
                        } else {
                            "Heart rate"
                        },
                    value =
                        when {
                            live != null ->
                                "${live.bpm} bpm"

                            hr != null ->
                                "${hr.bpm} bpm"

                            else ->
                                "–"
                        },
                )

                if (
                    live == null &&
                    hr != null
                ) {

                    StatText(
                        "at",
                        Fmt.time(hr.time)
                    )
                }

                val ranges =
                    today?.let { t ->
                        if (
                            t.minHr != null &&
                            t.maxHr != null
                        ) {
                            "${t.minHr}–${t.maxHr}"
                        } else {
                            null
                        }
                    }

                if (ranges != null) {

                    StatText(
                        "Min–max",
                        ranges
                    )
                }
            }

            if (showSpo2) Row(
                horizontalArrangement =
                    Arrangement.spacedBy(24.dp)
            ) {

                val spo2 =
                    today?.lastSpo2

                StatText(
                    "Blood oxygen",
                    spo2?.let {
                        "${it.percent} %"
                    } ?: "–"
                )

                if (spo2 != null) {

                    StatText(
                        "at",
                        Fmt.time(spo2.time)
                    )
                }
            }

            /*
             * First P32 BP implementation:
             *
             * Kept in memory only for now. Once C7 spot measurement is
             * confirmed on the real watch we can add repository/history
             * persistence and C8 FA history synchronization.
             */
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(24.dp)
            ) {

                StatText(
                    "Blood pressure",
                    bloodPressure?.let {
                        "${it.systolic}/${it.diastolic} mmHg"
                    } ?: "–"
                )

                if (bloodPressure != null) {

                    StatText(
                        "at",
                        Fmt.time(
                            bloodPressure.time
                        )
                    )
                }
            }

            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(24.dp)
            ) {

                StatText(
                    "Resting HR",
                    restingHr?.let {
                        "${it.bpm} bpm"
                    } ?: "–"
                )

                if (restingHr != null) {

                    StatText(
                        "fitness",
                        FitnessBand
                            .ofDaytime(
                                restingHr.bpm
                            )
                            .label
                    )

                    StatText(
                        "as at",
                        "${Fmt.shortDate(restingHr.computedAt)} ${
                            Fmt.time(
                                restingHr.computedAt
                            )
                        }"
                    )
                }
            }

            val sleepBpm =
                restingHr?.sleepBpm

            if (sleepBpm != null) {

                Row(
                    horizontalArrangement =
                        Arrangement.spacedBy(24.dp)
                ) {

                    StatText(
                        "Sleeping HR",
                        "$sleepBpm bpm"
                    )

                    StatText(
                        "fitness",
                        FitnessBand
                            .ofSleeping(
                                sleepBpm
                            )
                            .label
                    )
                }
            }

            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(24.dp)
            ) {

                val d1 =
                    recovery?.hrr1

                StatText(
                    "Recovery (HRR)",
                    d1?.let {
                        "${HeartRateRecovery.dropText(it)} at 1 min"
                    } ?: "–"
                )

                if (d1 != null) {

                    StatText(
                        HeartRateRecovery.band1min(
                            d1
                        ),
                        recovery?.hrr2?.let {
                            "${
                                HeartRateRecovery
                                    .dropText(it)
                                    .removeSuffix(" bpm")
                            } at 2 min"
                        } ?: ""
                    )
                }
            }

            if (recovery != null) {

                Text(
                    "From ${Fmt.shortDate(recovery.start)}'s ${
                        SportTypes.name(
                            recovery.sportType
                        )
                    }: peak ${recovery.hrrPeak ?: recovery.maxHr ?: "?"} bpm",
                    style =
                        MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            /*
             * Keep HR and BP separate so the buttons do not get cramped
             * on narrower phones.
             */
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {

                OutlinedButton(
                    onClick =
                        onMeasureHr,
                    enabled =
                        connected &&
                            !measuring &&
                            !busy
                ) {

                    Text(
                        if (measuring) {
                            "Measuring…"
                        } else {
                            "Measure HR"
                        }
                    )
                }

                OutlinedButton(
                    onClick =
                        onBloodPressure,
                    enabled =
                        connected &&
                            !measuring &&
                            !busy
                ) {
                    Text(
                        "Measure BP"
                    )
                }
            }

            if (showSpo2) Row(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {

                OutlinedButton(
                    onClick =
                        onSpo2,
                    enabled =
                        connected &&
                            !measuring &&
                            !busy
                ) {
                    Text(
                        "SpO2 test"
                    )
                }
            }

            if (!connected) {

                Text(
                    "Connect the watch to take live measurements.",
                    style =
                        MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WatchFaceCard(
    config: WatchFaceConfig?,
    upload: WatchFaceUploadProgress?,
    connected: Boolean,
    busy: Boolean,
    onRead: () -> Unit,
    onInstall: () -> Unit,
) {
    ElevatedCard(
        Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Watch face",
                style = MaterialTheme.typography.titleMedium
            )

            if (config == null) {
                Text(
                    "Read the P32's watch-face capabilities before installing a GloryFit .BIN face.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    StatText(
                        "Resolution",
                        "${config.width} × ${config.height}"
                    )
                    StatText(
                        "Max data",
                        "${config.maxDataSize} bytes"
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    StatText(
                        "Dial",
                        config.dialNumber.toString()
                    )
                    StatText(
                        "Screen type",
                        config.screenType.toString()
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    StatText(
                        "Compatibility",
                        config.compatibleLevel?.toString() ?: "—"
                    )
                    StatText(
                        "Corner angle",
                        config.cornerAngle?.toString() ?: "—"
                    )
                }
            }

            if (upload != null && upload.totalBytes > 0) {
                val pct =
                    ((upload.bytesSent * 100L) / upload.totalBytes)
                        .coerceIn(0L, 100L)

                Text(
                    "Installing… $pct% (${upload.bytesSent}/${upload.totalBytes} bytes)",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onRead,
                    enabled = connected && !busy
                ) {
                    Text("Read info")
                }

                Button(
                    onClick = onInstall,
                    enabled = connected && config != null && !busy
                ) {
                    Text("Install .BIN")
                }
            }

            Text(
                "Only GloryFit-compatible .BIN files are accepted. The app checks the .BIN header, payload length, CRC-32 and watch size limit before preparing the watch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!connected) {
                Text(
                    "Connect the watch before reading or installing a watch face.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * One line for last night ("Last night: 7 h 12 m", bed and rise times) over the stage strip; the hypnogram and
 * the per-stage totals live on the History tab. Nothing is shown until a night has been synced.
 */
@Composable
private fun SleepCard(
    stages: List<SleepStage>
) {
    val chart =
        remember(stages) {
            SleepChartData.build(stages)
        } ?: return

    ElevatedCard(
        Modifier.fillMaxWidth()
    ) {

        Column(
            Modifier.padding(16.dp),
            verticalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Text(
                    "Sleep",
                    style =
                        MaterialTheme.typography.titleMedium,
                    modifier =
                        Modifier.weight(1f)
                )

                Text(
                    "Bed ${Fmt.time(chart.start)} · Rise ${Fmt.time(chart.end)}",
                    style =
                        MaterialTheme.typography.labelMedium,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                "Last night: ${
                    SleepChartData.hoursMinutes(
                        chart.summary.totalMin
                    )
                }",
                style =
                    MaterialTheme.typography.bodyLarge
            )

            SleepStrip(chart)
        }
    }
}

/** One coloured block per stage across the night (same colours as the History hypnogram). */
@Composable
private fun SleepStrip(
    chart: SleepChart
) {
    val span =
        (
            chart.end -
                chart.start
            )
            .toDouble()
            .coerceAtLeast(1.0)

    val colors =
        chartColors()

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(20.dp)
    ) {

        for (b in chart.blocks) {

            val x0 =
                (
                    (b.start - chart.start) /
                        span *
                        size.width
                    )
                    .toFloat()
                    .coerceIn(
                        0f,
                        size.width
                    )

            val x1 =
                (
                    (b.end - chart.start) /
                        span *
                        size.width
                    )
                    .toFloat()
                    .coerceIn(
                        0f,
                        size.width
                    )

            drawRect(
                sleepStageColor(
                    b.stage,
                    colors
                ),
                Offset(
                    x0,
                    0f
                ),
                Size(
                    (x1 - x0)
                        .coerceAtLeast(1f),
                    size.height
                )
            )
        }
    }
}
