# Using Latch for real

Everything in this document was built between 1 and 2 September 2026 and **none of it has run on
a phone.** `./gradlew build` is green and 576 JVM tests pass; that is evidence about arithmetic
and record formats, and this project has three documented cases of a load-bearing path that had
never actually run while every test was green.

So the point of using it is not to admire it. It is to find the fourth.

`CLAUDE.md`'s **Device pass backlog** is the systematic version of this — slice by slice, the
exact checks and what each failure would look like. This is the other half: what to do with the
app in ordinary use, in the order that finds things fastest.

---

## Start here, before anything else

**Install, open, and capture one ordinary thing.**

Select "Kickoff 8 September 2027 at 9am" in any app, choose Latch from the selection toolbar, and
save it. Then look in Google Calendar.

That is the whole product in one gesture, and it is the path most likely to have been broken by
everything built since. If it does not work, stop and report it — nothing below will be
informative until it does.

Two things to watch while you do it: the sheet should appear in well under a second (**no
spinner at any point** for a text capture), and the Undo button should count down and work.

---

## Then, roughly in the order these will bite

### 1. Things that used to be impossible

These are the ones most worth trying, because until this week the app simply refused them.

- **Capture something with no date at all.** "Ask about the uniform order." The button should say
  **Add to Inbox**, not Save, and nothing should reach Google. Then open the Inbox from the home
  screen, give it a date, and save it. *(AC-03)*
- **Capture a past date.** "the order dated 12 March." It should become an **undated to-do**
  whose notes say "Originally dated 12 March 2026." — not an event, not a dated task. *(AC-04)*
- **Capture a time with no day.** "call at 4pm." The sheet should offer *Today / Tomorrow / In a
  week* chips **without you tapping anything first**, and `Other date…` should open a calendar.
- **Tap the EVENT badge.** It should flip to TASK, and there should be a line telling you what
  that costs *before* you tap it ("keeps the day but not the time").

### 2. Recipes

- Capture a dated meeting, then pick **Meeting + prep** from the chips below the date. You should
  get three rows; the prep task should say the weekend was skipped if it crossed one.
- Save it and look at the event in Google Calendar: **does it have a 30-minute reminder?** That
  code has never run. `RecipeStep.reminderOffsets` existed for weeks with nothing reading it.
- Untick a step before saving. Two items should appear, not three.
- Go to **Recipes** on the home screen and edit one of the built-ins. Then delete your edit — the
  shipped version should come back rather than the row disappearing.

### 3. The Inbox, over several days

This is the one that needs time rather than attention.

- Let a few low-confidence captures accumulate. Does the count on the home screen feel like
  information or like nagging? FR-704 says it must not nag, and that is a judgement only use can
  make.
- **Open a row you captured on a previous day.** If it said "tomorrow" when you captured it, it
  must still mean *the day after you captured it* — not the day after today. If that date has
  walked forward, that is a serious defect and worth reporting immediately.
- Snooze one. It should leave the list and the count, and come back a week later.

### 4. Offline

- Aeroplane mode, capture, reconnect. The sheet should say **"No connection"** rather than
  "Saved" — the distinction matters — and the item should appear when you reconnect.
- With something queued, try **Retry now** on the home screen.
- Capture the same message twice offline. Only **one** item should appear on reconnection.
- Save an event; edit its date in the message; aeroplane mode; capture the edited text. It should
  go to the **Inbox** saying it looks like a change, rather than quietly creating a second item.

### 5. Settings

- Change the date order and capture "Invoice 05/09". Then **cold-start** the app and do it again
  — the second is the case where the settings record is read while the capture is already opening.
- Change the working week to six days and re-run a recipe with a working-day step.
- Turn the Quick Settings tile off. It should leave the shade.
- Turn the share sheet off. Latch will **still appear** in the share sheet and say it is switched
  off — that is documented, not a defect.

### 6. Notifications (only if you want it)

Off by default, and the disclosure screen is worth reading as a user rather than as its author:
does it tell you what you would want to know before granting this?

- Turn it on, tick one messaging app, and have someone send you a message with a date in it.
- The offer notification must be **quiet** — no sound, no heads-up — and must **not** show the
  message text.
- Save one, then look at the item in Google: the message text must appear **nowhere**. *(AC-22)*

### 7. The odds

- **Export .ics** from a capture and open the file somewhere — mail it to yourself, or share it
  to Google Calendar. Does it actually open as a calendar file?
- Rename your Latch calendar in Google, then relaunch Latch. The chip should quietly show the new
  name.
- Delete your Latch calendar in Google, then relaunch. It should tell you captures now go to your
  main calendar.

### 8. Last, and only when you are ready to set up again

**Settings → Disconnect and delete.** It removes Latch's access to your Google account and
deletes everything local. Items already in Google Calendar and Tasks are left alone. Check
`myaccount.google.com` → third-party connections afterwards: Latch should be gone.

---

## Reporting a defect

The four lines below are what turn "it didn't work" into something diagnosable. The first two
matter most: **what you captured, verbatim**, and **what you expected**.

```
What I captured:
    (the exact text, or which fixture/screenshot — verbatim, including punctuation)

What I did:
    (which capture layer: selection toolbar / share sheet / tile / notification.
     Then: which buttons, in order. Online or offline. Cold start or not.)

What I saw:
    (on screen, and — separately — what actually appeared in Google Calendar or Tasks.
     These are different questions and the difference is usually where the defect is.)

What I expected:
    (one sentence)

Screenshot:
    (of the confirmation sheet, and of the item in Google if one was created)
```

### Why "what you captured, verbatim" is the first line

Every parser defect this project has found came down to characters. `March, 8, 2030` failed
because of the comma. `1 October` was recognised as `10ctober`. A paraphrase of what you captured
is not a reproduction of it.

### Why "on screen" and "in Google" are separate lines

Three of the worst defects in this project's history were cases where the screen said one thing
and the account held another: a duplicate the queue wrote while the screen said nothing, a title
the sheet displayed that differed from the one written, and a drain that emptied correctly while
writing a second copy. **An empty queue looks exactly like a drained one.** If something is
wrong, the item count in your account is usually the instrument, not the screen.

### If it is a date that came out wrong

Say what day it was when you captured it. Everything relative — "tomorrow", "kal", "next Monday"
— resolves against the moment of capture, so a report without that date cannot be reproduced.

### If it is an image or a PDF

Attach the file itself if you can, not a screenshot of it. Two renderings of the same sentence
recognise differently, which is recorded in the SRS and is exactly the kind of thing a screenshot
of a screenshot loses.

---

## What is already known to be missing

Not worth reporting; all of it is written up:

- **AC-07's second half** — capturing the same message on phone *and* PC — needs the Windows
  client, which does not exist.
- **Multi-image share.** Selecting several images at once will not offer Latch. Deliberate; the
  question of what one undo would mean over four screenshots has no answer yet.
- **Bottom-of-screenshot chrome.** A composer placeholder near the bottom of a screenshot may be
  read as content. The top band is dropped; the bottom deliberately is not, because that is where
  the most recent message sits.
- **A dangling word in a title.** "Trip from", "Yes. PTM on". Cosmetic, recorded.
- **FR-1004's webhook is untested against a real endpoint**, as is nearly everything in the last
  four slices. That is what this document is for.
