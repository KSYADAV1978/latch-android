package com.latch.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
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
 * from never having run one (AC-16), and what now makes a completed one survive the
 * process that ran it.
 *
 * The Capture Inbox (FR-701) and Settings (FR-1001) still belong here and are not built.
 */
class MainActivity : ComponentActivity() {

    private val latchApplication get() = application as LatchApplication

    /**
     * The consent screen's result. Registered as a field, which `ComponentActivity` handles
     * before STARTED and gives a stable key. The composition-scoped
     * `rememberLauncherForActivityResult` would key itself off a composition that changes
     * shape below — the `when` that swaps between setup, Home and nothing — and a key that
     * moves is a result delivered to no one. The wait it would strand has no retry on screen.
     */
    private val consentResult = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // Null data covers a dismissed consent screen; the bridge reads that as cancelled.
        latchApplication.authResolution.deliver(result.data)
    }

    override fun onStart() {
        super.onStart()
        latchApplication.authResolution.attach(consentResult)
    }

    override fun onStop() {
        latchApplication.authResolution.detach(consentResult)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = latchApplication
        val coordinator = app.setupCoordinator

        setContent {
            LatchTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val outcome by coordinator.outcome.collectAsState()
                    val setupState by coordinator.state.collectAsState()
                    val configured by app.configuredAccounts.collectAsState()

                    LaunchedEffect(outcome) {
                        // Backing out of step 1 leaves the app rather than stranding the
                        // user on a screen they have just declined.
                        if (outcome == SetupOutcome.ABANDONED) finish()
                    }

                    when {
                        // Stored defaults have not been read yet. This lasts a frame or
                        // two, and drawing nothing through it is the only option that
                        // cannot flash setup at a configured user.
                        configured == null -> Unit

                        // The outcome covers the account configured moments ago in this
                        // process; the stored list covers every earlier launch.
                        outcome == SetupOutcome.COMPLETED || configured?.isNotEmpty() == true -> Home()

                        else -> {
                            BackHandler { coordinator.dispatch(SetupEvent.BackRequested) }
                            SetupFlow(state = setupState, onEvent = coordinator::dispatch)
                        }
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
