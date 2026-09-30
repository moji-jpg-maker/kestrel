package dev.narumi.kestrel.feature.map

import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.narumi.kestrel.R
import dev.narumi.kestrel.core.location.LatLng
import dev.narumi.kestrel.core.location.SCHEDULE_NOTIFICATIONS_DISABLED_MESSAGE
import dev.narumi.kestrel.core.location.parseCoordInput
import dev.narumi.kestrel.core.routeplan.MAX_UPDATE_INTERVAL_MS
import dev.narumi.kestrel.core.routeplan.MIN_UPDATE_INTERVAL_MS
import dev.narumi.kestrel.core.routeplan.PlanBuildResult
import dev.narumi.kestrel.core.routeplan.PlaybackPlan
import dev.narumi.kestrel.core.routeplan.ScheduleDraft
import dev.narumi.kestrel.core.routeplan.SpeedMode
import dev.narumi.kestrel.core.routeplan.buildPlaybackPlan
import dev.narumi.kestrel.core.routeplan.formatScheduleDistance
import dev.narumi.kestrel.core.routeplan.formatScheduleDuration
import dev.narumi.kestrel.core.routeplan.formatScheduleStart
import dev.narumi.kestrel.ui.components.KestrelCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** MIME types offered by the file picker. Providers often report octet-stream, so it is included. */
private val ROUTE_PICKER_TYPES =
    arrayOf(
        "application/gpx+xml",
        "application/geo+json",
        "application/json",
        "application/xml",
        "text/xml",
        "text/csv",
        "text/plain",
        "application/octet-stream",
    )

private const val DEFAULT_START_OFFSET_MS = 10 * 60_000L
private val INTERVAL_CHOICES_MS = listOf(200L, 500L, 1_000L, 2_000L, 5_000L)

