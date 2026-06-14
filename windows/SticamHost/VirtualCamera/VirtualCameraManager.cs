using System;
using System.Drawing;
using System.Threading;

namespace SticamHost.VirtualCamera
{
    /// <summary>
    /// Unified Virtual Camera manager.
    ///
    /// Automatically selects the best available output path:
    ///
    ///   Priority 1: OBS VirtualCam (DirectShow) — appears as real webcam device
    ///               Requires OBS Studio with VirtualCam plugin installed.
    ///               Detected via Windows registry scan.
    ///
    ///   Priority 2: RTSP stream (always available)
    ///               URL: rtsp://127.0.0.1:8554/sticam
    ///               Add as a "Media Source" in OBS, or open in VLC.
    ///               Works without any extra software beyond bundled ffmpeg.exe.
    ///
    ///   Future:     Windows 11 22H2+ MF Virtual Camera
    ///               Will be integrated once a stable C# COM source is wired up.
    ///               (Detected but not yet activated — see note below.)
    ///
    /// Usage:
    ///   var vcam = new VirtualCameraManager(width, height, fps);
    ///   vcam.OnLog += msg => ...;
    ///   var info = vcam.Start();       // returns active mode description
    ///   vcam.PushFrame(bitmap);        // call on every decoded frame
    ///   vcam.Stop();
    /// </summary>
    public sealed class VirtualCameraManager : IDisposable
    {
        public int Width { get; private set; }
        public int Height { get; private set; }
        public readonly int Fps;

        private FfmpegPipe?          _pipe;
        private ObsVirtualCamQueue?  _vcamQueue;
        private bool                 _running;

        public event Action<string>? OnLog;
        public event Action<string>? OnModeChanged;   // describes active output path

        public VirtualCameraMode ActiveMode { get; private set; } = VirtualCameraMode.None;
        public string            ModeDetail { get; private set; } = "";

        public VirtualCameraManager(int width, int height, int fps)
        {
            Width  = width;
            Height = height;
            Fps    = fps;
        }

        // ── Public API ────────────────────────────────────────────────────────

        /// <summary>
        /// Probes available output paths and starts the best one.
        /// Returns a human-readable description of the active mode.
        /// </summary>
        public string Start()
        {
            if (_running) return ModeDetail;

            Log($"OS: {WindowsVersion.OsDescription}");
            Log($"MF Virtual Camera supported: {WindowsVersion.SupportsMfVirtualCamera}");

            // ── Path 1: OBS VirtualCam ────────────────────────────────────────
            bool obsAvailable = FfmpegPipe.IsObsVirtualCamInstalled();
            Log($"OBS VirtualCam installed: {obsAvailable}");

            if (obsAvailable)
            {
                string devName = FfmpegPipe.DetectObsVirtualCamName()!;
                Log($"Using Standalone Virtual Camera: {devName}");

                _vcamQueue = new ObsVirtualCamQueue(Width, Height, Fps);
                if (_vcamQueue.Start())
                {
                    _running   = true;
                    ActiveMode = VirtualCameraMode.ObsVirtualCam;
                    ModeDetail = "Standalone Sticam Camera Active";
                    OnModeChanged?.Invoke(ModeDetail);
                    return ModeDetail;
                }

                Log("Standalone Virtual Camera start failed — falling through to RTSP");
                _vcamQueue.Dispose(); _vcamQueue = null;
            }

            // ── Path 2: RTSP (always available with bundled ffmpeg) ───────────
            return StartRtsp();
        }

        private string StartRtsp()
        {
            _pipe?.Dispose();
            _pipe = new FfmpegPipe(Width, Height, Fps, useObsVirtualCam: false);
            _pipe.OnLog += Log;
            _pipe.OnRunningChanged += ok => { if (!ok && _running) Log("RTSP stream stopped"); };

            if (_pipe.Start())
            {
                _running   = true;
                ActiveMode = VirtualCameraMode.Rtsp;
                ModeDetail = $"RTSP → {FfmpegPipe.RtspUrl}";
                OnModeChanged?.Invoke(ModeDetail);
                return ModeDetail;
            }

            ActiveMode = VirtualCameraMode.None;
            ModeDetail = "FAILED — check ffmpeg in tools/";
            Log(ModeDetail);
            return ModeDetail;
        }

