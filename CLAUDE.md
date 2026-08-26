# latch-android

## What this is
The Android client of Latch: captures dates and deadlines from text anywhere on the phone,
parses them on device, and writes them to the user's Google Calendar and Google Tasks.
There is no backend, and no user content reaches any publisher server.

## Specification
The authoritative requirements are in `docs/SRS.md`. Requirements are numbered
(FR-nnn functional, NFR-nnn non-functional). When implementing, cite the
requirement ID in your summary so work can be traced back.

## Module structure
| Module | Type | Holds |
|---|---|---|
| `:app` | Android application | Capture entry points, first-run setup, Compose confirmation UI |
| `:core-model` | pure Kotlin (JVM) | Domain types from SRS §7.1 |
| `:parser` | pure Kotlin (JVM) | Date and time extraction, classification (FR-500 series) |
| `:recipes` | pure Kotlin (JVM) | Working-day arithmetic, recipe expansion (FR-600 series) |
| `:data` | Android library | Storage — Inbox, write queue and secret store contracts, account defaults persisted — and the Google API contracts plus their REST implementations. Every outbound request in the app originates here. No Play services: the OAuth grant lives in `:app` |

Dependencies point one way: `:app` → `:data`/`:parser`/`:recipes` → `:core-model`.
`:parser` and `:recipes` do not depend on each other; they exchange `:core-model` types.

## How to build and test
Requires JDK 17+ on `JAVA_HOME`. Android Studio's bundled JBR works:
`C:\Program Files\Android\Android Studio\jbr` (JDK 21). Gradle and the Android SDK
components download on first run; `local.properties` needs `sdk.dir` and is not committed.

```
./gradlew build                # everything: compile, unit tests, lint, debug + release APK
./gradlew :parser:test         # the parser corpus alone — seconds, no emulator
./gradlew :app:assembleDebug   # debug APK

# The launch canary. Needs a connected device and is NOT part of `build`, so a green
# build says nothing about whether the app starts — that gap is what it exists for.
./gradlew :app:connectedDebugAndroidTest
```

## Hard constraints
Do not violate these without asking first:
- **`:parser` and `:recipes` stay pure Kotlin.** No Android dependency, no third-party
  dependency. This is what makes FR-501 and NFR-502 enforceable by the build rather than by
  review, and it keeps the corpus running without a device.
- **Never invent a date** (design principle 1). No date found means an undated item, never
  "today". A missing year means the current year, never rolled forward — AC-04 depends on it.
- **Nothing leaves the device** except the finished Calendar/Tasks entry (design principle 2,
  NFR-201). The one exception is a user-configured FR-1004 webhook, off by default, never
  for notification-sourced captures (FR-210a), never in the write queue (FR-1004b).
- **No `MediaProjection`** (FR-217) and **no Accessibility Service** (FR-218).
- **Ask before adding a dependency** (NFR-501). Each one needs a written justification in
  `docs/DEPENDENCIES.md`, with its measured APK impact. Anything not listed there has not
  been justified.
- **All user-facing strings live in `app/src/main/res/values/strings.xml`** (NFR-402). The
  pure-Kotlin modules return data, never display text — see `ShiftResult` for how FR-606's
  "weekend skipped" message is left to the app to phrase.

## Conventions
- Kotlin, Compose with Material 3, `java.time` (available natively at minSdk 26, no desugaring).
- AGP 9 has built-in Kotlin support: do **not** apply `org.jetbrains.kotlin.android` to the
  Android modules. The Compose compiler plugin is still applied separately.
- Versions live in `gradle/libs.versions.toml`. Nothing is version-pinned in a module file.
- Comments explain decisions the code cannot state — why a heuristic leans the way it does,
  which requirement forces a shape. They do not restate the code.
- Parser rules take `ParseContext.now` and never read the clock, so every case is reproducible.

## Commits
Commit directly to `main`. This is a single-developer repository with no CI and no review
step, so a feature branch adds ceremony without adding safety — there is nothing for it to
gate. This overrides the default habit of branching before committing to the default branch.

Subject line in the present tense, imperative, one line. The body carries the reasoning:
which requirements the change serves, and any decision the code cannot state for itself.

## State of the build
Skeleton only. Working: the five-module structure, the parser (87-case corpus, all passing),
working-day arithmetic and recipe expansion, capture layers 1, 2 and 4 as far as the
confirmation screen, and first-run setup (FR-100 series) end to end.

