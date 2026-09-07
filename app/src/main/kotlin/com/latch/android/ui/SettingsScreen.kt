package com.latch.android.ui

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.latch.android.R
import com.latch.android.settings.Destinations
import com.latch.core.model.CaptureLayer
import com.latch.core.model.Holiday
import com.latch.core.model.MatchType
import com.latch.core.model.RoutingMode
import com.latch.core.model.RoutingRule
import com.latch.data.AccountDefaults
import com.latch.webhook.EndpointRefusal
import com.latch.webhook.WebhookDelivery
import com.latch.webhook.WebhookResult
import com.latch.core.model.LatchSettings
import com.latch.data.RevokeOutcome
import com.latch.google.TaskList
import com.latch.google.WritableCalendar
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

private val HOLIDAY_DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/**
 * FR-1000: Settings, in the order FR-1001 lists them.
 *
 * The order is the requirement's own, deliberately: "destination calendar; task list; routing
 * mode; routing rules; default reminder lead times; default event duration; working week;
 * holiday list; date-order preference; time zone; confidence threshold behaviour; per-layer
 * capture toggles; and the outbound webhook endpoint, which shall appear as an advanced
 * setting, empty by default." Reading the screen against the requirement should be a matter of
 * going down both lists together.
 */
@Composable
fun SettingsScreen(
    settings: LatchSettings,
    account: AccountDefaults?,
    destinations: Destinations,
    bundledHolidays: List<Holiday>,
    endpointMask: String?,
    endpointRefusal: EndpointRefusal?,
    /** FR-1004b's passive report. Null until a delivery has been attempted. */
    lastDelivery: WebhookDelivery? = null,
    onBack: () -> Unit,
    onReloadDestinations: () -> Unit,
    onChooseCalendar: (WritableCalendar) -> Unit,
    onChooseTaskList: (TaskList) -> Unit,
    onChooseMode: (RoutingMode) -> Unit,
    onAddRule: (MatchType, String, WritableCalendar) -> Unit,
    onRemoveRule: (String) -> Unit,
    onUpdate: ((LatchSettings) -> LatchSettings) -> Unit,
    onAddHoliday: (LocalDate, String) -> Unit,
    onRemoveHoliday: (LocalDate) -> Unit,
    onSetEndpoint: (String) -> Unit,
    onClearEndpoint: () -> Unit,
    onSetWebhookEnabled: (Boolean) -> Unit,
    /** FR-806b: the grant no longer covers this build's scopes. */
    grantNeedsConsent: Boolean = false,
    /** FR-1007: re-consent, and nothing else. Never a sign-out. */
    onSignInAgain: () -> Unit = {},
    /** NFR-205: the single action. */
    onRevokeAndDelete: () -> Unit,
    /**
     * FR-209: the notification listener is turned on from its **own** screen and nowhere else.
     * A switch here would be the disclosure the requirement forbids skipping.
     */
    onOpenNotificationAccess: () -> Unit,
    /** NFR-205: what the last one managed, or null where none has been asked for. */
    revokeOutcome: RevokeOutcome? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        }

        // ----- FR-1001: destination calendar, task list, routing mode -----

        Section(R.string.settings_destination)
        if (destinations.loading) {
            Note(stringResource(R.string.settings_loading))
        } else if (destinations.failed) {
            Note(stringResource(R.string.settings_load_failed))
            TextButton(onClick = onReloadDestinations) { Text(stringResource(R.string.setup_retry)) }
        } else {
            CalendarChips(destinations.calendars, account?.destinationCalendarId, onChooseCalendar)
        }

        Section(R.string.settings_task_list)
        FlowChips(
            options = destinations.taskLists.map { it.id to it.title },
            selectedId = account?.taskListId,
            onSelect = { id -> destinations.taskLists.firstOrNull { it.id == id }?.let(onChooseTaskList) },
        )

        Section(R.string.settings_routing_mode)
        // FR-1002, and the requirement asks for this sentence as much as for the behaviour:
        // "Switching between Option A and Option B shall not move, alter or delete anything
        // already saved, and the UI shall state this."
        Note(stringResource(R.string.settings_mode_safe))
        FlowChips(
            options = listOf(
                RoutingMode.LATCH_CALENDAR.name to stringResource(R.string.settings_mode_latch),
                RoutingMode.EXISTING_CALENDARS.name to stringResource(R.string.settings_mode_existing),
            ),
            selectedId = account?.routingMode?.name,
            onSelect = { onChooseMode(RoutingMode.valueOf(it)) },
        )

        // ----- FR-905: routing rules -----

        Section(R.string.settings_rules)
        if (account?.routingMode != RoutingMode.EXISTING_CALENDARS) {
            // FR-103: Option B is "the destination for **all** captures", so a rule sending
            // some elsewhere would make one setting mean two things.
            Note(stringResource(R.string.settings_rules_option_a_only))
        } else {
            RulesEditor(
                rules = settings.routingRules,
                calendars = destinations.calendars,
                onAdd = onAddRule,
                onRemove = onRemoveRule,
            )
        }

        // ----- FR-1001: reminder lead times, event duration -----

        Section(R.string.settings_reminders)
        MinutesField(
            value = settings.defaultReminderMinutes.firstOrNull(),
            label = R.string.settings_reminder_minutes,
            onChange = { minutes ->
                onUpdate { it.copy(defaultReminderMinutes = listOfNotNull(minutes)) }
            },
        )

        Section(R.string.settings_duration)
        MinutesField(
            value = settings.defaultEventDuration.toMinutes().toInt(),
            label = R.string.settings_duration_minutes,
            onChange = { minutes ->
                // Zero would make every event end when it starts. Ignored rather than clamped
                // to a guess: the field is mid-edit while the user deletes the old number.
                minutes?.takeIf { it > 0 }?.let { m ->
                    onUpdate { it.copy(defaultEventDuration = Duration.ofMinutes(m.toLong())) }
                }
            },
        )

        // ----- FR-605: working week, holiday list -----

        Section(R.string.settings_working_week)
        WorkingWeekChips(settings, onUpdate)

        Section(R.string.settings_holidays)
        HolidayList(settings, bundledHolidays, onAddHoliday, onRemoveHoliday)

        // ----- FR-504, FR-1001: date order, time zone -----

        Section(R.string.settings_date_order)
        FlowChips(
            options = listOf(
                "day" to stringResource(R.string.settings_date_order_day_first),
                "month" to stringResource(R.string.settings_date_order_month_first),
            ),
            selectedId = if (settings.dayFirstDates) "day" else "month",
            onSelect = { choice -> onUpdate { it.copy(dayFirstDates = choice == "day") } },
        )

        Section(R.string.settings_time_zone)
        var zone by remember(settings.timeZone) { mutableStateOf(settings.timeZone.orEmpty()) }
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = zone,
            onValueChange = { typed ->
                zone = typed
                // Only a zone the platform actually knows is stored; anything else leaves the
                // setting alone, so a half-typed "Asia/Kol" cannot make every capture fail.
                val id = typed.trim().takeIf { it.isNotEmpty() }
                if (id == null || runCatching { java.time.ZoneId.of(id) }.isSuccess) {
                    onUpdate { it.copy(timeZone = id) }
                }
            },
            label = { Text(stringResource(R.string.settings_time_zone_hint)) },
            singleLine = true,
        )

        // ----- FR-512: the confidence threshold -----

        Section(R.string.settings_threshold)
        Note(stringResource(R.string.settings_threshold_blurb))
        MinutesField(
            value = (settings.confidenceThreshold * 100).toInt(),
            label = R.string.settings_threshold_percent,
            onChange = { percent ->
                percent?.takeIf { it in 0..100 }?.let { p ->
                    onUpdate { it.copy(confidenceThreshold = p / 100.0) }
                }
            },
        )

        // ----- FR-1003: per-layer capture toggles -----

        Section(R.string.settings_layers)
        LayerToggles(settings, onUpdate, onOpenNotificationAccess)

        // ----- FR-1004: the webhook, advanced and empty by default -----

        HorizontalDivider()
        Section(R.string.settings_webhook)
        WebhookSection(
            settings = settings,
            endpointMask = endpointMask,
            refusal = endpointRefusal,
            lastDelivery = lastDelivery,
            onSetEndpoint = onSetEndpoint,
            onClear = onClearEndpoint,
            onSetEnabled = onSetWebhookEnabled,
        )

        // ----- FR-1007: the account, and a way back to a consent screen -----
        //
        // Deliberately **above** the divider that starts NFR-205's danger zone, and not inside
        // it. These are opposites: this re-runs a grant and touches no stored data, that one
        // revokes and deletes everything. Putting a re-consent beside a disconnect is how
        // somebody loses an Inbox they meant to keep, and this project has already recorded what
        // happens when an act with a consequence sits behind a label that reads as information.

        Section(R.string.settings_account)
        AccountSection(account, grantNeedsConsent, onSignInAgain)

        // ----- NFR-205: revoke access and delete all local data -----

        HorizontalDivider()
        Section(R.string.settings_danger)
        DangerZone(onRevokeAndDelete, revokeOutcome)
    }
}

