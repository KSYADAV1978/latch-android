package com.latch.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.latch.android.R
import com.latch.android.help.KNOWN_NON_COOPERATING
import com.latch.android.help.explanationFor

/**
 * FR-204's in-app help topic (SRS 1.150).
 *
 * **The topic ends with what to do instead, and that placement is the point.** §8.3's own
 * sentence is "Layers 2, 3 and 4 exist to cover this" — the absence is designed for rather than
 * regretted — so the screen closes on three things that work everywhere rather than on an
 * apology for one that does not.
 *
 * The list is rendered from [KNOWN_NON_COOPERATING], each row carrying its own reason, because
 * the two reasons lead the reader somewhere different: one is fixable by the app's authors and
 * one is fixable by nobody.
 */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.help_selection_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.help_selection_intro),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.help_selection_mechanism),
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
        Text(
            text = stringResource(R.string.help_selection_known_heading),
            style = MaterialTheme.typography.titleSmall,
        )
        // Said on the screen rather than only in the code: a list a user reads as exhaustive is
        // one that tells them their own case is impossible.
        Text(
            text = stringResource(R.string.help_selection_known_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KNOWN_NON_COOPERATING.forEach { app ->
            Text(
                text = stringResource(app.nameRes),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(explanationFor(app.reason)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
        Text(
            text = stringResource(R.string.help_selection_instead_heading),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.help_selection_instead),
            style = MaterialTheme.typography.bodyMedium,
        )

        TextButton(onClick = onBack) {
            Text(stringResource(R.string.help_selection_back))
        }
    }
}
