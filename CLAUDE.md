# latch-android

## What this is
The Android client of Latch: captures dates and deadlines from text anywhere on the phone,
parses them on device, and writes them to the user's Google Calendar and Google Tasks.
There is no backend, and no user content reaches any publisher server.

## Specification
The authoritative requirements are in `docs/SRS.md`. Requirements are numbered
(FR-nnn functional, NFR-nnn non-functional). When implementing, cite the
requirement ID in your summary so work can be traced back.

## Module structure
| Module | Type | Holds |
|---|---|---|
| `:app` | Android application | Capture entry points, first-run setup, Compose confirmation UI |
| `:core-model` | pure Kotlin (JVM) | Domain types from SRS §7.1, including FR-701's `InboxCapture` and `InboxReason` — §7.1's `Capture` carries `state (inbox / saved / discarded)`, so which client holds a row is a storage question and what a row *is* is not — and FR-1001's `LatchSettings`, because three of its fields reach `ParseContext` and a parse two clients read differently is the drift `:wire` exists against |
| `:parser` | pure Kotlin (JVM) | Date and time extraction, classification (FR-500 series) |
| `:cards` | pure Kotlin (JVM) | **FR-1204's business-card grammar** (SRS §5.11, Phase A). vCard 2.1/3.0/4.0 and MECARD → `CardDraft`. Depends on `:parser` not at all: the FR-500 series has nothing to say about a card, and a card has no date in it |
| `:recipes` | pure Kotlin (JVM) | Working-day arithmetic, recipe expansion (FR-600 series), FR-603's edit rules and FR-602's shadowing — a client that minted a new id when editing a built-in would show nine entries where the other showed eight |
| `:wire` | pure Kotlin (JVM) | **The §7.2 write contract, compiled.** Its metadata and hashes, FR-509/509a/509b's title derivation, FR-805's description, FR-1005's `.ics` — everything that decides a byte Google receives. Shared by the Android and Windows clients so the two cannot derive different keys from one message |
| `:google` | pure Kotlin (JVM) | **Every request either client makes to Google.** The API contracts and their REST implementations, FR-803/FR-804's duplicate and reschedule queries, AC-17's `ALLOWED_HOSTS` guard, and a hand-written JSON — because `org.json` ships inside `android.jar` and nowhere else |
| `:webhook` | pure Kotlin (JVM) | **FR-1004, and it is not `:google` on purpose.** AC-17 rests on every request this project composes going through `ALLOWED_HOSTS`; the webhook is the single documented exception (NFR-201, AC-18), so it is sent by a different client with narrower rules. Keeping it out of `:google` is what keeps that module's description true, and what makes FR-1004b's exclusion from the write queue structural rather than remembered |
| `:desktop` | Kotlin/JVM application | **The Windows client (FR-300 series).** Swing UI, WinRT OCR, `RegisterHotKey`, DPAPI — all reached through what Windows already ships, so no SDK and no third-party dependency. Shares `:wire`, `:parser`, `:recipes`, `:core-model` with `:app` |
| `:ocr` | Android library | On-device OCR (FR-215, FR-207). The only module that names an ML Kit type; `:app` sees `OcrReader` and `OcrResult`. Bundled models, +12.83 MB per device — the largest single thing this app ships |
| `:data` | Android library | Storage — the FR-701 SQLite database (Capture Inbox, FR-803's hash index, FR-807's stored offer), the write queue and account defaults in encrypted preferences, the secret store contract — and the Google API contracts plus their REST implementations. Every outbound request in the app originates here. No Play services: the OAuth grant lives in `:app` |

Dependencies point one way: `:app` and `:desktop` → `:data`/`:google`/`:webhook`/`:parser`/`:recipes`/`:ocr`/`:wire`
→ `:core-model`. `:google` → `:wire` → `:parser` → `:core-model`. `:webhook` → `:google` for the
hand-written JSON and nothing else; a test asserts it imports nothing else from there. `:desktop` never touches `:data`,
which is Android storage; it has its own.
`:parser` and `:recipes` do not depend on each other; they exchange `:core-model` types.
`:wire` depends on both: FR-600's chain is part of what Google receives, so `RecipeChain` sits
beside `ItemDrafts` rather than in a client.
`:ocr` depends on neither — it returns text, and what that text means is the parser's business.

## How to build and test
Requires JDK 17+ on `JAVA_HOME`. Android Studio's bundled JBR works:
`C:\Program Files\Android\Android Studio\jbr` (JDK 21). Gradle and the Android SDK
components download on first run; `local.properties` needs `sdk.dir` and is not committed.

```
./gradlew build                # everything: compile, unit tests, lint, debug + release APK
./gradlew :parser:test         # the parser corpus alone — seconds, no emulator
./gradlew :app:assembleDebug   # debug APK

# The instrumented suite. Needs a connected device and is NOT part of `build`, so a green
# build says nothing about whether the app starts or whether any of its storage works —
# that gap is what it exists for.
./gradlew :app:connectedDebugAndroidTest
```

**The instrumented suite leaves the app installed.** `connectedAndroidTest` uninstalls both APKs
when it finishes, and for this project that has teeth: the device is left with no Latch, and the
next thing anyone does is a device pass that needs one. `gradle.properties` sets
`android.injected.androidTest.leaveApksInstalledAfterRun=true`. There is no DSL for it, so if a
future AGP drops the property the symptom is the old behaviour rather than a build failure —
the check is that the app is still on the device afterwards.

**What the suite is for, and what it is not.** It covers one class of defect: *a platform call
whose stubbed behaviour under JVM unit tests inverts the real one.* `BitmapFactory` is a throwing
stub in the `android.jar` unit tests compile against — the same property that makes the parser
corpus cheap — and it hid a dead image path for a whole slice with 328 tests green.
`SQLiteOpenHelper`, `SharedPreferences` and the Keystore have exactly that standing. Espresso and
Compose UI test are still absent and adding either is still a new NFR-501 decision: every
assertion here is over a store, a file or a recogniser, and an assertion about a screen belongs
in the pure function the screen calls.

## Hard constraints
Do not violate these without asking first:
- **`:parser`, `:recipes` and `:wire` stay pure Kotlin.** For `:wire` the constraint is
  portability rather than testability: whatever goes in it has to exist on every platform §4.1
  names, or the clients stop sharing it and the drift it exists to prevent comes back. No Android dependency, no third-party
  dependency. This is what makes FR-501 and NFR-502 enforceable by the build rather than by
  review, and it keeps the corpus running without a device.
- **Never invent a date** (design principle 1). No date found means an undated item, never
  "today". A missing year means the current year, never rolled forward — AC-04 depends on it.
- **Nothing leaves the device** except the finished Calendar/Tasks entry (design principle 2,
  NFR-201). The one exception is a user-configured FR-1004 webhook, off by default, never
  for notification-sourced captures (FR-210a), never in the write queue (FR-1004b).
- **No `MediaProjection`** (FR-217) and **no Accessibility Service** (FR-218).
- **Ask before adding a dependency** (NFR-501). Each one needs a written justification in
  `docs/DEPENDENCIES.md`, with its measured APK impact. Anything not listed there has not
  been justified.
- **All user-facing strings live in `app/src/main/res/values/strings.xml`** (NFR-402). The
  pure-Kotlin modules return data, never display text — see `ShiftResult` for how FR-606's
  "weekend skipped" message is left to the app to phrase.

## Conventions
- Kotlin, Compose with Material 3, `java.time` (available natively at minSdk 26, no desugaring).
- AGP 9 has built-in Kotlin support: do **not** apply `org.jetbrains.kotlin.android` to the
  Android modules. The Compose compiler plugin is still applied separately.
- Versions live in `gradle/libs.versions.toml`. Nothing is version-pinned in a module file.
- Comments explain decisions the code cannot state — why a heuristic leans the way it does,
  which requirement forces a shape. They do not restate the code.
- Parser rules take `ParseContext.now` and never read the clock, so every case is reproducible.

### Two conventions about testing, learned the expensive way

Both come from the same week, in which **three load-bearing paths turned out never to have
run** while every test was green and every acceptance check had passed honestly: AC-07 passed
against a calendar holding one event, the drain's duplicate check had no reachable test, and
`authorize()` was never called because the token was always warm. They are here rather than in
anyone's memory because each cost a day to re-diagnose.

**Name the condition that would make a check fail, and confirm the fixture creates it.** A
criterion verified against a fixture too easy to fail it has not been verified. AC-07's whole
point is finding an existing item among others, and it was run on a calendar with one thing in
it, so a query that could only ever match the first event passed for five days while writing
duplicates. Before recording a device pass, write down what the failure would look like and
check the fixture can produce it. This is cheaper than any of the diagnoses it replaces.

**Reachability decides coverage, so put decisions where a test can reach them.** Twice the
untested thing was the load-bearing thing, and both times not because anyone declined to test
it: the drain's FR-803 check was private to a `CoroutineWorker` needing a `Context`, and the
paging loop was inside a REST client owning its own HTTP. What surrounded them — `drainable`,
the URL builders — was pure, and so that is what had tests. When a decision matters, extract it
until a JVM test can call it: `duplicateProbeFor` takes a `PendingWrite`, `findEventPaged`
takes its `get`. And a fake that answers by fixture rather than by matching cannot catch a
matching bug — `findEventBySourceHash` in the fakes ignored its argument for as long as the
real one was broken.

A corollary worth stating on its own: **a test can pin behaviour exactly and pin the wrong
behaviour.** `assertTrue(url.contains("maxResults=1"))` passed throughout the period that query
was writing duplicates into a real calendar. It described the code faithfully. Asserting what
the code does is not the same as asserting what the requirement needs.

## Releasing
`docs/RELEASE.md` is FR-1108's gate in operational form: six items, two done in the repository
and four only the publisher can do. Read it before any Play submission.

Two things about the build are worth knowing before touching `app/build.gradle.kts`. The release
**signing config exists only where `keystore.properties` does** — that file is git-ignored, a
committed `.example` shows its four keys, and a release build without it is *unsigned rather than
failed*, because NFR-503 requires a clean checkout to build and the launch canary has to work on
a machine that has never seen a keystore. And the **`bundle` block is not optional**: ABI splits
are the condition NFR-103's per-device reading rests on, and without them the same tree is 42.58
MB against a 40 MB budget.

Nothing here generates a keystore, deliberately.

## Commits
Commit directly to `main`. This is a single-developer repository with no CI and no review
step, so a feature branch adds ceremony without adding safety — there is nothing for it to
gate. This overrides the default habit of branching before committing to the default branch.

Subject line in the present tense, imperative, one line. The body carries the reasoning:
which requirements the change serves, and any decision the code cannot state for itself.

## State of the build
Skeleton only. Working: the six-module structure, the parser (113-case corpus, all passing —
see the NFR-502 note below),
working-day arithmetic and recipe expansion, capture layers 1, 2 and 4 as far as the
confirmation screen, first-run setup (FR-100 series) end to end, on-device OCR of
images and PDFs (FR-215, FR-207) verified on a device, and the FR-700 Capture Inbox with the
local storage under it — **the Inbox and everything in its slice is JVM-verified and
DEVICE-OWED; see the Device pass backlog at the end of this file.**

Setup runs against real Google. `StubGoogle.kt` is gone. The NFR-501 question it was waiting
on is settled: `play-services-auth` for the OAuth grant and nothing else (+135 KB, measured),
and every API call hand-written REST over `HttpURLConnection` and the platform's `org.json`.
`GoogleAuthClient` in `:app` is the only class that touches Play services; `GoogleRest.kt` and
`GoogleHttp.kt` in `:data` are the calls.

Two things there are worth knowing before changing them. **No access token is persisted** —
Play services caches its own, they last an hour, and re-authorizing a granted scope set
returns one with no UI, so NFR-203 is satisfied by there being nothing at rest. And the
account's identity comes from `calendars/primary`, whose id is the user's email address,
because `AuthorizationClient` grants authorization and not identity; `AccountDefaults.accountId`
is a SHA-256 of that address, so the preference key stays opaque.

Account defaults persist. `EncryptedAccountDefaultsStore` in `:data` writes them to
app-private preferences, each record encrypted with AES-GCM under an Android Keystore key
(`KeystoreCipher`, same file) — no dependency, because that is all `androidx.security-crypto`
would have done and it is deprecated in favour of these platform APIs. The secret store
(NFR-203) should reuse `KeystoreCipher`. Setup therefore runs once: a second launch reads
the stored account and goes straight to the home screen.

## Verified on a device
SRS §10 requires each acceptance scenario to pass on a physical device before sign-off. This
is the running record. A green `./gradlew build` is not evidence for anything in this list —
the launch canary exists because the app once could not start at all with every unit test
passing.

Pixel 6 Pro, Android 17 (API 37), Play services 26.32.62, debug build.

