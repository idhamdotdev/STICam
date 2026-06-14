using System;
using System.Drawing;
using System.Drawing.Text;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using System.Text.Json;
using SticamHost.Adb;
using SticamHost.Stream;
using SticamHost.VirtualCamera;

namespace SticamHost
{
    /// <summary>
    /// Two-state UI:
    ///   IDLE    — Dark navy themed panels matching the STICam design
    ///   LIVE    — Full-window video feed with floating overlays
    /// </summary>
    public sealed class MainForm : Form
    {
        // ── Theme colors ─────────────────────────────────────────────────────
        private static readonly Color NavyDark    = Color.FromArgb(13, 27, 42);
        private static readonly Color NavyMid     = Color.FromArgb(26, 42, 64);
        private static readonly Color NavyLight   = Color.FromArgb(35, 55, 80);
        private static readonly Color TealAccent  = Color.FromArgb(30, 204, 145);
        private static readonly Color TextWhite   = Color.FromArgb(240, 240, 240);
        private static readonly Color TextDim     = Color.FromArgb(180, 195, 210);

        // ── Font management ──────────────────────────────────────────────────
        private readonly PrivateFontCollection _pfc = new();
        private FontFamily _lalezar = null!;

        // ── IDLE-state controls ──────────────────────────────────────────────
        private readonly Panel        _idleContainer;
        private readonly Label        _lblHeader;
        private readonly Label        _lblInstructions;
        private readonly RadioButton  _rbUsb;
        private readonly RadioButton  _rbWifi;
        private readonly TextBox      _tbIp;
        private readonly Label        _lblIpPrefix;
        private readonly Panel        _ipPanel;
        private readonly Button       _btnConnect;

        // ── LIVE-state controls ──────────────────────────────────────────────
        private readonly Panel        _liveContainer;
        private readonly PictureBox   _videoPb;
        private readonly Label        _lblDeviceName;
        private readonly Button       _btnMenu;
        private readonly Label        _lblWatermark;
        private readonly Label        _lblLiveStats;
        private readonly Panel        _menuPopup;
        private readonly Button       _menuDisconnect;
        private readonly Button       _menuVirtualCam;
        private readonly Button       _menuMirror;
        private readonly Label        _menuVcamStatus;

        // hidden idle controls kept for logic compatibility
        private readonly CheckBox     _chkVirtualCam;
        private readonly CheckBox     _chkAutoConnect;

        // Tray notify icon
        private NotifyIcon _notifyIcon = null!;
        private ToolStripMenuItem _trayMenuToggleConnect = null!;
        private ToolStripMenuItem _trayMenuToggleVcam = null!;
        private Icon? _currentTrayIcon;

        [DllImport("user32.dll", CharSet = CharSet.Auto)]
        private static extern bool DestroyIcon(IntPtr handle);

        // ── Backend ──────────────────────────────────────────────────────────
        private AdbForwarder?          _adb;
        private H264Receiver?          _receiver;
        private VideoDecoder?          _decoder;
        private VirtualCameraManager?  _vcam;
        private readonly System.Windows.Forms.Timer _statsTimer;
        private bool _vcamActive;
        private bool _mirrorVideo;
        private bool _cinemaMode;
        private FormBorderStyle _prevBorderStyle;
        private FormWindowState _prevWindowState;
        private Rectangle _prevBounds;

