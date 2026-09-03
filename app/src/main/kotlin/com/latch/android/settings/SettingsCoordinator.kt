package com.latch.android.settings

import com.latch.core.model.CaptureLayer
import com.latch.core.model.Holiday
import com.latch.core.model.HolidaySource
import com.latch.core.model.MatchType
import com.latch.core.model.RoutingMode
import com.latch.core.model.RoutingRule
import com.latch.data.AccountDefaults
import com.latch.data.AccountDefaultsStore
import com.latch.google.CalendarApi
import com.latch.data.EncryptedSecretStore
import com.latch.webhook.EndpointRefusal
import com.latch.core.model.LatchSettings
import com.latch.data.SecretStore
import com.latch.data.SettingsStore
import com.latch.google.TaskList
import com.latch.google.TasksApi
import com.latch.google.WritableCalendar
import com.latch.webhook.maskedEndpoint
import com.latch.webhook.validateEndpoint
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * FR-1001's destinations, which have to be fetched rather than stored.
 *
 * Loaded on demand — opening Settings, not launching the app — because NFR-101 budgets the
 * capture path 800 ms and a `calendarList.list` on every launch would be spent on a screen most
 * launches never reach. FR-908's launch-time refresh is a different obligation and is separate.
 */
data class Destinations(
    val calendars: List<WritableCalendar> = emptyList(),
    val taskLists: List<TaskList> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/**
 * FR-1000: Settings.
 *
 * Held by `LatchApplication` for the reason every coordinator here is — a write started from
 * this screen persists and must finish even if the user leaves — and because the settings it
 * edits are read by the capture path, which is a different process entry point entirely.
 *
 * **FR-1002 is a property of what this does not do.** Switching between Option A and Option B
 * writes one field on the account defaults and touches nothing else: no item is moved, altered
 * or deleted, and no stored choice from the other mode is cleared. That is AC-12, and it is met
 * by the mode being a routing input rather than a migration. The UI states it, which the
 * requirement also asks for.
 */
class SettingsCoordinator(
    private val settingsStore: SettingsStore,
    private val defaultsStore: AccountDefaultsStore,
    private val secrets: SecretStore,
    private val calendarApi: CalendarApi,
    private val tasksApi: TasksApi,
    private val scope: CoroutineScope,
    /** The capture path reads both; a change here has to reach it. */
    private val onChanged: () -> Unit,
) {
    private val _destinations = MutableStateFlow(Destinations())
    val destinations: StateFlow<Destinations> = _destinations.asStateFlow()

    private val _endpointMask = MutableStateFlow<String?>(null)

    /**
     * NFR-203: "It shall be masked in the Settings UI once saved."
     *
     * The mask rather than the value, all the way to the screen — a composable holding the real
     * endpoint would be one recomposition away from rendering it, and the requirement is about
     * what is on the glass rather than about how carefully it got there.
     */
    val endpointMask: StateFlow<String?> = _endpointMask.asStateFlow()

    private val _endpointRefusal = MutableStateFlow<EndpointRefusal?>(null)
    val endpointRefusal: StateFlow<EndpointRefusal?> = _endpointRefusal.asStateFlow()

    fun open() {
        loadDestinations()
        scope.launch {
            _endpointMask.value = runCatching {
                secrets.get(EncryptedSecretStore.KEY_WEBHOOK_ENDPOINT)
            }.getOrNull()?.let(::maskedEndpoint)
        }
    }

    fun loadDestinations() {
        scope.launch {
            _destinations.value = Destinations(loading = true)
            val calendars = runCatching { calendarApi.listWritableCalendars() }
            val lists = runCatching { tasksApi.listTaskLists() }
            _destinations.value = if (calendars.isFailure || lists.isFailure) {
                Destinations(failed = true)
            } else {
                Destinations(calendars.getOrDefault(emptyList()), lists.getOrDefault(emptyList()))
            }
        }
    }

    fun update(change: (LatchSettings) -> LatchSettings) {
        scope.launch {
            val current = runCatching { settingsStore.read() }.getOrDefault(LatchSettings())
            runCatching { settingsStore.write(change(current)) }
            onChanged()
        }
    }

    /**
     * FR-1001's destination calendar, task list and routing mode — the three that live on the
     * account rather than in the settings record, because FR-110 stores them per account.
     *
     * **FR-1002 and AC-12 in one line**: this writes a destination and nothing else. Items
     * already in the user's account are not read, moved or deleted, and the other mode's stored
     * choice is not cleared — switching back finds it where it was.
     */
    fun updateAccount(change: (AccountDefaults) -> AccountDefaults) {
        scope.launch {
            val account = runCatching { defaultsStore.allAccounts().firstOrNull() }.getOrNull()
                ?: return@launch
            runCatching { defaultsStore.save(change(account)) }
            onChanged()
        }
    }

    fun chooseCalendar(calendar: WritableCalendar) = updateAccount {
        it.copy(
            destinationCalendarId = calendar.id,
            destinationCalendarName = calendar.summary,
            destinationCalendarColour = calendar.backgroundColor,
        )
    }

    fun chooseTaskList(list: TaskList) = updateAccount { it.copy(taskListId = list.id) }

    fun chooseMode(mode: RoutingMode) = updateAccount { it.copy(routingMode = mode) }

    /** FR-1003: a capture layer, on or off. */
    fun setLayerEnabled(layer: CaptureLayer, enabled: Boolean) = update {
        it.copy(enabledLayers = if (enabled) it.enabledLayers + layer else it.enabledLayers - layer)
    }

    /** FR-605: a holiday of the user's own. */
    fun addHoliday(date: LocalDate, name: String) = update {
        it.copy(holidayAdditions = it.holidayAdditions + Holiday(date, name, HolidaySource.USER))
    }

    /**
     * FR-605's removal, which has to cover both lists.
     *
     * A bundled holiday is removed by recording its **date**, because a bundled entry has no id
     * the user could name and the calculator asks about dates. One of their own is removed
     * outright, which is why both halves are here rather than only the first.
     */
    fun removeHoliday(date: LocalDate) = update {
        it.copy(
            holidayAdditions = it.holidayAdditions.filterNot { holiday -> holiday.date == date },
            holidayRemovals = it.holidayRemovals + date,
        )
    }

    /** FR-905: a routing rule, under Option A. */
    fun addRule(matchType: MatchType, matchValue: String, calendar: WritableCalendar) = update {
        it.copy(
            routingRules = it.routingRules + RoutingRule(
                id = "rule." + UUID.randomUUID(),
                matchType = matchType,
                matchValue = matchValue.trim(),
                calendarId = calendar.id,
                priority = it.routingRules.size,
                calendarName = calendar.summary,
                calendarColour = calendar.backgroundColor,
            )
        )
    }

    fun removeRule(ruleId: String) = update {
        it.copy(routingRules = it.routingRules.filterNot { rule -> rule.id == ruleId })
    }

    /**
     * FR-1004: the endpoint, entered explicitly and stored as a secret (NFR-203).
     *
     * A refusal is reported rather than corrected. `https` is required outright — this address
     * receives the user's captured content, and over plaintext that is not a decision a warning
     * makes reasonable.
     *
     * **Saving an endpoint does not enable the webhook.** FR-1004 says the feature is disabled
     * by default and requires the user to enter the URL explicitly; those are two acts and the
     * screen keeps them as two, so a paste cannot start sending anything.
     */
    fun setEndpoint(raw: String) {
        val refusal = validateEndpoint(raw)
        _endpointRefusal.value = refusal
        if (refusal != null) return
        scope.launch {
            runCatching { secrets.put(EncryptedSecretStore.KEY_WEBHOOK_ENDPOINT, raw.trim()) }
            _endpointMask.value = maskedEndpoint(raw.trim())
            onChanged()
        }
    }

    /** FR-1004: clearing the endpoint turns the webhook off with it — there is nowhere to send. */
    fun clearEndpoint() {
        scope.launch {
            runCatching { secrets.put(EncryptedSecretStore.KEY_WEBHOOK_ENDPOINT, "") }
            _endpointMask.value = null
            _endpointRefusal.value = null
        }
        update { it.copy(webhookEnabled = false) }
    }

    fun setWebhookEnabled(enabled: Boolean) = update { it.copy(webhookEnabled = enabled) }
}