| Scenario | Date | Result |
|---|---|---|
| **AC-16** — abandon setup at step 2 | 26 Aug 2026 | **Pass.** Signed in, reached step 2, backed out. No Latch calendar created in the Google account. FR-105 holds against the real `calendars.insert`, not just the stub. |
| **FR-104** — the Latch calendar's one colour | 26 Aug 2026 | **Pass.** Renders red in Google Calendar, distinct from the user's own calendars. Confirms `colorId = "3"` is right for the **calendar** palette that `calendarList.patch` reads — the previous `"11"` was an id from the **event** palette, which is separately numbered, and was inert only while the stub's patch did nothing. |
| **FR-801, FR-802, FR-904** — a capture becomes an event | 27 Aug 2026 | **Pass.** Destination chip showed the Latch calendar in its own colour, Save wrote the event, and it appeared in Google Calendar at the parsed time. §7.2 verified against the live API by logging what `events.insert` returned: all six mandatory keys stored under `extendedProperties.private`, `latch.recipe` correctly absent, `captured_at` correct in UTC, and `latch.source_app` populated for the first time from `getReferrer()`. |
| **AC-07** — duplicate detection | 27 Aug 2026 | **Mechanism verified; cross-device half still owed.** Capturing identical text a second time returned "Already saved. Nothing was written again." and created nothing. That is Google's own index answering a `privateExtendedProperty=latch.source_hash=…` query, so the hash was written, is stored in `private`, and round-trips deterministically through the normalisation. But AC-07 reads "capture the same message **on phone and PC**", and the Windows client does not exist — this was phone-to-phone. The criterion is not met until two clients do it. |
| **AC-11** — undo removes what a save wrote | 27 Aug 2026 | **Mechanism verified; the four-item half still owed.** Save swapped the button for a counting-down Undo, a tap outside the window did **not** dismiss it while the offer stood, the window closed itself when the offer lapsed, and Undo removed the event from Google Calendar. That covers every part of FR-807 no JVM test can see — the `setFinishOnTouchOutside` suppression above all, which is a real dialog-window behaviour and the piece most likely to have differed on a device. But AC-11 reads "save a **four-item** chain, then press undo", and a chain is one item on every path reachable today: `draftItems` drafts only `ParseResult.primary` until FR-511's per-date checkboxes exist, and FR-601 recipe expansion is not wired to this path either. The removal loop is written for a chain of any size and reports how far it got, and none of that has been run against more than one item. The criterion is not met until a real four-item chain is undone.
| **AC-10** — offline capture reaches Google | 27 Aug 2026 | **Pass.** Aeroplane mode on, capture saved, screen said "No connection" rather than "Saved" — the distinction the Queued state exists for, since nothing was in the account yet. Home showed the pending count, and on reconnecting the item appeared in Google Calendar. That is the criterion as written, met in full: queued locally, written on reconnection, no loss. What it does **not** cover is the rest of NFR-302 — app termination and device restart while queued are the other two cases it names, and neither has been watched. Nor has the FR-803 re-check at drain, which is the defect that would stay invisible until a user queued the same message twice offline and got two items. |
| **FR-505 ranking, and exclusive weekday attachment** | 28 Aug 2026 | **Pass.** "Sprint ends March 11, 2030 and the review is Tuesday, March 5, 2030 at 21:30" opened on **5 Mar 2030, 21:30, Event** — the date carrying a time, not the one written first. Before the fix this opened on 11 March with no time. 11 Mar 2030 is a Monday and 5 Mar a Tuesday, so the weekday attached to the date whose day it falls on rather than to the nearer one, which is the half no JVM test can show reaching a screen. |
| **AC-08 / FR-804** — a reschedule is offered, not duplicated | 28 Aug 2026 | **Pass, on events and on tasks.** The offer appeared on the second capture naming both positions with computed weekdays, and **nothing was written while it stood**. Both answers work: Create new made a second item, Update moved the first and left exactly one. A tap outside did **not** dismiss the offer. §7.2's third row was watched too — re-capturing the text of a reschedule already applied answered "Already saved. Nothing was written again." rather than offering to move the item onto the position it already held. |
| **FR-807 over an update** — undo restores, never deletes | 28 Aug 2026 | **Pass.** Undo inside the window put the event back on its previous date; nothing was deleted. Also observed on the way: an offer left untaken closed itself at ten seconds and the save stood, which is FR-807's lapse behaviour seen rather than reasoned about. |
| **`tasks.delete` and `tasks.patch`** | 28 Aug 2026 | **Pass, first run against the live API.** Both had only ever run against a fake. `tasks.delete` via undo of a created task, `tasks.patch` via an update and again via its undo restoring the due date. |
| **§7.2 write-once, observed** | 28 Aug 2026 | **Pass.** After an update the event's description still showed the **original** capture's text, March 5 and all, while its dates read March 7. That is SRS 1.18's reading seen on a device: the description is provenance, not current state. |
| **AC-11** — a four-item chain, undone | 28 Aug 2026 | **Pass. The criterion is met in full**, closing the half owed since 27 Aug. Four dates in one capture, four rows ticked, four items written — two events and two tasks — and one Undo inside the window removed all four from Calendar and Tasks. The removal loop had been written for a chain of any size since FR-807 and had never run against more than one item; FR-511 is what made it reachable. Verified undo-first and inspected after, with the previous step having already established that the writes reach Google — inspecting before undoing is what loses the window. |
| **FR-511** — per-date checkboxes | 28 Aug 2026 | **Pass.** Four rows in document order, all ticked, badges EVENT/TASK/EVENT/TASK against dates 6·1·10·14 Sep 2027, and "4 dates found in this capture". Unticking one wrote exactly three items and left nothing on the unticked date. Mixed badges confirmed separately on a range-plus-task capture: one all-day EVENT row beside a TASK row. |
| **FR-502** — a date range on the wire | 28 Aug 2026 | **Pass.** "from September 20 to September 24, 2027" landed as one all-day event covering the 20th through the **24th**, not the 25th — so the inclusive-to-exclusive conversion is right where it actually matters. This is the text whose opening date resolved to the wrong year until SRS 1.25; the pass is of the fix, not of the original behaviour. |
| **FR-804 suppression for a chain** (SRS 1.25) | 28 Aug 2026 | **Pass.** A four-date capture re-captured with one date amended produced **no** offer at all — an ordinary four-row sheet — and saving wrote four items including three visible duplicates of the unchanged dates. That is the reading's accepted cost seen rather than argued about: visible on screen and removed by one undo, where the behaviour it replaced would have moved one item and discarded three in silence. |
| **FR-804 for a single date** | 28 Aug 2026 | **Pass, as a regression check.** "Kickoff 8 September 2027 at 9am" saved, then the 9 September form offered the move with computed weekdays — Wed 8 Sep to Thu 9 Sep. The suppression above is confined to multi-item captures and did not disturb the requirement's own case. |
| **NFR-102 gating — an ordinary text capture** | 31 Aug 2026 | **Pass, and it is the gate on the whole FR-215 slice.** "Kickoff 8 September 2027 at 9am" opened with EVENT, the right date and time, destination chip and Save enabled, and **no spinner at any point**; `content ready, ocr=false, chars=31 in 4ms` says the synchronous path stayed synchronous. Saved, undo counted down, the window closed itself on lapse, and the event reached Google at 2027-09-08 09:00 IST with the whole source text in its description — the FR-805 text path unchanged. |
| **NFR-101 for text, and `MlKitInitProvider`'s cost** | 31 Aug 2026 | **Pass.** Cold start to a filled sheet, worst of seven runs **577 ms** against 800 ms. Warm 64–67 ms. The cost of ML Kit's init provider — which runs before `Application.onCreate` on every launch, including the text captures that never use OCR — was measured by building `0f248fb` in a throwaway worktree and running the same capture: **+27 ms median** (549 → 576), +20 ms mean. Measurable, imperceptible, 223 ms of headroom left. |
| **FR-205 / FR-207 share targets** | 31 Aug 2026 | **Pass, by real gesture.** Latch appears in the system resolver for `image/*` and for `application/pdf`. Every image and PDF step below went through the resolver rather than a named component, because `am start -n` does not propagate the URI read grant — which is a property of the harness, not of the app, and is written up in `testdata/fr215/share-to-latch.ps1`. |
| **FR-215 — a screenshot becomes items** | 31 Aug 2026 | **Pass.** Three dates read off a rendered chat image, each with its own badge and date line. NFR-101 worst **1673 ms** against 2.5 s across four image fixtures, three runs each. |
| **FR-215 — EXIF rotation** | 31 Aug 2026 | **Pass.** A page photographed portrait, stored 2800×2000 with EXIF orientation 6, read correctly: `TASK 22 Nov 2027`, title from the document. This is the total-failure case — an unread tag hands ML Kit a sideways page and it returns nothing at all. |
| **FR-207 — a text-layer PDF** | 31 Aug 2026 | **Pass.** `letter.pdf`, 2 pages, → `TASK 14 Oct 2027` from `Renewal due 14 October 2027`. `pages=2/2`, so the cap line is correctly **not** shown. |
| **FR-207 — the page cap, reported** | 31 Aug 2026 | **Pass.** `long.pdf`, 14 pages → the page-2 date found, **"First 10 of 14 pages read."** on screen, the page-12 date correctly absent. That string is the only part of FR-207's reporting no JVM test can reach. It also produced NFR-101a: 10 pages took **5074 ms**, which is what a document budget now exists for. |
| **FR-803 over an OCR capture** | 31 Aug 2026 | **Pass.** Re-sharing an identical image answered "Already saved. Nothing was written again." — Google's own index, through the app's client. Also the measurement behind SRS 1.30: the same file recognised twice gave an identical character count and an identical hash, so the §7.2 wobble lives in re-renderings and across §4.1's clients, not in re-sharing one file. |
| **FR-805b — the row window** | 31 Aug 2026 | **Pass, at the second attempt, and the first attempt is why the rule changed.** The character-radius version wrote the **entire** recognised screen into a task note. Rebuilt as a row window, the note carries the dated rows and one neighbour each: no sender name, no amount, no chrome. The failing note is committed as the conformance fixture at `data/src/test/resources/fr805b/device-note.txt`. |
| **AC-17 — network monitor over an image capture** | 31 Aug 2026 | **Pass.** A per-app capture across a full cycle including image captures recorded exactly two destinations, both Google: `tasks.googleapis.com`, this app's own writes through the `ALLOWED_HOSTS` guard, and **`firebaselogging.googleapis.com`**, which is ML Kit's, firing three times a few seconds after each image capture. **No non-Google endpoint.** The second host is not in `ALLOWED_HOSTS` and must not be added: that list governs requests this app composes. SRS 1.27's structural restatement, observed rather than reasoned about. |
| **FR-509a — an OCR title from its date's own row** | 1 Sep 2026 | **Pass.** On `three-dates-v4.png` the three rows now read `Yes. PTM on` / `Fees due` / `Trip from` against their own dates, where the whole capture previously carried one title of `Sharma Ji online Paid the uniform bill, Rs 12,500 in total.` — the chat header and the amount, both of which FR-805b had already excluded from the description. Two things were found only by looking at the screen. The confirmation screen was still drawing FR-509's single title above the list while the write used per-row ones, so a user would have confirmed one thing and Google received another; `titleFor` is now the single place that decides, called by both. And blanking a span left its punctuation stranded — `Yes. PTM on .` reached a real title — which `tidyBlanked` repairs. The dangling connective is left standing and is recorded as cosmetic. |
| **FR-215 — chrome-bleed, the top-band drop** | 1 Sep 2026 | **Pass, on three fixtures chosen to fail differently.** `three-dates-chrome.png`: the `10:42` status-bar clock is gone and `14 Sept 2026` is now a **TASK** where it was an `EVENT … 10:42 am` — the manufactured time and the wrong item type both removed, `chars` 287 → 278. `three-dates-v4.png`, which has no status bar: **unchanged**, `chars=262`, same three dates — the rule does not touch an image it should not. `three-dates-cropped.png`, 1440×900 with content at y=0: **untouched**, first line `Thanks. Did the school send` intact and all three dates found — the stated guarantee that the rule is inert below roughly a thousand pixels, since 4% of 900 is 36px and thinner than a line of text. |
| **AC-10 with an expired token — FR-806a, re-run on the sign-in surface build** | 1 Sep 2026 | **Pass.** Repeated against `215656e`, which changed `markFailed`, `QueueStatus` and the drain's failure handling, so the drain path needed re-checking rather than assuming. Token invalidated, aeroplane mode on, capture: queued immediately and **no "Sign in needed"** shown — correct, because a capture-path failure enqueues directly rather than through `markFailed`. Aeroplane off: the drain ran, FR-803 found the existing event through the paging query and **retired without writing**, the count staying at exactly one. |
| **AC-10 with an expired token — FR-806a** | 1 Sep 2026 | **Pass, and it is the half of AC-10 that had never been run.** AC-10's 28 Aug pass was inside a session whose token was still cached, so `authorize()` was never reached; on 1 Sep an offline save with an expired token suspended for **thirteen minutes** with no error, no queue entry and nothing on screen. Re-run against FR-806a with the debug token hook — invalidate, aeroplane mode, capture — the sheet reported **queued immediately**, no consent Activity was attempted at all, and on reconnecting the drain ran (`Worker result SUCCESS`) and wrote **exactly one** event. The fixtures having been cleaned up first, writing one was the correct outcome rather than retiring. |
| **FR-002 on Windows — sign-in without Play services** | 3 Sep 2026 | **Pass.** Consent granted in a browser through the loopback receiver, PKCE S256. A **refresh token is stored** — the failure that looks like working software until the hour is up — and a **fresh process signed in from it**, obtaining a new access token with no browser. On disk it is a DPAPI blob (`AQAAANCMnd8BFdER…`); no `ya29.`, no `1//0` anywhere in the file. |
| **The desktop adopts the phone's calendar, not a second one** | 3 Sep 2026 | **Pass, and it is AC-07's second guard.** Three writable calendars in the account, exactly one named Latch, and the desktop chose it by id rather than creating its own. FR-803's event query is scoped to a calendar id, so a second Latch calendar would have made the desktop blind to everything the phone wrote. |
| **FR-801/802 from Windows — a real write** | 3 Sep 2026 | **Pass.** Two captures written to the live API through the shared `:google` client. Read back: all five §7.2 keys present, `latch.recipe` correctly **absent**, metadata decoding cleanly through `remoteMetadataFromEventProperties`, description carrying the source text, and Google canonicalising the zone this JVM calls `Asia/Calcutta` to `Asia/Kolkata`. The absent-recipe check is the hand-written JSON's `put(name, null)` removes semantics, confirmed against Google rather than against a unit test. |
| **FR-803 on Windows** | 3 Sep 2026 | **Pass.** The identical capture a second time answered "Already saved. Nothing was written again."; a different message wrote. **On this client that answer can only be the source-hash query**, because the desktop has no FR-804 path to fall through to — which is what Android's did on 1 Sep, and why AC-07 was worth closing here first. |
| **AC-07 — the same message on phone and PC** | 3 Sep 2026 | **PASS. The oldest open criterion in this record, closed.** The phone captured `Kickoff 8 September 2027 at 9am` on a device weeks earlier. The PC computed `source_hash b3375f4a…` and `item_key f5d2496e…` — **byte-identical to what the phone stored**, verified against the values read back out of Google — and answered "Already saved. Nothing was written again." Evidence is the count and not the message: the calendar held **4 events before and 4 after**, exactly one Kickoff, and FR-803's own query returns exactly **1** match for that hash. Two things make this more than one green run. The identity was derived by *shared compiled code*, so it agrees by construction rather than by luck; and `f5d2496e…` is the same digest the `plain_event` row of `item_key_vectors.tsv` was generated with today, on Windows, from `:wire` — so a vector taken this morning matches an item a phone wrote in August. The reverse direction was run on 3 Sep 2026 — see the row below. |
| **AC-07 — the reverse direction, PC first then phone** | 3 Sep 2026 | **Pass on the criterion as written; the mechanism half is OWED and the reason is structural.** An email selection captured on the PC on 18 Sept, **with its title corrected before saving** (FR-509b), was shared into Latch on the phone. The sheet said *"Already saved. Nothing was written again."* and **nothing was written** — FR-803's index unchanged at 2 rows, no Inbox row, no stored undo offer, empty write queue, `latch.db` untouched since the morning's unrelated save. The phone's index records every item *this* device writes, so an unchanged index proves this phone created nothing rather than that nothing is visible. **The title edit made no difference**, which is the load-bearing half: FR-509b's correction travels through `titleOverrides`, outside both `source_hash` and `item_key`, precisely so correcting a typo cannot make an item unmatchable. **What is NOT established is which query answered.** SRS 1.60 closed the forward direction on the desktop *because* `DesktopSaver` has no FR-804 path; the reverse direction has no such guard — Android falls through to `item_key`, and §7.2's row 3 produces the identical sentence. That is the 1 Sep 2026 confusion exactly. It is not academic: `item_key` is the weaker identity (title with date spans blanked), the text reached the phone through a note-taking app, and a reflowed line break would have changed `source_hash` while `item_key` still matched. **Mechanism closed on the second run, same day** — see the row below. |
| **AC-07 — the reverse direction, mechanism** | 3 Sep 2026 | **PASS, and AC-07 is now met in full.** The same capture re-run against the build carrying `WriteBasis`: `I/LatchTiming: save decision=Duplicate basis=SOURCE_HASH`. **FR-803's source-hash query matched** — not the FR-804 fall-through that produced a misleading "Already saved" on 1 Sep 2026, and the first time on this client the difference has been *observed* rather than assumed. Four things travel with it. `basis=SOURCE_HASH` means `latch.item_key` was **never consulted**, so the identity that matched is the strong one. **No `scan=capped`**, so the Tasks API's ±1-day scan *completed* rather than giving up — SRS §5.8 forbids reading a capped scan as "no duplicate", and this was not one. The **local index did not and could not answer**: it is consulted only to cure a capped scan and holds no row for an item the PC wrote. And a matching hash **proves the text arrived byte-identical**, retiring the fixture doubt about the note-taking app the text passed through. It also settles the 16:54 run by construction — same 437 characters, same unchanged account, and `writeDecision` is pure. Nothing written on five instruments. And across the run's **49,705 lines of logcat the app emitted exactly two**, neither carrying a character of the capture. |
| **FR-503 / SRS 1.62 — a relative word and its gloss are one date** | 3 Sep 2026 | **Pass, on the capture that prompted it.** The NITI Aayog email shared into Latch verbatim (256 chars, `ocr=false`, 0 ms — the synchronous text path, so NFR-102 is undisturbed). The sheet showed **one** candidate, not two: no checkboxes at all, which is how a single-candidate sheet renders, where the same text produced a to-do for the reading day beside an event for the previous one before the change. It read `EVENT`, `2 Sept 2026, 5:30 pm` — the explicit date, not the relative one — with FR-510's line *"2 Sept 2026 has passed. Latch will save a follow-up to-do instead of a dated item."* The developer then pressed Save while this was being watched, and the local index records exactly one new row: a **TASK**, at 12:56:03, which is FR-510's undated follow-up and the expected outcome of the merge. Nothing was queued and nothing crashed. **Read back out of Google afterwards**: the task is in the list with **no due date**, carrying `Originally dated 2 September 2026.` and FR-805's source text, and its `source_hash` and `item_key` match the phone's index byte for byte — sheet, index and account all agree. FR-509's title finding is visible in the same item, and in its worst form: `In continuation of the trail mail, please find` is the title of a real to-do in the user's list. Recorded against FR-509, deliberately not fixed, FR-509b's edit being the answer. |
| ~~**FR-509b is easier to reach**~~ | 4 Sep 2026 | **PASS on both clients, closing a row open since 3 Sep.** On Windows the title field opened focused and selected, typing replaced it immediately, and Enter alone completed the capture without the field being touched — watched on an undated capture, so nothing reached the account. On Android **tapping the title text opened the editor, and the software keyboard stayed away until the field itself was tapped**. That second clause is the one worth having watched: a keyboard appearing on its own would cover the sheet the user is confirming, which is the whole reason the arrangement is two taps — and a keyboard that behaves looks identical to one nobody checked for. |
| **AC-05 — a screenshot with three dates** | 31 Aug 2026 | **Pass**, on `three-dates-v4.png`. Exactly three dates — `TASK 14 Sept 2026`, `TASK 20 Sept 2027`, `EVENT 1 Oct 2027`, the range correctly one candidate and an Event. Three checkboxes all `checked=true` at open, read from the view hierarchy rather than eyeballed; unticking the middle gave `true,false,true`; the save wrote **two** items, not three; nothing on the unticked date; and a re-capture answered "Already saved", which is Google confirming both landed. **Provenance:** v4 is a *rendered* chat image, not a device screenshot, so the criterion is met by a proxy and is to be re-run if a real screenshot is supplied. **The undo half was not re-run here** and is not silently omitted: it is covered by AC-11's four-item chain undo of 28 Aug. |

| **FR-804's move note, and its undo** (SRS 1.79) | 4 Sep 2026 | **Pass on Android, both halves, the day after it was built on Windows.** An update onto 19 Nov 2027 appended *"Moved by Latch from … 12 Nov 2027, 16:00, on 4 Sep 2026."* **below** FR-805's captured text rather than displacing it. A second update was then undone inside the ten seconds: the event returned to 19 Nov carrying **exactly one** note, the earlier one — so the undo wrote back a stored previous body rather than stripping notes, which a single-update fixture could not have distinguished. `WriteBasis` named every step: `Create basis=NO_MATCH`, `Reschedule basis=ITEM_KEY_DIFFERENT_DATE` twice. **Found on the way, and recorded as SRS 1.81 rather than as a defect**: re-capturing the message an item was moved *off* answers "Already saved" and cannot move it back, because §7.2 is write-once and the item still carries that text's source hash — `basis=SOURCE_HASH` said so, the first use of that line to exonerate rather than convict. Across 37,510 lines of logcat the capture text appears **zero** times. |
| **FR-806b — the banner precedes the loss** (SRS 1.87) | 4 Sep 2026 | **Pass, and the evidence is the store rather than the screen.** The contacts scope was added to the request, making the existing grant insufficient; opening Latch showed the sign-in banner and button on the home screen, and the consent screen was reached and completed from it. **Which of the two sentences it was is derived, not recalled**: `signInPrompt` returns `QUEUE_HELD` only when `queueWaiting > 0`, and `write_queue.xml` was 65 bytes and untouched since 2 Sep — an empty queue — so it can only have been `GRANT_ONLY`. That is the requirement's own case: the prompt appeared **without any capture being made, queued or lost**, which before this slice was impossible, the flag having been set only by a failed drain. The silent check ran from `MainActivity.onStart` and presented nothing; the consent came from a tap, so FR-806a is undisturbed. **Two things are NOT established and are not to be read into this.** FR-1007's Settings action was not used — the home screen's button was — so that surface is still unwatched. And this says nothing about **FR-806a**, whose row stays open: a drain that fails for want of a sign-in is a different mechanism, and the create-and-wait-for-drain path has not been run. |
Also established in passing, none of it reachable from a JVM test: the OAuth grant works end
to end (so the debug SHA-1 is registered and the account is a test user), `KeystoreCipher`
encrypts against a real Keystore, a completed setup survives a cold start, and the stored
preference key is a hex digest rather than an email address.

**What the FR-215 pass found, 31 Aug 2026.** Four defects, all fixed in the pass and each
invisible to the 328 JVM tests that were green throughout.

**The image path was dead on every image.** `openStream(uri)?.use { decodeStream(it, null,
bounds) } ?: return null` — `decodeStream` returns null **by contract** under
`inJustDecodeBounds`, so the elvis guarded the decode result rather than the stream and
`decodeBitmap` returned null always. No JVM test could see it: `BitmapFactory` is a throwing
stub in the `android.jar` unit tests compile against, which is the same property that makes the
parser corpus cheap. This failure class — a platform call whose stubbed behaviour inverts a
null check — has no guard, and an instrumented test that decodes a real asset would have caught
it in seconds. **Owed: a small instrumented suite, beyond the launch canary.**

**The Devanagari model was substituting Bengali digits into Latin words.** `October` came back
as `০ctobe` (U+09E6), `12,500` as `12,50০`. The Latin-only artifact had been refused as
"declared and never called"; it is +8,082 bytes and it was the fix. NFR-501 reasoning was
sound and the outcome was wrong, which is what a device pass is for.

**FR-805b wrote the whole screen.** Its character radius covered a ~270-character chat
screenshot entirely, so the note carried every message, the sender's name and the amount paid.
Rebuilt as a row window.

**Block order was not reading order.** ML Kit returned an early message after a later one, so
FR-505's earliest-mention tiebreak — and §7.2's `item_key`, which derives from character
positions — were resting on a library internal that an upgrade could move silently.

**Deferred, with fixtures, none of it in this slice.** *Chrome-bleed and the OCR title*, one
defect seen twice: a screenshot's status-bar clock parses as a time and manufactures an Event
(`three-dates-chrome.png`), and FR-509's opening-of-the-text rule puts the header and first
message into the title — where it undoes FR-805b's exclusion in the most visible field
(`three-dates-v4.png`). *Document progress*, so NFR-102's spinner can say "Reading page N of
M". *Skipping the second recogniser* for a document whose first page shows no Devanagari, worth
roughly half the per-page cost, to be measured before adopted. All four are written up against
their requirements in the SRS.

**One thing about the harness is worth keeping.** `testdata/fr215/share-to-latch.ps1` drives
captures through the real resolver, because `am start -n` does not propagate the URI read
grant. It carries a hard rule in code: it taps only inside Latch or the system resolver and
aborts otherwise. An earlier version tapped a stale coordinate when an intent went to a default
handler instead of a resolver, and the taps landed in a WhatsApp contact picker. Nothing was
sent; nothing in the script prevented it either.

**`latch.item_key` now survives a reschedule**, after two attempts that did not. It first came
back byte-identical to `latch.source_hash`, because `TitleExtractor` implements FR-509 — "use
the selection verbatim if under 60 characters" — and strips salutations and sign-offs but never
dates, so for a short capture the title *is* the whole text. Stripping the date span fixed that
and left a second hole: the span for "Monday 31 August" covers only "31 August", so the weekday
stayed in the identity and moved with the meeting.

The parser already knew about that case and was throwing the answer away. `collectDates`
detects a bare weekday beside an explicit date and drops it as corroboration — "in 'PTM on
Friday 12 September' the weekday is the writer corroborating their own date" — and now absorbs
its span into the date it corroborates instead. One change, covering every date format rather
than one rule's regex. It does **not** touch FR-504: that requirement is "the resolved
interpretation shall be displayed", which the confirmation screen meets with the when-line and
its ambiguity notes — **there is no source-text highlight in this UI and none is specified**.
The spans exist for §7.2's derivation, not for display. §7.2 specifies the derivation as
of v1.11; the clause is the only part of that schema resting on parser behaviour, so it is
where a second client is most likely to drift.

**FR-803's re-check at drain: FAILED 31 Aug 2026, FIXED and re-verified 1 Sep 2026.** The
cause was paging, not the drain — see SRS 1.37 and 1.38. Confirmed by a controlled run: a
capture queued in aeroplane mode, aeroplane mode off, the drain ran (`Worker result SUCCESS`)
and **retired the entry without writing** — the count through the app's own client stayed at
`items=2`, and the device mirror agrees. The account held exactly the two Kickoff events the
original defect produced. **That is no longer true and the sentence is left standing with this
correction rather than edited away**: on 4 Sep 2026 the account held none at 8 Sep 2027 and one
at 8 Sep 2026, after an FR-804 update moved it. The residue of the 31 Aug defect is gone from
the account; the diagnosis below is kept because the method in it outlives the fixture.

