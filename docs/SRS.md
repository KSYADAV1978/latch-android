---
title: "Software Requirements Specification"
subtitle: "Working title: Latch — cross-platform date and deadline capture"
author: "Version 1.21 (draft for developer handover)"
date: "28 August 2026"
---

# 1. Document control

| Field | Value |
|---|---|
| Document | Software Requirements Specification (SRS) |
| Product | Latch (working title — subject to trademark clearance) |
| Version | 1.21 — draft for developer handover |
| Status | For estimation and build planning |
| Platforms | Android, Windows, Chrome/Edge extension |
| Commercial model | Free. No ads, no paid tier, no in-app purchase |

## 1.1 Revision history

| Version | Date | Change |
|---|---|---|
| 1.0 | 25 Aug 2026 | Initial draft |
| 1.1 | 25 Aug 2026 | Resolved a contradiction between the outbound webhook (FR-1004) and the no-external-traffic acceptance test (AC-17). FR-1004 reclassified to [SHOULD] and made off-by-default; design principle 2 and NFR-201 qualified; AC-17 scoped to the default configuration; AC-18 added. |
| 1.2 | 25 Aug 2026 | Completed the webhook amendment: §2.3 vision qualified; FR-210 now suppresses webhook delivery for notification-sourced captures (AC-19 added); FR-1004 payload schema defined and phased; FR-1001 settings list and FR-1103 Data Safety obligation updated. |
| 1.3 | 25 Aug 2026 | Closed the webhook thread. §12 no longer justifies a scope exclusion by a deferrable feature; the privacy-policy obligation moved into FR-1102 as a hard clause; the conditional `[MUST, if X ships]` modality added to the legend; NFR-203 extended to cover the webhook endpoint as a secret; FR-1004b defines failure semantics (AC-20 added). |
| 1.4 | 25 Aug 2026 | Closing corrections: classification note now lists FR-1004b; the offline no-webhook consequence of FR-1004b stated explicitly and given an acceptance test (AC-21). |
| 1.5 | 26 Aug 2026 | Recorded four parser rules established during the Android build: year resolution (FR-513), the Hinglish heuristics and their confidence treatment (FR-514), the parse-context requirement (FR-515) and the `java.time` decision, which also fixes the minimum SDK (FR-516). Recorded the reading of NFR-204 under which platform encryption satisfies encryption at rest. No existing requirement changed. |
| 1.6 | 26 Aug 2026 | Recorded the reading of NFR-203 under which the Android Keystore is used directly and no encryption library is required, established when account defaults were first persisted. Names the restore-unreadability consequence and requires it be treated as absence rather than error. No existing requirement changed. |
| 1.7 | 26 Aug 2026 | Specified §7.2 in full ahead of the first write of the FR-800 path. §7.2 previously gave six key names and nothing else; it now fixes every value's format, the hash normalisation, the task-notes encoding, the version-skew rule and the Google platform limits, and is marked normative as a cross-client wire contract. Adds `latch.item_key`, without which FR-804 and AC-08 are unsatisfiable — a reschedule has different source text, so FR-803's hash can never find the item being rescheduled. Points at normative conformance vectors for AC-07. FR-802 now defers to §7.2, resolving a five-versus-six element discrepancy. §2.4 widens "chain" from a Recipe expansion to the items produced by one save, so FR-807 undo has a group identity in every case. No existing requirement changed in substance. |
| 1.8 | 26 Aug 2026 | Resolved the conflict between FR-805 and FR-210/NFR-206, before any write path exists to embed it. FR-805 required the source text in every item; NFR-206 forbids notification content reaching persistent storage. A reading recorded against NFR-206 settles that a Google item is persistent storage, and FR-805a excludes the source text for notification-sourced captures only, on the same reasoning as FR-210a. Records what is deliberately still written for that layer — a derived title, and the FR-803 hash — and the brute-force caveat on hashing short messages. AC-22 added. |
| 1.9 | 26 Aug 2026 | Recorded how FR-803 is met and where it is bounded, as the write path was built. Events are matched server-side and are exact; tasks have no content filter in the Google API at all, so the check is a scan bounded to D±1 day — a day wider than correctness needs, to absorb time-zone boundary differences between two devices, which is where AC-07 would otherwise fail silently. An undated task falls back to a ten-page capped scan that reports when it gives up rather than returning a false negative, with the residual duplicate risk accepted for v1.0 and the cure named. Also records that FR-806's queue is deliberately deferred, that a failed or offline write is surfaced under NFR-303 and lost, and that AC-10 does not pass until it lands. No requirement changed. |
| 1.10 | 26 Aug 2026 | Recorded two readings as the Save button was built. FR-512 gains an **interim** reading, in force only until the FR-700 Inbox exists: with nowhere to route to, a user-confirmed item is saved whatever its confidence, with the confidence surfaced rather than the save blocked — refusing would lose the capture entirely and would make an undated item unsaveable, which design principle 1 contradicts. FR-512 is superseded the moment the Inbox lands. FR-807 records that a save cannot yet be undone from the app, that `latch.chain_id` is already written so undo has a group to act on, and alongside it that FR-506 row 3, FR-507, FR-510 and FR-511 are unmet because the UI each needs is not built. No requirement changed. |
| 1.11 | 27 Aug 2026 | Specified §7.2's `latch.item_key` properly, after the first real writes showed it coming back byte-identical to `latch.source_hash` and therefore inert. It is now defined as the title with every date and time expression removed, with the derivation given step by step, including that a corroborating weekday is part of the date phrase and must be removed with it — omitting it leaves the day name in the identity, which moves on exactly the reschedule FR-804 exists to catch. Records that this clause is the one part of §7.2 resting on parser behaviour rather than arithmetic over text, that it is where three clients are most likely to drift, that the existing conformance vectors cannot pin it, and that FR-804 is therefore reliable within a client and unproven across them until a second one exists. No requirement changed. |
| 1.12 | 27 Aug 2026 | Recorded how FR-807 undo is met and where it is bounded, as it was built. Undo removes the ids the save recorded rather than re-querying `latch.chain_id`: the chain id is the group identity and is on every item, but the Tasks API has no content filter — the same limitation that makes FR-803 a bounded scan — so finding a task chain by it would be a scan that is allowed to give up, and undo would be exact for events and best-effort for tasks. Records the three consequences: the offer does not survive process death, a new capture ends it, and a cross-device undo would be a different feature, events only. Records the reading under which the capture window suppresses its own touch-outside dismissal while the offer stands, without which "not less than 10 seconds" is met on paper and not in the hand. No requirement changed. |
| 1.13 | 27 Aug 2026 | Recorded how FR-806's write queue is met and where it is bounded, as it was built. Records that the payload is held in an app-private encrypted store and **not** in WorkManager, whose own database is unencrypted and whose input data is capped; that FR-803's duplicate check therefore runs a second time at drain, without which two offline captures of one message become two items and AC-07 fails inside AC-10; and that a queued entry is not drained inside its FR-807 undo window, which removes the race between an undo and a drain rather than trying to win it. Records two limits: a queued capture does not survive a device transfer, and an entry that has been given up on stays visible but has no manual retry until Settings (FR-1000) exists. §7.1's `WriteQueue` row is corrected — an `Item` cannot carry a write, because §7.2's metadata, the FR-805 body and the time zone are not on it. No requirement changed.
| 1.14 | 27 Aug 2026 | Records a reading against FR-505 fixing **which** of several dates in one capture the app acts on, after a capture opened on a date the writer had not committed to. Ranking is: a date with a time, then an explicitly written date over one the app calculated, then earliest mention. FR-505's confidence is deliberately **not** a key — a range's year is written once at the end, so ranking on it makes the end of every "from A to B, 2026" the primary, which is the same defect by another road. Also corrects the corroborating-weekday clause of §7.2: a weekday is absorbed by the one date it falls on, nearest wins between two that match, and a weekday elsewhere in the text stays a date of its own — but a weekday written into the same phrase as a date is still absorbed even where it contradicts it, because §7.2's own example "PTM on Friday 12 September" is such a case (12 September 2026 is a Saturday) and reading a writer's slip as a second commitment would invent an item. No requirement changed; FR-511 remains unmet.
| 1.15 | 28 Aug 2026 | Records that v1.14's correction to the corroborating-weekday clause of §7.2 changed the `latch.item_key` **wire contract**, which the entry above does not say — 1.14 shipped without it, describing the change as a parser fix and closing with "no requirement changed", which is true of the code and false of the data. `item_key` is written into items in the user's Google account and is the value FR-804 matches a reschedule on, so changing which span that clause blanks changes the identity derived from the same message: a breaking change to stored data rather than an internal correction. The move is acceptable **only while Android is the sole client deriving keys** — with one writer and one reader the contract can still be moved, whereas once §4.1's Windows or browser client derives keys it can be moved only in all of them at once, and a client left behind degrades FR-804 without failing anything visibly. Two consequences are accepted rather than fixed. Items written before v1.14 may carry keys the corrected derivation will not reproduce — any capture whose title held a weekday the proximity-alone rule absorbed, or failed to absorb — and **FR-804 shall treat such an item as unmatched**, so rescheduling one creates a second item instead of updating the first; the items written during the 27 Aug device runs are already in that state, which makes this a live consequence and not a hypothetical one. And no migration is offered, because an item already in a user's account is not rewritten — the same constraint that required §7.2 to be pinned before anything wrote it. §7.2's own text is amended to carry all of this, a changelog row not being normative and an implementer of the derivation landing on §7.2 rather than here. No requirement changed.
| 1.16 | 28 Aug 2026 | Specifies FR-804 ahead of building it, and corrects two defects found while planning it — both of the kind that cannot be corrected after the first write, which is why this entry precedes the code rather than recording it. **§7.2 gains a three-value decision table.** "Same key, different hash is a reschedule" silently assumed a different date; a message merely restated — forwarded, quoted, re-sent with a different greeting — satisfies it while naming the very same date, and would have been offered as a move to where the item already is. Same key, different hash and the **same resolved date** is now a duplicate, never an offer. **§7.2 also states that its metadata is written exactly once, at insert, and that no later operation modifies it** — not an update, not an undo, not a repair. That one rule settles three things that would otherwise each have needed their own clause: an updated item keeps the `source_hash` of the capture that created it, so the original message stays protected by FR-803 and neither it nor the applied reschedule text nags on re-capture; no client may backfill or recompute an `item_key`, and specifically may not derive a superseded-derivation key as a fallback query, which would restore the wire contract v1.14 moved; and an undo of an update never has metadata to restore. **FR-807's wording did not cover an update.** "Removing all items created by that save" describes a create, and deleting an item an update had merely moved would destroy something the user owned beforehand — the one thing an undo must not do. Undo is now one of three operations chosen by what the save did: delete what was created, drop what was queued, restore what was updated. **§7.1's `pending_write` could not express an `UPDATE`** — the same defect as `item_id`, one level down. `operation` has admitted `UPDATE` since the table was written while the payload held only the state to be written, carrying neither the target item's remote id nor the prior values a restore must write back; both are added, bounded to exactly the fields an update may modify. **FR-804 gains a how-it-is-met note.** An update modifies date fields only — for an event `start`, `end`, `allDay` and time zone, for a task `due` — the title being the identity matched on, and the description and notes being where §7.2's metadata lives on tasks. FR-803's D±1 task bound does not carry over, because a reschedule has a different date by definition, so task detection is a capped scan in every case and a capped scan without a match is not "no reschedule". Where several items share a key the offer goes to the one with the latest date. Weekday names in the offer are computed from the resolved date, never carried from the captured text, which may contradict it. Two narrowings and two residuals are recorded rather than left to be found: **the matched item is updated and not its chain**, since an item_key query returns one item per chain and reaching the rest would need a `chain_id` lookup that is exact for events and a give-up-able scan for tasks — the asymmetry FR-807 already refused for deletes — which has no observable consequence until FR-511 or FR-601 makes a multi-item chain reachable; **an offline reschedule becomes a second item**, because detection is a query and repeating it at drain cannot work when a silent update is forbidden and no user is present, with the Capture Inbox named as the cure; **FR-804 detects re-statements and not announcements of change**, "moved to" being extra words that change the title and therefore the key; and **an item that has moved twice** can be offered a backward move if a superseded reschedule text is re-captured, which is inherent to storing one hash. FR-807's scope is clarified to cover an update and FR-804 is narrowed to the matched item; no requirement is added or removed.
| 1.17 | 28 Aug 2026 | Records a change of fact, not of specification. The items written during the 27 Aug device runs — the ones carrying `latch.item_key` values derived before v1.14 — have been deleted from the developer's Google account. The 1.15 row above says that those items made the pre-fix consequence "a live consequence and not a hypothetical one"; that was true when it was written and is now historical. **The row stands as written and is not edited**, for the reason 1.15 itself exists: a changelog that revises its own past cannot be relied on to show what was known when. **§7.2's pre-fix clause is likewise unchanged and remains normative** — it states a rule about any item written before v1.14, wherever it exists, and never claimed anything about a particular account. What changes is only what is true of one account today: no known Google account now holds an item with a stale key, so the clause currently describes the wire contract and no live data. One verification consequence follows, and is recorded here rather than allowed to lapse quietly. FR-804's device check for "a pre-fix item is offered no update" **is retired for want of a fixture**: there is no longer an item with a stale key to capture a reschedule of, and manufacturing one would mean writing an item under a derivation this specification has withdrawn. Pre-fix-unmatched is therefore verified at **JVM level only**, by the assertion that exactly one `item_key` query shape is issued and that no superseded-derivation fallback query exists — which is the part §7.2 actually forbids, and the part a device could not have shown anyway. Should a second client ever be built against a pre-v1.14 account, the device-level case returns with it. No requirement changed.
| 1.18 | 28 Aug 2026 | Makes explicit a consequence of v1.16's dates-only rule that was implied but not stated: an updated item's **description continues to show the originating capture's text**, including the old date and weekday that text names. The description is provenance — where the item came from — and not a statement of the item's current state, which its date fields carry. Recorded so it is recognised as intended behaviour rather than reported later as a defect. No rule is added and no behaviour changes; this is the same residual v1.16 already recorded, said plainly. No requirement changed.
| 1.19 | 28 Aug 2026 | Records three consequences of v1.16's decision to read an item's prior state **at match time**, all found while building the `:data` transport and one of them a defect the specification had not ruled on. **An item whose current dates cannot be read is not a match**, and yields an ordinary create with no offer: the values undo must write back exist nowhere but in what the search read, so a match without them would be an update that could not be undone. The realistic cause is the user's own editing in Google Calendar, not a malformed response. **A matched event that has become recurring is likewise no match, and this one is new rather than clarified.** Its dates parse perfectly, so nothing already written would have stopped it; the objection is that `events.list` is not asked for `singleEvents`, so a series returns as its master and `events.patch` on a master moves **every** occurrence — silently converting "move this meeting" into "move a weekly stand-up for the rest of time", which no requirement authorises and which undo could not reverse across occurrences it never recorded. The refusal is required to be explicit, testing the recurrence fields rather than relying on a parse that happens to fail, and to cover the instance form as well as the master so that later asking for `singleEvents` cannot reopen it. **And the prior state can go stale**: an `UPDATE` that fails transiently waits in FR-806's queue, so an undo taken after a delayed drain writes back match-time values and overwrites any hand edit made in between. That window is bounded by the retry schedule rather than by FR-807's ten seconds and is accepted for v1.0, the alternative being a re-read at drain that can itself fail and that restores a state the user was never shown. No requirement changed.
| 1.20 | 28 Aug 2026 | Sharpens v1.16's third table row and records two rules beside it, all three being definitions that read like defects to anyone meeting them without the reasoning — the kind a later reader corrects into a bug. **Row 3's "same date" is defined as "an update would change nothing"**, compared over exactly the fields §5.8 permits an update to modify. The loose phrase was "same resolved date", and it is now precise in the direction that matters: **a change of time on the same day is a reschedule and must be offered as one**, because an update would move something. Read as a comparison of calendar dates alone, the row would refuse to move a meeting from 21:30 to 22:30 — a reschedule by any ordinary meaning, and one the user would then have to perform by hand. **The time zone is excluded from that comparison**, because Google returns none for an event using its calendar's default: an item written under the device zone and read back without one would compare as changed while being identical, and the app would offer to move it onto the position it already holds — the precise misreading row 3 exists to prevent. Nothing on this path reschedules across zones; the zone comes from the device, never from the text. **And FR-803's duplicate check is not run when a queued `UPDATE` drains.** FR-806 requires that check at drain for a `CREATE`, without which two offline captures of one message become two items; an `UPDATE` creates nothing, and the question FR-803 asks is answered "yes" by the very item the entry is about to move — so running it would find that item by its own `source_hash` and read the answer as a reason to abandon the move, leaving the reschedule silently undone. It is the one place in the app where an FR-803 check is skipped before a write, and it is skipped because the write is not a creation. No requirement changed.
| 1.21 | 28 Aug 2026 | Records a **known FR-503 gap found on a device**, and a consequence of it that reaches FR-512. Android's voice typing punctuates a dictated date — "Project sync on friday. March, 8, 2030 at 21:30" — and `WrittenDateRule`'s month-first pattern allows an optional period after the month name but **not a comma**, so "March, 8, 2030" is not matched at all. The bare "friday" beside it then matches on its own, resolves forward to the next Friday, and the 21:30 attaches to that: the capture opened on 28 Aug 2026 rather than 8 Mar 2030. The exact input is kept in the corpus as a commented row so the next person to touch that rule has the text verbatim. **The consequence for FR-512 is the part worth recording.** A bare weekday carries `MEDIUM` (0.75) where a qualified one ("next Friday") carries `HIGH`, so the framework FR-514 describes does apply — but 0.75 is **above** the default threshold of 0.6, so once the FR-700 Inbox exists this capture would still be saved directly rather than routed for confirmation, with a date three and a half years wrong. Note also that the parser has **no notion of "a weekday whose adjacent date failed to parse"**: the written-date rule simply produced nothing, and the weekday match is an ordinary one that cannot know what did not happen. Any reduced confidence for that situation would have to be built, not merely relied upon. **The fix is deferred to the FR-511 parser work rather than taken now**, because it is not the contained change it appears to be: widening the pattern to accept `Month, D` makes "In March, 8 people attended" a date, and a comma after a month name is a clause boundary in English far more often than it is dictation. The narrower form — accepting the comma only where a four-digit year follows — is the likelier answer and is itself a reading to be taken deliberately. No requirement changed.

