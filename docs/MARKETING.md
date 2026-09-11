# Reaching users (FR-1101, FR-1105, FR-1107, §8.6)

This is the plan for getting Latch in front of people. It is written in the same shape as
`docs/RELEASE.md`: what can be done, what only the publisher can do, and — the part that matters
most here — **what must not be done yet, and why**.

The constraints are not marketing constraints. They come out of the specification, and three of
them will decide whether a launch works:

| Constraint | Where from | What it means |
|---|---|---|
| **OAuth verification gates sign-in** | §8.6, FR-002, FR-1241 | Until Google verifies the app, only accounts on the consent screen's test-user list can sign in **at all**. There are 100 slots |
| **No revenue, ever** | FR-1101, FR-1107 | No advertising, no in-app purchase, no paid tier. There is no budget and there will not be one |
| **The name is not settled** | §13 decision 1 | "Latch" is a working title awaiting trademark and store-name clearance |

---

## The rule that everything else follows from

**Do not tell anybody about Latch until they can sign in to it.**

This is not caution. Until OAuth verification completes, a stranger who installs Latch reaches the
Google consent screen and is refused, with a message that reads like the app is broken. Every
channel below is a channel you get **one** attempt at: a Hacker News thread, a subreddit post, a
Product Hunt launch. Spending one on an app nobody outside a hundred addresses can use converts an
audience into people who tried it once and concluded it did not work.

The sequence is therefore fixed, and each phase's gate is a real event rather than a date.

---

## Phase 0 — before any public use of the name

**Clear the name.** §13's first open decision. Marketing under a name you may have to abandon
means doing it twice and losing whatever the first attempt earned — the store listing, the domain,
the search results, the word of mouth.

Two checks, and they are different: a **trademark** search in the relevant classes, and a **store
name** check in Play and the Microsoft Store, where a name can be refused for being confusable
with something already listed. "Latch" is a common English word and there are existing products
using it; assume it is contested until shown otherwise.

**Register the homepage.** FR-1102's privacy policy needs a public URL and OAuth verification
needs a homepage on a domain you can prove you control. That is a Phase 0 item because
verification cannot start without it, and verification is the long pole.

---

## Phase 1 — the hundred, and what they are actually for

The test-user list holds 100 accounts. Treat those slots as the most valuable thing in this plan,
because they do three jobs at once and only one of them is testing.

**They close the two corpora.** NFR-502 wants 300 real captured strings and stands at 113;
FR-1222 wants 120 real business cards and stands at 11. Both explicitly forbid invented entries.
**Only real users produce real messages**, and `docs/RELEASE.md` records both as knowingly unmet.
A hundred people using this for a month is the only mechanism that closes either.

**They produce the device evidence.** `CLAUDE.md`'s device backlog is long and much of it needs a
phone that is not the developer's — a different Android version, a different keyboard, a different
set of apps that do or do not cooperate with `PROCESS_TEXT` (FR-204's help topic ships with **one**
entry because one is all anybody has checked).

**They satisfy FR-1105 if the account is personal.** A personal Play account triggers closed
testing — a panel of testers over a fixed period — which is this phase whether you wanted it or
not. An organisation account skips the requirement, and that is the reason to prefer one.

**Where to find a hundred people who are the actual users.** §3.1 names them: people receiving
school circulars, bills, courier and appointment notifications. That is not a developer audience
and should not be recruited from one.

- **Parent groups.** The canonical capture in this repository is a PTM notice. One parent in one
  school group who finds it useful is worth more than fifty developers who star the repository.
- **Small-business and professional contacts.** The business-card pillar exists because somebody
  came back from a conference with a pocket of cards. People who collect cards are a specific,
  findable group.
- **Anyone who already forwards messages to themselves as reminders.** That habit is the problem
  statement (§2.2) and the people with it recognise the product in one sentence.

**What to ask them for** is in `docs/DOGFOODING.md`, which is already written for this and asks
for the two things that make a report usable: what was captured verbatim, and what was expected.

---

## Phase 2 — verification, which is the long pole and starts early

§11 recommends submitting for OAuth verification at the **start** of Phase 2 rather than the end,
and records that it is the most common cause of launch slippage. Expect weeks.

`docs/RELEASE.md` carries what it needs: a published privacy policy, a homepage on a verified
domain, a demo video, and the final scope set — **`.../auth/contacts` included**, because
verification is granted per scope and adding one afterwards is a second verification and a second
wait.

**The demo video is a marketing asset and should be made as one.** Google requires it; it is also
the single clearest explanation of the product that will exist, and it costs nothing to shoot it
well enough to use afterwards. Show one capture end to end, in the app people actually receive
messages in, on a real phone. Thirty seconds.

