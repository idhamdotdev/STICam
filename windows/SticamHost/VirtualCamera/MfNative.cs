using System;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;

namespace SticamHost.VirtualCamera
{
    // ═══════════════════════════════════════════════════════════════════════════
    //  Windows 11 Media Foundation Virtual Camera P/Invoke & COM declarations
    //
    //  Requires: Windows 11 22H2 (build 22621+)
    //  DLL:      mfvirtualcamera.dll (ships in-box on Win11 22H2+)
    //
    //  How it works:
    //   1. Register a COM class that implements IMFMediaSource (our frame source)
    //   2. Call MFCreateVirtualCamera() with its CLSID
    //   3. Windows creates a virtual camera device visible to all apps
    //   4. When an app opens the camera, Windows instantiates our COM class
    //      and calls IMFMediaSource methods to pull frames
    // ═══════════════════════════════════════════════════════════════════════════

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum MFVirtualCameraType
    {
        MFVirtualCamera_Software = 0,
    }

    public enum MFVirtualCameraLifetime
    {
        MFVirtualCameraLifetime_Session   = 0,  // removed when process exits
        MFVirtualCameraLifetime_System    = 1,  // persists (requires elevation)
    }

    public enum MFVirtualCameraAccess
    {
        MFVirtualCameraAccess_CurrentUser = 0,
        MFVirtualCameraAccess_AllUsers    = 1,  // requires elevation
    }

    // ── HRESULT helpers ───────────────────────────────────────────────────────

    internal static class HResult
    {
        public const int S_OK          = 0;
        public const int S_FALSE       = 1;
        public const int E_NOTIMPL     = unchecked((int)0x80004001);
        public const int E_NOINTERFACE = unchecked((int)0x80004002);
        public const int E_POINTER     = unchecked((int)0x80004003);
        public const int E_ABORT       = unchecked((int)0x80004004);
        public const int E_FAIL        = unchecked((int)0x80004005);
        public const int MF_E_SHUTDOWN = unchecked((int)0xC00D3E85);

        public static bool Succeeded(int hr) => hr >= 0;
        public static void ThrowIfFailed(int hr, string msg = "")
        {
            if (hr < 0)
                throw new COMException(string.IsNullOrEmpty(msg)
                    ? $"HRESULT 0x{hr:X8}" : $"{msg} (0x{hr:X8})", hr);
        }
    }

    // ── IMFVirtualCamera ──────────────────────────────────────────────────────

    [ComImport]
    [Guid("09B4E12C-D4C4-4F51-88C7-B3EFAA54F27B")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IMFVirtualCamera
    {
        [PreserveSig] int AddFilter([MarshalAs(UnmanagedType.LPWStr)] string deviceSymbolicLink);
        [PreserveSig] int RemoveFilter([MarshalAs(UnmanagedType.LPWStr)] string deviceSymbolicLink);
        [PreserveSig] int GetMediaSource(out IntPtr ppMediaSource);
        [PreserveSig] int Start(IntPtr pAttributes);
        [PreserveSig] int Stop();
        [PreserveSig] int Remove();
    }

    // ── MFCreateVirtualCamera P/Invoke ────────────────────────────────────────

    internal static class MfNative
    {
        [DllImport("mfvirtualcamera.dll", ExactSpelling = true)]
        public static extern int MFCreateVirtualCamera(
            MFVirtualCameraType     type,
            MFVirtualCameraLifetime lifetime,
            MFVirtualCameraAccess   access,
            [MarshalAs(UnmanagedType.LPWStr)] string friendlyName,
            [MarshalAs(UnmanagedType.LPWStr)] string? sourceId,   // optional CLSID string
            [In] IntPtr             categories,
            uint                    categoryCount,
            out IMFVirtualCamera    virtualCamera
        );

        [DllImport("mf.dll", ExactSpelling = true)]
        public static extern int MFStartup(uint version, uint dwFlags = 0);

        [DllImport("mf.dll", ExactSpelling = true)]
        public static extern int MFShutdown();

        [DllImport("mfplat.dll", ExactSpelling = true)]
        public static extern int MFCreateMediaType(out IntPtr ppMFType);

        [DllImport("mfplat.dll", ExactSpelling = true)]
        public static extern int MFCreateSample(out IntPtr ppIMFSample);

        [DllImport("mfplat.dll", ExactSpelling = true)]
        public static extern int MFCreateMemoryBuffer(uint cbMaxLength, out IntPtr ppBuffer);

        public const uint MF_VERSION = 0x00020070; // MF 2.0
    }

    // ── OS version check ──────────────────────────────────────────────────────

    public static class WindowsVersion
    {
        /// <summary>
        /// Returns true if the OS is Windows 11 22H2 (build 22621) or later,
        /// which is the minimum required for MFCreateVirtualCamera.
        /// </summary>
        public static bool SupportsMfVirtualCamera
        {
            get
            {
                var v = Environment.OSVersion.Version;
                // Windows 11 22H2 = NT 10.0.22621
                return v.Major >= 10 && v.Build >= 22621;
            }
        }

        public static string OsDescription =>
            $"Windows NT {Environment.OSVersion.Version.Major}.{Environment.OSVersion.Version.Minor}" +
            $" (build {Environment.OSVersion.Version.Build})";
    }
}