/**
 * Compose a scheduled route: import a file, pick a start time, speed and update interval, then
 * confirm. Nothing starts until the user presses the button at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun SchedulePlanSheet(
    sheetState: SheetState,
    draft: ScheduleDraft,
    importing: Boolean,
    importError: String?,
    setupStep: MapSetupStep,
    notificationPermissionGranted: Boolean,
    onAllowPermissions: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onRefreshMockCheck: () -> Unit,
    replacesSummary: String?,
    operationPending: Boolean,
    serviceError: String?,
    onDraftChange: (ScheduleDraft) -> Unit,
    onPickFile: (Uri) -> Unit,
    onImportText: (String) -> Unit,
    onClearRoute: () -> Unit,
    onSchedule: (PlaybackPlan) -> Unit,
    onDismiss: () -> Unit,
) {
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onPickFile(uri)
        }
    var pastedText by remember { mutableStateOf("") }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    val ready = setupStep == MapSetupStep.Ready
    val scope = rememberCoroutineScope()
    // Building the timeline walks every route point, so it runs off the main thread. The previous
    // result stays on screen until the next one is ready; null only before the first build.
    val buildResult by produceState<PlanBuildResult?>(initialValue = null, draft) {
        value = withContext(Dispatchers.Default) { buildPlaybackPlan(draft, System.currentTimeMillis()) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
                    .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.schedule_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.schedule_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            RouteCard(
                draft = draft,
                importing = importing,
                importError = importError,
                pastedText = pastedText,
                onPastedTextChange = { pastedText = it },
                onChooseFile = { filePicker.launch(ROUTE_PICKER_TYPES) },
                onImportText = { onImportText(pastedText) },
                onClearRoute = onClearRoute,
            )

            if (draft.route != null) {
                StartCard(draft = draft, enabled = !operationPending, onDraftChange = onDraftChange)
                SourceCard(draft = draft, enabled = !operationPending, onDraftChange = onDraftChange)
                SpeedCard(draft = draft, enabled = !operationPending, onDraftChange = onDraftChange)
                IntervalCard(draft = draft, enabled = !operationPending, onDraftChange = onDraftChange)
                buildResult?.let { SummaryCard(result = it, draft = draft) }
            }

            replacesSummary?.let {
                Text(
                    stringResource(R.string.schedule_replaces_current, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!ready) {
                // Same prompt and actions as the map banner, so the fix is one tap away without leaving the form.
                SetupPromptCard(
                    setupStep = setupStep,
                    title = setupPromptTitle(setupStep).orEmpty(),
                    message = setupPromptMessage(setupStep).orEmpty(),
                    onAllowPermissions = onAllowPermissions,
                    onOpenDeveloperOptions = onOpenDeveloperOptions,
                    onRefreshMockCheck = onRefreshMockCheck,
                )
            }
            (scheduleError ?: serviceError)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (draft.route != null) {
                Button(
                    onClick = {
                        scheduleSetupError(setupStep, notificationPermissionGranted)?.let {
                            scheduleError = it
                            return@Button
                        }
                        // Rebuild with the current clock so a start time that slipped into the past is caught.
                        val latest = draft
                        scope.launch {
                            val fresh = withContext(Dispatchers.Default) { buildPlaybackPlan(latest, System.currentTimeMillis()) }
                            when (fresh) {
                                is PlanBuildResult.Ready -> {
                                    scheduleError = null
                                    onSchedule(fresh.plan)
                                }
                                is PlanBuildResult.Invalid -> scheduleError = fresh.message
                            }
                        }
                    },
                    enabled = buildResult is PlanBuildResult.Ready && !operationPending && !importing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            when {
                                draft.startAtEpochMs == null -> R.string.schedule_action_start_now
                                draft.joinInProgress -> R.string.schedule_action_join
                                else -> R.string.schedule_action_schedule
                            },
                        ),
                    )
                }
            }
        }
    }
}

internal fun scheduleSetupError(
    setupStep: MapSetupStep,
    notificationPermissionGranted: Boolean,
): String? =
    when {
        !notificationPermissionGranted -> SCHEDULE_NOTIFICATIONS_DISABLED_MESSAGE
        setupStep == MapSetupStep.Permissions -> "Allow location permission before scheduling this route."
        setupStep == MapSetupStep.MockLocationApp ->
            "Kestrel is not selected as the mock location app. Choose it under Developer options, then try again."
        else -> null
    }

@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList")
@Composable
private fun RouteCard(
    draft: ScheduleDraft,
    importing: Boolean,
    importError: String?,
    pastedText: String,
    onPastedTextChange: (String) -> Unit,
    onChooseFile: () -> Unit,
    onImportText: () -> Unit,
    onClearRoute: () -> Unit,
) {
    val route = draft.route
    KestrelCard {
        SectionLabel("Route")
        if (route == null) {
            Text(
                stringResource(R.string.schedule_route_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(route.name ?: stringResource(R.string.schedule_route_default_name), style = MaterialTheme.typography.titleMedium)
            Text(
                pluralStringResource(R.plurals.schedule_route_points, route.points.size, route.points.size) +
                    if (route.hasTimestamps) stringResource(R.string.schedule_route_has_timestamps) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            route.warnings.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onChooseFile, enabled = !importing) { Text(stringResource(R.string.schedule_choose_file)) }
            if (route != null) TextButton(onClick = onClearRoute) { Text(stringResource(R.string.schedule_remove_route)) }
        }
        OutlinedTextField(
            value = pastedText,
            onValueChange = onPastedTextChange,
            label = { Text(stringResource(R.string.schedule_paste_label)) },
            minLines = 2,
            maxLines = 5,
            enabled = !importing,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onImportText, enabled = pastedText.isNotBlank() && !importing) {
            Text(stringResource(if (importing) R.string.schedule_importing else R.string.schedule_import_pasted))
        }
        importError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun StartCard(
    draft: ScheduleDraft,
    enabled: Boolean,
    onDraftChange: (ScheduleDraft) -> Unit,
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val startAt = draft.startAtEpochMs
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    KestrelCard {
        SectionLabel(stringResource(R.string.schedule_section_start))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChipChoice(stringResource(R.string.schedule_start_now), startAt == null, enabled) { onDraftChange(draft.withStart(null)) }
            ChipChoice(stringResource(R.string.schedule_pick_time), startAt != null, enabled) {
                if (startAt == null) onDraftChange(draft.withStart(System.currentTimeMillis() + DEFAULT_START_OFFSET_MS))
            }
        }
        if (startAt != null) {
            val local = Instant.ofEpochMilli(startAt).atZone(zone)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDate = true }, enabled = enabled) {
                    Text(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(local))
                }
                OutlinedButton(onClick = { showTime = true }, enabled = enabled) {
                    Text(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(local))
                }
            }
            if (draft.startIsPast(System.currentTimeMillis())) {
                PastStartChoice(draft = draft, enabled = enabled, onDraftChange = onDraftChange)
            }
            if (showDate) {
                StartDateDialog(local, zone, onDismiss = { showDate = false }) { onDraftChange(draft.withStart(it)) }
            }
            if (showTime) {
                StartTimeDialog(local, DateFormat.is24HourFormat(context), onDismiss = { showTime = false }) {
                    onDraftChange(draft.withStart(it))
                }
            }
        }
    }
}

/**
 * A start time that already passed needs an explicit answer: start from the beginning now, or
 * join the route where it would be by now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PastStartChoice(
    draft: ScheduleDraft,
    enabled: Boolean,
    onDraftChange: (ScheduleDraft) -> Unit,
) {
    if (draft.joinInProgress) {
        Text(
            stringResource(R.string.schedule_join_active),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            stringResource(R.string.schedule_past_start),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { onDraftChange(draft.withStart(null)) }, enabled = enabled) {
                Text(stringResource(R.string.schedule_start_now))
            }
            OutlinedButton(onClick = { onDraftChange(draft.withJoinInProgress()) }, enabled = enabled) {
                Text(stringResource(R.string.schedule_join_current))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartDateDialog(
    local: ZonedDateTime,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
) {
    val dateState =
        rememberDatePickerState(
            initialSelectedDateMillis =
                local
                    .toLocalDate()
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli(),
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    dateState.selectedDateMillis?.let { utcMillis ->
                        val day = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
                        onSelect(ZonedDateTime.of(day, local.toLocalTime(), zone).toInstant().toEpochMilli())
                    }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.schedule_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.schedule_cancel)) } },
    ) { DatePicker(state = dateState) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartTimeDialog(
    local: ZonedDateTime,
    is24Hour: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
) {
    val timeState = rememberTimePickerState(initialHour = local.hour, initialMinute = local.minute, is24Hour = is24Hour)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val next =
                        local
                            .withHour(timeState.hour)
                            .withMinute(timeState.minute)
                            .withSecond(0)
                            .withNano(0)
                    onSelect(next.toInstant().toEpochMilli())
                    onDismiss()
                },
            ) { Text(stringResource(R.string.schedule_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.schedule_cancel)) } },
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) { TimePicker(state = timeState) } },
    )
}

@Composable
private fun SourceCard(
    draft: ScheduleDraft,
    enabled: Boolean,
    onDraftChange: (ScheduleDraft) -> Unit,
) {
    // Re-seeded when a different route is imported, because its hints may carry a source.
    key(draft.route) {
        var text by remember { mutableStateOf(draft.source?.let { formatLatLng(it) }.orEmpty()) }
        val parsed = parseCoordInput(text)
        KestrelCard {
            SectionLabel(stringResource(R.string.schedule_section_source))
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    if (it.isBlank()) {
                        onDraftChange(draft.withSource(null))
                    } else {
                        parseCoordInput(it)?.let { point -> onDraftChange(draft.withSource(point)) }
                    }
                },
                label = { Text(stringResource(R.string.schedule_source_label)) },
                singleLine = true,
                isError = text.isNotBlank() && parsed == null,
                supportingText = {
                    Text(stringResource(R.string.schedule_source_hint))
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedCard(
    draft: ScheduleDraft,
    enabled: Boolean,
    onDraftChange: (ScheduleDraft) -> Unit,
) {
    KestrelCard {
        SectionLabel(stringResource(R.string.schedule_section_speed))
        if (draft.hasTimestamps) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChipChoice(stringResource(R.string.schedule_speed_constant), draft.speedMode == SpeedMode.Constant, enabled) {
                    onDraftChange(draft.withSpeedMode(SpeedMode.Constant))
                }
                ChipChoice(stringResource(R.string.schedule_speed_timestamps), draft.speedMode == SpeedMode.FromTimestamps, enabled) {
                    onDraftChange(draft.withSpeedMode(SpeedMode.FromTimestamps))
                }
                ChipChoice(stringResource(R.string.schedule_speed_scaled), draft.speedMode == SpeedMode.TimestampsScaled, enabled) {
                    onDraftChange(draft.withSpeedMode(SpeedMode.TimestampsScaled))
                }
            }
        }
        key(draft.route, draft.speedMode) {
            when (draft.speedMode) {
                SpeedMode.Constant ->
                    PositiveNumberField(stringResource(R.string.schedule_speed_kmh), draft.speedKmh, enabled) { onDraftChange(draft.withSpeedKmh(it)) }
                SpeedMode.FromTimestamps ->
                    Text(
                        stringResource(R.string.schedule_speed_timestamps_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                SpeedMode.TimestampsScaled ->
                    PositiveNumberField(stringResource(R.string.schedule_speed_factor), draft.timestampFactor, enabled) {
                        onDraftChange(draft.withTimestampFactor(it))
                    }
            }
            if (draft.speedMode != SpeedMode.Constant && draft.source != null) {
                PositiveNumberField(stringResource(R.string.schedule_speed_lead_in), draft.leadInSpeedKmh, enabled) {
                    onDraftChange(draft.withLeadInSpeedKmh(it))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntervalCard(
    draft: ScheduleDraft,
    enabled: Boolean,
    onDraftChange: (ScheduleDraft) -> Unit,
) {
    val choices = (INTERVAL_CHOICES_MS + draft.updateIntervalMs).distinct().sorted()
    KestrelCard {
        SectionLabel(stringResource(R.string.schedule_section_interval))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { ms ->
                ChipChoice(formatInterval(ms), ms == draft.updateIntervalMs, enabled) {
                    onDraftChange(draft.withUpdateIntervalMs(ms))
                }
            }
        }
        if (draft.updateIntervalMs !in MIN_UPDATE_INTERVAL_MS..MAX_UPDATE_INTERVAL_MS) {
            Text(
                stringResource(R.string.schedule_interval_range, MIN_UPDATE_INTERVAL_MS, MAX_UPDATE_INTERVAL_MS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SummaryCard(
    result: PlanBuildResult,
    draft: ScheduleDraft,
) {
    KestrelCard {
        SectionLabel(stringResource(R.string.schedule_section_summary))
        when (result) {
            is PlanBuildResult.Invalid ->
                Text(result.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            is PlanBuildResult.Ready -> {
                val plan = result.plan
                val timeline = result.timeline
                val now = System.currentTimeMillis()
                val start =
                    when {
                        draft.startAtEpochMs == null -> stringResource(R.string.schedule_summary_starts_now)
                        draft.joinInProgress ->
                            stringResource(R.string.schedule_summary_joins, formatScheduleStart(plan.startAtEpochMs, now))
                        else -> stringResource(R.string.schedule_summary_starts, formatScheduleStart(plan.startAtEpochMs, now))
                    }
                Text(start, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(
                        R.string.schedule_summary_line,
                        formatScheduleDistance(timeline.totalMeters),
                        formatScheduleDuration(timeline.durationSeconds),
                        formatAverage(timeline.averageSpeedKmh),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                result.largeStepMeters?.let { meters ->
                    Text(
                        stringResource(R.string.schedule_step_warning, meters.roundToInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PositiveNumberField(
    label: String,
    value: Double,
    enabled: Boolean,
    onValidValue: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(formatNumber(value)) }
    val valid = parsePositive(text) != null
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            parsePositive(it)?.let(onValidValue)
        },
        label = { Text(label) },
        singleLine = true,
        isError = !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun parsePositive(text: String): Double? =
    text
        .trim()
        .replace(',', '.')
        .toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0.0 }

private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

private fun formatInterval(ms: Long): String = if (ms % 1_000L == 0L) "${ms / 1_000L} s" else "$ms ms"

private fun formatAverage(kmh: Double): String = "%.1f km/h".format(Locale.US, kmh)

private fun formatLatLng(point: LatLng): String = "%.6f, %.6f".format(Locale.US, point.lat, point.lng)
