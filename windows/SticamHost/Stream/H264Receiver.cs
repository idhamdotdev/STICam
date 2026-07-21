using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Channels;
using System.Threading.Tasks;
using SticamHost.Security;

namespace SticamHost.Stream
{
    internal static class PacketType
    {
        public const byte Frame = 0x00;
        public const byte Sps = 0x01;
        public const byte Pps = 0x02;
        public const byte Cmd = 0x10;
    }

    public record CameraParams(int Iso, long ShutterNs, float FocusDiopters, int WbKelvin);

    public sealed class FrameEventArgs : EventArgs
    {
        public required byte[] Data { get; init; }
        public bool IsKeyFrame { get; init; }
        public long PtsUs { get; init; }
    }

    /// <summary>
    /// Listens for the Android TCP client and processes the typed-packet stream.
    /// Outgoing control writes are coalesced and serialized by one background writer.
    /// </summary>
    public sealed class H264Receiver : IDisposable
    {
        private const int MaxFrameLength = 16 * 1024 * 1024;
        private const int MaxConfigLength = 1024 * 1024;
        private const int MaxCommandLength = 64 * 1024;
        private static readonly TimeSpan FirstRecordTimeout = TimeSpan.FromSeconds(10);

        public readonly string Host;
        public readonly int Port;
        private readonly string _pairingKey;

        private readonly object _stateLock = new();
        private readonly ConcurrentDictionary<string, object> _pendingParams = new();
        private readonly ConcurrentQueue<string> _pendingCommands = new();
        private readonly Channel<byte> _writeSignal = Channel.CreateBounded<byte>(
            new BoundedChannelOptions(1)
            {
                FullMode = BoundedChannelFullMode.DropWrite,
                SingleReader = true,
                SingleWriter = false,
            });

        private TcpListener? _listener;
        private TcpClient? _tcp;
        private NetworkStream? _net;
        private CancellationTokenSource? _cts;
        private Task? _receiveTask;
        private byte[]? _sps;
        private byte[]? _pps;
        private long _framesReceived;
        private long _bytesReceived;
        private bool _disposed;

        public event Action<byte[], byte[]>? OnConfigReceived;
        public event EventHandler<FrameEventArgs>? OnFrameReceived;
        public event Action<bool>? OnConnectionChanged;
        public event Action<string>? OnLog;
        public event Action<string>? OnCommandReceived;

        public long FramesReceived => Interlocked.Read(ref _framesReceived);
        public long BytesReceived => Interlocked.Read(ref _bytesReceived);

        public H264Receiver(string host, string pairingKey, int port = 8765)
        {
            Host = host;
            _pairingKey = pairingKey;
            Port = port;
        }

        public void Connect()
        {
            lock (_stateLock)
            {
                ObjectDisposedException.ThrowIf(_disposed, this);
                if (_receiveTask is { IsCompleted: false }) return;

                _cts?.Dispose();
                var cts = new CancellationTokenSource();
                _cts = cts;
                _receiveTask = Task.Run(() => ReceiveLoopAsync(cts.Token));
            }
        }

        public void Disconnect()
        {
            CancellationTokenSource? cts;
            Task? receiveTask;
            lock (_stateLock)
            {
                cts = _cts;
                _cts = null;
                receiveTask = _receiveTask;
            }
            cts?.Cancel();
            try { _listener?.Stop(); } catch { }
            try { _net?.Close(); } catch { }
            try { _tcp?.Close(); } catch { }
            if (receiveTask != null && receiveTask.Id != Task.CurrentId)
            {
                try { receiveTask.GetAwaiter().GetResult(); }
                catch (OperationCanceledException) { }
                catch (ObjectDisposedException) { }
                catch (IOException) { }
                catch (SocketException) { }
                catch (Exception ex) { Log($"Receiver shutdown: {ex.Message}"); }
            }
            cts?.Dispose();
            lock (_stateLock)
            {
                if (_receiveTask == receiveTask) _receiveTask = null;
            }
        }

        public void Dispose()
        {
            lock (_stateLock)
            {
                if (_disposed) return;
                _disposed = true;
            }
            Disconnect();
        }

        public void SendCameraParams(CameraParams p) => QueueParameters(new Dictionary<string, object>
        {
            ["iso"] = p.Iso,
            ["shutterNs"] = p.ShutterNs,
            ["focus"] = p.FocusDiopters,
            ["wbKelvin"] = p.WbKelvin,
        });

