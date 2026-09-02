# NFR-203: encryption at rest under the operating system's own key store.
#
# DPAPI, through the .NET Framework that ships with Windows. The key is derived by the OS from
# the user's credentials and never exists in this application; a copy of the encrypted file on
# another machine, or under another account, cannot be read. That is the same property the
# Android client gets from the Keystore, and the same limitation: a device transfer loses it.
#
# Mode is PROTECT or UNPROTECT and arrives in the environment.
#
# **The payload arrives on stdin and leaves on stdout, and never on the command line**, which
# on Windows is readable by any process the same user can run. A refresh token passed as an
# argument would be visible to anything on the machine for as long as this child lived.
#
# **Both directions are base64, so no text encoding is involved anywhere.** The first version
# read stdin as text and a Devanagari secret came back as twelve characters instead of four:
# PowerShell decodes a redirected stdin with the console's code page, not UTF-8, and setting
# [Console]::InputEncoding is unreliable once stdin is a pipe. Carrying bytes rather than
# characters removes the question instead of answering it, and has a second benefit worth
# having — the plaintext never exists as a PowerShell string, so it is never interned in a
# process this code does not control.
$ErrorActionPreference = 'Stop'

try {
    Add-Type -AssemblyName System.Security
    $reader = New-Object IO.StreamReader ([Console]::OpenStandardInput())
    $payload = $reader.ReadToEnd().Trim()
    $bytes = [Convert]::FromBase64String($payload)
    $scope = [Security.Cryptography.DataProtectionScope]::CurrentUser

    if ($env:LATCH_DPAPI_MODE -eq 'PROTECT') {
        $out = [Security.Cryptography.ProtectedData]::Protect($bytes, $null, $scope)
    } elseif ($env:LATCH_DPAPI_MODE -eq 'UNPROTECT') {
        $out = [Security.Cryptography.ProtectedData]::Unprotect($bytes, $null, $scope)
    } else {
        Write-Output 'ERR unknown mode'
        exit 0
    }
    Write-Output ('OK ' + [Convert]::ToBase64String($out))
} catch {
    # Deliberately not the exception's own message: a DPAPI failure can name the user and the
    # key container, and this string ends up in logs.
    Write-Output 'ERR the operating system refused this'
}
