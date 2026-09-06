# Dependency justifications (NFR-501)

NFR-501: third-party dependencies shall be minimised and each justified in writing. Every
dependency is a maintenance obligation on a product with no revenue (FR-1107), so the
default answer is no and this file records the exceptions.

**FR-1101 is enforced by this list and nowhere else.** The requirement is that the app contains
no advertising, no in-app purchase and no paid tier — and on Android all three arrive as
dependencies: Play Billing, an ad SDK, an analytics SDK to measure either. There is no code to
inspect for their absence, because absence is not a thing code says; what says it is that no
such artifact appears below, and that NFR-501 makes adding one a written decision rather than a
line in a build file. A future reader checking FR-1101 should read this table, not the source.

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
| `com.google.mlkit:text-recognition-devanagari` (bundled) | 16.0.1 | +41.35 MB (dev artifact) | **+12.83 MB** (arm64-v8a) | **Approved, now in use** |
| `com.google.mlkit:text-recognition` (bundled, Latin) | 16.0.1 | +8 KB on top of the above | +8 KB | **Approved, now in use** — see reversal |
| ML Kit text recognition, **unbundled** (Play services) | 19.0.1 / 16.0.1 | +325 KB | +325 KB | **Rejected** — NFR-301 |
| `androidx.work:work-runtime-ktx` | 2.11.2 | +118 KB (see note) | +118 KB | **Approved, now in use** |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.9.0 | 0 | 0 | **Approved** |
| `com.google.android.gms:play-services-auth` | 22.0.0 | +135 KB | +135 KB | **Approved** |
| `androidx.room` | 2.8.4 | +36 KB | +36 KB | **Deferred** |
| `net.zetetic:sqlcipher-android` | 4.18.0 | +7.34 MB | ~2.0 MB (arm64-v8a) | **Rejected** |
| `com.google.zxing:core` | 3.5.3 | **+17,176 bytes** | **+17,176 bytes** (no native code) | **Approved, now in use** |
| `com.google.mlkit:barcode-scanning` (bundled) | 17.3.0 | +20.25 MB | ~+5.7 MB (arm64-v8a) | **Rejected** — NFR-103 |
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

None of the rows above is the binding constraint on NFR-103. **On-device OCR is, and it has
now been measured** — see the FR-215 section below. The bundled models are, as predicted, the
largest single item this app will ever ship: they are roughly thirty-six times the whole
current app on a universal APK and eleven times it per device.

## Approved

### ZXing core - QR decoding for business cards (FR-1203, SRS 5.11 Phase A)

**Approved and adopted 4 Sep 2026**, with the figure measured rather than estimated, as FR-1240
requires before any contact-write code ships.

| Variant | Universal APK | Delta | dex | Native |
|---|---|---|---|---|
| Baseline (before) | 45,155,590 B | - | 3,764,444 B | 39.16 MB across four ABIs |
| `com.google.zxing:core` 3.5.3, **reachable** | 45,172,766 B | **+17,176 B** | +48,392 B | **unchanged** |
| `com.google.mlkit:barcode-scanning` 17.3.0 | 63.31 MB | +20.25 MB | +0.96 MB | **+19.29 MB** |

**Why the per-device figure equals the universal one.** ZXing core contributes **no native library
and no asset** - the per-ABI totals are byte-identical to the baseline - so ABI splits change
nothing about its cost. That is the decisive difference from ML Kit's barcode model, whose 20.25
MB is 19.29 MB of native code across four ABIs and therefore about **+5.7 MB on the device that
installs it**, on top of the 12.83 MB the OCR models already spend against NFR-103's 40 MB budget.

**How it was measured, because an earlier figure was wrong in an instructive way.** The first
attempt added the dependency with no call site and produced a **byte-identical** APK - R8 removes
what nothing reaches - which is not a measurement of ZXing but a measurement of dead code, and SRS
1.90 records it as such rather than as a zero. A second attempt used `-keep` rules modelling a
decode and gave +16,384 B as an upper bound. The figure above is the third and honest one: taken
with `ZxingQrReader` genuinely called from the capture path, no keep rules, R8 shrinking normally.
The bound was good to within 800 bytes.

**Why core and not `zxing-android-embedded`.** That artifact carries a camera Activity and a
capture UI. FR-1201 narrows Phase A to an image the user has already shared, so what is needed is
a decoder; the UI would be weight for a path this phase does not have.

**Why not ML Kit, given `:ocr` already depends on it.** "Not a new vendor" is a real argument and
is rejected on size alone: 350 times the cost, against a budget the release APK is already close
to. It is recorded here rather than left implicit so that a future reader does not re-open it as
an obvious simplification.

