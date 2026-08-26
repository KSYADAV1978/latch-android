package com.latch.android.capture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.latch.android.ui.CaptureScreen
import com.latch.android.ui.LatchTheme
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The confirmation window for capture layers 1, 2 and 4.
 *
 * Nothing is written to the user's Google account here, and nothing is persisted: the Google
 * write (FR-801), the Capture Inbox (FR-701) and the destination picker (FR-904) are not
 * built yet. What this proves out is the capture path end to end — intent in, on-device
 * parse, classified item on screen with its EVENT/TASK badge (FR-508).
 */
class CaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val captured = intent.toCapturedText(this)
        // Parsing is synchronous: it is string work with no I/O, and NFR-101 gives the whole
        // gesture-to-window path 800 ms. If the corpus ever grows a rule that changes that,
        // NFR-102 (render first, fill fields progressively) is the answer, not a spinner.
        val result = captured?.let {
            DateParser.parse(it.text, ParseContext(now = LocalDateTime.now(), zone = ZoneId.systemDefault()))
        }

        setContent {
            LatchTheme {
                CaptureScreen(
                    captured = captured,
                    result = result,
                    onDismiss = { finish() },
                    fromEmptyClipboard = intent.getBooleanExtra(EXTRA_READ_CLIPBOARD, false),
                )
            }
        }
    }
}