What follows is kept because it is the diagnosis, and the method in it is worth more than the
bug.

**The original failure, 31 Aug 2026.**

The queue emptied, which is what an empty queue always looks like — and it was read at first as
both entries having retired without writing. **The item count says otherwise.** The Latch
calendar holds **two** `Kickoff 8 September 2027 at 9am` events, distinct `_sync_id`s, identical
`dtstart`, and — decisively — the **identical `latch.source_hash`**
`b3375f4a20bb6c1d5cea22c95c59d1f7feb896d40c836671910d9b972543db6a`, under different
`latch.chain_id`s. Their `latch.captured_at` values identify them exactly: `2026-08-31T05:19:24Z`
is the save that wrote directly, `2026-08-31T12:40:33Z` is the capture the UI **queued** rather
than wrote, having reported "No connection". So the second was written by the drain, with a
matching hash already in the same calendar for over seven hours.

**This is the failure FR-806's note says the re-check exists to prevent**, and it is worse than
the case that note describes: there the two captures race each other offline, whereas here the
item was in the account, indexed and long settled, before the entry was even queued. `AC-07
failing inside AC-10` is how the SRS puts it.

**What is ruled out, after a probe on 1 Sep 2026. The fault is drain-specific.**

A throwing query cannot cause it: `WriteQueueWorker` catches, marks the entry failed and
retries rather than writing, so the query ran and answered "not found" against a hash that was
present.

**Nor is the event-side query broken in general**, which was the working hypothesis for a
while and was wrong. Every correctly-detected duplicate in the FR-215 pass came from a capture
whose `items.first()` is a **task** — a multi-date capture leads with one — so the event query
had not once been the deciding query, and it looked as though it might never match. The probe
settles it: at HEAD, re-capturing an event-only item in the **foreground** answers "Already
saved. Nothing was written again." and writes nothing. AC-07 on 27 Aug says the same for the
commit it passed at. **The event query works; only the drain's use of it fails.**

**The three remaining candidates are the drain's inputs**, since the query function, the URL
builder and the filter are shared code and are therefore exonerated with the foreground path:
the `calendarId` carried on the queued entry, the calendar scoping that id produces, and
whatever the worker's own auth context resolves. Code inspection cannot separate them —
`calendarId` round-trips through `putOpt`/`optString` correctly, and a null would
`requireNotNull`-throw into the retry path rather than write.

**Why it survived to a device.** The drain's FR-803 check was **unreachable from a JVM test**:
a private method of a `CoroutineWorker`, needing a `Context`, which is why all five drain tests
covered `drainable` — the pure scheduling function beside it — and none covered the check
FR-806's note calls "the last line for FR-803". The fake could not have caught it either:
`findEventBySourceHash` there ignored its `sourceHash` argument and returned a preset id,
answering by fixture rather than by matching. **Both are fixed** — `drainEntry` and
`duplicateProbeFor` are out of the worker in `QueueDrain.kt`, the fake indexes on the hash the
write actually carries, and the check has four tests. **The refactor did not find the bug**:
all four pass, including the one written to be red, so the probe's construction, the decision
flow and the written-hash-equals-queried-hash round trip are all exonerated.

**The expired-token path is EXCLUDED — do not re-run that hypothesis.** It is the obvious
suspect, the entry having been queued at 18:10 and drained after 19:35 against tokens that last
about an hour, and it is wrong twice over. **In code**: `findEventBySourceHash` calls
`http.get`, which goes through the same `authorised` wrapper as `post` and refreshes on 401
identically; a non-200 **throws** before any parsing, so no error body can reach `firstEventId`
and become an empty list; and a twice-401 is `GoogleRejected(401)`, which `isWorthRetrying`
classifies false, so the entry is marked permanently failed rather than written. **In
evidence**: the AC-05 chain retired correctly *in the same drain*, so its task scan reached
Google and matched — auth was working that session.

**The probe answered on 1 Sep 2026, and the drain was never the fault.** Both halves — app
process and worker — issued a byte-identical URL against the right calendar with the right
hash, and **both returned `status=200 items=0`** for a hash carried by two events in that very
calendar. So `duplicateProbeFor` hands the drain exactly what the saver uses, the store's round
trip is sound, and the worker's context differs in nothing.

**FR-803's event-side query does not match, in either path.** The earlier reading here — that
the foreground query worked, because re-capturing an event-only item answered "Already saved" —
was wrong, and is corrected rather than edited away. With `items=0` that answer cannot have
come from FR-803. It came from **FR-804's `item_key` query** falling through to §7.2's
decision-table row 3, *same key, same resolved date, therefore a duplicate*: both events carry
`latch.item_key=f5d2496e…`, and that query uses the same `privateExtendedProperty` mechanism
and does match.

That single fact explains every observation, including the asymmetry:

| Path | `source_hash` query | fallback | outcome |
|---|---|---|---|
| Foreground, event-only | misses | `item_key` → row 3 | "Already saved" |
| **Drain, event-only** | misses | **none — the drain runs FR-803 only** | **duplicate written** |
| Drain, chain leading with a task | not used | task scan matches in-client | retires |

**So the defect is larger than a queue bug: FR-803 is unprotected for every event-only capture,
online and off.** The saver is accidentally covered by a requirement written for something else,
and the drain — which has no FR-804 step by design, an update creating nothing — is where the
absence becomes visible. It is a live duplicate-generator for offline event-only captures and
takes priority over feature work.

**The leading hypothesis is paging, and it is testable.** `eventDedupUrl` asks for
`maxResults=1` while `eventItemKeyUrl` asks for 250, and Google's filtered `events.list` can
return an **empty page carrying a `nextPageToken`** — the filter applies to a page of the scan
rather than selecting the page — so a one-event page will almost never contain the match, while
250 covers a small calendar in one go. It also explains why AC-07 passed on 27 Aug: the
calendar then held about one event. **An empty first page with a `nextPageToken` confirms it**,
and the fix is then to follow the token to exhaustion rather than to raise the number, so
correctness stops depending on calendar size.

**The AC-05 items are separately unaccounted for.** No event titled `Sharma…` exists on the
device, deleted or otherwise, and the Latch calendar contains nothing but the two Kickoffs —
yet the AC-05 save reported "Saved to Latch" and a re-capture answered "Already saved. Nothing
was written again." Two readings fit: the developer deleted them as cleanup after observing the
drain, or the chain's event was never written and FR-803's "Already saved" matched on the
chain's **task** instead, both items sharing one `source_hash`. **The second would be a second
defect — a partially written chain reporting success — and the two readings are told apart by
whether the 14 Sept 2026 task still exists.** Ask before assuming; do not delete it.

The experiment that produced it is worth keeping, because it was not arranged and would be
awkward to arrange again. A Wi-Fi outage during the 31 Aug pass — PCAPdroid, still running with
a filter on this package — left **two queue entries** behind: the AC-05 chain of two items, and
a single "Kickoff 8 September 2027 at 9am" capture made as a read-only diagnostic. Both had
since been written to Google by other means, so both were entries whose message was already
saved, which is precisely the condition FR-803-at-drain exists to detect.

Two things about the drain were measured and are not in doubt. **Backoff cannot be hurried**:
`ExistingWorkPolicy.KEEP` correctly refuses to reset the timer — you do not want every launch
hammering the API — and WorkManager refuses `cmd jobscheduler run -f` before the scheduled
time, so a failed drain during an outage pushes the next attempt out exponentially, here to
about seventy minutes. And **a save does not wait for the queue**: `CaptureSaver` writes
directly first and enqueues only on a failure that waiting can fix, so the AC-05 capture was
completed by re-running it rather than by waiting for the drain. That is FR-806's "fallback,
not the path" seen from the far side.

Not yet run on a device:
**NFR-302's other two limbs** — app termination and device restart while queued — none of which
AC-10's run exercised; **AC-15**, **AC-09** (hidden calendar offered and actually ticked),
**FR-806a's "Sign in needed" surface is built and unit-tested but has NO device verification.**
It was set up for one on 1 Sep 2026 and the run was abandoned before the grant was revoked, so
what was observed — a clean drain and no sign-in prompt — says nothing about it either way. The
condition was never created. Recorded rather than left as a half-memory, because an
inconclusive run looks exactly like a pass in a log and the surface is the part of FR-806a that
a user actually sees.

The run does need the grant genuinely revoked, and that needs checking **before** the drain is
watched rather than after: Play services answers `authorize()` from its own cached grant
record, so an app can keep calling Google for some time after a revocation at
`myaccount.google.com/connections`. Probe first — `PROBE_DEDUP` returning `found=true` means
the grant is still live and the fixture is not ready. That check is the standing convention in
this file applied to itself; the first attempt read the result without it.

Still open, then: FR-806a's surface, and the consent-bridge cases: rotation and
process death with the consent screen up, which are the only part of this app with no
automated cover at all. FR-804's own gaps join that list: **a queued `UPDATE` has never
drained** — the 28 Aug pass was online throughout, so the worker's update path and the
staleness SRS 1.19 records have only ever run on the JVM — and **the task search has never
capped**, which needs a list longer than ten pages, so the give-up behaviour is JVM-only.

**The phone says nothing about what a save decided, and that is now a known obstacle to
device passes.** `LatchTiming` logs the content step — `content ready, ocr=false, chars=437 in
0ms` — and nothing after it. So from outside the phone there is no way to tell a source-hash
match from an item-key fall-through, and (as the 3 Sep AC-07 reverse run showed before the
developer said which) no way to tell either of those from a Save that was never pressed: all
three leave the same trace, which is an unchanged index and an untouched database.

The desktop says what it did, on screen and in the tray. The phone says it only to whoever is
holding it. This directly defeats the discipline recorded above — *the count is the instrument,
not the screen* — in the one case this file warns about hardest, because the count is identical
in every branch and only the reason differs.

**Built 3 Sep 2026 (SRS 1.72), installed, and not yet seen firing.** `WriteBasis` in `:google`
names which row of §7.2's table answered — including, within row 4, whether the key query
matched nothing or was never asked (SRS 1.25 skips it for a multi-item capture). It is logged
debug-only under the same guard and the same tag as `LatchTiming`, so one grep gives the whole
story of a capture:

```
adb logcat -v time | grep LatchTiming
I/LatchTiming: content ready, ocr=false, chars=437 in 0ms
I/LatchTiming: save decision=Duplicate basis=SOURCE_HASH
```

The line carries **no capture content**, and structurally rather than carefully: its inputs are
an enum, a boolean and a decision taken through `basisSafeName`. The obvious spelling would
have leaked — `WriteDecision.Reschedule` holds a `RescheduleMatch` whose `toString()` carries
the **title stored on the user's own item** — and a test asserts both that the hazard is real
and that the line avoids it.

`CaptureSaver` still has **no** `android.*` import: it takes a sink defaulting to a no-op and
`LatchApplication` supplies the logcat one. `android.util.Log` is a throwing stub under JVM
unit tests, so logging from inside the saver would have broken every save test and cost the
property that makes them possible.

**Not covered, and named rather than assumed:** the FR-806 drain's own FR-803 check is a second
site and is unlogged. It needs no basis — the drain has no FR-804 path to be confused with.

**Seen firing, 3 Sep 2026, and it closed AC-07's mechanism half on its first use:**

```
09-03 17:19:05.238 I/LatchTiming(27095): content ready, ocr=false, chars=437 in 0ms
09-03 17:19:15.912 I/LatchTiming(27095): save decision=Duplicate basis=SOURCE_HASH
```

Those two lines were the **only** output the application produced across the run's 49,705
lines of logcat, which is the privacy half of the design observed rather than reasoned about.

**One method note, because it nearly went into the record as a smaller number.** The first count
taken was 5,834, read off the capture file *while logcat was still writing to it*; the run
finished at 49,705. A count taken from a live file is a count of when you looked, not of what
happened, and the two differ by whatever arrived next. Both captures in this session ended at
`17:41:40` with exit 255 — the phone was unplugged — well after the events they covered, so the
windows were whole; but the arithmetic was taken early and had to be corrected afterwards.

**Two observations from real use, recorded rather than acted on.**

**Ten seconds is tight for anyone who verifies before undoing.** The undo window was missed
twice across passes, both times while checking Google to confirm the write had landed —
which is the natural thing to do before deciding to take it back. FR-807 says "not less
than 10 seconds" so nothing is out of specification, and no change is asked for; but the
number was chosen against the requirement's floor rather than against how the offer is used,
and this is the evidence for revisiting it if it is ever revisited.

**One cosmetic item remains on the multi-date sheet**: FR-509's 50-character truncation can
end a title on a dangling preposition ("…September 6, 2027 at"), which is the truncation
working as specified and reading badly. Fixed since the pass, in one commit: the primary's
badge and FR-510 note no longer sit above the list — everything describing a single candidate
is now header-level only where there is a single candidate, and the title is the exception
because it names the capture rather than a date in it. The same commit closed an **FR-504
miss** found while making that change: the ambiguity notes were primary-only, so in a
multi-date capture an ambiguous date that was not the primary had its resolved reading shown
nowhere at all.

**AC-07 is closed as of 3 Sep 2026.** It was owed from the first day of this project because
the Windows client did not exist; it does, and the criterion passed in the phone-to-PC
direction with the count as evidence.

**The PC-to-phone direction was run on 3 Sep 2026 and passes on the criterion as written**: a
task the PC created on 18 Sept from an email selection — with its title corrected before saving
— was re-captured on the phone, which answered "Already saved. Nothing was written again." and
wrote nothing. Four local stores agree that the phone created nothing.

**What that run could not establish, and what is now owed, is *which query* answered.** The
forward direction was clean because `DesktopSaver` has no FR-804 path at all, so an "Already
saved" there could only be the source-hash query — SRS 1.60 says so, and says it is why AC-07
was worth closing on the desktop first. **The reverse direction has no equivalent guard.**
Android asks FR-803 and, only on a miss, FR-804's `item_key`; §7.2's row 3 — same key, same
resolved date — returns `AlreadySaved` too, and both paths reach the identical sentence. That
is the 1 Sep 2026 defect's own shape, where an "Already saved" was traced to the fall-through
while FR-803's query was returning `items=0`.

The ambiguity is not academic on this run. `item_key` is the **weaker** identity of the two —
the title with every date span blanked — so it survives changes to the surrounding text that
`source_hash` would not. The text reached the phone through a note-taking app; had that
transport reflowed a single line break, `source_hash` would have differed while `item_key`
still matched, and the sentence on screen would have been the same.

**Retired rather than owed**, so it is not mistaken for a gap: FR-804's device check that a
**pre-fix item is offered no update**. The 27 Aug items carrying pre-v1.14 `latch.item_key`
values were deleted from the account on 28 Aug 2026, so the fixture no longer exists, and
manufacturing one would mean writing an item under a derivation the SRS has withdrawn. It is
covered at JVM level instead — exactly one `item_key` query shape is issued and there is no
superseded-derivation fallback, which is the part §7.2 forbids and the part a device could not
have shown. SRS 1.17 records this. No account known to this project now holds an item with a
stale key.

AC-15 is half-observed and deliberately not recorded as passing. A setup run on 26 Aug 2026
took **43 seconds** from launch to the defaults record being written, measured off the
filesystem, which is inside its 60-second budget. But AC-15 also requires that the calendar
is created *only* on finish, and that half has not been watched. Timing is evidence; the
criterion is not met until both halves are.

**AC-17 is no longer structural.** The app holds `INTERNET` now, so "no outbound request to
any non-Google endpoint" is a property of the code rather than of the manifest. It rests on
`ALLOWED_HOSTS` in `data/.../GoogleHttp.kt` — three exact hostnames, checked on the parsed
host, HTTPS only, redirects refused — which every request in the app goes through.
`GoogleEndpointGuardTest` tests it by name. Play services also calls Google for the grant;
that traffic is Google's and will show on a network monitor, but it is not ours to route.
Widening that set is an AC-17 decision, not a refactor.

The §7.2 remote metadata schema is pinned, ahead of the write path rather than behind it.
`RemoteMetadata.kt` in `:data` encodes and decodes it for both transports — `extendedProperties`
on events, an appended `[latch]` line on tasks, which have no metadata field at all. **Nothing
writes it yet, deliberately**: items in a user's account cannot be rewritten, so the schema had
to settle first. SRS §7.2 is now normative and fully specified, because §4.1's three clients
share only the Google account and AC-07 needs all three to derive the same hash from the same
text; `data/src/test/resources/metadata/hash_vectors.tsv` is the conformance suite they must
pass, and is pure ASCII so an editor cannot normalise a case away. `latch.item_key` was added
there: FR-803's source hash can never find a rescheduled item, because a reschedule *is*
different source text, so FR-804 and AC-08 would have been unsatisfiable without it.

`:data` can now write. `insertEvent` and `insertTask` build the request bodies and attach the
§7.2 metadata, and `findEventBySourceHash` / `findTaskBySourceHash` are FR-803. The two
transports are not equally capable and the asymmetry is worth knowing before touching it:
events are filtered server-side by `privateExtendedProperty` and the answer is exact, while
the Tasks API has **no content filter at all**, so the check is a scan bounded to D±1 day —
a day wider than correctness needs, to absorb time-zone skew between two devices, which is
where AC-07 fails silently otherwise. An undated task falls back to a ten-page capped scan
that *reports* giving up rather than returning a false negative; `DuplicateSearch.scanCapped`
is that signal and callers must not read it as "no duplicate".

**Capture saves.** The confirmation screen has a destination chip (FR-904) and a Save button,
and a capture becomes a real event or task in the user's account. `ItemDrafts.kt` maps
FR-506's rows onto `Item`; `CaptureSaver` — held by `LatchApplication`, not the activity,
because the capture window closes on a tap outside it and a write in flight must still finish
— runs the FR-803 check and then the insert.

`AccountDefaults` gained the destination's name and colour, and the record format went to
**version 2**. There is no migration: a v1 record decodes to null, is deleted as unreadable
and setup runs once more. That was the point of the leading version field, and it stops being
an acceptable answer the moment there are users.

**FR-512's interim reading is retired.** It said that with no Capture Inbox to route to, a
user-confirmed item was saved whatever its confidence — and that it died the moment the FR-700
series landed. It has. A capture below the threshold, one with no date at all (FR-506 row 4,
AC-03) and one that cannot be completed (FR-506 row 3) now go to the Inbox, and the button says
**Add to Inbox** rather than Save so the routing is a thing the user chose rather than a save
that quietly went somewhere else. `saveRoute` is that decision as a pure function.

**One narrowing survives permanently and is not a deferral.** A notification-derived capture is
never routed: the Inbox is persistent storage and NFR-206 forbids that layer's content reaching
it, which is the third appearance of the rule FR-805a and FR-210a already apply to the item
description and to webhook delivery. For that layer the interim behaviour stands for good —
confidence shown, save not blocked — because the alternative is losing the capture, and design
principle 1 puts that above everything.

**FR-701's storage is a hand-rolled `SQLiteOpenHelper`** (`LatchDatabase` in `:data`), one
encrypted payload per row under the same `KeystoreCipher` the preferences use. Three tables:
the Inbox, FR-803's index of written items, and FR-807's stored offer. Room stays deferred on
the build-configuration grounds `docs/DEPENDENCIES.md` records.

Three things about it are decisions rather than mechanics.

**The write queue did not move into it.** SRS 1.24 named the per-item written marker as needing
this storage; it does not — the queue record carries it at version 4. What the move would have
cost is a migration of entries holding captures that exist nowhere else, done to tidy a storage
layer, which is the trade NFR-302 forbids. The index went into the database instead, because it
is new and loses nothing by starting empty.

**FR-803's index is stored in the clear.** A SHA-256 digest is not content — NFR-206 says
exactly that of `latch.source_hash` in a Google item — and an index that must be decrypted row
by row to be searched is not an index. Everything with content beside it stays encrypted.

