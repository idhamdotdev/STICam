using System;
using System.Collections.Concurrent;
using System.Drawing;
using System.Threading;
using System.Threading.Tasks;
using Sdcb.FFmpeg.Raw;

namespace SticamHost.Stream
{
    /// <summary>
    /// Software H.264 decoder. Decoded bitmaps are borrowed by subscribers and
    /// are valid only for the duration of the synchronous callback.
    /// </summary>
    public sealed class VideoDecoder : IDisposable
    {
        public event Action<Bitmap>? OnFrameDecoded;
        public event Action<string>? OnLog;
        public event Action? OnKeyFrameNeeded;

        public bool MirrorX { get; set; }
        public bool MirrorY { get; set; }

        private sealed record EncodedFrame(byte[] Data, bool IsKeyFrame);

        private readonly BlockingCollection<EncodedFrame> _queue = new(boundedCapacity: 8);
        private readonly object _queueLock = new();
        private readonly object _decoderLock = new();
        private Thread? _decodeThread;
        private volatile bool _running;
        private bool _waitingForKeyFrame;

        private IntPtr _ctxPtr;
        private IntPtr _framePtr;
        private IntPtr _pktPtr;

        private const int H264CodecId = 27;

        public void Start(byte[] sps, byte[] pps)
        {
            if (_running)
            {
                unsafe { ReinitDecoder(sps, pps); }
                return;
            }

            unsafe { InitDecoder(sps, pps); }
            _running = true;
            _decodeThread = new Thread(DecodeLoop)
            {
                Name = "SticamDecode",
                IsBackground = true,
                Priority = ThreadPriority.AboveNormal,
            };
            _decodeThread.Start();
        }

        public void Stop()
        {
            _running = false;
            lock (_queueLock)
            {
                if (!_queue.IsAddingCompleted) _queue.CompleteAdding();
            }

            if (_decodeThread != null && !_decodeThread.Join(5000))
                Log("Decoder thread did not stop within 5 seconds; native cleanup is deferred");
            else
                unsafe { FreeDecoder(); }
        }

        public void Dispose() => Stop();

        /// <summary>
        /// Enqueues a complete Annex-B access unit. On overflow, stale frames are
        /// discarded and decoding resumes only at an IDR.
        /// </summary>
        public void PushFrame(byte[] annexB, bool isKeyFrame = false)
        {
            bool requestKeyFrame = false;
            lock (_queueLock)
            {
                if (!_running || _queue.IsAddingCompleted) return;

                if (_waitingForKeyFrame)
                {
                    if (!isKeyFrame) return;
                    _waitingForKeyFrame = false;
                    while (_queue.TryTake(out _)) { }
                    _queue.TryAdd(new EncodedFrame(annexB, true));
                    return;
                }

                if (_queue.TryAdd(new EncodedFrame(annexB, isKeyFrame))) return;

                while (_queue.TryTake(out _)) { }
                _waitingForKeyFrame = !isKeyFrame;
                if (isKeyFrame)
                    _queue.TryAdd(new EncodedFrame(annexB, true));
                else
                    requestKeyFrame = true;
            }

            if (requestKeyFrame) OnKeyFrameNeeded?.Invoke();
        }

        private void DecodeLoop()
        {
            try
            {
                foreach (var frame in _queue.GetConsumingEnumerable())
                {
                    if (!_running) break;
                    try { unsafe { DecodeFrame(frame.Data); } }
                    catch (Exception ex) { Log($"Decode error: {ex.Message}"); }
                }
            }
            finally
            {
                unsafe { FreeDecoder(); }
            }
        }

        private unsafe void InitDecoder(byte[] sps, byte[] pps)
        {
            var codec = ffmpeg.avcodec_find_decoder((AVCodecID)H264CodecId);
            if (codec == null) throw new InvalidOperationException("H.264 decoder not found");

            var ctx = ffmpeg.avcodec_alloc_context3(codec);
            if (ctx == null) throw new OutOfMemoryException("avcodec_alloc_context3 failed");
            AVFrame* frame = null;
            AVPacket* packet = null;

            try
            {
                ctx->thread_count = Math.Min(Environment.ProcessorCount, 4);
                int extLen = checked(sps.Length + pps.Length);
                ctx->extradata_size = extLen;
                ctx->extradata = (byte*)ffmpeg.av_mallocz(
                    (ulong)(extLen + ffmpeg.AV_INPUT_BUFFER_PADDING_SIZE));
                if (ctx->extradata == null) throw new OutOfMemoryException("av_mallocz failed");
                sps.CopyTo(new Span<byte>(ctx->extradata, sps.Length));
                pps.CopyTo(new Span<byte>(ctx->extradata + sps.Length, pps.Length));

                int ret = ffmpeg.avcodec_open2(ctx, codec, null);
                if (ret < 0) throw new InvalidOperationException($"avcodec_open2 failed: {ret}");

                frame = ffmpeg.av_frame_alloc();
                packet = ffmpeg.av_packet_alloc();
                if (frame == null || packet == null)
                    throw new OutOfMemoryException("FFmpeg frame/packet allocation failed");

                _ctxPtr = (IntPtr)ctx;
                _framePtr = (IntPtr)frame;
                _pktPtr = (IntPtr)packet;
            }
            catch
            {
                if (frame != null) ffmpeg.av_frame_free(&frame);
                if (packet != null) ffmpeg.av_packet_free(&packet);
                ffmpeg.avcodec_free_context(&ctx);
                throw;
            }
            Log("Decoder initialized");
        }

