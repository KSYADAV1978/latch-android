# Restart prompt — Latch, after the card-scanning and classifier run of 11 September 2026

Copy everything below the line into a fresh session.

---

I am developing **Latch** at `C:\dev\latch-android` — an Android + Windows app that captures dates
and business cards into Google Calendar, Tasks and Contacts. Read `CLAUDE.md` and `docs/SRS.md`
first; they are the authoritative record and they are long. `docs/SRS.md`'s revision table now runs
to **1.216**.

## Standing rules — these override defaults and stay in force all session

- **My Google account is live on both clients and writes are real.** Do not touch the account
  except as a slice genuinely requires, and tell me before you do.
- **Never put a real person's name, number or address in this repository.** SRS 1.155 recorded this
  happening once. **SRS 1.216 records it happening again on 11 September, by the same mechanism** —
  a rule gets explained by naming the card that forced it, and the name travels into a KDoc, a test
  fixture and a revision row. It was caught one command before publication. **Before any push, grep
  the tracked files for the names on the cards you have been holding.** `cards/.../card_corpus.tsv`
  is git-ignored and lives only on this machine; it must never be published.
- **The refusal to guess is limited to three fields** (SRS 1.211): a **name**, a **telephone
  number** and an **email address** are acted on, so a wrong one is worse than a blank and must
  never be repaired. A **company**, **job title**, **address** and **website** are read, so they may
  be assembled across lines and spell-corrected — but an address may be *assembled*, never invented.
- **A protected field that is suspect must say so** rather than be placed in silence (SRS 1.213).
- **Measure a classifier rule against the corpus before keeping it.** It will say *fixes N, breaks
  M*. Every guard in the 11 Sep rules exists because the corpus caught the rule taking something
  wrong; not one was foreseen.
- **Plan into the SRS revision row and the commit message, not to me.**
- **Every slice ends green**: the full JVM suite and `./gradlew build`. **One commit per slice.**
- **No new dependencies without asking**, with measured APK impact (NFR-501).
- **For a device pass I drive the phone and you watch.** Arm a capture on `adb logcat`, give me one
  step at a time, and say what failure looks like before I press anything.
- `adb` is not on PATH — use `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"`. It is **USB**,
  and it dropped **four times** on 11 Sep. Use the self-healing capture (below) or lose readings.
- `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"` for every Gradle command.
- Do not use subagents or workflows unless I ask.

## Where the build stands

Clean tree on `main`, **1429 tests green**, pushed. Both clients installed and signed in.

**FR-1222's corpus went from 11 to 52 of 120 real cards** (60 recognitions) in one sitting — 39
cards scanned, 47 readings, 34 contacts created. It is now a **scoreboard**: every row records what
the card actually says, and the **18 fields the classifier still gets wrong are listed as
`# known-miss` lines** inside the corpus file. A ratchet test asserts each one *still fails*, so a
rule that fixes one turns the build red until the line is struck — a fix cannot land uncounted.
**The ceiling may be lowered and never raised.**

**The measured error rates that drove the work** (47 readings): company wrong or blank **45%**,
address **45%**, email 26%, name 21%, job title 9%. And the account was read back and diffed against
the classifier's draft: **manual correction rate 7 of 35 contacts, 20% — five name corrections, five
company corrections and nothing else.** Two independent measurements agreeing on which fields are
weak.

## What was built on 11 September

| SRS | What | State |
|---|---|---|
| 1.205 | A lost space defeated the company guard (`AMini Ratna Company`) | Fixed |
| 1.206 | **Every camera card shared one capture key**, so the second card of a session opened on the first one's "Saved" and was never written | Fixed, watched |
| 1.207–1.209 | The scanning run, the identity merge, the scoreboard corpus | Recorded |
| 1.210 | Company from the card's own domain, trade-body suffixes, one name split by layout | Fixed, watched |
| 1.211 | The guessing policy narrowed; address rule rebuilt off the comma | Fixed, watched |
| 1.212 | **An identity match may no longer rename a contact** | Fixed, watched on the card that broke it |
| 1.213 | A suspect name or email says so and is never repaired | Fixed, watched |
| 1.214–1.215 | A bare unit number joins an address, and leads it | Fixed, watched |
| 1.216 | The personal-data leak and the purge of all 319 commits | Done |

## The two things owed, in the order they bite

### 1. The account still holds what the old classifier produced

Latch never rewrites a contact it created — §7.2 is write-once. **Fixing the classifier fixed
nothing already saved.** Outstanding by hand in Google Contacts:

- **JSW** — email should be `prashantkumar.saraf@jsw.in`; it holds the half-address `Saraf@jsw.in`
- **SICMA** — job title reads `ceo@sicmain`
- **Adani** — email ends `.cam`, should be `.com`
- **Chetan Bhardwaj (Vedanta)** — email domain is damaged
- **One duplicate Prabhat Trivedi (ALIMCO)** — delete one
- **Deloitte** — company is the fragment `Tohmatsu India LLP` unless it was corrected on the sheet

