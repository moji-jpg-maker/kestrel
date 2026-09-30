package dev.narumi.kestrel.feature.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.narumi.kestrel.core.routeplan.ScheduleDraft

/**
 * Holds the schedule form across configuration changes. It does not survive process death: an
 * imported route can be far larger than a saved-state Bundle allows, so the user re-imports instead.
 */
internal class ScheduleUiModel : ViewModel() {
    var draft by mutableStateOf(ScheduleDraft())
    var sheetVisible by mutableStateOf(false)
}
