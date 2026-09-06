# Restart prompt — Latch, after the ship-v1.0 run

Copy everything below the line into a fresh session.

---

I am developing **Latch** at `C:\dev\latch-android` — an Android + Windows app that captures dates
and business cards into Google Calendar, Tasks and Contacts. Read `CLAUDE.md` and `docs/SRS.md`
first; they are the authoritative record and they are long. `docs/SRS.md`'s revision table now runs
to **1.156**.

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

Clean tree on `main`, **1320 JVM tests green**, `./gradlew build` green.

The ship-v1.0 run of 6 Sep 2026 built every remaining requirement except one, prepared the
repository for publication, and wrote the release and launch documents. What it did is in
`CLAUDE.md` under *The ship-v1.0 run*, and each slice has an SRS row (1.150 to 1.156).

## What is now owed, in the order it bites

### 1. The device pass — the largest debt by far

Everything built on 6 Sep is **JVM-verified and DEVICE-OWED**, and so is most of what came before
it. `CLAUDE.md` carries the backlog slice by slice, each row written as *the condition that would
make this fail*, then *the fixture*. The newest rows are under **Device pass backlog — Step 0's
three slices** and they include FR-1231's, which **write to my real Google Contacts** — every one
of those rows says what it leaves behind.

Start with the two rows carried forward from before the run, which are still unwatched:
FR-1226's cropped contact picture (SRS 1.148) and the four-section home screen (SRS 1.149).

### 2. The two corpora, which only real use can close

NFR-502 wants 300 real captured strings and stands at **113**. FR-1222 wants 120 real business
cards and stands at **11**. Both forbid invented entries. Both are recorded in `docs/RELEASE.md`
under **Knowingly unmet at submission**, and `docs/MARKETING.md` argues that the hundred test-user
slots exist mainly to close them.

### 3. Publisher work only I can do

`docs/RELEASE.md` and `docs/RELEASE-WINDOWS.md` are the operational gates. In rough order:

- **Clear the name.** §13 decision 1. `docs/MARKETING.md` makes this Phase 0 — marketing under a
  name I may lose means doing it twice.
- **A hosted privacy policy and a homepage**, without which OAuth verification cannot start.
- **Submit for OAuth verification early.** §11 says the start of Phase 2, not the end; it is the
  most common cause of launch slippage. Until it completes only test users can sign in at all.
- **The upload keystore**, and registering its SHA-1 (and Play App Signing's) against the Android
  OAuth client — sign-in fails on exactly the build being shipped otherwise.
- **Measure the bundle per device.** NFR-103's figure is still an estimate. The universal APK is
  43.15 MB; the budget is 40 MB per device and the `bundle` block is what makes that different.
- **A Play organisation account** (FR-1105), or accept the closed-testing requirement.
- **The Windows SDK**, for FR-305's MSIX. `desktop/packaging/Package-Msix.ps1` is written and
  refuses rather than approximating; it needs the SDK, a jlink runtime and tile artwork.

### 4. The browser extension, deferred rather than dropped

FR-401 to FR-403 are `[MUST]`, are not built, and were deferred by decision on 6 Sep 2026 with the
alternatives measured — SRS 1.154 and `docs/RELEASE.md`. If it is taken up, the decision recorded
there is Kotlin/JS over the four shared modules rather than a hand-written JavaScript parser, and
it carries two dependency questions: `kotlinx-datetime` (which is not `java.time`) and a
synchronous SHA-256 that Web Crypto does not provide.

### 5. Smaller things, named so they are not forgotten

- **FR-306's receiving half.** The share-target declaration is in the manifest and the activation
  stub is not built; the script strips the declaration until it is (SRS 1.153).
- **FR-204's help list has one entry**, because one is all anybody has checked.
  `docs/DOGFOODING.md` asks for more.
- **`LatchCardOcr` stays until FR-1222's corpus is closed**, then goes. It is item 0 of
  `docs/RELEASE.md`, and the artifact sweep that proves a release build is clean is written there.

## The git repository

Prepared for publication on 6 Sep 2026 and **not yet pushed** — `git remote -v` is empty.

- README and Apache 2.0 licence are committed.
- History was audited: no keystore, no `local.properties`, no OAuth client file, no API keys or
  tokens have ever been committed.
- **The history was rewritten** to remove real third parties' contact details (SRS 1.155). Every
  commit changed SHA. There was no remote, so nothing needed force-pushing anywhere.
- A pre-rewrite bundle was kept at the time; it is **not** in this repository and holds the
  unscrubbed history, so it must not be published.

To push, once the repository exists on the host:

    git remote add origin <url>
    git push -u origin main

## One limit worth knowing before you measure anything

**Neither device contacts table counts what it looks like** (SRS 1.135). `contacts` is the
aggregated view; `raw_contacts` has 2,273 duplicate names in this account. **Google Contacts on the
web is the only authority.** Every device-side count is an indication, not a number.