---

## Phase 3 — launch, and what the story actually is

Latch's differentiator is not that it reads dates. It is **what it refuses to do**, and that is a
story rather than a feature list:

> It has no backend. There is no account to create, nothing you capture leaves your phone except
> to your own Google account, and no server the publisher operates ever sees a message. It refuses
> screen recording and it refuses the accessibility service — the two permissions that would make
> capture easier and would let it read everything on your screen for ever. It has no analytics of
> its own, because there is nowhere for a measurement to go.

*Corrected 11 Sep 2026 (SRS 1.217). This used to say "nothing is uploaded" and "no analytics"
unqualified. A ticked FR-1226 box uploads a card photo to the user's Google Contacts, and ML Kit
sends Google device and latency metrics after an image capture. The claim is only an asset while
it is true, and the narrowed one still is. Say "Google account", never "Gmail".*

That claim is unusually strong and — this is the part that makes it usable in public — it is
**auditable**. The repository is open, `AC-17` is a network-monitor check anybody can repeat, and
`docs/DEPENDENCIES.md` lists every third-party artifact with its measured cost. Very few
privacy claims in an app store come with a way to check them.

### Channels, in the order they should be attempted

**1. The people who are already using it.** Phase 1's hundred, told the day it goes public. They
are the only people who can say it works, and a launch with no users is a launch with no reviews.

**2. Indian Android communities.** `r/india`, `r/developersIndia`, `r/androidapps`. The Hinglish
handling — *kal*, *parso*, *agle hafte*, *4 baje* — is a genuine differentiator here and nowhere
else, and it is the thing to lead with for this audience rather than the privacy story.

**3. Hacker News, once, on the architecture.** The submission is not "I made a date-capture app";
it is the thing that is actually interesting to that audience — a two-client application with no
backend, where cross-device consistency is achieved by both clients writing to the same Google
account and deriving identical hashes from **shared compiled code**. `docs/SRS.md`'s §7.2 and the
revision history are, unusually, the strongest assets: this repository documents its own defects
and the reasoning behind every reversal. Lead with the record, not with the app.

**4. Product Hunt**, which suits a free privacy-focused utility and costs a day of preparation.

**5. Word of mouth in the groups the problem lives in.** Slowest, and the only one that compounds.

### What not to do

- **No paid acquisition.** FR-1107: no revenue. An install bought is an install that must be
  retained by a product that earns nothing.
- **No launch before verification.** See the rule at the top.
- **No claim the product does not keep.** The privacy story is only an asset while every sentence
  of it is true — and two of them need care in the listing: the FR-1004 webhook *does* send content
  off the device when a user configures one, and the business-card pillar writes a third party's
  details to Google Contacts. Both are declared in FR-1102's policy and FR-1103's Data Safety form,
  and the marketing copy has to agree with those, not with a simplification of them.
- **No mention of a browser extension.** FR-401 to FR-403 are deferred (SRS 1.154). §4.1's diagram
  shows three clients because the architecture is unchanged; the product ships as two.

---

## The Microsoft Store, which is a different job

FR-1106 records that developer registration there is currently free, and the Windows client is a
genuinely different product to describe: no phone, a global hotkey, region-snip OCR through
Windows' own recogniser. Its audience overlaps far less with the Play listing than it looks —
somebody who wants a hotkey that captures a date out of an email is not necessarily somebody who
photographs business cards.

`docs/RELEASE-WINDOWS.md` carries what it needs. Two things are worth knowing before writing a
word of copy: the client has **no destination picker** yet, so everything goes to the calendar the
phone created; and it needs a jlink runtime bundled, which is the unmeasured half of NFR-103's
80 MB budget.

---

## What success looks like, stated in advance

Written down before launch so that whatever happens can be read against it rather than
rationalised afterwards — the same discipline `CLAUDE.md` applies to a device pass.

| Measure | Why this one |
|---|---|
| **NFR-502 reaches 300 and FR-1222 reaches 120** | The two `[MUST]` requirements that only real users can close. If a launch does not move these, it did not reach real users |
| **A defect reported by somebody who is not the developer** | The whole argument for having users at all. This project has three documented cases of a load-bearing path that never ran while every test was green |
| **An app named in FR-204's help topic by a user** | It ships with one entry, because one is all anybody has checked. A second entry means somebody hit the problem and told you |

Installs are deliberately not on that list. A free app with no revenue and no analytics cannot
measure engagement and should not pretend to; what it can measure is whether the requirements that
were waiting on real users have stopped waiting.
