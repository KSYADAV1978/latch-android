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

**The manifest and the packaging script exist now** (SRS 1.153): `desktop/packaging/`.

```
powershell -ExecutionPolicy Bypass -File desktop\packaging\Package-Msix.ps1 `
    -PackageName 12345Publisher.Latch `
    -Publisher "CN=12345Publisher" `
    -Version 1.0.0.0 `
    -RuntimeImage desktop\build\jre
```

It refuses rather than approximating, and there are four things it will refuse for. Each is a
publisher item and none can be done from a checkout:

| It stops on | Because |
|---|---|
| No Windows SDK | `MakeAppx.exe` is not on this machine. Visual Studio Installer → Individual components → Windows 11 SDK, or the standalone download |
| No `-RuntimeImage` | A package depending on a JVM already on the machine is what `install-local.ps1` does for dogfooding and is explicitly not good enough to ship. The jlink invocation is in §2 above — **take the measurement while you are there** |
| No `desktop/packaging/Assets` | The Store needs the four tile images the manifest names. This repository has no artwork beyond the tray glyph, and generating it from nothing is not a thing a script should do |
| A placeholder left in the manifest | Three values are yours: the reserved package name, the certificate subject character for character, and the version. A `Publisher` that does not match the certificate produces a package that builds, signs, and then **fails to install with an error naming neither** |

**Nothing here generates a certificate**, deliberately, exactly as nothing generates the Android
keystore. Store submission signs the package for you — which is FR-305's own stated reason for
choosing the Store — and a self-signed certificate is only for installing a package locally to
test it.

### 3a. FR-301 and FR-306 are in that manifest, and one of them is not finished

Both are packaging declarations rather than application code, which is why they had no place to
live until there was a manifest.

**FR-301, launch at sign-in — done, and `Enabled="false"`.** *Optional* is the requirement's own
word: a startup task that begins enabled has made the choice for the user on a machine they have
just installed software on. Windows then offers it in Settings → Startup apps and in Task
Manager, which is a better answer than `install-local.ps1`'s Startup-folder shortcut because
those screens can see it.

**FR-306, the Windows share target — declared, and the receiving half is not built.** The
declaration puts Latch in the share flyout. Receiving the share is a `ShareTarget` **activation**
whose `ShareOperation` belongs to the activated process and is reachable only through WinRT — so
the PowerShell projection this client uses for FR-303 and NFR-203 cannot read it, being a
different process, and an activation is not a callable API you can poll for.

The shape that works is the one FR-302's hotkey sidecar already uses: a stub compiled by the
`csc.exe` inside Windows, activated by the package, handing the shared text to the running JVM.
It needs the SDK's reference metadata, so it is blocked on the same install as the packaging.

**Until that stub exists the declaration must not be submitted**, and the script strips it unless
`-WithShareTarget` is passed. Appearing in the share flyout and doing nothing is worse than being
absent — the same reason `ACTION_SEND_MULTIPLE` is deliberately not registered on Android
(FR-205's note): a filter the app cannot honour is a promise broken in front of the user.
FR-306 is `[SHOULD]`, and this is a `[SHOULD]` deferred with its reasoning rather than dropped.

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

~~Not built.~~ **Built, in the manifest — see §3a.** This entry was right that it is a packaging
decision rather than application code; what it did not know is that FR-306 is the same shape, and
both are in `desktop/packaging/AppxManifest.xml` now.