Five cards were deleted and re-scanned on 11 Sep to verify the fixes and are correct: Deloitte,
Grant Thornton, Reliance, FIMI and Vedanta.

### 2. Tell the person whose address was public for six days — or decide not to

The only part of SRS 1.216 nobody but you can do. `sarita.ghorpade@vedanta.co.in` — the real address
behind that pseudonym is in the corpus — was on GitHub from 5 to 11 September. It is gone from the
server, from both bundles and from this repository, verified by fresh clone. Whether the person is
told is a judgement, and it is the last open item from that incident.

**Everything else about the leak is closed.** The 6 September bundle that still carried it was
deleted, `backup.cmd` was re-run, and the new bundle was verified by restoring it: 320 commits,
`docs/SRS.md` present, corpus absent, zero occurrences. The superseded corpus copy was deleted after
checking row by row that the current one contains all twelve of its rows unchanged plus forty-eight
more. `C:\dev\latch-backup` now holds three files: two clean bundles and one corpus.

**The boundary the design always intended now holds cleanly**, and it is worth keeping in mind
before writing anything down: GitHub, every bundle and this repository carry **no** real personal
data; the corpus and its single backup copy carry it **by design**, git-ignored and never
bundled.

## Three misses recorded and not fixed

1. **Multi-line fields keep only one line.** A company across two lines (`Deloitte Touche` +
   `Tohmatsu India LLP`) and a job title across three both lose the tail to `unplaced`. This is the
   same root cause as the address rule and is now *permitted* by SRS 1.211's policy — it is the
   largest single remaining win.
2. **Multi-address cards.** One card prints works, registered office and a mine within six lines;
   the anchor's cluster wins and the rest is dropped. `CardDraft.addresses` is already a list.
3. **A stranded local part that loses its trailing dot** raises no email doubt — a genuinely damaged
   address placed in silence.

## Instrument lessons from 11 September, each of which produced a wrong answer first

**A rule added inside a guard that already refuses the case is indistinguishable from no rule, and
a green build says nothing about it.** The `B-201` fix was gated behind a check demanding the exact
vocabulary that line lacks; it could never run, and the corpus stayed green with nothing changed.

**A `logcat` capture through a pipe is block-buffered.** Reading it at a saved line offset gave an
empty tail twice and produced two confident wrong conclusions — *"the app logs nothing"* and *"an
update with no capture"*. Read the whole tail by timestamp. The device's own main buffer holds
**about two minutes** under load, so a continuous capture is the instrument and not a convenience.
Use `scratchpad/watch.sh`, which waits for the device and restarts itself.

**`latch.card.captured_at` sits at the save, not at the recognition.** Matching contacts to drafts
on the recognition instant paired every contact with the *next* card's draft and produced twelve
plausible-looking "manual edits" that were all artefacts. A near-miss key gives a table that reads
perfectly and is wrong in every row.

**Reading order is not printed order** (SRS 1.134, again). The same card returned `B-201` before the
street once and after the city the next time, so the corpus row recorded the reading that happened
to be right and a fix went in untested.

**The developer found four defects in one verification pass by reading the screen**, none of which
any JVM test could see: a false email note on a logotype, a missing unit number, its ordering, and a
company fragment. **Ask them to look at the sheet, not just the log.**

## The git repository

GitHub is the primary remote: `https://github.com/KSYADAV1978/latch-android`, public, `origin/main`
tracking `main`. **History was rewritten and force-pushed on 11 Sep** (SRS 1.216) — all 319 commits,
content and messages. Any clone taken before that is stale and carries personal data.
`backup.cmd` and `C:\dev\latch-backup` remain the secondary backup, and `backup.cmd` copies the
git-ignored corpus separately because a bundle is a packfile of git objects and has never contained
it.

## Two limits worth knowing before you measure anything

**Neither device contacts table counts what it looks like** (SRS 1.135). **Google Contacts on the
web is the only authority.**

**A grant survives its own revocation for a while.** Probe before judging: `PROBE_DEDUP` returning
`found=true` means the grant is still live and the fixture is not ready.

## Useful instruments built on 11 September

- **`DebugSavedCardsProbe`** — read-only, debug source set only, creates and patches nothing. Pages
  `connections`, keeps the contacts carrying FR-1207's record, prints them. This is how the manual
  correction rate became measurable at all.
  ```
  adb shell am broadcast -a com.latch.android.debug.PROBE_SAVED_CARDS \
    -n com.latch.android/com.latch.android.debug.DebugSavedCardsProbeReceiver
  adb logcat -d -v time -s LatchSavedProbe:I
  ```
- **`LatchCardOcr`** prints a card's recognised lines and classification, debug-only. It is the only
  diagnostic in this application that prints content, and it is why the run could be analysed.
