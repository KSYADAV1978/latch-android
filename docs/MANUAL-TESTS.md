# Manual test guide — what only a person can do

**Written 10 September 2026.** Everything an automated pass could close has been closed. This is
what is left, ordered by how much it costs you, cheapest first.

**How to read a row.** Each says *what to do*, *what passing looks like*, and — the part that
matters most — *what failure looks like*. This project's own rule is that a criterion verified
against a fixture too easy to fail it has not been verified, so every row names the failure it is
hunting rather than only the success.

**Two standing rules while testing.**

1. **The count is the instrument, not the screen.** "Saved" and "Already saved" can both appear
   over an account in a state you did not expect. Where a row says *check in Google*, check in
   Google.
2. **Do not verify before undoing.** FR-807's window is ten seconds and the measured margin is
   1.7 seconds. If a row asks you to undo, undo first and inspect afterwards.

---

## 0. Housekeeping before you start

- [x] ~~**Sign in again on Windows.**~~ Done 10 Sep — and the recovery itself confirmed SRS 1.192,
      1.195 and 1.200 in the wild (SRS 1.201).
- [ ] **Turn aeroplane mode off and Wi-Fi on.** A test session on 10 Sep left them off.
- [x] ~~**Delete the test fixtures**~~ Done 10 Sep — 5 events and 7 tasks removed by hand. Nothing
      from that session remains in the account.

---

## 1. Quick wins — minutes each, no setup

### 1.1 Open the .ics in another calendar application (FR-1005)

The only half of FR-1005 a device cannot settle on its own. The file itself is already verified
byte for byte: CRLF throughout, folded at 74 octets, no §7.2 metadata.

- **Do:** capture any dated text, press **Export .ics**, send the file to a calendar app or mail client.
- **Passes:** the receiving application recognises it as a calendar file and imports the right date.
- **Fails:** a file nothing will open, or one that imports at the wrong time.

### 1.2 The tray menu at your display scaling (Windows, SRS 1.82)

- **Do:** open the Latch tray menu at your usual 200% scaling.
- **Passes:** the text is the size of any other Windows menu, readable at arm's length.
- **Fails:** roughly half size — meaning Swing is not following the display scaling after all.

### 1.3 No box glyph in any tray label

- **Do:** read the tray menu. Then, with something stuck in the queue, read it again.
- **Passes:** the Settings and Recipes rows end in an ellipsis, not a box. The "N stuck - retry now"
  row is clean too.
- **Fails:** a box character. That second row is where a glyph defect would hide for months.

### 1.4 "Capture copied text" (Windows)

- **Do:** copy some dated text in any application, then press that tray row.
- **Passes:** the popup opens on that text.
- **Fails:** an empty capture, a stale clipboard — or, the one to watch for, a stray Ctrl+C landing
  in whatever window had focus.

### 1.5 Both mouse buttons open the tray menu

- **Passes:** left-click and right-click both open it, and there is exactly **one** menu.
- **Fails:** a second, tiny menu appearing beside it.

### 1.6 A second monitor at mixed DPI (Windows)

- **Passes:** the capture popup appears on the monitor the pointer is on, and the tray icon is sharp.

---

## 2. The offline rows — two are now done

**A correction, because this section said something false.** It claimed adb here is connected over
Wi-Fi, and that offline testing therefore could not be automated. **It is USB** —
`persist.sys.usb.config=adb`, no TCP adb port, and the link survived both Wi-Fi being disabled and
aeroplane mode on the retry. The disconnect that prompted that inference was coincidence, and the
disconnections through that day remain unexplained. Two of these rows were driven automatically as
a result and are struck below.

### ~~2.1 FR-806 — Retry now~~ — **DONE 10 Sep 2026**

Offered on the home screen beside *1 capture waiting to be saved to Google*, and tapping it while
still offline left the entry queued and lost nothing — the half that makes it a manual retry
rather than a button that only works when it was going to work anyway.

### ~~2.2 FR-806 — the immediate drain~~ — **DONE 10 Sep 2026**