**How to read this document.** Requirements are numbered (FR-nnn functional, NFR-nnn non-functional) so they can be quoted, tracked and tested individually. Requirements marked **[MUST]** are in scope for v1.0. Those marked **[SHOULD]** are expected but may be deferred by agreement. Those marked **[LATER]** are explicitly out of scope for v1.0 and are recorded here only to prevent architectural decisions that would block them. A requirement marked **[MUST, if X ships]** is conditional: it does not compel X to be built, but binds absolutely if X is built.

---

# 2. Introduction

## 2.1 Purpose

This document specifies the requirements for a free, cross-platform utility that captures dates, deadlines and to-dos from text or images encountered anywhere on a phone or PC, and writes them to the user's Google Calendar and Google Tasks.

## 2.2 Problem statement

Commitments arrive as unstructured text scattered across messaging apps, email, web pages, PDFs and images. Transcribing them into a calendar is manual, so they are frequently missed. Existing tools solve fragments of this: screenshot-to-calendar apps handle images but send them to vendor clouds; task managers offer quick capture but no image handling and no system-wide text selection; browser extensions cover web pages only. No single product covers phone and PC with a unified capture model and on-device processing.

## 2.3 Product vision

One capture gesture on every surface the user touches; one calendar at the other end; and nothing leaves the device except the finished calendar entry — unless the user has deliberately configured it to (FR-1004).

## 2.4 Definitions

| Term | Meaning |
|---|---|
| **Capture** | A single user action that sends selected text or an image to the app |
| **Item** | One thing the app will create — either an Event or a Task |
| **Event** | A Google Calendar event. Has a start time, and may have end time, location, attendees, reminders |
| **Task** | A Google Tasks entry. Has a title, optional due **date** (no time — see §9.1), optional notes |
| **Recipe** | A user-selectable template that expands one captured date into a set of related items |
| **Chain** | The set of items produced by one save. Usually a Recipe expansion (FR-601), but a multi-date capture (FR-511) and a save of a single item are also chains, of several and of one — FR-807 undoes "all items created by that save", so every save needs a group identity, not only those a Recipe produced |
| **Capture Inbox** | Local holding area for captures that are incomplete, undated, or low-confidence |
| **Latch calendar** | A dedicated Google Calendar created by the app at setup (the default destination) |
| **Source** | The application a capture originated from (mail client, messaging app, browser, etc.) |

## 2.5 Reference material

Two animated walkthrough videos (Android and Windows) accompany this document and illustrate the intended first-run setup and capture flows. Where the video and this document differ, **this document governs**.

---

# 3. Product overview

## 3.1 Target users

Primary: general consumers in India managing school, family, travel, payment and appointment dates arriving through messaging apps and email. Secondary: professional users capturing meeting and deadline commitments from email and documents.

## 3.2 Design principles

These are binding and should be treated as acceptance criteria in their own right.

1. **Never invent a date.** If no date is found, the item is created undated. The app must never silently default to "today" or any other guess.
2. **On-device only.** Text and image content is never transmitted to any server operated by the publisher, nor to any third party other than Google, and then only as the finished calendar or task entry. The sole exception is an endpoint the user has explicitly configured themselves under FR-1004, which is disabled by default.
3. **No backend.** The publisher operates no server that receives, stores or processes user content.
4. **Show the destination.** The target calendar is always visible before saving and always changeable in one tap.
5. **Reversible.** Anything the app creates can be found and undone as a group.

## 3.3 Non-goals

The product is not a calendar client, not a task manager, and not a note-taking app. It does not display a calendar view beyond what is needed to confirm a save. It does not attempt to replace Google Calendar or Google Tasks.

---

# 4. System architecture

## 4.1 Overview

```
  Android app  --+
  Windows app  --+-->  Google Calendar API
  Browser ext  --+     Google Tasks API
                            |
                            v
                   User's Google account
```

There is no publisher-operated backend. Cross-device consistency is achieved because all clients write to the same Google account; **Google Calendar is the synchronisation layer**.

## 4.2 Client responsibilities

