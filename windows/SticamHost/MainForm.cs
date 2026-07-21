using System;
using System.Drawing;
using System.Drawing.Text;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using System.Text.Json;
using SticamHost.Adb;
using SticamHost.Security;
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
        private readonly Panel        _pairingPanel;
        private readonly TextBox      _tbPairingKey;
        private string                _pairingKey;
        private readonly Button       _btnConnect;
        private readonly Label        _lblStatus;

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
        private readonly Button       _menuFaceTracking;
        private readonly Label        _menuVcamStatus;
        private readonly Panel        _panelControls;
        private readonly Label        _lblControlsTitle;
        private readonly SticamSlider _sliderZoom;
        private readonly SticamSlider _sliderExposure;
        private readonly SticamSlider _sliderIso;
        private readonly SticamSlider _sliderFocus;
        private readonly CheckBox     _chkAutoIso;
        private readonly CheckBox     _chkAutoFocus;
        private readonly CheckBox     _chkFlash;
        private readonly Label        _lblCameraTitle;
        private readonly ComboBox     _cbCamera;
        private readonly Label        _lblResolutionTitle;
        private readonly ComboBox     _cbResolution;

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
        private bool _faceTrackingActive;
        private bool _cinemaMode;
        private bool _isSyncing;
        private int _currentRotation = 0;
        private long _sessionId;
        private long _lastStatsFrames;
        private long _lastStatsBytes;
        private readonly object _previewLock = new();
        private Bitmap? _pendingPreviewFrame;
        private int _previewInvokePending;
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
            try
            {
                _pairingKey = PairingKeyStore.GetOrCreate();
            }
            catch (Exception ex)
            {
                var rotate = MessageBox.Show(
                    $"The saved pairing key could not be opened:\n\n{ex.Message}\n\n" +
                    "Rotate it now? Previously paired phones will need the new key.",
                    "Pairing Key Recovery",
                    MessageBoxButtons.YesNo,
                    MessageBoxIcon.Warning);
                if (rotate != DialogResult.Yes) throw;
                _pairingKey = PairingKeyStore.Rotate();
            }

            Text          = "STICam";
            Size          = new Size(720, 650);
            MinimumSize   = new Size(540, 560);
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
                    "*Copy the pairing key below into the Android app\n" +
                    "*For Wi-Fi mode: both devices are on the same network",
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

            _pairingPanel = new Panel
            {
                Location = new Point(70, 410),
                Size = new Size(610, 40),
                BackColor = Color.Black,
            };
            _pairingPanel.Paint += (_, e) =>
            {
                float scale = (float)this.DeviceDpi / 96f;
                using var pen = new Pen(Color.FromArgb(200, 200, 200), 2 * scale);
                e.Graphics.DrawRectangle(pen, 0, 0, _pairingPanel.Width - 1, _pairingPanel.Height - 1);
            };
            var pairingLabel = new Label
            {
                Text = "PAIR KEY:",
                ForeColor = TextWhite,
                Font = MakeFont(10f, FontStyle.Bold),
                Location = new Point(10, 9),
                AutoSize = true,
                BackColor = Color.Transparent,
            };
            _tbPairingKey = new TextBox
            {
                Text = _pairingKey,
                Location = new Point(100, 9),
                Size = new Size(270, 25),
                Font = new Font(FontFamily.GenericMonospace, 9f),
                ForeColor = TealAccent,
                BackColor = Color.Black,
                BorderStyle = BorderStyle.None,
                ReadOnly = true,
            };
            var copyPairingKey = new Button
            {
                Text = "COPY",
                Location = new Point(380, 5),
                Size = new Size(70, 30),
                FlatStyle = FlatStyle.Flat,
                ForeColor = TextWhite,
                BackColor = NavyMid,
            };
            var rotatePairingKey = new Button
            {
                Text = "ROTATE KEY",
                Location = new Point(455, 5),
                Size = new Size(145, 30),
                FlatStyle = FlatStyle.Flat,
                ForeColor = TextWhite,
                BackColor = NavyMid,
            };
            _pairingPanel.Controls.Add(pairingLabel);
            _pairingPanel.Controls.Add(_tbPairingKey);
            _pairingPanel.Controls.Add(copyPairingKey);
            _pairingPanel.Controls.Add(rotatePairingKey);

            _rbWifi.CheckedChanged += (_, _) => _ipPanel.Visible = _rbWifi.Checked;

            // ── CONNECT button ───────────────────────────────────────
            _btnConnect = new Button
            {
                Text      = "CONNECT",
                Size      = new Size(190, 60),
                Location  = new Point(490, 515),
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

            _lblStatus = new Label
            {
                Text = "Ready",
                ForeColor = TextDim,
                Font = MakeFont(9f),
                Location = new Point(30, 500),
                Size = new Size(440, 70),
                BackColor = Color.Transparent,
            };
            copyPairingKey.Click += (_, _) =>
            {
                try
                {
                    Clipboard.SetText(_pairingKey);
                    _lblStatus.Text = "Pairing key copied. Paste it into the Android app.";
                }
                catch (Exception ex)
                {
                    _lblStatus.Text = $"Could not copy pairing key: {ex.Message}";
                }
            };
            rotatePairingKey.Click += (_, _) =>
            {
                if (_receiver != null)
                {
                    _lblStatus.Text = "Disconnect before rotating the pairing key.";
                    return;
                }
                var confirm = MessageBox.Show(
                    this,
                    "Rotate the pairing key? The Android app must be updated with the new key.",
                    "Rotate Pairing Key",
                    MessageBoxButtons.YesNo,
                    MessageBoxIcon.Warning);
                if (confirm != DialogResult.Yes) return;
                try
                {
                    _pairingKey = PairingKeyStore.Rotate();
                    _tbPairingKey.Text = _pairingKey;
                    _lblStatus.Text = "Pairing key rotated. Copy the new key to Android.";
                }
                catch (Exception ex)
                {
                    _lblStatus.Text = $"Could not rotate pairing key: {ex.Message}";
                }
            };

            // Wire idle controls
            _idleContainer.Controls.Add(_lblStatus);
            _idleContainer.Controls.Add(_btnConnect);
            _idleContainer.Controls.Add(_pairingPanel);
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
                BackColor = NavyDark,
                Visible   = false,
            };
            _videoPb = new PictureBox
            {
                Dock      = DockStyle.None,
                SizeMode  = PictureBoxSizeMode.Zoom,
                BackColor = Color.Black,
            };
            _lblDeviceName = new Label
            {
                Text      = "Android Phone [USB]",
                AutoSize  = true,
                BackColor = NavyMid,
                ForeColor = TextWhite,
                Font      = MakeFont(11f, FontStyle.Bold),
                Padding   = new Padding(10, 4, 10, 4),
            };
            _btnMenu = new Button
            {
                Text      = "≡",
                Width     = 40, Height = 36,
                FlatStyle = FlatStyle.Flat,
                BackColor = NavyMid,
                ForeColor = TextWhite,
                Font      = MakeFont(14f, FontStyle.Bold),
                Cursor    = Cursors.Hand,
                Location  = new Point(8, 8),
            };
            _btnMenu.FlatAppearance.BorderSize = 0;
            _btnMenu.Click += ToggleMenuPopup;

            _lblWatermark = new Label
            {
                Text      = "STICam",
                AutoSize  = true,
                BackColor = Color.Transparent,
                ForeColor = Color.FromArgb(180, 255, 255, 255),
                Font      = MakeFont(12f, FontStyle.Bold),
                Padding   = new Padding(8, 4, 8, 8),
            };
            _lblLiveStats = new Label
            {
                Text      = "",
                AutoSize  = true,
                BackColor = Color.Transparent,
                ForeColor = Color.FromArgb(180, 255, 255, 255),
                Font      = MakeFont(10f),
                Padding   = new Padding(8, 4, 8, 8),
            };

            _menuPopup = new Panel
            {
                BackColor = NavyMid,
                Width = 260, Height = 180, Visible = false,
            };
            _menuDisconnect   = MakeFlyoutButton("■  Disconnect", Color.FromArgb(220, 60, 60));
            _menuVirtualCam   = MakeFlyoutButton("▶  Start Virtual Webcam", Color.FromArgb(50, 160, 80));
            _menuFaceTracking = MakeFlyoutButton("🤖  AI Face Tracking: Off", Color.FromArgb(180, 180, 180));
            _menuMirror       = MakeFlyoutButton("🪞  Mirror Video", Color.FromArgb(50, 150, 200));
            _menuVcamStatus   = new Label
            {
                Text = "", ForeColor = Color.FromArgb(160, 255, 255, 255),
                Font = MakeFont(8f), AutoSize = true,
                Padding = new Padding(12, 0, 0, 8), BackColor = Color.Transparent,
                Dock = DockStyle.Top
            };

            _menuDisconnect.Click   += (_, _) => { HideMenuPopup(); OnDisconnect(null, EventArgs.Empty); };
            _menuVirtualCam.Click   += OnMenuVirtualCam;
            _menuFaceTracking.Click += OnMenuFaceTrackingToggle;
            _menuMirror.Click       += OnMenuMirrorToggle;

            _menuPopup.Controls.Add(_menuMirror);
            _menuPopup.Controls.Add(_menuFaceTracking);
            _menuPopup.Controls.Add(_menuVcamStatus);
            _menuPopup.Controls.Add(_menuVirtualCam);
            _menuPopup.Controls.Add(_menuDisconnect);

            _menuPopup.Height = 200; // slightly taller to accommodate the bigger buttons

            // ── CAMERA CONTROL SIDE PANEL ─────────────────────────────────────
            _panelControls = new Panel
            {
                Dock      = DockStyle.Right,
                Width     = 220,
                BackColor = NavyDark,
                Visible   = false
            };
            _panelControls.Paint += (s, e) =>
            {
                // Draw a sleek left border to separate it from the video feed
                using (var pen = new Pen(TealAccent, 2))
                {
                    e.Graphics.DrawLine(pen, 0, 0, 0, _panelControls.Height);
                }
            };

            _lblControlsTitle = new Label
            {
                Text      = "CAMERA CONTROL",
                Font      = MakeFont(12f, FontStyle.Bold),
                ForeColor = TealAccent,
                Location  = new Point(12, 16),
                AutoSize  = true,
                BackColor = Color.Transparent
            };
            _panelControls.Controls.Add(_lblControlsTitle);

            int yOffset = 55;
            int spacing = 60;

            // Slider: Zoom
            _sliderZoom = new SticamSlider
            {
                Label      = "ZOOM LEVEL",
                Min        = 1.0f,
                Max        = 8.0f,
                Value      = 1.0f,
                Font       = MakeFont(10f),
                ValueText  = "1.0x",
                Location   = new Point(12, yOffset),
                Width      = 196,
                BackColor  = Color.Transparent
            };
            _sliderZoom.ValueChanged += (s, e) =>
            {
                if (_isSyncing) return;
                _sliderZoom.ValueText = $"{_sliderZoom.Value:F1}x";
                _receiver?.SendCameraControl(iso: null, brightness: null, focus: null, zoom: _sliderZoom.Value);
            };
            _panelControls.Controls.Add(_sliderZoom);
            yOffset += spacing;

            // Slider: Exposure
            _sliderExposure = new SticamSlider
            {
                Label      = "EXPOSURE COMP",
                Min        = -4.0f,
                Max        = 4.0f,
                Value      = 0.0f,
                ValueText  = "0",
                Font       = MakeFont(10f),
                Location   = new Point(12, yOffset),
                Width      = 196,
                BackColor  = Color.Transparent
            };
            _sliderExposure.ValueChanged += (s, e) =>
            {
                if (_isSyncing) return;
                int val = (int)Math.Round(_sliderExposure.Value);
                _sliderExposure.ValueText = val > 0 ? $"+{val}" : $"{val}";
                _receiver?.SendCameraControl(iso: null, brightness: (float)val, focus: null);
            };
            _panelControls.Controls.Add(_sliderExposure);
            yOffset += spacing;

            // Checkbox: Auto ISO
            _chkAutoIso = new CheckBox
            {
                Text = "AUTO ISO",
                Font = MakeFont(9.5f),
                ForeColor = TextWhite,
                FlatStyle = FlatStyle.Flat,
                Location = new Point(12, yOffset),
                Width = 196,
                Height = 24,
                Cursor = Cursors.Hand,
                Checked = true
            };
            _chkAutoIso.FlatAppearance.CheckedBackColor = TealAccent;
            _panelControls.Controls.Add(_chkAutoIso);
            yOffset += 30; // Shorter spacing for checkbox

            // Slider: ISO Sensitivity
            _sliderIso = new SticamSlider
            {
                Label      = "ISO SENSITIVITY",
                Min        = 0f,
                Max        = 3200f,
                Value      = 0f,
                ValueText  = "AUTO",
                Font       = MakeFont(10f),
                Location   = new Point(12, yOffset),
                Width      = 196,
                BackColor  = Color.Transparent
            };
            _sliderIso.ValueChanged += (s, e) =>
            {
                if (_isSyncing) return;
                int val = (int)_sliderIso.Value;
                
                // If user drags the slider, automatically turn off Auto ISO
                if (_chkAutoIso.Checked) {
                    _isSyncing = true;
                    _chkAutoIso.Checked = false;
                    _isSyncing = false;
                }
                
                _sliderIso.ValueText = $"{val}";
                _receiver?.SendCameraControl(iso: val, brightness: null, focus: null);
            };
            _chkAutoIso.CheckedChanged += (s, e) =>
            {
                if (_isSyncing) return;
                if (_chkAutoIso.Checked) {
                    _sliderIso.ValueText = "AUTO";
                    _receiver?.SendCameraControl(iso: -1, brightness: null, focus: null);
                } else {
                    int val = (int)_sliderIso.Value;
                    _sliderIso.ValueText = $"{val}";
                    _receiver?.SendCameraControl(iso: val, brightness: null, focus: null);
                }
            };
            _panelControls.Controls.Add(_sliderIso);
            yOffset += spacing;

            // Checkbox: Auto Focus
            _chkAutoFocus = new CheckBox
            {
                Text = "AUTO FOCUS",
                Font = MakeFont(9.5f),
                ForeColor = TextWhite,
                FlatStyle = FlatStyle.Flat,
                Location = new Point(12, yOffset),
                Width = 196,
                Height = 24,
                Cursor = Cursors.Hand,
                Checked = true
            };
            _chkAutoFocus.FlatAppearance.CheckedBackColor = TealAccent;
            _panelControls.Controls.Add(_chkAutoFocus);
            yOffset += 30; // Shorter spacing for checkbox

            // Slider: Focus Distance
            _sliderFocus = new SticamSlider
            {
                Label      = "MANUAL FOCUS",
                Min        = 0f,
                Max        = 1.0f,
                Value      = 0f,
                ValueText  = "AUTO",
                Font       = MakeFont(10f),
                Location   = new Point(12, yOffset),
                Width      = 196,
                BackColor  = Color.Transparent
            };
            _sliderFocus.ValueChanged += (s, e) =>
            {
                if (_isSyncing) return;
                if (_chkAutoFocus.Checked) {
                    _isSyncing = true;
                    _chkAutoFocus.Checked = false;
                    _isSyncing = false;
                }
                _sliderFocus.ValueText = $"{_sliderFocus.Value:F2}";
                _receiver?.SendCameraControl(iso: null, brightness: null, focus: _sliderFocus.Value);
            };
            _chkAutoFocus.CheckedChanged += (s, e) =>
            {
                if (_isSyncing) return;
                if (_chkAutoFocus.Checked) {
                    _sliderFocus.ValueText = "AUTO";
                    _receiver?.SendCameraControl(iso: null, brightness: null, focus: -1f);
                } else {
                    _sliderFocus.ValueText = $"{_sliderFocus.Value:F2}";
                    _receiver?.SendCameraControl(iso: null, brightness: null, focus: _sliderFocus.Value);
                }
            };
            _panelControls.Controls.Add(_sliderFocus);

            yOffset += spacing;
            _chkFlash = new CheckBox
            {
                Text = "FLASHLIGHT ON",
                Font = MakeFont(10f),
                ForeColor = TextWhite,
                FlatStyle = FlatStyle.Flat,
                Location = new Point(12, yOffset),
                Width = 196,
                Height = 24,
                BackColor = Color.Transparent
            };
            _chkFlash.FlatAppearance.BorderSize = 1;
            _chkFlash.FlatAppearance.CheckedBackColor = TealAccent;
            _chkFlash.CheckedChanged += (s, e) =>
            {
                if (_isSyncing) return;
                _receiver?.SendCameraControl(iso: null, brightness: null, focus: null, zoom: null, flash: _chkFlash.Checked);
            };
            _panelControls.Controls.Add(_chkFlash);

            yOffset += 38;
            _lblCameraTitle = new Label
            {
                Text = "CAMERA SOURCE",
                Font = MakeFont(10f),
                ForeColor = TextDim,
                Location = new Point(12, yOffset),
                AutoSize = true,
                BackColor = Color.Transparent
            };
            _panelControls.Controls.Add(_lblCameraTitle);

            yOffset += 18;
            _cbCamera = new ComboBox
            {
                Location = new Point(12, yOffset),
                Width = 196,
                DropDownStyle = ComboBoxStyle.DropDownList,
                BackColor = NavyMid,
                ForeColor = TextWhite,
                FlatStyle = FlatStyle.Flat,
                Font = MakeFont(9.5f)
            };
            _cbCamera.SelectedIndexChanged += (s, e) =>
            {
                if (_isSyncing) return;
                if (_cbCamera.SelectedItem is CameraItem item)
                {
                    _receiver?.SendCameraControl(iso: null, brightness: null, focus: null, zoom: null, flash: null, cameraId: item.Id);
                }
            };
            _panelControls.Controls.Add(_cbCamera);

            yOffset += 38;
            _lblResolutionTitle = new Label
            {
                Text = "RESOLUTION PRESET",
                Font = MakeFont(10f),
                ForeColor = TextDim,
                Location = new Point(12, yOffset),
                AutoSize = true,
                BackColor = Color.Transparent
            };
            _panelControls.Controls.Add(_lblResolutionTitle);

            yOffset += 18;
            _cbResolution = new ComboBox
            {
                Location = new Point(12, yOffset),
                Width = 196,
                DropDownStyle = ComboBoxStyle.DropDownList,
                BackColor = NavyMid,
                ForeColor = TextWhite,
                FlatStyle = FlatStyle.Flat,
                Font = MakeFont(9.5f)
            };
            _cbResolution.SelectedIndexChanged += (s, e) =>
            {
                if (_isSyncing) return;
                string? selectedRes = _cbResolution.SelectedItem?.ToString();
                if (!string.IsNullOrEmpty(selectedRes))
                {
                    _receiver?.SendCameraControl(iso: null, brightness: null, focus: null, zoom: null, flash: null, cameraId: null, resolution: selectedRes);
                }
            };
            _panelControls.Controls.Add(_cbResolution);
            


            _liveContainer.Controls.Add(_panelControls);
            _liveContainer.Controls.Add(_videoPb);
            _liveContainer.Controls.Add(_lblWatermark);
            _liveContainer.Controls.Add(_lblLiveStats);
            _liveContainer.Controls.Add(_lblDeviceName);
            _liveContainer.Controls.Add(_btnMenu);
            _liveContainer.Controls.Add(_menuPopup);

            _videoPb.SendToBack();

            _liveContainer.Resize += PositionOverlays;
            _panelControls.VisibleChanged += PositionOverlays;
            _videoPb.Click       += (_, _) => HideMenuPopup();
            _videoPb.DoubleClick += (_, _) => { if (_liveContainer.Visible) ToggleCinemaMode(); };
            _lblDeviceName.Click += (_, _) => HideMenuPopup();

            Controls.Add(_liveContainer);
            Controls.Add(_idleContainer);

            // Stats timer
            _statsTimer = new System.Windows.Forms.Timer { Interval = 1000 };
            _statsTimer.Tick += (_, _) =>
            {
                var r = _receiver;
                if (r == null) { _lblLiveStats.Text = ""; return; }
                long dF = Math.Max(0, r.FramesReceived - _lastStatsFrames);
                long dB = Math.Max(0, r.BytesReceived - _lastStatsBytes);
                _lastStatsFrames = r.FramesReceived;
                _lastStatsBytes = r.BytesReceived;
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
            string fontPath = Path.Combine(RuntimePaths.FontsDirectory, "Lalezar-Regular.ttf");
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
            int cw = _liveContainer.Width;
            int w = (_panelControls != null && _panelControls.Visible) ? cw - _panelControls.Width : cw;
            int h = _liveContainer.Height;

            if (_videoPb != null)
            {
                _videoPb.Location = new Point(0, 0);
                _videoPb.Size = new Size(w, h);
            }

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
            if (_panelControls != null) _panelControls.Visible = true;
            _lblDeviceName.BringToFront();
            _btnMenu.BringToFront();
            _lblWatermark.BringToFront();
            _lblLiveStats.BringToFront();
            _menuPopup.BringToFront();

            float scale = (float)this.DeviceDpi / 96f;
            if (_currentRotation == 90 || _currentRotation == 270) // Vertical (9:16)
            {
                this.MinimumSize = new Size((int)(450 * scale), (int)(540 * scale));
                if (Width < (int)(450 * scale)) Width = (int)(620 * scale);
                if (Height < (int)(540 * scale)) Height = (int)(780 * scale);
            }
            else // Horizontal (16:9)
            {
                this.MinimumSize = new Size((int)(860 * scale), (int)(520 * scale));
                if (Width < (int)(860 * scale)) Width = (int)(980 * scale);
                if (Height < (int)(520 * scale)) Height = (int)(580 * scale);
            }
            PositionOverlays(null, EventArgs.Empty);

            FormBorderStyle = FormBorderStyle.Sizable;
            MaximizeBox = true;
            UpdateTrayMenuItems();
        }

        private void ShowIdleMode()
        {
            lock (_previewLock)
            {
                _pendingPreviewFrame?.Dispose();
                _pendingPreviewFrame = null;
            }
            HideMenuPopup();
            if (_panelControls != null) _panelControls.Visible = false;
            _liveContainer.Visible = false;
            _idleContainer.Visible = true;
            _videoPb.Image?.Dispose();
            _videoPb.Image = null;
            if (_chkFlash != null) _chkFlash.Checked = false;
            if (_cbCamera != null) _cbCamera.Items.Clear();
            if (_cbResolution != null) _cbResolution.Items.Clear();
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;

            float scale = (float)this.DeviceDpi / 96f;
            Size = new Size((int)(720 * scale), (int)(650 * scale));
            UpdateTrayMenuItems();
        }

        // ── Connect / Disconnect ─────────────────────────────────────────────

        private void ReportBackendLog(string message)
        {
            System.Diagnostics.Trace.WriteLine(message);
            if (IsDisposed || !IsHandleCreated) return;
            void UpdateStatus()
            {
                if (IsDisposed) return;
                _lblStatus.Text = message;
                if (message.Contains("fail", StringComparison.OrdinalIgnoreCase) ||
                    message.Contains("error", StringComparison.OrdinalIgnoreCase) ||
                    message.Contains("not found", StringComparison.OrdinalIgnoreCase))
                {
                    _lblStatus.ForeColor = Color.FromArgb(240, 120, 100);
                    _menuVcamStatus.Text = message;
                }
                else
                {
                    _lblStatus.ForeColor = TextDim;
                }
            }
            if (InvokeRequired) BeginInvoke((Action)UpdateStatus);
            else UpdateStatus();
        }

        private void QueuePreviewFrame(Bitmap source, long sessionId, string modeLabel)
        {
            if (sessionId != System.Threading.Interlocked.Read(ref _sessionId) || IsDisposed) return;
            Bitmap clone;
            if (_currentRotation == 90 || _currentRotation == 270)
            {
                int cropWidth = Math.Min(source.Width, (source.Height * source.Height) / source.Width) & ~1;
                cropWidth = Math.Max(2, cropWidth);
                int cropX = (source.Width - cropWidth) / 2;
                clone = source.Clone(new Rectangle(cropX, 0, cropWidth, source.Height), source.PixelFormat);
            }
            else
            {
                clone = (Bitmap)source.Clone();
            }

            lock (_previewLock)
            {
                _pendingPreviewFrame?.Dispose();
                _pendingPreviewFrame = clone;
            }
            if (System.Threading.Interlocked.Exchange(ref _previewInvokePending, 1) == 0)
            {
                try { BeginInvoke((Action)(() => PresentPendingPreview(sessionId, modeLabel))); }
                catch
                {
                    lock (_previewLock)
                    {
                        _pendingPreviewFrame?.Dispose();
                        _pendingPreviewFrame = null;
                    }
                    System.Threading.Interlocked.Exchange(ref _previewInvokePending, 0);
                }
            }
        }

        private void PresentPendingPreview(long sessionId, string modeLabel)
        {
            Bitmap? next;
            lock (_previewLock)
            {
                next = _pendingPreviewFrame;
                _pendingPreviewFrame = null;
            }

            if (next != null)
            {
                if (sessionId != System.Threading.Interlocked.Read(ref _sessionId) || IsDisposed)
                    next.Dispose();
                else
                {
                    var old = _videoPb.Image;
                    _videoPb.Image = next;
                    old?.Dispose();
                    if (!_liveContainer.Visible)
                    {
                        ShowLiveMode($"Android Phone {modeLabel}");
                        if (_chkVirtualCam.Checked) StartVirtualCam();
                        UpdateMenuVcamButton();
                    }
                }
            }

            System.Threading.Interlocked.Exchange(ref _previewInvokePending, 0);
            lock (_previewLock)
            {
                if (_pendingPreviewFrame != null &&
                    System.Threading.Interlocked.Exchange(ref _previewInvokePending, 1) == 0)
                    BeginInvoke((Action)(() => PresentPendingPreview(sessionId, modeLabel)));
            }
        }

        private void OnConnect(object? s, EventArgs e)
        {
            if (_receiver != null)
            {
                OnDisconnect(null, EventArgs.Empty);
                return;
            }

            long sessionId = System.Threading.Interlocked.Increment(ref _sessionId);
            int hasEverConnected = 0;
            _lastStatsFrames = 0;
            _lastStatsBytes = 0;
            _lblStatus.Text = "Starting receiver...";

            _faceTrackingActive = false;
            UpdateMenuFaceTrackingButton();

            string host      = _rbUsb.Checked ? "127.0.0.1" : _tbIp.Text.Trim();
            bool   isUsb     = _rbUsb.Checked;
            string modeLabel = isUsb ? "[USB]" : "[Wi-Fi]";

            if (isUsb)
            {
                _adb = new AdbForwarder();
                _adb.OnLog += ReportBackendLog;
                _adb.Start();
            }

            _decoder = new VideoDecoder();
            _decoder.MirrorX = _mirrorVideo;
            _decoder.OnLog += ReportBackendLog;
            _decoder.OnFrameDecoded += bmp =>
            {
                QueuePreviewFrame(bmp, sessionId, modeLabel);
            };

            _receiver = new H264Receiver(host, _pairingKey, 8765);
            var receiver = _receiver;
            var decoder = _decoder;
            receiver.OnLog += ReportBackendLog;
            _receiver.OnCommandReceived += cmdJson =>
            {
                if (sessionId != System.Threading.Interlocked.Read(ref _sessionId) || IsDisposed || !IsHandleCreated) return;
                BeginInvoke(() =>
                {
                    if (sessionId != System.Threading.Interlocked.Read(ref _sessionId) || IsDisposed) return;
                    try
                    {
                    using var doc = JsonDocument.Parse(cmdJson);
                    var root = doc.RootElement;
                    var cmd = root.GetProperty("cmd").GetString();
                    if (cmd == "flip")
                    {
                        _mirrorVideo = root.GetProperty("mirrorX").GetBoolean();
                        if (_decoder != null)
                        {
                            _decoder.MirrorX = _mirrorVideo;
                            _decoder.MirrorY = root.GetProperty("mirrorY").GetBoolean();
                        }
                        UpdateMenuMirrorButton();
                    }
                    else if (cmd == "face_tracking")
                    {
                        _faceTrackingActive = root.GetProperty("enabled").GetBoolean();
                        UpdateMenuFaceTrackingButton();
                    }
                    else if (cmd == "sync_params")
                    {
                        _isSyncing = true;
                        try
                        {
                            if (root.TryGetProperty("face_tracking", out var ftProp))
                            {
                                _faceTrackingActive = ftProp.GetBoolean();
                                UpdateMenuFaceTrackingButton();
                            }
                            if (root.TryGetProperty("zoom", out var zoomProp))
                            {
                                float zoomVal = (float)zoomProp.GetDouble();
                                _sliderZoom.Value = zoomVal;
                                _sliderZoom.ValueText = $"{_sliderZoom.Value:F1}x";
                            }
                            if (root.TryGetProperty("brightness", out var brProp))
                            {
                                float brVal = (float)brProp.GetDouble();
                                _sliderExposure.Value = brVal;
                                int intBr = (int)Math.Round(brVal);
                                _sliderExposure.ValueText = intBr > 0 ? $"+{intBr}" : $"{intBr}";
                            }
                            if (root.TryGetProperty("iso", out var isoProp))
                            {
                                int isoVal = isoProp.GetInt32();
                                if (isoVal >= 0) {
                                    _sliderIso.Value = isoVal;
                                    _sliderIso.ValueText = $"{isoVal}";
                                    _chkAutoIso.Checked = false;
                                } else {
                                    _sliderIso.ValueText = "AUTO";
                                    _chkAutoIso.Checked = true;
                                }
                            }
                            if (root.TryGetProperty("max_focus", out var maxFocusProp))
                            {
                                float maxFocus = (float)maxFocusProp.GetDouble();
                                if (maxFocus <= 0.001f)
                                {
                                    _sliderFocus.Enabled = false;
                                    _chkAutoFocus.Enabled = false;
                                    _sliderFocus.ValueText = "FIXED FOCUS";
                                }
                                else
                                {
                                    _sliderFocus.Enabled = true;
                                    _chkAutoFocus.Enabled = true;
                                }
                            }
                            if (root.TryGetProperty("focus", out var focusProp))
                            {
                                float focusVal = (float)focusProp.GetDouble();
                                if (_sliderFocus.Enabled) 
                                {
                                    if (focusVal < 0f)
                                    {
                                        _chkAutoFocus.Checked = true;
                                        _sliderFocus.ValueText = "AUTO";
                                    }
                                    else
                                    {
                                        _chkAutoFocus.Checked = false;
                                        _sliderFocus.Value = focusVal;
                                        _sliderFocus.ValueText = $"{focusVal:F2}";
                                    }
                                }
                            }
                            if (root.TryGetProperty("flash", out var flashProp))
                            {
                                _chkFlash.Checked = flashProp.GetBoolean();
                            }
                            
                            // Cameras list & selection sync
                            if (root.TryGetProperty("cameras", out var camsProp) && root.TryGetProperty("selected_camera", out var selCamProp))
                            {
                                string selectedCamId = selCamProp.GetString() ?? "";
                                _cbCamera.BeginUpdate();
                                _cbCamera.Items.Clear();
                                CameraItem? selectedItem = null;
                                foreach (var camObj in camsProp.EnumerateArray())
                                {
                                    string cid = camObj.GetProperty("id").GetString() ?? "";
                                    string clabel = camObj.GetProperty("label").GetString() ?? "";
                                    var item = new CameraItem { Id = cid, Label = clabel };
                                    _cbCamera.Items.Add(item);
                                    if (cid == selectedCamId)
                                    {
                                        selectedItem = item;
                                    }
                                }
                                _cbCamera.EndUpdate();
                                if (selectedItem != null)
                                {
                                    _cbCamera.SelectedItem = selectedItem;
                                }
                            }

                            // Resolutions list & selection sync
                            if (root.TryGetProperty("resolutions", out var resListProp) && root.TryGetProperty("selected_resolution", out var selResProp))
                            {
                                string selectedRes = selResProp.GetString() ?? "";
                                _cbResolution.BeginUpdate();
                                _cbResolution.Items.Clear();
                                int selectedIndex = -1;
                                foreach (var resObj in resListProp.EnumerateArray())
                                {
                                    string resStr = resObj.GetString() ?? "";
                                    _cbResolution.Items.Add(resStr);
                                    if (resStr == selectedRes)
                                    {
                                        selectedIndex = _cbResolution.Items.Count - 1;
                                    }
                                }
                                _cbResolution.EndUpdate();
                                if (selectedIndex != -1)
                                {
                                    _cbResolution.SelectedIndex = selectedIndex;
                                }
                            }

                            if (root.TryGetProperty("orientation", out var oriProp))
                            {
                                int rotation = oriProp.GetInt32();
                                SetOrientation(rotation);
                            }
                        }
                        finally
                        {
                            _isSyncing = false;
                        }
                    }
                }
                catch (Exception ex) { ReportBackendLog($"[Control] Invalid command: {ex.Message}"); }
                });
            };
            receiver.OnConnectionChanged += ok =>
            {
                if (sessionId != System.Threading.Interlocked.Read(ref _sessionId) || IsDisposed || !IsHandleCreated) return;
                if (ok) System.Threading.Interlocked.Exchange(ref hasEverConnected, 1);
                BeginInvoke(() =>
                {
                    if (sessionId != System.Threading.Interlocked.Read(ref _sessionId) || IsDisposed) return;
                    if (!ok)
                    {
                        StopVirtualCam();
                        ShowIdleMode();
                        _btnConnect.Text = "RECONNECTING...";
                        _btnConnect.BackColor = Color.FromArgb(50, 130, 180);
                        _lblStatus.Text = "Connection lost; waiting for the phone to reconnect...";
                    }
                    else if (!_liveContainer.Visible)
                    {
                        _btnConnect.Text = "CONNECTED";
                        _btnConnect.BackColor = Color.FromArgb(50, 180, 130);
                        _lblStatus.Text = "Phone connected; waiting for video configuration...";
                    }
                    UpdateTrayMenuItems();
                });
            };
            receiver.OnConfigReceived += (sps, pps) =>
            {
                if (sessionId == System.Threading.Interlocked.Read(ref _sessionId))
                    decoder.Start(sps, pps);
            };
            receiver.OnFrameReceived += (_, args) =>
            {
                if (sessionId == System.Threading.Interlocked.Read(ref _sessionId))
                    decoder.PushFrame(args.Data, args.IsKeyFrame);
            };
            decoder.OnKeyFrameNeeded += receiver.RequestKeyFrame;

            receiver.Connect();
            _btnConnect.Text = "WAITING...";
            _btnConnect.BackColor = Color.FromArgb(50, 130, 180);
            UpdateTrayMenuItems();

            // Auto-timeout if no connection within 30 seconds
            var currentReceiver = _receiver;
            Task.Run(async () =>
            {
                await Task.Delay(30000);
                if (IsDisposed || !IsHandleCreated) return;
                BeginInvoke(() =>
                {
                    if (IsDisposed) return;
                    if (_receiver == currentReceiver &&
                        System.Threading.Interlocked.CompareExchange(ref hasEverConnected, 0, 0) == 0)
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
            System.Threading.Interlocked.Increment(ref _sessionId);
            StopVirtualCam();
            var receiver = _receiver;
            var decoder = _decoder;
            var adb = _adb;
            _receiver = null;
            _decoder = null;
            _adb = null;
            receiver?.Dispose();
            decoder?.Dispose();
            adb?.Dispose();

            _faceTrackingActive = false;
            UpdateMenuFaceTrackingButton();

            _btnConnect.Enabled    = true;
            _lblLiveStats.Text     = "";
            _btnConnect.Text       = "CONNECT";
            _btnConnect.BackColor  = TealAccent;
            _lblStatus.Text        = "Disconnected";

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
            int w = (_videoPb.Image?.Width ?? 1280) & ~1;
            int h = (_videoPb.Image?.Height ?? 720) & ~1;
            var manager = new VirtualCameraManager(w, h, fps: 30);
            _vcam = manager;
            manager.OnLog += ReportBackendLog;
            manager.OnModeChanged += detail =>
            {
                if (manager.ActiveMode != VirtualCameraMode.None || IsDisposed || !IsHandleCreated) return;
                BeginInvoke((Action)(() =>
                {
                    if (!ReferenceEquals(_vcam, manager)) return;
                    if (_decoder != null) _decoder.OnFrameDecoded -= OnVcamFrameDecoded;
                    manager.Dispose();
                    _vcam = null;
                    _vcamActive = false;
                    _menuVcamStatus.Text = detail;
                    UpdateMenuVcamButton();
                }));
            };
            string result = manager.Start();
            if (manager.ActiveMode != VirtualCameraMode.None)
            {
                _decoder.OnFrameDecoded += OnVcamFrameDecoded;
                _vcamActive = true;
            }
            else
            {
                manager.Dispose();
                _vcam = null;
                _vcamActive = false;
                ReportBackendLog(result);
            }
            _menuVcamStatus.Text = result;
        }

        private void StopVirtualCam()
        {
            if (_decoder != null) _decoder.OnFrameDecoded -= OnVcamFrameDecoded;
            _vcam?.Dispose(); _vcam = null;
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

        private void OnMenuFaceTrackingToggle(object? s, EventArgs e)
        {
            _faceTrackingActive = !_faceTrackingActive;
            _receiver?.SendFaceTracking(_faceTrackingActive);
            UpdateMenuFaceTrackingButton();
        }

        private void UpdateMenuFaceTrackingButton()
        {
            _menuFaceTracking.Text = _faceTrackingActive ? "🤖  AI Face Tracking: On" : "🤖  AI Face Tracking: Off";
            _menuFaceTracking.ForeColor = _faceTrackingActive ? Color.FromArgb(30, 204, 145) : Color.FromArgb(180, 180, 180);
        }

        // ── Helpers ──────────────────────────────────────────────────────────

        private Button MakeFlyoutButton(string text, Color fg)
        {
            var b = new Button
            {
                Text = text, ForeColor = fg, BackColor = Color.Transparent,
                FlatStyle = FlatStyle.Flat, Height = 38, Dock = DockStyle.Top,
                Font = MakeFont(11f, FontStyle.Bold),
                TextAlign = ContentAlignment.MiddleLeft,
                Cursor = Cursors.Hand, Padding = new Padding(8, 0, 0, 0),
            };
            b.FlatAppearance.BorderSize = 0;
            b.FlatAppearance.MouseOverBackColor = Color.FromArgb(60, 255, 255, 255);
            return b;
        }

        private void SetOrientation(int rotation)
        {
            if (_currentRotation == rotation) return;
            _currentRotation = rotation;

            float scale = (float)this.DeviceDpi / 96f;

            if (_currentRotation == 90 || _currentRotation == 270) // Vertical mode (9:16)
            {
                this.MinimumSize = new Size((int)(450 * scale), (int)(540 * scale));
                if (this.WindowState == FormWindowState.Normal)
                {
                    this.Size = new Size((int)(620 * scale), (int)(780 * scale));
                }
            }
            else // Horizontal mode (16:9)
            {
                this.MinimumSize = new Size((int)(860 * scale), (int)(520 * scale));
                if (this.WindowState == FormWindowState.Normal)
                {
                    this.Size = new Size((int)(980 * scale), (int)(580 * scale));
                }
            }
            
            PositionOverlays(null, EventArgs.Empty);
        }

        private void OnVcamFrameDecoded(Bitmap bmp)
        {
            var manager = _vcam;
            if (manager == null) return;
            
            if (_currentRotation == 90 || _currentRotation == 270) // Vertical mode, crop the frame!
            {
                int cropW = Math.Min(bmp.Width, (bmp.Height * bmp.Height) / bmp.Width) & ~1;
                cropW = Math.Max(2, cropW);
                int cropX = (bmp.Width - cropW) / 2;
                var cropRect = new Rectangle(cropX, 0, cropW, bmp.Height);
                using (Bitmap cropped = bmp.Clone(cropRect, bmp.PixelFormat))
                {
                    manager.PushFrame(cropped);
                }
            }
            else
            {
                manager.PushFrame(bmp);
            }
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
                if (_panelControls != null) _panelControls.Visible = false;
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
                if (_panelControls != null) _panelControls.Visible = true;
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
            lock (_previewLock)
            {
                _pendingPreviewFrame?.Dispose();
                _pendingPreviewFrame = null;
            }
            if (_currentTrayIcon != null)
            {
                IntPtr handle = _currentTrayIcon.Handle;
                _currentTrayIcon.Dispose();
                DestroyIcon(handle);
                _currentTrayIcon = null;
            }
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

    public class SticamSlider : Control
    {
        private float _value = 0f;
        private float _min = 0f;
        private float _max = 1f;
        private string _label = "";
        private string _valStr = "";
        private bool _isDragging = false;

        public event EventHandler? ValueChanged;

        public float Value
        {
            get => _value;
            set
            {
                float clamped = Math.Max(_min, Math.Min(_max, value));
                if (Math.Abs(_value - clamped) > 0.0001f)
                {
                    _value = clamped;
                    Invalidate();
                }
            }
        }

        public float Min { get => _min; set { _min = value; Invalidate(); } }
        public float Max { get => _max; set { _max = value; Invalidate(); } }
        public string Label { get => _label; set { _label = value; Invalidate(); } }
        public string ValueText { get => _valStr; set { _valStr = value; Invalidate(); } }

        public SticamSlider()
        {
            SetStyle(ControlStyles.SupportsTransparentBackColor, true);
            DoubleBuffered = true;
            Height = 45;
            Cursor = Cursors.Hand;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;

            // Draw label & value text
            using (var brushLabel = new SolidBrush(Color.FromArgb(240, 240, 240)))
            using (var brushVal = new SolidBrush(Color.FromArgb(30, 204, 145)))
            {
                g.DrawString(_label, this.Font, brushLabel, 2, 0);
                g.DrawString(_valStr, this.Font, brushVal, Width - g.MeasureString(_valStr, this.Font).Width - 2, 0);
            }

            // Draw track line
            int trackY = 28;
            int trackH = 4;
            int margin = 10;
            int trackW = Width - 2 * margin;

            using (var penBg = new Pen(Color.FromArgb(26, 42, 64), trackH)) // NavyMid
            {
                penBg.StartCap = System.Drawing.Drawing2D.LineCap.Round;
                penBg.EndCap = System.Drawing.Drawing2D.LineCap.Round;
                g.DrawLine(penBg, margin, trackY, Width - margin, trackY);
            }

            // Draw active portion
            float pct = (_max - _min) > 0 ? (_value - _min) / (_max - _min) : 0;
            int activeX = margin + (int)(pct * trackW);

            if (pct > 0)
            {
                using (var penAct = new Pen(Color.FromArgb(30, 204, 145), trackH)) // TealAccent
                {
                    penAct.StartCap = System.Drawing.Drawing2D.LineCap.Round;
                    penAct.EndCap = System.Drawing.Drawing2D.LineCap.Round;
                    g.DrawLine(penAct, margin, trackY, activeX, trackY);
                }
            }

            // Draw thumb circle
            int thumbRadius = 7;
            using (var brushThumb = new SolidBrush(Color.White))
            {
                g.FillEllipse(brushThumb, activeX - thumbRadius, trackY - thumbRadius, thumbRadius * 2, thumbRadius * 2);
            }
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            if (e.Button == MouseButtons.Left)
            {
                _isDragging = true;
                UpdateValueFromMouse(e.X);
            }
            base.OnMouseDown(e);
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            if (_isDragging)
            {
                UpdateValueFromMouse(e.X);
            }
            base.OnMouseMove(e);
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            _isDragging = false;
            base.OnMouseUp(e);
        }

        private void UpdateValueFromMouse(int mouseX)
        {
            int margin = 10;
            int trackW = Width - 2 * margin;
            if (trackW <= 0) return;

            float pct = (float)(mouseX - margin) / trackW;
            pct = Math.Max(0f, Math.Min(1f, pct));
            Value = _min + pct * (_max - _min);
            ValueChanged?.Invoke(this, EventArgs.Empty);
        }
    }

    public class CameraItem
    {
        public string Id { get; set; } = "";
        public string Label { get; set; } = "";
        public override string ToString() => Label;
    }
}
