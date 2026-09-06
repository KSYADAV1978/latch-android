# The Play listing, and the two declarations that go with it

Draft copy for the Google Play listing, and worked answers for the FR-1103 Data Safety form.

**Nothing here is filed for you.** `docs/RELEASE.md` is the gate; this is the wording that gate
needs, written now because the distance between building a capability and describing it in a
console is months, and a description written from memory of what the app does is how a listing
comes to contradict a privacy policy.

**Every claim below is checkable against the specification**, and it has to stay that way. The
privacy story is the product's argument (see `docs/MARKETING.md`), and it is only an asset while
every sentence of it is true.

---

## App name

**Not settled.** §13's first open decision: "Latch" is a working title awaiting trademark and Play
Store name clearance. Everything below says "Latch" as a placeholder. Clearing the name is Phase 0
of the marketing plan and it comes before any of this.

---

## Short description (80 characters)

> Turn any message into a calendar entry. On your phone, never on a server.

79 characters. It names the action and the differentiator, and it does not say "AI", which the app
deliberately is not — FR-501 forbids a cloud or LLM call.

---

## Full description

> **Latch turns the dates buried in your messages into calendar entries and to-dos.**
>
> A school circular says the PTM is on Friday. A bill says payment is due on the 20th. A courier
> says delivery is Tuesday. You read it, you mean to note it down, and you do not.
>
> Select the text and choose Latch — or share the message, a screenshot or a PDF. Latch reads the
> date, shows you what it found, and saves it to your own Google Calendar or Google Tasks when you
> say so.
>
> **It reads your messages on your phone.**
> There is no server. Nothing is uploaded, there is no account to create, and no computer we
> operate ever sees a word you capture. The only thing that leaves your phone is the finished
> calendar entry, sent to Google as you.
>
> **It does not guess.**
> No date found means an undated to-do, never "today". A missing year means this year, never next.
> Where Latch is not sure, it holds the capture and asks instead of quietly saving something wrong.
>
> **You check everything before it is saved.**
> Every date it found is a row you can untick. Every one has a badge you can flip between event
> and to-do, and a title you can correct. Saved something by mistake? Undo for ten seconds.
>
> **It understands how people actually write.**
> Written dates and numeric ones, ranges, times, and the Hinglish that fills Indian phones — kal,
> parso, agle hafte, 4 baje.
>
> **It notices what you have already saved.**
> Capture the same message twice and Latch writes nothing the second time. Capture it with the date
> changed and Latch offers to move what you already have, rather than leaving you with two.
>
> **Business cards become contacts.**
> Photograph a card and Latch reads it, shows you every field to check, and adds the contact to
> your own Google Contacts when you confirm. It never deletes a contact.
>
> **Recipes turn one date into a plan.**
> A reminder the week before, a follow-up the day after, prep three working days ahead — with
> weekends and holidays skipped. Eight are built in; change them or write your own.
>
> **What Latch refuses to do**
> • No screen recording and no accessibility service. Both would make capture easier and both would
>   let an app read everything on your screen, all the time.
> • No advertising, no in-app purchase, no paid tier.
> • No analytics. Nothing measures how you use this, because there is nowhere for a measurement to
>   go.
>
> Works offline — captures are held and written when you reconnect.
>
> Latch is open source. Everything above can be checked rather than taken on trust.

### Two sentences that are deliberately not in it

Both are true, both are declared in the privacy policy and the Data Safety form, and both are the
kind of thing a listing gets wrong by simplifying:

- **The webhook** (FR-1004) does send captured content to an address outside Google — to an
  endpoint *the user configures themselves*, off by default, requiring two separate deliberate
  acts to enable. It is an advanced feature that would need a paragraph to describe honestly, and
  a listing that says "nothing leaves your device" without it would be false. The copy above says
  *no computer we operate* and *the only thing that leaves your phone is the finished entry*,
  which stays true; if a reviewer asks, the answer is FR-1004 and its Settings screen.
- **A business card is somebody else's data.** The copy says Latch adds it to *your own* Google
  Contacts and never deletes one, which is the whole of what FR-1226 and FR-1214 promise.

---

## FR-1103 — the Data Safety form, answered

