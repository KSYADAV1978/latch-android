package com.latch.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-301's menu, which is the only surface this application has when no capture is open.
 *
 * **Everything the menu decides is decided in `trayMenu` so that these tests can reach it.**
 * Swing is the part of this client no JVM test touches, and it has produced five defects of one
 * shape — the model correct, tested and reachable, and the screen showing something else. What
 * a test can hold here is the ordering, the grouping, which rows are information and which are
 * offers. What it cannot hold is size, colour and glyph coverage; those are in the human
 * backlog, named.
 */
class TrayMenuTest {

    private val signedIn = TrayModel("Ctrl+Shift+K", "a@example.com", configured = true, pending = 0)

    private fun labels(model: TrayModel) = trayMenu(model).mapNotNull { it.label }

    private fun row(model: TrayModel, action: TrayAction) =
        trayMenu(model).single { it.id == action }

    // ---- what the menu offers ------------------------------------------------------------

    @Test
    fun `the menu leads with a capture the tray can actually perform`() {
        // SRS 1.82. The row read "Capture now (Ctrl+Shift+K)" and synthesised a copy, which
        // cannot work from a tray icon: pressing the row *is* the act of defocusing the window
        // holding the selection. It reads the clipboard now, and the label says so.
        val first = trayMenu(signedIn).first()
        assertEquals(TrayAction.CAPTURE, first.id)
        assertTrue(first is TrayItem.Action, "the capture row is an action, not status")
        assertTrue(first.label!!.contains("copied"), first.label!!)
    }

    @Test
    fun `the shortcut is stated as information rather than offered as a button`() {
        val hint = trayMenu(signedIn).filterIsInstance<TrayItem.Status>()
            .single { it.label.contains("Ctrl+Shift+K") }
        assertEquals(null, hint.id, "the hotkey hint must not look pressable")
    }

    @Test
    fun `with no client configured the menu says so rather than offering a sign-in that cannot work`() {
        val model = signedIn.copy(signedInAs = null, configured = false)
        val account = trayMenu(model).filterIsInstance<TrayItem.Status>()
            .single { it.label.contains("configured") }
        assertEquals(null, account.id, "a sign-in that cannot work must not be pressable")
        assertTrue(trayMenu(model).none { it.id == TrayAction.SIGN_IN })
    }

    @Test
    fun `a queue held for a sign-in asks for one, and pressing it signs in`() {
        // FR-806a's surface on this client, which had none (SRS 1.192). Before it, the same
        // entry read "2 waiting to be written" — true, and it names a wait the user can do
        // nothing about, over a queue that will not move until they do the one thing the row
        // now asks for.
        val model = signedIn.copy(pending = 2, queueNeedsSignIn = true)
        val labels = labels(model)

        assertTrue(labels.any { it.contains("sign in", ignoreCase = true) }, labels.toString())
        assertTrue(
            labels.none { it.contains("waiting to be written") },
            "the ordinary wait must not be shown beside it: this queue is not waiting on a network",
        )
        // Pressing it signs in rather than retrying. A retry before the sign-in is the failure
        // the row exists to explain.
        val count = trayMenu(model).filterIsInstance<TrayItem.Status>()
            .single { it.label.contains("sign in", ignoreCase = true) && it.label.contains("2") }
        assertEquals(TrayAction.SIGN_IN, count.id)
    }

    @Test
    fun `an ordinary queue is unchanged and still offers a retry`() {
        // The guard: the flag governs the sentence, so an ordinary offline queue must be
        // untouched — a menu that asked for a sign-in on every dropped network would train
        // the user to ignore it, which is worse than the silence this replaces.
        val model = signedIn.copy(pending = 2)
        assertEquals(TrayAction.RETRY, row(model, TrayAction.RETRY).id)
        assertTrue(labels(model).none { it.contains("sign in", ignoreCase = true) })
    }

    @Test
    fun `a sign-in is not asked for when there is nothing waiting`() {
        // A flag with an empty queue is stale, not a prompt — Android's `signInPrompt` takes
        // the same reading, and for the same reason: it would refer to captures that no
        // longer exist.
        val model = signedIn.copy(pending = 0, queueNeedsSignIn = true)
        assertTrue(labels(model).none { it.contains("sign in", ignoreCase = true) })
    }