        public MainForm()
        {
            this.SuspendLayout();
            this.AutoScaleDimensions = new System.Drawing.SizeF(96F, 96F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Dpi;

            // Load Lalezar font
            LoadLalezarFont();

            Text          = "STICam";
            Size          = new Size(720, 580);
            MinimumSize   = new Size(540, 480);
            BackColor     = NavyDark;
            Font          = MakeFont(10f);
            StartPosition = FormStartPosition.CenterScreen;
            try
            {
                Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath) ?? SystemIcons.Application;
            }
            catch
            {
                Icon = SystemIcons.Application;
            }
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox   = false;

            // hidden checkboxes for logic
            _chkVirtualCam  = new CheckBox { Visible = false, Checked = true };
            _chkAutoConnect = new CheckBox { Visible = false, Checked = true };
            _chkVirtualCam.CheckedChanged += OnVirtualCamToggle;

            // ═══════════════════════════════════════════════════════════
            //  IDLE CONTAINER
            // ═══════════════════════════════════════════════════════════
            _idleContainer = new Panel { Dock = DockStyle.Fill, BackColor = NavyDark };

            // ── Header band ──────────────────────────────────────────
            var headerPanel = new Panel
            {
                Dock      = DockStyle.Top,
                Height    = 90,
                BackColor = Color.Transparent,
                Padding   = new Padding(24, 16, 24, 16),
            };
            _lblHeader = new Label
            {
                Text      = "STICam turns your device into a Wireless\nWebcam. Connect via USB (ADB) or Wi-Fi.",
                Dock      = DockStyle.Fill,
                ForeColor = TextWhite,
                Font      = MakeFont(13f, FontStyle.Bold),
                TextAlign = ContentAlignment.MiddleCenter,
                BackColor = Color.Transparent,
            };
            headerPanel.Controls.Add(_lblHeader);

            // ── Instructions ─────────────────────────────────────────
            _lblInstructions = new Label
            {
                Text =
                    "Please make sure that :\n" +
                    "*STICam app is running on your Android phone\n" +
                    "*For USB Mode : USB Debugging is enabled\n" +
                    "*For Wi-Fi mode: both devices on the same network",
                ForeColor = TextWhite,
                Font      = MakeFont(10f, FontStyle.Bold),
                AutoSize  = false,
                Location  = new Point(30, 110),
                Size      = new Size(620, 100),
                BackColor = Color.Transparent,
            };

            // ── Radio: USB ───────────────────────────────────────────
            _rbUsb = MakeRadio("USB (ADB)", true);
            _rbUsb.Location = new Point(30, 230);
            _rbUsb.Size     = new Size(300, 50);
            _rbUsb.Font     = MakeFont(22f, FontStyle.Bold);

            // ── Radio: Wi-Fi ─────────────────────────────────────────
            _rbWifi = MakeRadio("Wi - Fi", false);
            _rbWifi.Location = new Point(30, 300);
            _rbWifi.Size     = new Size(300, 50);
            _rbWifi.Font     = MakeFont(20f, FontStyle.Bold);

            // ── IP input panel ───────────────────────────────────────
            _ipPanel = new Panel
            {
                Location  = new Point(70, 360),
                Size      = new Size(400, 40),
                BackColor = Color.Black,
                Visible   = false,
            };
            _ipPanel.Paint += (s, e) =>
            {
                float scale = (float)this.DeviceDpi / 96f;
                using var pen = new Pen(Color.FromArgb(200, 200, 200), 4 * scale);
                e.Graphics.DrawRectangle(pen, 0, 0, _ipPanel.Width - 1, _ipPanel.Height - 1);
            };
            _lblIpPrefix = new Label
            {
                Text      = "IP :",
                ForeColor = TextWhite,
                Font      = MakeFont(11f, FontStyle.Bold),
                Location  = new Point(10, 8),
                AutoSize  = true,
                BackColor = Color.Transparent,
            };
            _tbIp = new TextBox
            {
                Text      = GetLocalIpAddress(),
                Location  = new Point(50, 8),
                Size      = new Size(340, 28),
                Font      = MakeFont(11f),
                ForeColor = TextWhite,
                BackColor = Color.Black,
                BorderStyle = BorderStyle.None,
                ReadOnly  = true,
            };
            _ipPanel.Controls.Add(_lblIpPrefix);
            _ipPanel.Controls.Add(_tbIp);

            _rbWifi.CheckedChanged += (_, _) => _ipPanel.Visible = _rbWifi.Checked;

            // ── CONNECT button ───────────────────────────────────────
            _btnConnect = new Button
            {
                Text      = "CONNECT",
                Size      = new Size(190, 60),
                Location  = new Point(490, 435),
                FlatStyle = FlatStyle.Flat,
                BackColor = TealAccent,
                ForeColor = TextWhite,
                Font      = MakeFont(18f, FontStyle.Bold),
                Cursor    = Cursors.Hand,
            };
            _btnConnect.FlatAppearance.BorderSize = 4;
            _btnConnect.FlatAppearance.BorderColor = Color.FromArgb(200, 200, 200);
            _btnConnect.FlatAppearance.MouseOverBackColor = Color.FromArgb(40, 224, 165);
            _btnConnect.Click += OnConnect;

            // Wire idle controls
            _idleContainer.Controls.Add(_btnConnect);
            _idleContainer.Controls.Add(_ipPanel);
            _idleContainer.Controls.Add(_rbWifi);
            _idleContainer.Controls.Add(_rbUsb);
            _idleContainer.Controls.Add(_lblInstructions);
            _idleContainer.Controls.Add(headerPanel);
            _idleContainer.Controls.Add(_chkVirtualCam);
            _idleContainer.Controls.Add(_chkAutoConnect);

            // ═══════════════════════════════════════════════════════════
            //  LIVE CONTAINER
            // ═══════════════════════════════════════════════════════════
            _liveContainer = new Panel
            {
                Dock      = DockStyle.Fill,
                BackColor = Color.Black,
                Visible   = false,
            };
            _videoPb = new PictureBox
            {
                Dock      = DockStyle.Fill,
                SizeMode  = PictureBoxSizeMode.Zoom,
                BackColor = Color.Black,
            };
            _lblDeviceName = new Label
            {
                Text      = "Android Phone [USB]",
                AutoSize  = true,
                BackColor = Color.FromArgb(200, 255, 255, 255),
                ForeColor = Color.FromArgb(30, 30, 30),
                Font      = MakeFont(9f),
                Padding   = new Padding(10, 4, 10, 4),
            };
            _btnMenu = new Button
            {
                Text      = "≡",
                Width     = 40, Height = 36,
                FlatStyle = FlatStyle.Flat,
                BackColor = Color.FromArgb(160, 0, 0, 0),
                ForeColor = Color.White,
                Font      = new Font("Segoe UI", 13f, FontStyle.Bold),
                Cursor    = Cursors.Hand,
                Location  = new Point(8, 8),
            };
            _btnMenu.FlatAppearance.BorderSize = 0;
            _btnMenu.Click += ToggleMenuPopup;

            _lblWatermark = new Label
            {
                Text      = "🎥 STICam",
                AutoSize  = true,
                BackColor = Color.Transparent,
                ForeColor = Color.FromArgb(180, 255, 255, 255),
                Font      = MakeFont(9f, FontStyle.Bold),
                Padding   = new Padding(8, 4, 8, 8),
            };
            _lblLiveStats = new Label
            {
                Text      = "",
                AutoSize  = true,
                BackColor = Color.Transparent,
                ForeColor = Color.FromArgb(180, 255, 255, 255),
                Font      = MakeFont(8f),
                Padding   = new Padding(8, 4, 8, 8),
            };

            _menuPopup = new Panel
            {
                BackColor = Color.FromArgb(230, 30, 30, 30),
                Width = 240, Height = 145, Visible = false,
            };
            _menuDisconnect = MakeFlyoutButton("■  Disconnect", Color.FromArgb(220, 60, 60));
            _menuVirtualCam = MakeFlyoutButton("▶  Start Virtual Webcam", Color.FromArgb(50, 160, 80));
            _menuMirror     = MakeFlyoutButton("🪞  Mirror Video", Color.FromArgb(50, 150, 200));
            _menuVcamStatus = new Label
            {
                Text = "", ForeColor = Color.FromArgb(160, 255, 255, 255),
                Font = MakeFont(7.5f), AutoSize = true,
                Padding = new Padding(8, 0, 0, 4), BackColor = Color.Transparent,
            };

            _menuDisconnect.Click += (_, _) => { HideMenuPopup(); OnDisconnect(null, EventArgs.Empty); };
            _menuVirtualCam.Click += OnMenuVirtualCam;
            _menuMirror.Click     += OnMenuMirrorToggle;

            _menuPopup.Controls.Add(_menuVcamStatus);
            _menuPopup.Controls.Add(_menuMirror);
            _menuPopup.Controls.Add(_menuVirtualCam);
            _menuPopup.Controls.Add(_menuDisconnect);

            _liveContainer.Controls.Add(_videoPb);
            _liveContainer.Controls.Add(_lblWatermark);
            _liveContainer.Controls.Add(_lblLiveStats);
            _liveContainer.Controls.Add(_lblDeviceName);
            _liveContainer.Controls.Add(_btnMenu);
            _liveContainer.Controls.Add(_menuPopup);

            _liveContainer.Resize += PositionOverlays;
            _videoPb.Click       += (_, _) => HideMenuPopup();
            _videoPb.DoubleClick += (_, _) => { if (_liveContainer.Visible) ToggleCinemaMode(); };
            _lblDeviceName.Click += (_, _) => HideMenuPopup();

            Controls.Add(_liveContainer);
            Controls.Add(_idleContainer);

            // Stats timer
            _statsTimer = new System.Windows.Forms.Timer { Interval = 1000 };
            long lastF = 0, lastB = 0;
            _statsTimer.Tick += (_, _) =>
            {
                var r = _receiver;
                if (r == null) { _lblLiveStats.Text = ""; return; }
                long dF = r.FramesReceived - lastF;
                long dB = r.BytesReceived  - lastB;
                lastF = r.FramesReceived;
                lastB = r.BytesReceived;
                _lblLiveStats.Text = $"{dF} fps  •  {dB / 1024.0 / 1024.0:F1} MB/s";
                PositionOverlays(null, EventArgs.Empty);
            };
            InitTrayIcon();
            _statsTimer.Start();

            this.ResumeLayout(false);
            this.PerformLayout();
        }

