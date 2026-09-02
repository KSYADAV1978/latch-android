# FR-303: on-device text recognition through Windows.Media.Ocr.
#
# Output is a TSV on stdout, one record per line, text always last so a tab inside it cannot
# shift a column:
#
#   ENGINE <tab> <language tag> <tab> <image width> <tab> <image height>
#   LINE   <tab> x <tab> y <tab> w <tab> h <tab> <text>
#   ERROR  <tab> <named reason> <tab> <detail>
#
# The reasons are named rather than phrased. NFR-402 puts user-facing strings in the client,
# and this script is not the client — it reports a fact and the caller decides what to say.
# The path arrives in the environment rather than on the command line, and that is a decision.
# A file name is user data — it can hold a quote, a dollar or a backtick — and PowerShell would
# read every one of them as syntax. An environment variable is never parsed, so there is no
# escaping to get right and no path that can turn into a command.
$Path = $env:LATCH_OCR_PATH

$ErrorActionPreference = 'Stop'
# Devanagari, accented Latin and CJK all come back from the recogniser as UTF-16; without this
# the pipe home is the console's ANSI code page and every non-ASCII character becomes a
# question mark. The caller reads UTF-8, so say UTF-8, and no BOM — the BOM would be data.
[Console]::OutputEncoding = New-Object Text.UTF8Encoding $false

function Fail($reason, $detail) {
    Write-Output ("ERROR`t" + $reason + "`t" + $detail)
    exit 0   # a named failure is an answer, not a crash; exit codes are for the bridge failing
}

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
    $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() |
        Where-Object {
            $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
            $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
        })[0]
} catch {
    Fail 'NO_WINRT' $_.Exception.Message
}

function Await($op, $type) {
    $task = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
    $task.Wait(-1) | Out-Null
    $task.Result
}

[Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]           | Out-Null
[Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics, ContentType = WindowsRuntime] | Out-Null
[Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]        | Out-Null

if ([string]::IsNullOrEmpty($Path)) { Fail 'BRIDGE_FAILED' 'LATCH_OCR_PATH was not set' }
if (-not (Test-Path -LiteralPath $Path)) { Fail 'UNREADABLE_SOURCE' $Path }

# The engine follows the user's own language preferences. Where none of them has a recogniser
# installed, any installed one is better than refusing: a date is digits and a month name, and
# an en-GB engine reads a German page's dates perfectly well.
$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
if ($null -eq $engine) {
    $available = [Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages
    if ($available.Count -eq 0) { Fail 'NO_RECOGNISER' 'no OCR language pack is installed' }
    $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($available[0])
    if ($null -eq $engine) { Fail 'NO_RECOGNISER' $available[0].LanguageTag }
}

try {
    $file    = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($Path)) ([Windows.Storage.StorageFile])
    $stream  = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
} catch {
    Fail 'UNREADABLE_SOURCE' $_.Exception.Message
}

$transform = New-Object Windows.Graphics.Imaging.BitmapTransform
$width  = $decoder.PixelWidth
$height = $decoder.PixelHeight

# The recogniser refuses an image past MaxImageDimension outright. A phone camera clears it on
# the long edge routinely, so scale rather than fail — text small enough to be lost at this
# ratio was not going to be recognised at full size either.
$max = [Windows.Media.Ocr.OcrEngine]::MaxImageDimension
if ($width -gt $max -or $height -gt $max) {
    $scale = [Math]::Min($max / $width, $max / $height)
    $width  = [uint32][Math]::Floor($width * $scale)
    $height = [uint32][Math]::Floor($height * $scale)
    $transform.ScaledWidth  = $width
    $transform.ScaledHeight = $height
    $transform.InterpolationMode = [Windows.Graphics.Imaging.BitmapInterpolationMode]::Fant
}

try {
    # RespectExifOrientation is the whole reason this overload is used rather than the plain
    # GetSoftwareBitmapAsync(). A page photographed portrait is stored landscape with an
    # orientation tag, and handing the recogniser a sideways page returns nothing at all — not
    # degraded text, nothing. That exact defect was found on Android on 31 Aug 2026 against a
    # 2800x2000 JPEG with orientation 6, and it is a total failure rather than a partial one,
    # which is why it is worth an overload with five arguments.
    $bitmap = Await ($decoder.GetSoftwareBitmapAsync(
        [Windows.Graphics.Imaging.BitmapPixelFormat]::Bgra8,
        [Windows.Graphics.Imaging.BitmapAlphaMode]::Premultiplied,
        $transform,
        [Windows.Graphics.Imaging.ExifOrientationMode]::RespectExifOrientation,
        [Windows.Graphics.Imaging.ColorManagementMode]::ColorManageToSRgb)) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
} catch {
    Fail 'RECOGNISER_FAILED' $_.Exception.Message
}

Write-Output ("ENGINE`t" + $engine.RecognizerLanguage.LanguageTag + "`t" + $bitmap.PixelWidth + "`t" + $bitmap.PixelHeight)

# A line has no bounding box of its own; its words do. The union of them is the row, and the
# caller sorts on it rather than trusting the order this loop happens to emit — see OcrOutput.
foreach ($line in $result.Lines) {
    $left = [double]::MaxValue; $top = [double]::MaxValue; $right = 0.0; $bottom = 0.0
    foreach ($word in $line.Words) {
        $r = $word.BoundingRect
        if ($r.X -lt $left) { $left = $r.X }
        if ($r.Y -lt $top)  { $top  = $r.Y }
        if (($r.X + $r.Width)  -gt $right)  { $right  = $r.X + $r.Width }
        if (($r.Y + $r.Height) -gt $bottom) { $bottom = $r.Y + $r.Height }
    }
    if ($line.Words.Count -eq 0) { $left = 0; $top = 0 }
    $text = $line.Text -replace "`t", ' '
    Write-Output ("LINE`t{0:F1}`t{1:F1}`t{2:F1}`t{3:F1}`t{4}" -f $left, $top, ($right - $left), ($bottom - $top), $text)
}
