using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;

namespace SticamHost.VirtualCamera
{
    /// <summary>
    /// Pipes decoded BGR24 frames into a bundled ffmpeg.exe process.
    ///
    /// Output options (selected at runtime):
    ///   A) RTSP stream  → rtsp://127.0.0.1:8554/sticam   (always available)
    ///   B) OBS VirtualCam (DirectShow) → detected automatically
    ///
    /// OBS/VLC/Zoom can consume the RTSP URL as a media source.
    /// When OBS VirtualCam is installed, the feed also appears as a real webcam
    /// device in all video-conferencing apps.
    ///
    /// Frame flow:
    ///   VideoDecoder.OnFrameDecoded → FfmpegPipe.PushFrame()
    ///       → BGR24 pixels written to ffmpeg stdin
    ///           → ffmpeg encodes → RTSP / DirectShow output
    /// </summary>
    public sealed class FfmpegPipe : IDisposable
    {
        // ── Config ────────────────────────────────────────────────────────────

        public readonly int  Width;
        public readonly int  Height;
        public readonly int  Fps;
        public readonly bool UseObsVirtualCam;

        public const string RtspUrl = "rtsp://127.0.0.1:8554/sticam";

        private static readonly string FfmpegPath = LocateFfmpeg();

        // ── State ─────────────────────────────────────────────────────────────

        private Process?          _proc;
        private BinaryWriter?     _stdin;

        private readonly byte[] _pixelBuf;  // reused per frame
        private          bool   _running;

        public event Action<string>? OnLog;
        public event Action<bool>?   OnRunningChanged;

        public FfmpegPipe(int width, int height, int fps, bool useObsVirtualCam = false)
        {
            Width            = width;
            Height           = height;
            Fps              = fps;
            UseObsVirtualCam = useObsVirtualCam;
            _pixelBuf        = new byte[width * height * 3]; // BGR24
        }

        // ── Public API ────────────────────────────────────────────────────────

        public bool Start()
        {
            if (_running) return true;
            if (!File.Exists(FfmpegPath))
            {
                Log($"ffmpeg not found at: {FfmpegPath}");
                return false;
            }

            var args = BuildFfmpegArgs();
            Log($"Starting ffmpeg: {args}");

            _proc = new Process
            {
                StartInfo = new ProcessStartInfo
                {
                    FileName               = FfmpegPath,
                    Arguments              = args,
                    UseShellExecute        = false,
                    RedirectStandardInput  = true,
                    RedirectStandardError  = true,
                    CreateNoWindow         = true,
                },
                EnableRaisingEvents = true,
            };

            _proc.ErrorDataReceived += (_, e) => { if (e.Data != null) Log($"[ffmpeg] {e.Data}"); };
            _proc.Exited += (_, _) =>
            {
                _running = false;
                OnRunningChanged?.Invoke(false);
                Log("ffmpeg process exited");
            };

            try
            {
                _proc.Start();
                _proc.BeginErrorReadLine();
                _stdin  = new BinaryWriter(_proc.StandardInput.BaseStream);
                _running = true;
                OnRunningChanged?.Invoke(true);
                Log($"Virtual camera active → {(UseObsVirtualCam ? "OBS VirtualCam" : RtspUrl)}");
                return true;
            }
            catch (Exception ex)
            {
                Log($"ffmpeg start failed: {ex.Message}");
                return false;
            }
        }

        public void Stop()
        {
            _running = false;
            try { _stdin?.Close(); } catch { }
            try { _proc?.Kill(); } catch { }
            _proc?.Dispose(); _proc = null;
            OnRunningChanged?.Invoke(false);
        }

        public void Dispose() => Stop();

