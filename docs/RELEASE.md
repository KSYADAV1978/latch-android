# Release checklist (FR-1108, and everything that binds at submission)

FR-1108 makes three things a **release gate**, and shipping FR-1004 and FR-208 has since added
three more. Shipping §5.11's business-card pillar has added a seventh, which is item 0 below.
A **Knowingly unmet at submission** section was added on 6 Sep 2026 and is not optional reading:
it names the `[MUST]` requirements this release ships without.
This file is the whole list, and it exists because the distance between each of those decisions
and the day someone opens the Play Console is months — long enough that nothing in the code will
remind them.

Three of the seven are done — item 4's measurement joined the first two on 11 Sep 2026 — one is a
grep in this repository, and **three are things only the publisher can do**, marked so. §5.11 also makes two of the publisher items *wider* rather than
merely due: FR-1102's privacy policy must now carry "Latch never deletes contacts", FR-1103's
Data Safety declaration must cover Contacts, and FR-1241's OAuth verification must be requested
against the final scope set including `.../auth/contacts`.

---

## The release sequence — personal account, planned 11 Sep 2026 (SRS 1.220)

The items below are *what* must be true at submission; this is *the order*, because a personal
Play account adds a 14-day closed test (FR-1105), and three of the items change what that test can
do. **Budget about five weeks** from the first upload to production, not the two weeks the test
alone suggests. OAuth verification runs alongside and its length is Google's.

**A — Before anything is uploaded**
1. Create the upload keystore and `keystore.properties` (item 3).
2. Register the **upload key's** SHA-1 on the Android OAuth client (item 5, first half).
3. Decide `versionName` for the first upload. It is `0.1.0` now and shows on the listing;
   `versionCode` must rise with every upload after the first.
4. Record the demo video (`docs/OAUTH-VERIFICATION.md`) on the debug build.

**B — The Play Console account**
5. A **personal** account: US$25, identity verification with a government ID whose address
   matches the Google payments profile, and the device check through the Play Console app on a
   physical Android 10+ phone. Google says verification may take a few days.
6. Create the app. New apps are enrolled in Play App Signing.

**C — Internal testing: the sign-in gate**
7. `./gradlew :app:bundleRelease`, re-run item 0's sweep and item 4's measurement on **that**
   bundle, and upload it to the **Internal testing** track.
8. Play Console → *Setup → App signing*: register the **app signing key's** SHA-1 on the Android
   OAuth client (item 5, second half). Testers install Play's re-signed build, and without this
   fingerprint **every tester's sign-in fails**, on the only build they have.
9. Install from the internal track on your own phone and sign in. *Failure looks like* a sign-in
   error on a Play-installed Latch while the debug build still works — that is step 8 missing. No
   other check proves that the build testers get can sign in.

**D — The listing and OAuth, in parallel**
10. App content: privacy policy URL, Data Safety (`docs/STORE-LISTING.md`), no ads, the content
    rating questionnaire, and a target audience that is not children. **App access**: everything
    in Latch is behind Google sign-in, so Play's reviewers need a way in. Google's form asks for
    instructions or credentials; what it accepts for a Google sign-in is to be read in the Console
    rather than assumed.
11. The store listing text and screenshots (`docs/STORE-LISTING.md`).
12. **OAuth: move the consent screen to *In production* and submit verification**, with the video
    and the justifications, **before the closed test starts**. In *Testing*, every tester's
    authorization expires seven days after consent, so a 14-day test would ask every tester to sign
    in again at least once, and a Play reviewer who is not a listed test user could not sign in at
    all. In production and still unverified, sign-in is open to up to **100 users in total**,
    behind an unverified-app warning. Tell testers that warning is expected.

**E — The closed test, 14 days at least**
13. Recruit **15–20** testers for a requirement of 12, because the rule is 12 opted in
    *continuously* for the 14 days before applying, and people drop out. Each needs an Android
    phone and a Google account, and each counts against the 100-user cap. Recruit from §3.1's
    users, not developers (`docs/MARKETING.md`, Phase 1): parents, people who get bills and courier
    messages, people who collect cards.
14. Create the **Closed testing** track, add the testers (an email list or a Google Group), and
    roll out the release. Google reviews it, which can take days. Then send the opt-in link;
    testers opt in and install from Play.
