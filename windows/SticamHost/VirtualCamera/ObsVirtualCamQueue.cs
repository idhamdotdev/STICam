using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO.MemoryMappedFiles;
using System.Runtime.InteropServices;
using System.Threading;
using System.Collections.Concurrent;

namespace SticamHost.VirtualCamera
{
    /// <summary>
    /// Writes raw NV12 video frames directly to the OBS Virtual Camera shared memory region.
    /// This makes the video instantly visible in the standalone DirectShow virtual camera device 
    /// (e.g. "Sticam Camera" or "OBS Virtual Camera") without needing OBS Studio running.
    /// </summary>
    public sealed class ObsVirtualCamQueue : IDisposable
    {
        private const string SharedMemoryName = "OBSVirtualCamVideo";
        private const int FrameHeaderSize = 32;

        private readonly int _width;
        private readonly int _height;
        private readonly int _fps;

        private MemoryMappedFile?     _mmf;
        private MemoryMappedViewAccessor? _accessor;
        private int                   _totalSize;
        private int[]                 _offsets = new int[3];

        private int  _writeIdx;
        private bool _initialized;

        // High performance async queuing & allocation-free buffer
        private BlockingCollection<Bitmap>? _queue;
        private Thread?                     _workerThread;
        private byte[]?                     _nv12Buffer;
        private readonly object             _stateLock = new();

        public event Action<string>? OnLog;

        public ObsVirtualCamQueue(int width, int height, int fps)
        {
            _width  = width;
            _height = height;
            _fps    = fps;
        }

        public bool Start()
        {
            if (_initialized) return true;
            if (_width <= 0 || _height <= 0 || _fps <= 0 || (_width & 1) != 0 || (_height & 1) != 0)
            {
                OnLog?.Invoke("OBS VirtualCam requires positive, even frame dimensions and FPS.");
                return false;
            }

            int cx = _width;
            int cy = _height;
            int frameSize = cx * cy * 3 / 2; // NV12: 12 bits per pixel

            // Calc aligned size helper
            int align(int val, int alignment) => (val + alignment - 1) & ~(alignment - 1);

            int headerSize = align(80, 32); // queue_header: 80 bytes padded to 96
            int slotSize   = align(frameSize + FrameHeaderSize, 32);

            _offsets[0] = headerSize;
            _offsets[1] = _offsets[0] + slotSize;
            _offsets[2] = _offsets[1] + slotSize;
            _totalSize  = _offsets[2] + slotSize;

            try
            {
                // Create or open the shared memory mapping
                _mmf = MemoryMappedFile.CreateOrOpen(SharedMemoryName, _totalSize, MemoryMappedFileAccess.ReadWrite);
                _accessor = _mmf.CreateViewAccessor(0, _totalSize, MemoryMappedFileAccess.ReadWrite);

                // Write initial queue_header parameters
                // struct queue_header offsets:
                //   write_idx: 0 (uint32)
                //   read_idx: 4 (uint32)
                //   state: 8 (uint32) -> SHARED_QUEUE_STATE_STARTING = 1
                //   offsets[0]: 12, offsets[1]: 16, offsets[2]: 20 (uint32 * 3)
                //   type: 24 (uint32) -> SHARED_QUEUE_TYPE_VIDEO = 0
                //   cx: 28 (uint32)
                //   cy: 32 (uint32)
                //   interval: 40 (uint64) -> frame interval in 100ns units (e.g. 10000000 / FPS)
                
                _accessor.Write(0, (uint)0); // write_idx
                _accessor.Write(4, (uint)0); // read_idx
                _accessor.Write(8, (uint)1); // state: STARTING

                _accessor.Write(12, (uint)_offsets[0]);
                _accessor.Write(16, (uint)_offsets[1]);
                _accessor.Write(20, (uint)_offsets[2]);

                _accessor.Write(24, (uint)0); // type: VIDEO
                _accessor.Write(28, (uint)cx);
                _accessor.Write(32, (uint)cy);

                ulong interval = 10000000UL / (ulong)_fps;
                _accessor.Write(40, interval); // interval at offset 40 (aligned)

                _writeIdx    = 0;

                // Start background worker thread to process frames asynchronously
                _queue = new BlockingCollection<Bitmap>(boundedCapacity: 3);
                _workerThread = new Thread(WorkerLoop)
                {
                    Name = "SticamVcamWorker",
                    IsBackground = true,
                    Priority = ThreadPriority.Normal
                };
                _initialized = true;
                _workerThread.Start();
                return true;
            }
            catch (Exception ex)
            {
                OnLog?.Invoke($"OBS VirtualCam shared-memory startup failed: {ex.Message}");
                Stop();
                return false;
            }
        }