Each client independently performs: capture, on-device text extraction (OCR where applicable), parsing, classification, recipe expansion, and the API write. No client depends on another being present.

## 4.3 Google Cloud configuration

**FR-001 [MUST]** A single Google Cloud project shall host three OAuth client credentials: Android (package name + SHA-1 release fingerprint), Desktop, and Chrome Extension.

**FR-002 [MUST]** The application shall request only the following scopes:

| Scope | Purpose | Classification |
|---|---|---|
| `.../auth/calendar.events` | Create and update events | Sensitive |
| `.../auth/calendar.calendarlist` | Read the user's calendar list | Sensitive |
| `.../auth/calendar.calendars` | Create the Latch calendar | Sensitive |
| `.../auth/tasks` | Create tasks and read task lists | Sensitive |

**FR-003 [MUST]** No scope granting access to Gmail, Drive (except as noted in FR-1006), Contacts or any other Google service shall be requested. The developer shall confirm the minimum sufficient scope set against current Google documentation before submitting for OAuth verification, as scope granularity changes over time.

---

# 5. Functional requirements

## 5.1 First-run setup (FR-100 series)

**FR-101 [MUST]** On first launch the app shall present a three-step setup: (1) Google sign-in, (2) calendar destination, (3) task list selection.

**FR-102 [MUST]** Step 1 shall state plainly, on screen, that the app does not read the user's mail or messages and stores nothing on publisher servers.

**FR-103 [MUST]** Step 2 shall present exactly two options:

- **Option B — "Create a Latch calendar" (default, pre-selected, marked Recommended).** A new Google Calendar is created and becomes the destination for all captures.
- **Option A — "Use my existing calendars".** Captures are routed to the user's existing calendars per §5.10.

**FR-104 [MUST]** Step 2 shall explain the benefit of Option B in the user's terms: one colour, one show/hide checkbox in Google Calendar, and the ability to undo a batch.

**FR-105 [MUST]** The Latch calendar shall be created via `calendars.insert` **only on completion of setup**, not on entry to step 2. If the user abandons setup, nothing shall be created in their Google account.

**FR-106 [MUST]** Step 3 shall list the user's task lists via `tasklists.list` and allow selection of a default.

**FR-107 [MUST]** Step 3 shall disclose that Google displays all task lists within a single Tasks layer in Google Calendar, and that task lists cannot be colour-filtered in the way calendars can.

**FR-108 [MUST]** Setup shall be completable in under 60 seconds with no text entry beyond Google authentication.

**FR-109 [MUST]** Every choice made during setup shall be changeable afterwards in Settings, and the setup screens shall say so.

**FR-110 [SHOULD]** The app shall support more than one Google account, with an account switcher available at capture time. Calendar and task list defaults are stored per account.

## 5.2 Android capture (FR-200 series)

Four independent capture layers are required. Each must function if the others are disabled.

### Layer 1 — Text selection

**FR-201 [MUST]** The app shall register an activity with an `ACTION_PROCESS_TEXT` intent filter (`category.DEFAULT`, `mimeType text/plain`) so that it appears in the system text-selection toolbar.

**FR-202 [MUST]** The activity's `android:label` shall be short enough to display without truncation in the floating toolbar (target: 6 characters or fewer).

**FR-203 [MUST]** The activity shall read `EXTRA_PROCESS_TEXT` and honour `EXTRA_PROCESS_TEXT_READONLY`.

**FR-204 [MUST]** Documentation shall note that host applications targeting Android 11 or later must declare a `<queries>` element for `PROCESS_TEXT` for the action to appear, and that this is outside the app's control. Known non-cooperating apps shall be listed in an in-app help topic.

### Layer 2 — Share sheet

**FR-205 [MUST]** The app shall register as a share target for `text/plain` and `image/*`.

**FR-206 [MUST]** Where the sending app supplies `EXTRA_SUBJECT` (typical of mail clients), the subject line shall be preferred as the item title over the shared body text.

**FR-207 [MUST]** The share target shall accept `application/pdf` and extract text from it. (See FR-215.)

### Layer 3 — Notification listener

**FR-208 [MUST]** The app shall optionally implement `NotificationListenerService` to detect dates in incoming messages.

**FR-209 [MUST]** This layer shall be **disabled by default**, enabled only through an explicit in-app disclosure screen that names what is accessed and why.

**FR-210 [MUST]** Notification content shall be processed entirely in memory, never written to disk, and never transmitted. Only a user-confirmed item is persisted.

**FR-210a [MUST]** Webhook delivery under FR-1004 shall be **suppressed for any capture originating from this layer**, whether or not a webhook is configured. Rationale: this layer carries the heaviest disclosure obligation under FR-1104, and forwarding notification-derived content to an arbitrary third-party endpoint is inconsistent with the basis on which the user grants notification access. The suppression shall be stated in the notification-access disclosure screen.

**FR-211 [MUST]** When a date is detected, the app shall post a low-priority notification offering to capture it. It shall never create an item without confirmation.

**FR-212 [MUST]** The user shall be able to select which applications this layer monitors.

### Layer 4 — Quick Settings tile

**FR-213 [MUST]** The app shall provide a `TileService` that, when tapped, brings a capture activity to the foreground and reads the clipboard. This is the supported path for apps (notably WhatsApp) where partial text selection is unavailable and the user must use "Copy".

**FR-214 [MUST]** The app shall not attempt background clipboard access, which is restricted from Android 10 onward.

### Image and document capture

**FR-215 [MUST]** Images and PDFs received by any layer shall be processed with on-device OCR (ML Kit Text Recognition v2 or equivalent), supporting at minimum Latin and Devanagari scripts.

**FR-216 [MUST]** OCR shall run entirely on device. No image shall be transmitted anywhere.

**FR-217 [MUST]** The app shall **not** use `MediaProjection` for screen capture. Rationale: from Android 14, consent is required for every capture session and the token cannot be reused, making the interaction unusable for this purpose.

**FR-218 [MUST]** The app shall **not** implement an Accessibility Service.

**FR-219 [SHOULD]** A screenshot-folder watcher may be offered as an opt-in setting, disabled by default. If implemented, media permission usage must satisfy Google Play's photo and video permissions policy; if that cannot be assured, this requirement shall be dropped.

## 5.3 Windows capture (FR-300 series)

**FR-301 [MUST]** The application shall run as a system tray application with optional launch at sign-in.

**FR-302 [MUST]** A configurable global hotkey (default `Ctrl+Shift+K`) shall be registered via `RegisterHotKey`. On activation the app shall synthesise a copy command, read the clipboard, and present the capture popup.

**FR-303 [MUST]** Where the clipboard contains an image, on-device OCR (`Windows.Media.Ocr` or equivalent) shall extract text. This is the supported path for region snips.

**FR-304 [MUST]** The capture popup shall appear near the cursor, be dismissible with `Esc`, and be fully keyboard-operable.

**FR-305 [MUST]** The app shall be distributed through the Microsoft Store as an MSIX package. Rationale: Store distribution provides code signing, avoiding both an annual certificate cost and SmartScreen warnings.

**FR-306 [SHOULD]** The app shall register as a Windows share target.

**FR-307 [LATER]** Outlook desktop add-in.

## 5.4 Browser extension (FR-400 series)

**FR-401 [MUST]** A Manifest V3 extension for Chrome and Edge shall add a context-menu item on text selection.

**FR-402 [MUST]** The extension shall capture the page title and URL alongside the selected text and store the URL in the created item for traceability.

**FR-403 [MUST]** The extension shall function on webmail (Gmail web, Outlook web) without special handling.

## 5.5 Parsing and classification (FR-500 series)

**FR-501 [MUST]** All parsing shall be performed on device. No cloud or LLM service shall be called.

**FR-502 [MUST]** The parser shall extract, where present: title, date, time, end time or duration, location, and recurrence.

**FR-503 [MUST]** The parser shall support, at minimum:

- Numeric formats `DD/MM/YY`, `DD-MM-YYYY`, `DD.MM.YY`
- Written formats "12 September", "12th Sept", "Sept 12"
- Relative references "today", "tomorrow", "day after tomorrow", "next Monday", "this Friday", "next week"
- Times "11 AM", "11:00", "1130 hrs", "3.30 pm", "11 baje"
- Hinglish and common vernacular relatives: "kal", "parso", "agle Monday", "3 tarikh"
- Durations and offsets: "in 3 days", "within 2 weeks", "3 working days before"

**FR-504 [MUST]** Date-order ambiguity (`05/09`) shall be resolved using a user-visible setting defaulting to `DD/MM` for Indian locales. The resolved interpretation shall be displayed to the user before saving.

**FR-505 [MUST]** The parser shall assign a confidence value to each extracted field.

> **How several dates in one capture are ranked, recorded because the rule is a reading and not a mechanism.** FR-511 requires every date to be offered; nothing in this specification said which of them the confirmation screen should **open on**, and until now that was decided by position alone — the first date mentioned won. That is wrong often enough to have been reported: a capture reading "from August 31 to September 6, 2026" followed by "Date: Monday, August 31, 2026 at 21:30" opened on 6 September with no time, and would have been saved against a date the writer never committed to.
>
> The primary candidate shall be chosen as follows, each key settling the matter before the next is consulted.
>
> 1. **A date carrying a time outranks one that does not.** A writer who gave a clock time has said the most about the commitment, and under FR-506 it is the difference between an event and a task with a due date.
> 2. **An explicitly written date outranks one the app calculated.** "August 31" is a statement; "Monday", "tomorrow" and "in 3 days" are arithmetic this app performed. Design principle 1 forbids inventing a date, and letting the app's own inference outrank the user's words is the same instinct one step further on.
> 3. **Otherwise the earliest mention wins**, which is the previous behaviour kept as the tiebreak.
>
> **The confidence this requirement defines is deliberately not one of those keys**, and that is worth recording because it was the obvious third key and it is wrong. A year is what separates `CERTAIN` from `HIGH`, and a date range is written with its year once, at the end — "from August 31 to September 6, 2026". Only the closing date carries one, so ranking on confidence makes the **end** of every such range the primary: the same defect this reading exists to fix, arrived at down a different road. Placed below position it can never fire at all, since no two candidates share a first mention, so it is left out rather than kept as a key that decides nothing.
>
> Ordering of the **list** is unchanged: `candidates` stays in document order, so FR-511's checkboxes will read in the order the capture reads. Only which one is primary is decided here.

**FR-506 [MUST]** Classification rules:

| Extracted | Item type | Behaviour |
|---|---|---|
| Date **and** time | **Event** | Fields pre-filled; one action to save |
| Date only | **Task** with due date | Saved as task |
| Time only, or ambiguous relative reference | **Event**, incomplete | Date picker opens automatically with suggestion chips |
| Neither | **Task**, undated | Routed to Capture Inbox |

**FR-507 [MUST]** The user shall be able to override the Event/Task classification with a single control before saving.

**FR-508 [MUST]** The item type shall be displayed as a visible badge (EVENT / TASK) at all times in the confirmation UI, including for each item in a chain.

**FR-509 [MUST]** Where no date is found, the title shall be derived as follows: use the selection verbatim if under 60 characters; otherwise take the first clause up to sentence punctuation, capped at 50 characters; strip leading salutations and trailing sign-offs.

**FR-510 [MUST]** Where the only date found is in the past, the app shall **not** create a dated item. It shall offer instead to create a follow-up, with the past date recorded in the notes.