    @Test
    fun `not signed in, the account line is status and is pressable`() {
        val account = row(signedIn.copy(signedInAs = null), TrayAction.SIGN_IN)
        assertTrue(account is TrayItem.Status, "the account line is status in every state")
    }

    @Test
    fun `signed in, the account names the account and signing out is a separate action`() {
        // SRS 1.82: the account line used to *be* the sign-out button, so an act with a
        // consequence sat behind a label that reads as information.
        val account = trayMenu(signedIn).filterIsInstance<TrayItem.Status>()
            .single { it.label.contains("a@example.com") }
        assertEquals(null, account.id, "the account line must not sign the user out")
        assertTrue(row(signedIn, TrayAction.SIGN_OUT) is TrayItem.Action, "signing out is an action")
    }

    @Test
    fun `signing out is not offered when there is nobody to sign out`() {
        assertTrue(trayMenu(signedIn.copy(signedInAs = null)).none { it.id == TrayAction.SIGN_OUT })
        assertTrue(trayMenu(signedIn.copy(configured = false)).none { it.id == TrayAction.SIGN_OUT })
    }

    // ---- the counts ----------------------------------------------------------------------

    @Test
    fun `a queue with nothing in it is not mentioned at all`() {
        // FR-704's instinct, one client over: a count of zero is a thing to say nothing about.
        assertTrue(labels(signedIn).none { it.contains("waiting") }, labels(signedIn).toString())

        val busy = signedIn.copy(pending = 3)
        assertTrue(labels(busy).any { it.contains("3 waiting") }, labels(busy).toString())
        assertEquals(TrayAction.RETRY, row(busy, TrayAction.RETRY).id, "the count must be pressable")
    }

    @Test
    fun `a stuck entry is named apart from one that is merely waiting`() {
        // The one queue state the user has to act on. An entry retries have stopped for is
        // never deleted — it holds a capture that exists nowhere else — so the only way it ever
        // moves is if they are told it is there.
        val stuck = signedIn.copy(givenUp = 2)
        assertTrue(labels(stuck).any { it.contains("2 stuck") }, labels(stuck).toString())

        val label = row(signedIn.copy(pending = 1, givenUp = 2), TrayAction.RETRY).label!!
        assertTrue(label.contains("1 waiting"), label)
        assertTrue(label.contains("2 stuck"), label)
    }

    @Test
    fun `every count is status, and every count is pressable`() {
        // The dogfooding finding this slice answers: "3 waiting in the Inbox" had opened the
        // Inbox since the day it was written, and nothing about it suggested a press would.
        val busy = signedIn.copy(pending = 2, inbox = 3)
        listOf(TrayAction.RETRY, TrayAction.INBOX).forEach { action ->
            val item = row(busy, action)
            assertTrue(item is TrayItem.Status, "$action should read as status")
            assertEquals(action, item.id, "$action must be pressable")
        }
    }

    @Test
    fun `the Inbox count sits below the queue and neither is shown at zero`() {
        assertTrue(trayMenu(signedIn).none { it.id == TrayAction.INBOX })

        val busy = labels(signedIn.copy(pending = 2, inbox = 3))
        assertTrue(busy.any { it == "3 waiting in the Inbox" }, busy.toString())
        assertTrue(
            busy.indexOfFirst { "to be written" in it } < busy.indexOfFirst { "in the Inbox" in it },
            "a capture on its way to Google comes before one waiting on the user",
        )
    }

    // ---- the grouping --------------------------------------------------------------------

    @Test
    fun `four groups, in the order something-to-do, how-things-stand, windows, leaving`() {
        val items = trayMenu(signedIn.copy(pending = 2, inbox = 3))
        val groups = mutableListOf(mutableListOf<TrayItem>())
        items.forEach { if (it is TrayItem.Separator) groups.add(mutableListOf()) else groups.last().add(it) }

        assertEquals(4, groups.size, items.mapNotNull { it.label }.toString())
        assertEquals(listOf(TrayAction.CAPTURE), groups[0].map { it.id })
        assertTrue(groups[1].all { it is TrayItem.Status }, "the second group is where things stand")
        assertEquals(
            listOf(TrayAction.RECIPES, TrayAction.SETTINGS, TrayAction.SIGN_OUT),
            groups[2].map { it.id },
        )
        assertEquals(listOf(TrayAction.QUIT), groups[3].map { it.id })
    }

