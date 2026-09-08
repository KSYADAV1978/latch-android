# Restart prompt — Latch, after the four-slice run of 8 September 2026

Copy everything below the line into a fresh session.

---

I am developing **Latch** at `C:\dev\latch-android` — an Android + Windows app that captures dates
and business cards into Google Calendar, Tasks and Contacts. Read `CLAUDE.md` and `docs/SRS.md`
first; they are the authoritative record and they are long. `docs/SRS.md`'s revision table now runs
to **1.193**.

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

## The four things owed are BUILT — 8 September 2026, SRS 1.190 to 1.193

All four, one commit each, pushed to GitHub at `8c2b059` and verified against the server rather
than against this clone: `refs/heads/main` matches local `HEAD`, 333 files, and `card_corpus.tsv`
is **not** among them — only its `.example`. **Everything is JVM-verified and DEVICE-OWED**;
no device was attached and nothing touched the Google account.

Each commit was **re-built on its own afterwards** rather than only at the end of its slice, so
"every slice ends green" is checked across the range: 1361 → 1374 → 1385 → 1396 tests, no failure
at any of them. The four together cost **+172 bytes** of release APK, measured by building
`:app:assembleRelease` at `4645957` and at `8c2b059`. No dependency was added.

| SRS | What it was | Where the surprise was |
|---|---|---|
| 1.190 | `recipeItems` compared a start against midnight, which cannot tell *no time given* from *midnight given* | **A third site had it too** — the desktop's `stepWhenLine`, where screen and write agreed *wrongly*, which is why it read as correct |
| 1.191 | A chain interrupted part-way reported a success on the desktop | Checking Android as this brief asked found it **twice more**, and one is a **silent loss**: a retryable failure mid-chain queued with no markers, so the drain found the item the chain itself wrote and retired the entry with the rest never written |
| 1.192 | An expired grant on Windows lost the capture | **Two tests pinned the wrong behaviour** and passed honestly throughout the period captures were being lost |
| 1.193 | The undo emits no decision line | **SRS 1.189's account was not quite right**: a *transport* line has existed since 6 Sep. What was missing is the decision line above it |

**What is owed now is a person**, and the rows are in `CLAUDE.md` under *Device pass backlog — the
four slices of 8 September*. Run the **Windows sign-in row first**: it is the only one whose fix is
unwatched *and* whose failure mode is a lost capture.

Both clients are built at `8c2b059` and **neither is installed**: `app/build/outputs/apk/debug/`
and `desktop/build/install/desktop/`. **The installed Windows client is older, read off the disk
rather than assumed**: `%LOCALAPPDATA%\Latch\app\lib\desktop.jar` is dated **7 Sep 10:17**, so
it does not have SRS 1.192 in it. Re-run `install-local.ps1` before testing that row or the run
will measure the old behaviour — and an old build cannot even produce the fixture, since it
*drops* the capture rather than queueing it. `queue.dat` is currently **absent**, so the queue is
empty and the row starts from a clean fixture.

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