**FR-511 [MUST]** The app shall detect multiple dates in a single capture and present them as a multi-select list with per-item checkboxes, defaulting to all selected.

**FR-512 [MUST]** Where confidence is below a configurable threshold, the item shall be routed to the Capture Inbox rather than saved directly.

> **Interim reading, in force only until the FR-700 Capture Inbox exists.** The Inbox is not built, so there is nowhere to route to. Until it is, **a user-confirmed item is saved whatever its confidence**, and the confidence is surfaced on the confirmation screen instead of blocking the save.
>
> The alternative — refusing to save anything below the threshold — would honour the sentence and lose the capture entirely, since the Inbox that was supposed to catch it does not exist. That is worse than saving something the user has read and chosen to keep, and it would also make an undated capture unsaveable, which design principle 1 contradicts: "If no date is found, the item is created undated."
>
> **This reading is superseded the moment the FR-700 series lands.** It is not a reinterpretation of FR-512 and does not weaken it: once there is an Inbox, below-threshold items go to it as written, and the confirmation screen stops being the only thing standing between a doubtful parse and the user's calendar.

**FR-513 [MUST]** Where a date is written without a year, the parser shall resolve it to the current year and shall **not** advance it to the next year. A date that then lies in the past is reported as past and handled under FR-510.

> **Rationale, because the alternative looks more helpful and is wrong.** AC-04 requires "the order dated 12 March" to produce no dated item. Rolling a past date forward to its next occurrence would make that date valid and future, and the acceptance test would pass while the product did the opposite of what it promises.

Where the day of the month alone is given ("3 tarikh"), the month may be inferred: the current month, or the next month where that day has already passed. This is a narrower inference than a year roll — the writer has named no month at all — and the resolved date shall be displayed before saving.

**FR-514 [MUST]** Where a Hinglish term is ambiguous, the parser shall resolve it to the forward-in-time reading and shall assign it a confidence strictly lower than an equivalent unambiguous match, so that a deployment may set the FR-512 threshold to route such captures to the Capture Inbox for confirmation. The resolved value shall in all cases be displayed before saving.

| Term | Ambiguity | Resolution |
|---|---|---|
| `kal` | Means both yesterday and tomorrow; disambiguated in speech by verb tense, which the parser does not model | Tomorrow (AC-13) |
| `N baje` | Carries no meridiem | Hours 1–7 resolve to the afternoon (13:00–19:00); hours 8–12 as written. AC-13 fixes "4 baje" at 16:00; FR-503's "11 baje" resolves to 11:00 |

The reduced confidence is the requirement, not a detail of it: these readings are correct more often than not, which is exactly why they must not be silent.

**FR-515 [MUST]** The parser shall receive the reference instant, the time zone and the date-order preference as explicit inputs, and shall not read the system clock. Each parse shall be a pure function of the input text and that context.

> **Rationale.** NFR-502's corpus cannot hold a parser that reads the clock: every relative expectation would drift daily and the suite would fail on its own without a code change. AC-13 and AC-14 are only testable against a fixed reference instant.

**FR-516 [MUST]** Date and time arithmetic shall use `java.time`. The Android minimum SDK shall be 26 or higher, at which `java.time` is available natively with no core library desugaring and behaves identically on the JVM. No third-party date library shall be added (NFR-501).

> **Consequence worth stating:** the parsing and recipe modules compile and run unchanged under desktop JUnit and on the device, which is what makes the NFR-502 corpus cheap enough to keep growing.

## 5.6 Recipes and derived items (FR-600 series)

**FR-601 [MUST]** A Recipe shall expand one captured date into a chain of related items, each with its own type, title template and date offset.

**FR-602 [MUST]** The app shall ship with at least eight built-in recipes covering: meeting with preparation, appointment, school or class event, travel booking, payment due, renewal or subscription, exam or interview, and delivery.

**FR-603 [MUST]** Users shall be able to create, edit, duplicate and delete their own recipes.

**FR-604 [MUST]** Offsets shall support both calendar days and **working days**.

**FR-605 [MUST]** Working-day arithmetic shall use a configurable working week and a holiday list. The app shall ship with an Indian public holiday list and permit user additions and removals.

**FR-606 [MUST]** Where a working-day calculation has skipped non-working days, the UI shall say so explicitly (for example, "weekend skipped — 3 working days").

**FR-607 [MUST]** All items in a chain shall be written to the same calendar so that the chain can be hidden or removed as a unit.

**FR-608 [MUST]** The user shall be able to deselect individual items in a chain before saving.

## 5.7 Capture Inbox (FR-700 series)

**FR-701 [MUST]** The app shall maintain a local Capture Inbox holding items that are undated, incomplete, or below the confidence threshold.

**FR-702 [MUST]** Inbox items shall be triageable: assign a date, edit, save, snooze, or discard.

**FR-703 [MUST]** Inbox contents shall be stored locally only and shall not be written to the user's Google account until confirmed.

**FR-704 [MUST]** The app shall display an unobtrusive count of pending inbox items and shall not nag.

**FR-705 [SHOULD]** Inbox items older than a configurable period shall be surfaced for review rather than deleted automatically.

## 5.8 Writing to Google (FR-800 series)

**FR-801 [MUST]** Events shall be created via the Google Calendar API. Tasks shall be created via the Google Tasks API.

**FR-802 [MUST]** Each created item shall carry, in `extendedProperties.private` (events) or notes (tasks), the metadata specified in **§7.2**, which governs its keys, values, encoding and normalisation. In summary: a hash of the source text, a date-independent item key, the capture timestamp, a chain identifier, and — where known — the source application identifier and the recipe applied.

**FR-803 [MUST]** Before writing, the app shall check for an existing item with a matching source hash and shall not create a duplicate.

> **How this requirement is met, and where it is bounded — recorded because the two transports are not equally capable.**
>
> **Events are exact.** `events.list` accepts a `privateExtendedProperty=latch.source_hash=<hash>` constraint, so Google performs the match and one request answers the question however large the calendar is. Deleted events are excluded by the API's own default, which is the behaviour this requirement wants: an event the user undid under FR-807 must not prevent them capturing it again.
>
> **Tasks are a bounded scan, and this is a real limitation.** The Google Tasks API has **no filter on content of any kind** — only due, completion and update dates — `maxResults` caps at 100, and §7.2 metadata lives in free-text notes. There is therefore nothing for Google to match on and the client must read and parse tasks itself.
>
> Where the item has a due date the scan is bounded to **D−1 to D+1**. A duplicate of the same source text parses to the same due date, so a single day would suffice for correctness on one device; the window is a day wider either side **to absorb time-zone boundary differences between two devices**. A task due "5 September" written from a phone in IST and searched for from a PC in UTC is precisely where AC-07 fails silently otherwise, and the failure would look like the feature simply not working rather than like a boundary error.
>
> The scan sets `showCompleted` and `showHidden`, because a duplicate the user has already ticked off still exists and this requirement asks whether the message was saved, not whether it is outstanding. It leaves `showDeleted` at false, matching events.
>
> **An undated task has nothing to bound the scan by**, and falls back to reading pages until it runs out or reaches a cap of ten. Beyond that the search gives up and **reports that it did so** rather than returning "no duplicate found" — the caller is told the difference between having looked everywhere and having stopped looking. A user with more than roughly a thousand undated tasks in one list may therefore have a duplicate created. That is accepted for v1.0. The cure is a local index of source hashes maintained by an incremental `updatedMin` sync, which is deferred because it needs local storage that does not yet exist; it should be revisited when the Capture Inbox (FR-701) brings that storage with it.

**FR-804 [MUST]** Where a capture appears to be a rescheduling of an existing item (matching title and identifiers, different date), the app shall offer to **update** the existing item and its chain rather than create a new one. The user shall confirm; the app shall not update silently.

