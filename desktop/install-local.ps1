<#
.SYNOPSIS
    Installs Latch on this PC for everyday use, with a double-clickable shortcut.

.DESCRIPTION
    FR-305 says Latch ships as an MSIX through the Microsoft Store, and that needs the Windows
    SDK and a publisher account -- see docs/RELEASE-WINDOWS.md. This is the dogfooding stand-in
    for it: it copies the built application somewhere stable, makes shortcuts, and can add the
    FR-301 start-with-Windows entry.

    It deliberately does NOT bundle a Java runtime. A shipped MSIX must (NFR-103 budgets 80 MB
    for exactly that), but jlink needs a JDK with a `jmods` directory and the one on this
    machine -- Android Studio's bundled JBR -- has none. So the shortcut points at a runtime
    already on the machine, and moving or removing that runtime breaks it. That is a real
    limitation of this script and not of the application; it is why the MSIX is still owed.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File desktop\install-local.ps1
    powershell -ExecutionPolicy Bypass -File desktop\install-local.ps1 -StartWithWindows
    powershell -ExecutionPolicy Bypass -File desktop\install-local.ps1 -Remove
#>
[CmdletBinding()]
param(
    # FR-301: add Latch to the per-user Startup folder so it runs at sign-in.
    [switch] $StartWithWindows,
    # Take the shortcuts and the installed copy away again. Leaves your data alone.
    [switch] $Remove
)

$ErrorActionPreference = 'Stop'

$repo      = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$appHome   = Join-Path $env:LOCALAPPDATA 'Latch\app'
$iconPath  = Join-Path $env:LOCALAPPDATA 'Latch\latch.ico'
$startMenu = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Latch.lnk'
$desktop   = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Latch.lnk'
$startup   = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Startup\Latch.lnk'

function New-Shortcut($path, $target, $arguments, $workingDirectory, $icon, $description) {
    $shell = New-Object -ComObject WScript.Shell
    $link = $shell.CreateShortcut($path)
    $link.TargetPath       = $target
    $link.Arguments        = $arguments
    $link.WorkingDirectory = $workingDirectory
    $link.Description      = $description
    if (Test-Path $icon) { $link.IconLocation = $icon }
    $link.Save()
}

if ($Remove) {
    foreach ($link in @($startMenu, $desktop, $startup)) {
        if (Test-Path $link) { Remove-Item $link -Force; Write-Host "removed $link" }
    }
    if (Test-Path $appHome) { Remove-Item $appHome -Recurse -Force; Write-Host "removed $appHome" }
    Write-Host ''
    Write-Host 'Your sign-in, settings and anything still queued are untouched:'
    Write-Host "  $(Join-Path $env:LOCALAPPDATA 'Latch')  (secrets.dat, queue.dat, client.properties)"
    Write-Host 'Delete that folder by hand if you want them gone too.'
    return
}

# ---- find a runtime -------------------------------------------------------------------------
$candidates = @()
if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\javaw.exe') }
$candidates += 'C:\Program Files\Android\Android Studio\jbr\bin\javaw.exe'
$onPath = Get-Command javaw.exe -ErrorAction SilentlyContinue
if ($onPath) { $candidates += $onPath.Source }

$javaw = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $javaw) {
    throw "No Java runtime found. Set JAVA_HOME, or install a JDK 17+, then run this again."
}
Write-Host "runtime:  $javaw"

# `javaw.exe`, not `java.exe`: java.exe opens a console window that sits behind the tray icon
# for as long as Latch runs, and closing it kills the application.

# ---- build and copy -------------------------------------------------------------------------
Push-Location $repo
try {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaw) }
    Write-Host 'building...'
    & (Join-Path $repo 'gradlew.bat') ':desktop:installDist' '-q'
    if ($LASTEXITCODE -ne 0) { throw "the build failed" }
} finally {
    Pop-Location
}

$built = Join-Path $repo 'desktop\build\install\desktop'
if (-not (Test-Path $built)) { throw "expected a build at $built" }

if (Test-Path $appHome) { Remove-Item $appHome -Recurse -Force }
New-Item -ItemType Directory -Path $appHome -Force | Out-Null
Copy-Item (Join-Path $built 'lib') -Destination $appHome -Recurse -Force
Copy-Item (Join-Path $repo 'desktop\launcher\latch.ico') -Destination $iconPath -Force
Write-Host "installed: $appHome"

# ---- shortcuts ------------------------------------------------------------------------------
$arguments = '-cp "' + (Join-Path $appHome 'lib\*') + '" com.latch.desktop.MainKt'
New-Shortcut $startMenu $javaw $arguments $appHome $iconPath 'Latch - capture dates from anything'
New-Shortcut $desktop   $javaw $arguments $appHome $iconPath 'Latch - capture dates from anything'
Write-Host "shortcut:  $startMenu"
Write-Host "shortcut:  $desktop"

if ($StartWithWindows) {
    New-Shortcut $startup $javaw $arguments $appHome $iconPath 'Latch'
    Write-Host "startup:   $startup"
} elseif (Test-Path $startup) {
    Remove-Item $startup -Force
    Write-Host "startup:   removed (pass -StartWithWindows to keep it)"
} else {
    Write-Host "startup:   not set (pass -StartWithWindows to enable)"
}

Write-Host ''
Write-Host 'Done. Double-click Latch on the Desktop, or find it in the Start menu.'
Write-Host 'It has no window: look for the blue L in the system tray, next to the clock.'