**The Inbox re-parses at the captured instant, never at the instant it is opened.** It stores
the raw text plus the capture's own `now` and zone; FR-515 makes a parse a pure function of
those, so replaying them reproduces the reading the user was shown. Parsing against today's
clock would walk "kal" one day further every time the list was opened, which is design
principle 1's failure inverted — not inventing a date, but quietly moving one.

**FR-803's local index cures the capped task scan, and nothing else.** It is consulted *after*
Google, never instead: Google sees what every §4.1 client wrote and the index sees only this
device, so asking it first would answer "already saved" about an item the user had deleted by
hand and leave them unable to capture it again. Its one job is the question Google cannot
answer — `scanCapped`, the Tasks API admitting it read ten pages and stopped. **It is
deliberately not used for FR-804's capped scan**: a row records what Latch wrote, and SRS 1.19
requires an update's prior state to be what the item held before this app changed it, so
restoring an index row would overwrite a hand edit with a value the user never saw.

**FR-804's offline limitation gets the cure its own note names.** Where there is no network and
the index shows the same `item_key` at a different date, the capture is **held in the Inbox**
rather than queued as a create — the question waits for a surface it can be asked on. The
answer, and the prior state, are still read from Google at match time.

**FR-807's offer survives process death.** It is written to the database as it opens and
surfaces on the home screen once the capture window has gone. Two of its three recorded limits
stand: a new capture still ends the offer, and cross-device undo is still a different feature.
A latent defect went with it — `CaptureActivity` reset the saver unconditionally on every
`onCreate`, so a **rotation** inside the ten seconds silently ended the offer; `reset` is now
keyed on what was captured, so a recreation of the same capture keeps it.

**FR-806 gained the three things its own note recorded as missing.** A **backoff ceiling** of
thirty minutes, because WorkManager's exponential doubles to five hours and a queue that failed
overnight was still waiting at breakfast. An **immediate drain** on OS-reported connectivity or
any successful foreground request — applied only to entries whose last failure was
transport-class, because a 429 is not cured by learning the socket works, and rate-limited to
once a minute so a flaky network cannot turn `onAvailable` into a burst of replaced work. And a
**Retry now** button beside the pending count, which also revives a given-up entry: the note
said such an entry "has no manual retry or dismissal, because the screen that would offer one
is Settings (FR-1000)", and this is that retry on the screen that exists.

`shouldDrainNow`, `retryPlan` and `itemsLeftToWrite` are pure, for the reason `drainable` is
and the reason the drain's own FR-803 check was not.

FR-805a is structural rather than careful: call sites never compose an item's description
themselves, `sourceBlock` does, and it drops the source text for `CaptureLayer.NOTIFICATION`.
AC-22 is a unit test over it.

**A save can be undone.** FR-807: ten seconds, `events.delete` and `tasks.delete`, and one
thing worth knowing before touching it — **undo deletes the ids the save recorded, not the
result of a `latch.chain_id` query**. The chain id is the group identity and is on every item,
but the Tasks API has no content filter, so finding a task chain by it would be the same
give-up-able scan as FR-803's, and undo would be exact for events and best-effort for tasks.
Three limits follow, all in the SRS: the offer dies with the process, a new capture ends it,
and undo from another device would be a different feature, events only. The ten seconds are
protected from the capture window itself — that window closes on a tap outside, so
`CaptureActivity` suppresses its own `setFinishOnTouchOutside` while the offer stands, and
closes the window when it lapses. Every decision about the offer is a pure function
(`undoOffer`, `saveIsOffered`) for the same reason `saveBlocker` is.

**The write queue is built.** FR-806, on WorkManager. Four things about it are decisions
rather than mechanics, and each is written up against the requirement in the SRS.

The queue is a **fallback, not the path**: a save still writes directly and enqueues only on a
failure that waiting can fix, because routing everything through the queue would cost FR-803
its immediate "Already saved" answer, which AC-07 depends on. `isWorthRetrying` in `:data` is
the classifier, and a 403 or a 400 is still a reported failure rather than an entry that would
never drain.

**The payload is not in WorkManager.** Its database is unencrypted and its input data is capped
around 10 KB, so entries live in `EncryptedWriteQueueStore` under `KeystoreCipher` and the
worker carries nothing but the instruction to drain. Records are JSON there, not the
unit-separated format `EncryptedPreferences.kt` uses, because an FR-805 body is free user text
with newlines in it — the file says why.

**FR-803 runs again at drain**, per entry, immediately before the insert. Without it two
offline captures of one message become two items, which is AC-07 failing inside AC-10.

**A queued entry is not drained inside its FR-807 undo window** — `drainable` is that rule, and
it exists so an undo is never a race between dropping a queue entry and chasing an item that
has just been written. `CreatedItem` is a sealed type for the same reason: `Written` is undone
by a delete, `Queued` by dropping the entry.

Two limits are recorded rather than hidden: a queued capture does not survive a device transfer
(Keystore), and an entry given up on stays visible but has no manual retry until Settings
(FR-1000). Adding `androidx.work` also merges four permissions into the manifest — the manifest
comment names them, because that file is where the app's promises are read.

**Which of several dates wins is a recorded reading, not an accident.** `PRIMARY_RANKING` in
`:parser` — a date with a time, then an explicit date over a calculated one, then earliest
mention — is written up against FR-505 in the SRS. FR-505's own confidence is deliberately not
a key: a range's year is written once at the end, so ranking on it makes the end of every
"from A to B, 2026" the primary, which is the defect the ranking exists to fix. `candidates`
stays in document order; only `primary` is ranked.

Weekday corroboration goes by the day the date falls on, not proximity — proximity alone let
one weekday be absorbed into two dates at once, and overlapping candidate spans corrupt §7.2's
`item_key`, which blanks the primary's span out of the title. The exception is a weekday in the
same phrase as a date, which is absorbed even when it contradicts it: §7.2's own example "PTM on
Friday 12 September" is such a case, because 12 September 2026 is a Saturday.

**FR-804 is built and verified on a device.** A save runs FR-803, then — only on a miss —
searches `latch.item_key`, and a match on a different date offers update-or-create before
anything is written. `writeDecision` in `:app` is §7.2's table as a pure function, one test per
row. Undo of an update is a **restore**, never a delete: `CreatedItem.Updated` carries the
dates read at match time, which after the patch exist nowhere else. Four things there are
decisions rather than mechanics, each written up in the SRS: "same date" means an update would
change nothing (so a same-day time change is a real reschedule), the time zone is excluded from
that comparison, a recurring event is refused outright because `events.patch` on a series master
moves every occurrence, and FR-803 is deliberately **not** re-run when a queued `UPDATE` drains.
Detection is scoped by transport — a task capture queries only tasks — so two items sharing a
date-free title across the two transports cannot match each other.

**FR-511 is built and verified on a device.** Every date in a capture is a row with its own
checkbox, badge and date line, all ticked to begin with, and a save writes a chain of N items
sharing one `chain_id` and — per SRS 1.23 — one `item_key`, because §7.2 blanks every date
span rather than the primary's. A date range is one row and an Event even with no time, its
end held inclusive by the parser and converted to Google's exclusive end date in `ItemDrafts`.
Two things there are readings rather than mechanics: a multi-item capture is **never** offered
as an FR-804 reschedule (accepting one would patch a single item and discard the rest), and
FR-803 runs **once per capture** before the chain is written, at the saver and at the drain
alike. The queue holds a chain as one entry for the same reason.

**The three confirmation-screen requirements FR-807's note listed as unmet since SRS 1.12 are
built.** FR-506 row 3's date picker is on the sheet, FR-507's Event/Task override is the badge
itself, and FR-510's past-date follow-up is offered. Four things there are readings rather than
mechanics, all recorded in SRS 1.44.

**The picker is inline and already unfolded, not a modal.** FR-506 row 3 says it "opens
automatically with suggestion chips", so the chips — Today, Tomorrow, In a week — are on screen
without a tap and the full calendar is one tap behind them. A modal calendar appearing unbidden
over a floating capture sheet would cover the very text the user is confirming. Nothing is
pre-selected: design principle 1 forbids the *app* choosing a date, and a chip the user taps is
the user choosing one.

**FR-507's single control is the badge.** FR-508 already requires the EVENT/TASK badge to be
visible at all times and, in a chain, on every item, so putting the override on it satisfies "a
single control" literally and puts it where the classification is shown. It is on the Inbox's
badge too, because FR-507 says "before saving" and a row is saved from there.

**What an override to a Task costs is disclosed before the tap, not after.** §8.1: a time is
discarded, and a range loses its closing day. This is the one place in the app where a user
action deliberately loses something they wrote. It also supplies a second way out of FR-506
row 3 — a time with no day, made a to-do, is an undated to-do — which is a consequence rather
than a design and is written down so a later reader does not take it for an accident.

**FR-510 is applied per candidate, and outranks FR-507.** Its "where the *only* date found is
in the past" was written when a capture produced one item; with FR-511 a capture can hold a past
date beside a future one. The follow-up is an **undated** task carrying the past date in its
notes — undated because a follow-up needs a date only if the app picks one. There is no opt-out
and that is the requirement rather than a narrowing: "shall not create a dated item" admits
none, so the offer is take-it-or-leave-it and leaving it is unticking the row. A past row
therefore cannot be overridden into an Event. **AC-04 is reachable for the first time.**

`SheetEdits`, `withAssignedDates`, `withTypeOverrides`, `typeChangeCost` and `canOverrideTo` are
pure and applied in exactly one place, so the badge, the checkbox, the blocker and the write all
read the same rows — a screen that applied them itself would eventually show one thing and save
another.

**Recipes reach a screen (FR-601 to FR-608).** A chooser on the confirmation sheet expands one
captured date into a chain, each step tickable (FR-608) and each saying which non-working days
it stepped over (FR-606). `RecipesScreen` is FR-603's editor. Five things there are readings.

**A recipe is offered only for a capture holding exactly one date** — the same narrowing SRS
1.25 took for FR-804, for the same reason: FR-601 expands *one* date, and four raises a question
the SRS has never asked. The reason is on screen rather than expressed as a missing control.

**Applying a recipe routes to Google whatever FR-512's threshold says.** Picking a template
against a date the user can see is a stronger confirmation than the threshold tests for.

**FR-607 holds structurally**: `recipeItems` is the single place a chain's destination is
chosen. `Recipe.targetCalendarId` is deliberately unused — routing to a calendar the user has
not seen is what FR-906 forbids, and the picker that fixes it arrives with FR-905 in Settings.

**FR-603 never mutates a built-in.** FR-602's eight ship as code; editing one stores a copy
carrying its id, which shadows it. Deleting that copy restores the shipped one; deleting one of
the user's own removes it. A duplicate always mints a new id.

**Reminders finally reach Google.** `RecipeStep.reminderOffsets` existed and nothing read it, so
FR-602's "meeting with preparation" shipped a thirty-minute reminder that never left the device.
They are written as explicit overrides and **omitted entirely where there are none**, which
leaves Google's own calendar defaults in force — an empty override list would mean "no reminders
at all", which is a different request and a decision this app has never taken. Tasks get none:
Google Tasks has no reminder, which is §8.1 one field over. `Item` carries them and the queue
record goes to version 6.

**An SRS contradiction is resolved rather than worked around.** §5.8 said a recipe's steps carry
different `item_key`s; §7.2 at v1.23 says every item of one capture shares one and records that
the per-item alternative was rejected. v1.23 governs, §5.8's sentence is corrected in place, and
SRS 1.45 says why — a second client that implemented the withdrawn sentence would derive keys no
other client reproduces.

**FR-1001's settings record is built ahead of its UI** (`LatchSettings`, `EncryptedSettingsStore`),
holding the working week, FR-605's holiday additions and removals, the date order, the default
duration and reminder lead times, the FR-512 threshold, the time zone, FR-1003's per-layer
toggles and FR-905's routing rules. Its defaults are field for field the behaviour the app
already had, so an unreadable record is a working app with some preferences forgotten. Slice 5's
Settings screen is UI over it; FR-905's rules are stored and **not yet applied**.

AC-06 is reachable for the first time.

**Settings is built, FR-1004 included.** The screen follows FR-1001's list in the requirement's
own order, so reading one against the other is a matter of going down both together. Six things
are decisions.

**Shipping FR-1004 makes two documentation obligations live.** FR-1102's privacy-policy clause
and FR-1103's Data Safety declaration were conditional on the webhook shipping; it has. Both are
now items on FR-1108's release gate, because they are things only the publisher can do and
nothing in the code will remind whoever does.

**The webhook does not go through `ALLOWED_HOSTS` and must not.** That guard holds AC-17 for
every request this app composes, and widening it to carry a user endpoint would destroy the
property it exists to hold. A webhook is sent by a separate client with its own narrower rules —
HTTPS only, no credentials in the authority, no redirects, one attempt, five-second timeouts.
That separation is also what makes FR-1004b's "not in the write queue" **structural**: every
entry there drains through the guard and would be refused.

**FR-1004a's list is enumerated in code, not serialised from a type.** A payload built by
serialising `Item` would grow silently the next time `Item` did, and what would leak is whatever
was added. A test asserts the exact key set.

**Entering an endpoint and enabling delivery stay two acts**, which is FR-1004 asking for both,
and plaintext endpoints are refused outright rather than warned about.

**FR-1003's toggles are honoured differently by layer, and the screen says so.** The Quick
Settings tile is its own component and is genuinely disabled. Text selection and the share sheet
are three intent filters on **one** activity, so those toggles are enforced when the capture
arrives — Latch still appears in the share sheet and declines with a reason. Splitting the
activity per layer is the cure and is **owed**, not pretended away.

**FR-905/FR-906/FR-904/FR-907 hang together on one pure function.** `destinationFor` is called
by the sheet and by the saver, so the calendar shown is the calendar written to. The list is
fetched on demand, not on open, because NFR-101 budgets the capture path 800 ms. FR-907 counts
overrides per source and *offers*; nothing is written by counting.

**One consequence is recorded rather than left to be found**: on a cold start the first frames of
a capture use the shipped default settings, because the record is read asynchronously and a
capture may be the first thing in the process. The sheet re-parses when it arrives — NFR-102's
own pattern — and Save is blocked meanwhile by the destination read, which is a round trip to the
same store. The alternatives were a blocking main-thread read or an asynchronous text path, and
NFR-102's note is explicit about the second.

AC-12 is met by what Settings does *not* do: switching mode writes one field and touches nothing
else.

**FR-908, NFR-205 and FR-1005 are built.** Four things there are decisions.

**FR-908's failure case is a failure to *read the list*, not a missing calendar.**
`calendarList.list` fails for want of a network far more often than because a calendar was
deleted, so an unreadable list changes nothing — falling back to primary because the phone was
on a train would move a user's captures for a reason unrelated to their calendars. "Missing" and
"lost write access" are one test, because FR-901 already filters to owner and writer. A rename is
corrected silently; only a fallback informs the user, which is the one case where their captures
start landing somewhere they did not choose.

**NFR-205's order matters.** The revoke goes first because it needs a token; the local deletion
happens whether or not it succeeded, because the user asked for their data gone. The outcome
says which half worked. Play services has no revoke to call — `AuthorizationClient` offers none
and `GoogleSignIn.revokeAccess` belongs to the API this app does not use — so it is the OAuth2
endpoint through the same guard as everything else. `oauth2.googleapis.com` was listed in
`ALLOWED_HOSTS` against exactly this call, so the allowlist does not move. A 400 counts as
success, on `alreadyGone`'s reasoning. The deletion is **enumerated, not swept**: adding a store
is a change to `deleteAllLocalData`, visible in a diff, rather than something a directory wipe
would silently start missing.

**FR-1005 is treated as a wire format**, because the reader is somebody else's calendar program.
CRLF, folding at 75 **octets** (a Devanagari title folded by characters would be cut through a
UTF-8 sequence), backslash escaped first. A task is a `VTODO`, an undated one carries no `DUE`,
and **no §7.2 metadata is exported** — those keys mean nothing elsewhere and a `source_hash` in
a file the user emails is a digest of their own message. An export mints fresh item ids: a UID
is how a reader tells a new item from a replacement, and the save path's ids are stable by
design.

**The export leaves through a `FileProvider` rooted at one cache subdirectory**, cleared on
every export. It is deliberately not enumerated by NFR-205's deletion — it is a copy made to
hand to another app, in the directory the system clears anyway.

**The notification listener is built (FR-208 to FR-212), off by default.** Five things there
are decisions.

**`POST_NOTIFICATIONS` is the app's first runtime permission**, and it is not about reading:
FR-211 posts an offer, and since Android 13 posting one needs it. Without it the offer would be
created and silently dropped. Requested from the disclosure screen at the moment the layer is
turned on, never at launch. Lint found this before a device could.

**The message text lives in the process's memory and the posted notification carries a key.**
That is how FR-210 and NFR-206 are met structurally: the obvious implementation — the message in
the `PendingIntent`'s extras — hands it to the system's notification manager, which is not this
app's memory. A process death loses an outstanding offer, which is correct rather than a
limitation. The holder is bounded at twenty.

**FR-212's app list is built from what the listener has seen.** A picker of installed apps needs
`QUERY_ALL_PACKAGES`, which this app will not spend on a settings screen. A package name is not
notification *content*, which is what NFR-206 governs. Nothing is ticked by default.

**Every filter is a pure function and the listener holds none of them** — nothing inside a
`NotificationListenerService` is JVM-reachable, which is the shape that hid the drain's FR-803
check and `decodeBitmap` for a slice each. Latch's own notification is skipped **first**, before
the toggle and the monitored list are even consulted: this layer posts a notification, and a
listener reading its own would offer to capture its own offer for ever.

**The disclosure screen carries two statements the SRS asks for by name** — FR-210a's webhook
suppression and FR-805a's source-text exclusion both say they "shall be stated in the
notification-access disclosure screen". Both are properties of `CaptureSource` in code and
cannot be forgotten by an implementation; what could be forgotten is telling the user.

AC-19 and AC-22 are reachable, and hold as properties of `CaptureSource` rather than as code in
this layer.

Sign-in works only on builds whose signing certificate is registered against the Android
OAuth client. There is no release signing config, so that means debug builds from a machine
whose debug keystore fingerprint is registered. All four FR-002 scopes are Sensitive, so
until OAuth verification (SRS §8.6) only test users on the consent screen can sign in.

FR-105 is load-bearing and structural, not a matter of care: `com.latch.android.setup` is
pure Kotlin, `SetupEffect.Commit` is the only effect that can reach `calendars.insert`, and
`SetupEvent.FinishRequested` is the only event that produces one. AC-15 and AC-16 are JVM
unit tests over that reducer. Keep it that way.

**NFR-502 is not met, and this is the first place it is written down.** The requirement asks for
a corpus of "no fewer than **300** real-world input strings and expected outputs". There are
**113** — eight added on 3 Sep 2026 for year-first dates and three more that day for the
first real desktop capture, after building §7.2's
date-free-title vectors found that `Renewal 2027-10-14` resolved to October **2026**. Every one of them earns its place — each was added against a rule or a defect, and the
v1.21 voice-typing row that FR-511's slice was told to activate is active — but 113 is a third of
what the requirement names, and the gap has been carried silently since the corpus was started.

It is recorded rather than closed because closing it is its own slice: two hundred more rows is
not a matter of typing, it is a matter of finding two hundred *real* messages, and a corpus
padded with invented ones would satisfy the number while weakening the thing the number is a
proxy for. §3.1's users are the source — school circulars, bills, courier notifications — and
dogfooding is where they come from.

### Verified on the device, 2 Sep 2026 — the instrumented suite, 21/21

Pixel 6 Pro, Android 17 (API 37), debug build at `5886e0c` plus the fixture fix. **21 tests,
13.3 seconds, no failures.** Scoped narrowly below, because a device pass that overclaims is
worse than none — and note that **nothing here touched the developer's Google account**: every
API in the suite is a fake, and the OCR half reads two bundled assets.