        public void SendFaceTracking(bool enabled) => QueueParameters(new Dictionary<string, object>
        {
            ["face_tracking"] = enabled,
        });

        public void SendCameraControl(
            int? iso,
            float? brightness,
            float? focus,
            float? zoom = null,
            bool? flash = null,
            string? cameraId = null,
            string? resolution = null,
            string? arFilter = null,
            string? lutFilter = null)
        {
            var parameters = new Dictionary<string, object>();
            if (iso.HasValue) parameters["iso"] = iso.Value;
            if (brightness.HasValue) parameters["brightness"] = brightness.Value;
            if (focus.HasValue) parameters["focus"] = focus.Value;
            if (zoom.HasValue) parameters["zoom"] = zoom.Value;
            if (flash.HasValue) parameters["flash"] = flash.Value;
            if (!string.IsNullOrEmpty(cameraId)) parameters["camera_id"] = cameraId;
            if (!string.IsNullOrEmpty(resolution)) parameters["resolution"] = resolution;
            if (!string.IsNullOrEmpty(arFilter)) parameters["ar_filter"] = arFilter;
            if (!string.IsNullOrEmpty(lutFilter)) parameters["lut_filter"] = lutFilter;
            QueueParameters(parameters);
        }

        public void RequestKeyFrame()
        {
            _pendingCommands.Enqueue("{\"cmd\":\"request_idr\"}");
            SignalWriter();
        }

        private void QueueParameters(IReadOnlyDictionary<string, object> parameters)
        {
            foreach (var pair in parameters)
                _pendingParams[pair.Key] = pair.Value;
            SignalWriter();
        }

        private void SignalWriter() => _writeSignal.Writer.TryWrite(0);

        private async Task ReceiveLoopAsync(CancellationToken ct)
        {
            IPAddress bindAddress;
            if (!IPAddress.TryParse(Host, out bindAddress!))
            {
                Log($"Invalid local bind address: {Host}");
                OnConnectionChanged?.Invoke(false);
                return;
            }

            try
            {
                Log($"Starting TCP listener on {bindAddress}:{Port}...");
                _listener = new TcpListener(bindAddress, Port);
                _listener.Start();
            }
            catch (Exception ex)
            {
                Log($"Failed to start listener: {ex.Message}");
                OnConnectionChanged?.Invoke(false);
                return;
            }

            try
            {
                while (!ct.IsCancellationRequested)
                {
                    bool connected = false;
                    using var connectionCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                    try
                    {
                        Log("Waiting for connection...");
                        _tcp = await _listener.AcceptTcpClientAsync(ct).ConfigureAwait(false);
                        _tcp.NoDelay = true;
                        _tcp.ReceiveBufferSize = 4 * 1024 * 1024;
                        _net = _tcp.GetStream();
                        await using var secure = await SecureChannel.AcceptAsync(
                            _net,
                            _pairingKey,
                            connectionCts.Token).ConfigureAwait(false);
                        using var firstRecordCts = CancellationTokenSource.CreateLinkedTokenSource(
                            connectionCts.Token);
                        firstRecordCts.CancelAfter(FirstRecordTimeout);
                        SecurePacket firstPacket = await secure.ReceiveAsync(
                            firstRecordCts.Token).ConfigureAwait(false);
                        _sps = null;
                        _pps = null;
                        Interlocked.Exchange(ref _framesReceived, 0);
                        Interlocked.Exchange(ref _bytesReceived, 0);

                        connected = true;
                        OnConnectionChanged?.Invoke(true);
                        Log($"Client connected: {_tcp.Client.RemoteEndPoint}");

                        Task writerTask = CommandWriterLoopAsync(secure, connectionCts.Token);
                        Task readerTask = ReadPacketsAsync(
                            secure,
                            firstPacket,
                            connectionCts.Token);
                        try
                        {
                            Task completed = await Task.WhenAny(readerTask, writerTask).ConfigureAwait(false);
                            await completed.ConfigureAwait(false);
                        }
                        finally
                        {
                            connectionCts.Cancel();
                            try { _net?.Close(); } catch { }
                            try { await Task.WhenAll(readerTask, writerTask).ConfigureAwait(false); }
                            catch (OperationCanceledException) { }
                            catch when (readerTask.IsFaulted || writerTask.IsFaulted) { }
                        }
                    }
                    catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
                    catch (Exception ex)
                    {
                        if (!ct.IsCancellationRequested)
                            Log($"Connection error: {ex.Message}");
                    }
                    finally
                    {
                        try { _net?.Close(); } catch { }
                        try { _tcp?.Close(); } catch { }
                        _net = null;
                        _tcp = null;
                        if (connected) OnConnectionChanged?.Invoke(false);
                    }
                }
            }
            finally
            {
                try { _listener.Stop(); } catch { }
                _listener = null;
            }
        }

