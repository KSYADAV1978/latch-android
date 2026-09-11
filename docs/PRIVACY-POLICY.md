# Privacy policy — DRAFT for the publisher's review

**This is a draft, not a published policy.** FR-1102 requires a publicly hosted policy, and
`docs/RELEASE.md` item 6 is where filing it is tracked. A privacy policy is a legal document: read
every sentence against the product before hosting it, and have it reviewed by whoever answers for
it. Everything between the two horizontal rules is the policy text. Everything after the second
rule is for the reviewer and is **not** to be published.

Placeholders are in square brackets: `[Publisher]`, `[contact address]`, `[effective date]`, and
the app name, which stays "Latch" only until §13 decision 1 clears it.

---

# Latch privacy policy

Effective [effective date]

Latch is published by [Publisher]. This policy covers the Latch app for Android and Latch for
Windows.

## The short version

- **We run no servers.** Latch has no backend, and nothing you capture ever reaches a computer
  we operate. We could not read your messages, your calendar or your contacts even if we wanted
  to, because none of it is ever sent to us.
- Latch reads text and images **on your device**. What leaves the device is the calendar event,
  to-do or contact you confirm, sent directly to **your own Google account**.
- There is one exception you would have to set up yourself: an optional **webhook**, off by
  default, that sends a copy of each saved item to an address you choose. It is described below.
- Latch contains **no advertising, no analytics and no crash reporting**. The one part of the app
  that sends usage data to anyone is Google's on-device text recognition, and what it sends is
  described below.

## What Latch does with your Google account

When you sign in, you grant Latch permission to use Google Calendar, Google Tasks and Google
Contacts on your behalf. Latch uses that permission only to do what you ask it to on screen:

| Google data | What Latch reads | What Latch writes |
|---|---|---|
| **Calendar** | Your list of calendars, so you can choose where entries go. Existing Latch entries, to tell whether something you capture is already saved or has moved | Events you confirm, and a calendar named "Latch" if you choose that option during setup |
| **Tasks** | Your task lists, and existing Latch to-dos, for the same two reasons | To-dos you confirm |
| **Contacts** (Android only) | Your contacts, **on your device**, to tell whether a business card you photographed belongs to someone already there | Contacts you confirm from a business card, and changes to an existing contact that you confirm field by field |
| **Your account address** | The email address of the account you signed in with, so Latch knows which account it is working in. It is kept on your device, encrypted | Nothing |

To recognise its own entries later, Latch writes a small record into each item it creates: a
one-way digest of the captured text, a digest used to recognise a rescheduled item, the time of
capture, the app it was captured from, and — where you applied one — the name of the recipe used.
On a contact, the record holds a digest of the card, a digest of the card's email address, the
time of capture and how it was captured. These records are digests and labels, not copies of your
content, and they stay in your own Google account.

**Latch never deletes a contact.** Google's permission for Contacts also allows deleting them,
because Google offers no narrower one, and the sign-in screen will tell you so. Latch does not
use that part of it. The one exception is Latch's own Undo button, offered for about ten seconds
after you save a contact: pressing it removes the contact Latch has just created. If you undo a
change Latch made to a contact you already had, Latch puts the old values back and deletes
nothing.

### Google API Services User Data Policy

