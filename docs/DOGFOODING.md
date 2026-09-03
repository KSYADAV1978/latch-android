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
- **Configure a webhook and look at Settings after saving a capture.** There should now be a line
  saying what happened to the last delivery — accepted, refused with the status your endpoint
  gave, or unreachable. Until today the phone sent the request and threw the answer away, so a
  broken endpoint and a working one looked exactly the same.

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

---

# Latch on Windows — the short version

Everything above is about the phone. This part is the PC, and it is written to be read once.

## Starting it

It is **already installed**. Double-click **Latch** on your Desktop, or find it in the Start
menu. There is no window — look for the blue **L** in the system tray, next to the clock. If
you do not see it, click the `^` arrow; Windows hides new tray icons by default, and it is
worth dragging Latch out so it is always visible.

To install it again after a rebuild, or on another machine:

```
powershell -ExecutionPolicy Bypass -File desktop\install-local.ps1
```

**It starts with Windows.** That is FR-301, and it is already switched on — there is a Latch
shortcut in your Startup folder. To turn it off: Task Manager → **Startup apps** → Latch →
Disable. Or run the installer again *without* `-StartWithWindows`, which removes the entry.

One thing to know: the shortcut points at the Java runtime inside Android Studio. If you move
or uninstall Android Studio, Latch stops starting. A shipped version would carry its own
runtime; that is part of what the Microsoft Store build still owes.

## Capturing

The hotkey is **Ctrl + Shift + K**. It works anywhere in Windows.

1. **Select the text** — an email, a message, a web page, a PDF. Anywhere.
2. **Press Ctrl+Shift+K.** Latch copies the selection itself; you do not press Ctrl+C.
3. **A small window appears near your mouse pointer**, with the title, the date it found, and
   a tick box for each date.
4. **Check the title.** It is a text box — click in and correct it before saving. This is the
   one field OCR and the parser get wrong most often.
5. **Save.** Or **Esc** to throw it away, or **Export .ics** to get a calendar file in your
   Downloads folder instead of writing to Google.

Everything works from the keyboard: Tab moves, Space ticks, **Enter saves**, **Esc closes**.

**Screenshots work too.** Snip with Win+Shift+S, then press Ctrl+Shift+K — Latch reads the text
out of the image. Windows does this with whatever language packs are installed, and **this PC
has no Hindi pack**, so a Devanagari screenshot will capture on your phone and not here. Latch
says so rather than failing silently.

## The tray icon

Right-click it for the menu. Double-click it to capture without the hotkey.

| What it says | What it means |
|---|---|
| **Capture now (Ctrl+Shift+K)** | Same as the hotkey. |
| **Signed in as you@gmail.com** | Working. Click it to sign out of this PC — nothing in your Google account is touched. |
| **Sign in to Google…** | Not signed in. Captures cannot be saved until you do. |
| **No Google client configured** | Greyed out. The credentials file is missing; this should not happen on your machine. |
| **3 waiting to be written — retry now** | Latch could not reach Google, so it is holding 3 captures. Nothing is lost. Click to try immediately instead of waiting. |
| **2 stuck — retry now** | It gave up retrying those. **They are still there** and nothing is deleted — click to try again. |

Messages appear as balloons from the tray, because there is no window to put them in.
**"Saved to Latch"** means it is in your calendar. **"No connection. Held on this machine"**
means it is not, and Latch will write it when it can. Those two are deliberately different
sentences — if it says *held*, do not go looking in Google for it yet.

## The two questions Latch asks back

These now work the same on both machines, so what you learn on one holds on the other.

**"This looks like a reschedule."** Capture a message about something you have already saved,
with the date changed, and Latch does not write a second item. It shows you the item it found —
quoted by the title *as stored in your calendar*, with both weekdays worked out — and asks:

> This looks like a reschedule. "Project sync" is already saved for Wed 8 Sep 2027, 11:00.
> Move it to Thu 9 Sep 2027, 11:00?

**Update** moves the one you have. **Create new** writes a second, which is sometimes what you
want. **Close** does neither. Nothing is written until you choose, and there is no default —
pressing Enter will not pick one for you, because moving an item you already own is not
something a stray keystroke should decide.

Two things it deliberately will not do. It never offers this for a capture holding **several**
dates, because accepting would move one and quietly drop the rest. And if the message names the
date the item is *already* on — a forward, a quote-back — it says "Already saved" instead of
offering to move it to where it is.

**"Undo."** After a save the window stays up for ten seconds with a counting-down **Undo**
button, then closes itself. Undo removes what was just written. If the save was an *update*,
undo **puts the old date back** rather than deleting the item — it was yours before Latch
touched it. If several items were written and only some could be removed, it tells you how many,
because the rest is a manual job.

## What still differs between the two

Both clients now capture, parse, save, detect duplicates, offer reschedules, undo, hold work
offline and export `.ics`. These are the remaining differences. **Please do not report them as
defects** — but do say if one bites in a way this list does not describe.

- **No calendar picker on the PC.** Everything goes to the Latch calendar the phone made. That
  is deliberate — it is what makes the two machines recognise each other's captures.
- **No notification capture on the PC** — that is an Android capture layer.
- **The PC's queue only runs while Latch is running.** Queue something with no connection, quit,
  and it waits until you open Latch again. It is held, not lost — but the phone would have
  written it in the background.

## Two things worth doing early