15. **Day 0 is the day the twelfth tester opts in.** Check the opted-in count every day. If it
    drops below 12, read Google's current wording before assuming the 14 days carry on.
16. During the test: collect feedback, ship fixes to the closed track, and keep a note of what
    changed. The production application asks for **how testers were recruited, how they engaged,
    what they said, and what changed because of it**, and that is easy to write from notes and
    hard to reconstruct later.

**F — Production**
17. After day 14: *Dashboard → Apply for production*. Google says review is usually seven days or
    less.
18. On the exact bundle to be released: item 0's sweep, item 4's measurement, and the
    *Knowingly unmet* section below, read again.
19. Release. **Until OAuth verification completes, the 100-user cap applies to production too.**
    Don't promote Latch beyond the closed test until verification is granted; the 101st person
    to install it would be unable to sign in.

---

## What is done in the repository

### 1. A release signing configuration ✅

`app/build.gradle.kts` reads `keystore.properties` from the repository root — git-ignored — and
creates a `release` signing config only if that file exists.

**Its absence is not an error.** Every debug build, `./gradlew build` and the launch canary have
to work on a machine that has never seen a keystore, including a fresh clone, which NFR-503
requires to build. So a release build with no `keystore.properties` produces an **unsigned**
artifact rather than failing — and shipping one is caught by this checklist, not by the compiler.

`keystore.properties.example` is committed and shows the four keys with no values.

### 2. A `bundle` block ✅

ABI, density and language splits are all enabled.

**This is the condition NFR-103's whole reading rests on.** Bundled ML Kit is one native library
per ABI, so a universal APK carries four copies of a thing any device needs one of: **13.97 MB
per device against 42.58 MB universal**, with a 40 MB budget. `docs/DEPENDENCIES.md` records the
measurement and the SRS records the reading; both are only true if Play actually delivers a
split, which is only true if the shipping artifact is an App Bundle with these splits on.

    ./gradlew :app:bundleRelease

---

## What only you can do

### 3. Create the upload keystore ⛔ **you**

Nothing in this repository generates one, deliberately: a key generated by a build script is a
key someone else has seen the passwords of.

    keytool -genkeypair -v \
      -keystore latch-upload.jks \
      -alias latch-upload \
      -keyalg RSA -keysize 4096 -validity 10000

Then copy `keystore.properties.example` to `keystore.properties` and fill it in with an absolute
path to the `.jks` and the two passwords.

**Keep it and back it up somewhere that is not this machine.** Whoever holds this key holds the
ability to publish updates. Losing it means the app can never be updated under the same listing
unless Play App Signing is enrolled — which it should be, and which is the safety net for
exactly this.

### 4. Measure the bundle, per device ✅ **11 Sep 2026**

**Done: 17.20 MB on the device and 8.16 MB downloaded, arm64-v8a, against 40 MB.** Recorded in
`docs/DEPENDENCIES.md` under *NFR-103, measured with bundletool*, with the commands and the split
breakdown. Re-run it on the bundle you actually submit; what follows is the original instruction.

FR-1108 item 2, and it is separate from items 1 and 3 on purpose. **The figure NFR-103 currently
rests on is an estimate**: it subtracts the other ABIs' entries from a universal APK, which is
the honest arithmetic available before a bundle exists but is not the artifact Play builds.
Splits carry their own overhead and the real number will differ.

    ./gradlew :app:bundleRelease
    bundletool build-apks \
      --bundle=app/build/outputs/bundle/release/app-release.aab \
      --output=latch.apks --mode=default
    bundletool get-size total --apks=latch.apks --dimensions=ABI

Record the arm64-v8a figure in `docs/DEPENDENCIES.md` against NFR-103's 40 MB budget. This is
the point at which NFR-103 stops being a calculation and becomes a measurement.

### 5. Register the release SHA-1 against the Android OAuth client ⛔ **you**

FR-1108 item 3, FR-001, and the thing that is invisible until the first release build and fatal
when it is found: **sign-in fails on exactly the build being shipped.**

    keytool -list -v -keystore latch-upload.jks -alias latch-upload

Take the SHA-1 and add it to the Android OAuth client in the Google Cloud console, beside the
debug fingerprint that is already there. If Play App Signing is enrolled, register the **app
signing key's** SHA-1 from the Play Console as well — Play re-signs the artifact, so the
certificate users' devices see is not the one you uploaded with. That second fingerprint is the
one that catches people out.

