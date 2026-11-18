# Consumer ProGuard rules for dpad-messenger library.
-keep class com.offline.dpadmessenger.data.** { *; }

# HIT video converter: JNI methods are looked up by name from libhitvideo.so.
-keep class com.offline.dpadmessenger.ui.util.HitVideoNative { native <methods>; }
