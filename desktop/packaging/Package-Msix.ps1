<#
.SYNOPSIS
    Builds the FR-305 MSIX package for the Windows client.

.DESCRIPTION
    FR-305 requires distribution through the Microsoft Store as an MSIX. This script assembles
    the package layout, substitutes the publisher's values into the manifest and calls
    `MakeAppx.exe` -- optionally `signtool.exe` -- from the Windows SDK.

    It does three things this repository is careful about elsewhere.

    **It refuses rather than approximating.** With no Windows SDK, no runtime image or a
    placeholder left in the manifest, it stops and says which. `docs/RELEASE.md` records what an
    estimate recorded in place of a measurement cost on the Android bundle: it was wrong in the
    direction that mattered and had to be redone.

    **It does not generate a certificate**, exactly as nothing in this repository generates the
    Android keystore. Store distribution is what provides code signing and is FR-305's stated
    reason for choosing the Store; a self-signed certificate is for testing an install locally
    and is the publisher's to make.

    **It strips FR-306's share target unless asked.** Declaring it puts Latch in the Windows
    share flyout, and the receiving half is not built (SRS 1.153) -- so a submitted package
    carrying the declaration would offer the user something that does nothing, which is the
    failure FR-205's note refuses on Android for the same reason.

.PARAMETER PackageName
    The Store-reserved package identity name, e.g. `12345Publisher.Latch`.

.PARAMETER Publisher
    The signing certificate's subject, character for character, e.g. `CN=...`. A mismatch here
    produces a package that builds, signs, and then fails to install with an error naming
    neither.

.PARAMETER Version
    Four-part, e.g. `1.0.0.0`. The Store requires the last part to be 0.

.PARAMETER RuntimeImage
    A `jlink` image directory to bundle. NFR-103 budgets the MSIX at 80 MB and this is the half
    that has never been measured -- see docs/RELEASE-WINDOWS.md for the exact jlink invocation.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File desktop\packaging\Package-Msix.ps1 `
        -PackageName 12345Publisher.Latch `
        -Publisher "CN=12345Publisher" `
        -Version 1.0.0.0 `
        -RuntimeImage desktop\build\jre
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $PackageName,
    [Parameter(Mandatory = $true)] [string] $Publisher,
    [Parameter(Mandatory = $true)] [string] $Version,
    [string] $PublisherDisplayName = 'Latch',
    [string] $RuntimeImage,
    # FR-306. Off by default; see the note in AppxManifest.xml and SRS 1.153.
    [switch] $WithShareTarget,
    # Signs with an existing certificate. Nothing here creates one.
    [string] $CertificatePath,
    [string] $CertificatePassword,
    [string] $OutputPath = 'desktop/build/msix'
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
$packaging = Join-Path $repo 'desktop\packaging'
$layout = Join-Path $repo 'desktop\build\msix-layout'
$outDir = Join-Path $repo $OutputPath

# ---- 1. The SDK ------------------------------------------------------------------------------
#
# Looked for rather than assumed, and named in the failure. The machine this was written on has
# no `Windows Kits` directory at all, which is why this check exists and why FR-305 is still
# owed rather than merely untested.