**The obligation this creates.** ZXing core is a single-purpose decoder with no transitive
dependencies, which is what makes it cheap to carry; it is also unmaintained relative to its
Android wrappers. `:ocr` holds it behind `QrReader`, and `:app` names no ZXing type - the same
containment `OcrReader` gives ML Kit - so replacing it is a change to one file.



### ML Kit Text Recognition v2, bundled — on-device OCR (FR-215, FR-216, FR-207)

FR-215 requires images and PDFs to be processed with on-device OCR, "ML Kit Text Recognition
v2 or equivalent", supporting **at minimum Latin and Devanagari**. FR-216 requires it to run
entirely on device. ML Kit ships that in two forms, and the choice between them is the whole
decision — the API is identical, the import statements are identical, and only the artifact
coordinates differ.

- **Bundled** (`com.google.mlkit:text-recognition` + `:text-recognition-devanagari`). The
  native pipeline and the model files are packaged into the APK. Works on first launch, with
  no network, on a device with no Play services.
- **Unbundled** (`com.google.android.gms:play-services-mlkit-text-recognition` +
  `:play-services-mlkit-text-recognition-devanagari`). The APK carries a thin client; the
  pipeline and the models are downloaded by Play services on first use.

**Measured 28 Aug 2026**, release build, R8 and resource shrinking on, against a baseline of
**1,219,462 bytes** — `main` at commit `fff5e10`, re-measured after the spike was reverted
and reproduced byte-for-byte. Per the methodology above, the spike used realistic usage: a
real `:ocr` module wired into `CaptureActivity` behind a reachable call, so R8 could not
strip what was being measured.

| Variant | Universal APK | Delta | Per device (arm64-v8a) | Delta |
|---|---|---|---|---|
| Baseline (no OCR) | 1,219,462 | — | 1,192,166 | — |
| **Unbundled**, Latin + Devanagari | 1,551,985 | **+332,523 (+325 KB)** | 1,524,689 | **+332,523 (+325 KB)** |
| Bundled, Latin only | 44,002,604 | +42,783,142 | 14,006,192 | +12,814,026 (+12.22 MB) |
| **Bundled**, Latin + Devanagari | 44,643,418 | **+43,423,956 (+41.41 MB)** | 14,647,006 | **+13,454,840 (+12.83 MB)** |

**The universal and per-device figures diverge enormously, and which one NFR-103 means is the
decision.** Bundled ML Kit's weight is one native library per ABI — `libmlkit_google_ocr_pipeline.so`,
stored uncompressed: arm64-v8a 11,074,640, armeabi-v7a 6,789,192, x86 11,570,332, x86_64
11,636,888 — plus 1,908,759 bytes of model assets shared across all four. Play delivers only
the device's ABI, so **13.97 MB is the honest per-device figure and 42.58 MB is the
universal-APK worst case**, the same distinction recorded for SQLCipher below. Unbundled has
no ABI split at all: it ships no native code, so its two figures are the same number.

**Against NFR-103's 40 MB, the two readings give opposite answers.** Per device, bundled
lands at 13.97 MB — comfortably inside the budget and inside half of it. As a universal APK,
bundled lands at 42.58 MB and **breaches NFR-103 outright**, before a single further feature
is built. This project does not currently produce an App Bundle: there is no signing config
and no `bundle` block, so the only artifact this build makes today is the universal APK that
breaches. Adopting bundled therefore makes NFR-103 conditional on shipping an AAB rather than
an APK — which is what Play requires for new applications in any case, but it is a commitment
this project has not yet made anywhere in writing.

**One artifact was tried first, and the device pass reversed it. Both are now taken.** The
Devanagari artifact ships a **combined `gocrdevanagari_and_latin` engine** together with the
Latn, Deva **and Beng** models — verified by listing a built APK's assets — so on paper it
satisfies FR-215's "at minimum Latin and Devanagari" in one pass, and the Latin-only artifact
looked like +8,082 bytes for a recogniser nothing would call, which is what NFR-501 exists to
prevent.

**The Bengali model is not inert, and that is what the reasoning missed.** On real images the
combined engine substitutes Bengali codepoints into Latin words: `October` came back as
`০ctobe` (**U+09E6 BENGALI DIGIT ZERO**), `12,500` as `12,50০`, and `also` as `als০`. In one
fixture that broke a date range badly enough to lose a commitment silently; in another it
degraded a five-day trip into a one-day task on its last day. The dedicated Latin model has no
Bengali in its character set at all, and its Latin language model is what resolves `0ctober`
back to a word. **8,082 bytes bought a correctness fix that no amount of size reasoning could
have predicted** — which is exactly what the device pass is for, and why the escalation was
written into `OcrReader`'s KDoc rather than dismissed.

