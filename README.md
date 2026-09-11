# Latch

Latch captures dates and deadlines from text anywhere on your phone or PC, reads them on the
device, and writes them to **your own** Google Calendar and Google Tasks. It also reads a business
card and turns it into a contact in your own Google Contacts.

There is no backend. There is no account to create. Nothing you capture reaches any server the
publisher operates — the only thing that leaves the device is the finished calendar entry, to-do
or contact, sent to Google as you.

> **Status: pre-release.** The Android and Windows clients are built and in daily use by their
> author. Neither is published yet, and sign-in currently works only for accounts on the OAuth
> consent screen's test-user list — see [Signing in](#signing-in) below, which is the first thing
> to read if you are thinking of running this.

## What it does

**Capture a date from wherever you are reading it.** Select text and choose Latch from the
selection toolbar; share a message, a screenshot or a PDF to Latch; tap the Quick Settings tile;
or let Latch read a notification you have allowed it to see. On Windows, select anything at all
and press `Ctrl+Shift+K`.

**Latch reads it on the device.** No cloud, no LLM, no network call to understand your message.
The parser handles written and numeric dates, ranges, times, relative expressions and romanised
Hindi — *kal*, *parso*, *agle hafte*, *4 baje*.

**You confirm before anything is saved.** Every date it found is a row you can tick or untick,
with its own event-or-to-do badge you can flip and its own title you can correct. A save can be
undone for ten seconds.

**It does not invent dates.** No date found means an undated to-do, never "today". A missing year
means the current year, never the next one. Where Latch is unsure it holds the capture in an Inbox
and asks, rather than guessing.

**It notices what you have already saved.** Capture the same message twice — on the phone and then
on the PC — and Latch writes nothing the second time. Capture it with the date changed and it
offers to move the item you have rather than creating a second one.

**Business cards** (Android). Photograph a card, or share a picture of one: Latch decodes a
contact QR code if there is one and otherwise reads the card, shows you every field to check, and
creates the contact only when you say so. Latch never deletes a contact.

**Recipes** turn one captured date into a chain — a reminder the week before, a follow-up the day
after — with working days and holidays taken into account.

## What it deliberately does not do

- **No screen recording and no accessibility service.** Both would make capture easier and both
  are refused: they are the two permissions that would let an app read everything on your screen
  all the time.
- **No advertising, no in-app purchase, no paid tier.**
- **No analytics and no tracking.** Latch has nowhere to send a measurement. The one exception is
  not ours: on Android, Google's ML Kit text recogniser reports device, app and performance details
  to Google after reading an image — never the image or the text it read. See
  [ML Kit's data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).
- **Nothing you capture leaves the device** except to your own Google account. The one exception is
  a webhook you configure yourself, which is off by default, cannot be enabled by accident, and is
  never used for anything read out of a notification.

## Clients

| Client | State |
|---|---|
| **Android** | Built. Capture by selection, share, tile and notification; images and PDFs read on device; business cards; Inbox, recipes, settings, offline queue, `.ics` export |
| **Windows** | Built. Tray application, global hotkey, region-snip OCR through Windows' own recogniser, Inbox, recipes, settings, offline queue |
| **Browser extension** | **Not built, and deferred out of v1.0.** The requirements (FR-401 to FR-403) stand; the reasoning is in `docs/SRS.md` revision 1.154 and `docs/RELEASE.md` |

The two clients share their parser, their working-day arithmetic and — most importantly — the code
that derives what Google receives, so a message captured on one is recognised by the other as the
same message. That sharing is the design decision the architecture rests on rather than a
convenience.

## Building it

Requires **JDK 17 or later**. Android Studio's bundled JBR works.

```
export JAVA_HOME="/path/to/jbr"          # on Windows: C:\Program Files\Android\Android Studio\jbr

./gradlew build                          # everything: compile, unit tests, lint, both APKs
./gradlew :parser:test                   # the parser corpus alone - seconds, no device
./gradlew :app:assembleDebug             # Android debug APK
./gradlew :desktop:installDist           # the Windows client
```

A clean checkout builds with no configuration. `local.properties` needs `sdk.dir` and is not
committed; Gradle and the Android SDK components download on first run.

**The instrumented suite needs a connected device and is not part of `build`:**

```
./gradlew :app:connectedDebugAndroidTest
```

It writes to the app's real local stores, so run it on a device you do not mind clearing.

## Signing in

Latch talks to Google as you, using OAuth. Two things follow, and both will stop you if you do not
know about them.

**The scopes are classified Sensitive by Google**, so until the app completes OAuth verification
only accounts added to the consent screen's test-user list can sign in at all. A build installed
by anyone else will reach the sign-in screen and be refused.

**Sign-in only works on a build whose signing certificate is registered** against the Android
OAuth client in the Google Cloud project. In practice that means debug builds from a machine whose
debug keystore fingerprint has been registered.

Neither is a defect and neither can be worked around from a checkout. `docs/RELEASE.md` and
`docs/RELEASE-WINDOWS.md` carry what has to happen before either client can be installed by
somebody who is not its author.

## Where the documentation is

This repository is unusually heavily documented, on purpose: the reasoning behind a decision is
exactly what a later reader cannot recover from the code.

| File | What it is |
|---|---|
| `docs/SRS.md` | The authoritative specification. Every requirement is numbered and every decision that changed one is in the revision table with its reasoning |
| `CLAUDE.md` | The running engineering record: module structure, hard constraints, what has been verified on a physical device and what has not |
| `docs/RELEASE.md` | What has to be true before a Play submission, and what is knowingly unmet |
| `docs/RELEASE-WINDOWS.md` | The same for the Microsoft Store |
| `docs/DEPENDENCIES.md` | Every third-party dependency, with its measured APK cost and why it was accepted or refused |
| `docs/DOGFOODING.md` | How to use it for real, and how to report what goes wrong |
| `docs/MARKETING.md` | How this is meant to reach users, and what must not happen before it can |
| `docs/STORE-LISTING.md` | Draft listing copy, and worked answers for Play's Data Safety form |

## A note on the test data

The parser is tested against real captured messages, because a parser tested only against invented
examples tests that the rules do what their author meant rather than that they work on what people
actually write. Where a fixture is synthetic — several of the screenshots are rendered rather than
photographed — it says so.

**The business-card corpus is deliberately not here.** Every row of it is a card somebody actually
handed over, carrying a real person's name, direct line and work address; they consented to none of
it, and this app's own card sheet tells its user that a card is a third party's data. So the corpus
lives only on the developer's machine, `card_corpus.tsv.example` shows the format, and the tests
that read it report *skipped* rather than passing when it is absent.

## Licence

Apache License 2.0 — see [LICENSE](LICENSE). "Latch" is a working title and is not licensed as a
trademark; see the SRS's open decisions.
