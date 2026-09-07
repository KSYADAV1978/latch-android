package com.latch.android.ui

/**
 * How tall a floating capture sheet may be — **a guard, not the cure** (SRS 1.182).
 *
 * `CaptureActivity` is a floating dialog, so `windowIsFloating` makes the window WRAP_CONTENT
 * and the composition is measured with no maximum height at all. Everything that bounds these
 * sheets therefore has to be stated; nothing is inherited. That was survivable for as long as
 * every part of the sheet that grows with the capture lived inside the scrolling column — and
 * SRS 1.182 is what happened when one part did not.
 *
 * **This function is deliberately not the fix for that.** The fix was to put the growing part
 * back inside the scroll. What this adds is the property the sheet lacked entirely: an upper
 * bound, so that `weight(1f, fill = false)` on the content column has a real remainder to
 * compute and yields space to the action row instead of computing its share from infinity. With
 * the nesting correct the bound is inert in every case measured on a device; it exists so that
 * the *next* thing to grow outside the scroll is squeezed rather than silently clipped.
 *
 * [SHEET_WINDOW_CHROME_DP] is what the dialog cannot use: the status bar plus the margins a
 * floating window keeps around itself. It is subtracted rather than taken as a percentage
 * because the quantity really is a fixed inset. A bound that does not bind is indistinguishable
 * from no bound at all, which is why the test asserts that it binds rather than that it exists.
 */
internal fun sheetMaxHeightDp(screenHeightDp: Int): Int =
    (screenHeightDp - SHEET_WINDOW_CHROME_DP).coerceAtLeast(SHEET_MIN_HEIGHT_DP)

/**
 * Measured on the device this was diagnosed on: a screen of 891dp gave a largest-possible sheet
 * window of 826dp, so the chrome is 65dp. Rounded up, because being a few dp too generous
 * restores the old behaviour exactly and being a few dp too mean costs a few dp of a column
 * that scrolls anyway.
 */
private const val SHEET_WINDOW_CHROME_DP = 96

/**
 * A floor, so a very short screen or a very large display size cannot drive the bound below the
 * space the fields themselves need.
 */
private const val SHEET_MIN_HEIGHT_DP = 320
