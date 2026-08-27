# Dependency justifications (NFR-501)

NFR-501: third-party dependencies shall be minimised and each justified in writing. Every
dependency is a maintenance obligation on a product with no revenue (FR-1107), so the
default answer is no and this file records the exceptions.

## Measured APK impact

Release build, R8 and resource shrinking enabled, each dependency measured on its own
against a re-measured baseline of **773,448 bytes**. The measurement used realistic usage —
an entity, DAO and database for Room; a worker enqueued at startup for WorkManager — so that
R8 could not strip what was being measured. NFR-103 budget: 40 MB.

The 773,448-byte baseline is from that measuring session and is not re-derivable now — the
app has grown since. Later rows say which baseline they were taken against; the coroutines
row was measured against 892,134 bytes, the app as it stood when first-run setup was
persisted. Comparing a later row's delta to an earlier row's is fine; comparing the
baselines is not.

| Dependency | Version | Universal APK | Per device (App Bundle) | Decision |
|---|---|---|---|---|
| `androidx.work:work-runtime-ktx` | 2.11.2 | +118 KB | +118 KB | **Approved** |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.9.0 | 0 | 0 | **Approved** |
| `com.google.android.gms:play-services-auth` | 22.0.0 | +135 KB | +135 KB | **Approved** |
| `androidx.room` | 2.8.4 | +36 KB | +36 KB | **Deferred** |
| `net.zetetic:sqlcipher-android` | 4.18.0 | +7.34 MB | ~2.0 MB (arm64-v8a) | **Rejected** |
| `org.jetbrains.kotlin:kotlin-test-junit5` | 2.2.21 | test-only, 0 | test-only, 0 | **Approved** |
| `org.json:json` | 20260814 | test-only, 0 | test-only, 0 | **Approved** |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.9.0 | test-only, 0 | test-only, 0 | **Approved** |
| `androidx.test:core` | 1.6.1 | instrumented-only, 0 | instrumented-only, 0 | **Approved** |
| `androidx.test:runner` | 1.6.2 | instrumented-only, 0 | instrumented-only, 0 | **Approved** |
| `androidx.test.ext:junit` | 1.2.1 | instrumented-only, 0 | instrumented-only, 0 | **Approved** |
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

### `org.jetbrains.kotlinx:kotlinx-coroutines-core` — main-safe storage in `:data`

`:data`'s contracts are `suspend` throughout, which the stdlib alone supports — a module can
declare `suspend fun` with no coroutines dependency at all, and `:data` did. What it cannot
do is honour the promise. `SharedPreferences.commit()` and every Keystore call are blocking,
so those functions blocked whichever thread called them while advertising, by their
signature, that they were safe to call from anywhere. That is worse than a plain blocking
function, which at least looks like one.

The alternative was to leave the dispatcher to callers and document it, which is what the
first version did. It does not survive contact with a second caller: the guarantee lives in
a comment, the compiler cannot check it, and the failure mode is a main-thread disk write
that shows up as jank on someone else's device. `withContext(Dispatchers.IO)` inside each
method moves it into the type system's reach and makes cancellation work as a caller would
expect.

**Measured impact: zero.** Release APK 892,134 bytes before and after, R8 and resource
shrinking on. The library was already on the release runtime classpath at this exact
version, pulled in transitively by `androidx.activity:activity-compose` through
`lifecycle-runtime-ktx`, so the declaration ships no code that was not already shipping.
The only real growth is 208 bytes of uncompressed dex, which is the `withContext` call
sites in `:data` and not the library. That is also why the version is pinned to 1.9.0 in
`gradle/libs.versions.toml`: it is what androidx already resolves, and naming a higher one
would upgrade the whole graph as a side effect of a `:data` declaration.

`implementation`, not `api`. Nothing in the storage contracts exposes a coroutines type;
only the implementations need a dispatcher to move to.

Note for whoever adds the next Android module: this is `-core`, not `-android`.
`Dispatchers.Main` comes from the `-android` artifact, which `:app` already has
transitively. `:data` does not touch the main dispatcher and should not start.

### `com.google.android.gms:play-services-auth` — the OAuth grant (FR-002, FR-101)

Setup cannot reach Google without an access token, and this is the library that produces one
on Android. Used for the grant and nothing else: `AuthorizationRequest` with the four FR-002
scopes, `Identity.getAuthorizationClient(...).authorize(...)`, and the `PendingIntent`
resolution when the user has not yet consented. Every Google call the app makes is
hand-written REST on top of the token it returns.

