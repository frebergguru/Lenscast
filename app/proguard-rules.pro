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

# The SFTP stack's EdDSA provider (net.i2p.crypto.eddsa) has an optional fallback that
# references sun.security.x509.X509Key, which isn't on the Android boot classpath. The path
# is never taken at runtime (we feed it standard key material), so silence the R8 warning.
-dontwarn sun.security.x509.X509Key
