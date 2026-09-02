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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.latch.android.R
import com.latch.core.model.CaptureLayer
import com.latch.data.LatchSettings

/**
 * FR-209 and FR-1104: the disclosure screen, without which the notification listener cannot be
 * enabled at all.
 *
 * **The whole screen is the requirement.** FR-209: "enabled only through an explicit in-app
 * disclosure screen that names what is accessed and why." FR-1104: "notification access shall be
 * justified in the Play Console as core functionality, with in-app **prominent disclosure**."
 * Play's own policy asks for a disclosure that is in the app, is unavoidable before the
 * permission is requested, and says what is collected and why — which is why this is a screen
 * with a button at the bottom rather than a line of small print beside a switch.
 *
 * **Two exclusions are stated here because the SRS requires it, not because they read well.**
 * FR-210a: "The suppression shall be stated in the notification-access disclosure screen."
 * FR-805a: "The exclusion shall be stated in the notification-access disclosure screen alongside
 * FR-210a's." Both are properties of `CaptureSource` in code and neither can be forgotten by an
 * implementation; what could be forgotten is telling the user, which is what these two lines are.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationAccessScreen(
    settings: LatchSettings,
    /** Whether the system has actually granted notification access. Only the OS can say. */
    accessGranted: Boolean,
    /**
     * FR-211: whether Latch may **post** a notification.
     *
     * A different permission from the access above and easily confused with it: this one does
     * not let Latch read anything, it lets it show the offer. Without it the offer is created
     * and silently dropped, which is a feature that appears to do nothing.
     */
    canPostNotifications: Boolean,
    onRequestPostPermission: () -> Unit,
    onBack: () -> Unit,
    /** Opens the system's notification-access settings. Latch cannot grant this itself. */
    onOpenSystemSettings: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onToggleMonitored: (String) -> Unit,
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
            Text(
                stringResource(R.string.notification_access_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        }

        // FR-209: what is accessed, and why. In that order, because the second is what makes
        // the first reasonable and a user who stops reading after one paragraph should have
        // read the one that matters.
        Text(stringResource(R.string.notification_access_what), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.notification_access_why), style = MaterialTheme.typography.bodyMedium)

        HorizontalDivider()

        // FR-210 and NFR-206.
        Text(stringResource(R.string.notification_access_memory), style = MaterialTheme.typography.bodyMedium)
        // FR-211.
        Text(stringResource(R.string.notification_access_confirm), style = MaterialTheme.typography.bodyMedium)
        // FR-805a, stated here because the requirement says to state it here.
        Text(stringResource(R.string.notification_access_no_source_text), style = MaterialTheme.typography.bodyMedium)
        // FR-210a, likewise.
        Text(stringResource(R.string.notification_access_no_webhook), style = MaterialTheme.typography.bodyMedium)
        // The honest limit: this is what the system grants, not what Latch chooses to read.
        Text(stringResource(R.string.notification_access_system_grant), style = MaterialTheme.typography.bodyMedium)

        HorizontalDivider()

        if (!accessGranted) {
            // The permission is granted in the system's own screen and nowhere else. The button
            // sends the user there; it does not and cannot grant anything.
            Note(stringResource(R.string.notification_access_not_granted))
            Button(onClick = onOpenSystemSettings) {
                Text(stringResource(R.string.notification_access_open_settings))
            }
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.notification_access_enable),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = CaptureLayer.NOTIFICATION in settings.enabledLayers,
                onCheckedChange = onSetEnabled,
            )
        }

        // FR-211's other half. Asked for here, at the moment the user turns the layer on,
        // because this is the only feature in the app that posts anything — a prompt at first
        // launch for a feature that is off by default is the pattern that teaches people to
        // refuse prompts.
        if (!canPostNotifications) {
            Note(stringResource(R.string.notification_access_cannot_post))
            Button(onClick = onRequestPostPermission) {
                Text(stringResource(R.string.notification_access_allow_posting))
            }
        }

        // FR-212: which applications this layer monitors.
        Text(
            stringResource(R.string.notification_access_which_apps),
            style = MaterialTheme.typography.titleSmall,
        )
        // Empty by default and empty until the user chooses: a layer switched on that
        // immediately read every messaging app would be a second decision they never made.
        Note(stringResource(R.string.notification_access_apps_blurb))

        if (settings.seenNotificationPackages.isEmpty()) {
            // The list is built from what the listener has seen, which is the only way to offer
            // one without QUERY_ALL_PACKAGES. Before it has seen anything there is nothing to
            // show, and saying why is better than an empty box.
            Note(stringResource(R.string.notification_access_no_apps_yet))
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                settings.seenNotificationPackages.sorted().forEach { packageName ->
                    FilterChip(
                        selected = packageName in settings.monitoredPackages,
                        onClick = { onToggleMonitored(packageName) },
                        label = { Text(packageName) },
                    )
                }
            }
        }
    }
}