/**
 * FR-1007: the connected account, and a route back to a consent screen.
 *
 * **The gap this closes was found by looking for a consent screen and not finding one**
 * (SRS 1.87). `reauthorize()` existed and worked, and was reachable from exactly one place in the
 * whole application: a button on the home screen that renders only while a queue entry has
 * already failed for want of a sign-in. So the only way to re-consent was to lose a capture
 * first, and discover the button by accident.
 *
 * **It is present whatever the queue is doing.** The condition it answers — a grant that no
 * longer covers what the app asks for — has nothing to do with whether anything is queued, and
 * §5.11's contacts scope will put every existing user in exactly that state on their next
 * authorization.
 *
 * The action is re-consent and never sign-out. NFR-205's disconnect is a different thing, is
 * destructive by design, and is below the divider.
 */
@Composable
private fun AccountSection(
    account: AccountDefaults?,
    grantNeedsConsent: Boolean,
    onSignInAgain: () -> Unit,
) {
    // The account's own name is not stored — `AccountDefaults.accountId` is a SHA-256 of the
    // address, deliberately, so the preference key is opaque. What can honestly be shown is that
    // an account is connected and which calendar its captures go to.
    Text(
        text = stringResource(
            if (account == null) R.string.settings_account_none else R.string.settings_account_connected
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (grantNeedsConsent) {
        Text(
            text = stringResource(R.string.settings_account_needs_consent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Text(
        text = stringResource(R.string.settings_account_sign_in_again_hint),
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = onSignInAgain) {
        Text(stringResource(R.string.settings_account_sign_in_again))
    }
}

/**
 * NFR-205: "a single action to revoke access and delete all local data."
 *
 * **One action, behind one confirmation.** The requirement asks for a single action and gets
 * one; the confirmation is not a second action but a chance to read what it does, which for
 * something irreversible is the least a screen can offer. The warning says what is deleted
 * *and* what is not — items already in Google Calendar and Tasks are left exactly as they are,
 * which is the thing a user is most likely to be afraid of and the thing this does not do.
 */
@Composable
private fun DangerZone(onRevokeAndDelete: () -> Unit, outcome: RevokeOutcome?) {
    var confirming by remember { mutableStateOf(false) }

    outcome?.let {
        Note(
            stringResource(
                when {
                    // NFR-303's instinct applied to something that is not a write: say which
                    // half failed, and what the user can do about it.
                    !it.localDataDeleted -> R.string.settings_revoke_failed
                    !it.accessRevoked -> R.string.settings_revoke_partial
                    else -> R.string.settings_revoke_done
                }
            )
        )
    }

    if (!confirming) {
        TextButton(onClick = { confirming = true }) {
            Text(stringResource(R.string.settings_revoke))
        }
        return
    }

    Note(stringResource(R.string.settings_revoke_warning))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { confirming = false }) {
            Text(stringResource(R.string.settings_revoke_cancel))
        }
        Button(onClick = {
            confirming = false
            onRevokeAndDelete()
        }) {
            Text(stringResource(R.string.settings_revoke_confirm))
        }
    }
}

@Composable
private fun Section(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChips(
    options: List<Pair<String, String>>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) ->
            FilterChip(
                selected = id == selectedId,
                onClick = { onSelect(id) },
                label = { Text(label) },
            )
        }
    }
}

/**
 * FR-901 and FR-902: only calendars the user can write to, each in its own colour.
 *
 * FR-903's hidden indicator is here too — writing to an unticked calendar produces an item the
 * user cannot see, which is indistinguishable from a failed write.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarChips(
    calendars: List<WritableCalendar>,
    selectedId: String?,
    onSelect: (WritableCalendar) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        calendars.forEach { calendar ->
            FilterChip(
                selected = calendar.id == selectedId,
                onClick = { onSelect(calendar) },
                leadingIcon = { CalendarSwatch(calendar.backgroundColor) },
                label = {
                    Text(
                        if (calendar.visible) calendar.summary
                        else calendar.summary + " · " + stringResource(R.string.setup_destination_hidden_badge)
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RulesEditor(
    rules: List<RoutingRule>,
    calendars: List<WritableCalendar>,
    onAdd: (MatchType, String, WritableCalendar) -> Unit,
    onRemove: (String) -> Unit,
) {
    rules.forEach { rule ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Note("${rule.matchType.name.lowercase()} · ${rule.matchValue} → ${rule.calendarName}")
            TextButton(onClick = { onRemove(rule.id) }) {
                Text(stringResource(R.string.settings_rule_remove))
            }
        }
    }

    var matchType by remember { mutableStateOf(MatchType.SOURCE_APP) }
    var matchValue by remember { mutableStateOf("") }
    var calendarId by remember { mutableStateOf<String?>(null) }

    FlowChips(
        options = MatchType.entries.map { it.name to it.name.lowercase().replace('_', ' ') },
        selectedId = matchType.name,
        onSelect = { matchType = MatchType.valueOf(it) },
    )
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = matchValue,
        onValueChange = { matchValue = it },
        label = { Text(stringResource(R.string.settings_rule_match)) },
        singleLine = true,
    )
    CalendarChips(calendars, calendarId) { calendarId = it.id }
    Button(
        enabled = matchValue.isNotBlank() && calendarId != null,
        onClick = {
            calendars.firstOrNull { it.id == calendarId }?.let { onAdd(matchType, matchValue, it) }
            matchValue = ""
        },
    ) { Text(stringResource(R.string.settings_rule_add)) }
}

@Composable
private fun MinutesField(value: Int?, label: Int, onChange: (Int?) -> Unit) {
    var typed by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = typed,
        onValueChange = {
            typed = it.filter(Char::isDigit)
            onChange(typed.toIntOrNull())
        },
        label = { Text(stringResource(label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

/** FR-605: a configurable working week, because a six-day one is common in the target market. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkingWeekChips(settings: LatchSettings, onUpdate: ((LatchSettings) -> LatchSettings) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DayOfWeek.entries.forEach { day ->
            val on = day in settings.workingDays
            FilterChip(
                selected = on,
                onClick = {
                    // `WorkingWeek`'s own init refuses a week with no working days, and the
                    // calculator's loop would never terminate against one — so the last day
                    // cannot be turned off.
                    if (!on || settings.workingDays.size > 1) {
                        onUpdate {
                            it.copy(
                                workingDays = if (on) it.workingDays - day else it.workingDays + day
                            )
                        }
                    }
                },
                // The platform's own day names, in the composition's locale rather than the
                // process default — a locale read outside observable state does not recompose
                // when the user changes it, which is the whole of what NFR-403 is about.
                label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
            )
        }
    }
}

/** FR-605: the bundled list, plus the user's additions, minus their removals. */
@Composable
private fun HolidayList(
    settings: LatchSettings,
    bundled: List<Holiday>,
    onAdd: (LocalDate, String) -> Unit,
    onRemove: (LocalDate) -> Unit,
) {
    // The bundled list is a placeholder — three gazetted holidays on fixed dates — and §13's
    // fourth open decision is exactly this question. What is not a placeholder is the shape.
    Note(stringResource(R.string.settings_holidays_blurb))

    settings.holidays(bundled).sortedBy { it.date }.forEach { holiday ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Note("${holiday.date.format(HOLIDAY_DATE)} · ${holiday.name}")
            TextButton(onClick = { onRemove(holiday.date) }) {
                Text(stringResource(R.string.settings_holiday_remove))
            }
        }
    }

    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.settings_holiday_name)) },
        singleLine = true,
    )
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = date,
        onValueChange = { date = it },
        label = { Text(stringResource(R.string.settings_holiday_date)) },
        singleLine = true,
    )
    Button(
        enabled = name.isNotBlank() && runCatching { LocalDate.parse(date.trim()) }.isSuccess,
        onClick = {
            runCatching { LocalDate.parse(date.trim()) }.getOrNull()?.let { parsed ->
                onAdd(parsed, name.trim())
                name = ""
                date = ""
            }
        },
    ) { Text(stringResource(R.string.settings_holiday_add)) }
}

