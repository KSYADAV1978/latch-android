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
| `:core-model` | pure Kotlin (JVM) | Domain types from SRS §7.1 |
| `:parser` | pure Kotlin (JVM) | Date and time extraction, classification (FR-500 series) |
| `:recipes` | pure Kotlin (JVM) | Working-day arithmetic, recipe expansion (FR-600 series) |
| `:ocr` | Android library | On-device OCR (FR-215, FR-207). The only module that names an ML Kit type; `:app` sees `OcrReader` and `OcrResult`. Bundled models, +12.83 MB per device — the largest single thing this app ships |
| `:data` | Android library | Storage — Inbox, write queue and secret store contracts, account defaults persisted — and the Google API contracts plus their REST implementations. Every outbound request in the app originates here. No Play services: the OAuth grant lives in `:app` |

Dependencies point one way: `:app` → `:data`/`:parser`/`:recipes`/`:ocr` → `:core-model`.
`:parser` and `:recipes` do not depend on each other; they exchange `:core-model` types.
`:ocr` depends on neither — it returns text, and what that text means is the parser's business.

## How to build and test
Requires JDK 17+ on `JAVA_HOME`. Android Studio's bundled JBR works:
`C:\Program Files\Android\Android Studio\jbr` (JDK 21). Gradle and the Android SDK
components download on first run; `local.properties` needs `sdk.dir` and is not committed.

```
./gradlew build                # everything: compile, unit tests, lint, debug + release APK
./gradlew :parser:test         # the parser corpus alone — seconds, no emulator
./gradlew :app:assembleDebug   # debug APK

# The launch canary. Needs a connected device and is NOT part of `build`, so a green
# build says nothing about whether the app starts — that gap is what it exists for.
./gradlew :app:connectedDebugAndroidTest
```

## Hard constraints
Do not violate these without asking first:
- **`:parser` and `:recipes` stay pure Kotlin.** No Android dependency, no third-party
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

## Commits
Commit directly to `main`. This is a single-developer repository with no CI and no review
step, so a feature branch adds ceremony without adding safety — there is nothing for it to
gate. This overrides the default habit of branching before committing to the default branch.

Subject line in the present tense, imperative, one line. The body carries the reasoning:
which requirements the change serves, and any decision the code cannot state for itself.

## State of the build
Skeleton only. Working: the six-module structure, the parser (87-case corpus, all passing),
working-day arithmetic and recipe expansion, capture layers 1, 2 and 4 as far as the
confirmation screen, first-run setup (FR-100 series) end to end, and on-device OCR of
images and PDFs (FR-215, FR-207) verified on a device.

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
| **AC-05 — a screenshot with three dates** | 31 Aug 2026 | **Pass**, on `three-dates-v4.png`. Exactly three dates — `TASK 14 Sept 2026`, `TASK 20 Sept 2027`, `EVENT 1 Oct 2027`, the range correctly one candidate and an Event. Three checkboxes all `checked=true` at open, read from the view hierarchy rather than eyeballed; unticking the middle gave `true,false,true`; the save wrote **two** items, not three; nothing on the unticked date; and a re-capture answered "Already saved", which is Google confirming both landed. **Provenance:** v4 is a *rendered* chat image, not a device screenshot, so the criterion is met by a proxy and is to be re-run if a real screenshot is supplied. **The undo half was not re-run here** and is not silently omitted: it is covered by AC-11's four-item chain undo of 28 Aug. |

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
`items=2`, and the device mirror agrees. The account still holds exactly the two Kickoff events
the original defect produced.

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

**Owed, but not on a device**, so it is not looked for in the list above: **AC-07's second
half**. Its phone half passed on 27 Aug and is recorded in the table; what is outstanding is
"capture the same message on phone **and PC**", which needs the Windows client of §4.1 to
exist. No number of device runs can close it, and it is not a device gap.

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

FR-512 is under an **interim** reading recorded in the SRS: with no Capture Inbox to route to,
a user-confirmed item is saved whatever its confidence, and the confidence is shown instead of
blocking the save. That reading dies when the FR-700 series lands.

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

Also not built, and user-visible now that saving is real: FR-506 row 3's date picker, so a
capture with a time but no date cannot be saved — and its per-row form is unreachable until
the assembler can leave a time unpaired beside dated candidates (SRS 1.25);
FR-507's type override; and FR-510's past-date follow-up. Each is recorded against
its requirement in the SRS. Further out:
the Capture Inbox (FR-700 series), Settings (FR-1000 series), OCR (FR-215) and the
notification listener (FR-208). FR-908 is not built either — the calendar list is not
refreshed on launch and a stored destination that has been deleted or has lost write access
is not yet detected; the `TODO` in `LatchApplication.onCreate` marks where it goes. NFR-205's
revoke is unbuilt, which is why `AuthClient.signOut` is still a no-op.

Sign-in works only on builds whose signing certificate is registered against the Android
OAuth client. There is no release signing config, so that means debug builds from a machine
whose debug keystore fingerprint is registered. All four FR-002 scopes are Sensitive, so
until OAuth verification (SRS §8.6) only test users on the consent screen can sign in.

FR-105 is load-bearing and structural, not a matter of care: `com.latch.android.setup` is
pure Kotlin, `SetupEffect.Commit` is the only effect that can reach `calendars.insert`, and
`SetupEvent.FinishRequested` is the only event that produces one. AC-15 and AC-16 are JVM
unit tests over that reducer. Keep it that way.

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
| "Reading page N of M…" counts up while a PDF is read | The line never appears, or it appears once and sticks at 1 — a progress callback that fires on a thread the flow does not publish from, or one page's recognition so fast the state is overwritten before a frame draws | `testdata/fr215/long.pdf` (14 pages). It must show **M = 10**, not 14, and must count 1→10 |
| The cap line still follows | "First 10 of 14 pages read." after the sheet fills. The progress line and the cap line are different sentences about different numbers, and showing 10 in both places is correct | the same file |
| A short PDF still says the same thing | `letter.pdf` (2 pages) counts 1→2 and shows **no** cap line | `testdata/fr215/letter.pdf` |
| An **image** capture is unchanged | The spinner says "Reading the text…", never "Reading page…". An image has nothing to count, and a progress line on one would be a number invented from nothing | any `testdata/fr215/*.png` |
| NFR-101 for text is unmoved | An ordinary text capture is still synchronous — `content ready, ocr=false` in `LatchTiming`, no spinner at any point. NFR-102's note requires this re-check whenever the asynchronous path is touched | "Kickoff 8 September 2027 at 9am" |