**Measured after the switch: 14,647,638 per device, +13,455,472 — +12.83 MB**, the same figure
to two decimal places as the original both-artifacts spike. The second artifact is free in
practice because the models were already shipping.

**How the two scripts are read now.** Both recognisers run over the same image and the results
are merged **per block**, not per image: any block of the Devanagari result containing a
codepoint in `U+0900–U+097F` is kept, every other region comes from the Latin pass, and a
Latin block substantially overlapping a kept Devanagari block is dropped. A Latin screenshot is
therefore read entirely by the Latin model, a Hindi one entirely by the Devanagari model, and a
mixed one per bubble. The test is applied to the Devanagari result because that is the pass
which can *prove* Devanagari is present — given Devanagari glyphs the Latin model returns
plausible Latin garbage rather than nothing, which no test on its own output could catch.

**Concurrency buys nothing measurable, and this is recorded so it is not re-litigated.** The
two passes are launched concurrently, on the expectation that wall-clock would approach the
slower pass rather than the sum. Measured over four fixtures, three runs each: **concurrent
median 1384 ms, sequential median 1380 ms** — a 4 ms difference inside a 1282–1673 ms spread.
ML Kit's recognisers evidently contend for the same native inference resource and serialise
however they are launched. The concurrent shape is kept because it is not slower, it is correct
if ML Kit ever does parallelise, and it is no more code — but it is **not** what bought the
headroom. What did is that the second pass costs only about **+200 ms**, not a second full
inference: a single pass ran 700–950 ms and two run 950–1100 ms, so most of the work is shared
setup. Worst NFR-101 observed either shape: **1673 ms against 2500 ms.**

**Devanagari is not what costs.** The second script adds **640,814 bytes (626 KB)** over
Latin alone — model assets only; the native pipeline `.so` is byte-identical in both builds
and is shared between the scripts. The expensive thing is bundling *at all*, not bundling
*two scripts*. There is consequently no meaningful middle option in which Devanagari is
dropped to save space: doing so would give up half of what FR-215 names, for 4% of the cost.
(The bundled model assets also carry a **Bengali** model, 443,176 bytes, which nothing in
this specification asks for and which cannot be excluded — it ships inside the same asset
bundle as Devanagari.)

**What the unbundled variant costs instead is a first-run network dependency**, and that is
the reason this is a decision and not an arithmetic problem. Latch is offline-first: NFR-301
requires capture to function fully offline, and FR-806's entire write queue exists so that a
capture made with no network is not lost. Under the unbundled variant a user's **first image
capture with no network fails** — not queued, not degraded, but unable to extract text at
all, because the model has not been downloaded yet. That is a hole in NFR-301 in exactly the
situation the rest of the app is built to survive, and it lands on a first-run user, who has
the least reason to give the app a second try.

**Decision, 28 Aug 2026: bundled, both scripts, and NFR-103 adopts the per-device reading.**
The per-device figure is the one that reaches a user, it is what Play delivers, and it is the
reading this file already applies to the SQLCipher measurement below. The universal APK's
42.58 MB is recorded as a **development artifact**, not as a breach. The unbundled variant is
**rejected** — not on size, which it wins by a factor of a hundred and forty, but on the
NFR-301 hole it opens: a first image capture with no network fails outright, neither queued
under FR-806 nor degraded, on the user with the least reason to try again. Its +325 KB stays
in the table above as the road not taken, so the trade is visible to whoever revisits this.

It is not free: 13.97 MB against a 40 MB budget spends roughly a third of it on one feature,
and every later slice — the Capture Inbox, Settings, Recipes UI, the notification listener —
is drawn from what is left. Recorded as **spent**, rather than discovered as missing.

**The per-device reading is conditional, and the condition is now a requirement.** It holds
only if the shipping artifact is an App Bundle, and this project has no signing config and no
`bundle` block. **SRS FR-1108** makes that a release gate: a signing configuration and bundle
block, a **bundletool-derived** per-device measurement recorded here against NFR-103 — the
figures above subtract ABI entries from a universal APK, which is the honest arithmetic
available before a bundle exists but is not the artifact Play builds — and the release
certificate's SHA-1 registered against the Android OAuth client. Written as a requirement
because the distance between this decision and that submission is months, and because whoever
ships is not necessarily whoever measured.