Latch's use and transfer to any other app of information received from Google APIs will adhere
to the [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including the Limited Use requirements. Google user data is used only to provide the features
described in this policy. It is not sold, not used for advertising, not used to decide
creditworthiness, not read by any person, and not used to develop, improve or train artificial
intelligence or machine-learning models.

## What you capture

**Text and screenshots.** When you select text, share a message, share a screenshot or PDF, or
press the Windows shortcut, Latch reads the dates in it on your device. When you save, the item's
description includes the captured text, so you can see later where the entry came from — this is
in your own calendar and nowhere else. For a screenshot or PDF, only a short extract around the
dates goes into the description, not everything that was on the screen.

**Business cards** (Android only). When you photograph a card, the photograph is read on your
device and **deleted when you close the capture**. It is never stored anywhere else and never
sent anywhere — with one exception you choose card by card: if you tick "Use this photo as the
contact's picture", a cropped copy of the card is sent to Google Contacts as that contact's
picture. That box is off every time, and a picture is never sent for a contact saved while you are
offline.

A business card is someone else's information. Latch puts it only where you tell it to: in your
own Google Contacts.

**Notifications** (Android only, off unless you turn it on). If you switch on notification
capture, Latch reads incoming notifications from the apps you pick, in memory, to spot dates. It
never writes a notification's text to storage, never puts the message into the calendar entry it
offers, and never sends it to a webhook. The offer it shows you carries only a suggested title.
Latch remembers the names of the apps that have sent notifications, so you can choose which to
monitor; it does not keep what they said.

**The clipboard** (Windows). Latch reads the clipboard only when you press its shortcut or choose
"Capture copied text".

## What stays on your device

Latch keeps some things locally so that it works offline and so that nothing you capture is lost:

- **Captures waiting for you** in the Capture Inbox — things with no date, or that Latch was not
  sure about. They stay until you save or discard them.
- **Captures waiting for a connection**, held until they can be written to Google, then removed.
- **Your settings and recipes.**
- **A list of digests** of items Latch has written, used to avoid saving the same thing twice.
  These are one-way digests, not content.

On Android these are kept in the app's private storage and encrypted with a key held in the
Android Keystore. On Windows they are kept in your user profile and encrypted with Windows' own
data protection. **Latch turns off Android's cloud backup and device-to-device transfer** for its
data, so none of it is copied to a backup service.

**Sign-in.** On Android, Google Play services holds your sign-in and Latch stores no password or
token of its own. On Windows, Latch stores a Google sign-in token, encrypted with Windows' data
protection, so that you do not have to sign in every time.

## Who else receives data

**Google**, as your calendar, to-do and contacts provider, receives the items you save. Google's
own [privacy policy](https://policies.google.com/privacy) covers what it does with them.

**Google's text recognition.** On Android, Latch reads images with Google ML Kit, which runs on
your device. ML Kit sends Google information about the device (such as manufacturer, model and
Android version), the app (its name and version), and performance (such as how long recognition
took). According to Google, it does not send the image or the text it read. See
[ML Kit's data disclosure](https://developers.google.com/ml-kit/android-data-disclosure). Latch
for Windows uses the text recognition built into Windows and sends nothing.

**Google Play services** handles signing in on Android.

**A webhook, only if you set one up.** In Settings you can give Latch an https address to receive
a copy of each item you save. It is off by default and takes two separate steps to turn on: you
enter the address, and then you enable sending. When it is on, each save sends that address the
item's type, title, dates, location, notes, the recipe and group it belongs to, the app it came
from, and the time it was captured. The notes may contain the captured text. **That content
leaves your device and goes to whoever runs that address, under their terms, not ours.** Latch
never sends an image, never sends a notification capture, and tries once only: an item saved
while offline is never sent. The address is stored encrypted, because such addresses often
contain a password.

**Files you export.** "Export .ics" creates a calendar file and hands it to the app you choose to
share it with. What happens to it next is up to you and that app.

Latch sends nothing to anyone else. It does not sell data, share it with advertisers, or pass it
to data brokers.

## Deleting your data

**On Android**, Settings → "Disconnect and delete everything" revokes Latch's access to your
Google account and deletes everything Latch keeps on the device: the Inbox, waiting captures,
settings, recipes, the digest list and its encryption keys. Uninstalling Latch also deletes its
local data.

**On Windows**, "Sign out" removes the stored sign-in. To withdraw Latch's access to your Google
account completely, remove it at [myaccount.google.com/connections](https://myaccount.google.com/connections).
[Where Latch for Windows keeps its files, and whether uninstalling removes them, to be confirmed
before publication.]

**Items already in your Google account stay there.** They are yours: Latch will not delete them
when you disconnect, and you can remove them in Google Calendar, Google Tasks or Google Contacts
like anything else. Removing Latch's access at myaccount.google.com/connections also works at any
time, from any device.

## Security

Every connection Latch makes to Google uses HTTPS, and the Android app refuses to send any request
it composes to any address other than Google's own, or to follow a redirect elsewhere. The webhook
is the one exception, and it accepts only https addresses.

## Children

Latch is not directed at children under 13 and does not knowingly process their data.

## Changes to this policy

If this policy changes, the new version will be posted at this address with a new effective date.

## Contact

[Publisher], [contact address].

Latch is open source: [repository address]. Everything this policy says about how the app
handles data can be checked in its source code.

---

## For the reviewer — not part of the policy

### Sources, sentence by sentence

| Policy section | Rests on |
|---|---|
| No servers, nothing to the publisher | NFR-201, FR-1102, design principle 2 |
| Scopes and what each is used for | FR-002, FR-003, §5.11.2 |
| Account address kept, encrypted | `AccountDefaults.email` in `:data`'s `Repositories.kt`, under `KeystoreCipher`. **Not** a digest: only the preference *key*, `accountId`, is one. The first draft said otherwise and was corrected against the code |
| The contact-picture checkbox | `card_attach_photo` and `card_photo_not_held` in `strings.xml`, quoted verbatim |
| The metadata written into items | §7.2 (`source_hash`, `item_key`, `chain_id`, `captured_at`, `source_app`, `recipe`); FR-1207 for contacts |
| Never deletes a contact | FR-1102's own wording, FR-1210, FR-1232, v1.88 |
| Limited Use statement | Google API Services User Data Policy. The AI/ML sentence follows Google's OAuth privacy-policy guidance (`support.google.com/cloud/answer/13806988`), read 11 Sep 2026 |
| Source text in the description; the OCR extract | FR-805, FR-805b |
| Card photograph deleted; the contact-photo exception | FR-1211 (SRS 1.120), FR-1226 |
| Notifications | FR-208–FR-212, FR-210, FR-210a, FR-805a, NFR-206; `seenNotificationPackages` in `LatchSettings` |
| Local storage and encryption | FR-701, NFR-203, NFR-204, `KeystoreCipher`, DPAPI; `allowBackup="false"` in the manifest |
| ML Kit | AC-17's pass of 31 Aug 2026 (`firebaselogging.googleapis.com`); `docs/DEPENDENCIES.md`; Google's ML Kit data disclosure page, read 11 Sep 2026 |
| Webhook | FR-1004, FR-1004a, FR-1004b, FR-210a, NFR-203 |
| `.ics` export | FR-1005, `IcsExport.kt` |
| Deletion | NFR-205, `deleteAllLocalData` / `deleteEveryStore`; the Windows narrowing recorded in `CLAUDE.md` |
| Security | AC-17, `ALLOWED_HOSTS` |

### Three places where the policy and the record disagree, which the publisher must settle

1. **FR-1102's own wording is wrong, and this draft departs from it on purpose.** It requires the
   policy to say the card image is "never stored or transmitted (FR-1211)". FR-1226, built on
   6 Sep 2026, transmits a crop of it to Google Contacts when the user ticks the box, and names
   itself "the only case in which an image leaves the device". A policy that repeated FR-1102
   word for word would be false. The SRS should be amended so FR-1102 names FR-1226 as the
   exception.
2. **The store listing says "No analytics. Nothing measures how you use this."** ML Kit sends
   Google device, app and latency metrics after image captures — observed on 31 Aug 2026. This
   draft is honest about it. `docs/STORE-LISTING.md` is not, and a Play reviewer comparing the
   two would find the difference. The listing sentence should be narrowed before either is filed.
3. **The Data Safety form** (`docs/STORE-LISTING.md`) has no row for ML Kit's metrics. Google
   documents them as device and app information and performance data, collected for analytics.
   Whether that counts as data this app collects is Play's question to answer, and it must be
   answered consistently with this policy.

### Owed before this can be hosted

- **Publisher name and contact address.** An organisation account (FR-1105) shows these publicly
  anyway.
- **Where to host it.** Google's OAuth verification requires the policy on a **domain you have
  verified**, the same domain as the app's homepage (or one you own), at a URL different from the
  homepage. A GitHub Pages site on the public repository is the cheapest route. Whether
  `*.github.io` satisfies verification, or a registered domain is needed, is for the publisher to
  confirm in the Cloud console — the answer decides where the homepage lives too.
- **The Windows bracket** under "Deleting your data": confirm where the desktop client keeps its
  files (`%LOCALAPPDATA%\Latch`) and whether the MSIX uninstaller removes them, since FR-305's
  package is not built.
- **The app name**, after §13 decision 1.
- **The effective date**, which is the day it is hosted.
- **Legal review.** This draft was assembled from the specification and the code by someone who
  is not a lawyer, and the jurisdictions it must satisfy (India's DPDP Act among them, given
  §3.1's users) have not been considered at all.
