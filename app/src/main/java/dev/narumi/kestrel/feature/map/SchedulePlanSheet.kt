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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.narumi.kestrel.core.location.LatLng
import dev.narumi.kestrel.core.location.parseCoordInput
import dev.narumi.kestrel.core.routeplan.MAX_UPDATE_INTERVAL_MS
import dev.narumi.kestrel.core.routeplan.MIN_UPDATE_INTERVAL_MS
import dev.narumi.kestrel.core.routeplan.PlanBuildResult
import dev.narumi.kestrel.core.routeplan.PlaybackPlan
import dev.narumi.kestrel.core.routeplan.RouteTimeline
import dev.narumi.kestrel.core.routeplan.ScheduleDraft
import dev.narumi.kestrel.core.routeplan.SpeedMode
import dev.narumi.kestrel.core.routeplan.buildPlaybackPlan
import dev.narumi.kestrel.core.routeplan.formatScheduleDistance
import dev.narumi.kestrel.core.routeplan.formatScheduleDuration
import dev.narumi.kestrel.core.routeplan.formatScheduleStart
import dev.narumi.kestrel.ui.components.KestrelCard
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
    ready: Boolean,
    replacesSummary: String?,
    operationPending: Boolean,
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
    val buildResult = remember(draft) { buildPlaybackPlan(draft, System.currentTimeMillis()) }

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
            Text("Schedule a route", style = MaterialTheme.typography.titleLarge)
            Text(
                "Kestrel plays the route back at the start time. Nothing starts until you confirm below.",
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
                SummaryCard(result = buildResult, draft = draft)
            }

            replacesSummary?.let {
                Text(
                    "Scheduling replaces the current mock: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!ready) {
                Text(
                    "Grant location and notification permission and select Kestrel as the mock location app on the map screen before scheduling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            scheduleError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (draft.route != null) {
                Button(
                    onClick = {
                        // Rebuild with the current clock so a start time that slipped into the past is caught.
                        when (val fresh = buildPlaybackPlan(draft, System.currentTimeMillis())) {
                            is PlanBuildResult.Ready -> {
                                scheduleError = null
                                onSchedule(fresh.plan)
                            }
                            is PlanBuildResult.Invalid -> scheduleError = fresh.message
                        }
                    },
                    enabled = ready && buildResult is PlanBuildResult.Ready && !operationPending && !importing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (draft.startAtEpochMs == null) "Start now" else "Schedule")
                }
            }
        }
    }
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
                "Import a GPX, GeoJSON, CSV or JSON route, or paste coordinates.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(route.name ?: "Imported route", style = MaterialTheme.typography.titleMedium)
            Text(
                "${route.points.size} points" + if (route.hasTimestamps) " · has timestamps" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            route.warnings.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onChooseFile, enabled = !importing) { Text("Choose file…") }
            if (route != null) TextButton(onClick = onClearRoute) { Text("Remove route") }
        }
        OutlinedTextField(
            value = pastedText,
            onValueChange = onPastedTextChange,
            label = { Text("Or paste route text") },
            minLines = 2,
            maxLines = 5,
            enabled = !importing,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onImportText, enabled = pastedText.isNotBlank() && !importing) {
            Text(if (importing) "Importing…" else "Import pasted text")
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
        SectionLabel("Start")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChipChoice("Start now", startAt == null, enabled) { onDraftChange(draft.withStart(null)) }
            ChipChoice("Pick a time", startAt != null, enabled) {
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
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
            SectionLabel("Source (optional)")
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
                label = { Text("Latitude, longitude") },
                singleLine = true,
                isError = text.isNotBlank() && parsed == null,
                supportingText = {
                    Text("Where the device sits before the start. Empty means the route's first point.")
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
        SectionLabel("Speed")
        if (draft.hasTimestamps) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChipChoice("Constant", draft.speedMode == SpeedMode.Constant, enabled) {
                    onDraftChange(draft.withSpeedMode(SpeedMode.Constant))
                }
                ChipChoice("File timestamps", draft.speedMode == SpeedMode.FromTimestamps, enabled) {
                    onDraftChange(draft.withSpeedMode(SpeedMode.FromTimestamps))
                }
                ChipChoice("Timestamps × factor", draft.speedMode == SpeedMode.TimestampsScaled, enabled) {
                    onDraftChange(draft.withSpeedMode(SpeedMode.TimestampsScaled))
                }
            }
        }
        key(draft.route, draft.speedMode) {
            when (draft.speedMode) {
                SpeedMode.Constant ->
                    PositiveNumberField("Speed (km/h)", draft.speedKmh, enabled) { onDraftChange(draft.withSpeedKmh(it)) }
                SpeedMode.FromTimestamps ->
                    Text(
                        "The file's own timing is replayed as recorded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                SpeedMode.TimestampsScaled ->
                    PositiveNumberField("Factor (2 = twice as fast)", draft.timestampFactor, enabled) {
                        onDraftChange(draft.withTimestampFactor(it))
                    }
            }
            if (draft.speedMode != SpeedMode.Constant && draft.source != null) {
                PositiveNumberField("Speed from source to route (km/h)", draft.leadInSpeedKmh, enabled) {
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
        SectionLabel("Update interval")
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
                "Use $MIN_UPDATE_INTERVAL_MS–$MAX_UPDATE_INTERVAL_MS ms.",
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
        SectionLabel("Summary")
        when (result) {
            is PlanBuildResult.Invalid ->
                Text(result.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            is PlanBuildResult.Ready -> {
                val plan = result.plan
                val timeline = remember(plan) { RouteTimeline(plan) }
                val now = System.currentTimeMillis()
                val start = if (draft.startAtEpochMs == null) "Starts now" else "Starts ${formatScheduleStart(plan.startAtEpochMs, now)}"
                Text(start, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${formatScheduleDistance(timeline.totalMeters)} · ${formatScheduleDuration(timeline.durationSeconds)} · " +
                        "avg ${formatAverage(timeline.averageSpeedKmh)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
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