`CLAUDE.md` already records the debug-keystore half of this fact; this is the same fact one step
later.

### 6. Play Console: three declarations ⛔ **you**

These became binding on the days their capabilities shipped, months before anyone opens the
Console.

- **FR-1102 — privacy policy.** A publicly hosted policy stating that no user content reaches
  publisher servers, and — because FR-1004 ships — additionally describing the outbound webhook:
  that the capability exists, that it is disabled by default, that the destination endpoint is
  chosen by the user, and that content sent to it leaves the device.

  **And, because §5.11's business-card pillar ships, three sentences about contacts.** That Latch
  creates contacts in the user's own Google Contacts; that the data written is a third party's
  and comes off a card the user photographed or was handed; and — verbatim, because the app tells
  the user this on the card sheet and a policy that did not say it would contradict the product —
  that **Latch never deletes a contact**. On card images the policy needs two sentences, not
  one: the photograph itself is not stored (FR-1211, amended at SRS 1.120), **but FR-1226 is
  built** — when the user ticks the box, a crop of the card is sent to Google Contacts as the
  contact's picture. This line used to say FR-1226 was unbuilt and the policy could claim no
  image left the device at all; that stopped being true on 6 Sep 2026, and a policy written from
  the old line would be false. `docs/STORE-LISTING.md` item 4 has the same wording.

  **Hosted from 11 Sep 2026 at `https://ksyadav1978.github.io/latch-android/privacy/`**, homepage
  one level up, both on the `gh-pages` branch; `docs/PRIVACY-POLICY.md` is the source and changes
  first. Still owed: a legal review, and confirming in the Cloud console that Google's OAuth
  branding accepts a `github.io` domain — the draft's reviewer notes say why that is uncertain.
- **FR-1103 — Data Safety.** `docs/STORE-LISTING.md` now carries the form **answered row by row**, with the one distinction that decides four of them. Google's form asks about data transmitted **off the device**, not
  only data reaching publisher servers, so the webhook is very likely to require declaration
  even though the publisher never receives the data. Confirm the correct treatment against
  current Play policy before filing.

  **Contacts is now a declared data type**, and it is the one on this form most likely to be
  filled in wrongly by analogy with the rest of the app: the data is *collected* (it goes to
  Google Contacts, off the device) even though the publisher never sees it, which is exactly the
  distinction the webhook line above already draws. The camera is **not** a permission this app
  holds — FR-1201a delegates to the camera application through `ACTION_IMAGE_CAPTURE` — so
  nothing on this form should assert camera access.
- **FR-1104 — notification access.** Justify it in the Console as core functionality. The in-app
  prominent disclosure it also requires **is** built and is unavoidable before the permission can
  be requested — `NotificationAccessScreen` — so what is owed here is the Console half.

  **Checked 11 Sep 2026: there is no Console form for it.** Play's declaration form covers SMS and
  call log, background location, all-files access, `QUERY_ALL_PACKAGES`, accessibility,
  `REQUEST_INSTALL_PACKAGES`, Health Connect, exact alarms and full-screen intents — not
  notification listeners. What governs Latch is the user-data policy's prominent-disclosure rule,
  which the screen meets. Scene 11 of `docs/OAUTH-VERIFICATION.md` records it on video, for a
  reviewer who asks.

### 7. Publication account and store listing ⛔ **you**

