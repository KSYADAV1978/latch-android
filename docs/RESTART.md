# Restart prompt — Latch, ship v1.0 complete

Copy everything below the line into a fresh session.

---

I am developing **Latch** at `C:\dev\latch-android` — an Android + Windows app that captures dates
and business cards into Google Calendar, Tasks and Contacts. Read `CLAUDE.md` and `docs/SRS.md`
first; they are the authoritative record and they are long. `docs/SRS.md`'s revision table now runs
to **1.149**.

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
- `adb` is not on PATH — use `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"`.
- `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"` for every Gradle command.
- Do not use subagents or workflows unless I ask.

## Where the build stands

Clean tree on `main`, **1278 JVM tests green**, `./gradlew build` green. Last commits:

    94b913e Attach the picture the sheet showed, and section the home screen
    0b7e896 Count dates, not candidates, when a card sheet is dismissed
    2195cbf Preview every photograph, not the first
    5f11162 Show what a partly-consumed line left behind
    0832b00 Crop the preview to the region the reader read

The debug build is installed on a Pixel (`23041FDEE007F3`). **Two things are built and unwatched**
and should be verified on the device early: FR-1226's contact picture now being the cropped,
levelled image the sheet showed (SRS 1.148), and the four-section home screen (SRS 1.149).

## The decision taken, and the job

I audited the SRS against the source. I have chosen **the full build — nothing deferred.** Build
every remaining requirement, then ship. Work through this in order, and do not skip ahead:

### Step 0 — build every unbuilt requirement

| Requirement | What it is | Size |
|---|---|---|
| **FR-204** | In-app help topic listing apps that do not cooperate with `PROCESS_TEXT` | small |
| **FR-1230** | A **text selection** capturable as a contact — the email-signature case — through FR-1202's same explicit choice | slice |
| **FR-1231** | Where a capture matches an existing contact by FR-1209's identity with different values, **offer** to update it, naming the fields that would change and their current values, writing nothing while the offer stands. This is FR-804 on contacts and inherits its readings: no default answer, Save withdrawn while it stands | slice |
| **FR-1232** | Undo of that update **restores** the previous values — which after the patch exist nowhere else — and **never deletes the contact** | with 1231 |
| **FR-1233** `[SHOULD]` | Where an update changes employer or job title, show the previous value in the offer. **The SRS forbids inventing a move-note analogue here** — read FR-1233 before building it | with 1231 |
| **FR-401/402/403** | Manifest V3 browser extension for Chrome and Edge: context-menu item on selection, capturing page title and URL into the item | **a whole fourth client** |
| **FR-305** | Windows MSIX packaging. Needs the Windows SDK — check whether it is installed before planning | needs SDK |
| **FR-306** `[SHOULD]` | Windows share target | small |

**Already built but never cited by requirement ID** — traceability only, no behaviour missing.
Add the citation where the code implements them: **FR-514** (`HINGLISH_AMBIGUOUS = setOf("kal")`
in `RelativeDateRule`, and `Confidence.MEDIUM`'s `baje` note), **FR-516**, **NFR-301**,
**FR-1101**, **FR-1214**.

**Deferred by the SRS itself, leave alone:** FR-307 `[LATER]`, FR-1006 `[LATER]`,
FR-909 `[SHOULD]`.

### Steps 1 to 5, in this order

1. **Git.** I do not yet have a remote — `git remote -v` is empty and `gh` is not installed on
   this machine. Prepare the repository: audit `.gitignore` and scan the history for anything
   secret (`keystore.properties`, `local.properties`, tokens, my email in committed files), write
   a README and choose a LICENCE, then hand me the exact commands. **You cannot create the account
   for me** — I will do that and tell you the URL. Ask me whether the repository should be public
   or private before pushing.
2. **A final user version with no debug or developer artefacts.** `docs/RELEASE.md` item 0 is the
   one with teeth: **the `LatchCardOcr` diagnostic prints the contents of a business card to
   logcat and must be removed before submission.** Keep it until the card corpus work is done,
   then remove it. Also sweep for: `LatchTiming`, any debug token hook, `testdata/` fixtures,
   the instrumented suite's reach into real stores, and anything else that should not ship.
3. **Google Play.** Work `docs/RELEASE.md` — it is FR-1108's gate in operational form.
4. **Microsoft Store** — `docs/RELEASE-WINDOWS.md`, FR-305, FR-1106.
5. **A marketing plan** so the word reaches users.

## Blockers you must not paper over

- **OAuth verification is the gate on a public launch** (SRS §8.6). All four scopes are Sensitive,
  so until Google verifies the app **only test users on my consent screen can sign in** — a public
  listing would install for anyone and sign in for nobody. It needs a hosted privacy policy
  (FR-1102), a homepage, a demo video and weeks of turnaround.
- **FR-1105 requires a Play organisation account.** A personal one triggers the closed-testing
  requirement — a panel of testers over a fixed period.
- **There is no release keystore and nothing in the repository generates one, deliberately.**
  Only I can create it.
- **Sign-in works only on builds whose signing certificate is registered against the Android OAuth
  client**, so the release SHA-1 must be registered before a release build can sign in at all.

## Still undecided — ask me before acting on it

**The two corpora are MUSTs that only real-world data can close**: NFR-502 wants 300 real captured
strings and stands at **113**; FR-1222 wants 120 real business cards and stands at **11**. Both
forbid invented entries from counting. The options are to ship with the gap recorded openly in
`RELEASE.md` as a dated, knowingly-unmet requirement; to hold the release until I have collected
them; or to amend the targets — which I am inclined to refuse, since moving a target to meet it is
what this repository's own conventions warn against. **Ask me at the point it matters, not now.**

## Owed device work, carried forward

- **FR-1226's cropped contact picture (SRS 1.148)** and **the sectioned home screen (SRS 1.149)** —
  both built today, neither watched.
- **The card-sheet dismiss row, second half** — a capture holding **both** a contact code and a
  date, where dismissing after saving the contact must return to the **dates**. SRS 1.147 fixed the
  first half; this fixture has never existed.
- **FR-1227's third row** — the exact answer outranking the inexact one, which needs **one image
  file shared twice** so the source hashes match. Every capture so far has been a fresh photograph.
- The B2 camera rows still open in `CLAUDE.md`: the "Reading the card…" spinner, backing out of the
  camera, a rotation with the sheet up, a card with a QR, a blank wall, `latch.card.layer=CAMERA`
  read back from a contact, FR-1003 not refusing the camera, and NFR-101 for a four-photograph
  capture.
- Everything in `CLAUDE.md`'s **Device pass backlog** — slices 1 to 9 — is JVM-verified and
  device-owed.

## One limit worth knowing before you measure anything

**Neither device contacts table counts what it looks like** (SRS 1.135). `contacts` is the
aggregated view; `raw_contacts` has 2,273 duplicate names in this account. **Google Contacts on the
web is the only authority.** Every device-side count is an indication, not a number.

Start with Step 0. Before writing code, read `docs/SRS.md` for each requirement you are about to
build — several of them carry readings that forbid the obvious implementation.