function Find-SdkTool([string] $tool) {
    $roots = @(
        "${env:ProgramFiles(x86)}\Windows Kits\10\bin",
        "$env:ProgramFiles\Windows Kits\10\bin"
    ) | Where-Object { Test-Path $_ }

    foreach ($root in $roots) {
        $found = Get-ChildItem -Path $root -Filter $tool -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match '\\x64\\' } |
            Sort-Object FullName -Descending |
            Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

$makeAppx = Find-SdkTool 'makeappx.exe'
if (-not $makeAppx) {
    throw @'
The Windows SDK is not installed, so no MSIX can be built here.

MakeAppx.exe ships with the Windows 10/11 SDK. Install it either through Visual Studio
Installer ("Windows 11 SDK" under Individual components) or standalone from
https://developer.microsoft.com/windows/downloads/windows-sdk/ and run this again.

This is a publisher step and docs/RELEASE-WINDOWS.md records it as one. Nothing is estimated
in its place.
'@
}
Write-Host "MakeAppx: $makeAppx"

# ---- 2. The application, and the runtime NFR-103 has never had measured -----------------------

$appImage = Join-Path $repo 'desktop\build\install\desktop'
if (-not (Test-Path $appImage)) {
    throw "No built application at $appImage. Run: ./gradlew :desktop:installDist"
}

if (-not $RuntimeImage) {
    throw @'
-RuntimeImage was not given, so this package would depend on a JVM already on the machine.

That is what desktop\install-local.ps1 does for dogfooding and it is explicitly NOT good
enough to ship: the shortcut there points at the runtime inside Android Studio, and moving it
breaks Latch. NFR-103 budgets the MSIX at 80 MB, of which the runtime is the unmeasured half.

docs/RELEASE-WINDOWS.md carries the exact jlink invocation and the five modules this client
actually uses. Take the measurement while you are there; do not estimate it.
'@
}
if (-not (Test-Path $RuntimeImage)) { throw "No runtime image at $RuntimeImage." }

# ---- 3. The layout ---------------------------------------------------------------------------

if (Test-Path $layout) { Remove-Item -Recurse -Force $layout }
New-Item -ItemType Directory -Force -Path $layout | Out-Null

Copy-Item -Recurse -Path (Join-Path $appImage '*') -Destination $layout
Copy-Item -Recurse -Path $RuntimeImage -Destination (Join-Path $layout 'runtime')

$assets = Join-Path $packaging 'Assets'
if (Test-Path $assets) {
    Copy-Item -Recurse -Path $assets -Destination (Join-Path $layout 'Assets')
} else {
    throw @'
desktop\packaging\Assets is missing.

The Store requires the tile images the manifest names -- Square44x44Logo, Square150x150Logo,
Wide310x150Logo and StoreLogo. They are artwork, not code, and this repository has none: it has
never had an icon beyond the tray glyph. That is a publisher item and belongs on the
docs/RELEASE-WINDOWS.md gate rather than being generated here from nothing.
'@
}

# ---- 4. The manifest -------------------------------------------------------------------------

$manifest = Get-Content (Join-Path $packaging 'AppxManifest.xml') -Raw

if (-not $WithShareTarget) {
    # FR-306: remove the declaration rather than shipping one the application cannot honour.
    $manifest = [regex]::Replace(
        $manifest,
        '(?s)\s*<uap:Extension Category="windows\.shareTarget">.*?</uap:Extension>',
        ''
    )
    Write-Host 'FR-306 share target: omitted (pass -WithShareTarget once the activation stub exists).'
} else {
    Write-Warning @'
FR-306's share target is declared, and its receiving half is not built (SRS 1.153).
Latch will appear in the Windows share flyout and the share will go nowhere.
Do not submit a package built this way.
'@
}

$manifest = $manifest.
    Replace('__PACKAGE_NAME__', $PackageName).
    Replace('__PUBLISHER__', $Publisher).
    Replace('__VERSION__', $Version).
    Replace('__PUBLISHER_DISPLAY_NAME__', $PublisherDisplayName)

if ($manifest -match '__[A-Z_]+__') {
    throw "A placeholder is still in the manifest: $($Matches[0]). A manifest whose Publisher does not match the certificate produces a package that builds, signs and then fails to install."
}

Set-Content -Path (Join-Path $layout 'AppxManifest.xml') -Value $manifest -Encoding utf8

# ---- 5. Pack, and optionally sign ------------------------------------------------------------

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$msix = Join-Path $outDir "Latch-$Version.msix"

& $makeAppx pack /d $layout /p $msix /o
if ($LASTEXITCODE -ne 0) { throw "MakeAppx failed with exit code $LASTEXITCODE." }
Write-Host "Packed: $msix"

$size = (Get-Item $msix).Length
$mb = [math]::Round($size / 1MB, 2)
# NFR-103 budgets 80 MB. Reported rather than enforced: the number is the publisher's to act on
# and a script that refused at 81 MB would be making a release decision.
Write-Host "NFR-103: $mb MB of an 80 MB budget ($size bytes)."

if ($CertificatePath) {
    $signTool = Find-SdkTool 'signtool.exe'
    if (-not $signTool) { throw 'signtool.exe was not found beside MakeAppx.' }
    $args = @('sign', '/fd', 'SHA256', '/a', '/f', $CertificatePath)
    if ($CertificatePassword) { $args += @('/p', $CertificatePassword) }
    $args += $msix
    & $signTool @args
    if ($LASTEXITCODE -ne 0) { throw "signtool failed with exit code $LASTEXITCODE." }
    Write-Host 'Signed.'
} else {
    Write-Host @'
Not signed. Store submission signs the package for you, which is FR-305's stated reason for
choosing the Store over an annual certificate and a SmartScreen warning. To install this
package locally for testing you need a certificate of your own; nothing here creates one, for
the reason nothing here creates the Android keystore.
'@
}
