# Releasing the Windows client (FR-305, FR-1106, NFR-103)

FR-305 requires distribution through the Microsoft Store as an MSIX, and FR-1106 records that
developer registration there is currently free. This file is the operational form of that, in
the same shape as `docs/RELEASE.md`: what the repository does, and what only the publisher can.

## What the repository does

`./gradlew :desktop:installDist` produces a runnable image under
`desktop/build/install/desktop`. That is the application and its jars; it uses whatever JVM is
on the machine.

## What is owed, and cannot be done from a checkout

### 1. A Desktop OAuth client (FR-001)

The Windows client needs its own OAuth client id, of type **Desktop app**, in the same Google
Cloud project as the Android one. Nothing here can create it.

Put it in `%LOCALAPPDATA%\Latch\client.properties` (see `desktop/client.properties.example`)
or set `LATCH_GOOGLE_CLIENT_ID` in the environment. Its absence is a named state, not a build
failure — NFR-503 requires a clean checkout to build, and a machine that has never had a client
id must still run the tests.

The consent screen's test-user list governs who may sign in until the FR-002 scopes clear the
review §8.6 describes. **The same list already gates the Android client**, so adding the
desktop does not start that clock again — but a tester who can sign in on the phone can only
sign in here once this client id exists.

### 2. A runtime, and NFR-103's measurement

NFR-103 budgets the Windows MSIX at **80 MB**. Two halves:

| Part | Size | How it was arrived at |
|---|---|---|
| Application jars | **3.60 MB** | Measured, 2 Sep 2026, from `installDist`. Kotlin's standard library and coroutines are 3.1 MB of it; everything this project wrote is about 500 KB |
| Java runtime | **not measured** | Needs `jlink` against a JDK that ships `jmods`. Android Studio's bundled JBR has neither `jmods` nor `jpackage`, so the figure cannot be taken on the machine this was built on |

**Do not record an estimate in place of the measurement.** `docs/RELEASE.md` says the same
thing about the Android bundle for the same reason: the 28 Aug 2026 Android figure was arrived
at by subtraction, was wrong in the direction that matters, and had to be redone. A
`java.base` + `java.desktop` image is usually somewhere near 45 MB, which would leave headroom
— but "usually near" is not a measurement, and NFR-103 is a MUST.

To take it: install a full JDK 17+ (one with a `jmods` directory), then

```
jlink --add-modules java.base,java.desktop,jdk.httpserver,jdk.crypto.ec,jdk.localedata \
      --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
      --output build/jre
```

Those five modules are what the client actually uses: Swing and AWT for FR-304's popup and
FR-301's tray, `jdk.httpserver` for the OAuth loopback receiver, `jdk.crypto.ec` for TLS to
Google, and `jdk.localedata` so a date reads correctly outside the root locale.

### 3. MSIX packaging and the Store

`jpackage --type msi` produces an installer; an **MSIX** needs `MakeAppx.exe` from the Windows
SDK, which is not installed on the machine this was built on. Both are publisher steps.

Worth knowing before the first submission:

- **Store distribution is what provides code signing.** FR-305 records that as the reason for
  choosing it: the alternative is an annual certificate and a SmartScreen warning on every
  download until the certificate accrues reputation.
- **The application spawns `powershell.exe`** — three times, for FR-303's recogniser, FR-302's
  hotkey and NFR-203's DPAPI. That is ordinary for a desktop application and needs no special
  capability, but it is the kind of thing a Store reviewer asks about, and the answer is that
  each is a documented Windows API with no SDK binding reachable from a JVM.
- **FR-1102's privacy policy and FR-1103's data-safety declaration cover both clients.** The
  Windows client sends nothing anywhere except Google, through the same `ALLOWED_HOSTS` guard,
  so the existing statements hold — but the Store has its own form and it has not been filled
  in.

### 4. Launch at sign-in (FR-301)

FR-301 says "with optional launch at sign-in". Not built. On a Store-distributed MSIX the
supported mechanism is a `windows.startupTask` extension in the package manifest, which is a
packaging decision rather than application code — which is why it is recorded here rather than
in the backlog with the rest.