/**
 * FR-1003's per-layer toggles.
 *
 * **Only the Quick Settings tile can genuinely be removed from the system**, and the screen
 * says so rather than implying otherwise. The tile is its own manifest component, so turning it
 * off disables the component and the tile disappears. Text selection and the share sheet are
 * three intent filters on **one** activity, so disabling it would disable all three at once;
 * those toggles are enforced when the capture arrives instead, which means Latch still appears
 * in the share sheet and declines the capture with a reason. Splitting the activity per layer is
 * the cure and is recorded as owed rather than pretended away.
 */
@Composable
private fun LayerToggles(
    settings: LatchSettings,
    onUpdate: ((LatchSettings) -> LatchSettings) -> Unit,
    onOpenNotificationAccess: () -> Unit,
) {
    val layers = listOf(
        CaptureLayer.TEXT_SELECTION to R.string.settings_layer_text_selection,
        CaptureLayer.SHARE_SHEET to R.string.settings_layer_share_sheet,
        CaptureLayer.QUICK_TILE to R.string.settings_layer_tile,
    )
    layers.forEach { (layer, label) ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = layer in settings.enabledLayers,
                onCheckedChange = { on ->
                    onUpdate {
                        it.copy(
                            enabledLayers = if (on) it.enabledLayers + layer else it.enabledLayers - layer
                        )
                    }
                },
            )
        }
    }
    Note(stringResource(R.string.settings_layers_note))
    // FR-1003 and FR-209: the notification listener is off and is not turned on from here. It
    // has its own disclosure screen, which FR-209 requires before it can be enabled at all —
    // so this is a way *to* that screen and never a switch.
    Note(stringResource(R.string.settings_layer_notification_elsewhere))
    TextButton(onClick = onOpenNotificationAccess) {
        Text(stringResource(R.string.notification_access_open))
    }
}

