package com.latch.android.capture

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.latch.android.LatchApplication
import com.latch.android.ui.CaptureScreen
import com.latch.android.ui.LatchTheme
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The confirmation screen behind every capture layer (§5.2).
 *
 * The parse is synchronous and touches no I/O, which is what keeps NFR-101's 800 ms budget
 * from gesture to confirmation achievable — and why the FR-904 destination is read from
 * stored defaults rather than fetched.
 */
class CaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LatchApplication

        // A previous capture in this process may have left an outcome on screen.
        app.captureSaver.reset()
        // Setup may have completed in another task since this process read its defaults.
        app.refreshAccounts()

        val captured = intent.toCapturedText(this)?.copy(appId = referrerPackage())
        val context = ParseContext(now = LocalDateTime.now(), zone = ZoneId.systemDefault())
        val result = captured?.let { DateParser.parse(it.text, context) }

        setContent {
            LatchTheme {
                val destinations by app.configuredAccounts.collectAsState()
                val saveState by app.captureSaver.state.collectAsState()

                CaptureScreen(
                    captured = captured,
                    result = result,
                    onDismiss = { finish() },
                    // Three states, not a nullable value: not-yet-read and none-configured
                    // are opposite facts, and only the second is worth telling the user.
                    destination = when (val accounts = destinations) {
                        null -> DestinationState.Loading
                        else -> accounts.firstOrNull()
                            ?.let(DestinationState::Ready)
                            ?: DestinationState.None
                    },
                    saveState = saveState,
                    // FR-512, interim: shown rather than blocking the save, until the
                    // Capture Inbox exists to send it to instead.
                    lowConfidence = result != null &&
                        result.overallConfidence < context.confidenceThreshold,
                    onSave = {
                        if (captured != null && result != null) {
                            app.captureSaver.save(captured, result, context)
                        }
                    },
                    fromEmptyClipboard = intent.getBooleanExtra(EXTRA_READ_CLIPBOARD, false),
                )
            }
        }
    }

    /**
     * `launchMode` is `singleTop`, so a second capture arriving while this one is showing is
     * delivered here rather than to a new instance. Without this the screen would keep the
     * previous capture's text — and, now that there is a Save button under it, would offer
     * to save the wrong thing.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    /**
     * FR-802's source application, for `latch.source_app`. `getReferrer()` is the only
     * source of it here — neither `ACTION_PROCESS_TEXT` nor `ACTION_SEND` carries the
     * sender's package, and `getCallingActivity()` is null because both arrive via
     * `startActivity` rather than for a result. Often null, which the schema reads as
     * unknown rather than as an error.
     */
    private fun referrerPackage(): String? = referrer?.host?.takeIf { it.isNotBlank() }
}
