# Share a file to Latch through the real system resolver, for the FR-215 device pass.
#
# Why this exists rather than `am start -n com.latch.android/...`: a directly named
# component does not receive the URI read grant, so the file arrives unreadable and every
# capture fails with UNREADABLE_SOURCE. Going through the resolver is also the more
# faithful test, since it exercises FR-205's share-target registration.
#
# THE HARD RULE, and the reason it is written in code rather than remembered:
# this script taps ONLY inside Latch or the system resolver, and aborts the moment the
# foreground package is anything else. An earlier version tapped a coordinate it had not
# re-checked, the intent had gone straight to a default handler instead of a resolver, and
# the taps landed inside the developer's WhatsApp contact picker. Nothing was sent, but
# nothing about the script prevented it either. Two consequences follow and both are
# enforced below: never tap without re-dumping and matching the node exactly, and never
# tap at all unless the foreground package is on the allowlist.
#
# Note on mime: pass image/png even for a JPEG where the device has a default handler
# registered for image/jpeg (WhatsApp, typically). Routing is all the mime affects here;
# BitmapFactory and ExifInterface both read the real bytes, so decode and EXIF rotation
# are unaffected by the declared type.
param(
    [Parameter(Mandatory=$true)][string]$Uri,
    [Parameter(Mandatory=$true)][string]$Mime,
    [int]$WaitSeconds = 12,
    [string]$Shot = "share"
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$sp  = "C:\Users\kulve\AppData\Local\Temp\claude\C--dev-latch-android\5d69a124-ff96-4da5-a293-45577ba4ecc5\scratchpad"

# The only packages this script may send a tap into.
$AllowedPackages = @(
    "com.latch.android",
    "android",                                  # the system resolver / chooser
    "com.android.intentresolver",               # its own package on newer releases
    "com.google.android.apps.nexuslauncher"     # the launcher, for HOME between runs
)

function Get-ForegroundPackage {
    $focus = & $adb shell dumpsys window 2>&1 | Select-String "mCurrentFocus" | Select-Object -First 1
    if ($focus -match '\s([A-Za-z0-9_.]+)/') { return $Matches[1] }
    return "<unknown>"
}

function Assert-Safe {
    $pkg = Get-ForegroundPackage
    if ($AllowedPackages -notcontains $pkg) {
        Write-Output "ABORT: foreground package is '$pkg', which is not Latch or the resolver."
        Write-Output "       No tap was sent. Backing out."
        & $adb shell input keyevent KEYCODE_BACK | Out-Null
        Start-Sleep -Seconds 1
        & $adb shell input keyevent KEYCODE_HOME | Out-Null
        throw "refused to tap inside $pkg"
    }
    return $pkg
}

function Get-Dump {
    & $adb shell uiautomator dump /sdcard/ui.xml 2>&1 | Out-Null
    return (& $adb shell cat /sdcard/ui.xml 2>&1 | Out-String)
}

function Find-Exact([string]$xml, [string]$text) {
    $pattern = 'text="' + [regex]::Escape($text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Match($xml, $pattern)
    if (-not $m.Success) { return $null }
    $x = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
    $y = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
    return @([int]$x, [int]$y)
}

# Tap only after re-dumping and re-matching, and only inside an allowed package.
function Tap-Node([string]$text) {
    Assert-Safe | Out-Null
    $node = Find-Exact (Get-Dump) $text
    if (-not $node) { return $false }
    Assert-Safe | Out-Null
    & $adb shell input tap $node[0] $node[1]
    return $true
}

& $adb shell am force-stop com.latch.android
& $adb shell input keyevent KEYCODE_HOME
Start-Sleep -Seconds 1
& $adb logcat -c
& $adb shell "am start -a android.intent.action.SEND -t $Mime --eu android.intent.extra.STREAM $Uri --grant-read-uri-permission" | Out-Null
Start-Sleep -Seconds 3

# If the intent went straight to a default handler, this is where we stop.
$pkg = Assert-Safe

$picked = $false
for ($attempt = 1; $attempt -le 4; $attempt++) {
    if ((Get-ForegroundPackage) -eq "com.latch.android") { $picked = $true; break }
    if (Tap-Node "Just once") { $picked = $true; break }
    if (Tap-Node "Latch") { Start-Sleep -Seconds 2; continue }
    Assert-Safe | Out-Null
    & $adb shell input swipe 718 2650 718 2000 300
    Start-Sleep -Seconds 2
}

if (-not $picked) { Write-Output "WARN: never confirmed a Latch tap for $Shot" }

Start-Sleep -Seconds $WaitSeconds
& $adb shell screencap -p "/sdcard/$Shot.png"
& $adb pull "/sdcard/$Shot.png" "$sp\$Shot.png" | Out-Null
& $adb logcat -d -v time | Select-String "START.*CaptureActivity|Displayed.*CaptureActivity|LatchTiming" | ForEach-Object { $_.Line.Trim() }