| Check | Result |
|---|---|
| The app installs over the previous build and **starts** | **Pass.** `adb install -r`, account defaults survived the update, launch canary reached RESUMED. That is the gap the canary exists for and nothing more. |
| The three new manifest components are registered | **Pass.** `LatchNotificationListener` with `BIND_NOTIFICATION_LISTENER_SERVICE`, the `FileProvider`, and `CaptureTileService`, all in `dumpsys package`. |
| `connectedAndroidTest` **leaves the app installed** | **Pass.** The AGP-injected property works; both APKs survive. The annoyance recorded since 27 Aug is closed. |
| **`:data`'s SQLite works at all** | **Pass, and this was the session's largest unknown.** The database is created on first use and every store round-trips through SQL *and* `KeystoreCipher`: the Inbox row with its captured instant and zone, the FR-803 index by hash and by key, FR-807's stored offer including the prior dates an undo must write back, a user recipe with its steps, FR-1001's settings, and NFR-203's secret with its mask. Fifteen tests, all green. |
| FR-806's queue on the real store | **Pass.** A four-item chain is **one** entry carrying all four (SRS §7.1 at v1.23); SRS 1.24's per-item marker survives to disk; a given-up entry stays and is revived by "Retry now"; and a drain that finds the message already saved **retires without writing** — the 31 Aug defect's own shape, against the storage that had never been exercised. |
| **FR-215: the image path is alive** | **Pass.** A real PNG decodes and recognises rather than returning `UNREADABLE_SOURCE`. That is the elvis-binding defect that shipped through three commits with 328 tests green, now covered by a test that would go red for it. |
| **Both ML Kit artifacts are present and merging** | **Pass.** No codepoint in `U+0980–U+09FF` anywhere in a Latin screenshot's recognised text. This is the only check that the +8,082-byte Latin artifact is still in the APK; one line in a build file undoes it. |
| **FR-805b's extract is strictly shorter** | **Pass**, on a real recognition — and the half a length check would miss: neither the sender's name nor the amount paid survives into it. SRS 1.32's stated test of any implementation, met. |
| FR-207: a PDF renders, reads, and reports its coverage | **Pass.** `letter.pdf` → 2 of 2 pages, and correctly **not** reported as capped. |
| **Slice 1's page progress** | **Pass.** The callback fires `1 of 2` then `2 of 2`, before each page rather than after. The sequence is verified; **the line reaching the screen is not** — that is still owed. |
| **FR-509b — the title, corrected before it is written** | **Pass, end to end, on a real capture.** A photographed schedule table drafted as `Location Notes`; the user tapped Edit, typed `MTG to review PMGATI`, saved, and that is the event's title in Google Calendar. The local index shows one EVENT written 28 seconds after the sheet opened, nothing queued. |
| **FR-507 is discoverable** | **Pass.** "Tap the badge above to switch between event and to-do." is on the sheet, and the Edit control beside the title is legible and in reach. |
| **The sheet fits** | **Pass**, on a device at a large font scale, with the hint line and the title control both present: Close, Export .ics and Save are all visible without scrolling. This is the layout that had put Save below the glass an hour earlier. |

**What the same session did *not* establish, though I said it would.** The FR-803 stale-index
case — an event deleted by hand in Google, whose local index row survives — was *not* tested,
because the instrumented suite had run in between and its `index.clear()` wiped the row. The
reading stands unverified: capture, delete the event in Google, capture again, and it should
save rather than answering "Already saved".

**And the suite interfered with a live device twice in one session**, which is the lesson worth
more than either fix. It left a fake write in a real queue (fixed with `@After`), and it cleared
an index row that was about to be the fixture for a test. The second is inherent to a suite that
owns the real stores under their real names, and is why its KDoc now says so out loud.

**What this does not say.** Nothing above involves Google: no write, no read, no account. Every
acceptance criterion in §10 is still owed, as is every screen — the confirmation sheet, the
Inbox, Recipes, Settings, the disclosure screen — the notification listener, the FR-1004
webhook, the `.ics` share, FR-908 and NFR-205. The storage tests also write and read within one
process, so **survival across a force-stop is still unverified**; that row stays in the backlog.

**One defect was found by running it, which is what running it was for.** All five OCR tests
failed on the first attempt with `FileNotFoundException: latin-chat.png` — the test read its
fixtures from `targetContext`, which is the *app's* assets, while `src/androidTest/assets` is
packaged into the **test** APK and reached through `getInstrumentation().context`. SRS 1.50 had
recorded the suite as "never executed, so its fixtures may not be reachable"; they were not.

## The Windows client (FR-300 series)

Built in the session of 2 Sep 2026, on the same terms as everything else here: **JVM-verified
and HUMAN-OWED.** It has never been used by a person, and no capture it made has ever reached a
Google account, because FR-001's Desktop OAuth client does not exist yet.

**It is Kotlin on the JVM, not C#, and that decision is SRS 1.52.** Three requirements name
Windows machinery — FR-302's `RegisterHotKey`, FR-303's `Windows.Media.Ocr`, FR-305's MSIX —
and the obvious reading of those three is a C# application. §7.2 outranks them: its `item_key`
clause "is the one part of §7.2 that depends on parser behaviour... where three independently
written clients are most likely to drift", and its own remedy is conformance vectors, which
detect drift rather than prevent it. Sharing the compiled parser removes the failure. That was
only possible because `:parser`, `:recipes` and `:core-model` were kept free of Android from the
first commit for an unrelated reason.

**All three Windows primitives were probed before the decision, not after**, and all three work
through what Windows already ships — no SDK, no dependency:

| Primitive | How | Measured |
|---|---|---|
| FR-303's recogniser | `Windows.Media.Ocr` via the PowerShell WinRT projection | **109 ms** on `three-dates-v4.png`, against ML Kit's 878 ms on the phone. Whole bridge including process startup, **~570 ms** |
| FR-302's hotkey | `RegisterHotKey` P/Invoke, compiled by the `csc.exe` inside Windows | Registers; a second registration correctly returns 1409 |
| NFR-203 at rest | DPAPI through `System.Security` | ~700–900 ms per call, paid at sign-in and startup, never by a capture |

**What works, with tests that ran against the real thing.** Recognition of the Android OCR
fixtures including the EXIF-rotated photograph; DPAPI round trips including a tampered
ciphertext; a real loopback HTTP server answering a real request and refusing a forged `state`;
`RegisterHotKey` succeeding and reporting an already-held combination. The application starts,
installs a tray icon, registers the hotkey, opens a popup and composes a write.

**FR-806's queue is built.** An offline save is held under DPAPI and drained by a thread of
the application's own. Two narrowings are real and are not gaps to be closed by tidying:
there is no WorkManager, so **the queue drains only while Latch is running** — a capture
survives termination and restart, as NFR-302 asks, but is not written until the application
runs again; and connectivity is a **weak proxy**, a non-loopback interface being up, because
no OS callback is reachable from a JVM. Being wrong costs one early attempt that backs off
again.

**One inversion of this project's own record discipline is deliberate.** Every other store
here drops an unreadable record; the queue **keeps** one, because dropping a queue entry
loses a capture. It is carried through every rewrite and counted, so the tray can say how
many lines this build could not read.

**FR-804 and FR-807 are built**, following the Android readings rather than re-deciding them:
the offer quotes the title stored on the existing item, computes both weekdays from the dates,
has **no default answer**, and takes Save away while it stands. Undo is one of three operations
chosen by what the save did — delete, drop, or **restore** an update, never delete one.

**A live defect was found by running the offline path.** An offline save whose access token had
expired reported REFUSED instead of queueing, and the capture was lost — SRS 1.39's shape
arrived at from a different direction. `accessTokenOrThrow` now separates "no network"
(retryable, so FR-806 holds it) from "grant revoked" (permanent, so it is reported).

**What is owed and is not pretended at.** The FR-900 destination picker: setup takes the
Option B default and says which calendar it chose. No Capture Inbox, no Settings screen, no
recipes, no webhook, no notification capture. FR-306's share target. FR-305's MSIX, which needs
the Windows SDK. And **nobody has pressed the hotkey, Update, or Undo**: each is verified as far
as a machine can verify it, and the keystroke and the click are a person's.

Two asymmetries with Android are permanent rather than gaps.

**The desktop stores a refresh token; Android stores nothing at rest.** Play services holds the
grant on the phone, and re-authorizing a granted scope set returns a token with no UI, so NFR-203
is satisfied there by absence. There is no Play services here, so the alternative to keeping a
refresh token is a sign-in on every launch. It goes under DPAPI. Same requirement, opposite
mechanism, and a machine transfer loses it exactly as a device transfer loses a Keystore key.

**Recognition is a property of the machine, not of the application.** Android bundles ML Kit's
Latin and Devanagari models and pays 12.83 MB. `Windows.Media.Ocr` reads only installed language
packs — the development machine offers `en-GB`, `en-US`, `ko` and two Chinese variants and **no
Devanagari at all**. NFR-404's Hinglish is romanised and unaffected; a Devanagari image will
capture on the phone and not on the desktop, and `EmptyCapture.NO_RECOGNISER` exists so the user
is told which and where Windows adds packs.

**One defect was found by running it, and no test would have reached it.** `RegisterHotKey` is
system-wide and the registration lives in the sidecar process. A shutdown hook releases it on an
ordinary exit — but Task Manager's End task and a crash send no signal, so a killed application
left a sidecar holding Ctrl+Shift+K. What a user notices then is a shortcut that silently does
nothing in *every* application on the machine, with no error and nothing to blame. The sidecar
now waits on the parent's process handle alongside its message queue. Verified by hard-killing
the application and re-registering the combination afterwards.

### Installed after the four Windows slices of 3 Sep 2026

| Client | State |
|---|---|
| Windows | **Installed and running**, from `install-local.ps1 -StartWithWindows`, re-run at the commit that fixes FR-701 and FR-1004b — `:core-model` and `:webhook` moved under it, so the copy from the FR-600 commit was stale. A launch canary was run and is **all that was watched**: a fresh process started, stayed up for eight seconds and wrote nothing to stderr — so the tray installed, the FR-302 sidecar registered, the queue runner started, and FR-1001's settings were read across the newly batched DPAPI bridge. **Nothing was clicked.** Every screen these four slices added is in the human backlog below. |
| Android | **Reinstalled 3 Sep 2026 at the commit that fixes FR-701 and FR-1004b.** `adb install -r` over the previous build; `account_defaults.xml`, `settings.xml`, `secrets.xml`, `write_queue.xml` and `latch.db` all survived, so setup did not run again and nothing was wiped. Launch canary: `MainActivity` reached `topResumedActivity`, no `FATAL EXCEPTION` in logcat. **That is all that was watched** — the instrumented suite was **not** run, because it destroys the app's local data and this phone is in real use. |
| Android (before that) | **Was not reinstalled for the four Windows slices, and it did not matter for AC-07.** The four slices are Windows-only, and the shared modules moved code rather than changing what it computes: `:parser` is untouched, `TitleDerivation` and `RemoteMetadata` are untouched, `parseContextFor` is what `CaptureActivity` already did inline, and `IndianHolidays.around` is the window `LatchApplication` already composed. So the phone derives byte-identical `source_hash` and `item_key` values to the ones it derived this morning, and the reverse half of AC-07 can still be run against the build that is on it. |

### The shared parser change of 3 Sep 2026 — installed and device-checked

A relative word adjacent to an explicit date is now one commitment (SRS 1.62). The change is in
`:parser`, which both clients compile, so both had to be rebuilt before they would read the same
email the same way.

| Client | State |
|---|---|
| Windows | **Installed and running.** `install-local.ps1` re-run, relaunched from the shortcut. |
| Android | **Installed 3 Sep 2026 12:48**, `adb install -r` over the previous build. Account defaults, the FR-803 index, the queue and the settings all survived the upgrade. Launch canary passed: `MainActivity` reached RESUMED, no crash. |

### Closing AC-07 — the oldest open criterion in this record

AC-07 reads "capture the same message **on phone and PC**". Its phone half passed on 27 Aug
2026; the other half has been unclosable since the project began because there was no PC
client. There is one now. This is the procedure, written before the run so the run cannot be
graded against whatever it happens to produce.

**What would make this fail, named first.** The PC writes a second item instead of answering
"Already saved. Nothing was written again." Everything below exists to make that outcome
possible if the mechanism is broken, and to tell it apart from three ways of passing by
accident.

**Three ways to pass without meaning it, and the guard against each.**

1. **The PC answers from the wrong query.** On 1 Sep 2026 an "Already saved" on Android was
   traced to FR-804's `item_key` query falling through to §7.2's decision-table row 3, while
   FR-803's `source_hash` query was returning `items=0` against a hash carried by two events in
   that very calendar. The message on screen is identical either way. **Guard:** the desktop
   has no FR-804 path at all — `DesktopSaver` runs FR-803 and nothing else — so an "Already
   saved" here can only be the source-hash query. That is a property of this client and is the
   reason it is worth closing AC-07 on this client first.

2. **The two clients wrote to different calendars, and the PC found its own item.** FR-803's
   event query is scoped to a calendar id. **Guard:** before the run, confirm the desktop
   adopted the calendar the phone already made rather than creating a second one named "Latch"
   — `existingLatchCalendar` is written to do this and `DesktopSetupTest` pins it, but the
   account is the only place it is true. Check the calendar list in Google Calendar: there must
   be exactly one Latch calendar.

3. **The text differed, so the hashes differ, and the PC wrote a genuinely new item.** This is
   the likeliest false failure rather than a false pass, and it is a fixture problem.
   **Guard:** the message must reach both clients byte-identical. Send it to yourself once and
   copy it from the same source on both, or type it into a note synced to both. Do **not** hand
   it between clients through anything that reflows text.

**The capture must be text, not an image.** §7.2 records the OCR hash wobble: the same image
recognised twice can differ by a character, and the two clients use different recognisers
entirely — ML Kit and `Windows.Media.Ocr`. A cross-client image capture is *expected* to miss,
and testing AC-07 with one would produce a failure that proves nothing.

**The run.**

1. Both clients signed in to the same Google account, and the phone's Latch calendar already
   exists. Confirm exactly one Latch calendar in the account.
2. On the **phone**: capture `Board review 8 October 2027 at 15:00` and save. Confirm the event
   is in Google Calendar. Note its `latch.source_hash` if you can see it; you do not need to.
3. On the **PC**: copy the identical text, press Ctrl+Shift+K, and Save.
4. **Expected:** the tray says *Already saved. Nothing was written again.*, and Google Calendar
   still holds **one** Board review event.
5. Then the reverse, which is not the same test and is the half nobody thinks to run: capture a
   fresh message on the **PC** first, then the same text on the **phone**. The phone must answer
   "Already saved" too. FR-803 is symmetric by construction — one query against one index — but
   the two clients compose that query in code that has only ever been exercised in one
   direction on a real account.

**What to record.** Whether each direction passed, the number of Board review events in the
account afterwards (the count is the evidence; the message on screen is not), and which
calendar they are in. A pass in one direction and a failure in the other is a result worth
having, not an inconclusive run.

**If it fails.** The first thing to check is the hash, not the code: capture the same text
twice on the *same* client and confirm it answers "Already saved" to itself. If it does, the
clients disagree about the text; if it does not, FR-803 is broken on that client and AC-07 is
not what is wrong.

### What the Windows client still lacks — audited 3 Sep 2026 against the SRS

Read from the source rather than from memory. The order is by how much the absence costs.

**A third of the same shape, found by opening the window (SRS 1.75, 4 Sep 2026).** FR-602's
eight built-in recipes were rendered **four pixels tall** and the Recipes window read as empty:
each row clamped its `maximumSize` to `preferredSize.height` *before its children were added*,
so it read an empty container's height, and the parent `BoxLayout` honours that. The model was
never wrong — all eight were built and added — which is exactly what makes this class invisible:
every JVM test passes and an audit of the module against the SRS finds the capability present.
The fix removes the ordering rather than guarding it, since Swing layout ordering is not
JVM-reachable: the row builder takes its children as a parameter and adds them before clamping.
The other six clamps in this client are on text fields, whose preferred height is intrinsic, and
are correct.

**The two that were worse than absent are fixed (SRS 1.65, 3 Sep 2026).** FR-507's badge is a
button that flips the row and states §8.1's cost before the press; FR-506 row 3 has Today /
Tomorrow / In a week and a date spinner, with nothing pre-selected. `SheetEdits` moved into
`:wire`, so both clients apply those answers through one `withEdits`. **FR-506 row 3's chips were
pressed on 4 Sep 2026** and behave: the chips are on screen without a tap, nothing pre-selected,
and *Tomorrow* completed the row and ticked its checkbox. **FR-507's badge has still not been
pressed by anyone on this client** — the model is tested, the Swing is not.

**Absent, and plainly so.**

| Family | State on Windows |
|---|---|
| ~~**FR-1000 Settings**~~ | **Built 3 Sep 2026 (SRS 1.67), JVM-verified and HUMAN-OWED.** FR-302's hotkey, FR-504's date order, the default duration and reminders, FR-512's threshold, FR-605's working week and the time zone. `LatchSettings` moved to `:core-model` and `parseContextFor` to `:wire`, so a preference now actually reaches the parse — until this slice the desktop built a bare `ParseContext(now = LocalDateTime.now())` and **every FR-1001 preference was inert here**. Still absent and **named on the screen** with their requirement numbers: FR-900's picker and with it FR-1002's Option A/B switch, FR-1003's layer toggles, FR-600's recipes. **Nobody has opened the window or changed the hotkey.** |
| **FR-900 destination** | `DesktopSetup` takes the Option B default and says which calendar it chose. No picker (FR-901/902/904), no hidden-calendar badge (FR-903), no routing rules (FR-905), no override-three-times offer (FR-907), and **no FR-908 refresh** — a Latch calendar deleted in Google would not be noticed. |
| ~~**FR-700 Capture Inbox**~~ | **Built 3 Sep 2026 (SRS 1.66), JVM-verified and HUMAN-OWED.** `saveRoute` moved into `:wire`, so both clients route on one compiled decision; the store is `inbox.dat` under DPAPI, one row per line. FR-701 to FR-705 all reach a screen. **Nobody has opened the window.** |
| ~~**FR-600 recipes**~~ | **Built 3 Sep 2026 (SRS 1.69), JVM-verified and HUMAN-OWED.** A chooser on the capture popup, FR-606's skipped-days line on each step, FR-608's per-step ticks, and FR-603's editor behind the tray. `RecipeChain` moved into `:wire` and FR-603's rules into `:recipes`, so both clients expand one recipe the same way. **Nobody has applied one, and no chain has ever been written from this client.** |
| ~~**FR-1004 webhook**~~ | **Built 3 Sep 2026 (SRS 1.68), JVM-verified and HUMAN-OWED.** The payload, endpoint rules, masking and sender moved into a new `:webhook` module — separate from `:google` so that module's own description stays true, which is what AC-17 rests on. Endpoint under DPAPI, masked on screen, FR-1004's warning above the field, FR-1004b's offline consequence stated. **No delivery has ever reached a real endpoint from either client.** |
| **NFR-205 disconnect** | Nothing. Signing out forgets the local sign-in and the destination; it does **not** revoke the grant at Google, and there is no "delete everything" action. `oauth2.googleapis.com` is already in `ALLOWED_HOSTS` for it. |
| **FR-305 MSIX** | Publisher work; needs the Windows SDK. `docs/RELEASE-WINDOWS.md`. |
| **FR-306 share target** | `[SHOULD]`. Nothing. |
| **FR-307 Outlook add-in** | `[LATER]` in the SRS. |
| **FR-110 multiple accounts** | `[SHOULD]`. One account, as on Android. |
| **NFR-401 accessibility** | Partly by construction — Swing reaches the Java Access Bridge, rows carry accessible names, everything is keyboard-reachable — but never tested with a screen reader, and no contrast or scaling pass. The same gap Android has. |

**Deliberately narrowed rather than missing**, so they are not counted above: no notification capture (FR-208 to FR-212 are Android layers), and the queue drains only while Latch runs.

**What is built and working** — for completeness, since the list above is long: FR-301, FR-302,
FR-303, FR-304, FR-002's grant, FR-601 to FR-608, FR-701 to FR-705, FR-801, FR-802, FR-803,
FR-804, FR-806, FR-807, FR-1001 (for what this client has), FR-1004/1004a/1004b, FR-1005, and
FR-502/509/509a/509b/510/511 through the shared modules.

