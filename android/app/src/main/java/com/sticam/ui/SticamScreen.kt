package com.sticam.ui

import androidx.activity.compose.BackHandler
import android.graphics.Matrix
import android.view.TextureView
import android.view.Surface
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sticam.ui.theme.*
import com.sticam.ui.components.HudSlider

// ─────────────────────────────────────────────────────────────────────────────
//  Root screen — clean Iriun-style UI
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SticamScreen(vm: SticamViewModel) {
    val state      by vm.ui.collectAsStateWithLifecycle()

    var startRequested by remember { mutableStateOf(false) }
    var activeSlider by remember { mutableStateOf<String?>(null) }

    // Intercept back button gesture when connected/streaming so user doesn't exit accidentally
    BackHandler(enabled = state.isStreaming || startRequested) {
        // Do nothing. Only the STOP button exits the stream layout.
    }

    LaunchedEffect(state.isStreaming) {
        if (!state.isStreaming) {
            startRequested = false
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state.isScreenOffMode) {
        val activity = context as? android.app.Activity
        activity?.window?.attributes = activity?.window?.attributes?.apply {
            screenBrightness = if (state.isScreenOffMode) 0.01f else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!startRequested && !state.isStreaming) {
            IdleConnectionScreen(
                state = state,
                vm = vm,
                onConnectClick = { startRequested = true }
            )
        } else {
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val isPortraitUi = configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            if (isPortraitUi) {
                // Portrait output: preview (9:16) on left, side controls on right
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .systemBarsPadding()
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color.Black)
                            .clipToBounds(),
                        contentAlignment = Alignment.Center
                    ) {
                        ConnectedStreamLayout(
                            state = state,
                            vm = vm,
                            activeSlider = activeSlider,
                            isPortrait = true,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    SideControlBar(
                        state = state,
                        vm = vm,
                        onStopClick = { vm.stopStreaming() },
                        activeSlider = activeSlider,
                        onSliderToggle = { activeSlider = it }
                    )
                }
            } else {
                // Landscape output: preview (16:9) on top, bottom controls below
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .systemBarsPadding()
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .background(Color.Black)
                            .clipToBounds(),
                        contentAlignment = Alignment.Center
                    ) {
                        ConnectedStreamLayout(
                            state = state,
                            vm = vm,
                            activeSlider = activeSlider,
                            isPortrait = false,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    BottomControlBar(
                        state = state,
                        vm = vm,
                        onStopClick = { vm.stopStreaming() },
                        activeSlider = activeSlider,
                        onSliderToggle = { activeSlider = it }
                    )
                }
            }
        }


        if (state.showPresetSelector) {
            SelectorOverlay(title = "Quality", onDismiss = { vm.togglePresetSelector() }) {
                state.availableResolutions.forEach { r ->
                    SelectorItem(
                        label    = r.label,
                        sublabel = "${r.width}×${r.height}  ${r.fps}fps",
                        active   = r == state.preset,
                        onClick  = { vm.selectPreset(r) }
                    )
                }
            }
        }

        // Screen Off overlay
        if (state.isScreenOffMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { vm.setScreenOffMode(false) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "TAP ANYWHERE TO WAKE SCREEN",
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = 18.sp,
                    fontFamily = Lalezar,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun ConnectedStreamLayout(
    state: SticamUiState,
    vm: SticamViewModel,
    activeSlider: String?,
    isPortrait: Boolean = false,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier
            .background(Color.Black)
            .clipToBounds()
    ) {
        // ── Full-screen camera preview with pinch-to-zoom ─────────────────
        var pinchZoom by remember { mutableStateOf(state.zoom) }
        val bottomPadding = if (!isPortrait && activeSlider != null) 110.dp else 0.dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomPadding)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                CameraPreviewLayer(
                    vm = vm,
                    state = state,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val xNorm = offset.x / constraints.maxWidth.toFloat()
                                    val yNorm = offset.y / constraints.maxHeight.toFloat()
                                    vm.tapToFocus(xNorm, yNorm, constraints.maxWidth, constraints.maxHeight)
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoomChange, _ ->
                                pinchZoom = (pinchZoom * zoomChange).coerceIn(1f, state.maxZoom)
                                vm.setZoom(pinchZoom)
                            }
                        }
                )

                // ── Tap-to-Focus Ring Overlay ─────────────────────────────────────
                val tx = state.tapFocusX
                val ty = state.tapFocusY
                if (tx != null && ty != null) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val focusXDp = with(density) { (tx * constraints.maxWidth).toDp() }
                    val focusYDp = with(density) { (ty * constraints.maxHeight).toDp() }
                    Box(
                        Modifier
                            .size(68.dp)
                            .offset(x = focusXDp - 34.dp, y = focusYDp - 34.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .border(1.5.dp, Color(0xFFFFCC00), CircleShape)
                        )
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(Color(0xFFFFCC00), CircleShape)
                        )
                    }
                }
            }
        }

        // ── Camera OFF overlay ────────────────────────────────────────────
        if (!state.isCameraOn) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("⬛", fontSize = 48.sp)
                    Text(
                        "CAMERA OFF",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.6f),
                        letterSpacing = 4.sp,
                    )
                }
            }
        }





        // ── Zoom level indicator (top-center, shown only when zoomed) ─────
        AnimatedVisibility(
            visible = state.zoom > 1.05f,
            enter   = fadeIn(),
            exit    = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Text(
                text  = state.zoomLabel,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .padding(top = 14.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }

        // ── Connection status indicator overlay (top-right corner) ──
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 14.dp, end = 14.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                .border(1.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val dotColor = if (state.clientConnected) Color(0xFF00FF41) else Color(0xFFFFB300)
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(dotColor, CircleShape)
                )

                val isUsb = state.mode is com.sticam.ConnectionMode.Usb
                val statusText = if (state.clientConnected) {
                    if (isUsb) "CONNECTED VIA USB" else "CONNECTED VIA WI-FI"
                } else {
                    "WAITING FOR PC..."
                }
                Text(
                    text = statusText,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Lalezar
                )
                if (!isUsb && state.clientConnected) {
                    SignalBars(
                        bars = state.wifiSignalBars,
                        modifier = Modifier.padding(bottom = 1.dp)
                    )
                }
            }
        }



        // ── Active Slider Overlay ──
        val sliderModifier = if (isPortrait) {
            Modifier
                .align(Alignment.Center)
                .graphicsLayer(rotationZ = -90f)
                .width(320.dp)
                .padding(horizontal = 16.dp)
        } else {
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        }

        Box(modifier = sliderModifier) {
            AnimatedVisibility(
                visible = activeSlider != null,
                enter = fadeIn() + if (isPortrait) fadeIn() else slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + if (isPortrait) fadeOut() else slideOutVertically(targetOffsetY = { it })
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.85f))
                        .border(1.5.dp, Color(0xFFD1D5DB).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    when (activeSlider) {
                        "brightness" -> {
                            var brightnessVal by remember { mutableStateOf(state.brightnessValue) }
                            HudSlider(
                                label = "EXPOSURE COMPENSATION",
                                value = brightnessVal,
                                valueMin = -4f,
                                valueMax = 4f,
                                displayValue = if (brightnessVal > 0) "+${brightnessVal.toInt()}" else "${brightnessVal.toInt()}",
                                onValueChange = {
                                    brightnessVal = it
                                    vm.setBrightness(it)
                                }
                            )
                        }
                        "iso" -> {
                            var isoVal by remember { mutableStateOf(state.isoValue.toFloat()) }
                            Column {
                                HudSlider(
                                    label = "ISO SENSITIVITY",
                                    value = isoVal.coerceAtLeast(0f),
                                    valueMin = 0f,
                                    valueMax = 3200f,
                                    displayValue = if (isoVal < 0f) "AUTO" else if (isoVal == 0f) "MIN" else "${isoVal.toInt()}",
                                    onValueChange = {
                                        isoVal = it
                                        vm.setIso(it.toInt())
                                    }
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable {
                                        if (isoVal < 0f) {
                                            isoVal = 100f
                                            vm.setIso(100)
                                        } else {
                                            isoVal = -1f
                                            vm.setIso(-1)
                                        }
                                    }
                                ) {
                                    CustomRadioButton(selected = isoVal < 0f)
                                    Spacer(Modifier.width(8.dp))
                                    Text("AUTO ISO", color = Color.White, fontFamily = Lalezar, fontSize = 14.sp)
                                }
                            }
                        }
                        "focus" -> {
                            var focusVal by remember { mutableStateOf(state.focusValue) }
                            HudSlider(
                                label = "MANUAL FOCUS DISTANCE",
                                value = focusVal,
                                valueMin = 0f,
                                valueMax = 1f,
                                displayValue = if (focusVal == 0f) "AUTO" else "%.2f".format(focusVal),
                                onValueChange = {
                                    focusVal = it
                                    vm.setFocusDistance(it)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Camera preview  — correct aspect-ratio transform for portrait devices
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CameraPreviewLayer(
    vm: SticamViewModel,
    state: SticamUiState,
    modifier: Modifier = Modifier
) {
    var readySurface by remember { mutableStateOf<android.view.Surface?>(null) }

    /**
     * Standard Android Camera2 preview transform.
     *
     * The camera sensor buffer always arrives in sensor-native orientation
     * regardless of screen orientation. We always swap buffer dimensions
     * (camH × camW) and apply a rotation that varies with display rotation:
     *   ROTATION_0  (portrait)  → 180°
     *   ROTATION_90 (landscape) → 270°
     * Mirror is applied last as a simple X-axis flip.
     */
     fun applyTransform(tv: TextureView, viewW: Int, viewH: Int) {
        if (viewW == 0 || viewH == 0) return
        val matrix = Matrix()
        val cx = viewW / 2f
        val cy = viewH / 2f
        val camW = state.outW.toFloat()   // e.g. 1920
        val camH = state.outH.toFloat()   // e.g. 1080

        val isGlPath = state.activeLutFilter != "None" || state.activeArFilter != "None"
        val sensorOri = state.sensorOrientation
        val outputRot = state.outputRotation

        // Calculate the base rotation needed to align the sensor with the screen.
        // On the zero-copy path, we cancel the sensor orientation: (360 - sensorOri) % 360.
        // For the front camera, the SurfaceTexture default buffer transform implicitly mirrors
        // the image horizontally, which is equivalent to an extra 180° rotation. We add 180°
        // on the zero-copy path for front cameras to compensate and render the preview upright.
        // On the GL path, OpenGL adds a vertical flip (equivalent to 180°), so:
        // (360 - sensorOri + 180) % 360.
        val baseRot = if (isGlPath) {
            (360 - sensorOri + 180) % 360
        } else {
            if (state.isFrontCamera) (360 - sensorOri + 180) % 360
            else (360 - sensorOri) % 360
        }

        val rotationDeg = ((baseRot + outputRot) % 360).toFloat()

        // Since the SurfaceTexture's default transform rotates by sensorOrientation on the
        // zero-copy path, it swaps the width and height of the incoming buffer.
        // To cancel this stretching, we must swap the target dimensions in setRectToRect.
        // On the GL path, there is no default rotation, so we use the unswapped dimensions.
        val bufferW = if (isGlPath) camW else camH
        val bufferH = if (isGlPath) camH else camW

        val bufferRect = android.graphics.RectF(0f, 0f, bufferW, bufferH)
        bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
        matrix.setRectToRect(
            android.graphics.RectF(0f, 0f, viewW.toFloat(), viewH.toFloat()),
            bufferRect,
            Matrix.ScaleToFit.FILL
        )

        // If the user's selected manual orientation rotation is portrait (90° or 270°),
        // the visible dimensions swap, so we fit the portrait image with black bars.
        // Otherwise, it is landscape (0° or 180°), filling the 16:9 box perfectly.
        val isRotated = (outputRot == 90 || outputRot == 270)
        val visW = if (isRotated) camH else camW
        val visH = if (isRotated) camW else camH

        // Fit the video within the view, preserving aspect ratio (letterbox with black bars).
        // Using minOf ensures the full frame is always visible and never bleeds into the HUD.
        val scale = minOf(viewW / visW, viewH / visH)
        matrix.postScale(scale, scale, cx, cy)

        matrix.postRotate(rotationDeg, cx, cy)

        val mirror = if (state.isMirrored) -1f else 1f
        matrix.postScale(mirror, 1f, cx, cy)

        tv.setTransform(matrix)
    }

    androidx.compose.runtime.key(state.selectedCameraId, state.outW, state.outH) {
        Box(modifier = modifier.clipToBounds()) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                st: android.graphics.SurfaceTexture, w: Int, h: Int
                            ) {
                                st.setDefaultBufferSize(state.outW, state.outH)
                                applyTransform(this@apply, w, h)
                                readySurface = android.view.Surface(st)
                                vm.onPreviewSurfaceReady(readySurface!!)
                            }
                            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                st.setDefaultBufferSize(state.outW, state.outH)
                                applyTransform(this@apply, w, h)
                            }
                            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                vm.onPreviewSurfaceDestroyed()
                                readySurface = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                        }
                    }
                },
                update = { tv ->
                    applyTransform(tv, tv.width, tv.height)
                },
                modifier = Modifier.fillMaxSize(),
            )
            
            if (state.debugInfo.isNotEmpty()) {
                androidx.compose.material3.Text(
                    text = state.debugInfo,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.TopStart)
                        .padding(top = 40.dp, start = 16.dp)
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
                        .padding(4.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}




// ─────────────────────────────────────────────────────────────────────────────
//  IP dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IpInputDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var ip by remember { mutableStateOf(initial) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.80f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.80f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1C1C1E))
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Wi-Fi Host IP", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            BasicTextField(
                value = ip, onValueChange = { ip = it }, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                cursorBrush = SolidColor(Color.White),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.10f))
                            .padding(12.dp)
                    ) {
                        if (ip.isEmpty()) Text("192.168.x.x", color = Color.White.copy(alpha = 0.3f), fontSize = 17.sp)
                        inner()
                    }
                }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Cancel" to false, "Connect" to true).forEach { (lbl, confirm) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (confirm) Color(0xFF2A7AE2) else Color.White.copy(alpha = 0.08f))
                            .clickable { if (confirm) onConfirm(ip.trim()) else onDismiss() }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(lbl, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Selector overlay  (camera / quality)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SelectorOverlay(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.80f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .fillMaxHeight(0.80f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1C1C1E))
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SelectorItem(label: String, sublabel: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Color(0xFF2A7AE2).copy(alpha = 0.25f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label,    color = Color.White,                          fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(sublabel, color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp)
        }
        if (active) Text("✓", color = Color(0xFF2A7AE2), fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun IdleConnectionScreen(
    state: SticamUiState,
    vm: SticamViewModel,
    onConnectClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C1328))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .background(Color(0xFFD1D5DB))
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 18.dp, start = 40.dp, end = 40.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1.2f),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    Text(
                        text = "STICam",
                        color = Color.White,
                        fontSize = 56.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        fontFamily = Lalezar
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Spacer(modifier = Modifier.height(40.dp))
                        Text(
                            text = "by @idham.dev",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = Lalezar
                        )
                    }
                }

                val isUsb = state.mode is com.sticam.ConnectionMode.Usb
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { vm.selectUsb() }
                        .padding(vertical = 8.dp)
                ) {
                    CustomRadioButton(selected = isUsb)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "USB",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = Lalezar
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { vm.selectWifi(state.wifiIp) }
                        .padding(vertical = 8.dp)
                ) {
                    CustomRadioButton(selected = !isUsb)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "WI - FI",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = Lalezar
                    )
                    Spacer(modifier = Modifier.width(16.dp))

                    Box(
                        modifier = Modifier
                            .width(280.dp)
                            .height(42.dp)
                            .border(3.dp, Color(0xFFD1D5DB))
                            .background(Color.Black)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = state.wifiIp,
                            onValueChange = { vm.selectWifi(it) },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = Lalezar),
                            cursorBrush = SolidColor(Color.White),
                            decorationBox = { innerTextField ->
                                if (state.wifiIp.isEmpty()) {
                                    Text(
                                        text = "Enter PC's IP Adress",
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = Lalezar
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.8f),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.End
            ) {
                if (state.statusMessage.startsWith("ERR")) {
                    Text(
                        text = "Can't Connect, Try Again....",
                        color = Color(0xFFFF5252),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .width(180.dp)
                        .height(60.dp)
                        .border(4.dp, Color(0xFFD1D5DB))
                        .background(Color(0xFF2A2A2A))
                        .clickable { onConnectClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "CONNECT",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = Lalezar
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
fun CustomRadioButton(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .border(2.dp, Color.White, CircleShape)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFD1D5DB), CircleShape)
            )
        }
    }
}