        /// <summary>
        /// Pushes one decoded Bitmap frame into the ffmpeg stdin pipe.
        /// Called from the VideoDecoder output thread — must be fast.
        /// </summary>
        public void PushFrame(Bitmap bmp)
        {
            if (!_running || _stdin == null) return;

            try
            {
                // Lock bitmap and copy BGR24 bytes into reusable buffer
                var data = bmp.LockBits(
                    new Rectangle(0, 0, bmp.Width, bmp.Height),
                    ImageLockMode.ReadOnly,
                    PixelFormat.Format24bppRgb);

                try
                {
                    int stride    = Math.Abs(data.Stride);
                    int rowBytes  = Width * 3;
                    var srcPtr    = data.Scan0;

                    if (stride == rowBytes)
                    {
                        // Performance: single block memory copy at L1/L2 cache bus speed
                        Marshal.Copy(srcPtr, _pixelBuf, 0, _pixelBuf.Length);
                    }
                    else
                    {
                        for (int row = 0; row < Height; row++)
                        {
                            Marshal.Copy(
                                IntPtr.Add(srcPtr, row * stride),
                                _pixelBuf,
                                row * rowBytes,
                                rowBytes);
                        }
                    }
                }
                finally { bmp.UnlockBits(data); }

                _stdin.Write(_pixelBuf);
                _stdin.Flush();
            }
            catch (IOException)
            {
                // Pipe broken — ffmpeg died
                _running = false;
                OnRunningChanged?.Invoke(false);
            }
            catch (Exception ex)
            {
                Log($"PushFrame error: {ex.Message}");
            }
        }

        // ── ffmpeg argument builder ───────────────────────────────────────────

        private string BuildFfmpegArgs()
        {
            // Raw BGR24 pixel input from stdin
            string input =
                $"-f rawvideo -pixel_format bgr24 -video_size {Width}x{Height} " +
                $"-framerate {Fps} -i pipe:0";

            // Shared encoder settings (ultrafast, low-latency)
            string encode =
                "-vcodec libx264 -preset ultrafast -tune zerolatency " +
                "-pix_fmt yuv420p -g 30 -bf 0";

            if (UseObsVirtualCam)
            {
                // Push to OBS Virtual Camera DirectShow device
                // Requires OBS Studio (with VirtualCam plugin) to be installed.
                // The device name must match exactly what's registered.
                string devName = DetectObsVirtualCamName() ?? "OBS Virtual Camera";
                return $"-y {input} {encode} " +
                       $"-f dshow \"video={devName}\" 2>&1";
            }
            else
            {
                // RTSP server — OBS/VLC can consume rtsp://127.0.0.1:8554/sticam
                // -rtsp_transport tcp: avoids UDP fragmentation on loopback
                return $"-y {input} {encode} " +
                       $"-f rtsp -rtsp_transport tcp {RtspUrl}";
            }
        }

        // ── OBS VirtualCam detection ──────────────────────────────────────────

        /// <summary>
        /// Checks the Windows registry for any registered DirectShow video
        /// capture device whose friendly name contains "OBS" or "Virtual".
        /// Returns the device name string suitable for -f dshow.
        /// </summary>
        public static string? DetectObsVirtualCamName()
        {
            // Common names registered by OBS VirtualCam:
            string[] candidates = {
                "Sticam Camera",
                "OBS Virtual Camera",
                "OBS-Camera",
                "OBS-VirtualCam",
            };

            try
            {
                using var root = Microsoft.Win32.Registry.ClassesRoot.OpenSubKey(
                    @"CLSID\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\Instance");
                if (root == null) return candidates[0];

                foreach (var sub in root.GetSubKeyNames())
                {
                    using var k = root.OpenSubKey(sub);
                    var name = k?.GetValue("FriendlyName")?.ToString();
                    if (name != null)
                    {
                        foreach (var c in candidates)
                            if (name.Contains(c, StringComparison.OrdinalIgnoreCase))
                                return name;
                    }
                }
            }
            catch { }

            return candidates[0]; // default fallback
        }

        public static bool IsObsVirtualCamInstalled()
            => DetectObsVirtualCamName() != null;

        // ── Helpers ───────────────────────────────────────────────────────────

        private static string LocateFfmpeg()
        {
            // 1. Temp folder extracted tools\ffmpeg.exe
            string tempPath = Path.Combine(Path.GetTempPath(), "SticamHost", "tools", "ffmpeg.exe");
            if (File.Exists(tempPath)) return tempPath;

            // 2. Bundled tools\ffmpeg.exe
            string bundled = Path.Combine(
                AppDomain.CurrentDomain.BaseDirectory, "tools", "ffmpeg.exe");
            if (File.Exists(bundled)) return bundled;

            // 3. System PATH
            return "ffmpeg";
        }

        private void Log(string msg) => OnLog?.Invoke($"[VirtualCam] {msg}");
    }
}