**What the Windows client still lacks, after 3 Sep 2026**: FR-900's destination picker and with
it FR-1002's Option A/B switch, FR-305's MSIX, FR-306's share target, FR-110's multiple accounts,
NFR-205's disconnect, and NFR-401 tested with a screen reader. Everything else in the audit above
is built and awaiting a person.

### The tray menu, rebuilt from a dogfooding report (SRS 1.82, 4 Sep 2026)

**JVM-verified and HUMAN-OWED.** Reported from real use: tiny at 200% scaling, a box where
`Settings…` should end, and every row looking alike. The first two were one cause —
`java.awt.PopupMenu` is drawn by AWT at a size it chooses and offers no font, no renderer and no
styling — so the menu is a Swing `JPopupMenu` now, which follows DPI and takes the system menu
font. The cost is a one-pixel focused invoker window, because a `JPopupMenu` summoned from a tray
icon has no owner and would otherwise never dismiss.

**`TrayItem` is sealed — `Action`, `Status`, `Separator` — and pressable is not the same fact as
action.** A `Status` carries an optional id, so a row can be dimmed as information *and* offer
itself with a chevron. That was the actual report: "3 waiting in the Inbox" had opened the Inbox
since the day it was written and nothing said so.

**`Capture now` became `Capture copied text`**, reading the clipboard and never synthesising a
copy — pressing a tray row *is* the act of defocusing the window holding the selection, and the
stray Ctrl+C would land in whatever had focus. FR-213's reading, one client over. The hotkey path
is untouched.

**The human-owed rows are in the Windows backlog below**, and they are most of what was reported:
size, glyph, dismissal, and whether the chevron reads as pressable. No JVM test reaches any of
them.

### The recurring shape on the Windows client — five instances, four found by a person

Every one of these was *the model is correct, tested and reachable, and the screen shows
something else*: FR-507's badge, FR-506's date chips, FR-602's eight built-ins drawn four pixels
tall, FR-608's frozen ticks, and now (SRS 1.80) a Settings window that replaced an unsaved edit
with what was on disk whenever the tray item was clicked a second time — and cleared the message
line that would have explained it, in the same call. `fillWebhook` had the shape one field over
and was worse for landing asynchronously off the DPAPI thread. `fill` now owns the fields a user
edits and runs only on a fresh open or after a write; `fillWebhook` is confined to the mask and
FR-1004b's report.

**Swing is the part of this client no JVM test reaches**, which is the reachability argument this
project has already applied twice on Android. Until something covers it, a control that has not
been pressed by a person is not verified, whatever the model tests say.

**And read the store, not the code, when a preference "does not take".** FR-504's failure looked
like a parser or plumbing fault; `day_first_dates` in `%LOCALAPPDATA%\Latch\secrets.dat` was
still `true`, which separated *never stored* from *stored and ignored* in one step — and found in
passing that the FR-512 threshold was sitting at 90% from an earlier check.

**Verified 4 Sep 2026**: with Settings open, a second click on the tray item left the changed
radio changed and the confidence field as typed. The fix is watched, not just built.

### Built and verified on Windows, 4 Sep 2026 (SRS 1.79) — Android owed

**FR-804's move note.** An updated item now carries a line saying where it came from — *"Moved
by Latch from Wed 8 Sep 2027, 11:00, on 4 Sep 2026."* — composed in `:google` so both clients
word it identically, with the sentence itself coming from each client's strings (NFR-402).

**The safety is the placement.** It goes **above** the `[latch]` line, never after it: §7.2's
metadata lives inside a task's notes and a note that displaced or followed it would break FR-803
and FR-804 for that item for ever, silently. Tested by round-tripping the real encoder and
decoder rather than by inspecting text.

**The undo takes it back off**, which is what makes it safe to write at all — an undo that
reverted the move and left the note standing would leave a *false* statement in the user's
calendar. `CreatedItem.Updated` carries the prior body on both clients; Android's persisted
offer carries it as an optional field, so no record version moved.

**Watched on Windows the day it was built.** A real update wrote *"Moved by Latch from Mon 6 Mar
2028, 11:00, on 4 Sep 2026."* into the event's description with the original captured text still
above it, and a real Undo put the date back **and took the note off**. That second half is the
one SRS 1.77 called the expensive part and the one that makes the note safe to write at all: an
undo that reverted the move and left the line standing would put a *false* statement in the
user's calendar, which is worse than the silence it replaced.

**Watched on Android too, 4 Sep 2026, and the undo fixture there was better than the one
designed for it.** An update wrote its note; a *second* update was then undone inside the ten
seconds, and the event came back to its previous date carrying **exactly one** move note — the
earlier one. So the undo writes back a **stored previous body** rather than stripping notes:
had it blanked the description, the earlier note would have gone too, and that is a distinct
failure the single-update fixture could not have told apart. **A queued update writes no
note** — a recorded narrowing, not a defect.

### The finding this answers (4 Sep 2026)

**A rescheduled item says nowhere that it was rescheduled, and its title still names the date
it moved off.** After an FR-804 update the calendar held an event dated 8 Sep 2026 titled
"Kickoff 8 September **2027** at 9am". Three correct behaviours produce it — FR-804 updates
dates only, FR-509 puts the date in the title verbatim, and SRS 1.18 keeps the description as
provenance — and **no requirement asks that a user can tell an item was moved**. SRS 1.77 has
the full reading.

**Do not fix it by re-deriving the title**: that would overwrite an FR-509b correction, the one
field the user owns outright. The shape is one *appended* line, composed in `:wire` from a
per-client template, placed above the `[latch]` line on tasks — safe, because
`remoteMetadataFromTaskNotes` reads the **last** such line.

**The cost is the undo, not the write.** FR-807's undo of an update is a restore, so the note
must come back off — which means `CreatedItem.Updated` carrying prior notes, a bound §7.1
deliberately draws, and a queue record version bump on both clients. It is a slice, not a patch.

### Human pass backlog — Windows

Nothing below has been done by a person. Read each row as *the condition that would make it
fail*, then *the fixture*.

| Check | What failure looks like | Fixture |
|---|---|---|
| ~~**The hotkey actually captures**~~ | **PASS, 4 Sep 2026, many times over.** Every capture in the pass — a dozen or more across the webhook, recipe, Inbox, Settings, offline and undo blocks — went through Ctrl+Shift+K over a selection in another application, and each popup carried the text just selected. That also settles the doubt this row was written around: **the 180 ms wait after the synthesised copy was a guess and had never been watched**, and no capture showed a stale clipboard. |
| ~~**The synthesised copy is a copy**~~ | **PASS, 4 Sep 2026**, in a browser — chosen because the failure there is loud rather than subtle: Ctrl+Shift+C would have opened the developer tools instead of copying. The popup carried the selected text. |
| ~~**FR-303 from the clipboard**~~ | **PASS, 4 Sep 2026, and it is the first time this path has run at all.** A Win+Shift+S region snip, then the hotkey with nothing selected: the dates in the image were found. FR-303 had only ever been exercised from a file, which is not how anybody uses it. |
| **The tray menu is readable** (SRS 1.82) | Open the menu at your 200% scaling: the text is the size of any other Windows menu and readable at arm's length. Failure is the old symptom — roughly half size — meaning Swing is not following the display scaling after all | the tray, either button |
| **No box in any label** | `Settings…` and `Recipes…` end in an ellipsis, not `❑`. Then the row almost nobody sees: with something stuck, `N stuck - retry now` must be clean too — that label is where a glyph defect would hide for months | the tray; and a queued entry for the second half |
| **The menu dismisses** | Click away from it: it closes. Press Esc: it closes. This is the one thing the Swing menu can get wrong that the AWT one could not — a `JPopupMenu` with no focused owner draws and then stays on screen for ever | the tray |
| **Status reads as status, and the chevron reads as pressable** | Four groups with rules between them; the hotkey hint and the account line dimmed and inert; the counts dimmed but carrying `›` and opening the Inbox and the retry. Failure is the report that started this: nothing distinguishing what acts from what informs | the tray, with something queued and something in the Inbox |
| **Both buttons open the menu** | Left-click and right-click both open it, and there is exactly **one** menu — a second, tiny AWT menu appearing beside it would mean `popupMenu` got set again | the tray |
| **`Capture copied text` actually captures** | Copy some dated text, then press the row: the popup opens on that text. This path has never run. Failure is an empty capture, a stale clipboard, or — the one to watch — a Ctrl+C arriving in whatever window had focus | any dated text, copied first |
| A machine with no language pack | Says which, and where Windows adds one. Not "that image could not be read" | a machine with the pack removed |
| ~~**FR-304 keyboard operation**~~ | **PASS, 4 Sep 2026, mouse untouched.** Tab reached every control, Enter completed the capture, Esc dismissed one without saving. The fixture was deliberately an **undated** capture — the button reads *Add to Inbox*, so Enter proves the keyboard path at zero cost to the account. NFR-401's screen-reader half is still owed. |
| ~~**The popup near a screen edge**~~ | **PASS, 4 Sep 2026.** Pointer in the bottom-right corner, whole popup on screen. The clamping arithmetic was unit-tested; the real screen insets it runs against were not. |
| A second monitor, mixed DPI | The popup appears on the monitor the pointer is on, and the tray icon is sharp | two monitors at different scaling |
| ~~**Sign-in, end to end**~~ | **PASS, 3 Sep 2026** — recorded in the device table above and never struck here. Consent in a browser through the loopback receiver, PKCE S256, a refresh token stored under DPAPI, and **a fresh process signing in from it with no browser**, which is the half that separates a working sign-in from one that stops at the hour. |
| ~~**AC-07 across two clients**~~ | **PASS in both directions, and on the mechanism.** Phone→PC on 3 Sep 2026 (SRS 1.60); PC→phone on 3 Sep with the outcome (SRS 1.71) and its mechanism closed the same day once a decision line existed — `save decision=Duplicate basis=SOURCE_HASH` (SRS 1.72, 1.73). It was the oldest open criterion in this record. |
| ~~**AC-03 on Windows**~~ | **PASS, 4 Sep 2026.** `Ask about the uniform order` — verified against the parser first as one candidate, no date, `TASK_UNDATED`, which is the criterion's own condition. The button read **Add to Inbox**, the message read *"Held in the Latch Inbox on this PC. Nothing was written to Google"*, and **Google held nothing** — the half worth actually looking for, since a screen saying "held" over an account that holds an item is the worst of the three outcomes and is exactly what this client did until SRS 1.66. |
| ~~**AC-06 on this client**~~ | **PASS, 4 Sep 2026.** Fixture amended to `Project sync on 15 September 2026 at 11:00` — the canonical 8 Sep text would have put the prep step on Thu 3 Sep, *yesterday*, tangling the arithmetic with a past date. Same shape: Tue 15 → three working days back → **Thu 10 Sep**, over Sat 12 and Sun 13. Three rows, prep on Thu 10 Sep, and FR-606's line read **"Skipped 2 non-working days"** — the count corroborating the arithmetic, since 2 is exactly what lies between. |
| ~~**A chain reaches Google intact**~~ | **PASS, 4 Sep 2026**, read in Google and not on the popup. One event and two tasks; the `[latch]` line appended to a task's notes carries `chain_id` **and** `recipe` — §7.2's optional key present when a recipe *was* applied, the pair to the 27 Aug 2026 pass that checked it absent when none was. **Both tasks were opened and observed to carry the same `chain_id`**, so the identity is watched across items rather than reasoned about. **The event's own `chain_id` remains inferred**: it lives in `extendedProperties.private`, which Google's web UI does not display, and it comes from the same `chainId` variable in the same save. |
| ~~**A reminder actually fires**~~ | **PASS, 4 Sep 2026 — the first time this field has ever reached Google from this client.** The event carries a **30-minute** notification rather than the calendar's default. `RecipeStep.reminderOffsets` existed unread for weeks on both clients, and no chain had ever been written from this machine. |
| ~~**FR-608**~~ | **Failed, fixed, and passed on the retry — 4 Sep 2026.** A step **could not be unticked**: `redraw()` rebuilt each row from a model whose `checked` flags were frozen when the recipe was applied, so the tick reverted — and `canSave`, read from the same frozen model, would have left Save enabled with everything unticked. SRS 1.76. After the fix the untick sticks. **The write half — two items and nothing on 10 Sep — is inferred**: `recipeItems(selected = …)` is unit-tested and the UI now produces the right set. |
| ~~**"Just this one" puts the capture back**~~ | **PASS, 4 Sep 2026.** Pressing it returns the ordinary candidate rows. That is what keeps applying a recipe from being a one-way gesture on a popup a stray click closes. |
| ~~**A picked date makes a capture expandable**~~ | **PASS, 4 Sep 2026.** `call at 4pm` opened with a time and no day, the chooser correctly offering nothing; *Tomorrow* completed the row to 5 Sep 2026 and ticked it; and the chooser then **offered recipes without the capture being closed and reopened**. That last clause is the check: the blocker is re-asked on every edit, and a blocker computed once when the sheet opened would have been the same shape as FR-608's frozen ticks one control over — invisible to every JVM test, because the model is correct either way. |
| ~~**The chooser is absent for good reason**~~ | **PASS, 4 Sep 2026.** A four-date capture showed the **reason** — "A recipe expands one date, and this capture holds several" — and no chooser. The fixture was verified against the parser first rather than assumed: `CorpusTest` asserts only `result.primary` and never the candidate *count* (SRS 1.62), so a text that quietly merged to three would have tested nothing. It parsed as exactly four. Failure here would have been silence, which is what the three defects this pass found all looked like. |
| ~~**FR-605 from Settings**~~ | **PASS, 4 Sep 2026 — Settings and recipes are wired to one another, which nothing had shown before.** Saturday ticked, the AC-06 capture re-run: prep moved **Thu 10 Sep → Fri 11 Sep** and FR-606's line read **"Skipped 1 non-working day."** The count corroborates the date independently rather than restating it — once Saturday works, Sun 13 is the only non-working day between Fri 11 and Tue 15, so a date that moved without the count moving would have been a half-applied preference. **The singular branch of that string had never been rendered**: every skip until now has been a weekend pair. Expectation checked against `WorkingDayCalculatorTest`'s six-day case before the run, not graded afterwards. |
| ~~**FR-603, all four verbs**~~ | **PASS, 4 Sep 2026.** Create, edit, duplicate and delete, each surviving a close-and-reopen of the window *and* a quit-and-restart of Latch — two different storage claims, and only the second exercises the DPAPI file. Editing a built-in shadowed it rather than adding a ninth row, its label read "edited by you", the button read **Restore the shipped version** rather than Delete, and pressing it brought the shipped one back with the other seven untouched. That shadowing rule is `:recipes`' and both clients compile it, so this is evidence for the phone too. |
| ~~**The editor is usable**~~ | **PASS, 4 Sep 2026**, on a multi-step recipe: type, offset, unit, direction, title template and reminders all reachable from the keyboard. It could only be checked after SRS 1.75 — until that fix every row of the list was four pixels tall and there was nothing to reach. |
| ~~**A queued chain keeps its reminders**~~ | **PASS, 4 Sep 2026.** The event drained out of the offline queue carrying its **30-minute** notification. The Latch calendar is a secondary calendar Google created with no default notification of its own, which is what makes the reading unambiguous — a 30-minute reminder there can only have come from `reminderOffsets` surviving the queue record to disk and back. This is the one field the queue could silently have dropped, and it reached Google through the drain rather than through a direct save. |
| ~~**FR-803 over a chain**~~ | **PASS, 4 Sep 2026.** The same text with the same recipe re-captured online answered *"Already saved. Nothing was written again."* and the account stayed at three items. One check covers all three because the chain shares one `source_hash`. **The count is the evidence, not the sentence** — SRS 1.38 records that sentence arriving from the wrong query, and on this client it cannot, `DesktopSaver` having no FR-804 path. |
| ~~**AC-18 — exactly one webhook request**~~ | **PASS, 4 Sep 2026 — and the first webhook delivery ever received from either client.** Against a local HTTPS bin: **exactly one** request, FR-1004b's single attempt. FR-1004a's list verified **both ways** — every key present is named by the requirement, and `due_date`, `location`, `recipe` and `source_app` are absent for the right reasons rather than by luck. Delivery **3.5 s after** `captured_at`, in that order, so FR-1004b's "strictly secondary to the Google write" is observed. One delivery for the save, not one per item. **Still open: the "and no other non-Google traffic" half**, which needs a monitor and rests meanwhile on `ALLOWED_HOSTS` and the Android AC-17 pass of 31 Aug. |
| ~~**The accepted path at all**~~ | **PASS, 4 Sep 2026.** This row said a 200 from a real endpoint was the whole of what was owed. `keytool` ships with the JDK this project already builds on: a throwaway cert for `localhost`, a truststore that is a copy of the JDK's own `cacerts` **plus** that cert so Google still validates, and a loopback `HttpsServer`. **Limit recorded:** accepted under a *test root* — the application's code is untouched and only the trusted roots changed, so what is proven is the payload, the count and the ordering, not the public trust path. |
| ~~**AC-20 — an unreachable endpoint costs nothing**~~ | **PASS, 4 Sep 2026.** Pointed at `https://127.0.0.1:9/hook`: the save reached Google, no error appeared, the FR-807 offer stood, and **Undo removed the item**. FR-1004b's "shall never block FR-807 undo" and NFR-303's "a failed webhook is not a failed API write", both observed. |
| ~~**AC-21 — nothing is sent for a queued capture**~~ | **PASS, 4 Sep 2026, and it is a *meaningful* negative.** The queued capture reached Google on reconnection and the endpoint received **nothing** — the local bin's total stayed at exactly **1**, the AC-18 delivery, across the whole offline block. The negative counts because the webhook was **configured and enabled throughout**: the endpoint was still stored afterwards, and the same configuration had produced a delivery in step 3 and a reported failure in step 4. One attempt happens at save time; offline, it fails and is never retried, which FR-1004b requires and the configuration screen states. |
| ~~**FR-1004b's passive report**~~ | **PASS on both branches, 4 Sep 2026**: *accepted*, then *could not reach your endpoint*. Seeing it **change** is worth more than either alone — it shows the line is derived from the outcome rather than a plausible-looking constant. |
| ~~**NFR-203's mask**~~ | **PASS, 4 Sep 2026**, on the screen *and* at rest: masked in Settings, and neither the host nor the path appears in `secrets.dat`. The mask alone does not prove the second — a client could mask perfectly and still write the URL to disk in the clear. |
| ~~**Two acts, not one**~~ | **PASS, 4 Sep 2026.** The endpoint counted **zero** requests through the whole of configuration, so saving one started nothing; and ticking the box with nothing stored was refused with a reason rather than accepted and left inert. |
| ~~**Settings opens and takes**~~ | **PASS, 4 Sep 2026, and it is the strongest single check in this pass.** Threshold at **99** sent an ordinary dated capture to the **Inbox**; at **60** the same capture went to Google. A preference stored and then read by the capture path — which is precisely what no FR-1001 field on this client did until SRS 1.67, when it built a bare `ParseContext(now = LocalDateTime.now())` and asked for none of them. |
| ~~**FR-504 through Settings**~~ | **PASS, 4 Sep 2026, at the second attempt — and the first attempt is why SRS 1.80 exists.** Month-first set, `Invoice 05/09` read as **9 May 2026**, badge **TASK** with FR-510's past-date line. The discriminator is unusually clean and was chosen for it: day-first gives 5 Sep 2026, tomorrow and an ordinary EVENT, so the badge alone says which reading arrived. The first attempt read 5 September, and the stored record — not the code — showed `day_first_dates` still `true`, separating *never stored* from *stored and ignored* in one step. |
| ~~**Changing the FR-302 hotkey**~~ | **PASS, 4 Sep 2026, including the refusal — which was a manufactured condition rather than a hopeful one.** Ctrl+Shift+L took, captured, and left Ctrl+Shift+K dead. Then Ctrl+Shift+J, held system-wide for the run by a process registered against that combination on purpose (`RegisterHotKey` returning true before the check began, so 1409 was certain rather than likely): Latch said *"Another application already uses Ctrl+Shift+J. The old shortcut is back; choose a different one."* **and Ctrl+Shift+L still captured.** That second half is the row's whole point — the failure it guards against is being left with no working shortcut at all, which announces itself nowhere. The usual fixture for this ("Ctrl+Shift+S, which many applications hold") would not have tested anything: an in-app accelerator is not a system-wide registration, so `RegisterHotKey` would have **succeeded**. |
| ~~**A refused shortcut does not get stored**~~ | **PASS, 4 Sep 2026.** Settings reopened showing Ctrl+Shift+L, not the refused Ctrl+Shift+J. The rollback writes the previous record back and re-registers it, so the store and the live registration agree rather than merely the screen. |
| ~~**Settings survive a restart**~~ | **PASS, 4 Sep 2026**, on the hotkey rather than on the working week — deliberately the stronger fixture. A working week only has to be *displayed* correctly to pass this row; a hotkey has to be re-read from disk **and acted on** by `registerHotkey` before anything responds. Latch quit, restarted, and Ctrl+Shift+L captured. |
| ~~**The Inbox opens and triages**~~ | **PASS, 4 Sep 2026 — all five of FR-702's actions.** Tray read *1 waiting*, the entry opened the window, the row stated its reason and offered the chips without a press. **Assign a date**, **save**, **edit** (title change survived a close-and-reopen), **snooze** (left both the list and the count) and **discard** (took the tray entry away entirely). |
| ~~**FR-702's assigned date reaches Google**~~ | **PASS, 4 Sep 2026.** An undated row given a date with the chips saved as a **TASK due that date** — not an event, not undated. Inspected in Google rather than on the row. |
| ~~**FR-703**~~ | **PASS, 4 Sep 2026.** Nothing reached Google Calendar or Tasks while the capture sat in the Inbox; the item appeared only after Save. Verified in the account. |
| ~~**FR-704 does not nag**~~ | **PASS, 4 Sep 2026, on both halves.** The count appeared as *1 waiting in the Inbox* and nothing louder — no badge, no notification. Snoozing the only row took the tray entry away **entirely** rather than leaving *"0 waiting"*, which settles both clauses at once: a snoozed row is not counted, and zero due rows shows nothing at all. |
| **FR-515 across days** | Capture "kal 4 baje meeting", leave it a day, reopen the Inbox: the date must still read the day after the **capture**. A date that walked forward is a serious defect. JVM-verified with a fixed clock; the device check is that the *stored* instant is the one being replayed | "kal 4 baje meeting", two days |
| **FR-705's review line** | A row older than a fortnight says "Still want it?" and is **still there**. Needs a clock change or a seeded row — awkward rather than skipped | an aged row |
| ~~**A held row survives a restart**~~ | **PASS, 4 Sep 2026**, on both halves and in that order. The record was first **observed on disk** after a restart — `inbox.dat`, one row, encrypted, captured text not in the clear — which is what the store's own tests cannot show, because they write and read inside one process. Then a fresh un-snoozed row was **seen in the window** after a quit-and-restart, with its reason intact. The first attempt was hidden by a snooze, which was a sequencing mistake in the pass rather than a defect. |
| ~~**The batched DPAPI bridge, in the wild**~~ | **PASS, 4 Sep 2026, under exactly the load this row names and with no write to the account.** Five held Inbox rows and two queued entries: the tray menu opened immediately, no pause. Every store on this client crosses the bridge once per file now; before the batching each *record* crossed separately, which at seven records is the difference this change was made for. **The refresh token half was checked too** — Latch restarted, the hotkey gave a capture with the destination chip filled and no sign-in prompt, so the token came back across the same bridge. **Zero writes, by construction**: Latch was quit while still offline, `queue.dat` (2 rows) was deleted with the process confirmed stopped, and only then was the network restored — so this closes the bridge-under-load half and says nothing about the drain, which is struck separately. **FR-704's count corroborated in passing**: the tray read *4 waiting* against five rows on disk, the fifth being the row snoozed on 4 Sep and due back on the 11th. The count is derived from what is due, not from how many rows exist — which is the clause that makes a snoozed row silent. |
| ~~The Latch calendar is reused~~ | **PASS, 3 Sep 2026** — in the device table above and never struck here. Three writable calendars, exactly one named Latch, and the desktop adopted it by id rather than creating a second. FR-803's event query is scoped to a calendar id, so a second Latch calendar would have made each client blind to the other's items while both appeared to work. |
| Launch at sign-in (FR-301) | Not built. See `docs/RELEASE-WINDOWS.md` | |
| ~~**The queue survives a restart**~~ | **Done, 3 Sep 2026.** One JVM with every HTTPS connection failing held the capture on disk, encrypted; a **second, fresh** JVM read it and drained it to Google; re-capturing the same text then answered "already saved", which is Google's index confirming it landed. | |
| ~~**AC-10 with the network actually off**~~ | **PASS, 4 Sep 2026 — the trigger, not just the mechanism.** Wi-Fi genuinely off: the window said **held**, never "saved", which is the distinction the Queued state exists for. Latch was then **quit entirely and restarted** with the entry still showing in the tray, and on reconnecting it drained **within seconds** rather than at the next scheduled attempt. The 3 Sep run used a bogus proxy, so every request failed as it would offline while the interface stayed up — the connectivity-restored path was unexercised until now. Afterwards `queue.dat` is **absent**: the queue drained empty and removed its own file. |
| ~~**Pressing Update on a real offer**~~ | **PASS, 4 Sep 2026 — the first time on this client, and the count is the evidence.** Arose unprompted during the Settings check: the same text re-captured with the **year changed from 2027 to 2026** was offered as a reschedule, **Update** was pressed, and afterwards the calendar held **no event at the old date and exactly one at the new** — so one item moved and none was created, which is this row's own wording and is what the message on screen cannot say. **FR-804 matched across a year boundary**, meaning the blanked date span covers the year: the property SRS 1.59's `NumericDateRule` defect would have broken for an ISO-format date, seen holding here for a spelled-out one. |
| ~~**FR-804's move note** (SRS 1.79)~~ | **PASS on Windows, 4 Sep 2026, the day it was built.** An update wrote *"Moved by Latch from Mon 6 Mar 2028, 11:00, on 4 Sep 2026."* into the event's description, with FR-805's original captured text still above it — so the note appended rather than displaced. A second run undone inside the ten seconds put the date back **and removed the note**, which is the half SRS 1.77 named as the real cost and the half without which the note would be worse than the silence it replaced. **Also watched on Android, 4 Sep 2026** — see the device table — where undoing a second update left exactly the earlier note standing, proving the undo restores a stored body rather than stripping notes. |
| ~~**Pressing Undo**~~ | **PASS, 4 Sep 2026, on all three halves.** Undo of a **create** removed the item (taken during AC-20). A lapse was **watched**: the countdown ran out untouched, the window closed itself, and the save stood. And undo of an **update** put the old date back and **deleted nothing** — the single most consequential untested path on this client, because `priorDates` captured at match time is the only place those values still exist after the patch, and a delete there would be data loss on an item the user owned before Latch touched it. |
| ~~**A half-written chain resumes**~~ | **Met by proxy, 4 Sep 2026, and recorded as a proxy rather than as the row.** A three-item recipe chain queued offline drained on reconnection with **each item written exactly once** — the device check this row itself names. What is *not* established is the row's own condition: a chain whose first insert succeeds and whose second fails cannot be arranged by hand, so SRS 1.24's resume remains pinned by JVM tests alone. |
| ~~**FR-806's Retry now**~~ | **PASS, 4 Sep 2026.** Offered in the tray beside the pending count while the network was off; tapped offline it left the entry queued and lost nothing; tapped after reconnecting it drained within seconds rather than at the next backoff. Both halves matter — a button that only works when it was going to work anyway is not a manual retry. |
| **NFR-103** | The runtime half is unmeasured, and estimating it is what `docs/RELEASE.md` forbids for the Android bundle for the same reason | a full JDK with `jmods` |

