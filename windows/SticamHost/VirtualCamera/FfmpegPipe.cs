using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;

namespace SticamHost.VirtualCamera
{
    /// <summary>
    /// Pipes decoded BGR24 frames into ffmpeg and publishes them to an external
    /// RTSP server at rtsp://127.0.0.1:8554/sticam.
    ///
    /// Frame flow:
    ///   VideoDecoder.OnFrameDecoded → FfmpegPipe.PushFrame()
    ///       → BGR24 pixels written to ffmpeg stdin
    ///           → ffmpeg encodes → RTSP output
    /// </summary>
    public sealed class FfmpegPipe : IDisposable
    {
        // ── Config ────────────────────────────────────────────────────────────

        public readonly int  Width;
        public readonly int  Height;
        public readonly int  Fps;

        public const string RtspUrl = "rtsp://127.0.0.1:8554/sticam";

        private static string? FfmpegPath => RuntimePaths.FindExecutable("ffmpeg.exe");

        // ── State ─────────────────────────────────────────────────────────────

        private Process?          _proc;
        private BinaryWriter?     _stdin;
        private readonly object   _stateLock = new();
        private BlockingCollection<Bitmap>? _frameQueue;
        private Thread? _writerThread;

        private readonly byte[] _pixelBuf;  // reused per frame
        private volatile bool   _running;

        public event Action<string>? OnLog;
        public event Action<bool>?   OnRunningChanged;

        public FfmpegPipe(int width, int height, int fps)
        {
            Width            = width;
            Height           = height;
            Fps              = fps;
            _pixelBuf        = new byte[width * height * 3]; // BGR24
        }

        // ── Public API ────────────────────────────────────────────────────────

        public bool Start()
        {
            if (_running) return true;
            string? ffmpegPath = FfmpegPath;
            if (ffmpegPath == null)
            {
                Log("ffmpeg not found in the app tools directory or PATH");
                return false;
            }
            if (!IsRtspServerAvailable())
            {
                Log("No RTSP server is listening on 127.0.0.1:8554. Start MediaMTX or another RTSP server first.");
                return false;
            }

            var args = BuildFfmpegArgs();
            Log($"Starting ffmpeg: {args}");

            var process = new Process
            {
                StartInfo = new ProcessStartInfo
                {
                    FileName               = ffmpegPath,
                    Arguments              = args,
                    UseShellExecute        = false,
                    RedirectStandardInput  = true,
                    RedirectStandardError  = true,
                    CreateNoWindow         = true,
                },
                EnableRaisingEvents = true,
            };
            _proc = process;

            process.ErrorDataReceived += (_, e) => { if (e.Data != null) Log($"[ffmpeg] {e.Data}"); };
            process.Exited += (_, _) =>
            {
                BlockingCollection<Bitmap>? queue;
                lock (_stateLock)
                {
                    if (!ReferenceEquals(_proc, process)) return;
                    _running = false;
                    queue = _frameQueue;
                }
                queue?.CompleteAdding();
                OnRunningChanged?.Invoke(false);
                Log("ffmpeg process exited");
            };

            try
            {
                process.Start();
                process.BeginErrorReadLine();
                _stdin  = new BinaryWriter(process.StandardInput.BaseStream);
                if (process.WaitForExit(750))
                {
                    Log($"ffmpeg exited during startup with code {process.ExitCode}");
                    Stop();
                    return false;
                }
                var queue = new BlockingCollection<Bitmap>(boundedCapacity: 1);
                var writerThread = new Thread(() => FrameWriterLoop(queue))
                {
                    Name = "SticamRtspWriter",
                    IsBackground = true,
                };
                lock (_stateLock)
                {
                    if (!ReferenceEquals(_proc, process) || process.HasExited)
                    {
                        Stop();
                        return false;
                    }
                    _frameQueue = queue;
                    _writerThread = writerThread;
                    _running = true;
                }
                writerThread.Start();
                OnRunningChanged?.Invoke(true);
                Log($"RTSP publisher active → {RtspUrl}");
                return true;
            }
            catch (Exception ex)
            {
                Log($"ffmpeg start failed: {ex.Message}");
                Stop();
                return false;
            }
        }

        public void Stop()
        {
            Process? process;
            BinaryWriter? stdin;
            BlockingCollection<Bitmap>? queue;
            Thread? writerThread;
            lock (_stateLock)
            {
                _running = false;
                process = _proc;
                stdin = _stdin;
                _proc = null;
                _stdin = null;
                queue = _frameQueue;
                writerThread = _writerThread;
                _frameQueue = null;
                _writerThread = null;
            }
            queue?.CompleteAdding();
            try { stdin?.Close(); } catch { }
            try
            {
                if (process is { HasExited: false })
                {
                    process.Kill(entireProcessTree: true);
                    process.WaitForExit(2000);
                }
            }
            catch { }
            bool writerStopped = writerThread?.Join(2000) ?? true;
            if (writerStopped) queue?.Dispose();
            process?.Dispose();
            OnRunningChanged?.Invoke(false);
        }

        public void Dispose() => Stop();

        /// <summary>
        /// Pushes one decoded Bitmap frame into the ffmpeg stdin pipe.
        /// Called from the VideoDecoder output thread — must be fast.
        /// </summary>
        public void PushFrame(Bitmap bmp)
        {
            Bitmap clone;
            try { clone = (Bitmap)bmp.Clone(); }
            catch (Exception ex)
            {
                Log($"Frame clone failed: {ex.Message}");
                return;
            }

            lock (_stateLock)
            {
                var queue = _frameQueue;
                if (!_running || queue == null || queue.IsAddingCompleted)
                {
                    clone.Dispose();
                    return;
                }
                try
                {
                    if (queue.TryTake(out Bitmap? stale)) stale.Dispose();
                    if (!queue.TryAdd(clone)) clone.Dispose();
                }
                catch (InvalidOperationException)
                {
                    clone.Dispose();
                }
            }
        }

        private void FrameWriterLoop(BlockingCollection<Bitmap> queue)
        {
            foreach (Bitmap frame in queue.GetConsumingEnumerable())
            {
                try { WriteFrame(frame); }
                finally { frame.Dispose(); }
            }
        }

        private void WriteFrame(Bitmap bmp)
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

            // OBS/VLC can consume the RTSP URL. TCP avoids UDP fragmentation
            // on loopback.
            return $"-y {input} {encode} " +
                   $"-f rtsp -rtsp_transport tcp {RtspUrl}";
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
                "OBS Virtual Camera",
                "OBS-Camera",
                "OBS-VirtualCam",
            };

            try
            {
                using var root = Microsoft.Win32.Registry.ClassesRoot.OpenSubKey(
                    @"CLSID\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\Instance");
                if (root == null) return null;

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

            return null;
        }

        public static bool IsObsVirtualCamInstalled()
            => DetectObsVirtualCamName() != null;

        // ── Helpers ───────────────────────────────────────────────────────────

        public static bool IsRtspServerAvailable()
        {
            try
            {
                using var client = new TcpClient();
                using var timeout = new CancellationTokenSource(TimeSpan.FromMilliseconds(500));
                client.ConnectAsync("127.0.0.1", 8554, timeout.Token).GetAwaiter().GetResult();
                return client.Connected;
            }
            catch { return false; }
        }

        private void Log(string msg) => OnLog?.Invoke($"[VirtualCam] {msg}");
    }
}