/**
 * FR-1004: the outbound webhook — advanced, empty by default, and warned about at the point of
 * configuration.
 *
 * **Entering an endpoint and enabling delivery are two acts and stay two.** FR-1004 says the
 * feature is disabled by default *and* that the user must enter the URL explicitly; keeping
 * them separate means a paste cannot start sending anything.
 *
 * The warning is not decoration. FR-1004 requires "a warning at the point of configuration
 * stating that captured content will be sent to that endpoint", and FR-1004b's offline
 * consequence — a capture saved offline reaches Google later and **no webhook is ever sent for
 * it** — is stated here too, because the SRS asks for it on this screen and because it would
 * otherwise be reported as a defect.
 */
@Composable
private fun WebhookSection(
    settings: LatchSettings,
    endpointMask: String?,
    refusal: EndpointRefusal?,
    lastDelivery: WebhookDelivery?,
    onSetEndpoint: (String) -> Unit,
    onClear: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
) {
    Note(stringResource(R.string.settings_webhook_warning))
    Note(stringResource(R.string.settings_webhook_offline))
    Note(stringResource(R.string.settings_webhook_notifications))

    if (endpointMask != null) {
        // NFR-203: masked once saved. The screen never holds the real value.
        Note(stringResource(R.string.settings_webhook_saved, endpointMask))
        TextButton(onClick = onClear) { Text(stringResource(R.string.settings_webhook_clear)) }
    }

    var typed by remember { mutableStateOf("") }
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = typed,
        onValueChange = { typed = it },
        label = { Text(stringResource(R.string.settings_webhook_endpoint)) },
        singleLine = true,
    )
    refusal?.let {
        Note(
            stringResource(
                when (it) {
                    EndpointRefusal.MALFORMED -> R.string.settings_webhook_malformed
                    EndpointRefusal.NOT_HTTPS -> R.string.settings_webhook_not_https
                    EndpointRefusal.CARRIES_CREDENTIALS -> R.string.settings_webhook_credentials
                }
            )
        )
    }
    Button(
        enabled = typed.isNotBlank(),
        onClick = {
            onSetEndpoint(typed)
            typed = ""
        },
    ) { Text(stringResource(R.string.settings_webhook_save)) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.settings_webhook_enabled), style = MaterialTheme.typography.bodyMedium)
        Switch(
            // Cannot be turned on without somewhere to send to, which is FR-1004's "requires
            // the user to enter the endpoint URL explicitly" expressed as a control rather than
            // as a rule someone has to remember.
            enabled = endpointMask != null,
            checked = settings.webhookEnabled,
            onCheckedChange = onSetEnabled,
        )
    }
    // SRS 1.172. FR-1004 wants entering an endpoint and enabling delivery to be two acts, and
    // the disabled switch above is that rule expressed as a control. What it did not do was say
    // so: a greyed switch with no sentence beside it is a control that appears broken, which is
    // SRS 1.100's class and the standard SRS 1.160 already set for the offer's Update button.
    if (endpointMask == null) {
        Note(stringResource(R.string.settings_webhook_needs_endpoint))
    }

    // FR-1004b, last: a line below the switch it is about, on a screen the user opened. Nothing
    // at all until something has been attempted.
    lastDelivery?.let { Note(deliveryLine(it)) }
}

