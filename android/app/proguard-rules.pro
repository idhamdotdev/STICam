# Sticam ProGuard rules
# Camera2 and MediaCodec are Android SDK — no obfuscation needed
-keep class com.sticam.engine.** { *; }
-keep class com.sticam.server.** { *; }
-keepattributes SourceFile,LineNumberTable
-dontwarn kotlin.**