**The test that was outstanding here is done, 3 Sep 2026.** You captured an email selection on
the PC on 18 Sept, corrected its title before saving, and then shared the same text into Latch
on the phone. The phone said **"Already saved. Nothing was written again."** and created
nothing — checked against the phone's own records, not the message. That closes the reverse
half of AC-07 as the criterion is written, and it also showed that **correcting a title does
not break recognition**, which is the part that would have been expensive to get wrong.

One thing was owed from it and is now done. The phone gave no sign of *which* check answered —
"Already saved" reads the same whether it came from the message hash or from the weaker
item-key match, and this project had been caught by that difference once already. A build that
says which went on the phone the same afternoon, you re-ran the capture, and it said the
message hash. **AC-07 is finished, in both directions and on the mechanism as well as the
outcome.** Thank you — that was the oldest open item in the whole record.

## The Inbox on the PC (new, 3 Sep 2026)

The PC now holds a doubtful capture instead of guessing at it, exactly as the phone does.

Capture something with **no date at all** — "Ask about the uniform order" — and press Save. It
should say **"Held in the Latch Inbox on this PC"**, and nothing should appear in your Google
account. Until today the PC wrote that as an undated to-do; if it still does, that is the
defect.

The tray then says **"1 waiting in the Inbox"**. Click it. You get a window with the capture in
it, why it is there, and five things you can do: give it a date, correct the title, save it,
snooze it a week, or throw it away. Nothing in that window has reached Google — the line at the
top says so, and it is worth checking in your account rather than taking its word.

Two things worth watching, because they are the ones that would be hardest to notice:

- **The count must disappear at zero.** Deal with everything and the tray entry should be gone,
  not "0 waiting".
- **A held date must not walk.** Capture "kal 4 baje meeting" today; it means tomorrow. Open the
  Inbox again in a couple of days — it must still say the day after you captured it, not the day
  after today. If that date has moved, stop and tell me.

## Settings on the PC (new, 3 Sep 2026)

Right-click the tray icon, **Settings**. It has the shortcut, how `05/09` is read, the default
event length, default reminders, how sure Latch has to be before it saves rather than holds, the
working week, and the time zone. Everything the PC does **not** have yet is listed at the bottom
of that window rather than left out, so you can see what is missing without guessing.

Two worth trying, because they are where this would fail quietly:

- **Set the confidence to 99 and capture "Kickoff 8 September 2027 at 9am".** It should go to
  the Inbox instead of Google. Set it back to 0 and it should save normally. Until today every
  one of these preferences was stored and then ignored by the capture path.
- **Change the shortcut to something another application already uses** — Ctrl+Shift+S is a good
  bet. Latch should say so and **put your old shortcut back**. If you end up with no working
  shortcut at all, that is the defect, and it is the one I would most want to hear about.

## The webhook on the PC (new, 3 Sep 2026, and never tried against a real endpoint)

This is the one part of Latch that deliberately sends something to an address that is not
Google, and it is **off** until you turn it on. It is in Settings, below everything else.

If you want to try it, a request bin (`webhook.site` and its like give you a URL in one click)
is the easiest target. Paste the `https://` address, press **Save endpoint**, then tick the box
and press **Save**. Those are two separate acts on purpose — pasting an address does not start
sending anything.

Then capture something and save it. Exactly one request should reach your bin, carrying the
title, the dates and the text of what you captured, and **nothing** should go anywhere else.

**No delivery has ever reached a real endpoint from either machine**, so this is the part of the
last three slices I would most like a result from. Three things worth checking:

- **Point it somewhere dead** — `https://127.0.0.1:9/hook` will do. Saving must still work
  normally, with no error on the capture window, and Undo must still work. Settings should say
  it could not be reached.
- **Save something with the Wi-Fi off**, then turn it back on. Google gets the item when the
  connection returns; your endpoint gets **nothing** for it. That is deliberate — there is one
  attempt, at the moment you save.
- **Look at the endpoint after saving it.** It should read `https://yourhost/••••••••`. The rest
  of the address is often a token, which is why it is hidden.

## Recipes on the PC (new, 3 Sep 2026)

Capture a dated meeting — "Project sync on 8 September 2026 at 11:00" — and below the date you
should now see a row of recipe buttons. Press **Meeting + prep**. You get three rows instead of
one, and the prep step should say the weekend was skipped, because 8 September 2026 is a Tuesday
and three working days back lands on the Thursday.

**Press "Just this one" to go back.** Picking a recipe must never be a one-way move — the popup
is a floating window and a click outside it closes it.

Untick a step before saving and only the rest should be written. Then look at the event in
Google Calendar: **does it have a 30-minute reminder?** That is the part I would most like
confirmed — reminders are read from the recipe step and no chain has ever been written from this
machine.

**Recipes** is on the tray menu. The eight that ship are there, plus anything you make. Edit one
of the shipped ones and it says "edited by you"; the button beside it then reads **Restore the
shipped version** rather than Delete, because that is what it does.

**And one worth trying on the PC**, because it is the newest thing and the most interesting to
get wrong: capture the line above with the date changed to **12 October**. You should get the
reschedule question rather than a second event. I have checked that the question appears against
your real calendar; what nobody has checked is what happens after you press Update.

**And when you are done trying it:** the two events named "Latch desktop first write" and
"Latch desktop second write" are mine, from testing. Delete them whenever you like.
