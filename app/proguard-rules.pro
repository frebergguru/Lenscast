# Lenscast proguard rules.
# CameraX uses reflection internally; keep its public API.
-keep class androidx.camera.** { *; }

# RootEncoder ships some MediaCodec native bindings; keep its public API.
-keep class com.pedro.** { *; }

# Compose runtime classes are referenced by generated code.
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}

# Keep kotlinx coroutines internals used by reflection in some libs.
-keepnames class kotlinx.coroutines.** { *; }