        // ── Font helpers ─────────────────────────────────────────────────────

        private void LoadLalezarFont()
        {
            string fontPath = Path.Combine(Path.GetTempPath(), "STICamHost", "fonts", "Lalezar-Regular.ttf");
            if (!File.Exists(fontPath))
                fontPath = Path.Combine(AppContext.BaseDirectory, "fonts", "Lalezar-Regular.ttf");
            if (!File.Exists(fontPath))
                fontPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "fonts", "Lalezar-Regular.ttf");
            if (File.Exists(fontPath))
            {
                _pfc.AddFontFile(fontPath);
                _lalezar = _pfc.Families[0];
            }
        }

        private Font MakeFont(float size, FontStyle style = FontStyle.Regular)
        {
            if (_lalezar != null && _lalezar.IsStyleAvailable(style))
                return new Font(_lalezar, size, style);
            if (_lalezar != null && _lalezar.IsStyleAvailable(FontStyle.Regular))
                return new Font(_lalezar, size, FontStyle.Regular);
            return new Font("Segoe UI", size, style);
        }

        // ── Custom radio button ──────────────────────────────────────────────

        private RadioButton MakeRadio(string text, bool isChecked)
        {
            var rb = new RadioButton
            {
                Text      = text,
                Checked   = isChecked,
                ForeColor = TextWhite,
                BackColor = Color.Transparent,
                AutoSize  = false,
                Cursor    = Cursors.Hand,
            };
            rb.Paint += (s, e) =>
            {
                var r = (RadioButton)s!;
                e.Graphics.Clear(NavyDark);
                e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                e.Graphics.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;

                float scale = (float)this.DeviceDpi / 96f;
                int circleSize = (int)(28 * scale);
                int cy = (r.Height - circleSize) / 2;

                // outer circle
                using var penOuter = new Pen(TextWhite, 3f * scale);
                e.Graphics.DrawEllipse(penOuter, (int)(4 * scale), cy, circleSize, circleSize);

                // inner fill if checked
                if (r.Checked)
                {
                    using var brush = new SolidBrush(TextWhite);
                    e.Graphics.FillEllipse(brush, (int)(10 * scale), cy + (int)(6 * scale), circleSize - (int)(12 * scale), circleSize - (int)(12 * scale));
                }

                // text
                using var textBrush = new SolidBrush(TextWhite);
                e.Graphics.DrawString(r.Text, r.Font, textBrush, circleSize + (int)(16 * scale), (r.Height - r.Font.Height) / 2f);
            };
            return rb;
        }