**The merged manifest was inspected on adoption, 28 Aug 2026, and ML Kit adds no permission
at all.** Established by merging the manifest with and without `:ocr` and diffing the two, so
it is a measurement rather than a reading of the library's documentation: the permission sets
are byte-identical, and the four WorkManager brought in remain the whole of what this app
gained from a dependency. What it does add is five components — an init `ContentProvider` that
runs before `Application.onCreate`, a component-discovery service that is never started, and
three `datatransport` components that are ML Kit's telemetry pipeline to Google's Clearcut
backend. All five are named in `app/src/main/AndroidManifest.xml`'s comment, with their AC-17,
NFR-202 and NFR-104 consequences, per the WorkManager precedent.

**NFR-101's 2.5 s budget for OCR of a full-screen image remains unmeasured** and is in the
device pass. No JVM test can see it.

**Confirmed on the capture path, 28 Aug 2026.** With the reader actually called from
`CaptureActivity`, the release APK is **14,639,556 per device — +13,447,390, or +12.82 MB**,
against the +12.83 MB the original spike measured. The two agree to within 8 KB, which is
what the methodology at the top of this file is for: a spike with a reachable call measures
what ships.

Worth keeping, because it is the shape of every later re-measurement of this dependency. In
the intermediate commit, with `:ocr` declared but nothing in `:app` calling it, the same tree
measured 14,540,616 (+12.73 MB) — R8 strips the Kotlin and ML Kit classes nothing reaches
while the native library and the 1.82 MB of model assets ship regardless. **A 100 KB movement
in this row means reachability changed, not that the models did.**

**PDFs add no dependency** (FR-207). `android.graphics.pdf.PdfRenderer` has been in the
platform since API 21, well below the minSdk FR-516 fixes at 26. Pages are rendered to
bitmaps and put through the same recogniser as an image, so the PDF path costs the OCR
decision above and nothing further. The alternative — PdfBox-Android or iText, which read a
PDF's embedded **text layer** directly and would extract it perfectly rather than by looking
at a picture of it — is roughly 5–16 MB on top of a budget this feature has already spent a
third of, brings an AGPL-or-commercial licence question in iText's case, and would still need
the OCR path for a scanned PDF, which has no text layer to read. Rendering and recognising is
one path that handles both kinds. What it gives up is recorded as a reading against FR-207 in
the SRS rather than left here: OCR of a rendered page is lossy where the text layer is exact.

**Approved, not yet in use.** The measurement spike that produced these numbers was reverted:
at the time of writing no coordinate here is declared in `gradle/libs.versions.toml`, there is
no `:ocr` module in `settings.gradle.kts`, and no `image/*` or `application/pdf` share filter
in the manifest. Those land with the FR-215 build.

### `androidx.work` — the offline write queue (FR-806, NFR-302)

**Re-measured on adoption, 27 Aug 2026.** The release APK went from **1,064,828 bytes**
(the tree with FR-807 undo and no queue) to **1,186,254** — **+121,426**.

That figure is WorkManager **plus** the queue code it was added for: the encrypted store, the
JSON record, the worker and the two new UI surfaces. It cannot be split further by
measurement, because removing the dependency does not leave a tree that compiles, and
measuring the library against a stub would measure whatever R8 could strip rather than what
ships. It is quoted as a combined number for that reason.

It is consistent with the +118 KB measured for the library alone in the original session,
which suggests the queue code is close to free once R8 has run — plausible, since it adds no
new transitive dependency and reuses `KeystoreCipher`, `org.json` and the existing REST
clients rather than bringing its own of anything.


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

**This was a canary and is now a small test layer, and the justification is unchanged because
the artifacts are.** The three `androidx.test` dependencies still supply nothing but a runner
and `ActivityScenario`; what grew is what is written against them. Espresso, Compose UI test
and a fixture harness remain deliberately absent, and adding either is a new NFR-501 decision
rather than an extension of this one — every assertion in the suite is over a store, a file or
a recogniser, and an assertion about a *screen* belongs in the pure function the screen calls.

The layer exists because a whole class of defect is invisible without it: a platform call whose
stubbed behaviour under JVM unit tests **inverts** the real one. `BitmapFactory` is a throwing
stub in the `android.jar` unit tests compile against — the same property that makes the parser
corpus cheap — and it hid a dead image path through three commits with 328 tests green.
`SQLiteOpenHelper`, `SharedPreferences` and the Keystore have exactly that standing.

*(CLAUDE.md recorded this paragraph as stale on 2 Sep 2026 and left it alone because that
session had been told not to touch this file. Corrected here, on the first occasion the file was
open for another reason.)*

Assertions about behaviour that a JVM test can reach still belong there, where they are free and
cannot flake — `SetupStateTest` holds AC-15 and AC-16 that way.

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
