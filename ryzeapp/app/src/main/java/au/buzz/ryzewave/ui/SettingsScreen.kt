@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package au.buzz.ryzewave.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import au.buzz.ryzewave.notify.WatchNotificationListener
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import au.buzz.ryzewave.core.SamplingSettings
import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.WatchStatus
import au.buzz.ryzewave.core.Workout
import au.buzz.ryzewave.health.ExportResult
import au.buzz.ryzewave.workout.DefaultStrideModel
import java.util.Locale

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val mac by vm.mac.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val sampling by vm.sampling.collectAsStateWithLifecycle()
    val stride by vm.stride.collectAsStateWithLifecycle()
    val hcEnabled by vm.healthConnectEnabled.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val scanResults by vm.scanResults.collectAsStateWithLifecycle()
    val calibrationWorkout by vm.calibrationWorkout.collectAsStateWithLifecycle()
    val lastExport by vm.lastExport.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val notificationsEnabled by vm.notificationsEnabled.collectAsStateWithLifecycle()
    val allowedPackages by vm.allowedPackages.collectAsStateWithLifecycle()
    val forwardAll by vm.forwardAllNotifications.collectAsStateWithLifecycle()
    val stuckEnabled by vm.stuckDetectorEnabled.collectAsStateWithLifecycle()
    val stuckAutoStopApp by vm.stuckAutoStopAppWorkouts.collectAsStateWithLifecycle()
    val breadcrumbOn by vm.breadcrumbEnabled.collectAsStateWithLifecycle()
    val installedApps by vm.installedApps.collectAsStateWithLifecycle()
    val notificationAccess by vm.notificationAccess.collectAsStateWithLifecycle()
    val health = LocalHealthPermissionHost.current
    val sdkStatus by health.sdkStatus.collectAsStateWithLifecycle()
    val granted by health.granted.collectAsStateWithLifecycle()
    val optionalMissing by health.optionalMissing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageSnackbar(message, vm::clearMessage, snackbar)
    LaunchedEffect(Unit) {
        health.refresh()
        vm.refreshNotificationAccess()
        vm.loadInstalledApps()
    }
    // Notification access is granted in the system settings; re-check when the user comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refreshNotificationAccess() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WatchSection(
                mac = mac, status = status, scanning = scanning, results = scanResults, busy = busy,
                onSave = vm::saveMac, onScan = vm::startScan, onStopScan = vm::stopScan,
                onChoose = vm::chooseDevice, onFind = vm::findWatch,
            )
            ProfileSection(profile, onSave = vm::saveProfile)
            val watchStatus by au.buzz.ryzewave.App.graph.watch.status.collectAsStateWithLifecycle()
            SamplingSection(sampling, onChange = vm::saveSampling, showSpo2 = !watchStatus.firmware.orEmpty().startsWith("RB112UDG", ignoreCase = true))
            StrideSection(
                profile = profile, stride = stride, calibrationWorkout = calibrationWorkout,
                onSave = vm::saveStride, onReset = vm::resetStride, onCalibrate = vm::calibrateFromLastWorkout,
            )
            HealthSection(
                enabled = hcEnabled, sdkStatus = sdkStatus, granted = granted, routeMissing = optionalMissing.isNotEmpty(),
                busy = busy || exporting, lastExport = lastExport,
                onToggle = { on ->
                    vm.setHealthConnectEnabled(on)
                    if (on && !granted) health.request()     // arming the export without permissions is pointless
                },
                onGrant = health::request, onExport = vm::exportNow,
            )
            NotificationsSection(
                enabled = notificationsEnabled, accessGranted = notificationAccess, forwardAll = forwardAll,
                apps = installedApps, allowed = allowedPackages, connected = status.isConnected(), busy = busy,
                onToggle = vm::setNotificationsEnabled, onForwardAll = vm::setForwardAllNotifications,
                onAppToggle = vm::setPackageAllowed, onTest = vm::sendTestNotification,
            )
            StuckDetectorSection(
                enabled = stuckEnabled, autoStopApp = stuckAutoStopApp,
                onToggle = vm::setStuckDetectorEnabled, onAutoStopApp = vm::setStuckAutoStopAppWorkouts,
            )
            BreadcrumbSection(enabled = breadcrumbOn, onToggle = vm::setBreadcrumbEnabled)
        }
    }
}

// ---- stuck-workout detector ---------------------------------------------------------------------------------