`docs/RELEASE.md` warns that this form is the one most likely to be filled in wrongly by analogy
with the rest of the app. The trap is a single distinction, and it decides four answers:

> **Google's form asks about data that leaves the device — not about data that reaches the
> publisher.** Latch's whole design is that the publisher receives nothing. That is *not* the
> question being asked. Calendar entries, to-dos and contacts all leave the device, so they are
> **collected** in Google's sense, even though we never see them.

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** | See the note above. Answering "no" because the publisher receives nothing is the mistake this table exists to prevent |
| **Calendar events** — collected? | Yes | Written to the user's Google Calendar |
| Calendar events — shared with third parties? | **No** | Google is the user's own account, not a third party we transfer to |
| Calendar events — processed ephemerally? | No | It persists, in the user's own account |
| Calendar events — required or optional? | **Required** | It is the product |
| Calendar events — purpose | App functionality | Nothing else. No analytics, no advertising, no personalisation |
| **Contacts** — collected? | **Yes** | §5.11's business-card pillar writes to Google Contacts. This is the row most likely to be missed |
| Contacts — shared? | No | As above |
| Contacts — required or optional? | **Optional** | The card pillar is a feature the user chooses; the app works entirely without it |
| **Photos** — collected? | **No**, and be ready to explain | FR-1211: a card photograph is deleted when the capture window closes, and never reaches storage that outlives it. FR-1226's contact picture is different and **is** sent — declare it under Photos if the user has ticked that box, since it goes to Google Contacts. Read FR-1226 and FR-1211 before answering, not this table alone |
| **Messages / other in-app text** — collected? | **No**, with one exception to declare | Captured text is parsed on device and never transmitted, *except* that FR-805's source text is written into the created item's description, which is the user's own calendar. The FR-1004 webhook is the case where it genuinely leaves to a third party — declare it, and say it is user-configured and off by default |
| Is data encrypted in transit? | **Yes** | HTTPS only. `ALLOWED_HOSTS` refuses anything else and refuses redirects (AC-17) |
| Can users request data deletion? | **Yes** | NFR-205: Settings → disconnect and delete removes every local store and revokes the Google grant. Items already in the user's calendar are deliberately left alone, because they are theirs |

**The camera is not a permission this app holds.** FR-1201a delegates to the camera application
through `ACTION_IMAGE_CAPTURE`, and the installed APK requests no `CAMERA` at all — verified on a
device against `dumpsys package`, not against the source. Nothing on this form should assert camera
access.

**Notification access needs its own justification**, which is FR-1104 and is a separate Console
declaration rather than a row on this form. The in-app prominent disclosure it also requires is
built and is unavoidable before the permission can be requested.

---

## FR-1102 — what the privacy policy must contain

Not drafted here, because a privacy policy is a legal document and should not be assembled from a
checklist by somebody who is not writing it. What `docs/RELEASE.md` requires it to state:

1. That no user content reaches publisher servers, and that there are no publisher servers.
2. **The FR-1004 webhook**: that the capability exists, is disabled by default, sends to an
   endpoint the user chooses, and that content sent to it leaves the device.
3. **Contacts**, in three sentences: that Latch creates contacts in the user's own Google Contacts;
   that the data written is a third party's, off a card the user photographed or was handed; and —
   verbatim, because the app tells the user this on the card sheet and a policy that contradicted
   it would be worse than saying nothing — that **Latch never deletes a contact**.
4. That a card image is **not stored**: FR-1211 deletes it when the capture closes. The exception
   is FR-1226's contact picture, which the user ticks a box to send.
5. Notification access, if enabled: what is read, that the message text is never written into the
   created item (FR-805a), and that it is never sent to a webhook (FR-210a).

---

## Screenshots

Play wants at least two; take four, and take them on the device rather than in an emulator.

1. **The confirmation sheet on a real message** — a multi-date capture with its checkboxes,
   badges and date lines. This is the product.
2. **The item in Google Calendar afterwards**, so the outcome is visible and not just the promise.
3. **The card sheet**, with the thumbnail of what the reader actually saw above the fields.
4. **Settings**, showing the layer toggles — it is where the refusals become visible as choices.

Do not screenshot a real person's message or a real card. The rule in `.gitignore` about the
business-card corpus applies here with more force: a store listing is more public than a
repository.