**Measured impact: +138,330 bytes, ~135 KB.** Release APK 892,134 → 1,030,464 bytes with R8
and resource shrinking on, against a baseline re-measured on `main` immediately before the
spike. Uncompressed dex 1,506,464 → 1,743,560; APK entries 81 → 95. The spike used realistic
usage — a reachable `authorize()` call — so R8 could not strip what was being measured, per
the methodology above.

**Universal and per-device are the same figure**: play-services-auth ships no native code.
The four `libandroidx.graphics.path.so` files in the APK are Compose's, present byte-identical
before and after, and traceable to `androidx.compose.ui:ui-graphics`. There is no ABI split to
account for here, unlike SQLCipher below.

The alternative is a hand-rolled authorization-code flow with PKCE over Custom Tabs: a
redirect scheme, a token exchange, and a refresh token we would then have to store under
NFR-203 and rotate ourselves. That is more security-critical code that we own, in a place
where a mistake is an account compromise rather than a crash, and it gives up the package-name
plus SHA-1 binding that makes the Android OAuth client hard to impersonate. 135 KB is a good
price for not writing that.

Two notes for whoever implements it. **The Android OAuth client ID is a Cloud-console fact and
must never appear in source** — `AuthorizationRequest.builder()` takes scopes only, and a
client ID is an argument solely to `requestOfflineAccess(serverClientId)`, which takes a *Web*
client and which this app must never call: there is no backend to exchange the resulting code
at (design principle 3). GMS resolves the Android client from the package name and signing
fingerprint at `authorize()` time. And **`kotlinx-coroutines-play-services` is deliberately not
taken** — bridging `Task<AuthorizationResult>` to a suspend function is about ten lines of
`suspendCancellableCoroutine`, which is not worth a fourth runtime dependency.

### `org.jetbrains.kotlin:kotlin-test-junit5` — unit tests in `:app` (test-only)

Not a third-party dependency in any meaningful sense: it is the framework-bound variant of
`kotlin-test`, which `:parser` and `:recipes` already use, shipped by Kotlin at the same
version as the compiler. `testImplementation` only, so **zero APK impact**.

It has to be named explicitly here because the Kotlin JVM plugin picks the variant from the
test task and AGP does not. Without it, `kotlin.test.Test` does not resolve in an Android
module's unit tests, while the assertion functions do — a confusing half-failure worth
recording so the next person does not re-diagnose it.

The tests it carries are the ones that hold FR-105 in place: AC-15 (the Latch calendar is
created on completion of setup) and AC-16 (abandoning setup creates nothing) are plain JVM
tests, because the setup state machine has no Android types.

### `org.json:json` — JSON parsing in `:data` unit tests (test-only)

Like `kotlin-test-junit5`, barely a dependency: it is the reference implementation of the
`org.json` API that **already ships inside `android.jar`**, so the app itself pulls in nothing
and the APK does not move. It is `testImplementation` on `:data` only.

It has to be named because of how AGP builds unit tests. The `android.jar` on the unit-test
classpath is a stub whose every method throws `RuntimeException("Stub!")`, `org.json` included.
Without a real implementation the response mappers in `GoogleRest.kt` cannot be tested off a
device at all — and those mappers are where a silent defect lives, exactly as the stored record
format was in `EncryptedPreferences.kt`. FR-901's role filter, FR-902's colour and FR-903's
`selected` are all decided in that code.

The alternative is `testOptions.unitTests.isReturnDefaultValues = true`, and it is worse. It
silences the stub by making it return `null` and `0` rather than throwing, so a mapper reading
a field would quietly see nothing and the test would pass on an empty result. It converts the
bug class we are trying to catch into a green build.

**On the licence**, since it has a history: `org.json:json` carried the JSON License — the one
with the "shall be used for Good, not Evil" clause that Apache, Debian and the FSF all refused
— up to version 20220924, at which point it was **released into the public domain**. This entry
pins 20260814, long past that change, so the clause does not apply. Worth recording because
anyone who remembers the old objection will otherwise raise it again.

### `org.jetbrains.kotlinx:kotlinx-coroutines-test` — virtual time for FR-807 (test-only)

`testImplementation` on `:app` only. **Measured impact: zero.** The release APK is
1,064,828 bytes with this dependency declared and 1,064,828 bytes with the line removed and
nothing else changed — byte for byte identical, measured against that build rather than
asserted from the configuration name. (That 1,064,828 is the app *with* FR-807 undo in it;
the same tree before the feature was 1,047,872, so the feature is +16,956 and the dependency
is none of it.)