    @Test
    fun `the groups survive every state the menu has`() {
        // A rule that appeared only when a count did would give an empty group, or two rules
        // together. The rules are structural; only the rows inside them come and go.
        listOf(
            signedIn,
            signedIn.copy(signedInAs = null),
            signedIn.copy(configured = false, signedInAs = null),
            signedIn.copy(pending = 4, givenUp = 1, inbox = 2),
        ).forEach { model ->
            val items = trayMenu(model)
            assertEquals(3, items.count { it is TrayItem.Separator }, model.toString())
            assertTrue(items.first() !is TrayItem.Separator, model.toString())
            assertTrue(items.last() !is TrayItem.Separator, model.toString())
            assertTrue(
                items.zipWithNext().none { (a, b) -> a is TrayItem.Separator && b is TrayItem.Separator },
                "two rules together means an empty group: $model",
            )
        }
    }

    @Test
    fun `quit is always the last thing offered`() {
        listOf(0, 3).forEach { pending ->
            assertEquals(TrayAction.QUIT, trayMenu(signedIn.copy(pending = pending)).last().id)
        }
    }

    // ---- the glyphs ----------------------------------------------------------------------

    @Test
    fun `no label carries a character beyond the one the menu deliberately relies on`() {
        // SRS 1.82's second defect: "Settings…" drew as "Settings❑", because AWT's own menu
        // font had no U+2026. Swing draws the menu now, in the system menu font, so the
        // ellipsis is a decision rather than luck. What this pins is the *set*: a third exotic
        // character cannot arrive unnoticed and then hide in the "stuck" row, which is almost
        // never on screen. The em dash went for exactly that reason.
        val everything = listOf(
            signedIn.copy(pending = 4, givenUp = 2, inbox = 3),
            signedIn.copy(signedInAs = null),
            signedIn.copy(configured = false, signedInAs = null),
        ).flatMap { labels(it) }

        val exotic = everything.flatMap { it.toList() }.filter { it.code > 127 }.toSet()
        assertEquals(setOf('…'), exotic, "unexpected non-ASCII in a tray label: $exotic")
    }

    @Test
    fun `the icon is drawn at whatever size the tray asks for`() {
        // Windows asks for different sizes on a mixed-DPI desktop, which is why it is drawn
        // rather than shipped as one bitmap.
        listOf(16, 20, 24, 32, 48).forEach { size ->
            val image = latchIcon(size)
            assertEquals(size, image.width)
            assertEquals(size, image.height)
        }
    }

    @Test
    fun `the popup's invoker is a Dialog, because a plain Window cannot take focus`() {
        // SRS 1.196, and **this pins a JDK contract rather than a behaviour** — deliberately,
        // because the behaviour is Swing focus and no JVM test on this client reaches it.
        //
        // The menu never dismissed because the invoker was a `JWindow`.
        // `Window.isFocusableWindow()` refuses a plain `Window` twice over: it wants a focusable
        // component in the window's own traversal cycle, and a one-pixel invoker has none; and
        // it wants the nearest owning Frame or Dialog to be *showing*, where Swing's shared
        // owner frame never is. So `requestFocus()` did nothing, focus was never held, and
        // `windowLostFocus` could never fire. A `Frame` or `Dialog` short-circuits both.
        //
        // What this asserts is the one decision that fixed it, so a later change back to
        // `JWindow` — which compiles, runs, and looks identical until someone clicks away —
        // fails here instead of in a user's tray.
        val field = LatchTray::class.java.getDeclaredField("invoker")
        assertTrue(
            java.awt.Dialog::class.java.isAssignableFrom(field.type),
            "the invoker is ${field.type.simpleName}; a Window that is not a Frame or Dialog " +
                "cannot take focus, so the menu would never dismiss",
        )
    }
}
