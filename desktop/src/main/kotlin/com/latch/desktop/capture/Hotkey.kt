package com.latch.desktop.capture

/**
 * FR-302's hotkey, as `RegisterHotKey` needs it.
 *
 * Pure, and separate from the process that registers it, because a hotkey that is written down
 * wrongly fails in the least visible way there is: nothing happens when the user presses it,
 * and there is no error to read. Parsing it is where that goes wrong, so parsing it is what
 * has tests.
 */
data class HotkeySpec(val modifiers: Int, val virtualKey: Int, val display: String) {
    companion object {
        const val MOD_ALT: Int = 0x0001
        const val MOD_CONTROL: Int = 0x0002
        const val MOD_SHIFT: Int = 0x0004
        const val MOD_WIN: Int = 0x0008

        /**
         * Windows repeats a held hotkey. Without this the user holding the combination for a
         * moment opens a dozen capture windows, each of which reads the clipboard and one of
         * which they have to dismiss a dozen times.
         */
        const val MOD_NOREPEAT: Int = 0x4000

        /** FR-302's default. */
        const val DEFAULT: String = "Ctrl+Shift+K"
    }
}

/** Why a hotkey string could not be used. Named, so the UI phrases it (NFR-402). */
enum class HotkeyProblem { EMPTY, NO_KEY, UNKNOWN_KEY, NO_MODIFIER, TOO_MANY_KEYS }

sealed interface HotkeyParse {
    data class Parsed(val spec: HotkeySpec) : HotkeyParse
    data class Rejected(val problem: HotkeyProblem, val detail: String = "") : HotkeyParse
}

/**
 * Reads `Ctrl+Shift+K` and its like.
 *
 * **A modifier is required, and that is a decision rather than a limitation of the API.**
 * Windows will happily register a bare `K` as a system-wide hotkey, at which point the letter
 * K stops working in every other application on the machine. Refusing it here is cheaper than
 * the support conversation that follows.
 */
fun parseHotkey(text: String): HotkeyParse {
    val parts = text.split('+').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return HotkeyParse.Rejected(HotkeyProblem.EMPTY)

    var modifiers = 0
    val keys = mutableListOf<String>()
    parts.forEach { part ->
        when (part.lowercase()) {
            "ctrl", "control" -> modifiers = modifiers or HotkeySpec.MOD_CONTROL
            "shift" -> modifiers = modifiers or HotkeySpec.MOD_SHIFT
            "alt" -> modifiers = modifiers or HotkeySpec.MOD_ALT
            "win", "windows", "meta" -> modifiers = modifiers or HotkeySpec.MOD_WIN
            else -> keys += part
        }
    }

    if (keys.isEmpty()) return HotkeyParse.Rejected(HotkeyProblem.NO_KEY)
    if (keys.size > 1) return HotkeyParse.Rejected(HotkeyProblem.TOO_MANY_KEYS, keys.joinToString("+"))
    if (modifiers == 0) return HotkeyParse.Rejected(HotkeyProblem.NO_MODIFIER, keys.first())

    val virtualKey = virtualKeyOf(keys.first())
        ?: return HotkeyParse.Rejected(HotkeyProblem.UNKNOWN_KEY, keys.first())

    val display = buildList {
        if (modifiers and HotkeySpec.MOD_CONTROL != 0) add("Ctrl")
        if (modifiers and HotkeySpec.MOD_ALT != 0) add("Alt")
        if (modifiers and HotkeySpec.MOD_SHIFT != 0) add("Shift")
        if (modifiers and HotkeySpec.MOD_WIN != 0) add("Win")
        add(displayKey(keys.first()))
    }.joinToString("+")

    return HotkeyParse.Parsed(
        HotkeySpec(modifiers or HotkeySpec.MOD_NOREPEAT, virtualKey, display)
    )
}

/**
 * How a key is written on screen.
 *
 * A single character and a function key are upper case — `K`, `F12` — and anything else is
 * capitalised, so the hint reads `Ctrl+Space` rather than `Ctrl+SPACE`. It is a label a user
 * reads in Settings and in a tray tooltip, not an identifier.
 */
internal fun displayKey(key: String): String {
    val name = key.trim()
    if (name.length <= 1) return name.uppercase()
    if (name.uppercase().matches(Regex("F([1-9]|1[0-9]|2[0-4])"))) return name.uppercase()
    return name.first().uppercase() + name.drop(1).lowercase()
}

/**
 * The Win32 virtual-key code for a key's name.
 *
 * Only the keys a person would sensibly bind a capture to. The set is deliberately small: a
 * key that is not here is refused by name, which the user can read, rather than mapped to a
 * guess that registers something else.
 */
internal fun virtualKeyOf(key: String): Int? {
    val name = key.uppercase()
    if (name.length == 1) {
        val character = name[0]
        if (character in 'A'..'Z') return character.code
        if (character in '0'..'9') return character.code
    }
    if (name.startsWith("F") && name.length in 2..3) {
        name.drop(1).toIntOrNull()?.let { number ->
            if (number in 1..24) return 0x70 + number - 1
        }
    }
    return when (name) {
        "SPACE" -> 0x20
        "INSERT", "INS" -> 0x2D
        "DELETE", "DEL" -> 0x2E
        "HOME" -> 0x24
        "END" -> 0x23
        "PAGEUP", "PGUP" -> 0x21
        "PAGEDOWN", "PGDN" -> 0x22
        else -> null
    }
}