        private unsafe void ReinitDecoder(byte[] sps, byte[] pps)
        {
            lock (_decoderLock)
            {
                FreeDecoder();
                InitDecoder(sps, pps);
            }
        }

        private unsafe void DecodeFrame(byte[] data)
        {
            lock (_decoderLock)
            {
                if (_ctxPtr == IntPtr.Zero) return;
                var ctx = (AVCodecContext*)_ctxPtr;
                var frame = (AVFrame*)_framePtr;
                var packet = (AVPacket*)_pktPtr;

                fixed (byte* dataPointer = data)
                {
                    packet->data = dataPointer;
                    packet->size = data.Length;
                    try
                    {
                        int ret = ffmpeg.avcodec_send_packet(ctx, packet);
                        if (ret < 0) { Log($"send_packet error: {ret}"); return; }

                        while (true)
                        {
                            ret = ffmpeg.avcodec_receive_frame(ctx, frame);
                            if (ret == ffmpeg.AVERROR(ffmpeg.EAGAIN) || ret == ffmpeg.AVERROR_EOF) break;
                            if (ret < 0) { Log($"receive_frame error: {ret}"); break; }

                            try
                            {
                                using var bmp = YuvToBitmap(frame, frame->width, frame->height);
                                if (MirrorX || MirrorY)
                                {
                                    var flipType = RotateFlipType.RotateNoneFlipNone;
                                    if (MirrorX && MirrorY) flipType = RotateFlipType.RotateNoneFlipXY;
                                    else if (MirrorX) flipType = RotateFlipType.RotateNoneFlipX;
                                    else if (MirrorY) flipType = RotateFlipType.RotateNoneFlipY;
                                    bmp.RotateFlip(flipType);
                                }
                                OnFrameDecoded?.Invoke(bmp);
                            }
                            finally
                            {
                                ffmpeg.av_frame_unref(frame);
                            }
                        }
                    }
                    finally
                    {
                        packet->data = null;
                        packet->size = 0;
                    }
                }
            }
        }

        private unsafe Bitmap YuvToBitmap(AVFrame* frame, int width, int height)
        {
            var pixelFormat = (AVPixelFormat)frame->format;
            bool fullRange = pixelFormat == AVPixelFormat.Yuvj420p ||
                frame->color_range == AVColorRange.Jpeg;
            if (pixelFormat != AVPixelFormat.Yuv420p && pixelFormat != AVPixelFormat.Yuvj420p)
                throw new NotSupportedException($"Unsupported decoded pixel format: {pixelFormat}");

            var bmp = new Bitmap(width, height, System.Drawing.Imaging.PixelFormat.Format24bppRgb);
            var bmpData = bmp.LockBits(
                new Rectangle(0, 0, width, height),
                System.Drawing.Imaging.ImageLockMode.WriteOnly,
                System.Drawing.Imaging.PixelFormat.Format24bppRgb);

            try
            {
                var parallelOptions = new ParallelOptions
                {
                    MaxDegreeOfParallelism = Math.Min(Environment.ProcessorCount, 4),
                };
                Parallel.For(0, height, parallelOptions, row =>
                {
                    byte* yRow = (byte*)frame->data[0] + row * frame->linesize[0];
                    byte* uRow = (byte*)frame->data[1] + (row / 2) * frame->linesize[1];
                    byte* vRow = (byte*)frame->data[2] + (row / 2) * frame->linesize[2];
                    byte* dstRow = (byte*)bmpData.Scan0 + row * bmpData.Stride;

                    for (int column = 0; column < width; column++)
                    {
                        int y = yRow[column];
                        int u = uRow[column >> 1] - 128;
                        int v = vRow[column >> 1] - 128;
                        int r, g, b;
                        if (fullRange)
                        {
                            int c = y << 8;
                            r = (c + 359 * v) >> 8;
                            g = (c - 88 * u - 183 * v) >> 8;
                            b = (c + 454 * u) >> 8;
                        }
                        else
                        {
                            int c = (y - 16) * 298 + 128;
                            r = (c + 409 * v) >> 8;
                            g = (c - 100 * u - 208 * v) >> 8;
                            b = (c + 516 * u) >> 8;
                        }

                        byte* pixel = dstRow + column * 3;
                        pixel[0] = (byte)Math.Clamp(b, 0, 255);
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
            lock (_decoderLock)
            {
                if (_framePtr != IntPtr.Zero)
                {
                    var frame = (AVFrame*)_framePtr;
                    ffmpeg.av_frame_free(&frame);
                    _framePtr = IntPtr.Zero;
                }
                if (_pktPtr != IntPtr.Zero)
                {
                    var packet = (AVPacket*)_pktPtr;
                    ffmpeg.av_packet_free(&packet);
                    _pktPtr = IntPtr.Zero;
                }
                if (_ctxPtr != IntPtr.Zero)
                {
                    var context = (AVCodecContext*)_ctxPtr;
                    ffmpeg.avcodec_free_context(&context);
                    _ctxPtr = IntPtr.Zero;
                }
            }
        }

        private void Log(string msg) => OnLog?.Invoke($"[Decoder] {msg}");
    }
}
