# Restart prompt — Latch, after the card-pillar run of 7 September 2026

Copy everything below the line into a fresh session.

---

I am developing **Latch** at `C:\dev\latch-android` — an Android + Windows app that captures dates
and business cards into Google Calendar, Tasks and Contacts. Read `CLAUDE.md` and `docs/SRS.md`
first; they are the authoritative record and they are long. `docs/SRS.md`'s revision table now runs
to **1.189**.

## Standing rules — these override defaults and stay in force all session

- **My Google account is live on both clients and writes are real.** Do not touch the account
  except as a slice genuinely requires, and tell me before you do.
- **Plan into the SRS revision row and the commit message, not to me.** Record every decision and
  the reading it rests on.
- **Every slice ends green**: the full JVM suite and `./gradlew build`. **One commit per slice.**
  **SRS row first wherever a reading changes.**
- **No new dependencies without asking**, with measured APK impact (NFR-501).
- **Prefer moving shared logic into `:wire` / `:google`** over duplicating it between clients.
- **Put decisions in pure functions a JVM test can reach.**
- **Never record an unwatched thing as verified.** Android must stay green.
- **Never put a real person's name, number or address in this repository.** See SRS 1.155 — it
  happened, it reached ten files, and it was removed from the history. `cards/.../card_corpus.tsv`
  is git-ignored and lives only on this machine.
- `adb` is not on PATH — use `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"`.
- `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"` for every Gradle command.
- Do not use subagents or workflows unless I ask.

## Where the build stands

Clean tree on `main`, full suite green, pushed to GitHub.

**The business-card pillar is closed.** 7 September closed the defect that had blocked it and four
more found on the way: SRS 1.165 (`Update` unreachable on a photographed card), 1.183 (a rotation
destroyed the offer and returned the sheet to the button that writes a duplicate), 1.186–1.188 (the
photo tick was inert on the update path and reported success), and **FR-1232's undo restore passed
on a real contact** — a restore, never a delete, with `card decision=Updated fields=1` against an
offer of three, so FR-1231's per-field ticks were finally seen reaching the wire.

## The four things owed, in the order they bite

### 1. SRS 1.180 — the two drafting paths disagree about an explicit midnight

`draftItems` sets `allDay = time == null`. `recipeItems` sets it by comparing the start against
midnight, because a `PlannedItem` carries only a `LocalDateTime` and an anchor with no time was
expanded to midnight several steps earlier — so *no time given* and *midnight given* are the same
value by the time the flag is decided. Measured: `Party 5 October 2027 at 12am` drafts as a **timed
00:00–01:00 event** on the ordinary path and as an **all-day event** once a recipe is applied.

`:wire` exists to stop one message becoming two different things, and this is that failure inside
the module rather than between clients — worse, because the conformance vectors that catch
cross-client drift do not look here. **The cause is a discarded fact, not a wrong rule**:
`expandRecipe` knows whether the candidate had a time and encodes it away as midnight. Carry it on
`PlannedItem` so `recipeItems` tests the same fact `draftItems` does. Touches `:recipes`.

`AllDayTest` in `:wire` already pins the correct behaviour and deliberately does **not** pin the
divergence — a test over the current behaviour would pin the wrong behaviour.

### 2. SRS 1.176 — a partly written chain reports success

`DesktopSaver.writeAll` returns `SaveResult.Written(created.size, …)` whenever `created` is
non-empty, so a three-step recipe whose first task is written and whose second is permanently
refused reports **Saved 2** and says nothing about what was lost. The count is smaller and nothing
invites anyone to notice.

This is the defect the 31 Aug AC-05 note weighed and could not attribute — *a partially written
chain reporting success*. It exists, in code, on the desktop. **The right answer is a decision, not
a patch**: FR-806's queue holds what a retry can fix and a 4xx is not that, so the choice is between
reporting a partial chain honestly and holding the remainder for a manual retry. The honest report
is the smaller half and should come first. **Android's `CaptureSaver` has not been checked for the
same shape** — check it before deciding.

### 3. SRS 1.178 — an expired grant loses the capture on Windows

Android classes `SignInRequiredException` retryable and says why: *a capture given up on for want of
a tap is a capture lost*. The desktop turns the same condition into `GoogleRejected(400)`, which
`isWorthRetrying` refuses, so the capture is reported and **dropped**. It is a recorded decision
rather than an oversight, but it contradicts FR-806a's own reasoning on the client with no Play
services to keep a grant warm — where expiry is *more* likely, not less. The branch also cannot tell
a revoked grant from an expired one: both arrive as `invalid_grant`.

This bit for real on 7 Sep: a to-do with a recipe applied was refused, the capture was lost, and the
whole account of it was five words. **The fix needs a queue entry that survives a sign-in and a tray
line asking for one** — FR-806a's surface on a client that has never had it. A slice, not a patch.

### 4. The undo path logs no decision line

FR-1232 passed on the account rather than on the phone, because the undo emits nothing. That is the
gap SRS 1.72 found for save decisions and cured with `WriteBasis`, and the undo path still has it. A
line such as `card decision=Undone restored=N` would make the next card pass self-evidencing.
FR-1207's `captured_at` across an update is still JVM-only for the same reason — nothing reads it
back.

## Two instrument lessons from 7 September, both of which nearly produced a wrong answer

**Cause the behaviour; do not read the flag.** `uiautomator` reported an inert confirm button as
`enabled="true"` (SRS 1.174), and an inert, deliberately disabled tick as `enabled="true"` again
(SRS 1.188) — the second is a merged Compose `toggleable`, whose bounds span the whole row and whose
disabled state never reaches that attribute. A check written against either flag would have recorded
the wrong result. Press it and re-read.

**Build a reproduction you can run yourself before diagnosing anything.** SRS 1.165 cost two days
because every attempt was graded on a photograph a person had to take by hand. FR-1230 reaches the
card sheet from a *text selection*, and FR-1231 keys on an **email identity** — so a generated card
image whose job title, website and address differ from an existing contact raises
`UpdateOffered changes=N` on demand in about forty seconds, with no camera and no write. That
reproduction broke the defect open in minutes.

**And a false exoneration is worse than no diagnosis.** SRS 1.165's ruled-out table claimed a brace
walk put the offer rows inside the scrolling column. They were a *sibling* of it. That one wrong
line sent three separate fixes at a component that was never in the path.

## The git repository

GitHub is the primary remote: `https://github.com/KSYADAV1978/latch-android`, public, `origin/main`
tracking `main`. Commit identity is the GitHub noreply address. `backup.cmd` and
`C:\dev\latch-backup` stay as the secondary backup, and `backup.cmd` copies the git-ignored card
corpus separately — a bundle is a packfile of git objects and has never contained it.

## One limit worth knowing before you measure anything

Neither device contacts table counts what it looks like (SRS 1.135). `contacts` is the aggregated
view; `raw_contacts` shows one created contact as two rows in this account. **Google Contacts on the
web is the only authority.** Every device-side count in this project is an indication, not a number.