@Composable
fun BottomControlBar(
    state: SticamUiState,
    vm: SticamViewModel,
    onStopClick: () -> Unit,
    activeSlider: String?,
    onSliderToggle: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0C1328))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlButton(
                active = state.isMirrored,
                onClick = { vm.toggleMirror() }
            ) {
                MirrorIcon(color = Color.White)
            }

            ControlButton(
                active = activeSlider == "brightness",
                onClick = { onSliderToggle(if (activeSlider == "brightness") null else "brightness") }
            ) {
                SunIcon(color = Color.White)
            }

            ControlButton(
                active = activeSlider == "iso",
                onClick = { onSliderToggle(if (activeSlider == "iso") null else "iso") }
            ) {
                Text("ISO", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }

            ControlButton(
                active = state.showPresetSelector,
                onClick = {
                    vm.togglePresetSelector()
                }
            ) {
                GearIcon(color = Color.White)
            }

            ControlButton(
                active = false,
                onClick = { vm.flipCamera() }
            ) {
                RotateIcon(color = Color.White)
            }



            ControlButton(
                active = state.isFaceTrackingEnabled,
                onClick = { vm.setFaceTracking(!state.isFaceTrackingEnabled) }
            ) {
                FaceIcon(color = Color.White)
            }

            ControlButton(
                active = state.isFlashOn,
                onClick = { vm.toggleFlash() }
            ) {
                FlashlightIcon(color = Color.White)
            }

            ControlButton(
                active = state.isScreenOffMode,
                onClick = { vm.setScreenOffMode(true) }
            ) {
                ScreenOffIcon(color = Color.White)
            }
        }

        Box(
            modifier = Modifier
                .width(120.dp)
                .height(54.dp)
                .border(3.dp, Color(0xFFD1D5DB))
                .background(Color(0xFF2A2A2A))
                .clickable { onStopClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "STOP",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = Lalezar
            )
        }
    }
}

