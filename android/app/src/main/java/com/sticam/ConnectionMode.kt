package com.sticam

/**
 * Connectivity mode — determines how the Android device reaches the PC.
 *
 *  WiFi → host = user-entered IP (e.g. "192.168.1.10")
 *  Usb  → host = "127.0.0.1" (ADB forward tunnel; PC runs `adb forward tcp:8765 tcp:8765`)
 */
sealed class ConnectionMode(val host: String) {
    class WiFi(ip: String) : ConnectionMode(ip)
    data object Usb : ConnectionMode("127.0.0.1")
}
