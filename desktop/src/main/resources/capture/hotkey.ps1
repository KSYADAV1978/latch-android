# FR-302: a system-wide hotkey, registered through RegisterHotKey as the requirement names.
#
# This runs as a child process of the application and does one thing: it holds the
# registration and a message loop, and writes a line to stdout each time the combination is
# pressed. It is a separate process rather than a thread because RegisterHotKey delivers
# WM_HOTKEY to a **thread's** message queue, and a JVM has no such queue to give it.
#
# The C# is compiled by the csc.exe that ships inside Windows, through Add-Type. Nothing is
# installed; see desktop/build.gradle.kts for why that fact decides the language this client
# is written in.
$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public class LatchHotkey {
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    [StructLayout(LayoutKind.Sequential)]
    private struct MSG {
        public IntPtr hwnd; public uint message; public IntPtr wParam;
        public IntPtr lParam; public uint time; public int x; public int y;
    }

    [DllImport("user32.dll")]
    private static extern int GetMessage(out MSG lpMsg, IntPtr hWnd, uint min, uint max);

    private const uint WM_HOTKEY = 0x0312;

    public static int Register(uint modifiers, uint vk) {
        if (RegisterHotKey(IntPtr.Zero, 1, modifiers, vk)) return 0;
        return Marshal.GetLastWin32Error();
    }

    public static void Unregister() { UnregisterHotKey(IntPtr.Zero, 1); }

    // Blocks until the combination is pressed. GetMessage returning 0 or -1 means the queue
    // is finished or broken, so the caller stops rather than spinning on it.
    public static bool WaitForPress() {
        MSG message;
        while (true) {
            int result = GetMessage(out message, IntPtr.Zero, 0, 0);
            if (result <= 0) return false;
            if (message.message == WM_HOTKEY) return true;
        }
    }
}
'@

$modifiers = [uint32]$env:LATCH_HOTKEY_MODIFIERS
$vk = [uint32]$env:LATCH_HOTKEY_VK

$error_code = [LatchHotkey]::Register($modifiers, $vk)
if ($error_code -ne 0) {
    # 1409 is ERROR_HOTKEY_ALREADY_REGISTERED, the common one, and it is not a defect: some
    # other application holds the combination. Named so the client can say which.
    Write-Output ('ERR ' + $error_code)
    [Console]::Out.Flush()
    exit 0
}

Write-Output 'READY'
[Console]::Out.Flush()

try {
    while ([LatchHotkey]::WaitForPress()) {
        Write-Output 'PRESSED'
        [Console]::Out.Flush()
    }
} finally {
    [LatchHotkey]::Unregister()
}
