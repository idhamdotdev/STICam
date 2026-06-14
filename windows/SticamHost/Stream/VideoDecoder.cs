using System;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using Sdcb.FFmpeg.Raw;

namespace SticamHost.Stream
{
    /// <summary>
    /// Software H.264 decoder using Sdcb.FFmpeg raw API (ffmpeg.* functions).
    /// Receives raw Annex-B frames, decodes to BGR24 Bitmap, raises OnFrameDecoded.
    /// Runs on a dedicated decode thread — never blocks the UI.
    /// </summary>
    public sealed class VideoDecoder : IDisposable
    {
        public event Action<Bitmap>? OnFrameDecoded;
        public event Action<string>? OnLog;

        public bool MirrorX { get; set; }
        public bool MirrorY { get; set; }


        private readonly System.Collections.Concurrent.BlockingCollection<byte[]> _queue
            = new(boundedCapacity: 8);

        private Thread? _decodeThread;
        private bool    _running;

        // Raw FFmpeg context handles (GCHandles to prevent GC from interfering)
        private IntPtr _ctxPtr;
        private IntPtr _framePtr;
        private IntPtr _pktPtr;

        // H.264 codec ID numeric value (constant in all FFmpeg versions)
        private const int H264CodecId = 27;

        private readonly object _lock = new();

        public void Start(byte[] sps, byte[] pps)
        {
            if (_running)
            {
                unsafe { ReinitDecoder(sps, pps); }
                return;
            }
            _running = true;
            unsafe { InitDecoder(sps, pps); }
            _decodeThread = new Thread(DecodeLoop)
            {
                Name         = "SticamDecode",
                IsBackground = true,
                Priority     = ThreadPriority.AboveNormal,
            };
            _decodeThread.Start();
        }

        public void Stop()
        {
            _running = false;
            _queue.CompleteAdding();
            _decodeThread?.Join(1000);
            unsafe { FreeDecoder(); }
        }

        public void Dispose() => Stop();

        /// <summary>Enqueues an encoded frame for decoding. Drops if queue full.</summary>
        public void PushFrame(byte[] annexB)
        {
            if (!_queue.IsAddingCompleted)
                _queue.TryAdd(annexB);
        }

        // ── Decoder loop ──────────────────────────────────────────────────────

        private void DecodeLoop()
        {
            foreach (var data in _queue.GetConsumingEnumerable())
            {
                if (!_running) break;
                try   { unsafe { DecodeFrame(data); } }
                catch (Exception ex) { Log($"Decode error: {ex.Message}"); }
            }
        }

        // ── FFmpeg init / decode / free ───────────────────────────────────────

        private unsafe void InitDecoder(byte[] sps, byte[] pps)
        {
            // Find H.264 decoder using numeric ID (27 = AV_CODEC_ID_H264 in all FFmpeg versions)
            var codec = ffmpeg.avcodec_find_decoder((AVCodecID)H264CodecId);
            if (codec == null) throw new InvalidOperationException("H.264 decoder not found");

            var ctx = ffmpeg.avcodec_alloc_context3(codec);
            ctx->thread_count = Math.Min(Environment.ProcessorCount, 4);

            // Allocate extradata (SPS + PPS) using FFmpeg allocator with required padding
            int extLen = sps.Length + pps.Length;
            ctx->extradata_size = extLen;
            ctx->extradata = (byte*)ffmpeg.av_mallocz((ulong)(extLen + ffmpeg.AV_INPUT_BUFFER_PADDING_SIZE));
            sps.CopyTo(new Span<byte>(ctx->extradata, sps.Length));
            pps.CopyTo(new Span<byte>(ctx->extradata + sps.Length, pps.Length));

            int ret = ffmpeg.avcodec_open2(ctx, codec, null);
            if (ret < 0) throw new InvalidOperationException($"avcodec_open2 failed: {ret}");

            _ctxPtr   = (IntPtr)ctx;
            _framePtr = (IntPtr)ffmpeg.av_frame_alloc();
            _pktPtr   = (IntPtr)ffmpeg.av_packet_alloc();
            Log("Decoder initialized");
        }

        private unsafe void ReinitDecoder(byte[] sps, byte[] pps)
        {
            lock (_lock)
            {
                FreeDecoder();
                InitDecoder(sps, pps);
            }
        }