        public void Stop()
        {
            _running   = false;
            ActiveMode = VirtualCameraMode.None;
            ModeDetail = "";
            _pipe?.Stop(); _pipe = null;
            _vcamQueue?.Stop(); _vcamQueue = null;
            OnModeChanged?.Invoke("Stopped");
        }

        public void Dispose() => Stop();

        /// <summary>
        /// Push a decoded Bitmap frame into the active virtual camera output.
        /// No-op if not running. Safe to call from any thread.
        /// </summary>
        public void PushFrame(Bitmap bmp)
        {
            if (!_running) return;

            if (bmp.Width != Width || bmp.Height != Height)
            {
                Log($"Resolution changed from {Width}x{Height} to {bmp.Width}x{bmp.Height}. Re-initializing Virtual Camera.");

                // Stop the active queue/pipe
                _vcamQueue?.Stop(); _vcamQueue = null;
                _pipe?.Stop(); _pipe = null;
                _running = false;

                // Update resolution
                Width  = bmp.Width;
                Height = bmp.Height;

                // Re-initialize queue or pipe
                if (ActiveMode == VirtualCameraMode.ObsVirtualCam)
                {
                    _vcamQueue = new ObsVirtualCamQueue(Width, Height, Fps);
                    if (_vcamQueue.Start())
                    {
                        _running = true;
                    }
                    else
                    {
                        Log("Re-initialization of Standalone Virtual Camera failed.");
                        ActiveMode = VirtualCameraMode.None;
                    }
                }
                else if (ActiveMode == VirtualCameraMode.Rtsp)
                {
                    _pipe = new FfmpegPipe(Width, Height, Fps, useObsVirtualCam: false);
                    _pipe.OnLog += Log;
                    if (_pipe.Start())
                    {
                        _running = true;
                    }
                    else
                    {
                        Log("Re-initialization of RTSP stream failed.");
                        ActiveMode = VirtualCameraMode.None;
                    }
                }
            }

            if (!_running) return;

            if (ActiveMode == VirtualCameraMode.ObsVirtualCam)
            {
                _vcamQueue?.WriteFrame(bmp);
            }
            else
            {
                _pipe?.PushFrame(bmp);
            }
        }

        // ── Status info ───────────────────────────────────────────────────────

        public static VirtualCameraCapabilities QueryCapabilities() => new()
        {
            OsSupported              = true, // ffmpeg path works on all Windows
            ObsVirtualCamInstalled   = FfmpegPipe.IsObsVirtualCamInstalled(),
            ObsVirtualCamName        = FfmpegPipe.DetectObsVirtualCamName(),
            MfVirtualCamSupported    = WindowsVersion.SupportsMfVirtualCamera,
            OsDescription            = WindowsVersion.OsDescription,
            RtspUrl                  = FfmpegPipe.RtspUrl,
        };

        private void Log(string msg) => OnLog?.Invoke(msg);
    }

    // ── Supporting types ──────────────────────────────────────────────────────

    public enum VirtualCameraMode
    {
        None,
        Rtsp,          // rtsp://127.0.0.1:8554/sticam
        ObsVirtualCam, // DirectShow device via OBS VirtualCam plugin
        MfVirtualCam,  // Windows 11 22H2+ native (future)
    }

    public sealed class VirtualCameraCapabilities
    {
        public bool    OsSupported             { get; init; }
        public bool    ObsVirtualCamInstalled  { get; init; }
        public string? ObsVirtualCamName       { get; init; }
        public bool    MfVirtualCamSupported   { get; init; }
        public string  OsDescription           { get; init; } = "";
        public string  RtspUrl                 { get; init; } = "";
    }
}
