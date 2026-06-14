using System;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

namespace SticamHost.Adb
{
    /// <summary>
    /// Manages ADB port forwarding so the Windows host can reach the Sticam Node
    /// over USB without the user opening a terminal.
    ///
    /// Runs: adb forward tcp:<hostPort> tcp:<devicePort>
    ///
    /// The bundled adb.exe is expected at: <appDir>\tools\adb.exe
    /// Falls back to adb.exe on system PATH if not found locally.
    /// </summary>
    public sealed class AdbForwarder : IDisposable
    {
        private static readonly string AdbPath = LocateAdb();

        public readonly int HostPort;
        public readonly int DevicePort;

        private CancellationTokenSource? _cts;
        private bool _forwardActive;

        public event Action<string>?  OnLog;
        public event Action<bool>?    OnForwardStateChanged;  // true = active

        public AdbForwarder(int hostPort = 8765, int devicePort = 8765)
        {
            HostPort   = hostPort;
            DevicePort = devicePort;
        }

        // ── Public API ────────────────────────────────────────────────────────

        /// <summary>
        /// Sets up port forwarding and polls every 3 seconds to keep it alive
        /// (handles USB replug without user intervention).
        /// </summary>
        public void Start()
        {
            _cts = new CancellationTokenSource();
            Task.Run(() => ForwardLoop(_cts.Token));
        }

        /// <summary>Tears down the forward rule and stops polling.</summary>
        public void Stop()
        {
            _cts?.Cancel();
            if (_forwardActive) RemoveForward();
        }

        public void Dispose() => Stop();

        // ── Internal ──────────────────────────────────────────────────────────

        private async Task ForwardLoop(CancellationToken ct)
        {
            while (!ct.IsCancellationRequested)
            {
                bool ok = ApplyForward();
                if (ok != _forwardActive)
                {
                    _forwardActive = ok;
                    OnForwardStateChanged?.Invoke(ok);
                }
                try { await Task.Delay(TimeSpan.FromSeconds(3), ct); }
                catch (OperationCanceledException) { break; }
            }
        }

        private bool ApplyForward()
        {
            if (!File.Exists(AdbPath) && !IsOnPath("adb"))
            {
                Log("adb not found — USB mode unavailable");
                return false;
            }

            var result = RunAdb($"reverse tcp:{HostPort} tcp:{DevicePort}");
            if (result.Success)
            {
                Log($"ADB reverse active: Device:{DevicePort} → PC:{HostPort}");
                return true;
            }
            else
            {
                Log($"ADB reverse failed: {result.Error?.Trim()}");
                return false;
            }
        }

        private void RemoveForward()
        {
            RunAdb($"reverse --remove tcp:{DevicePort}");
            Log("ADB reverse removed");
        }

        // ── ADB process helpers ───────────────────────────────────────────────

        private static (bool Success, string? Output, string? Error) RunAdb(string args)
        {
            try
            {
                using var proc = new Process
                {
                    StartInfo = new ProcessStartInfo
                    {
                        FileName               = AdbPath,
                        Arguments              = args,
                        UseShellExecute        = false,
                        RedirectStandardOutput = true,
                        RedirectStandardError  = true,
                        CreateNoWindow         = true,
                    }
                };
                proc.Start();
                string stdout = proc.StandardOutput.ReadToEnd();
                string stderr = proc.StandardError.ReadToEnd();
                proc.WaitForExit(3000);
                return (proc.ExitCode == 0, stdout, stderr);
            }
            catch (Exception ex)
            {
                return (false, null, ex.Message);
            }
        }

        private static string LocateAdb()
        {
            // 1. Temp folder extracted tools\adb.exe
            string tempPath = Path.Combine(Path.GetTempPath(), "STICamHost", "tools", "adb.exe");
            if (File.Exists(tempPath)) return tempPath;

            // 2. Bundled tools\adb.exe next to the exe
            string bundled = Path.Combine(
                AppDomain.CurrentDomain.BaseDirectory, "tools", "adb.exe");
            if (File.Exists(bundled)) return bundled;

            // 3. System PATH — just return "adb" and let the OS resolve it
            return "adb";
        }

        private static bool IsOnPath(string exe)
        {
            try
            {
                using var p = Process.Start(
                    new ProcessStartInfo(exe, "--version")
                    { UseShellExecute = false, CreateNoWindow = true,
                      RedirectStandardOutput = true, RedirectStandardError = true });
                p?.WaitForExit(1000);
                return p?.ExitCode == 0;
            }
            catch { return false; }
        }

        private void Log(string msg) =>
            OnLog?.Invoke($"[ADB] {msg}");
    }
}