        private unsafe void DecodeFrame(byte[] data)
        {
            lock (_lock)
            {
                if (_ctxPtr == IntPtr.Zero) return;

                var ctx   = (AVCodecContext*)_ctxPtr;
                var frame = (AVFrame*)_framePtr;
                var pkt   = (AVPacket*)_pktPtr;

                fixed (byte* p = data)
                {
                    pkt->data = p;
                    pkt->size = data.Length;

                    int ret = ffmpeg.avcodec_send_packet(ctx, pkt);
                    if (ret < 0) { Log($"send_packet error: {ret}"); return; }

                    while (true)
                    {
                        ret = ffmpeg.avcodec_receive_frame(ctx, frame);
                        if (ret == ffmpeg.AVERROR(ffmpeg.EAGAIN) || ret == ffmpeg.AVERROR_EOF) break;
                        if (ret < 0) { Log($"receive_frame error: {ret}"); break; }

                        int w = frame->width;
                        int h = frame->height;
                        var bmp = YuvToBitmap(frame, w, h);
                        if (MirrorX || MirrorY)
                        {
                            var flipType = RotateFlipType.RotateNoneFlipNone;
                            if (MirrorX && MirrorY)      flipType = RotateFlipType.RotateNoneFlipXY;
                            else if (MirrorX)            flipType = RotateFlipType.RotateNoneFlipX;
                            else if (MirrorY)            flipType = RotateFlipType.RotateNoneFlipY;
                            bmp.RotateFlip(flipType);
                        }
                        OnFrameDecoded?.Invoke(bmp);
                        ffmpeg.av_frame_unref(frame);
                    }
                }
            }
        }

        private unsafe Bitmap YuvToBitmap(AVFrame* frame, int w, int h)
        {
            var bmp     = new Bitmap(w, h, System.Drawing.Imaging.PixelFormat.Format24bppRgb);
            var bmpData = bmp.LockBits(
                new Rectangle(0, 0, w, h),
                System.Drawing.Imaging.ImageLockMode.WriteOnly,
                System.Drawing.Imaging.PixelFormat.Format24bppRgb);

            try
            {
                // Manual YUV420P → BGR24 (no swscale dependency)
                // Parallelized over rows using all available CPU cores to handle 2K/4K high resolutions instantly.
                Parallel.For(0, h, row =>
                {
                    byte* y_row   = (byte*)frame->data[0] + row * frame->linesize[0];
                    byte* u_row   = (byte*)frame->data[1] + (row / 2) * frame->linesize[1];
                    byte* v_row   = (byte*)frame->data[2] + (row / 2) * frame->linesize[2];
                    byte* dst_row = (byte*)bmpData.Scan0   + row * bmpData.Stride;

                    for (int col = 0; col < w; col++)
                    {
                        int Y = y_row[col];
                        int uv_col = col >> 1;
                        int U = u_row[uv_col] - 128;
                        int V = v_row[uv_col] - 128;

                        int C = (Y - 16) * 298 + 128;
                        int r = (C + 409 * V) >> 8;
                        int g = (C - 100 * U - 208 * V) >> 8;
                        int b = (C + 516 * U) >> 8;

                        byte* pixel = dst_row + col * 3;
                        pixel[0] = (byte)Math.Clamp(b, 0, 255); // BGR
                        pixel[1] = (byte)Math.Clamp(g, 0, 255);
                        pixel[2] = (byte)Math.Clamp(r, 0, 255);
                    }
                });
            }
            finally { bmp.UnlockBits(bmpData); }

            return bmp;
        }

        private unsafe void FreeDecoder()
        {
            if (_framePtr != IntPtr.Zero)
            {
                var f = (AVFrame*)_framePtr;
                ffmpeg.av_frame_free(&f);
                _framePtr = IntPtr.Zero;
            }
            if (_pktPtr != IntPtr.Zero)
            {
                var p = (AVPacket*)_pktPtr;
                ffmpeg.av_packet_free(&p);
                _pktPtr = IntPtr.Zero;
            }
            if (_ctxPtr != IntPtr.Zero)
            {
                var c = (AVCodecContext*)_ctxPtr;
                ffmpeg.avcodec_free_context(&c);
                _ctxPtr = IntPtr.Zero;
            }
        }

        private void Log(string msg) => OnLog?.Invoke($"[Decoder] {msg}");
    }
}