@Composable
fun ControlButton(
    active: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .border(3.dp, if (active) Color(0xFF00FF41) else Color(0xFFD1D5DB))
            .background(Color(0xFF2A2A2A))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun MirrorIcon(color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        
        val centerX = w / 2f
        var y = 0f
        while (y < h) {
            drawLine(
                color = color.copy(alpha = 0.5f),
                start = Offset(centerX, y),
                end = Offset(centerX, (y + 4.dp.toPx()).coerceAtMost(h)),
                strokeWidth = 1.5.dp.toPx()
            )
            y += 8.dp.toPx()
        }

        val leftPath = Path().apply {
            moveTo(centerX - 2.dp.toPx(), h * 0.2f)
            lineTo(centerX - 8.dp.toPx(), h * 0.5f)
            lineTo(centerX - 2.dp.toPx(), h * 0.8f)
            close()
        }
        drawPath(leftPath, color = color, style = DrawStroke(width = 2.dp.toPx()))

        val rightPath = Path().apply {
            moveTo(centerX + 2.dp.toPx(), h * 0.2f)
            lineTo(centerX + 8.dp.toPx(), h * 0.5f)
            lineTo(centerX + 2.dp.toPx(), h * 0.8f)
            close()
        }
        drawPath(rightPath, color = color, style = DrawStroke(width = 2.dp.toPx()))
    }
}

