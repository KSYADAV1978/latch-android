package com.latch.android.ui

/**
 * How tall a floating capture sheet may be (SRS 1.181).
 *
 * **`CaptureActivity` is a floating dialog, so the composition is measured with no maximum
 * height at all** — `windowIsFloating` makes the window WRAP_CONTENT. Everything that bounds
 * these sheets therefore has to be stated; nothing is inherited. `SHEET_CONTENT_MAX` bounds the
 * *scrolling* column, which is why a long list of fields has always worked. It does not bound
 * the sheet, and the action row sits deliberately outside it so it cannot scroll away — so a
 * sheet is `chrome + content + actions`, and only the middle term has a ceiling.
 *
 * That is enough for as long as the action row is one line. An FR-1231 offer makes it four:
 * *Close*, *Save as a new contact* and *Update the contact* are long labels in a `FlowRow` on a
 * 411dp dialog. Measured on a device, the sheet then wants **826dp** — 88 of chrome, 520 of
 * content and **218 of actions** — and 826dp is the tallest window this screen can give it, so
 * the last button was laid out past the edge and the platform clipped it away. Not scrolled
 * off: *gone*, with nothing on screen to say so.
 *
 * **Bounding the sheet is what makes `weight(1f, fill = false)` work at last.** The content
 * column already asks for what is left after the actions; until now "what is left" was computed
 * from an infinite maximum and so was infinite too. Given a real one it yields the thirty-odd
 * dp the wrapped row needs, becomes scrollable, and every control stays inside the window.
 *
 * [SHEET_WINDOW_CHROME_DP] is what the dialog cannot use: the status bar plus the margins a
 * floating window keeps around itself. It is subtracted rather than approximated with a
 * percentage because the quantity really is a fixed inset, not a share of the screen — and a
 * bound that does not actually bind is indistinguishable from no bound at all, which is the
 * failure this function exists to make impossible to reintroduce silently.
 */
internal fun sheetMaxHeightDp(screenHeightDp: Int): Int =
    (screenHeightDp - SHEET_WINDOW_CHROME_DP).coerceAtLeast(SHEET_MIN_HEIGHT_DP)

/**
 * Measured on the device this was diagnosed on: a screen of 891dp gave a largest-possible sheet
 * of 826dp, so the chrome is 65dp. Rounded up, because being a few dp too generous restores the
 * old behaviour exactly and being a few dp too mean costs a few dp of a column that scrolls.
 */
private const val SHEET_WINDOW_CHROME_DP = 96

/**
 * A floor, so a very short screen or a very large display size cannot drive the bound to nothing
 * and leave a sheet with no room for its own fields.
 */
private const val SHEET_MIN_HEIGHT_DP = 320
