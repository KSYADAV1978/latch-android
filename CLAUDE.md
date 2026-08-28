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
| `:data` | Android library | Storage — Inbox, write queue and secret store contracts, account defaults persisted — and the Google API contracts plus their REST implementations. Every outbound request in the app originates here. No Play services: the OAuth grant lives in `:app` |

Dependencies point one way: `:app` → `:data`/`:parser`/`:recipes` → `:core-model`.
`:parser` and `:recipes` do not depend on each other; they exchange `:core-model` types.

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

## Commits
Commit directly to `main`. This is a single-developer repository with no CI and no review
step, so a feature branch adds ceremony without adding safety — there is nothing for it to
gate. This overrides the default habit of branching before committing to the default branch.

Subject line in the present tense, imperative, one line. The body carries the reasoning:
which requirements the change serves, and any decision the code cannot state for itself.

## State of the build
Skeleton only. Working: the five-module structure, the parser (87-case corpus, all passing),
working-day arithmetic and recipe expansion, capture layers 1, 2 and 4 as far as the
confirmation screen, and first-run setup (FR-100 series) end to end.

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

Also established in passing, none of it reachable from a JVM test: the OAuth grant works end
to end (so the debug SHA-1 is registered and the account is a test user), `KeystoreCipher`
encrypts against a real Keystore, a completed setup survives a cold start, and the stored
preference key is a hex digest rather than an email address.

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

Not yet run on a device: **the FR-803 re-check at drain**, and
**NFR-302's other two limbs** — app termination and device restart while queued — none of which
AC-10's run exercised; **AC-15**, **AC-09** (hidden calendar offered and actually ticked),
**AC-17** (network monitor over a full cycle), and the consent-bridge cases: rotation and
process death with the consent screen up, which are the only part of this app with no
automated cover at all. FR-804's own gaps join that list: **a queued `UPDATE` has never
drained** — the 28 Aug pass was online throughout, so the worker's update path and the
staleness SRS 1.19 records have only ever run on the JVM — and **the task search has never
capped**, which needs a list longer than ten pages, so the give-up behaviour is JVM-only.

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

Also not built, and user-visible now that saving is real: FR-506 row 3's date picker, so a capture with a time but no date
cannot be saved; FR-511's per-date checkboxes, so only the first date of a multi-date capture
is saved — which is also why an undo chain is one item on every path reachable today;
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
