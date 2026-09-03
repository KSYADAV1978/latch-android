package com.latch.desktop.store

/**
 * A cipher that is not one, so every store's format is testable without a Windows.
 *
 * It reverses the bytes, which makes it its own inverse, and it speaks the real bridge's
 * protocol — base64 in, base64 out, **one reply line per request line**. A fake that answered
 * in a shape the real one never produces would pass while the real path was broken, which is
 * the failure this project has already met once in `findEventBySourceHash`.
 *
 * [refuses] stands in for a record this machine's key cannot open: another user's, or one
 * restored from another machine. It is a set rather than a flag because the property being
 * tested is that one refusal does **not** cost its neighbours, and a fake that failed the whole
 * batch could not show that.
 */
fun reversingSecrets(refuses: Set<String> = emptySet()): WindowsSecrets =
    WindowsSecrets { mode, payloads ->
        BridgeReply(
            payloads.joinToString(separator = "\n") { payload ->
                if (mode == WindowsSecrets.Mode.UNPROTECT && payload in refuses) "ERR no"
                else "OK " + base64(unbase64(payload).reversedArray())
            },
        )
    }

/** A bridge that refuses everything, for the paths where Windows will not encrypt at all. */
fun refusingSecrets(): WindowsSecrets =
    WindowsSecrets { _, payloads -> BridgeReply(payloads.joinToString("\n") { "ERR no" }) }
