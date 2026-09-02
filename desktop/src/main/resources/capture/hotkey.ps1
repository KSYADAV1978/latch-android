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
    private static extern bool PeekMessage(out MSG lpMsg, IntPtr hWnd, uint min, uint max, uint remove);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint access, bool inherit, int pid);

    [DllImport("user32.dll")]
    private static extern uint MsgWaitForMultipleObjects(
        uint count, IntPtr[] handles, bool waitAll, uint milliseconds, uint wakeMask);

    private const uint WM_HOTKEY = 0x0312;
    private const uint PM_REMOVE = 0x0001;
    private const uint QS_ALLINPUT = 0x04FF;
    private const uint SYNCHRONIZE = 0x00100000;
    private const uint INFINITE = 0xFFFFFFFF;
    private const uint WAIT_OBJECT_0 = 0;

    private static IntPtr parent = IntPtr.Zero;

    // Watching the parent is what stops this process outliving the application that started
    // it. A shutdown hook covers an ordinary exit, but Task Manager's End task and a crash
    // send nothing — and a sidecar left behind holds the user's hotkey combination until
    // somebody notices and reaps it, which on a system-wide shortcut means the key silently
    // does nothing in every application.
    public static void WatchParent(int pid) { parent = OpenProcess(SYNCHRONIZE, false, pid); }

    public static int Register(uint modifiers, uint vk) {
        if (RegisterHotKey(IntPtr.Zero, 1, modifiers, vk)) return 0;
        return Marshal.GetLastWin32Error();
    }

    public static void Unregister() { UnregisterHotKey(IntPtr.Zero, 1); }

    // Blocks until the combination is pressed, or until the parent process exits — whichever
    // comes first. Returning false means stop.
    public static bool WaitForPress() {
        IntPtr[] handles = parent == IntPtr.Zero ? new IntPtr[0] : new IntPtr[] { parent };
        MSG message;
        while (true) {
            uint result = MsgWaitForMultipleObjects(
                (uint)handles.Length, handles, false, INFINITE, QS_ALLINPUT);
            if (handles.Length > 0 && result == WAIT_OBJECT_0) return false;   // the parent went
            while (PeekMessage(out message, IntPtr.Zero, 0, 0, PM_REMOVE)) {
                if (message.message == WM_HOTKEY) return true;
            }
        }
    }
}
'@

if ($env:LATCH_PARENT_PID) { [LatchHotkey]::WatchParent([int]$env:LATCH_PARENT_PID) }

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