/**
 * FR-1004b's report, in words.
 *
 * NFR-402 keeps the phrasing in `strings.xml` and the facts in `:webhook`, which is why this
 * reads a `WebhookResult` rather than a sentence: the desktop says the same four things in its
 * own file, and neither client invents an outcome the other cannot express.
 *
 * **The status the endpoint gave is shown**, because 404 against 401 is the difference between
 * a wrong path and a wrong token, and a user who cannot tell them apart has nowhere to start.
 * The endpoint itself is not, and cannot be: the record carries no address (NFR-203).
 */
@Composable
private fun deliveryLine(delivery: WebhookDelivery): String {
    val at = DateTimeFormatter.ofPattern("d MMM, HH:mm")
        .format(delivery.at.atZone(ZoneId.systemDefault()))
    return when (delivery.result) {
        WebhookResult.DELIVERED -> stringResource(R.string.settings_webhook_last_ok, at)
        WebhookResult.ENDPOINT_REFUSED ->
            stringResource(R.string.settings_webhook_last_refused, delivery.status ?: 0, at)
        WebhookResult.UNREACHABLE -> stringResource(R.string.settings_webhook_last_unreachable, at)
        WebhookResult.REFUSED_BEFORE_SENDING ->
            stringResource(R.string.settings_webhook_last_not_sent, at)
    }
}