> **How this requirement is to be met, and where it is bounded.**
>
> **Detection runs after FR-803 and before the insert.** A matching `source_hash` is a duplicate and settles the matter without the key being consulted, per §7.2's table; only a miss proceeds to query `latch.item_key`. A match on the key with a different resolved date is a reschedule. Nothing has been written when the question is asked.
>
> **Events are exact.** `events.list` with `privateExtendedProperty=latch.item_key=<key>` is the same server-side mechanism FR-803 uses, and deleted events are excluded by the API's own default.
>
> **Tasks are a capped scan, and FR-803's bound does not carry over.** FR-803 can bound its task scan to D−1..D+1 because a duplicate has the *same* due date. A reschedule has a **different** date by definition — that is the whole premise of this requirement — so there is nothing to bound by, and the item_key search is the ten-page capped scan in every case rather than only for undated tasks. Where it caps without a match the result **shall not** be read as "no reschedule": it falls through to an ordinary create, exactly as `scanCapped` is forbidden from meaning "no duplicate". A user with a large task list may therefore be offered a create where an update was available. The cure is the same local index deferred under FR-803, and it should arrive with FR-701's storage.
>
> **Where several items share a key**, which happens once a user has answered "create new" to an earlier offer, the offer is made against the item with the **latest** start or due date. That is the one a human means by "the meeting", the others being positions it has already left. Left to the API's own ordering this would be arbitrary and would vary between the two transports.
>
> **What the user is offered.** The screen presents the existing item and both dates, and two explicit actions — update the existing item, or create a new one — with no default, no timeout and no action taken until one is chosen. Weekday names shown alongside dates shall be **computed from the resolved date** and never carried over from the captured text, which may state a weekday that contradicts the date it is written beside (§7.2 requires such a contradiction to be absorbed, not acted on). Illustrating with the dates used in this document's own examples: an item standing at **Tuesday 5 March 2030, 21:30** and a capture resolving to **Thursday 7 March 2030, 21:30** — 5 March 2030 is a Tuesday and 7 March 2030 is a Thursday.
>
> **An update modifies the item's date fields and nothing else.** The permitted set is exhaustive: for an event `start`, `end`, `allDay` and the IANA time zone those resolve against; for a task `due`. The title is not modified, because it is the identity the match was made on. The description and notes are not modified either, and that is a deliberate choice rather than an omission: on tasks the §7.2 metadata *lives in the notes*, so rewriting them would breach the write-once invariant this specification states in §7.2, and having the two transports differ on which fields an update touches would be worse than the residual. The residual is that an updated item keeps the FR-805 source text of the capture that created it, not of the capture that moved it. FR-805 is satisfied — source text is present — and the capture that moved it is represented by the new date, which is the thing the user asked to change. **An updated item's description therefore continues to show the originating capture's text, including the old date and weekday it names, and this is deliberate: the description is provenance — where this item came from — and not a statement of the item's current state, which its date fields carry.** It is recorded here so that it is recognised as the intended behaviour rather than reported later as a defect.
>
> **An item whose current dates cannot be read is not a match, and the user is offered an ordinary create.** The values FR-807 must write back are the ones the item held *before* this app changed them, and after the patch they exist nowhere but in what the search read — so a match whose prior state could not be captured would be an update that could not be undone. Refusing it costs an offer; accepting it would cost the undo, which is the worse trade. No message is shown, because the app cannot distinguish this from there being no prior item at all. The realistic cause is not a malformed API response but **the user's own editing in Google Calendar**, which is where an item Latch wrote is most likely to be changed by hand.
>
> **A matched event that has become recurring shall be treated as no match, for a different and stronger reason.** Its dates read back perfectly well, so nothing in the paragraph above would stop it; the objection is to the patch. `events.list` is not asked for `singleEvents`, so a series is returned as its master, and `events.patch` on a master moves **every** occurrence. FR-804 offers to move one meeting, and silently moving a weekly stand-up for the rest of time is not a larger version of that but a different act — one this specification has never authorised and that undo would have to reverse across occurrences it never recorded. A user who turns a Latch-written meeting into a repeating one has taken it out of this feature's scope, and a create is the honest answer. **The refusal shall be explicit** — a test on the recurrence fields, not a parse that happens to fail — and shall cover the instance form as well as the master, so that later asking the query for `singleEvents` cannot quietly reopen it.
>
> **The prior state is read when the match is made, not when the undo is taken, and where a write is queued the two can be far apart.** An `UPDATE` that fails transiently sits in the queue until it drains, so an undo taken after a delayed drain writes back what the item held at match time — overwriting, without warning, any hand edit the user made to that item in between. The window is bounded by FR-806's retry schedule rather than by FR-807's ten seconds, and it is accepted for v1.0: the alternative is re-reading the item at drain, which turns an undo into a second query that can itself fail, and reads a state the user has not been shown. It is recorded so that it is recognised rather than diagnosed.
>
> **The matched item is updated, not its chain — a narrowing, recorded as one.** This requirement says "the existing item and its chain". An `item_key` is per-item and a recipe's steps carry different titles and therefore different keys, so an item_key query returns one item per chain and never the chain itself; reaching the rest would need a second lookup by `latch.chain_id`, which is exact for events and a give-up-able scan for tasks — **the same asymmetry FR-807 refused to accept for deletes, for the same reason**. Until FR-511 or FR-601 makes a multi-item chain reachable at all, every chain is one item and the distinction has no observable consequence. It is written down because it will acquire one, and the note is what will make it visible then.
>
> **Offline, a reschedule is created rather than detected, and this is a limitation rather than a deferral.** Detection is a query, and there is no network — that is why the entry exists. Repeating the check at drain, as FR-803's is repeated, **cannot** work here: this requirement forbids a silent update, and a drain runs in a worker with no user present, leaving it only the choice between updating without consent and creating anyway. Holding the entry to ask later needs a surface on which to ask, which is the Capture Inbox (FR-700 series) or Settings (FR-1000 series), neither of which exists. An offline reschedule therefore becomes a second item, and the recourse is to remove one by hand. **The Inbox is the cure**, and this should be revisited when FR-701 lands.
>
> **FR-803's duplicate check shall not run when a queued `UPDATE` drains, and its absence there is deliberate.** FR-806 requires that check to be repeated at drain for a `CREATE`, because two offline captures of one message would otherwise become two items. An `UPDATE` is not exposed to that: it writes no new item, and the question FR-803 asks — has this message already been saved — is answered "yes" by the very item the entry is about to move. Running it would find that item by its own `source_hash` and read the answer as a reason to abandon the move, leaving the reschedule silently undone. A drain of an `UPDATE` therefore patches its target directly. This is the one place in the app where an FR-803 check is skipped before a write, and it is skipped because the write is not a creation.
>
> **This requirement detects re-statements, not announcements of change.** The identity is a date-free title, so "Project sync on Tuesday 5 March 2030" and "Project sync on Thursday 7 March 2030" match, while "Project sync **moved to** Thursday 7 March 2030" does not — the added words change the title, the key and therefore the answer. Real reschedule messages are very often phrased the second way. This is inherent to a title-derived identity rather than a defect in it, and it is the reason FR-804 will find fewer reschedules in the field than in a test. It sits beside the residual §7.2 records for an item that has moved more than once.

**FR-805 [MUST]** The item's source text and, where available, a link back to the source (URL, or source app and timestamp) shall be stored in the item description or notes. **Subject to FR-805a.**

**FR-805a [MUST]** Where a capture originated from the notification listener (FR-208), the item's source text **shall not** be stored in the item description, notes or extended properties. FR-805 is satisfied for that layer by the source link alone — the source application and the capture timestamp, which the item already carries under §7.2.

> Rationale. A Google item is persistent storage (see the reading recorded against NFR-206), and NFR-206 forbids notification content reaching it. This is the same reasoning that suppresses webhook delivery for this layer under FR-210a, and it applies with more force here: a webhook is an endpoint the user chose, whereas the calendar entry is written by default and syncs to every device on the account. The exclusion shall be stated in the notification-access disclosure screen alongside FR-210a's.

**FR-806 [MUST]** All writes shall be queued locally when offline and retried on reconnection, with the queue visible to the user.

> **How this requirement is met, and where it is bounded.**
>
> **The queue is a fallback, not the path.** A save still attempts the write directly and enqueues only where the failure is one that waiting can fix — which is what "queued locally **when offline**" says. Routing every save through the queue would have been simpler and would have cost FR-803 its immediate answer: "Already saved. Nothing was written again." is a synchronous reply today, and AC-07 depends on the user seeing it. A failure that waiting cannot fix — a 400, a 403 for a scope the user has not granted — is still reported under NFR-303 rather than queued, because an entry that can never drain would sit in the user's count for ever with nothing said about why.
>
> **The payload is not in WorkManager.** WorkManager keeps its own SQLite database and it is not encrypted; its input data is also capped at roughly 10 KB, which a capture can exceed. A queue entry contains the user's own text, so entries live in an app-private store, one record per entry, encrypted under an Android Keystore key — the mechanism NFR-203's reading says to reuse rather than introduce a second scheme — and the worker carries no payload at all. This also keeps `androidx.room` deferred: nothing here needs a query language, only "read them all, oldest first".
>
> **FR-803 runs a second time, at drain.** The check the saver would have run could not run at all; there was no network, which is why the entry exists. Without repeating it per entry immediately before the insert, capturing the same message twice offline produces two items on reconnection — AC-07 failing inside precisely the situation AC-10 creates. The worker, not the saver, is therefore the last line for FR-803.
>
> **A queued entry is not drained inside its FR-807 undo window.** Undo of a queued save means dropping the entry; undo of a written one means deleting it from the account. If a drain could happen inside the ten seconds, an undo would sometimes be one and sometimes the other, and the losing side of that race is a delete the user did not watch happen. Skipping entries younger than the undo window removes the race instead of trying to win it, at a cost of at most ten seconds on a write that is already late.
>
> **Two limits, stated rather than discovered.** A Keystore key does not survive transfer to another device, so a queued capture is unreadable after a restore and is dropped as absent — which here means the capture is lost. NFR-302 names network failure, app termination and device restart, and the key survives all three, so this is inside the requirement; it is nonetheless the one way a queued capture can disappear. And an entry that has been given up on **stays in the queue and is shown as stuck, but has no manual retry or dismissal**, because the screen that would offer one is Settings (FR-1000) and that is not built. The recourse today is to capture the text again.
>
> **AC-10 passes**, verified on a device on 27 Aug 2026: a capture made in aeroplane mode was held locally, shown as pending, and written to Google Calendar on reconnection. NFR-302's other two limbs — app termination and device restart while queued — are what WorkManager is here for and have not been watched.
>
> **AC-21's webhook half remains unmet**, and not for want of the queue: FR-1004 webhooks do not exist. What the queue can already guarantee is the part FR-1004b names — a webhook cannot travel through it, structurally rather than by remembering to check, because every request a drain makes goes through the same `ALLOWED_HOSTS` guard that AC-17 rests on and would be refused for any non-Google host.

**FR-807 [MUST]** Every save shall offer an undo for a period of not less than 10 seconds, removing all items created by that save.

> **How this requirement is met, and where it is bounded.**
>
> A save now offers an undo for ten seconds, and taking it deletes every item that save created — `events.delete` for events, `tasks.delete` for tasks. Both are treated as idempotent: an item already gone (404, or 410 for one deleted since) is a **success**, because the requirement asks for it not to be in the account and it is not. Reporting a failure there would tell the user their item survived an undo when it did not, which is the more damaging of the two possible lies. A delete that genuinely fails is reported under NFR-303, along with how many of the chain were removed, because the recourse is to go and remove the rest in Google by hand.
>
> **Undo deletes the ids the save recorded, not the result of a `latch.chain_id` query.** The chain id is what makes these items a group (§2.4) and is written on every one of them, and for events a `privateExtendedProperty` query on it would work. For tasks it cannot: the Tasks API has no content filter of any kind — the limitation recorded against FR-803 — so a task chain could only be recovered by a page scan, and an undated one by a scan that is allowed to give up. Undo would then be exact for events and best-effort for tasks, which is the wrong shape for a destructive operation. Inside the window, in the process that did the writing, what was created is known exactly and the delete is exact on both transports.
>
> Three consequences follow, and they are limits of this design rather than defects in it. **The offer does not survive the process**: it is held in memory by `CaptureSaver`, so a process death inside the ten seconds loses it and the save stands. Persisting it needs local storage this app does not yet have — the same storage FR-701 will bring. **A new capture ends the offer**, because the countdown belonged to a screen that no longer exists. And **undo from another device is a different feature**, which would have to go by chain id and would therefore be available for events only.
>
> **The ten seconds are protected from the capture window itself.** The confirmation screen is a floating dialog with `windowCloseOnTouchOutside` set, so a stray tap outside it finishes the activity; an offer that lived only on that screen could be gone in well under a second through no deliberate act of the user, and the requirement would be met on paper and not in the hand. The dismissal is therefore suppressed while the offer stands and restored when it lapses, at which point the window closes itself. The Close button and the back gesture still work throughout — those are the user declining the offer, which is a different thing from brushing it away.
>
> **Re-capturing after an undo works**, and needs no special handling: FR-803's event query excludes deleted events by the API's own default, and the task scan leaves `showDeleted` at false. An item the user undid does not prevent them capturing it again.
>
> **Undo of an FR-804 update is a restore, not a delete, and this requirement's wording did not cover it.** "Removing all items created by that save" describes a create; an update creates nothing, and deleting the item it moved would destroy an item the user had before this app touched it — the one outcome an undo must never produce. Undoing an update shall therefore write back the previous values of exactly the fields FR-804 permits an update to modify, and nothing else. The §7.2 metadata is not among them and is not touched, per the write-once invariant, so an undo restores dates alone.
>
> This is idempotent in the same way and for the same reason as the deletes: an item already carrying the previous values is a success. It fails only where the write fails, and reports under NFR-303 as the deletes do. The ten-second window, the loss of the offer on process death, and the ending of the offer by a new capture apply unchanged — an update is a save like any other.
>
> **It follows that the undo of a save is not one operation but one of three**, chosen by what the save did: delete what was created, drop what was queued, or restore what was updated. §7.1's queue entry carries what each needs.
>
> Four other requirements remain unmet, because the UI each needs is not built. **FR-506 row 3**: a capture with a time but no date cannot be saved at all, because completing it needs the date picker that row describes. **FR-511**: only the first date of a multi-date capture is saved; the others are counted on screen and wait for the per-date checkboxes. That is also why a chain is one item on every path reachable today — the removal loop is written for a chain of any size and reports how far it got, but the multi-item case only becomes reachable with FR-511. **FR-507**'s Event/Task override is likewise absent, so the parser's classification stands. **FR-510**'s past-date follow-up is not offered; a past date is saved as read.

## 5.9 Calendar selection and routing (FR-900 series)