Setup runs against real Google. `StubGoogle.kt` is gone. The NFR-501 question it was waiting
on is settled: `play-services-auth` for the OAuth grant and nothing else (+135 KB, measured),
and every API call hand-written REST over `HttpURLConnection` and the platform's `org.json`.
`GoogleAuthClient` in `:app` is the only class that touches Play services; `GoogleRest.kt` and
`GoogleHttp.kt` in `:data` are the calls.

Two things there are worth knowing before changing them. **No access token is persisted** —
Play services caches its own, they last an hour, and re-authorizing a granted scope set
returns one with no UI, so NFR-203 is satisfied by there being nothing at rest. And the
account's identity comes from `calendars/primary`, whose id is the user's email address,
because `AuthorizationClient` grants authorization and not identity; `AccountDefaults.accountId`
is a SHA-256 of that address, so the preference key stays opaque.

Account defaults persist. `EncryptedAccountDefaultsStore` in `:data` writes them to
app-private preferences, each record encrypted with AES-GCM under an Android Keystore key
(`KeystoreCipher`, same file) — no dependency, because that is all `androidx.security-crypto`
would have done and it is deprecated in favour of these platform APIs. The secret store
(NFR-203) should reuse `KeystoreCipher`. Setup therefore runs once: a second launch reads
the stored account and goes straight to the home screen.

## Verified on a device
SRS §10 requires each acceptance scenario to pass on a physical device before sign-off. This
is the running record. A green `./gradlew build` is not evidence for anything in this list —
the launch canary exists because the app once could not start at all with every unit test
passing.

Pixel 6 Pro, Android 17 (API 37), Play services 26.32.62, debug build.

| Scenario | Date | Result |
|---|---|---|
| **AC-16** — abandon setup at step 2 | 26 Aug 2026 | **Pass.** Signed in, reached step 2, backed out. No Latch calendar created in the Google account. FR-105 holds against the real `calendars.insert`, not just the stub. |

Also established in passing, none of it reachable from a JVM test: the OAuth grant works end
to end (so the debug SHA-1 is registered and the account is a test user), `KeystoreCipher`
encrypts against a real Keystore, a completed setup survives a cold start, and the stored
preference key is a hex digest rather than an email address.

Not yet run on a device: **AC-15** (setup under 60 seconds, calendar created only on finish),
**AC-09** (hidden calendar offered and actually ticked), **AC-17** (network monitor over a
full cycle), the FR-104 colour, and the consent-bridge cases — rotation and process death
with the consent screen up, which are the only part of this app with no automated cover at
all.

**AC-17 is no longer structural.** The app holds `INTERNET` now, so "no outbound request to
any non-Google endpoint" is a property of the code rather than of the manifest. It rests on
`ALLOWED_HOSTS` in `data/.../GoogleHttp.kt` — three exact hostnames, checked on the parsed
host, HTTPS only, redirects refused — which every request in the app goes through.
`GoogleEndpointGuardTest` tests it by name. Play services also calls Google for the grant;
that traffic is Google's and will show on a network monitor, but it is not ours to route.
Widening that set is an AC-17 decision, not a refactor.

Not built: the FR-800 write path (`events.insert`, FR-803 dedup, FR-806 queue, FR-807 undo),
the Capture Inbox (FR-700 series), Settings (FR-1000 series), OCR (FR-215) and the
notification listener (FR-208). FR-908 is not built either — the calendar list is not
refreshed on launch and a stored destination that has been deleted or has lost write access
is not yet detected; the `TODO` in `LatchApplication.onCreate` marks where it goes. NFR-205's
revoke is unbuilt, which is why `AuthClient.signOut` is still a no-op.

Sign-in works only on builds whose signing certificate is registered against the Android
OAuth client. There is no release signing config, so that means debug builds from a machine
whose debug keystore fingerprint is registered. All four FR-002 scopes are Sensitive, so
until OAuth verification (SRS §8.6) only test users on the consent screen can sign in.

FR-105 is load-bearing and structural, not a matter of care: `com.latch.android.setup` is
pure Kotlin, `SetupEffect.Commit` is the only effect that can reach `calendars.insert`, and
`SetupEvent.FinishRequested` is the only event that produces one. AC-15 and AC-16 are JVM
unit tests over that reducer. Keep it that way.
