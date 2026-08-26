package com.latch.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.latch.android.setup.SetupEvent
import com.latch.android.setup.SetupOutcome
import com.latch.android.ui.LatchTheme
import com.latch.android.ui.SetupFlow

/**
 * FR-101: first launch runs setup. It is not "first launch" that is tested, but whether an
 * account has been configured — which is what makes an abandoned setup indistinguishable
 * from never having run one (AC-16).
 *
 * The Capture Inbox (FR-701) and Settings (FR-1001) still belong here and are not built.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val coordinator = (application as LatchApplication).setupCoordinator

        setContent {
            LatchTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val outcome by coordinator.outcome.collectAsState()
                    val setupState by coordinator.state.collectAsState()

                    LaunchedEffect(outcome) {
                        // Backing out of step 1 leaves the app rather than stranding the
                        // user on a screen they have just declined.
                        if (outcome == SetupOutcome.ABANDONED) finish()
                    }

                    if (outcome == SetupOutcome.COMPLETED) {
                        Home()
                    } else {
                        BackHandler { coordinator.dispatch(SetupEvent.BackRequested) }
                        SetupFlow(state = setupState, onEvent = coordinator::dispatch)
                    }
                }
            }
        }
    }
}

@Composable
private fun Home() {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.home_headline), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.home_setup_pending), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.home_try_it), style = MaterialTheme.typography.bodyMedium)
    }
}