**FR-901 [MUST]** The app shall retrieve calendars via `calendarList.list` and shall present only those with `accessRole` of `owner` or `writer`. Read-only calendars (Holidays, Birthdays, subscribed calendars) shall be excluded from selection.

**FR-902 [MUST]** Each calendar in the picker shall display its own `backgroundColor`.

**FR-903 [MUST]** The app shall read the `selected` property of each calendar. If the user chooses a calendar that is currently unticked in their Google Calendar view, the app shall display a "hidden" indicator and offer to make it visible via `calendarList.patch`. Rationale: writing to a hidden calendar produces an invisible item and is indistinguishable from failure.

**FR-904 [MUST]** The destination calendar shall be shown in the confirmation UI as a chip with the calendar's colour, changeable in one action.

**FR-905 [MUST]** Under Option A, routing rules shall be definable by source application and by recipe.

**FR-906 [MUST]** The app shall never route silently to a calendar the user has not seen in the confirmation UI.

**FR-907 [SHOULD]** Where the user overrides the destination for the same source three times, the app shall offer to make that a rule. It shall not create the rule automatically.

**FR-908 [MUST]** The calendar list shall be refreshed on launch. If a stored destination is missing or has lost write access, the app shall fall back to the primary calendar and inform the user.

**FR-909 [SHOULD]** Under Option A, a per-source event `colorId` may be applied for visual separation within a single calendar.

## 5.10 Settings (FR-1000 series)

**FR-1001 [MUST]** Settings shall expose: destination calendar; task list; routing mode (Option A or B); routing rules; default reminder lead times; default event duration; working week; holiday list; date-order preference; time zone; confidence threshold behaviour; per-layer capture toggles; and the outbound webhook endpoint (FR-1004), which shall appear as an advanced setting, empty by default.

**FR-1002 [MUST]** Switching between Option A and Option B shall not move, alter or delete anything already saved, and the UI shall state this.

**FR-1003 [MUST]** Capture layers shall default as follows: text selection ON, share sheet ON, Quick Settings tile ON, notification listener OFF, screenshot watcher OFF.

**FR-1004 [SHOULD]** The app shall provide the **capability** for a user to configure an outbound webhook that fires a JSON payload of the parsed item on save. The feature shall be **disabled by default**, shall require the user to enter the endpoint URL explicitly, and shall display a warning at the point of configuration stating that captured content will be sent to that endpoint. The request is made by the client directly; no publisher infrastructure is involved.

**FR-1004a [MUST, if FR-1004 ships]** The webhook payload shall contain only: the parsed item fields (type, title, start, end, due date, location, notes), the recipe applied, the chain identifier, the source application identifier, and the capture timestamp. It shall **never** contain raw image bytes or the contents of a captured file. Where a capture was OCR-derived, the extracted text may be included via the item's notes field; the source image never is.

**FR-1004b [MUST, if FR-1004 ships]** Webhook delivery shall be **best-effort and strictly secondary to the Google write**. It shall not be placed in the FR-806 write queue, shall not be retried beyond a single immediate attempt, shall never block or delay the Google write, and shall never block FR-807 undo. A delivery failure shall be reported passively in the Settings screen and shall not raise a blocking error. For the avoidance of doubt under NFR-303, a failed webhook delivery is **not** a failed API write.

> **Consequence, stated explicitly so it is not mistaken for a defect.** Where a capture is saved while offline, the Google write is queued under FR-806 and completes on reconnection, but **no webhook is sent for that item** — the single delivery attempt happened at save time and failed. This follows deliberately from best-effort delivery and from the exclusion of webhooks from the write queue. It shall be stated on the webhook configuration screen alongside the FR-1004 warning.

> **Note on classification.** The capability is [SHOULD] for v1.0. The feature being *enabled* is never a default. This distinction is what AC-17 and AC-18 test separately. FR-1004a, FR-1004b and FR-210a bind whenever the capability ships.

**FR-1005 [MUST]** The app shall provide `.ics` export for any item or chain.

**FR-1006 [LATER]** Cross-device settings synchronisation via Google Drive `appDataFolder`. Deferred because it adds an OAuth scope; not required for v1.0, as calendar data already syncs through Google.

---

# 6. Non-functional requirements

## 6.1 Performance

**NFR-101 [MUST]** Time from capture gesture to confirmation UI displayed: under 800 ms for text, under 2.5 s for OCR of a full-screen image, on a mid-range device (approximately Snapdragon 6-series, 4 GB RAM).

**NFR-102 [MUST]** The confirmation UI shall render before parsing completes if necessary, filling fields progressively, rather than delaying display.

**NFR-103 [MUST]** Android APK size under 40 MB. Windows MSIX under 80 MB.

**NFR-104 [MUST]** No persistent background service on Android other than the optional notification listener.

## 6.2 Privacy and security

**NFR-201 [MUST]** No captured text or image shall be transmitted to any endpoint other than Google's APIs — and then only as the content of a created calendar event or task — with the single exception of an endpoint the user has explicitly configured under FR-1004. In the app's default configuration no such endpoint exists, and no traffic leaves the device except to Google.

**NFR-202 [MUST]** No analytics SDK shall collect message content, image content, or parsed values. Crash reporting shall be limited to stack traces with no user content, and shall be opt-out.

**NFR-203 [MUST]** OAuth tokens shall be stored in the platform secure store (Android Keystore / Windows DPAPI or Credential Manager), never in plain preferences. The FR-1004 webhook endpoint shall be stored the same way and treated as a secret, because such URLs commonly embed a bearer token in the path or query string. It shall be masked in the Settings UI once saved.

> **How this requirement is read on Android, recorded so the decision is visible rather than implied.** "The platform secure store" is satisfied by using the Android Keystore **directly**, and **no encryption library is required for v1.0**. The mechanism is an AES-256-GCM key generated in the Keystore under a fixed alias, where the key material is never readable by the app process — only usable through it — with a per-write initialisation vector stored alongside the ciphertext in app-private preferences. It is written out in `data/src/main/kotlin/com/latch/data/EncryptedPreferences.kt` and is approximately fifty lines using only `javax.crypto` and `android.security.keystore`.
>
> `androidx.security:security-crypto` was the alternative and was rejected on NFR-501 grounds. It was deprecated in 2025 in favour of the platform APIs it wraps, so adopting it would mean taking on a maintenance obligation (FR-1107) to a library already being withdrawn, in exchange for code the app can own outright. The decision and its measurement are recorded in `docs/DEPENDENCIES.md`.
>
> One consequence is worth stating because it is a behaviour, not an implementation detail. A Keystore key does not survive transfer to another device, and the app disables backup and device transfer in any case (see NFR-204 below). Anything encrypted under it is therefore unreadable after a restore, and shall be treated as absent rather than as an error — for stored account defaults this means first-run setup runs again (FR-101), which is the only recovery that does not invent a configuration. **This is the correct behaviour for secrets and shall not be worked around** by moving key material somewhere it would survive a device transfer.
>
> The same mechanism is intended for the secret store when it is built: OAuth tokens and the FR-1004 webhook endpoint shall reuse it rather than introduce a second scheme.

**NFR-204 [MUST]** The Capture Inbox database shall be stored in app-private storage and encrypted at rest.

> **How this requirement is read, recorded so the decision is visible rather than implied.** At the minimum SDK fixed by FR-516, every supported device encrypts app-private storage at the platform level, and the app disables cloud backup and device transfer for its data. Encryption at rest is therefore satisfied by the platform, and **no application-level database encryption library is required for v1.0**. Adding one (SQLCipher or equivalent) was measured at approximately 2 MB per device and was rejected on NFR-501 grounds.
>
> The residual gap is deliberate and narrow: platform encryption does not protect the database from an attacker with root access on an unlocked device. **This reading shall be revisited if the threat model ever includes a rooted device**, at which point application-level encryption becomes the answer, along with the key-management question it brings — the passphrase would have to live in the same secure store as the OAuth tokens under NFR-203.

**NFR-205 [MUST]** The app shall provide a single action to revoke access and delete all local data.

**NFR-206 [MUST]** Notification content accessed under FR-208 shall never be written to persistent storage.

> **How this requirement is read, recorded so the decision is visible rather than implied.** A calendar event or task in the user's Google account **is** persistent storage for the purposes of this requirement — more so than local disk, since Google retains it, backs it up and synchronises it to every device on the account. NFR-206 therefore reaches the Google write, not only the local database, and this is what resolves its conflict with FR-805.
>
> It does **not** forbid the item. FR-210 says "Only a user-confirmed item is persisted", which sanctions the item itself: a title and a date the user has read and confirmed on screen are the product of the capture, not a copy of the notification. What NFR-206 forbids is storing the notification's content **verbatim**, and the only place FR-805 would have done so is the source text. FR-805a removes it for this layer.
>
> Two consequences are deliberate and narrow, and are recorded rather than left to be discovered. A notification-derived item still carries a **title** derived from the message, because an item without one would not be an item; that is the minimum the feature cannot work without, and it is what the user confirmed. And it still carries `latch.source_hash`, because FR-803 deduplication depends on it and a SHA-256 digest is not the content. Note that a digest of a short message is not beyond a brute-force search of likely messages; the exposure is small and the alternative is that captures from this layer cannot be deduplicated at all, but the trade is stated here rather than assumed.

## 6.3 Reliability

**NFR-301 [MUST]** Capture shall function fully offline; only the write to Google requires connectivity.

**NFR-302 [MUST]** No capture shall be lost due to network failure, app termination or device restart while queued.

**NFR-303 [MUST]** A failed API write shall surface a clear, actionable message. Silent failure is a defect.

## 6.4 Accessibility and localisation

**NFR-401 [MUST]** The app shall meet WCAG 2.1 AA contrast, support system font scaling to 200%, be fully operable by screen reader, and support keyboard-only operation on Windows.

**NFR-402 [MUST]** UI language: English (India) at v1.0, with all strings externalised for translation.

**NFR-403 [SHOULD]** Hindi UI localisation.

**NFR-404 [MUST]** Date parsing shall handle Hinglish input irrespective of UI language (see FR-503).

## 6.5 Maintainability

**NFR-501 [MUST]** Third-party dependencies shall be minimised and each justified in writing. Every dependency is a future maintenance obligation on a product with no revenue.

**NFR-502 [MUST]** The parsing engine shall be a separately testable module with a test corpus of no fewer than 300 real-world input strings and expected outputs.

**NFR-503 [MUST]** The build shall be reproducible from a clean checkout with documented steps.

---

# 7. Data model

## 7.1 Local storage (per client)

| Entity | Key fields |
|---|---|
| `Capture` | id, raw_text, source_app, source_uri, captured_at, ocr_used, confidence, state (inbox / saved / discarded) |
| `Item` | id, capture_id, chain_id, type (event/task), title, start, end, all_day, due_date, location, notes, calendar_id, task_list_id, remote_id, sync_state |
| `Recipe` | id, name, built_in, target_calendar_id, steps[] |
| `RecipeStep` | offset_value, offset_unit (calendar_days / working_days), direction, item_type, title_template, reminder_offsets[] |
| `RoutingRule` | id, match_type (source_app / recipe / keyword), match_value, calendar_id, priority |
| `Holiday` | date, name, source (bundled / user) |
| `WriteQueue` | id, pending_write (item + §7.2 metadata + FR-805 body + time zone + target remote id + prior state), operation, attempts, last_error, given_up, queued_at |