## Business-card capture — Phase A (FR-1200 series, SRS 5.11)

**Approved 4 Sep 2026 (SRS 1.84). Nothing built yet.** The slices, in order, with what each is
for. FR-1240 and FR-1241 gate **contact-write code**, not the spike, so Slice 0 may begin.

| Slice | Holds | Device pass |
|---|---|---|
| ~~**0 — spike**~~ | **DONE, 4 Sep 2026.** ZXing: **+16,384 B** APK, **+49,116 B** dex, **zero native**, against ML Kit barcode's ~+5.7 MB per device. FR-1241 read verbatim: *"See, edit, download and permanently delete contacts"*. **FR-1240(a) answered**: `clientData` stored **intact** at up to **500 entries** and **32 000-character values**; 131 072 refused with `Resource has been exhausted`; **no silent truncation at any size**, which was the dangerous outcome and did not occur. FR-1207 needs ~6 entries of ~70 characters — three orders of magnitude of headroom | Done. Account verified clean by a sweep returning zero |
| ~~**1 — grammar**~~ | **BUILT 4 Sep 2026 (SRS 1.93), 20 tests.** `:cards`, pure Kotlin: vCard 2.1/3.0/4.0 and MECARD → `CardDraft` in `:core-model`. Covers what real cards get wrong — line folding, `item1.` grouping, quoted-printable, bare 2.1 type parameters, MECARD's reversed `Family,Given`. Unknown properties dropped, never guessed; a payload with no usable field reported unreadable rather than opened as an empty sheet | — |
| ~~**2 — identity**~~ | **BUILT 4 Sep 2026 (SRS 1.94).** `CardMetadata` and FR-1209's identity in `:wire`, with `metadata/card_vectors.tsv` generated outside the Kotlin that must match it. Identity keys on **normalised email only** — a phone number contributes nothing at all — and the switchboard case is a test: two colleagues, one shared number, different addresses, two identities. An empty identity list refuses to match another empty one, so emailless cards cannot fold into one contact. The key is a digest, never the address. An unknown record version decodes to null so the contact is left alone | — |
| ~~**3 — the client**~~ | **BUILT 4 Sep 2026 (SRS 1.95).** `ContactsApi` + `ContactsRest` in `:google`, `people.googleapis.com` on `ALLOWED_HOSTS` with the guard test naming the four. FR-1208's scan is built and `createContact` is called from nowhere. **The open unknown is answered: `searchContacts` cannot query `clientData` and is eventually consistent** — a contact five seconds old returned zero while long-standing ones were found — so the check pages `connections` and compares client-side, capped, with `scanCapped` reported | — |
| ~~**4 — decode and sheet**~~ | **BUILT 4 Sep 2026 (SRS 1.96, 1.97).** ZXing behind `QrReader` at a measured **+17,176 B**, no native code, `DEPENDENCIES.md` written — closes FR-1240(b). Decode runs **off** NFR-101's critical path. `CardScreen` is the editable preview with FR-1213's account line; FR-1202's `DETECTED` and `AVAILABLE` are a filled button and a text button so they are tellable apart; `CardChooser` is FR-1203 refusing to guess. **Save is deliberately inert until 5a.** | **See the five rows below** |
| **FR-1202 NONE — a text capture** | Capture ordinary dated text. **No card action anywhere on the sheet.** There is no image, so there is nothing that could be a card; an action here would be an offer the app cannot honour | "Kickoff 8 September 2027 at 9am" |
| **FR-1202 AVAILABLE — an image with no code** | Share any dated screenshot. The sheet fills with dates as usual and carries **"Save as contact instead"** as a quiet text button. Failure is the action being absent — the user may know an image is a card when the decoder does not, and without this Phase B is unreachable from this screen | `testdata/fr215/three-dates-v4.png` |
| **FR-1202 DETECTED — a card QR** | Share an image of a vCard QR. The action becomes a **filled button, first in the row**, reading "This looks like a contact card". **The two states must be tellable apart at a glance**; if they look alike, detection changed nothing and the requirement is unmet in the only place it is visible | a printed or on-screen vCard QR |
| **FR-1203 — two codes, and the refusal to guess** | Share one image carrying **two** QR codes (two cards, or a card beside a Wi-Fi code). Expect the chooser: *"Latch will not guess which one you meant"*, options numbered and **not previewed**. Failure is either sheet opening by itself — the app choosing — or nothing happening at all | an image with two codes |
| **FR-1205 / FR-1213 — the preview** | Open a detected card. Every field is a text box already, with no edit affordance to press first; the account line names where it would go; and the sheet says **Latch only adds and updates contacts, never deletes one**. Blank a phone: the row goes rather than becoming empty. Blank every field: Save disables and says why. **Save writes nothing in this slice** — pressing it must do nothing at all, and an item appearing in Contacts would mean 5a landed early | a detected card |
| ~~**5a — the write**~~ | **BUILT 4 Sep 2026 (SRS 1.98), installed, NOT WATCHED.** `CardSaver` runs FR-1208 then FR-1206's insert, always in that order. A **capped scan writes and says so**; a search that **threw** writes nothing. FR-1207's record is composed once. The identity comes from the draft as confirmed, the hash from the payload. | **The rows below, plus Slice 4's five** |
| **FR-1208 — the same card twice writes once** | Share the card QR, Save; the sheet says *Saved to your contacts*. Share the identical image again and Save: **"Already saved. Nothing was written again."** **The evidence is the count in Google Contacts, not the sentence** — this project has watched that sentence come from the wrong query once already (SRS 1.38). Search Contacts for the name: exactly **one** | one vCard QR, twice |
| **FR-1207 — the record is on the contact** | After the first save, read the contact back and confirm its `clientData` carries `latch.card.version`, `latch.card.source_hash`, `latch.card.identity` and `latch.card.captured_at`. Failure is a contact created with no record at all, which would make it invisible to every future check | as above |
| **FR-1209 — a cleared address writes no identity** | On the sheet, blank the email before saving. The contact is created and its `clientData` carries **no** `latch.card.identity`. That is the requirement's safe direction made visible: no email means no automatic identity and always a new contact | a card with one email |
| **FR-1205 — the correction is what is written** | Change the job title on the sheet, then save. Google holds the **corrected** title, not the parsed one. Failure would mean the user confirmed one thing and the account received another — the defect SRS 1.44 found on the date side, where the screen drew one title and the write used another | any card |
| **A blank field is absent, not empty** | Clear the company on the sheet and save: the contact has **no** organisation, not a blank one. An empty `organizations` entry puts a visible empty row on somebody's contact | any card with a company |
| **A failed search writes nothing** | Aeroplane mode, then Save. Expect *"Could not save that. Nothing was written"* and **nothing in Contacts**. Failure is a contact appearing — that would be FR-1208 skipped whenever the network is poor, which is exactly how the calendar's duplicates happened | any card, offline |
| **5b — reversal** | FR-1210's undo, FR-1212's queue with the check re-run at drain | **Its own pass** |

**Why 5a and 5b are separate, and it is method rather than tidiness.** On the date side those two
mechanisms hid *different* defect classes: FR-803's query returned an empty page carrying a
`nextPageToken` and wrote duplicates for five days, and the drain ran a check no JVM test could
reach. One pass covering both would have made a finding hard to attribute to either.

**Phase A ships the shared image only.** The camera is FR-1201a at Phase B (SRS 1.85), and the
`ACTION_IMAGE_CAPTURE`-versus-CameraX decision is owed with it — the first needs no permission and
no dependency, the second needs both.

**v1.90 recorded a POST defect here. It does not exist (SRS 1.91).** File descriptors were
counted around every request and stayed flat, the interrupt flag was clean, and `GoogleHttp`'s
stream handling is correct on inspection. The cause was the probe's own harness: a
`BroadcastReceiver` returns immediately, the process drops to a cached state and loses network
mid-run, and Android reports that as `UnknownHostException` — naming DNS for something that is not
DNS. With `MainActivity` in the foreground the identical sequence gives three POSTs, three DELETEs
and two Calendar GETs, all successful.

**Any probe that talks to Google must therefore run with the app in the foreground**, or it will
measure its own backgrounding. That is the operational lesson and it applies to every future
device instrument in this project.

**What survives is real and is not a bug.** Two contacts were created by POSTs that reported
`SocketException` — the write reached Google and reading the response did not, so a successful
write was reported as a failure. It is inherent to HTTP without idempotency keys, it can happen to
a Calendar or Tasks write on any dropped network, and it is why FR-806's drain re-runs FR-803
before every insert. **Unestablished**: whether it has ever fired silently on the date pillar. The
FR-803 index could not be read off the device (`run-as cat` gives a file of the right length whose
`page_count` is zero); the instrument that would answer it is the account — duplicate Latch items
sharing one `latch.source_hash`.

**The Slice 3 unknown is answered, and the answer is a limit (SRS 1.95).** `searchContacts` cannot query `clientData`, and it is **eventually consistent**: a contact created five seconds earlier returned zero results by email, by name and by client data, while the same query found two of three long-standing contacts. So FR-1208 pages `people/me/connections` and compares client-side — 3,157 contacts in the account it was built against, hence a page size of 1,000 and a ten-page cap — and reports `scanCapped` rather than answering "none". **Cross-device duplicate detection for contacts is therefore weaker than for calendar**, where `privateExtendedProperty` answers exactly and immediately: two devices capturing one card inside the index's catch-up window will each write a contact. A known limit, not a defect to engineer around.

## Device pass backlog

**Everything in this section is JVM-verified and DEVICE-OWED.** It was built in the autonomous
session of 1 Sep 2026, in which no device pass was run and nothing was written to the
developer's Google account. A green `./gradlew build` is not evidence for any of it — the
launch canary exists because the app once could not start at all with every unit test passing,
and three load-bearing paths in this project have turned out never to have run while every test
was green.

Read each row as: *the condition that would make this check fail*, then *the fixture that
creates that condition*. That order is the standing convention in this file, applied to work
that has not been watched yet.

### Slice 1 — document page progress (FR-207, NFR-102)

| Check | What failure looks like | Fixture |
|---|---|---|
| "Reading page N of M…" counts up while a PDF is read | **The callback half is verified** (1 of 2, then 2 of 2, on `letter.pdf`). What is still owed is the **line on screen**: it never appears, or it appears once and sticks at 1, because the flow publishes from a thread the screen does not collect on | `testdata/fr215/long.pdf` (14 pages). It must show **M = 10**, not 14, and must count 1→10 |
| The cap line still follows | "First 10 of 14 pages read." after the sheet fills. The progress line and the cap line are different sentences about different numbers, and showing 10 in both places is correct | the same file |
| A short PDF still says the same thing | `letter.pdf` (2 pages) counts 1→2 and shows **no** cap line | `testdata/fr215/letter.pdf` |
| An **image** capture is unchanged | The spinner says "Reading the text…", never "Reading page…". An image has nothing to count, and a progress line on one would be a number invented from nothing | any `testdata/fr215/*.png` |
| NFR-101 for text is unmoved | An ordinary text capture is still synchronous — `content ready, ocr=false` in `LatchTiming`, no spinner at any point. NFR-102's note requires this re-check whenever the asynchronous path is touched | "Kickoff 8 September 2027 at 9am" |

### Slice 2 — FR-701 storage, the FR-700 Capture Inbox, FR-806's queue improvements

**Nothing in `:data`'s SQLite is reachable from a JVM test.** `SQLiteOpenHelper` is a throwing
stub under unit tests — the same property that hid the `decodeBitmap` defect in `:ocr` for a
whole slice — so the record formats are tested and the SQL is not. That is the single biggest
gap in this slice and the first thing to watch.

