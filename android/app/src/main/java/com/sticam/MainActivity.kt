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
     * POST_NOTIFICATIONS (13+) may be declined without consequence: streaming still
     * works, the foreground-service notification is simply hidden.
     */
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[android.Manifest.permission.CAMERA] != true) vm.onPermissionDenied()
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

        // Request CAMERA (+ notification permission on 13+) on first launch
        val perms = mutableListOf(android.Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms += android.Manifest.permission.POST_NOTIFICATIONS
        }
        permLauncher.launch(perms.toTypedArray())

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
}
