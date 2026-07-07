# Consumer R8/ProGuard rules for the :signal module — applied automatically to
# any app that minifies while depending on this module (e.g. the launcher).
#
# The Signal wire protocol is protobuf-lite, and device linking/provisioning is
# all protobuf. protobuf-javalite accesses generated message fields reflectively;
# a minified build strips/renames them, producing malformed provisioning
# messages and a server "Invalid response from service" error. Keep the
# generated messages + fields, and libsignal (JNI + reflection).
-keep class com.google.protobuf.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.protobuf.**

-keep class org.signal.** { *; }
-keepclassmembers class org.signal.** { *; }
-dontwarn org.signal.**

-keep class org.whispersystems.signalservice.** { *; }
-keep class com.offline.dpadmessenger.backend.signal.groupsproto.** { *; }
-dontwarn org.whispersystems.signalservice.**

# ObjectBox (message store). The objectbox-android AAR ships its own consumer
# rules for the io.objectbox runtime, but our @Entity classes and the generated
# `MyObjectBox` / per-entity `<Entity>_` metadata are accessed by the native
# binding via field names, so they must survive minification. Keep the whole
# store package (entities + generated code both live under it).
-keep class com.offline.dpadmessenger.backend.signal.store.** { *; }
-keepclassmembers class com.offline.dpadmessenger.backend.signal.store.** { *; }
-keep @io.objectbox.annotation.Entity class * { *; }
-keepclassmembers class * {
    @io.objectbox.annotation.Id <fields>;
}
-dontwarn io.objectbox.**
