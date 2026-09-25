<#
Stamps the app's AppUserModelID onto the installed shortcuts (desktop + start menu).

The running process claims the same id (AppIdentity.APP_ID), and the shell only
treats the two as one app - taskbar group, toasts, media card name instead of
"Unknown app" - when the ids match. A window whose id does not match its shortcut
groups apart from the pinned icon, so these two must always be kept in step.

This file is the SOURCE for the -EncodedCommand blob in the ApodStampAumid custom
action in packaging/windows/main.wxs (extra files in the resource dir are not
staged by jpackage, so only main.wxs travels). After changing this file, re-encode:

  [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes(
      (Get-Content -Raw packaging/windows/stamp-shortcuts.ps1)))

and paste the result into main.wxs. The installer runs it after CreateShortcuts
with Return=ignore: setup must never fail over a cosmetic stamp.

Safe to run by hand, too: missing shortcuts are skipped, per-file failures only
warn, exit code is always 0. Pass -Revert to clear the stamp again.
#>
param(
    [switch]$Revert
)

$APP_ID = 'AntennaPod.AntennaPodDesktop'
$LINK_NAME = 'AntennaPod-Desktop.lnk'

$csharp = @'
using System;
using System.Runtime.InteropServices;

public static class ShortcutAumid {
    const int GPS_READWRITE = 2;
    static readonly Guid IID_IPropertyStore =
        new Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99");
    static readonly Guid FMTID_AppUserModelID =
        new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3");
    const uint PID_AppUserModel_ID = 5;
    const ushort VT_LPWSTR = 31;
    const ushort VT_EMPTY = 0;

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = true)]
    static extern int SHGetPropertyStoreFromParsingName(
        string pszPath, IntPtr pbc, int flags, ref Guid riid,
        [MarshalAs(UnmanagedType.Interface)] out IPropertyStore ppv);

    [DllImport("ole32.dll", PreserveSig = true)]
    static extern int PropVariantClear(ref PROPVARIANT pvar);

    [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IPropertyStore {
        int GetCount(out uint count);
        int GetAt(uint iProp, out PROPERTYKEY pkey);
        int GetValue(ref PROPERTYKEY key, out PROPVARIANT pv);
        int SetValue(ref PROPERTYKEY key, ref PROPVARIANT pv);
        int Commit();
    }

    [StructLayout(LayoutKind.Sequential)]
    struct PROPERTYKEY {
        public Guid fmtid;
        public uint pid;
    }

    [StructLayout(LayoutKind.Explicit)]
    struct PROPVARIANT {
        [FieldOffset(0)] public ushort vt;
        [FieldOffset(8)] public IntPtr ptr;
    }

    public static void Set(string lnkPath, string appId) {
        Guid iid = IID_IPropertyStore;
        IPropertyStore store;
        int hr = SHGetPropertyStoreFromParsingName(
            lnkPath, IntPtr.Zero, GPS_READWRITE, ref iid, out store);
        if (hr != 0 || store == null) {
            throw new Exception("open store failed: 0x" + hr.ToString("X8"));
        }
        try {
            PROPERTYKEY key = new PROPERTYKEY();
            key.fmtid = FMTID_AppUserModelID;
            key.pid = PID_AppUserModel_ID;
            PROPVARIANT pv = new PROPVARIANT();
            try {
                if (appId != null) {
                    pv.vt = VT_LPWSTR;
                    pv.ptr = Marshal.StringToCoTaskMemUni(appId);
                } else {
                    pv.vt = VT_EMPTY;
                    pv.ptr = IntPtr.Zero;
                }
                hr = store.SetValue(ref key, ref pv);
                if (hr != 0) {
                    throw new Exception("set failed: 0x" + hr.ToString("X8"));
                }
                hr = store.Commit();
                if (hr != 0) {
                    throw new Exception("commit failed: 0x" + hr.ToString("X8"));
                }
            } finally {
                PropVariantClear(ref pv);
            }
        } finally {
            Marshal.ReleaseComObject(store);
        }
    }
}
'@

Add-Type -TypeDefinition $csharp -Language CSharp

$targets = @()
$desktop = Join-Path ([Environment]::GetFolderPath('Desktop')) $LINK_NAME
if (Test-Path -LiteralPath $desktop) {
    $targets += $desktop
}
$programs = Join-Path ([Environment]::GetFolderPath('ApplicationData')) `
    'Microsoft\Windows\Start Menu\Programs'
if (Test-Path -LiteralPath $programs) {
    $targets += Get-ChildItem -Path $programs -Filter $LINK_NAME -Recurse -Depth 3 `
        -File -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
}

$done = 0
foreach ($lnk in $targets | Select-Object -Unique) {
    try {
        if ($Revert) {
            [ShortcutAumid]::Set($lnk, $null)
            Write-Host "Cleared $lnk"
        } else {
            [ShortcutAumid]::Set($lnk, $APP_ID)
            Write-Host "Stamped $lnk"
        }
        $done++
    } catch {
        Write-Warning "Could not stamp $lnk : $($_.Exception.Message)"
    }
}
Write-Host "Done: $done shortcut(s), id '$APP_ID'"