- **FR-1105: a personal Play account — decided 11 Sep 2026 (SRS 1.220).** The developer has no
  registered business, and Google's organisation guide (*Verifying your Play Console developer
  account for organizations*, Oct 2024, p. 6) says **"Choose 'Organization' for any formal
  business entity. Otherwise, select 'Personal'."** Its sole-proprietor FAQ (p. 31) allows an
  organisation account only with a D-U-N-S number **and** an organisation registration document.
  **The account type cannot be changed after verification**, so this is decided once. The cost is
  the closed test, planned in *The release sequence* at the top of this file. The comparison that
  decided it, checked against Google's and D&B's pages that day:

  | | Personal | Organisation |
  |---|---|---|
  | Testing before production | **12 testers opted in for 14 consecutive days**, then *Apply for production* (review ≤ 7 days) — accounts created after 13 Nov 2023 | Not required |
  | Device check | Play Console mobile app on a physical Android 10+ phone | Not required |
  | Shown on Play | Developer name, legal name and country; an email | Organisation name and **address**, plus a **public email and phone**, both OTP-verified |
  | Documents | Government ID; the address must match the payments profile | Government ID of an authorised person **and** an organisation registration document from a government or business registry; the list shown is country-specific |
  | D-U-N-S | Not needed | **Required**, and its legal name and address must match the Google payments profile exactly — a mismatch leaves 28 days to fix or the account and apps are removed. **Limited attempts** to enter it |
  | Fee | US$25, once, card only, not refunded if identity fails | Same |

  **The route not taken**, for if a business is ever registered: a D-U-N-S number from
  `dnb.co.in/duns/get-a-duns` (free, up to 30 business days, "Google Developer" as the reason, name
  and address exactly as in the payments profile), then an organisation payments profile, a
  verified website and an organisation registration document. Because the account type is fixed,
  that would mean a **new** developer account and moving the app to it. The full steps are in
  commit `382d0dc`.

  **Also noted**: Android developer verification — identity for apps installed on certified
  devices, on Play or off it — starts **30 Sep 2026 in Brazil, Indonesia, Singapore and
  Thailand**, and globally in 2027. India is not in the first wave. Google's page does not say
  whether Play Console verification satisfies it; check when it reaches India.
- **FR-1101**: no advertising, no in-app purchase, no paid tier. Nothing in the app contradicts
  this; the listing must not either.
- **§8.6 / FR-002 / FR-1241**: the scopes are Sensitive, so OAuth verification is required before
  general availability. Expect a demo video, a published privacy policy and domain verification.
  §11 recommends submitting at the **start** of Phase 2, not the end: it is the most common cause
  of launch slippage. Until it completes, sign-in is limited: in *Testing*, to up to 100 listed
  test users whose grants expire after seven days; in *In production*, to 100 users in total,
  behind an unverified-app warning. The release sequence below chooses between them.
  **Submit against the final scope set, `.../auth/contacts` included.** Verification is granted
  per scope, so adding one afterwards is a second verification and a second wait — and SRS 1.87
  records what adding that scope does to an application already published: every existing user's
  grant is invalidated on the next authorization.
- **§13 decision 1**: "Latch" is a working title and needs trademark and Play Store name
  clearance.

---

## Before any of the above

### 0. Remove the `LatchCardOcr` diagnostic ⛔ **repository, and it is the one item here with teeth**

`CaptureActivity` logs the recognised lines of a business card and the classifier's reading of
them, under the tag `LatchCardOcr`. **It prints card content** — a third party's name, telephone
number and email address — which nothing else in this application does: `LatchTiming` carries an
enum, a boolean and a character count, and is structurally incapable of leaking. It exists to
build FR-1222's corpus from real cards (SRS 1.108), and it must be gone before submission.

It is `BuildConfig.DEBUG`-guarded, so a release build strips the branch and the strings with it,
and on that reading the risk is already zero. **It is listed here anyway**, because that guard is
one edit away from not being there and the guard is not what anyone would notice changing. The
check is a grep, and it takes a second:

```
grep -rn "LatchCardOcr" app/src/main
```

Nothing should match at submission. Removing it also retires the classifier-tuning workflow that
depends on it, so do it when FR-1222's corpus is closed and not before.

**Swept against the artifact on 6 Sep 2026, and it was clean** (SRS 1.156). The check above is a
grep over the source and is *meant* to match today; what was verified is the thing that actually
ships:

```
./gradlew :app:assembleRelease
strings -a app/build/outputs/apk/release/app-release-unsigned.apk | grep -E \
  "LatchCardOcr|LatchTiming|DebugDedupProbe|PROBE_DEDUP|INVALIDATE_TOKEN|PROBE_CLIENT_DATA"
```

All six absent. `LatchCardOcr` and `LatchTiming` are stripped by R8 with their guarded branches;
the three exported debug receivers never existed in a release build at all, because they live in
the `debug` source set rather than behind a constant — a component guarded by a constant is still
declared in the merged manifest, and an exported receiver that runs a Google query on request is
not something a release build should advertise.

**Re-run this sweep on the artifact you actually submit.** It is thirty seconds and it is the only
form of this check that a stray edit to a guard cannot pass.

