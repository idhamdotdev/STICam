using System;
using System.Buffers.Binary;
using System.Drawing;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace SticamHost.Stream
{
    // ── Typed-packet constants (must match Android StreamServer) ──────────────

    internal static class PacketType
    {
        public const byte Frame = 0x00;
        public const byte Sps   = 0x01;
        public const byte Pps   = 0x02;
        public const byte Cmd   = 0x10;
    }

    // ── Camera parameter snapshot (sent back to Android) ─────────────────────

    public record CameraParams(
        int   Iso,
        long  ShutterNs,
        float FocusDiopters,
        int   WbKelvin
    );

    // ── Per-frame callback ────────────────────────────────────────────────────

    public sealed class FrameEventArgs : EventArgs
    {
        public required byte[] Data        { get; init; }
        public          bool   IsKeyFrame  { get; init; }
        public          long   PtsUs       { get; init; }
    }

    /// <summary>
    /// Sticam stream receiver — connects to the Android Sticam Node as a TCP
    /// client and processes the typed-packet stream.
    ///
    /// Upstream (Android → Windows): SPS | PPS | Frame packets
    /// Downstream (Windows → Android): JSON control commands
    ///
    /// This class handles the network loop only. Frame decoding and rendering
    /// is done by the caller (VideoDecoder / WinForms PictureBox).
    /// </summary>
    public sealed class H264Receiver : IDisposable
    {
        public readonly string Host;
        public readonly int    Port;

        private TcpListener?          _listener;
        private TcpClient?            _tcp;
        private NetworkStream?        _net;
        private CancellationTokenSource? _cts;

        // Codec config received from device
        private byte[]? _sps;
        private byte[]? _pps;

        // ── Events ────────────────────────────────────────────────────────────

        /// <summary>Raised when SPS + PPS have been received and buffered.</summary>
        public event Action<byte[], byte[]>? OnConfigReceived;  // sps, pps

        /// <summary>Raised for each encoded video frame received.</summary>
        public event EventHandler<FrameEventArgs>? OnFrameReceived;

        /// <summary>Connection state changes.</summary>
        public event Action<bool>?   OnConnectionChanged;  // true=connected
        public event Action<string>? OnLog;

        /// <summary>Raised when a custom command is received from the device.</summary>
        public event Action<string>? OnCommandReceived;


        // ── Stats ─────────────────────────────────────────────────────────────
        public long FramesReceived { get; private set; }
        public long BytesReceived  { get; private set; }

        public H264Receiver(string host, int port = 8765)
        {
            Host = host;
            Port = port;
        }

        // ── Public API ────────────────────────────────────────────────────────

        public void Connect()
        {
            _cts = new CancellationTokenSource();
            Task.Run(() => ReceiveLoop(_cts.Token));
        }

        public void Disconnect()
        {
            _cts?.Cancel();
            try { _listener?.Stop(); } catch { }
            _net?.Close();
            _tcp?.Close();
        }

        public void Dispose() => Disconnect();

        /// <summary>
        /// Sends a JSON control command to the Android device (downstream).
        /// Serializes CameraParams into the command payload:
        ///   {"cmd":"set_params","iso":...,"shutterNs":...,"focusDiopters":...,"wbKelvin":...}
        /// </summary>
        public void SendCameraParams(CameraParams p)
        {
            var json = JsonSerializer.Serialize(new
            {
                cmd            = "set_params",
                iso            = p.Iso,
                shutterNs      = p.ShutterNs,
                focusDiopters  = p.FocusDiopters,
                wbKelvin       = p.WbKelvin,
            });
            SendCommand(json);
        }

        public void SendFaceTracking(bool enabled)
        {
            var json = JsonSerializer.Serialize(new
            {
                cmd           = "set_params",
                face_tracking = enabled
            });
            SendCommand(json);
        }

        public void SendCameraControl(int? iso, float? brightness, float? focus, float? zoom = null, bool? flash = null, string? cameraId = null, string? resolution = null, string? arFilter = null, string? lutFilter = null, string? trackAnchor = null)
        {
            var payload = new System.Collections.Generic.Dictionary<string, object>
            {
                { "cmd", "set_params" }
            };
            if (iso.HasValue) payload["iso"] = iso.Value;
            if (brightness.HasValue) payload["brightness"] = brightness.Value;
            if (focus.HasValue) payload["focus"] = focus.Value;
            if (zoom.HasValue) payload["zoom"] = zoom.Value;
            if (flash.HasValue) payload["flash"] = flash.Value;
            if (!string.IsNullOrEmpty(cameraId)) payload["camera_id"] = cameraId;
            if (!string.IsNullOrEmpty(resolution)) payload["resolution"] = resolution;
            if (!string.IsNullOrEmpty(arFilter)) payload["ar_filter"] = arFilter;
            if (!string.IsNullOrEmpty(lutFilter)) payload["lut_filter"] = lutFilter;
            if (!string.IsNullOrEmpty(trackAnchor)) payload["track_anchor"] = trackAnchor;

            var json = JsonSerializer.Serialize(payload);
            SendCommand(json);
        }

        public void RequestKeyFrame() => SendCommand("{\"cmd\":\"request_idr\"}");

        // ── Receive loop ──────────────────────────────────────────────────────

        private async Task ReceiveLoop(CancellationToken ct)
        {
            try
            {
                Log($"Starting TCP Listener on port {Port}…");
                _listener = new TcpListener(System.Net.IPAddress.Any, Port);
                _listener.Start();
            }
            catch (Exception ex)
            {
                Log($"Failed to start listener: {ex.Message}");
                OnConnectionChanged?.Invoke(false);
                return;
            }

            using (ct.Register(() => _listener.Stop()))
            {
                while (!ct.IsCancellationRequested)
                {
                    try
                    {
                        Log("Waiting for connection...");
                        _tcp = await _listener.AcceptTcpClientAsync(ct);
                        _tcp.NoDelay = true;
                        _tcp.ReceiveBufferSize = 4 * 1024 * 1024;
                        _net = _tcp.GetStream();
                        _sps = null; _pps = null;
                        FramesReceived = 0; BytesReceived = 0;

                        OnConnectionChanged?.Invoke(true);
                        Log("Client connected");

                        await ReadPackets(ct);
                    }
                    catch (OperationCanceledException) { break; }
                    catch (Exception ex)
                    {
                        if (!ct.IsCancellationRequested)
                        {
                            Log($"Connection error: {ex.Message}");
                        }
                        OnConnectionChanged?.Invoke(false);
                    }
                    finally
                    {
                        _net?.Close(); _tcp?.Close();
                    }
                }
            }
            OnConnectionChanged?.Invoke(false);
        }

        private async Task ReadPackets(CancellationToken ct)
        {
            var hdr = new byte[5]; // [type(1)] [length(4)]

            while (!ct.IsCancellationRequested)
            {
                await ReadFully(_net!, hdr, ct);
                byte type = hdr[0];
                int  len  = ReadBigEndianInt32(hdr, 1);

                if (len is <= 0 or > 50_000_000)
                    throw new InvalidDataException($"Bad packet length: {len}");

                var data = new byte[len];
                await ReadFully(_net!, data, ct);
                BytesReceived += len;

                switch (type)
                {
                    case PacketType.Sps:
                        _sps = data;
                        Log($"SPS received: {len}B");
                        TryRaiseConfig();
                        break;

                    case PacketType.Pps:
                        _pps = data;
                        Log($"PPS received: {len}B");
                        TryRaiseConfig();
                        break;

                    case PacketType.Frame:
                        FramesReceived++;
                        OnFrameReceived?.Invoke(this, new FrameEventArgs
                        {
                            Data       = data,
                            IsKeyFrame = IsKeyFrame(data),
                            PtsUs      = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() * 1000,
                        });
                        break;

                    case PacketType.Cmd:
                        OnCommandReceived?.Invoke(Encoding.UTF8.GetString(data));
                        break;

                    default:
                        Log($"Unknown packet type 0x{type:X2}, skipping {len}B");
                        break;
                }
            }
        }

        private void TryRaiseConfig()
        {
            if (_sps != null && _pps != null)
            {
                Log($"Config complete — raising OnConfigReceived");
                OnConfigReceived?.Invoke(_sps, _pps);
            }
        }

        // ── Control command sender ────────────────────────────────────────────

        private void SendCommand(string json)
        {
            var net = _net;
            if (net == null) return;
            try
            {
                var payload = Encoding.UTF8.GetBytes(json);
                var hdr = new byte[5];
                hdr[0] = PacketType.Cmd;
                BinaryPrimitives.WriteInt32BigEndian(hdr.AsSpan(1), payload.Length);
                lock (net)
                {
                    net.Write(hdr);
                    net.Write(payload);
                    net.Flush();
                }
                Log($"CMD sent: {json}");
            }
            catch (Exception ex) { Log($"CMD send error: {ex.Message}"); }
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        private static async Task ReadFully(System.IO.Stream s, byte[] buf, CancellationToken ct)
        {
            int off = 0;
            while (off < buf.Length)
            {
                int n = await s.ReadAsync(buf, off, buf.Length - off, ct);
                if (n == 0) throw new EndOfStreamException();
                off += n;
            }
        }

        private static int ReadBigEndianInt32(byte[] b, int off) =>
            (b[off] << 24) | (b[off+1] << 16) | (b[off+2] << 8) | b[off+3];

        /// <summary>
        /// Heuristic: returns true if the buffer starts with a start code
        /// followed by a NAL type 5 (IDR slice).
        /// </summary>
        private static bool IsKeyFrame(byte[] data)
        {
            if (data.Length < 5) return false;
            // 00 00 00 01 [NAL header]
            if (data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1)
                return (data[4] & 0x1F) == 5;
            // 00 00 01 [NAL header]
            if (data[0] == 0 && data[1] == 0 && data[2] == 1)
                return (data[3] & 0x1F) == 5;
            return false;
        }

        private void Log(string msg) => OnLog?.Invoke($"[Receiver] {msg}");
    }
}
