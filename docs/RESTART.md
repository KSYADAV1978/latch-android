# Restart prompt — Latch, after the device-pass run of 10 September 2026

Copy everything below the line into a fresh session.

---

I am developing **Latch** at `C:\dev\latch-android` — an Android + Windows app that captures dates
and business cards into Google Calendar, Tasks and Contacts. Read `CLAUDE.md` and `docs/SRS.md`
first; they are the authoritative record and they are long. `docs/SRS.md`'s revision table now runs
to **1.201**.

## Standing rules — these override defaults and stay in force all session

- **My Google account is live on both clients and writes are real.** Do not touch the account
  except as a slice genuinely requires, and tell me before you do.
- **If a test must write, put every dated fixture in one month** and give me the list at the end.
  September 2027 was used on 10 Sep and it worked: one search to clean up instead of an audit.
- **Plan into the SRS revision row and the commit message, not to me.** Record every decision and
  the reading it rests on.
- **Every slice ends green**: the full JVM suite and `./gradlew build`. **One commit per slice.**
  **SRS row first wherever a reading changes.**
- **No new dependencies without asking**, with measured APK impact (NFR-501).
- **Prefer moving shared logic into `:wire` / `:google`** over duplicating it between clients.
- **Put decisions in pure functions a JVM test can reach.**
- **Never record an unwatched thing as verified.** Android must stay green.
- **Never put a real person's name, number or address in this repository.** See SRS 1.155.
  `cards/.../card_corpus.tsv` is git-ignored and lives only on this machine.
- **For a device pass I drive the phone and you watch.** Arm a `Monitor` on `adb logcat`, give me
  one step at a time, and give steps that share a ten-second window *together* so I am never
  waiting on you mid-countdown.
- `adb` is not on PATH — use `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"`. It is **USB**.
- `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"` for every Gradle command.
- Do not use subagents or workflows unless I ask.

## Where the build stands

Clean tree on `main`, full suite green (**1403 tests**), pushed to GitHub. Both clients installed,
signed in, grants live, queues empty. **The account is clean** — every fixture the 10 Sep session
wrote was deleted by hand.

**The device backlog was reconciled and then worked down: 55 open of 108**, from roughly a hundred
apparent. `CLAUDE.md`'s slice tables had never been struck as passes landed, because a pass is
recorded in the *Verified on a device* table instead; 27 rows were already done and 7 more partly
done. What remains is mostly what only a person can do.

**`docs/MANUAL-TESTS.md` is the follow-along guide** — every remaining check written as *what to
do*, *what passing looks like* and *what failure looks like*, cheapest first, plus the publisher
steps and the open decisions.

**Five defects were found on 10 Sep, four of them by using the thing rather than testing it.**

| SRS | What | State |
|---|---|---|
| 1.195 | The desktop drain had no mutual exclusion; two overlapping drains both passed FR-803 and both inserted | **Fixed** |
| 1.196 | The tray menu never dismissed — a `JWindow` invoker cannot take focus, so the listener could not fire | **Fixed, watched** |
| 1.197 | FR-603's editor has **two controls labelled "Done"**; the top-right one discards the edit silently | **Open — a decision** |
| 1.198 | FR-806b's banner fired when merely offline, naming a cure the user could not apply | **Fixed at 1.199, watched both ways** |
| 1.200 | The Windows tray claims *"Signed in as…"* over a revoked grant and, with an empty queue, offers **no route back** | **Open — a decision** |

## The two things owed, in the order they bite

### 1. SRS 1.200 — the tray claims a signed-in state it has not got, and strands the user

`isSignedIn` is `secrets.get(REFRESH_TOKEN_KEY) != null` — **presence, not validity**. After a real
revocation the tray still reads *Signed in as …*; the account line is a `Status` with **no action**;
and `SIGN_IN` is attached only to the *Not signed in* row, which needs the token to be **absent**.
So a user whose grant is revoked and who has not yet captured anything has no way to sign in except
Sign out, which looks like leaving the account.