It exists for one thing: FR-807 requires an undo offer of **not less than ten seconds**, and
`CaptureSaver` closes that offer with a `delay`. `runTest` runs that delay on a virtual
clock, so `CaptureSaverTest` waits the window out in microseconds and can assert what
happens on both sides of it — that the offer lapses on its own, that lapsing deletes nothing,
and that an undo arriving after it does nothing. Without virtual time the honest version of
those three tests takes ten seconds each and the dishonest version shortens the window for
tests, which would mean the constant the requirement names is not the one under test.

It is the same artifact family as `kotlinx-coroutines-core`, already approved and already on
the app classpath, published by JetBrains at the version this project pins — so this is a
test-scoped sibling of an existing dependency rather than a new supplier.

**What it should not become.** This buys a clock, not a test framework. `runTest` also brings
`TestDispatcher` injection and `Turbine`-shaped flow assertions are a short step away; both
are a new NFR-501 decision, not an extension of this one. The saver's decisions are pure
functions — `undoOffer`, `saveIsOffered`, `saveBlocker` — precisely so that most of what is
worth asserting needs no coroutine machinery at all.

### `androidx.test:core`, `androidx.test:runner`, `androidx.test.ext:junit` — the launch canary

Three artifacts for one test, on `androidTestImplementation`. They build the separate
androidTest APK and are **never linked into the app's**, so the release APK is unmoved —
the same standing as the two test-only entries above, one step further out.

They exist because of a defect that shipped through three commits: `LatchApplication` built a
`Context`-dependent field in a property initializer, which runs before `attachBaseContext`,
so the app could not start at all. The build was green and 84 unit tests passed throughout.
That is not a gap in the tests, it is a gap no JVM test can close — a unit test never
instantiates `Application`, never calls `attachBaseContext` and never resolves a `Context`,
which is precisely the property that lets the parser corpus and the FR-105 reducer tests run
in milliseconds without a device. Android's initialisation order is invisible from there, and
only a device can see it.

So: `app/src/androidTest/.../LaunchCanaryTest.kt`, one test, asserting `MainActivity` reaches
RESUMED. **Verified to fail on the bug it was written for** — reintroducing the initializer
turns the run red with `Unable to instantiate application`, before any test body executes.

`androidx.test:core` supplies `ActivityScenario`, `runner` supplies `AndroidJUnitRunner`, and
`ext:junit` supplies the `AndroidJUnit4` runner class. `core` is named explicitly rather than
taken transitively through `ext:junit`, for the reason recorded under `kotlin-test-junit5`
below: a dependency whose classes we import should be one we declare.

**This is a canary, not a test layer, and the distinction is the justification.** Espresso,
Compose UI test and a fixture harness are all deliberately absent, and adding them is a new
NFR-501 decision rather than an extension of this one. Assertions about behaviour belong in
the reducer tests, where they are free and cannot flake — `SetupStateTest` already holds
AC-15 and AC-16 that way. The only thing bought here is the knowledge that the app gets off
the ground.

Two operational notes. It does **not** run under `./gradlew build`; it needs a device and the
task is `connectedDebugAndroidTest`, so it catches nothing unless someone runs it — there is
no CI in this repo to run it for us. And it requires no network and no Google account: the
activity renders either setup step 1 or the home screen, and neither touches the network
until a tap.

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

### `androidx.security:security-crypto` — encrypted preferences (FR-110, NFR-203)

Considered for persisting account defaults, which until now lived in memory and made every
launch re-run setup. `EncryptedSharedPreferences` is the obvious fit and was not taken.

Two reasons, either sufficient. It is **deprecated** — Jetpack Security Crypto was
deprecated in 2025 with the guidance being to use the platform Keystore APIs directly, so
adopting it now means adopting something already on its way out, and NFR-501 counts a
dependency as an ongoing obligation (FR-1107). And what it does here is small enough to
own: an AES-256-GCM key in the Android Keystore, ciphertext base64'd into an app-private
preferences file. That is `KeystoreCipher` in `data/.../EncryptedPreferences.kt`, about
fifty lines, using only `javax.crypto` and `android.security.keystore`.

**Not measured, because it was not adopted.** For reference the change that replaced it
moved the release APK not at all: 892,134 bytes before and after, R8 and resource shrinking
on. (That figure is not comparable to the 773,448-byte baseline in the table above — the app
has grown since it was taken.)

The same code is the answer for NFR-203 when the secret store is built. OAuth tokens and the
FR-1004 webhook URL should reuse `KeystoreCipher` rather than introduce a second scheme.

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