@Composable
fun SunIcon(color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = w * 0.2f

        drawCircle(color = color, radius = r, style = DrawStroke(width = 2.dp.toPx()))

        val rayLength = w * 0.15f
        val rayStart = r + 3.dp.toPx()
        for (i in 0 until 8) {
            val angle = i * (Math.PI / 4)
            val startX = cx + (rayStart * Math.cos(angle)).toFloat()
            val startY = cy + (rayStart * Math.sin(angle)).toFloat()
            val endX = cx + ((rayStart + rayLength) * Math.cos(angle)).toFloat()
            val endY = cy + ((rayStart + rayLength) * Math.sin(angle)).toFloat()
            drawLine(
                color = color,
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

@Composable
fun GearIcon(color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val outerR = w * 0.3f
        val innerR = w * 0.15f

        drawCircle(color = color, radius = outerR, style = DrawStroke(width = 2.dp.toPx()))
        drawCircle(color = color, radius = innerR, style = DrawStroke(width = 1.5.dp.toPx()))

        for (i in 0 until 8) {
            val angle = i * (Math.PI / 4)
            val startX = cx + (outerR * Math.cos(angle)).toFloat()
            val startY = cy + (outerR * Math.sin(angle)).toFloat()
            val endX = cx + ((outerR + 3.dp.toPx()) * Math.cos(angle)).toFloat()
            val endY = cy + ((outerR + 3.dp.toPx()) * Math.sin(angle)).toFloat()
            drawLine(
                color = color,
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 3.dp.toPx()
            )
        }
    }
}



@Composable
fun RotateIcon(color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = w * 0.32f

        // Draw two arcs representing circular motion
        drawArc(
            color = color,
            startAngle = -70f,
            sweepAngle = 140f,
            useCenter = false,
            style = DrawStroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
        drawArc(
            color = color,
            startAngle = 110f,
            sweepAngle = 140f,
            useCenter = false,
            style = DrawStroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )

        // Helper to draw arrowheads at ends of arcs
        fun drawArrow(thetaDegrees: Float) {
            val theta = (thetaDegrees * Math.PI / 180.0).toFloat()
            val cos = Math.cos(theta.toDouble()).toFloat()
            val sin = Math.sin(theta.toDouble()).toFloat()
            val px = cx + r * cos
            val py = cy + r * sin
            val tx = -sin
            val ty = cos
            val nx = cos
            val ny = sin
            val len = 7.dp.toPx()

            val tipX = px + len * 0.3f * tx
            val tipY = py + len * 0.3f * ty
            val leftX = px - len * 0.7f * tx + len * 0.5f * nx
            val leftY = py - len * 0.7f * ty + len * 0.5f * ny
            val rightX = px - len * 0.7f * tx - len * 0.5f * nx
            val rightY = py - len * 0.7f * ty - len * 0.5f * ny

            val p = Path().apply {
                moveTo(tipX, tipY)
                lineTo(leftX, leftY)
                lineTo(rightX, rightY)
                close()
            }
            drawPath(p, color = color)
        }

        drawArrow(70f)
        drawArrow(250f)
    }
}

@Composable
fun FlashlightIcon(color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        
        val p = Path().apply {
            moveTo(w * 0.35f, h * 0.2f)
            lineTo(w * 0.65f, h * 0.2f)
            lineTo(w * 0.6f, h * 0.45f)
            lineTo(w * 0.55f, h * 0.45f)
            lineTo(w * 0.55f, h * 0.85f)
            lineTo(w * 0.45f, h * 0.85f)
            lineTo(w * 0.45f, h * 0.45f)
            lineTo(w * 0.4f, h * 0.45f)
            close()
        }
        drawPath(p, color = color, style = DrawStroke(width = 2.dp.toPx()))
    }
}

@Composable
fun FaceIcon(color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = w * 0.4f

        drawCircle(color = color, radius = r, style = DrawStroke(width = 2.dp.toPx()))

        // Left eye
        drawCircle(color = color, radius = 1.5.dp.toPx(), center = Offset(cx - w * 0.15f, cy - h * 0.1f))
        // Right eye
        drawCircle(color = color, radius = 1.5.dp.toPx(), center = Offset(cx + w * 0.15f, cy - h * 0.1f))
        
        // Smile / mouth
        drawArc(
            color = color,
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(cx - w * 0.2f, cy - h * 0.1f),
            size = androidx.compose.ui.geometry.Size(w * 0.4f, h * 0.4f),
            style = DrawStroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
    }
}

@Composable
fun ScreenOffIcon(color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        
        // Draw smartphone outline
        val phoneW = w * 0.45f
        val phoneH = h * 0.75f
        val px = (w - phoneW) / 2f
        val py = (h - phoneH) / 2f
        
        drawRoundRect(
            color = color,
            topLeft = Offset(px, py),
            size = androidx.compose.ui.geometry.Size(phoneW, phoneH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            style = DrawStroke(width = 2.dp.toPx())
        )
        
        // Draw home button notch line
        drawLine(
            color = color,
            start = Offset(px + phoneW * 0.35f, py + phoneH - 4.dp.toPx()),
            end = Offset(px + phoneW * 0.65f, py + phoneH - 4.dp.toPx()),
            strokeWidth = 1.5.dp.toPx()
        )
        
        // Draw diagonal slash line
        drawLine(
            color = color,
            start = Offset(w * 0.22f, h * 0.22f),
            end = Offset(w * 0.78f, h * 0.78f),
            strokeWidth = 2.dp.toPx()
        )
    }
}

@Composable
fun SignalBars(bars: Int, modifier: Modifier = Modifier) {
    val activeColor = when (bars) {
        4 -> Color(0xFF00FF41) // Vibrant green
        3 -> Color(0xFFADFF2F) // Green-yellow
        2 -> Color(0xFFFFB300) // Amber/Orange
        else -> Color(0xFFFF3D00) // Red
    }
    val inactiveColor = Color.White.copy(alpha = 0.2f)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (i in 1..4) {
            val isActive = i <= bars
            val barHeight = (4 * i).dp
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeight)
                    .background(
                        if (isActive) activeColor else inactiveColor, 
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

@Composable
fun SideControlBar(
    state: SticamUiState,
    vm: SticamViewModel,
    onStopClick: () -> Unit,
    activeSlider: String?,
    onSliderToggle: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(Color(0xFF0C1328))
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val iconColor = Color.White
        fun Modifier.portraitRotate() = this.graphicsLayer(rotationZ = -90f)

        // Column 1: Mirror, Exposure, ISO, Quality, Face Tracking
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxHeight()
        ) {
            ControlButton(
                active = state.isMirrored,
                onClick = { vm.toggleMirror() }
            ) {
                Box(Modifier.portraitRotate()) { MirrorIcon(color = iconColor) }
            }

            ControlButton(
                active = activeSlider == "brightness",
                onClick = { onSliderToggle(if (activeSlider == "brightness") null else "brightness") }
            ) {
                Box(Modifier.portraitRotate()) { SunIcon(color = iconColor) }
            }

            ControlButton(
                active = activeSlider == "iso",
                onClick = { onSliderToggle(if (activeSlider == "iso") null else "iso") }
            ) {
                Box(Modifier.portraitRotate()) {
                    Text("ISO", color = iconColor, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            ControlButton(
                active = state.showPresetSelector,
                onClick = { vm.togglePresetSelector() }
            ) {
                Box(Modifier.portraitRotate()) { GearIcon(color = iconColor) }
            }

            ControlButton(
                active = state.isFaceTrackingEnabled,
                onClick = { vm.setFaceTracking(!state.isFaceTrackingEnabled) }
            ) {
                Box(Modifier.portraitRotate()) { FaceIcon(color = iconColor) }
            }
        }

        // Column 2: Flip Camera, Orientation, Flash, Screen Off, Connection Status, STOP Button
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxHeight()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ControlButton(
                    active = false,
                    onClick = { vm.flipCamera() }
                ) {
                    Box(Modifier.portraitRotate()) { RotateIcon(color = iconColor) }
                }



                ControlButton(
                    active = state.isFlashOn,
                    onClick = { vm.toggleFlash() }
                ) {
                    Box(Modifier.portraitRotate()) { FlashlightIcon(color = iconColor) }
                }

                ControlButton(
                    active = state.isScreenOffMode,
                    onClick = { vm.setScreenOffMode(true) }
                ) {
                    Box(Modifier.portraitRotate()) { ScreenOffIcon(color = iconColor) }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isUsb = state.mode is com.sticam.ConnectionMode.Usb
                val statusText = if (state.clientConnected) {
                    if (isUsb) "CONNECTED" else "WIFI"
                } else {
                    "WAITING"
                }
                Box(
                    modifier = Modifier.graphicsLayer(rotationZ = -90f).padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = statusText,
                        color = if (state.clientConnected) Color.White else Color(0xFFFFB300),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = Lalezar,
                        textAlign = TextAlign.Center
                    )
                }

                Box(
                    modifier = Modifier
                        .size(54.dp, 44.dp)
                        .graphicsLayer(rotationZ = -90f)
                        .border(2.dp, Color(0xFFD1D5DB))
                        .background(Color(0xFF2A2A2A))
                        .clickable { onStopClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "STOP",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = Lalezar
                    )
                }
            }
        }
    }
}