> **`item_id` was wrong and is corrected here.** An `Item` cannot carry a write. §7.2's metadata — the source hash, the item key, the capture time, the source application — is not on it, nor is the FR-805 description, nor the time zone that `start` resolves against. An entry built from an item id alone would write an item with no metadata, which §7.2 records as permanently unmanageable by FR-803, FR-804 and FR-807 afterwards. The queue therefore stores the whole write. `given_up` and `queued_at` are the two fields the implementation added: the first separates "not yet" from "not ever" so the user can be told which they are looking at, and the second is what keeps a drain out of FR-807's undo window.

> **`pending_write` could not express an `UPDATE`, and is corrected here — the same defect as `item_id`, one level down.** `operation` has admitted `CREATE`, `UPDATE` and `DELETE` since this table was written, but `pending_write` held only the state to be written. Two things were missing and neither can be reconstructed later.
>
> **The target.** An update names an item that already exists in the account, and nothing in the entry carried its remote id. An entry that survives a process restart — which is the entire purpose of the queue — would wake with no way to say which item it was to update.
>
> **The prior state**, without which FR-807's restore has nothing to write back. It shall carry exactly the fields FR-804 permits an update to modify and no others: for an event the previous `start`, `end`, `all_day` and time zone; for a task the previous `due`. The bound is deliberate and is what keeps this list from growing without limit. It carries **no previous description or notes**, because an update does not modify them — FR-804 says so, and on tasks the §7.2 metadata lives in the notes, so an update that rewrote them would breach the write-once invariant. It carries **no previous `source_hash` or `item_key`**, because §7.2 metadata is written once at insert and no operation modifies it, so there is never a prior value to restore.
>
> Both fields are meaningful only for `UPDATE`. A `CREATE` has no target and no prior state, and shall carry neither rather than carrying them empty — the same rule §7.2 applies to its own optional keys, and for the same reason: an empty value cannot be told apart from a value that is genuinely empty.

## 7.2 Remote metadata

Written to `extendedProperties.private` on events, and appended to notes on tasks.

> **This section is a cross-client wire contract and is normative.** §4.1 has three clients writing to one Google account with no backend, so the remote item *is* the shared state — there is no other channel through which clients can agree (FR-1006, which would have added one, is [LATER]). AC-07 requires a message captured on the phone to be recognised as a duplicate by the PC, which means both clients must derive a **byte-identical** `latch.source_hash` from the same text, each having been written independently in a different language. Every value below is therefore specified exactly rather than left to the implementer.
>
> **It also cannot be corrected later.** Items already written into a user's account cannot be rewritten, and FR-803, FR-804 and FR-807 all read these keys back. An item written without a key is permanently unmanageable by the feature that would have used it. This section must therefore be settled before the first item is written, not after.

| Key | Required | Value |
|---|---|---|
| `latch.version` | yes | `1` |
| `latch.source_hash` | yes | 64 lowercase hex characters — SHA-256 of the normalised **whole capture text** (FR-803) |
| `latch.item_key` | yes | 64 lowercase hex characters — SHA-256 of the normalised **date-free title**, defined below (FR-804) |
| `latch.chain_id` | yes | Lowercase UUID, e.g. `3f2504e0-4f89-41d3-9a0c-0305e82c3301` |
| `latch.captured_at` | yes | RFC 3339, UTC, whole seconds — `2026-08-26T14:03:22Z` |
| `latch.source_app` | no | `android:<package>`, `windows:<executable>` or `web:<host>` |
| `latch.recipe` | no | A `Recipe.id`, e.g. `builtin.meeting_prep` |

An optional key that has no value shall be **omitted**, never written empty. An empty value cannot be distinguished from a value that is genuinely the empty string, and a reader cannot then tell "unknown" from "known to be nothing".

**`latch.source_hash` covers the whole capture, not the individual item.** Every item produced by one capture therefore carries the same value, and FR-803's question is "has this message already been saved", not "has this item already been created".

**`latch.item_key` exists because FR-803's hash cannot satisfy FR-804.** A rescheduled message has different source text, so its `source_hash` differs and the item being rescheduled can never be found by it. `item_key` is the identity that survives a date change: the **same `item_key` with a different `source_hash` is a reschedule**, the same `source_hash` is a duplicate, and neither is a new item. It must not incorporate `latch.source_app`, which is optional and may begin being populated part-way through the product's life, giving the same meeting different keys either side of that change.

**"Same key, different hash" assumes a different date, and that assumption is now stated.** A reschedule is a different date; a message restated without one — forwarded, quoted back, sent again with a different greeting — is a different `source_hash` and the same `item_key` while naming the very same date. Reading that as a reschedule would offer to move an item to where it already is. The discrimination is therefore over three values and not two, and a client shall implement exactly this table.

| `latch.source_hash` | `latch.item_key` | Resolved date | Meaning |
|---|---|---|---|
| matches | not consulted | not consulted | **Duplicate** (FR-803). Nothing is written and no reschedule is offered. The key is not consulted at all, so a duplicate can never present as a reschedule. |
| differs | matches | **differs** | **Reschedule** (FR-804). Offer to update; never silent. |
| differs | matches | **same** | **Duplicate.** Nothing is written. There is nothing to move, and offering to move an item onto its own date is the app misreading a restatement as a change. |
| differs | differs | either | **New item.** |

**"Same" means an update would change nothing**, and the comparison shall be over **exactly the fields §5.8 permits an update to modify** — for an event its start, end and all-day flag; for a task its due date. That is the precise form of the loose phrase "resolved date", and it is precise in a direction that matters: **a change of time on the same day is a reschedule and shall be offered as one**, because an update would move something. Reading the row as a comparison of calendar dates alone would refuse to move a meeting from 21:30 to 22:30, which is a reschedule by any ordinary meaning of the word and one the user would have to perform by hand.

What is compared is what the parser **resolved**, not the text that produced it: "5 March 2030" and "next Tuesday" landing on the same moment are the same for this purpose.

**The time zone is excluded from that comparison**, and this is a rule rather than an oversight. Google returns no zone for an event that uses its calendar's default, so an item written under the device zone and read back without one would compare as changed while being identical — and the app would offer to move it onto the position it already holds, which is the exact misreading this row exists to prevent. Nothing on this path reschedules across zones in any case: the zone comes from the device, never from the captured text. A client that starts comparing it will produce spurious offers rather than better ones.

**§7.2 metadata is written exactly once, at insert, and no later operation modifies it.** Not an FR-804 update, not an FR-807 undo, not a repair, not a migration. This single rule carries three separate consequences that would otherwise each need their own clause and their own chance to be forgotten.

1. **An updated item keeps the `source_hash` of the capture that created it.** The original message therefore stays protected by FR-803 for the life of the item, and re-capturing it is answered as a duplicate rather than offered as a reschedule back to where it started. The reschedule text that moved it is not itself recorded as a hash; re-capturing *that* text lands on the third row of the table above — same key, same resolved date — and is likewise a duplicate. The user is not nagged from either direction.
2. **No client may repair, backfill or recompute an `item_key`**, including the pre-v1.14 keys the previous clause declares unmatched. In particular a client shall not derive a second, superseded-derivation key and query for that as a fallback: that would silently restore a wire contract this specification has moved, in the one client currently permitted to move it. Exactly one key is derived, by the derivation above.
3. **Undo of an update never restores metadata**, only the fields §5.8 permits an update to modify. What undo must carry is bounded by that list and by nothing else.

**One residual, recorded rather than left to be found.** An item carries one hash, so after two or more moves the intermediate texts are not represented. Re-capture the text of a superseded reschedule — the one that moved the meeting to a date it has since left — and it is a different hash with the same key and a different resolved date, which is the second row: a reschedule offer, backwards, to a date the user has already moved away from. It is confirmed rather than silent, so the cost is a question the user answers "create new" or closes. Representing every past position would mean a list of hashes, and §7.2 values are single-valued by design. This sits beside FR-804's re-statement limitation as the two ways a title-and-hash identity is thinner than the human notion of "the same meeting".

### The date-free title

`latch.item_key` is hashed from **the item's title with every date and time expression removed**, not from the title as displayed. The distinction is the whole requirement, and getting it wrong is silent: hashing the displayed title makes `item_key` identical to `source_hash` on any capture short enough for FR-509 to take verbatim, both values then move together when the date changes, and FR-804 has nothing left to match on. An implementation that produces `item_key == source_hash` is wrong even though every value is well-formed.

A client shall derive it as follows.

1. Where the item's title came from a subject line (FR-206), use that subject **unchanged**. Date expressions are located by their position in the captured body, and those positions do not apply to a subject. A subject carrying its own date is a known residual case and is accepted: a subject is normally the standing name of the thing, not a statement of when it happens.
2. Otherwise, take the captured text and **blank** every span matched as a date, a time or an end time — replace those characters with spaces rather than deleting them. Deleting shifts every later position and can weld two words together; blanking does neither, and the normalisation collapses the spaces.
3. Apply FR-509's title extraction to the blanked text.
4. Normalise and hash the result exactly as `latch.source_hash` is normalised and hashed.

**A date expression includes a weekday that corroborates it.** In "PTM on Friday 12 September" the whole phrase is one date, and the span removed at step 2 shall cover all of it. Removing only "12 September" leaves "Friday" in the identity, and a reschedule to another weekday moves it — which is precisely the failure `item_key` exists to prevent, so this clause is load-bearing rather than a refinement.

**Which date a weekday corroborates shall be decided by the day it falls on, not by proximity alone.** A weekday is absorbed by an explicit date whose own day of the week it matches; where two such dates are in range, the nearer wins; where none matches, the weekday is a date in its own right and becomes its own FR-511 candidate. "Gym Friday, and the review on 12 September" is two dates and not one phrase.

> **This clause was proximity alone, and proximity alone is wrong in both directions.** It allowed a single weekday to be absorbed into *every* explicit date within range rather than one, which produced candidate spans overlapping each other — and since the derivation above blanks the primary's span out of the title, an over-reaching span blanks part of a neighbouring date and silently changes an identity that cannot be corrected once written. It also attached weekdays to dates they contradict: in "September 6, 2026 … Monday, August 31, 2026" the 6th is a Sunday, and reading the Monday beside it as a restatement of it is simply false.
>
> **One exception, and it is not a softening.** Where a weekday is written into the same phrase as a date — nothing between them but spaces and commas, no line break and no intervening words — it is absorbed whether or not the day matches, and the contradiction is left for FR-504 to display rather than acted on. This section's own example requires it: **12 September 2026 is a Saturday**, so "PTM on Friday 12 September" is a phrase whose weekday matches nothing. Writers get weekdays wrong constantly, and reading a slip as a second commitment invents an item nobody asked for — and would drop "Friday" back out of the blanked span, which is the failure the paragraph above calls load-bearing.

This clause is the one part of §7.2 that depends on parser behaviour rather than on arithmetic over the text, and it is therefore where three independently written clients are most likely to drift. Two consequences follow. A client whose date rules match a different extent of the same phrase will compute a different `item_key` from the same message, which degrades FR-804 across devices without failing anything visibly. And the conformance vectors at `data/src/test/resources/metadata/hash_vectors.tsv` pin the normalisation only — they cannot pin this, because the input to it is whatever that client's parser matched. **A vector file for date-free titles should be added when the second client is built**, and until then FR-804 should be understood as reliable within a client and unproven across them.