**Re-swept on 11 Sep 2026, on the `.aab` as well as the APK, and the pattern is wider now.**
`DebugSavedCardsProbe` (tag `LatchSavedProbe`, action `PROBE_SAVED_CARDS`) was added that day
and is a third debug receiver, so the pattern must name it. This machine has no `strings`, so
the sweep greps the dex directly — and takes the **debug** APK first as a positive control,
because a pattern that matches nothing anywhere proves nothing:

```
P="LatchCardOcr|LatchTiming|LatchSavedProbe|DebugDedupProbe|DebugSavedCardsProbe|PROBE_DEDUP|PROBE_SAVED_CARDS|INVALIDATE_TOKEN|PROBE_CLIENT_DATA"
unzip -o app/build/outputs/apk/debug/app-debug.apk '*.dex' -d sweep/dbg
unzip -o app/build/outputs/bundle/release/app-release.aab 'base/dex/*' -d sweep/aab
grep -raoE "$P" sweep/dbg | wc -l    # must be non-zero: the control
grep -raoE "$P" sweep/aab | wc -l    # must be zero: what ships
```

Debug: all five tags present across 21 dex files. Release APK and `.aab`: **zero**.

**The per-device estimate moved, and is still an estimate.** The same subtraction item 4 warns
about, taken on the 11 Sep universal APK: **45.15 MB universal, 15.16 MB for arm64-v8a** — 4.08
MB of non-native content plus 11.07 MB of `lib/arm64-v8a`. Against 31 Aug's 13.97 MB that is
+1.19 MB across two weeks of card work, and 24.8 MB of headroom remains. **Superseded the same
day by item 4's measurement: 17.20 MB.** The estimate was 2.04 MB low, because Play's splits store
dex uncompressed and the universal APK does not.

---

## Knowingly unmet at submission

Requirements that are **`[MUST]` and not met**, recorded here with a date so that submission is a
decision taken with them in view rather than one taken without noticing them. This section is not
a to-do list; it is what a reader of this file has to have read before filing.

### FR-401, FR-402, FR-403 — the browser extension ⛔ **deferred 6 Sep 2026**

Not built, and deferred out of v1.0 deliberately. SRS 1.154 carries the decision in full.

Why it is not a slice that was skipped: §4.2 requires each client to parse and write
independently, so the extension must derive §7.2's `source_hash` and `item_key` itself — which
§7.2 names as the likeliest place three clients drift, and whose remedy SRS 1.52 settled as
sharing the *compiled* code rather than trusting conformance vectors. For a browser that means
Kotlin/JS across `:core-model`, `:parser`, `:recipes` and `:wire` — 40 files, ~4,340 lines,
`java.time` in 25 of them — plus `kotlinx-datetime` (which is not `java.time`) and a synchronous
SHA-256 that Web Crypto does not provide.

**It could not have shipped in this release in any case**, and that is what made deferring
cheap: FR-001's Chrome Extension OAuth client does not exist, and Chrome Web Store publication is
a gate of its own on top of §8.6's OAuth verification.

**What this obliges at submission.** Nothing in the Play or Store listing may describe or imply a
browser extension. §4.1's architecture diagram still shows three clients because the architecture
is unchanged — the extension is owed, not withdrawn — but the *product* ships as two.

### NFR-502 and FR-1222 — the two corpora

Both are `[MUST]` with numeric floors that only real-world data can close, and both are short.
NFR-502 asks for 300 real captured strings and stands at **113**; FR-1222 asks for 120 real
business cards and stands at **52** (SRS 1.207, 1.209 — 11 until the scanning run of 11 Sep
2026). Neither number may be met with invented entries — each
requirement says so, and FR-1222 says it citing NFR-502's own experience.

These are the developer's to settle before submission, and the options are to ship with the gap
recorded here, to hold the release until the data exists, or to amend the targets — which this
repository's conventions warn against, a target moved to meet it being no longer a measurement of
anything.

---

**The device pass.** `CLAUDE.md` carries a Device pass backlog listing, slice by slice, what a
human has to watch and what each failure would look like. Everything built in the autonomous
session of 1–2 September 2026 is JVM-verified and device-owed, including paths that have never
run once: all of `:data`'s SQLite, the FR-1004 webhook, the notification listener, and the `.ics`
export.

SRS §10 requires each acceptance scenario to pass on a physical device before sign-off. Several
are recorded as passing; the rest are in that backlog. **AC-07's second half cannot be closed on
Android at all** — it needs §4.1's Windows client to exist — and that is not a device gap.