        private async Task ReadPacketsAsync(
            SecureChannel secure,
            SecurePacket firstPacket,
            CancellationToken ct)
        {
            ProcessPacket(firstPacket);
            while (!ct.IsCancellationRequested)
            {
                SecurePacket packet = await secure.ReceiveAsync(ct).ConfigureAwait(false);
                ProcessPacket(packet);
            }
        }

        private void ProcessPacket(SecurePacket packet)
        {
            byte type = packet.Type;
            byte[] data = packet.Payload;
            int len = data.Length;
            int limit = type switch
            {
                PacketType.Frame => MaxFrameLength,
                PacketType.Sps or PacketType.Pps => MaxConfigLength,
                PacketType.Cmd => MaxCommandLength,
                _ => MaxCommandLength,
            };
            if (len is <= 0 || len > limit)
                throw new InvalidDataException($"Bad packet length {len} for type 0x{type:X2}");

            Interlocked.Add(ref _bytesReceived, len);

            switch (type)
            {
                case PacketType.Sps:
                    _sps = data;
                    _pps = null;
                    Log($"SPS received: {len}B");
                    break;
                case PacketType.Pps:
                    _pps = data;
                    Log($"PPS received: {len}B");
                    TryRaiseConfig();
                    break;
                case PacketType.Frame:
                    Interlocked.Increment(ref _framesReceived);
                    OnFrameReceived?.Invoke(this, new FrameEventArgs
                    {
                        Data = data,
                        IsKeyFrame = ContainsIdr(data),
                        PtsUs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() * 1000,
                    });
                    break;
                case PacketType.Cmd:
                    OnCommandReceived?.Invoke(Encoding.UTF8.GetString(data));
                    break;
                default:
                    Log($"Unknown packet type 0x{type:X2}, skipped {len}B");
                    break;
            }
        }

        private void TryRaiseConfig()
        {
            if (_sps != null && _pps != null)
                OnConfigReceived?.Invoke(_sps, _pps);
        }

        private async Task CommandWriterLoopAsync(SecureChannel secure, CancellationToken ct)
        {
            while (await _writeSignal.Reader.WaitToReadAsync(ct).ConfigureAwait(false))
            {
                while (_writeSignal.Reader.TryRead(out _)) { }

                while (_pendingCommands.TryDequeue(out string? command))
                    await WriteCommandAsync(secure, command, ct).ConfigureAwait(false);

                if (!_pendingParams.IsEmpty)
                {
                    var payload = new Dictionary<string, object> { ["cmd"] = "set_params" };
                    foreach (var pair in _pendingParams)
                    {
                        if (_pendingParams.TryRemove(pair.Key, out object? value))
                            payload[pair.Key] = value;
                    }
                    if (payload.Count > 1)
                        await WriteCommandAsync(secure, JsonSerializer.Serialize(payload), ct).ConfigureAwait(false);
                }
            }
        }

        private async Task WriteCommandAsync(SecureChannel secure, string json, CancellationToken ct)
        {
            byte[] payload = Encoding.UTF8.GetBytes(json);
            if (payload.Length > MaxCommandLength)
                throw new InvalidDataException("Command payload is too large.");
            await secure.SendAsync(PacketType.Cmd, payload, ct).ConfigureAwait(false);
            Log($"CMD sent: {json}");
        }

        internal static bool ContainsIdr(ReadOnlySpan<byte> data)
        {
            for (int i = 0; i + 3 < data.Length; i++)
            {
                int nalOffset = -1;
                if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1)
                    nalOffset = i + 3;
                else if (i + 4 < data.Length && data[i] == 0 && data[i + 1] == 0 &&
                         data[i + 2] == 0 && data[i + 3] == 1)
                    nalOffset = i + 4;

                if (nalOffset >= 0 && nalOffset < data.Length && (data[nalOffset] & 0x1F) == 5)
                    return true;
            }
            return false;
        }

        private void Log(string msg) => OnLog?.Invoke($"[Receiver] {msg}");
    }
}
