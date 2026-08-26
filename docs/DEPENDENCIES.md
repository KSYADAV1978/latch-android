# Dependency justifications (NFR-501)

NFR-501: third-party dependencies shall be minimised and each justified in writing. Every
dependency is a maintenance obligation on a product with no revenue (FR-1107), so the
default answer is no and this file records the exceptions.

## Measured APK impact

Release build, R8 and resource shrinking enabled, each dependency measured on its own
against a re-measured baseline of **773,448 bytes**. The measurement used realistic usage —
an entity, DAO and database for Room; a worker enqueued at startup for WorkManager — so that
R8 could not strip what was being measured. NFR-103 budget: 40 MB.

| Dependency | Version | Universal APK | Per device (App Bundle) | Decision |
|---|---|---|---|---|
| `androidx.work:work-runtime-ktx` | 2.11.2 | +118 KB | +118 KB | **Approved** |
| `androidx.room` | 2.8.4 | +36 KB | +36 KB | **Deferred** |
| `net.zetetic:sqlcipher-android` | 4.18.0 | +7.34 MB | ~2.0 MB (arm64-v8a) | **Rejected** |
| All three together | | +7.47 MB | ~2.2 MB | — |

SQLCipher's weight is one native library per ABI: arm64-v8a 2.00 MB, armeabi-v7a 1.00 MB,
x86 2.14 MB, x86_64 2.13 MB. Play delivers only the device's ABI, so 2.0 MB is the honest
figure and 7.34 MB is the universal-APK worst case.

None of this is the binding constraint on NFR-103. On-device OCR (FR-215) is unmeasured and
will dominate the budget — ML Kit's bundled text recognition models are the largest single
item this app will ever ship. Measure it before treating the 40 MB as comfortable.

## Approved

### `androidx.work` — the offline write queue (FR-806, NFR-302)

NFR-302 requires that no capture is lost to network failure, app termination or device
restart while queued. That is precisely WorkManager's contract: persisted work, a constraint
on connectivity, and survival across reboot.

The alternative is `JobScheduler` plus a `BOOT_COMPLETED` receiver plus retry and backoff
written by hand — the same behaviour for roughly the same size, in a place where the failure
mode is silent data loss. 118 KB is a good price for not writing that ourselves.

Two notes for whoever implements it. WorkManager keeps its own SQLite database, so adopting
it does not commit the project to Room. And webhook delivery (FR-1004) must **never** enter
this queue: FR-1004b makes it a single best-effort attempt at save time, and AC-21 tests
exactly the consequence — an item saved offline reaches Google on reconnection and no
webhook is ever sent for it.

## Deferred

### `androidx.room` — Capture Inbox and write queue storage (FR-701, FR-806, §7.1)

Room is the right long-term answer for the seven entities in §7.1, with their relations,
queries and migrations across app versions. It is deferred because of what it costs the
build, not what it costs the APK.

**Room needs KSP, and KSP is incompatible with AGP 9's built-in Kotlin support.** Adopting it
today forces all of the following, each verified working together in this repo:

- `android.builtInKotlin=false` and `android.newDsl=false` in `gradle.properties`
- the external Kotlin Android plugin at 2.4.10 or later — 2.2.21 fails, because AGP 9's DSL
  extension cannot be cast to the `BaseExtension` that older versions expect
- an explicit `jvmTarget` on every Android module, which built-in Kotlin had been inferring

That is a deliberate step backwards in the build configuration to gain 36 KB of convenience.
Until the Capture Inbox is actually being built, the cheaper position is to stay on built-in
Kotlin and revisit when AGP supports KSP there. If the Inbox lands first, hand-rolled
`SQLiteOpenHelper` over two tables is a reasonable interim and costs nothing.

## Rejected

### `net.zetetic:sqlcipher-android` — database encryption (NFR-204)

Not required. NFR-204 asks for the Capture Inbox to be stored in app-private storage and
encrypted at rest; at the minimum SDK fixed by FR-516 the platform already encrypts
app-private storage, and `android:allowBackup="false"` with the committed data-extraction
rules keeps it out of cloud backup and device transfer. The reading is recorded against
NFR-204 in the SRS so that it is visible rather than implied.

SQLCipher would add protection in one scenario the platform does not cover — an attacker
with root access on an unlocked device reading the database file directly — for 2.0 MB per
device. It also moves key management rather than solving it: the passphrase would have to
live in the same secure store as the OAuth tokens (NFR-203).

**Revisit if the threat model ever includes a rooted device.** That is the condition under
which this decision changes, and it is recorded in the SRS alongside NFR-204.

## Standing rule

Anything not listed here has not been justified and should not be added. Ask first.