**It self-heals on the first capture** — the `invalid_grant` handler removes the dead token, which
restores both rows — and SRS 1.192 means that capture is *held*, not lost. The harm is bounded, and
that whole sequence was watched end to end on 10 Sep (SRS 1.201).

**The amendment names a fix that needs no network call**: offer `SIGN_IN` on the account line
whenever a stored token has failed. That sidesteps the cost objection which made this a decision —
an honest tray otherwise means asking Google on a surface that reads four local files and is
expected to open instantly. **Decide, then fix.**

### 2. SRS 1.197 — FR-603's two "Done" buttons

The top-right `TextButton` reads `recipes_edit` collapsed and **`recipes_done` expanded**, and its
`onClick` is `onToggleExpanded` — collapse, no save. The saving control is a filled `Button` at the
**bottom**, often below the fold. Three things make it a trap: the discarding button sits where
"Edit" was a second earlier; it is the only "Done" visible until you scroll; and the outcome is
**silent**, which NFR-303 names as a defect in as many words.

Three honest fixes, each saying something different about what an expanded card is: relabel the
toggle ("Close"), make it save as well, or move the saving button up beside it.

## After that, what is left is mine rather than the code's

`docs/MANUAL-TESTS.md` §3–§9. The long pole is **the two corpora** — NFR-502 wants 300 real captured
strings and stands at **113**; FR-1222 wants 120 real cards and stands at **11**. Neither may be met
with invented entries. **Start OAuth verification regardless** (§9.1): it gates general availability
and is the commonest cause of launch slippage.

## Four instrument lessons from 10 September, each of which nearly produced a wrong answer

**Run a positive control beside the thing under test.** FR-904's picker looked inert across a dozen
taps — clickable bounds, coordinates provably inside them. The phone had **disconnected** and every
dump was a stale file. The badge would not flip either, and that is what caught it. *A control that
fails alongside the check means the instrument is dead, not the app.*

**Read the code before calling anything a defect.** Three of four apparent defects were deliberate:
the recipe chooser is folded until tapped, "New recipe" appends at the **bottom** of the list, and
the rename that "would not save" was the wrong Done button. Each cost a minute to check and would
have cost a false report.

**Guard every input event, not just taps.** Back from the recipe editor left Latch entirely and
eight **swipes** went into a third-party messaging app. `KEYCODE_BACK` is a guess about the
navigation stack — use `am start` to go where you mean to go. And with a text field focused, a swipe
over the keyboard is **glide-typing**: five scroll gestures put five words into a recipe name.

**One instrument can destroy another's measurement.** Polling `uiautomator` to catch FR-207's
progress line inflated that same read from 5128 ms to **7130 ms**, against a 6500 ms budget — a
breach that was not there. Measure the line or measure the budget, never both in one run.

## The git repository

GitHub is the primary remote: `https://github.com/KSYADAV1978/latch-android`, public, `origin/main`
tracking `main`. Commit identity is the GitHub noreply address. `backup.cmd` and
`C:\dev\latch-backup` stay as the secondary backup, and `backup.cmd` copies the git-ignored card
corpus separately — a bundle is a packfile of git objects and has never contained it.

## Two limits worth knowing before you measure anything

**Neither device contacts table counts what it looks like** (SRS 1.135). `contacts` is the
aggregated view; `raw_contacts` shows one created contact as two rows in this account. **Google
Contacts on the web is the only authority.**

**A grant survives its own revocation for a while.** Play services answers `authorize()` from a
cached record, so the app keeps working after a revocation at `myaccount.google.com`. Probe before
judging anything: `PROBE_DEDUP` returning `found=true` means the grant is still live and the fixture
is not ready. On 10 Sep it answered `SignInRequiredException` with Play services' own
`getToken() -> NEED_REMOTE_CONSENT` beside it, and that is what made the run conclusive rather than
suggestive.
