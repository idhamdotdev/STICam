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
    ///   Priority 2: RTSP publisher (requires ffmpeg and an RTSP server on port 8554)
    ///               URL: rtsp://127.0.0.1:8554/sticam
    ///               Add as a "Media Source" in OBS, or open in VLC.
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
        private readonly object      _stateLock = new();

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
            lock (_stateLock)
            {
                if (_running) return ModeDetail;
            }

            Log($"OS: {WindowsVersion.OsDescription}");
            Log($"MF Virtual Camera supported: {WindowsVersion.SupportsMfVirtualCamera}");

            // ── Path 1: OBS VirtualCam ────────────────────────────────────────
            bool obsAvailable = FfmpegPipe.IsObsVirtualCamInstalled();
            Log($"OBS VirtualCam installed: {obsAvailable}");

            if (obsAvailable)
            {
                string devName = FfmpegPipe.DetectObsVirtualCamName()!;
                Log($"Using installed OBS Virtual Camera: {devName}");

                _vcamQueue = new ObsVirtualCamQueue(Width, Height, Fps);
                _vcamQueue.OnLog += Log;
                if (_vcamQueue.Start())
                {
                    _running   = true;
                    ActiveMode = VirtualCameraMode.ObsVirtualCam;
                    ModeDetail = "OBS Virtual Camera active";
                    OnModeChanged?.Invoke(ModeDetail);
                    return ModeDetail;
                }

                Log("OBS Virtual Camera start failed — falling through to RTSP");
                _vcamQueue.Dispose(); _vcamQueue = null;
            }

            // ── Path 2: RTSP publisher (requires an external server) ─────────
            return StartRtsp();
        }

        private string StartRtsp()
        {
            _pipe?.Dispose();
            _pipe = new FfmpegPipe(Width, Height, Fps);
            _pipe.OnLog += Log;
            _pipe.OnRunningChanged += ok =>
            {
                if (ok) return;
                lock (_stateLock)
                {
                    if (!_running || ActiveMode != VirtualCameraMode.Rtsp) return;
                    _running = false;
                    ActiveMode = VirtualCameraMode.None;
                    ModeDetail = "RTSP publisher stopped";
                }
                Log(ModeDetail);
                OnModeChanged?.Invoke(ModeDetail);
            };

            if (_pipe.Start())
            {
                _running   = true;
                ActiveMode = VirtualCameraMode.Rtsp;
                ModeDetail = $"RTSP → {FfmpegPipe.RtspUrl}";
                OnModeChanged?.Invoke(ModeDetail);
                return ModeDetail;
            }

            ActiveMode = VirtualCameraMode.None;
            ModeDetail = "FAILED - install ffmpeg and start an RTSP server on 127.0.0.1:8554";
            Log(ModeDetail);
            OnModeChanged?.Invoke(ModeDetail);
            return ModeDetail;
        }

        public void Stop()
        {
            lock (_stateLock)
            {
                _running = false;
                ActiveMode = VirtualCameraMode.None;
                ModeDetail = "";
            }
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
            lock (_stateLock)
            {
                if (!_running) return;
            }

            if (bmp.Width != Width || bmp.Height != Height)
            {
                Log($"Resolution changed from {Width}x{Height} to {bmp.Width}x{bmp.Height}. Re-initializing Virtual Camera.");

                // Stop the active queue/pipe
                VirtualCameraMode previousMode = ActiveMode;
                _running = false;
                _vcamQueue?.Stop(); _vcamQueue = null;
                _pipe?.Stop(); _pipe = null;

                // Update resolution
                Width  = bmp.Width;
                Height = bmp.Height;

                // Re-initialize queue or pipe
                if (previousMode == VirtualCameraMode.ObsVirtualCam)
                {
                    _vcamQueue = new ObsVirtualCamQueue(Width, Height, Fps);
                    _vcamQueue.OnLog += Log;
                    if (_vcamQueue.Start())
                    {
                        _running = true;
                        ActiveMode = VirtualCameraMode.ObsVirtualCam;
                    }
                    else
                    {
                        Log("Re-initialization of OBS Virtual Camera failed.");
                        ActiveMode = VirtualCameraMode.None;
                        ModeDetail = "OBS Virtual Camera stopped after resolution change";
                        OnModeChanged?.Invoke(ModeDetail);
                    }
                }
                else if (previousMode == VirtualCameraMode.Rtsp)
                {
                    _pipe = new FfmpegPipe(Width, Height, Fps);
                    _pipe.OnLog += Log;
                    _pipe.OnRunningChanged += ok =>
                    {
                        if (ok) return;
                        lock (_stateLock)
                        {
                            if (!_running || ActiveMode != VirtualCameraMode.Rtsp) return;
                            _running = false;
                            ActiveMode = VirtualCameraMode.None;
                            ModeDetail = "RTSP publisher stopped";
                        }
                        Log(ModeDetail);
                        OnModeChanged?.Invoke(ModeDetail);
                    };
                    if (_pipe.Start())
                    {
                        _running = true;
                        ActiveMode = VirtualCameraMode.Rtsp;
                    }
                    else
                    {
                        Log("Re-initialization of RTSP stream failed.");
                        ActiveMode = VirtualCameraMode.None;
                        ModeDetail = "RTSP publisher stopped after resolution change";
                        OnModeChanged?.Invoke(ModeDetail);
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
