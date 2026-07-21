package com.sticam

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.pm.ActivityInfo
import com.sticam.ui.SticamScreen
import com.sticam.ui.SticamViewModel
import com.sticam.ui.theme.SticamTheme

class MainActivity : ComponentActivity() {

    private val vm: SticamViewModel by viewModels()

    /**
     * Permission launcher — streams begin via the TextureView SurfaceTextureListener
     * inside SticamScreen once CAMERA is confirmed. We only guard against denial here.
     */
    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) vm.onPermissionDenied()
        // If granted: SticamScreen's SurfaceTextureListener calls vm.startStreaming()
        // as soon as the TextureView surface becomes available — no race condition.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Start in landscape by default ─────────────────────────────────────
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // ── Provide activity reference to ViewModel for orientation toggling ──
        vm.attachActivity(this)

        // ── Keep screen on during streaming ───────────────────────────────────
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ── Edge-to-edge + immersive fullscreen ───────────────────────────────
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.statusBarColor     = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        // Request CAMERA on first launch
        cameraPermLauncher.launch(android.Manifest.permission.CAMERA)

        setContent {
            SticamTheme {
                SticamScreen(vm)
            }
        }
    }

    override fun onDestroy() {
        vm.stopStreaming()
        super.onDestroy()
    }

    override fun onStop() {
        // Streaming is Activity-scoped. A foreground camera service is not
        // implemented, so release camera/network resources when backgrounded.
        vm.stopStreaming()
        super.onStop()
    }
}