**This clause is a wire contract, and v1.14 moved it.** Because `item_key` is written into the user's account rather than recomputed on read, any change to which span this clause blanks changes the identity derived from the same message, and is a breaking change to stored data rather than an internal correction. v1.14's move — from proximity alone to the day the weekday falls on — is therefore permitted **only while Android is the sole client deriving keys**. Items written before it may carry keys the derivation above will not reproduce, and FR-804 shall treat such an item as unmatched: a reschedule of one creates a second item rather than updating the first. No migration is offered, because an item already in a user's account is not rewritten. Once a second client derives keys, this clause may only be changed in all clients at once, and a client left behind will degrade FR-804 silently.

### Normalisation

The following is applied to any text before it is hashed, in this order. It is the AC-07 contract, and each step exists because of a way the same message reaches two platforms differently.

1. Unicode normalisation to **NFC**.
2. Remove U+200B, U+200C, U+200D, U+FEFF and U+202A–U+202E.
3. Replace CRLF and lone CR with LF.
4. Replace every run of whitespace — ASCII whitespace and the Unicode separator categories, which include the non-breaking space — with a single space (U+0020).
5. Trim leading and trailing whitespace.
6. Lowercase using the **root** locale. Never the default locale: Turkish lowercases `I` to a dotless `ı`, which would silently desync two clients on identical text.
7. Encode as UTF-8, take SHA-256, and render as lowercase hexadecimal. The digest is **not** truncated.

**Conformance vectors are held at `data/src/test/resources/metadata/hash_vectors.tsv`** and are normative. A client that does not reproduce every digest in that file does not satisfy AC-07. The file is deliberately pure ASCII with escaped inputs, so that an editor normalising Unicode on save cannot silently turn one case into another.

### Encoding on tasks

A task has no `extendedProperties`; `notes` is its only free-text field, is capped by Google at 8192 characters, and is shown to and editable by the user. The metadata is therefore a single line, appended after the FR-805 source text and separated from it by a blank line:

```
[latch]v=1;sh=<hex>;ik=<hex>;ch=<uuid>;at=<rfc3339>;ap=<scheme:id>;rc=<recipe id>
```

Fields are separated by `;` and named by the short forms above; omitted keys are absent. No value may contain `;` or `=`, which hexadecimal digests, UUIDs, RFC 3339 timestamps, package names and recipe ids all satisfy, so no escaping is required. A reader shall take the **last** line beginning `[latch]`. A line that does not parse shall yield no metadata and the item shall be treated as unmanaged: task notes are user-editable, so corruption is a matter of when rather than whether, and it shall degrade quietly rather than fail a write.

### Version handling

A client encountering a `latch.version` it does not know shall treat the item as Latch-created, shall interpret no key it does not recognise, and **shall neither modify nor delete it**. Guessing at a newer client's keys is how one client corrupts another's items.

### Platform limits

Google caps an event property key at 44 characters and a value at 1024, with at most 300 properties totalling 32 KB per event. The schema above uses seven properties, a longest key of 17 characters and a longest value of 64. These limits are recorded because the SRS otherwise never acknowledges that they exist.

---

# 8. Constraints and known platform limitations

These are properties of the platforms, not defects. They must be designed around and, where user-visible, disclosed in the UI.

## 8.1 Google Tasks has no time of day

The Google Tasks API records only the date portion of a due date; the time is discarded and cannot be read or written through the API. Consequences:

- A deadline requiring a specific time must be created as an Event, not a Task.
- The UI must not offer a time field on a Task.
- Where a user needs a timed alert on a deadline, the app shall offer to create it as an Event and explain why.

**Developer action:** verify this against current Google documentation at build start, as it is a long-standing but not immutable limitation.

## 8.2 Google Tasks has no per-list display filter

All task lists appear within a single Tasks layer in Google Calendar. Task lists cannot be individually coloured or hidden in the calendar view. This means a chain's events can be filtered as a unit but its tasks cannot. Disclosed at setup per FR-107.

## 8.3 Android text selection is not universal

`ACTION_PROCESS_TEXT` requires the host app to use standard text selection and, on Android 11+, to declare package visibility. Apps that select whole messages rather than text ranges (notably WhatsApp) will not show the action. Layers 2, 3 and 4 exist to cover this.

## 8.4 Android background clipboard access is blocked

From Android 10, clipboard reads require foreground focus. The Quick Settings tile pattern (FR-213) is the supported workaround.

## 8.5 Screen capture consent

From Android 14, `MediaProjection` requires user consent per session with no reusable token. Excluded by FR-217.

## 8.6 OAuth verification

The scopes in FR-002 are sensitive and require Google review before general availability. Expect a demo video, a published privacy policy, and domain verification. Budget several weeks and at least one round of correspondence.

---

# 9. Compliance and publication

**FR-1101 [MUST]** The app shall contain no advertising, no in-app purchase and no paid tier.

**FR-1102 [MUST]** A publicly hosted privacy policy shall be maintained, accurately stating that no user content reaches publisher servers. Where FR-1004 ships, the policy **shall** additionally describe the outbound webhook: that the capability exists, that it is disabled by default, that the destination endpoint is chosen by the user, and that content sent to it leaves the device.

**FR-1103 [MUST]** The Google Play Data Safety declaration shall be completed consistently with NFR-201 and NFR-202. Note that Google's Data Safety form asks about data **transmitted off the device**, not only data reaching publisher servers. If FR-1004 ships, the webhook is therefore very likely to require declaration even though the publisher never receives the data. The corresponding privacy-policy obligation sits in FR-1102. The developer shall confirm the correct treatment against current Play policy before the declaration is filed.

**FR-1104 [MUST]** Notification access shall be justified in the Play Console as core functionality, with in-app prominent disclosure.

**FR-1105 [MUST]** Publication shall use a Google Play **organisation** account. Rationale: personal accounts are subject to a closed-testing requirement involving a panel of testers over a fixed period, which materially delays launch. Developer name and address are publicly displayed on the store listing.

**FR-1106 [MUST]** Windows distribution shall be through the Microsoft Store, where developer registration is currently free for both individual and company accounts.

**FR-1107 [MUST]** An annual maintenance allocation shall be planned for Google Play target-API-level updates, policy re-declarations and dependency upgrades. Estimated at two developer-weeks per year, ongoing.

---

# 10. Acceptance criteria

Each scenario below shall pass on a physical device before sign-off.

| # | Scenario | Expected result |
|---|---|---|
| AC-01 | Select "PTM on Friday 12 September at 11:00 AM" in a mail app, invoke from selection toolbar | Event created, correct date and time, on the configured calendar |
| AC-02 | Same text, but omit the time | Task created with due date 12 September, no time field offered |
| AC-03 | Select text containing no date | Undated task created in Capture Inbox; no calendar entry created |
| AC-04 | Select "the order dated 12 March" (past date) | No dated item created; follow-up offered |
| AC-05 | Share a screenshot containing three dates | Three items listed with checkboxes; user selection honoured |
| AC-06 | Apply "Meeting + prep" recipe to a Tuesday meeting with a "3 working days before" step | Prep item lands on the preceding Thursday; UI states that the weekend was skipped |
| AC-07 | Capture the same message on phone and PC | Second capture detected as duplicate; no second item created |
| AC-08 | Capture a rescheduled version of an existing meeting | Update offered, not a new item; user confirmation required |
| AC-09 | Choose a calendar that is unticked in Google Calendar | "Hidden" indicator shown; offer to make visible |
| AC-10 | Capture with aeroplane mode on, then reconnect | Item queued locally, written on reconnection, no loss |
| AC-11 | Save a four-item chain, then press undo | All four items removed from Google |
| AC-12 | Switch from Option B to Option A in Settings | Existing items unchanged; only future captures routed differently |
| AC-13 | Enter "kal 4 baje meeting" | Correctly resolved to tomorrow, 16:00 |
| AC-14 | Enter "05/09" with DD/MM setting | Interpreted as 5 September; interpretation displayed before save |
| AC-15 | Complete first-run setup | Under 60 seconds; Latch calendar created only on finish |
| AC-16 | Abandon setup at step 2 | No calendar created in the Google account |
| AC-17 | Network monitor active during a full capture cycle, app in **default configuration** (no webhook configured) | No outbound request to any non-Google endpoint |
| AC-18 | Configure a webhook, then capture an item with the network monitor active | Exactly one request to the user-configured endpoint; no other non-Google traffic |
| AC-19 | Configure a webhook, then confirm an item captured via the notification listener | No webhook request is made; the item is written to Google only |
| AC-20 | Configure a webhook pointing at an unreachable endpoint, then save an item | Item is written to Google normally; no blocking error; undo still works |
| AC-21 | Configure a reachable webhook, save an item while offline, then reconnect | Google write completes from the queue; no webhook request is sent for that item; behaviour is documented, not reported as an error |
| AC-22 | Confirm an item captured via the notification listener, then inspect the created item in Google | Title and date are present; the notification's text appears nowhere — not in the description, the notes, or the extended properties |

---

# 11. Delivery phases

| Phase | Content | Estimate |
|---|---|---|
| **1** | Android: setup, text selection, share sheet, OCR, parser v1, Event/Task classification, Calendar and Tasks write, Option A/B routing | 6–8 weeks |
| **2** | Browser extension; Windows tray app with hotkey and clipboard OCR | 4–6 weeks |
| **3** | Recipes and working-day engine, Capture Inbox, multi-event extraction, duplicate and reschedule detection | 4–5 weeks |
| **4** | Notification listener, Hinglish parsing, outbound webhook (FR-1004), accessibility pass, localisation, hardening | 3–4 weeks |
| **5** | Store submissions, OAuth verification, closed testing, launch | 3–4 weeks, partly parallel |

OAuth verification should be submitted at the **start of Phase 2**, not at the end, as it is the most common cause of launch slippage.

---

# 12. Out of scope for v1.0

- Any publisher-operated backend or hosted service
- iOS and macOS clients
- Cloud or LLM-based parsing
- Email-forwarding capture address (requires a server)
- Calendar browsing or editing beyond confirmation of a save
- Team or shared workspaces (Google Calendar sharing covers this natively)
- Zapier or similar partner integrations. Excluded on their own merits: a partner listing is an ongoing maintenance obligation to a third party on a product with no revenue. Where FR-1004 ships, it addresses much of the same need.

---

# 13. Open decisions for the client

These must be settled before or during Phase 1. None blocks the start of work.

| # | Decision | Note |
|---|---|---|
| 1 | Final product name | "Latch" is a working title and requires trademark and Play Store name clearance |
| 2 | Publishing entity | An organisation account is recommended (FR-1105); developer name and address appear publicly |
| 3 | Open-source or closed | Open-sourcing would make the on-device privacy claim auditable, address long-term maintenance succession, and enable F-Droid distribution |
| 4 | Holiday data source | Bundled static list versus a maintained source; affects the annual update burden |
| 5 | Hinglish parsing in v1.0 or v1.1 | A differentiator, but adds parser scope and test corpus |
| 6 | Crash reporting vendor | Or none at all, which is the strictest reading of the privacy principle |
| 7 | Support channel | Public issue tracker recommended over an email address with an implied response commitment |

---

*End of specification.*