        // ── Overlay positioning ──────────────────────────────────────────────

        private void PositionOverlays(object? s, EventArgs e)
        {
            if (!_liveContainer.Visible) return;
            float scale = (float)this.DeviceDpi / 96f;
            int w = _liveContainer.Width, h = _liveContainer.Height;
            _lblDeviceName.Location = new Point((w - _lblDeviceName.Width) / 2, (int)(12 * scale));
            _lblWatermark.Location  = new Point((int)(8 * scale), h - _lblWatermark.Height - (int)(8 * scale));
            _lblLiveStats.Location  = new Point(w - _lblLiveStats.Width - (int)(8 * scale), h - _lblLiveStats.Height - (int)(8 * scale));
            _menuPopup.Location     = new Point((int)(8 * scale), _btnMenu.Bottom + (int)(4 * scale));
        }

        private void ToggleMenuPopup(object? s, EventArgs e)
        {
            _menuPopup.Visible = !_menuPopup.Visible;
            _menuPopup.BringToFront();
        }

        private void HideMenuPopup() => _menuPopup.Visible = false;

        // ── Show / hide live mode ────────────────────────────────────────────

        private void ShowLiveMode(string deviceLabel)
        {
            _lblDeviceName.Text = deviceLabel;
            _idleContainer.Visible = false;
            _liveContainer.Visible = true;
            _lblDeviceName.BringToFront();
            _btnMenu.BringToFront();
            _lblWatermark.BringToFront();
            _lblLiveStats.BringToFront();
            _menuPopup.BringToFront();
            PositionOverlays(null, EventArgs.Empty);

            float scale = (float)this.DeviceDpi / 96f;
            int minLiveWidth = (int)(860 * scale);
            int minLiveHeight = (int)(520 * scale);
            if (Width < minLiveWidth) Width = minLiveWidth;
            if (Height < minLiveHeight) Height = minLiveHeight;

            FormBorderStyle = FormBorderStyle.Sizable;
            MaximizeBox = true;
            UpdateTrayMenuItems();
        }