        public void WriteFrame(Bitmap bmp)
        {
            lock (_stateLock)
            {
                if (!_initialized || _queue == null || _queue.IsAddingCompleted) return;

            try
            {
                // Clone the bitmap quickly on the caller thread
                var clone = (Bitmap)bmp.Clone();
                if (!_queue.TryAdd(clone))
                {
                    // Queue full — drop frame to prevent decoder blocking
                    clone.Dispose();
                }
            }
                catch (Exception ex)
                {
                    OnLog?.Invoke($"OBS VirtualCam frame queue failed: {ex.Message}");
                }
            }
        }

        private void WorkerLoop()
        {
            var queue = _queue;
            if (queue == null) return;
            foreach (var bmp in queue.GetConsumingEnumerable())
            {
                try
                {
                    ProcessFrame(bmp);
                }
                catch (Exception ex)
                {
                    OnLog?.Invoke($"OBS VirtualCam frame conversion failed: {ex.Message}");
                }
                finally
                {
                    bmp.Dispose();
                }
            }
        }

        private void ProcessFrame(Bitmap bmp)
        {
            if (!_initialized || _accessor == null) return;

            // Lock the source BGR24 bitmap
            var rect = new Rectangle(0, 0, bmp.Width, bmp.Height);
            var bmpData = bmp.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format24bppRgb);

            try
            {
                // Reuse NV12 buffer to prevent GC allocations
                int nv12Size = _width * _height * 3 / 2;
                if (_nv12Buffer == null || _nv12Buffer.Length != nv12Size)
                {
                    _nv12Buffer = new byte[nv12Size];
                }

                unsafe
                {
                    byte* pSrc = (byte*)bmpData.Scan0;
                    ConvertBgr24ToNv12(pSrc, bmpData.Stride, _nv12Buffer, _width, _height);
                }

                // Increment write index
                _writeIdx++;
                int slotIdx = _writeIdx % 3;
                int slotOffset = _offsets[slotIdx];

                // Write timestamp (8 bytes) at start of slot
                ulong timestamp = (ulong)DateTime.UtcNow.Ticks; // in 100ns ticks
                _accessor.Write(slotOffset, timestamp);

                // Write the raw NV12 pixels after the 32-byte slot header
                _accessor.WriteArray(slotOffset + FrameHeaderSize, _nv12Buffer, 0, _nv12Buffer.Length);

                // Update queue_header pointers to signal the reader
                _accessor.Write(0, (uint)_writeIdx); // write_idx
                _accessor.Write(4, (uint)_writeIdx); // read_idx
                _accessor.Write(8, (uint)2);         // state: READY
            }
            finally
            {
                bmp.UnlockBits(bmpData);
            }
        }

        public void Stop()
        {
            BlockingCollection<Bitmap>? queue;
            Thread? worker;
            lock (_stateLock)
            {
                if (!_initialized && _queue == null) return;
                _initialized = false;
                queue = _queue;
                worker = _workerThread;
                queue?.CompleteAdding();
                while (queue?.TryTake(out var pending) == true) pending.Dispose();
                _queue = null;
                _workerThread = null;
            }

            // The worker must stop before its memory map is disposed.
            worker?.Join();
            queue?.Dispose();
            if (_accessor != null)
            {
                try { _accessor.Write(8, (uint)3); } catch { }
            }
            _accessor?.Dispose(); _accessor = null;
            _mmf?.Dispose();      _mmf = null;
        }

        public void Dispose() => Stop();

        // ── High performance pixel conversion ─────────────────────────────────

        private static unsafe void ConvertBgr24ToNv12(byte* src, int stride, byte[] dst, int w, int h)
        {
            int yPlaneSize = w * h;
            fixed (byte* pDst = dst)
            {
                byte* yPlane  = pDst;
                byte* uvPlane = pDst + yPlaneSize;

                System.Threading.Tasks.Parallel.For(0, h, y =>
                {
                    byte* srcRow = src + y * stride;
                    byte* yRow   = yPlane + y * w;

                    if ((y & 1) == 0)
                    {
                        byte* uvRow = uvPlane + (y >> 1) * w;
                        for (int x = 0; x < w; x++)
                        {
                            byte* pixel = srcRow + x * 3;
                            byte B = pixel[0];
                            byte G = pixel[1];
                            byte R = pixel[2];

                            int Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                            yRow[x] = (byte)Math.Clamp(Y, 0, 255);

                            if ((x & 1) == 0)
                            {
                                int U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                                int V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;

                                uvRow[x]     = (byte)Math.Clamp(U, 0, 255);
                                uvRow[x + 1] = (byte)Math.Clamp(V, 0, 255);
                            }
                        }
                    }
                    else
                    {
                        for (int x = 0; x < w; x++)
                        {
                            byte* pixel = srcRow + x * 3;
                            byte B = pixel[0];
                            byte G = pixel[1];
                            byte R = pixel[2];

                            int Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                            yRow[x] = (byte)Math.Clamp(Y, 0, 255);
                        }
                    }
                });
            }
        }
    }
}