| Check | What failure looks like | Fixture |
|---|---|---|
| **An unreadable Inbox row is kept** | JVM-unreachable and now covered by an instrumented test, which has **not been run on this device** — the suite destroys the app's local data and was not run against a phone in real use. Run it on a throwaway install, or accept losing the Inbox, queue, recipes and settings. Failure is the old behaviour: the row silently gone and the count one lower | `./gradlew :app:connectedDebugAndroidTest` |
| **FR-704 still reaches zero over a kept row** | With one unreadable row and nothing else, the home screen must show **no** Inbox count at all, and the Inbox screen must say one held capture could not be read. Failure is a count that never goes to zero, which is the objection the old deletion existed to answer | as above |
| **FR-1004b's report on the phone** | Configure a `https://` bin that answers 404, enable the webhook, save a capture: Settings must say *"your endpoint answered 404"*. Point it at `https://127.0.0.1:9/hook`: *"could not reach your endpoint"*. A working bin: *"accepted"*. Before this, all three showed nothing | a request-bin endpoint |
| **The report does not follow a removed endpoint** | Remove the endpoint: the last-delivery line goes with it. A line describing a delivery to an address the user has just removed is worse than none | Settings |
| **AC-19 is unmoved** | A notification capture must still produce **no** request and now also **no** report — a suppressed delivery is not a failed one. Needs FR-208, so it is owed until slice 7 | notification listener |
| **AC-03** — text with no date | An undated row appears in the Inbox and **nothing** is created in Google Calendar or Tasks. Failure: an item appears in the account, or the sheet reports "Saved" | "Ask about the uniform order" |
| ~~The database is actually created~~ | **Verified 2 Sep by the instrumented suite.** The database is created on first use and every store round-trips through SQL and the Keystore. |
| A row survives a **process death** | Still owed: the instrumented tests write and read within one process, so they prove the SQL and the cipher and not the restart. Route a capture to the Inbox, force-stop, reopen: the row is still there with its reason | `adb shell am force-stop com.latch.android` |
| The Inbox re-parses at the **captured** instant | Capture "kal 4 baje meeting" (routes on confidence), leave it a day, reopen the Inbox: the date must still read the day after the **capture**, not the day after today. Failure is a date that walks forward every time the list is opened | "kal 4 baje meeting", checked on two different days |
| FR-702's five actions | Assign a date, edit the title, snooze, discard, save. Each must persist across a back-and-return, and Save must produce exactly one item in Google | any Inbox row |
| FR-702's assigned date reaches Google | A row with no date, given 20 Sep 2027, saves as a **TASK due 20 Sep 2027** — not an event, not undated | undated capture + date picker |
| FR-703 | Nothing in the Inbox appears in Google Calendar or Tasks until Save is pressed. Verified by looking at the account, not at the screen | any row left un-saved |
| FR-704 does not nag | The count is absent at zero, and there is no notification and no badge anywhere | empty Inbox |
| FR-705 | A row older than a fortnight shows its review line. Needs a clock change or a seeded row — recorded as awkward rather than skipped | `adb shell date`, or an Inbox row aged by hand |
| **FR-807 across a process death** | Save, force-stop inside the ten seconds, reopen: the home screen offers Undo with the remaining seconds, and taking it removes the item from Google. This is the limit FR-807's note recorded as unfixable without FR-701 | "Kickoff 8 September 2027 at 9am" |
| **FR-807 across a rotation** | Save, rotate inside the ten seconds: the offer is **still there**. Before this slice the recreation reset the saver and the offer vanished — a latent defect no test could see | any capture, rotate |
| FR-803's index cures the capped scan | Needs a task list with more than ~1000 undated tasks, which is why it is unverified. Cheaper proxy: capture, undo, capture again — it must save the second time, proving the index forgot | capture → undo → capture |
| **FR-804's offline hold** | Save an event online; edit its date in the message; aeroplane mode; capture the edited text. Expect the **Inbox**, reason "looks like a change", and **no queue entry** — the failure being a second item written on reconnection, which is the defect FR-804's note describes | "Kickoff 8 September 2027 at 9am" then "Kickoff 9 September 2027 at 9am" |
| **SRS 1.24's resume** | Needs a chain whose first insert succeeds and whose second fails. Hard to arrange by hand; the JVM test pins the logic, and the device check is that an ordinary four-item chain still writes four items and retires once | a four-date capture, offline then online |
| FR-806's backoff ceiling | After eight failed attempts the next drain is scheduled ~30 minutes out rather than hours. **Watch for the `REPLACE`-from-inside-a-worker behaviour**: the worker enqueues its own unique work while running, which cancels the current run. Intended, and the one thing here to watch rather than reason about | aeroplane mode for a long stretch, `adb shell dumpsys jobscheduler` |
| FR-806's immediate drain | Queue a capture offline, wait past one backoff, turn the network on: the drain runs within seconds rather than at the next scheduled attempt | aeroplane mode on → capture → off |
| FR-806's Retry now | With something queued, the button appears and a tap drains. With a given-up entry, the tap revives it and it is attempted again | a 403 or an entry given up on |
| **NFR-101 for text is unmoved** | An ordinary text capture still reaches a filled sheet under 800 ms. The Inbox route adds a decision to the save path and this is the standing re-check | "Kickoff 8 September 2027 at 9am" |
| **AC-17 still holds** | A network monitor over a cycle including an Inbox save shows only Google hosts. The Inbox adds no network path, but it adds a save path | any capture cycle |

### Slice 3 — FR-506 row 3, FR-507, FR-510

Everything these three requirements *decide* is a pure function and is tested. What a device
pass has to establish is that the controls are reachable, legible and not in each other's way on
a narrow floating dialog — which is exactly the class of defect the FR-804 offer's clipped
"Up…" label was, and which no JVM test can see.

| Check | What failure looks like | Fixture |
|---|---|---|
| **FR-506 row 3's chips are on screen without a tap** | The row shows "No day for this time yet" and nothing else, or the chips are below the fold of the sheet | "call at 4pm" |
| A chip completes the row | Tapping *Tomorrow* fills the date line, **ticks the checkbox**, and Save becomes enabled. Failure: the row stays unticked, which makes the picker feel inert | "call at 4pm" |
| `Other date…` opens the calendar over the sheet | The dialog appears and is dismissible; confirming is **disabled until a day is picked**, because a confirm that meant "today" would be the app choosing a date | "call at 4pm" |
| The calendar's date is the one that lands | Pick 20 Sep; the item must be 20 Sep, not the 19th or 21st. The M3 picker's millis are UTC midnight and converting them through the device zone is the off-by-one this is here to catch | "call at 4pm", IST device |
| **FR-507: the badge is tappable and flips** | EVENT → TASK → EVENT on a single row, and the date line and the note update with it | "Kickoff 8 September 2027 at 9am" |
| The cost is shown **before** the tap | "…keeps the day but not the time" is visible while the badge still reads EVENT | as above |
| An override reaches Google | Overridden to TASK, the save produces a **task due 8 Sep 2027** and no event | as above |
| A range overridden to a task | The note names the lost end date, and the saved task is due on the **first** day | "Trip from 20 September to 24 September 2027" |
| Per-row override in a chain | Two rows, one flipped: the save writes one event and one task | a two-date capture |
| The override is on the Inbox too | The badge in the Inbox list flips and the saved item follows it | any Inbox row |
| **AC-04** — a past date | "the order dated 12 March" → **no dated item**; one undated to-do whose notes begin "Originally dated 12 March 2026." Verified in Google Tasks, not on screen | "the order dated 12 March" |
| A past row's badge is fixed | It reads TASK and does not flip, with "A past date cannot be an event." beside it | as above |
| FR-510 per candidate | "Invoice dated 12 March, payment due 20 September 2027" → **two** items: an undated follow-up and a task due 20 Sep | as quoted |
| A queued follow-up keeps its note | Aeroplane mode, capture a past date, reconnect: the note survives the queue (record v5) and is in the item Google receives | as above, offline |
| **The action row still fits** | With chips, a badge, a cost note and Save on one sheet, nothing clips and nothing wraps mid-word. This is the defect class the FR-804 offer already produced once | a past-dated multi-row capture |

### Slice 4 — recipes, FR-601 to FR-608

The arithmetic AC-06 pins down is tested; what is not is that a chain of three reaches Google
intact, that a reminder actually fires, and that the editor is usable on a phone.

| Check | What failure looks like | Fixture |
|---|---|---|
| **AC-06** | "Project sync on 8 September 2026 at 11:00" + *Meeting + prep* → the prep task lands on **Thu 3 Sep**, and the row says the weekend was skipped. 8 Sep 2026 is a Tuesday, which is what makes the weekend get crossed | as quoted |
| The chain reaches Google intact | Three items — one event, two tasks — all in the Latch calendar and the chosen list, sharing one `latch.chain_id`, and `latch.recipe` reading `builtin.meeting_prep`. **Inspect the written items**, not the screen | as above |
| **A reminder actually fires** | The event in Google Calendar carries a 30-minute popup reminder rather than the calendar's default. This has never run: `reminderOffsets` reached nothing until this slice | as above |
| Reminders are omitted where there are none | An ordinary capture with no recipe still uses the calendar's own defaults — not "no reminders", which is what an empty override list would have meant | "Kickoff 8 September 2027 at 9am" |
| **FR-608** | Untick the prep step: **two** items are written, and nothing lands on 3 Sep | as above |
| FR-607 | Every item of the chain is in the same calendar. Under Option A with a non-default destination too | as above |
| The chooser is absent for a multi-date capture | A four-date capture shows the reason, not a chooser | any multi-date capture |
| A recipe on an all-day capture | "Project sync on 8 September 2026" expands to an **all-day** event, not a meeting at 00:00 | as quoted |
| FR-605's working week | Set a six-day week; the same AC-06 capture puts prep on **Fri 4 Sep** instead of Thursday. Needs the Settings screen (slice 5) or a seeded record | as AC-06 |
| FR-605's holidays | Add a holiday on the prep day; the shift steps over it and the row names it | any working-day step |
| **FR-603, all four verbs** | Create, edit, duplicate, delete — each surviving a return to Home and a cold start. **Editing a built-in must not change the others**, and deleting the edited copy must restore the shipped one | the Recipes screen |
| The editor is usable on a phone | Six filter chips, a number field and a template field per step. This is the same narrow-dialog class as the FR-804 offer's clipped label, one screen over | a recipe with three steps |
| **A queued chain keeps its reminders** | Aeroplane mode, apply a recipe, save, reconnect: the drained event still carries the reminder (queue record v6) | AC-06's capture, offline |
| FR-803 over a chain | Re-capturing the same text with the same recipe answers "Already saved" — the chain shares one `source_hash`, so one check covers it | AC-06's capture, twice |

### Slice 5 — Settings, the FR-1000 series

Two things here have never touched a network: the destination picker's calendar list, and the
FR-1004 webhook. The second is the one to watch, because it is the only code in this app that
deliberately contacts something that is not Google.

| Check | What failure looks like | Fixture |
|---|---|---|
| Settings opens and lists real calendars and task lists | Both lists populate; the current destination is selected; a hidden calendar shows its badge (FR-903) | a real account |
| Changing the destination takes effect on the next capture | The chip on the sheet shows the new calendar and the write lands there | any capture after the change |
| **AC-12** | Switch Option B → A → B. **Nothing in Google moves, changes or disappears**, and the other mode's calendar choice is still selected when you switch back. Verified in the account, not on screen | an account with items already saved |
| FR-1003's tile toggle | Turning the tile off **removes it from Quick Settings**. Turning it on puts it back | the QS shade |
| FR-1003's other two | Turning the share sheet off: Latch still appears in the resolver and the sheet says it is switched off. That is the documented limit, not a defect | share any text |
| FR-504 through settings | Set month-first, capture "05/09": it must read **9 May**. Then cold-start the app and repeat — this is the case where the first frames use defaults | "Invoice 05/09" |
| FR-512's threshold | Raise it to 99%: an ordinary capture starts routing to the Inbox. Lower it to 0: nothing does | "Kickoff 8 September 2027 at 9am" |
| Default duration and reminder | Set 45 minutes and a 10-minute reminder; an ordinary timed capture creates a 45-minute event with a 10-minute popup | as above |
| FR-605 from Settings | Change the working week to six days; AC-06's prep step moves from Thu 3 Sep to Fri 4 Sep | AC-06's capture |
| **FR-904's picker** | Tap Change on the sheet: the calendar list loads **and the sheet does not block while it does**. Choosing one changes the chip, and the write goes there | any capture |
| **FR-907** | Override the destination three times from the same app; on the third the offer appears. Answering yes creates a rule; the fourth capture from that app routes without asking | three captures shared from one app |
| FR-905 | A source-app rule, a recipe rule and a keyword rule each route as written, under Option A only | Settings, then a capture from each |
| **FR-1004, with a monitor running** | Configure `https://…`, enable it, save a capture: **exactly one** request to that endpoint and no other non-Google traffic. That is AC-18 | a request-bin style endpoint |
| **AC-19** | Configure a webhook, then confirm a notification capture: **no** request to the endpoint. Needs FR-208, so it is owed until slice 7 | notification listener |
| **AC-20** | Point the webhook at an unreachable host and save: the item still reaches Google, no blocking error appears, and undo still works | `https://127.0.0.1:9/hook` |
| **AC-21** | Reachable webhook, save offline, reconnect: Google gets the item from the queue and **no** webhook request is sent for it. Documented behaviour, not a defect | aeroplane mode |
| NFR-203's masking | Once saved, the endpoint shows as `https://host/••••••••` and the real path is nowhere on screen | a URL with a token in the path |
| The endpoint survives a cold start | It is still configured after a force-stop, and still masked | as above |
| **NFR-101 for text is unmoved** | An ordinary text capture still reaches a filled sheet under 800 ms with settings loaded. The settings read is new on this path | "Kickoff 8 September 2027 at 9am" |

### Slice 6 — FR-908, NFR-205, FR-1005

**NFR-205 is destructive and irreversible, and its device check needs a throwaway account or a
willingness to set Latch up again.** That is why it sits at the end of this list.

| Check | What failure looks like | Fixture |
|---|---|---|
| **FR-908, the ordinary case** | Launch with everything intact: nothing is said, and the destination chip is unchanged | any configured account |
| FR-908's rename | Rename the Latch calendar in Google, relaunch: the chip shows the new name **silently** | Google Calendar |
| **FR-908's own case** | Delete the destination calendar in Google, relaunch: the home screen says captures now go to your main calendar and names the one that went. The next capture lands in the primary calendar | delete the Latch calendar |
| FR-908 offline | Aeroplane mode, relaunch: **nothing changes and nothing is said**. This is the case the rule exists for and the easiest to get wrong | aeroplane mode |
| **FR-1005** | Export .ics from the sheet and share it to Google Calendar or a mail client: the file **opens as a calendar file** and imports the right dates. A file nothing will open is the failure | "Kickoff 8 September 2027 at 9am" |
| An exported chain | Apply a recipe, export: three components in one file, the event with its reminder and the tasks as VTODOs | AC-06's capture |
| Two exports do not collide | Export the same capture twice and import both: **two** items, not one overwriting the other | as above |
| A Devanagari title exports readably | Export a Hindi capture and open the file: the title is intact, not mojibake. This is the folding case no JVM test can fully close | a Devanagari capture |
| An export before saving | Export without pressing Save: **nothing** is written to Google | any capture |
| **NFR-205** | Settings → disconnect: everything local is gone (Inbox empty, queue empty, recipes back to the shipped eight, settings back to defaults), the next launch runs setup, and **Latch no longer appears** under `myaccount.google.com` → third-party connections. Items already in Google Calendar and Tasks are **untouched** | a throwaway account, or be ready to set up again |
| NFR-205 offline | The same action with no network: local data still goes, and the screen says the grant could not be removed and where to remove it by hand | aeroplane mode |

### Slice 7 — the notification listener, FR-208 to FR-212

**This is the layer with the heaviest disclosure obligation in the product and the least
JVM-reachable code.** Every filter is tested; nothing that actually reads a notification is.

| Check | What failure looks like | Fixture |
|---|---|---|
| **FR-209** — the disclosure is unavoidable | The listener cannot be enabled from the Settings layer list; the only route is the disclosure screen, and it reads in full before any switch | Settings → Ways to capture |
| The two required statements are there | FR-805a's ("not the message") and FR-210a's ("nothing is sent to your endpoint") both appear on that screen | as above |
| Android's own permission | The button opens Android's notification-access settings, and the screen notices when it comes back granted | Android settings |
| **`POST_NOTIFICATIONS`** | Turning the layer on prompts for it; **declining leaves the offer invisible**, which is the failure the permission exists to prevent. Check both answers | Android 13+ device |
| **FR-212** | An app appears in the picker only after it has notified; ticking one is what makes its messages read. An **unticked** app must produce **nothing** | WhatsApp and one other |
| **FR-211** | A message with a date produces a **low-priority** notification — no sound, no heads-up — whose text is the derived title and **never the message** | send yourself "PTM on Friday 12 September" |
| A message with no date produces nothing | Silence. The offer only follows a detected date | "ok see you" |
| Latch's own offer is not re-read | One offer, not an endless chain of them. This is the loop that would be obvious on a device and catastrophic | as above |
| An ongoing notification is ignored | Play something; no offer per progress tick | any media app |
| **FR-210 / NFR-206** | Tap the offer, save, then inspect the item **in Google**: title and date present, the message text nowhere — not the description, not the notes, not the extended properties. That is **AC-22** | as above |
| The offer does not survive a force-stop | Post an offer, force-stop Latch, tap the notification: it says the offer has gone rather than opening a capture. Memory that survived would be storage | `adb shell am force-stop` |
| A second tap finds nothing | Tap the offer, save, tap the same notification again: nothing opens a second capture of the same message | as above |
| **AC-19** | Configure a webhook, enable it, confirm a notification capture with a monitor running: **no** request to the endpoint. This is the criterion that has waited on this slice | a request-bin endpoint |
| The Inbox is never reached | A low-confidence notification capture is **saved** with its confidence shown, not routed. That is the permanent narrowing SRS 1.43 records | a vague dated message |
| NFR-104 | The listener is the only persistent service. Nothing else appears in `adb shell dumpsys activity services com.latch.android` | any state |

### Closed on Android, 3 Sep 2026 (SRS 1.70) — and both were unmet requirements

The two gaps the Windows slices found in the phone are fixed, so the clients no longer differ
on either. Both are **built and installed and neither has been watched on the device**; the
checks are in the device backlog below.

**FR-701, NFR-302 — an unreadable Inbox row is no longer deleted.** It is kept, carried through
every read, and counted in `InboxStatus.unreadable` rather than in FR-704's `due`, so the count
still reaches zero and nothing is thrown away. `InboxStatus` is `:core-model`'s and both stores
answer with the same three numbers. **The test is instrumented and had to be**: the decision is
behind `SQLiteOpenHelper` and `KeystoreCipher`, both throwing stubs under JVM unit tests, and
the fixture plants a row straight into the table with a payload the app's own cipher refuses.

**FR-1004b — the passive report exists.** `CaptureSaver` discarded the delivery result, so the
requirement's "shall not raise a blocking error" held and its "shall be reported passively in
the Settings screen" had nothing behind it. The record is `:webhook`'s and shared; the Settings
screen shows the outcome and the status the endpoint gave. Making it reportable also made it
testable — the send is a function now, so what this file decides is reachable without a socket,
and FR-210a is asserted at the same seam: a notification capture reaches the sender **not at
all**.

### Slice 9 — the instrumented suite

**The suite itself has never been run**, there being no device attached to the session that
wrote it. A suite that has never executed is one whose fixtures may not be reachable and whose
assertions may not hold, so it is not counted among the 576 tests that pass.

    ./gradlew :app:connectedDebugAndroidTest

| Check | What failure looks like |
|---|---|
| It runs at all | The two assets (`latin-chat.png`, `letter.pdf`) resolve from `app/src/androidTest/assets` and copy to the cache. A missing asset fails every OCR test at once and says so |
| **The app is still installed afterwards** | `adb shell pm list packages com.latch.android` still lists it. If it does not, the AGP property has stopped working and the note in `gradle.properties` is now wrong |
| The three defect tests genuinely bite | Each is written against a defect that actually shipped. Worth confirming at least one **fails** when reintroduced — the launch canary was verified that way and it is what makes a regression test a regression test |
| The storage tests are order-independent | Run the class twice, and run it after a device pass has left real data behind. `@Before` clears every store; a test that only passes on a clean install is one that will fail on someone's phone |
| NFR-103 is unmoved | The androidTest APK is separate and never linked into the app's, so the release APK must not have grown. `./gradlew :app:assembleRelease` and compare |

**One record is now stale and was left alone deliberately.** `docs/DEPENDENCIES.md`'s entry for
the three `androidx.test` artifacts says "This is a canary, not a test layer". It is a test layer
now — a small one, over stores and files, with no Espresso and no Compose UI test. The three
artifacts are unchanged and no dependency was added, so the justification still holds; the
sentence describing its scope does not. Recorded here rather than edited there, because this
session was instructed to leave that file untouched.