        private void ShowIdleMode()
        {
            HideMenuPopup();
            _liveContainer.Visible = false;
            _idleContainer.Visible = true;
            _videoPb.Image?.Dispose();
            _videoPb.Image = null;
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;

            float scale = (float)this.DeviceDpi / 96f;
            Size = new Size((int)(720 * scale), (int)(580 * scale));
            UpdateTrayMenuItems();
        }

        // ── Connect / Disconnect ─────────────────────────────────────────────

        private void OnConnect(object? s, EventArgs e)
        {
            if (_receiver != null)
            {
                OnDisconnect(null, EventArgs.Empty);
                return;
            }

            string host      = _rbUsb.Checked ? "127.0.0.1" : _tbIp.Text.Trim();
            bool   isUsb     = _rbUsb.Checked;
            string modeLabel = isUsb ? "[USB]" : "[Wi-Fi]";

            if (isUsb)
            {
                _adb = new AdbForwarder();
                _adb.OnLog += _ => { };
                _adb.Start();
            }

            _decoder = new VideoDecoder();
            _decoder.MirrorX = _mirrorVideo;
            _decoder.OnLog += _ => { };
            _decoder.OnFrameDecoded += bmp => Invoke(() =>
            {
                var old = _videoPb.Image;
                _videoPb.Image = (Bitmap)bmp.Clone();
                old?.Dispose();
                if (!_liveContainer.Visible)
                {
                    ShowLiveMode($"Android Phone {modeLabel}");
                    if (_chkVirtualCam.Checked)
                    {
                        StartVirtualCam();
                        UpdateMenuVcamButton();
                    }
                }
            });

            _receiver = new H264Receiver(host, 8765);
            _receiver.OnLog += _ => { };
            _receiver.OnCommandReceived += cmdJson => Invoke(() =>
            {
                try
                {
                    using var doc = JsonDocument.Parse(cmdJson);
                    var root = doc.RootElement;
                    if (root.GetProperty("cmd").GetString() == "flip")
                    {
                        _mirrorVideo = root.GetProperty("mirrorX").GetBoolean();
                        if (_decoder != null)
                        {
                            _decoder.MirrorX = _mirrorVideo;
                            _decoder.MirrorY = root.GetProperty("mirrorY").GetBoolean();
                        }
                        UpdateMenuMirrorButton();
                    }
                }
                catch { }
            });
            _receiver.OnConnectionChanged += ok => Invoke(() =>
            {
                if (!ok)
                {
                    ShowIdleMode();
                    _btnConnect.Text = "CONNECT";
                    _btnConnect.BackColor = TealAccent;
                    _receiver?.Disconnect(); _receiver = null;
                    _decoder?.Stop();        _decoder  = null;
                    _adb?.Stop();            _adb      = null;
                }
                else if (ok && !_liveContainer.Visible)
                {
                    _btnConnect.Text = "CONNECTED";
                    _btnConnect.BackColor = Color.FromArgb(50, 180, 130);
                }
                UpdateTrayMenuItems();
            });
            _receiver.OnConfigReceived += (sps, pps) => _decoder.Start(sps, pps);
            _receiver.OnFrameReceived  += (_, args)  => _decoder.PushFrame(args.Data);

            _receiver.Connect();
            _btnConnect.Text = "WAITING...";
            _btnConnect.BackColor = Color.FromArgb(50, 130, 180);
            UpdateTrayMenuItems();

            // Auto-timeout if no connection within 30 seconds
            var currentReceiver = _receiver;
            Task.Run(async () =>
            {
                await Task.Delay(30000);
                if (IsDisposed) return;
                Invoke(() =>
                {
                    if (_receiver == currentReceiver && !_liveContainer.Visible)
                    {
                        OnDisconnect(null, EventArgs.Empty);
                        MessageBox.Show(this, 
                            "Connection timed out.\n\nPlease check that:\n" +
                            "1. STICam is running and streaming on your phone.\n" +
                            "2. Both devices are on the same Wi-Fi network.\n" +
                            "3. Windows Defender Firewall is not blocking the connection.", 
                            "Connection Timeout", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    }
                });
            });
        }

        private void OnDisconnect(object? s, EventArgs e)
        {
            StopVirtualCam();
            _receiver?.Disconnect(); _receiver = null;
            _decoder?.Stop();        _decoder  = null;
            _adb?.Stop();            _adb      = null;

            _btnConnect.Enabled    = true;
            _lblLiveStats.Text     = "";
            _btnConnect.Text       = "CONNECT";
            _btnConnect.BackColor  = TealAccent;

            ShowIdleMode();
            UpdateTrayMenuItems();
        }

        // ── Virtual Webcam ───────────────────────────────────────────────────

        private void OnVirtualCamToggle(object? s, EventArgs e)
        {
            if (_chkVirtualCam.Checked) StartVirtualCam();
            else                        StopVirtualCam();
        }

        private void OnMenuVirtualCam(object? s, EventArgs e)
        {
            if (!_vcamActive) { StartVirtualCam(); _chkVirtualCam.Checked = true; }
            else              { StopVirtualCam();  _chkVirtualCam.Checked = false; }
            UpdateMenuVcamButton();
        }

        private void StartVirtualCam()
        {
            if (_vcamActive || _decoder == null) return;
            int w = _videoPb.Image?.Width  ?? 1280;
            int h = _videoPb.Image?.Height ?? 720;
            _vcam = new VirtualCameraManager(w, h, fps: 30);
            _vcam.OnLog += _ => { };
            _decoder.OnFrameDecoded += _vcam.PushFrame;
            string result = _vcam.Start();
            _vcamActive = true;
            _menuVcamStatus.Text = result;
        }

        private void StopVirtualCam()
        {
            if (!_vcamActive) return;
            if (_decoder != null && _vcam != null)
                _decoder.OnFrameDecoded -= _vcam.PushFrame;
            _vcam?.Stop(); _vcam = null;
            _vcamActive = false;
            _menuVcamStatus.Text = "";
        }

        private void UpdateMenuVcamButton()
        {
            _menuVirtualCam.Text = _vcamActive ? "■  Stop Virtual Webcam" : "▶  Start Virtual Webcam";
            _menuVirtualCam.ForeColor = _vcamActive ? Color.FromArgb(220, 80, 80) : Color.FromArgb(50, 200, 80);
            UpdateTrayMenuItems();
        }

        private void OnMenuMirrorToggle(object? s, EventArgs e)
        {
            _mirrorVideo = !_mirrorVideo;
            if (_decoder != null) _decoder.MirrorX = _mirrorVideo;
            UpdateMenuMirrorButton();
        }

        private void UpdateMenuMirrorButton()
        {
            _menuMirror.Text = _mirrorVideo ? "🪞  Unmirror Video" : "🪞  Mirror Video";
            _menuMirror.ForeColor = _mirrorVideo ? Color.FromArgb(220, 160, 40) : Color.FromArgb(50, 150, 200);
        }

        // ── Helpers ──────────────────────────────────────────────────────────

        private Button MakeFlyoutButton(string text, Color fg)
        {
            var b = new Button
            {
                Text = text, ForeColor = fg, BackColor = Color.Transparent,
                FlatStyle = FlatStyle.Flat, Height = 34, Dock = DockStyle.Top,
                Font = MakeFont(9f, FontStyle.Bold),
                TextAlign = ContentAlignment.MiddleLeft,
                Cursor = Cursors.Hand, Padding = new Padding(8, 0, 0, 0),
            };
            b.FlatAppearance.BorderSize = 0;
            b.FlatAppearance.MouseOverBackColor = Color.FromArgb(60, 255, 255, 255);
            return b;
        }

        private void ToggleCinemaMode()
        {
            _cinemaMode = !_cinemaMode;
            if (_cinemaMode)
            {
                _prevBorderStyle = FormBorderStyle;
                _prevWindowState = WindowState;
                _prevBounds      = Bounds;
                FormBorderStyle = FormBorderStyle.None;
                WindowState     = FormWindowState.Maximized;
                _btnMenu.Visible = false;
                _lblDeviceName.Visible = false;
                _lblWatermark.Visible  = false;
                _lblLiveStats.Visible  = false;
                HideMenuPopup();
            }
            else
            {
                FormBorderStyle = _prevBorderStyle;
                WindowState     = _prevWindowState;
                if (WindowState == FormWindowState.Normal) Bounds = _prevBounds;
                _btnMenu.Visible = true;
                _lblDeviceName.Visible = true;
                _lblWatermark.Visible  = true;
                _lblLiveStats.Visible  = true;
            }
        }

        protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
        {
            if (keyData == Keys.F11 && _liveContainer.Visible) { ToggleCinemaMode(); return true; }
            if (keyData == Keys.Escape && _cinemaMode) { ToggleCinemaMode(); return true; }
            return base.ProcessCmdKey(ref msg, keyData);
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            _statsTimer.Stop();
            OnDisconnect(null, EventArgs.Empty);
            _notifyIcon.Dispose();
            base.OnFormClosing(e);
        }

        private static string GetLocalIpAddress()
        {
            try
            {
                foreach (var ip in System.Net.Dns.GetHostAddresses(System.Net.Dns.GetHostName()))
                {
                    if (ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
                    {
                        string s = ip.ToString();
                        if (!s.StartsWith("127.") && !s.StartsWith("169.254"))
                        {
                            return s;
                        }
                    }
                }
            }
            catch { }
            return "127.0.0.1";
        }

        // ── System Tray Features ──────────────────────────────────────────────

        private void InitTrayIcon()
        {
            var menu = new ContextMenuStrip();

            var itemRestore = new ToolStripMenuItem("Show STICam");
            itemRestore.Click += (s, e) => RestoreFromTray();
            menu.Items.Add(itemRestore);

            menu.Items.Add(new ToolStripSeparator());

            _trayMenuToggleConnect = new ToolStripMenuItem("Connect");
            _trayMenuToggleConnect.Click += (s, e) => OnConnect(null, EventArgs.Empty);
            menu.Items.Add(_trayMenuToggleConnect);

            _trayMenuToggleVcam = new ToolStripMenuItem("Start Virtual Webcam");
            _trayMenuToggleVcam.Click += OnMenuVirtualCam;
            menu.Items.Add(_trayMenuToggleVcam);

            menu.Items.Add(new ToolStripSeparator());

            var itemExit = new ToolStripMenuItem("Exit");
            itemExit.Click += (s, e) => Close();
            menu.Items.Add(itemExit);

            _notifyIcon = new NotifyIcon
            {
                Text = "STICam",
                Icon = this.Icon,
                ContextMenuStrip = menu,
                Visible = true
            };

            _notifyIcon.DoubleClick += (s, e) => RestoreFromTray();
            UpdateTrayMenuItems();
        }

        private void RestoreFromTray()
        {
            Show();
            WindowState = FormWindowState.Normal;
            ShowInTaskbar = true;
            Activate();
        }

        private void UpdateTrayIcon(bool online)
        {
            try
            {
                if (this.Icon == null) return;
                using (Bitmap bmp = this.Icon.ToBitmap())
                {
                    using (Graphics g = Graphics.FromImage(bmp))
                    {
                        g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                        
                        int dotSize = (int)(bmp.Width * 0.35f);
                        int x = bmp.Width - dotSize - 1;
                        int y = bmp.Height - dotSize - 1;
                        
                        Color statusColor = online ? Color.FromArgb(30, 204, 145) : Color.FromArgb(220, 60, 60);
                        
                        using (var pen = new Pen(Color.FromArgb(13, 27, 42), 1.5f))
                        {
                            g.DrawEllipse(pen, x, y, dotSize, dotSize);
                        }
                        using (var brush = new SolidBrush(statusColor))
                        {
                            g.FillEllipse(brush, x, y, dotSize, dotSize);
                        }
                    }
                    
                    IntPtr hIcon = bmp.GetHicon();
                    Icon newIcon = Icon.FromHandle(hIcon);
                    
                    _notifyIcon.Icon = newIcon;
                    
                    if (_currentTrayIcon != null)
                    {
                        IntPtr oldHandle = _currentTrayIcon.Handle;
                        _currentTrayIcon.Dispose();
                        DestroyIcon(oldHandle);
                    }
                    
                    _currentTrayIcon = newIcon;
                }
            }
            catch
            {
                _notifyIcon.Icon = this.Icon;
            }
        }

        private void UpdateTrayMenuItems()
        {
            bool online = _liveContainer.Visible;
            UpdateTrayIcon(online);

            if (_receiver == null)
            {
                _trayMenuToggleConnect.Text = "🔴 Connect";
                _trayMenuToggleVcam.Enabled = false;
            }
            else
            {
                _trayMenuToggleConnect.Text = online ? "🟢 Disconnect" : "🟡 Disconnect";
                _trayMenuToggleVcam.Enabled = true;
            }
            _trayMenuToggleVcam.Text = _vcamActive ? "Stop Virtual Webcam" : "Start Virtual Webcam";
            _notifyIcon.Text = online ? "STICam - Online 🟢" : "STICam - Offline 🔴";
        }

        protected override void OnResize(EventArgs e)
        {
            base.OnResize(e);
            if (WindowState == FormWindowState.Minimized)
            {
                Hide();
                ShowInTaskbar = false;
                _notifyIcon.ShowBalloonTip(3000, "STICam Minimized", "STICam is running in the system tray. Double-click the icon to restore.", ToolTipIcon.Info);
            }
        }
    }
}