@Composable
private fun BreadcrumbSection(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    SectionCard("GPS breadcrumb (plan B)") {
        SwitchRow("Record where I go while I move", enabled, onChange = onToggle)
        Text(
            "Off by default. When on, the phone keeps a GPS trail only while it detects walking, running or cycling " +
                "(nothing while still or in a vehicle; about one fix a minute, faster when running). If a workout's " +
                "own tracking fails, open it and tap \"Rebuild from breadcrumb\". Independent of Google Fit. " +
                "For it to survive a reboot, set the app's location permission to \"Allow all the time\". " +
                "Trail kept 14 days, on this phone only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", context.packageName, null)),
            )
        }) { Text("Open location permission") }
    }
}

@Composable
private fun StuckDetectorSection(
    enabled: Boolean,
    autoStopApp: Boolean,
    onToggle: (Boolean) -> Unit,
    onAutoStopApp: (Boolean) -> Unit,
) {
    SectionCard("Stuck-workout detector") {
        SwitchRow("Detect a workout with no activity", enabled, onChange = onToggle)
        Text(
            "Warns (spoken + notification with Stop) when a running workout shows none of the activity its sport " +
                "should — steps, GPS movement, raised heart rate or phone motion — for 4 minutes. A workout the " +
                "watch started by itself is stopped after a grace period (2 minutes at night with your heart rate at " +
                "sleeping level, otherwise 10).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow("Also auto-stop app-started workouts", autoStopApp, enabled = enabled, onChange = onAutoStopApp)
        Text(
            "Off: a workout you started in the app only gets the warning and keeps running until you stop it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, suffix: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { s -> onChange(s.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

// ---- edit guard -------------------------------------------------------------------------------------------

/**
 * "Local edits win until saved": keeps a form's fields from being clobbered by the settings flow they were seeded
 * from.
 *
 * The obvious `remember(flowValue) { mutableStateOf(flowValue) }` re-creates the field state every time the flow
 * re-emits, and these flows re-emit for reasons unrelated to what the user is typing (another setting saved, the
 * service re-applying settings on connect, a scan result chosen, DataStore rewriting its file), so a half-typed
 * number silently reverts to the stored value — the profile bug of build 1 (weight typed 114, saved 50). The
 * Profile section got an `edited` flag in build 2; this is that flag, shared by every section with text fields.
 *
 * Rules: the fields are seeded with `remember { mutableStateOf(stored…) }`; while [edited] is false, the stored
 * value is copied into them whenever it changes ([rememberEditGuard] runs `seed` from a `LaunchedEffect(stored)`);
 * once the user has typed ([touch]) the fields are left alone, whatever the flow does. Call [saved] when the user
 * saves — the fields already hold what was saved, and the flow's echo re-seeds them with the same values (or with
 * the normalised ones, e.g. an upper-cased MAC). Call [discard] when something *other* than typing replaces the
 * stored value (a scan result tapped, "Use defaults", calibration): the edits are thrown away and the stored value
 * is shown at once, even when the flow does not re-emit because the value did not actually change.
 */
@Stable
class EditGuard internal constructor(private val seed: () -> Unit) {
    var edited by mutableStateOf(false)
        private set

    /** The user changed a field: keep the local values until [saved] or [discard]. */
    fun touch() { edited = true }

    /** The local values were handed to the store; from now on the stored value may re-seed the fields again. */
    fun saved() { edited = false }

    /** Drop the local edits and show the stored value now. */
    fun discard() { edited = false; seed() }
}

/**
 * See [EditGuard]. [stored] is the flow value the fields mirror; [seed] copies it into the field states and is
 * re-run whenever [stored] changes while nothing is being edited.
 */
@Composable
fun rememberEditGuard(stored: Any?, seed: () -> Unit): EditGuard {
    val latestSeed = rememberUpdatedState(seed)
    val guard = remember { EditGuard { latestSeed.value() } }
    LaunchedEffect(stored) { if (!guard.edited) latestSeed.value() }
    return guard
}

// ---- watch ------------------------------------------------------------------------------------------------

@Composable
private fun WatchSection(
    mac: String,
    status: WatchStatus,
    scanning: Boolean,
    results: List<ScannedDevice>,
    busy: Boolean,
    onSave: (String) -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onChoose: (ScannedDevice) -> Unit,
    onFind: () -> Unit,
) {
    var text by remember { mutableStateOf(mac) }
    val guard = rememberEditGuard(mac) { text = mac }
    // The field keeps exactly what was typed: rewriting the value inside onValueChange (the old
    // `it.uppercase()`) under Gboard's composition dropped keystrokes ("abcdef" -> "ACDEF"). The keyboard is asked
    // for capitals and the value is normalised (trimmed, upper-cased) when it is compared and saved.
    val normalised = MacText.normalise(text)
    SectionCard("Watch") {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; guard.touch() },
            label = { Text("MAC address") },
            supportingText = { Text("Default ${UiDefaults.WATCH_MAC}") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        // FlowRow: three buttons do not fit one row on a 360 dp phone and a Row squashed "Find watch" onto two lines
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(normalised); guard.saved() }, enabled = normalised != mac) { Text("Save") }
            OutlinedButton(onClick = if (scanning) onStopScan else onScan) { Text(if (scanning) "Stop scan" else "Scan") }
            OutlinedButton(onClick = onFind, enabled = status.isConnected() && !busy) { Text("Find watch") }
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        results.forEach { d ->
            ListItem(
                headlineContent = { Text(d.name) },
                supportingContent = { Text("${d.mac} · ${d.rssi} dBm · tap to use") },
                modifier = Modifier.clickable { guard.discard(); onChoose(d) },
            )
        }
        if (scanning && results.isEmpty()) {
            Text("Looking for watches named \"${UiDefaults.WATCH_NAME_PREFIX}…\"", style = MaterialTheme.typography.bodySmall)
        }
        Text("Status: ${connectionLabel(status)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- profile -----------------------------------------------------------------------------------------------

@Composable
private fun ProfileSection(profile: UserProfile, onSave: (UserProfile) -> Unit) {
    // Local edit state; the stored profile only re-seeds it while nothing is being edited (see EditGuard).
    var height by remember { mutableStateOf(profile.heightCm.toString()) }
    var weight by remember { mutableStateOf(profile.weightKg.toString()) }
    var age by remember { mutableStateOf(profile.age.toString()) }
    var goal by remember { mutableStateOf(profile.stepGoal.toString()) }
    var male by remember { mutableStateOf(profile.male) }
    val guard = rememberEditGuard(profile) {
        height = profile.heightCm.toString()
        weight = profile.weightKg.toString()
        age = profile.age.toString()
        goal = profile.stepGoal.toString()
        male = profile.male
    }
    val parsed = UserProfile(
        heightCm = height.toIntOrNull() ?: 0,
        weightKg = weight.toIntOrNull() ?: 0,
        age = age.toIntOrNull() ?: 0,
        male = male,
        stepGoal = goal.toIntOrNull() ?: 0,
    )
    val valid = parsed.heightCm in 100..250 && parsed.weightKg in 20..300 && parsed.age in 5..120 && parsed.stepGoal in 500..100_000
    SectionCard("Profile") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Height", height, { height = it; guard.touch() }, Modifier.weight(1f), "cm")
            NumberField("Weight", weight, { weight = it; guard.touch() }, Modifier.weight(1f), "kg")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Age", age, { age = it; guard.touch() }, Modifier.weight(1f))
            NumberField("Step goal", goal, { goal = it; guard.touch() }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = male, onClick = { male = true; guard.touch() }, label = { Text("Male") })
            FilterChip(selected = !male, onClick = { male = false; guard.touch() }, label = { Text("Female") })
        }
        Button(onClick = { onSave(parsed); guard.saved() }, enabled = valid && parsed != profile) { Text("Save profile") }
    }
}

// ---- sampling ---------------------------------------------------------------------------------------------

@Composable
private fun SamplingSection(sampling: SamplingSettings, onChange: (SamplingSettings) -> Unit, showSpo2: Boolean) {
    SectionCard("Sampling") {
        SwitchRow("Continuous heart rate (10-minute bins)", sampling.continuousHr) { onChange(sampling.copy(continuousHr = it)) }
        if (showSpo2) {
        SwitchRow("Automatic SpO2", sampling.spo2AutoEnabled) { onChange(sampling.copy(spo2AutoEnabled = it)) }
        Text("SpO2 interval", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UiDefaults.SPO2_INTERVALS.forEach { min ->
                FilterChip(
                    selected = sampling.spo2IntervalMin == min,
                    onClick = { onChange(sampling.copy(spo2IntervalMin = min)) },
                    enabled = sampling.spo2AutoEnabled,
                    label = { Text("$min min") },
                )
            }
        }
        }
        SwitchRow("Raise wrist to wake", sampling.raiseWristWake) { onChange(sampling.copy(raiseWristWake = it)) }

        Text("Heart rate alerts", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow("High heart rate alert", sampling.hrHighAlarmBpm > 0) {
            onChange(sampling.copy(hrHighAlarmBpm = if (it) 160 else 0))
        }
        if (sampling.hrHighAlarmBpm > 0) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                UiDefaults.HR_HIGH_ALARMS.forEach { bpm ->
                    FilterChip(
                        selected = sampling.hrHighAlarmBpm == bpm,
                        onClick = { onChange(sampling.copy(hrHighAlarmBpm = bpm)) },
                        label = { Text("$bpm") },
                    )
                }
                Text(
                    "bpm", Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SwitchRow("Low heart rate alert", sampling.hrLowAlarmBpm > 0) {
            onChange(sampling.copy(hrLowAlarmBpm = if (it) 45 else 0))
        }
        if (sampling.hrLowAlarmBpm > 0) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                UiDefaults.HR_LOW_ALARMS.forEach { bpm ->
                    FilterChip(
                        selected = sampling.hrLowAlarmBpm == bpm,
                        onClick = { onChange(sampling.copy(hrLowAlarmBpm = bpm)) },
                        label = { Text("$bpm") },
                    )
                }
                Text(
                    "bpm", Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "Both off by default. A low alert fires when your heart rate drops below the value, which happens " +
                "naturally in deep sleep, so keep it well under your sleeping rate or leave it off.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Changes are sent to the watch immediately when connected, otherwise on the next connect.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- stride -----------------------------------------------------------------------------------------------

@Composable
private fun StrideSection(
    profile: UserProfile,
    stride: StrideSettings,
    calibrationWorkout: Workout?,
    onSave: (StrideSettings) -> Unit,
    onReset: () -> Unit,
    onCalibrate: () -> Unit,
) {
    var walk by remember { mutableStateOf(stride.walkStrideM?.let { fmt3(it) } ?: "") }
    var run by remember { mutableStateOf(stride.runStrideM?.let { fmt3(it) } ?: "") }
    val guard = rememberEditGuard(stride) {
        walk = stride.walkStrideM?.let { fmt3(it) } ?: ""
        run = stride.runStrideM?.let { fmt3(it) } ?: ""
    }
    val walkVal = walk.toDoubleOrNull()
    val runVal = run.toDoubleOrNull()
    val walkOk = walk.isBlank() || (walkVal != null && walkVal in DefaultStrideModel.MIN_STRIDE_M..DefaultStrideModel.MAX_STRIDE_M)
    val runOk = run.isBlank() || (runVal != null && runVal in DefaultStrideModel.MIN_STRIDE_M..DefaultStrideModel.MAX_STRIDE_M)
    val parsed = StrideSettings(walkStrideM = walkVal?.takeIf { walk.isNotBlank() }, runStrideM = runVal?.takeIf { run.isNotBlank() })
    SectionCard("Stride (distance model)") {
        Text(
            "Defaults from your height: walk ${fmt3(DefaultStrideModel.defaultWalkStrideM(profile))} m, run ${fmt3(DefaultStrideModel.defaultRunStrideM(profile))} m per step. " +
                "In use: walk ${fmt3(strideModel.walkStrideM(profile, stride))} m, run ${fmt3(strideModel.runStrideM(profile, stride))} m.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Walk stride", walk, { walk = it; guard.touch() }, Modifier.weight(1f), "m")
            NumberField("Run stride", run, { run = it; guard.touch() }, Modifier.weight(1f), "m")
        }
        if (!walkOk || !runOk) Text("Stride must be between ${DefaultStrideModel.MIN_STRIDE_M} and ${DefaultStrideModel.MAX_STRIDE_M} m", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(parsed); guard.saved() }, enabled = walkOk && runOk && parsed != stride) { Text("Save") }
            OutlinedButton(onClick = { guard.discard(); onReset() }, enabled = stride != StrideSettings()) { Text("Use defaults") }
        }
        OutlinedButton(onClick = { guard.discard(); onCalibrate() }, enabled = calibrationWorkout != null) { Text("Calibrate from last GPS workout") }
        Text(
            calibrationWorkout?.let { "Uses ${Fmt.dateTime(it.start)}: ${Fmt.metres(it.distanceMeters)} in ${Fmt.duration(it.durationSeconds)}, divided by the steps the watch counted in that window." }
                ?: "Record a GPS workout of at least 200 m first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun fmt3(v: Double): String = String.format(Locale.US, "%.3f", v)

private val strideModel = DefaultStrideModel()

// ---- Health Connect ---------------------------------------------------------------------------------------

@Composable
private fun HealthSection(
    enabled: Boolean,
    sdkStatus: Int,
    granted: Boolean,
    /** The optional exercise-route permission was declined: exports run, sessions carry no GPS track. */
    routeMissing: Boolean,
    busy: Boolean,
    lastExport: ExportResult?,
    onToggle: (Boolean) -> Unit,
    onGrant: () -> Unit,
    onExport: () -> Unit,
) {
    val available = sdkStatus == HealthConnectClient.SDK_AVAILABLE
    SectionCard("Health Connect") {
        Text(healthSdkLabel(sdkStatus), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow("Export to Health Connect", enabled, enabled = available, onChange = onToggle)
        // `granted` is the exporter's own test (the required write permissions), so this status, the Export now
        // button and the background exports agree; the route permission is optional and only gets a hint.
        Text(
            when {
                granted && routeMissing -> "Permissions granted (GPS routes not allowed: workouts are exported without their track)"
                granted -> "Permissions granted"
                enabled -> "Permissions not granted yet — nothing is exported until you grant them"
                else -> "Permissions not granted yet"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled && !granted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        OutlinedButton(onClick = onGrant, enabled = available && (!granted || routeMissing), modifier = Modifier.fillMaxWidth()) {
            Text(if (granted && routeMissing) "Grant route permission" else "Grant permissions")
        }
        Button(onClick = onExport, enabled = available && granted && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Exporting…" else "Export now") }
        if (lastExport != null) {
            Text(
                exportSummary(lastExport),
                style = MaterialTheme.typography.bodySmall,
                color = if (lastExport.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** "Last export 18:05: 42 records" / "Last export 18:05 failed: …". */
private fun exportSummary(r: ExportResult): String {
    val at = Fmt.time(r.time)
    return when (r.status) {
        ExportResult.Status.OK -> "Last export $at: ${r.inserted} records" + (if (r.failed > 0) ", ${r.failed} rejected" else "")
        ExportResult.Status.NOTHING_TO_EXPORT -> "Last export $at: nothing new" + (if (r.skipped > 0) " (${r.skipped} records unchanged)" else "")
        else -> "Last export $at failed: ${r.message ?: r.status.name}"
    }
}

// ---- notifications to the watch -----------------------------------------------------------------------------

@Composable
private fun NotificationsSection(
    enabled: Boolean,
    accessGranted: Boolean,
    forwardAll: Boolean,
    apps: List<InstalledApp>?,
    allowed: Set<String>,
    connected: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onForwardAll: (Boolean) -> Unit,
    onAppToggle: (String, Boolean) -> Unit,
    onTest: () -> Unit,
) {
    val context = LocalContext.current
    SectionCard("Notifications") {
        SwitchRow("Forward notifications to the watch", enabled, onChange = onToggle)
        Text(
            when {
                accessGranted && WatchNotificationListener.connected -> "Notification access granted; listener running"
                accessGranted -> "Notification access granted; listener not connected yet (it starts on its own)"
                else -> "Notification access not granted: nothing is forwarded until you allow it"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled && !accessGranted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    Log.w("RyzeUi", "no notification access settings screen", e)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open notification access") }
        Button(onClick = onTest, enabled = connected && !busy, modifier = Modifier.fillMaxWidth()) { Text("Send test notification") }
        Text(
            "Calls are not forwarded here (the watch takes them over classic Bluetooth). Emoji are stripped; text is cut at 127 characters.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow("Forward all apps (debug)", forwardAll, enabled = enabled, onChange = onForwardAll)
        Text("Apps", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            apps == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            apps.isEmpty() -> Text("No launcher apps found", style = MaterialTheme.typography.bodySmall)
            else -> apps.forEach { app ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = app.packageName in allowed,
                        onCheckedChange = { onAppToggle(app.packageName, it) },
                        enabled = enabled && !forwardAll,
                    )
                }
            }
        }
    }
}
