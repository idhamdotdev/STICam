# Sticam ProGuard rules
# Camera2 and MediaCodec are Android SDK — no obfuscation needed
-keep class com.sticam.engine.** { *; }
-keep class com.sticam.server.** { *; }
-keepattributes SourceFile,LineNumberTable
-dontwarn kotlin.**

# MediaPipe Face Landmarker
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# MediaPipe's AAR bundles the AutoValue annotation processor, which references
# compile-time-only JDK classes (javax.lang.model, javax.annotation.processing).
# They never exist on Android and are never called at runtime — silence R8.
-dontwarn javax.annotation.processing.**
-dontwarn javax.lang.model.**
-dontwarn com.google.auto.value.**
-dontwarn autovalue.shaded.**