On reconnection the drain ran within about 35 seconds — `Worker result SUCCESS` for
`WriteQueueWorker`, and the queue back to empty — rather than at the next backoff.

### ~~2.2a FR-806b — the offline banner~~ — **FIXED and RE-WATCHED 10 Sep 2026** (SRS 1.198, 1.199)

Offline with something queued, the home screen used to say *"Latch needs you to sign in to Google
again"* over a perfectly good grant, and offline that **Sign in** button could not work anyway. A
silent check made with no network now concludes nothing.

Re-run it if you like — aeroplane mode on, queue a capture, open the home screen — and the
pending count and **Retry now** should appear with **no sign-in banner**. It was watched on a
device the day it was fixed.

**Both directions are done.** The grant was revoked for real on 10 Sep and the banner appeared as
it should, with `PROBE_DEDUP` confirming the grant was actually dead first — Play services' own
`getToken() -> NEED_REMOTE_CONSENT` beside it. So the fix silences the false alarm without
silencing a real warning, which was the only risk it carried. Nothing further is owed here.
- **Known residual, not a failure:** on a **captive portal** the phone looks connected and is not,
  so the banner can still appear there. That case is recorded rather than engineered around.

### 2.3 FR-804 — the offline hold

- **Do:** save "Kickoff 8 September 2027 at 9am" online. Then aeroplane mode on, and capture
  "Kickoff 9 September 2027 at 9am".
- **Passes:** it goes to the **Inbox** with a reason about looking like a change, and **no queue
  entry** is made.
- **Fails:** a queue entry — which becomes a second item on reconnection, exactly the defect
  FR-804's note describes.

### 2.4 NFR-302's other two limbs

- **Do:** queue a capture offline, then (a) force-stop the app and (b) restart the phone.
- **Passes:** the capture is still queued and still writes when the network returns.

---

## 3. The notification listener — 15 rows, none automatable

It needs real permission prompts and real messages, and it is the layer with the heaviest
disclosure obligation in the product.

### 3.1 FR-209 — the disclosure is unavoidable

- **Passes:** the listener **cannot** be enabled from the Settings layer list; the only route is
  the disclosure screen, and it reads in full before any switch.

### 3.2 The two required statements

