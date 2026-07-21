using System;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;

namespace SticamHost.Adb
{
    /// <summary>
    /// Maintains an ADB reverse rule so the Android client can connect to the
    /// Windows listener through USB: device tcp:8765 -> host tcp:8765.
    /// </summary>
    public sealed class AdbForwarder : IDisposable
    {
        private static readonly TimeSpan ProcessTimeout = TimeSpan.FromSeconds(5);

        public readonly int HostPort;
        public readonly int DevicePort;

        private readonly object _stateLock = new();
        private CancellationTokenSource? _cts;
        private Task? _loopTask;
        private bool _forwardActive;
        private bool _disposed;

        public event Action<string>? OnLog;
        public event Action<bool>? OnForwardStateChanged;

        public AdbForwarder(int hostPort = 8765, int devicePort = 8765)
        {
            HostPort = hostPort;
            DevicePort = devicePort;
        }

        public void Start()
        {
            lock (_stateLock)
            {
                ObjectDisposedException.ThrowIf(_disposed, this);
                if (_loopTask is { IsCompleted: false }) return;

                _cts?.Dispose();
                _cts = new CancellationTokenSource();
                _loopTask = Task.Run(() => ForwardLoopAsync(_cts.Token));
            }
        }

        public void Stop() => StopAsync().GetAwaiter().GetResult();

        public async Task StopAsync()
        {
            CancellationTokenSource? cts;
            Task? loopTask;
            lock (_stateLock)
            {
                cts = _cts;
                loopTask = _loopTask;
                _cts = null;
                _loopTask = null;
            }

            cts?.Cancel();
            if (loopTask != null)
            {
                try { await loopTask.ConfigureAwait(false); }
                catch (OperationCanceledException) { }
            }

            if (_forwardActive)
            {
                await RemoveForwardAsync(CancellationToken.None).ConfigureAwait(false);
                SetForwardState(false);
            }
            cts?.Dispose();
        }

        public void Dispose()
        {
            lock (_stateLock)
            {
                if (_disposed) return;
                _disposed = true;
            }
            Stop();
        }

        private async Task ForwardLoopAsync(CancellationToken ct)
        {
            while (!ct.IsCancellationRequested)
            {
                bool ok = await ApplyForwardAsync(ct).ConfigureAwait(false);
                if (ct.IsCancellationRequested) break;
                SetForwardState(ok);

                try { await Task.Delay(TimeSpan.FromSeconds(3), ct).ConfigureAwait(false); }
                catch (OperationCanceledException) { break; }
            }
        }

        private async Task<bool> ApplyForwardAsync(CancellationToken ct)
        {
            string? adbPath = RuntimePaths.FindExecutable("adb.exe");
            if (adbPath == null)
            {
                Log("adb not found - USB mode unavailable");
                return false;
            }

            var result = await RunAdbAsync(adbPath, ct,
                "reverse", $"tcp:{HostPort}", $"tcp:{DevicePort}").ConfigureAwait(false);
            if (result.Success)
            {
                Log($"ADB reverse active: Device:{DevicePort} -> PC:{HostPort}");
                return true;
            }

            if (!ct.IsCancellationRequested)
                Log($"ADB reverse failed: {result.Error?.Trim()}");
            return false;
        }

        private async Task RemoveForwardAsync(CancellationToken ct)
        {
            string? adbPath = RuntimePaths.FindExecutable("adb.exe");
            if (adbPath == null) return;

            await RunAdbAsync(adbPath, ct,
                "reverse", "--remove", $"tcp:{DevicePort}").ConfigureAwait(false);
            Log("ADB reverse removed");
        }

        private static async Task<(bool Success, string? Output, string? Error)> RunAdbAsync(
            string adbPath,
            CancellationToken cancellationToken,
            params string[] arguments)
        {
            try
            {
                using var proc = new Process
                {
                    StartInfo = new ProcessStartInfo
                    {
                        FileName = adbPath,
                        UseShellExecute = false,
                        RedirectStandardOutput = true,
                        RedirectStandardError = true,
                        CreateNoWindow = true,
                    }
                };
                foreach (string argument in arguments)
                    proc.StartInfo.ArgumentList.Add(argument);

                proc.Start();
                Task<string> stdoutTask = proc.StandardOutput.ReadToEndAsync(cancellationToken);
                Task<string> stderrTask = proc.StandardError.ReadToEndAsync(cancellationToken);
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                timeout.CancelAfter(ProcessTimeout);

                try
                {
                    await proc.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    try { proc.Kill(entireProcessTree: true); } catch { }
                    try { await proc.WaitForExitAsync(CancellationToken.None).ConfigureAwait(false); } catch { }
                    if (cancellationToken.IsCancellationRequested)
                        throw;
                    return (false, null, $"adb timed out after {ProcessTimeout.TotalSeconds:0}s");
                }

                string stdout = await stdoutTask.ConfigureAwait(false);
                string stderr = await stderrTask.ConfigureAwait(false);
                return (proc.ExitCode == 0, stdout, stderr);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                return (false, null, "cancelled");
            }
            catch (Exception ex)
            {
                return (false, null, ex.Message);
            }
        }

        private void SetForwardState(bool active)
        {
            if (_forwardActive == active) return;
            _forwardActive = active;
            OnForwardStateChanged?.Invoke(active);
        }

        private void Log(string msg) => OnLog?.Invoke($"[ADB] {msg}");
    }
}