- **Passes:** that screen carries FR-805a's ("not the message") and FR-210a's ("nothing is sent to
  your endpoint"). Both are required *by name* by the requirements.

### 3.3 POST_NOTIFICATIONS — answer it both ways

- **Do:** turn the layer on. Run it twice, granting once and declining once.
- **Passes:** granted, offers appear. **Declined, the offer is invisible** — which is the failure
  the permission exists to prevent, so it is the half worth checking.

### 3.4 FR-212 — the app list

- **Passes:** an app appears in the picker only after it has notified, and ticking one is what
  makes its messages read. An **unticked** app produces **nothing**.

### 3.5 FR-211 — the offer

- **Do:** send yourself "PTM on Friday 12 September".
- **Passes:** a **low-priority** notification — no sound, no heads-up — whose text is the derived
  title and **never the message**.
- **Fails:** the message text appearing in the notification.

### 3.6 Silence where it belongs

- **Passes:** "ok see you" produces nothing; a media app's ongoing notification produces nothing;
  and Latch's own offer is **not** re-read into an endless chain.

### 3.7 AC-22 — FR-210 and NFR-206. **The most important row here.**

- **Do:** tap the offer, save, then inspect the item **in Google**.
- **Passes:** title and date present, the message text **nowhere** — not the description, not the
  notes, not the extended properties.
- **Fails:** any fragment of the message reaching the item.

### 3.8 The offer does not outlive the process

- **Do:** post an offer, force-stop Latch, tap the notification.
- **Passes:** it says the offer has gone rather than opening a capture.

### 3.9 AC-19 — no webhook for a notification capture

Needs section 4 set up first.

- **Passes:** with a webhook configured and enabled, confirming a notification capture sends
  **no** request to the endpoint.

### 3.10 NFR-104

- **Passes:** the listener is the only persistent service in
  `adb shell dumpsys activity services com.latch.android`.

---

## 4. The webhook — needs an HTTPS endpoint and a network monitor

FR-1004 is the only code in this app that deliberately contacts something that is not Google.

### 4.1 AC-18 — exactly one request

- **Passes:** configuring an endpoint, enabling it, and saving a capture produces **exactly one**
  request to it and **no other non-Google traffic**.

### 4.2 AC-20 — an unreachable endpoint costs nothing

- **Do:** point it at `https://127.0.0.1:9/hook` and save.
- **Passes:** the item still reaches Google, no blocking error appears, and **undo still works**.

### 4.3 AC-21 — nothing is sent for a queued capture

- **Do:** reachable endpoint, save offline, reconnect.
- **Passes:** Google gets the item from the queue and the endpoint receives **nothing**. The
  negative only counts if the webhook was configured and enabled throughout.

### 4.4 FR-1004b — the passive report

- **Passes:** Settings reports "accepted", "your endpoint answered 404", and "could not reach your
  endpoint" against a working bin, a 404 bin, and `127.0.0.1:9`. Seeing it **change** is worth more
  than any one reading — it shows the line is derived from the outcome rather than a constant.

---

## 5. Destructive and irreversible — use a throwaway account

### 5.1 NFR-205 — disconnect and delete everything

- **Do:** Settings, then disconnect.
- **Passes:** everything local is gone (Inbox empty, queue empty, recipes back to the shipped
  eight, settings back to defaults), the next launch runs setup, **Latch no longer appears** under
  myaccount.google.com third-party connections, and items already in Google Calendar and Tasks are
  **untouched**.
- **Fails:** anything of yours deleted in Google, or the grant still listed afterwards.

### 5.2 NFR-205 offline

- **Passes:** local data still goes, and the screen says the grant could not be removed and where
  to remove it by hand.

### 5.3 NFR-205 with a held card

- **Do:** capture a business card offline so it is held, then disconnect.
- **Passes:** the card is gone and does **not** appear in Google Contacts on the next reconnection.

---

## 6. Rows that need the clock

### 6.1 FR-515 across days

- **Do:** capture "kal 4 baje meeting", leave it a day, reopen the Inbox.
- **Passes:** the date still reads the day after the **capture**.
- **Fails:** a date that walked forward. That is a serious defect — the Inbox re-parses at the
  captured instant precisely so this cannot happen.

### 6.2 FR-705 — the review line

- **Do:** age an Inbox row past a fortnight (a clock change, or a seeded row).
- **Passes:** it says "Still want it?" and is **still there**.

---

## 7. Google Calendar changes only you should make

### 7.1 FR-908 — a rename

- **Do:** rename the Latch calendar in Google, relaunch Latch.
- **Passes:** the chip shows the new name **silently**.

### 7.2 FR-908 — the deletion

- **Do:** delete the destination calendar in Google, relaunch.
- **Passes:** the home screen says captures now go to your main calendar and names the one that
  went, and the next capture lands in the primary calendar.

### 7.3 FR-908 offline

- **Passes:** aeroplane mode, relaunch — **nothing changes and nothing is said.** This is the case
  the rule exists for and the easiest to get wrong: falling back because the phone was on a train
  would move your captures for a reason unrelated to your calendars.

---

## 8. The two corpora — the actual long pole to v1.0

Both are **[MUST]** with numeric floors, and **neither may be met with invented entries** — each
requirement says so, and FR-1222 says it citing NFR-502's own experience.

| | Floor | Now | Owed |
|---|---|---|---|
| **NFR-502** — real captured strings | 300 | 113 | **187** |
| **FR-1222** — real business cards | 120 | 11 | **109** |

- **Do:** dogfood. School circulars, bills, courier notifications, and cards you are actually
  handed. `docs/DOGFOODING.md` describes the workflow.
- **Note the ordering trap:** `LatchCardOcr` in a debug build is the tool that builds the card
  corpus, and it must be **removed before submission**. Close the corpus first.

---

## 9. Publisher steps — in this order

1. **Start OAuth verification now.** §11 says submit at the *start* of Phase 2. It gates general
   availability and is the commonest cause of launch slippage. **Submit against the final scope
   set, contacts included** — verification is granted per scope, and adding one later invalidates
   every existing user's grant on their next authorization (SRS 1.87).
2. **Create the upload keystore**, fill `keystore.properties`, back the key up **somewhere that is
   not this machine**, and enrol Play App Signing.
3. **Register the release SHA-1** against the Android OAuth client — and, if App Signing is
   enrolled, the **app signing key's** SHA-1 from the Play Console as well. That second fingerprint
   is the one that catches people out: sign-in fails on exactly the build you ship.
4. **Measure the bundle per device** — `bundleRelease`, then
   `bundletool get-size total --dimensions=ABI`. Record the arm64-v8a figure against NFR-103's
   40 MB budget. Today's number is an estimate, not a measurement.
5. **Three Play declarations** — FR-1102 privacy policy (must cover the webhook **and** contacts,
   including that Latch never deletes a contact), FR-1103 Data Safety (contacts counts as
   *collected* even though you never see it; the app holds **no** camera permission), and FR-1104
   notification access.
6. **Publish from an organisation account** (FR-1105 — a personal account triggers a closed-testing
   delay) and clear the name "Latch" for trademark and Play Store use.
7. **Re-run the artifact sweep** on the exact build you submit. Thirty seconds, and the only form
   of that check a stray edit to a guard cannot pass. See `docs/RELEASE.md` item 0.

---

## 9a. Two fixes from 10 September that need watching

### 9a.1 SRS 1.202 — "Sign in again" in the tray (**Windows, needs the desktop running**)

The tray had no way to re-consent while a token was stored, which stranded you when the grant was
revoked. There is now a **Sign in again** action beside **Sign out**.

- **Do:** reinstall the Windows client (`install-local.ps1`), open the tray while signed in.
- **Passes:** a **Sign in again** row sits beside **Sign out**, and pressing it opens the browser
  without signing you out first.
- **Fails:** it is missing, or it signs you out rather than re-consenting.
- The account line above it should still do **nothing** when pressed — that is deliberate.

### 9a.2 SRS 1.203 — FR-603's two "Done" buttons (**Android, needs the phone connected**)

The top-right **Done** collapsed a recipe card without saving. It now saves, exactly as the button
at the bottom does.

- **Do:** Home → Recipes → **Edit** on any recipe → change the **Name** → press the **top-right
  Done** (not the one at the bottom).
- **Passes:** the list shows the new name, and it survives closing and reopening the app.
- **Fails:** the name reverts — meaning the top-right control is still discarding.
- **Also worth one look:** open a card and collapse it *without* typing anything. Nothing should
  change, and the recipe must not start showing as "edited by you" — an untouched draft saves a
  value identical to the stored one, and that is what makes this fix free.
- **Known and deliberate:** there is now **no way to abandon an edit mid-card**. That was never a
  designed feature, only the toggle's side effect. If you want one it needs a control that says
  *Cancel*.

---

## 10. One decision waiting on you — not a test

~~**SRS 1.197 — FR-603's editor has two controls labelled "Done".**~~ **Decided and fixed at SRS
1.203**: the top-right one now saves too, so the two identically-labelled controls do the identical
thing. Relabelling it "Close" was the alternative and is the weaker fix — it corrects the word and
leaves a silent discard behind a control sitting where "Edit" was a second earlier. See §9a.2 for
the device row.

~~**SRS 1.200 — the Windows tray claims a signed-in state it has not got.**~~ **Decided and fixed
at SRS 1.202**: the claim stays (it is honest about what it knows — a token is stored) and the dead
end is closed by a **Sign in again** action beside Sign out, which is what Android has offered since
FR-806b. See §9a.1.
