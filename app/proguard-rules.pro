# VidMateX ProGuard rules.
# Keep this file conservative: the app uses Hilt, Retrofit + kotlinx.serialization,
# OkHttp, Coil, Compose, and ffmpeg-kit (native). Rules below are additive only.

# --- Hilt / Dagger ---
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
# Generated Hilt components / entry points are referenced reflectively at startup.
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class *
-keepclassmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}
# Hilt generated classes.
-keep class app.vidmatex.**_HiltModules { *; }
-keep class app.vidmatex.hilt.** { *; }

# --- Kotlin metadata / coroutines ---
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# --- kotlinx.serialization (serializers are looked up by generated companion) ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class app.vidmatex.**$$serializer { *; }
-keepclassmembers class app.vidmatex.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class app.vidmatex.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Retrofit / OkHttp ---
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature, Exceptions
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# --- Coil (image loading) ---
-dontwarn coil.**
-keep class coil.** { *; }

# --- Media3 ExoPlayer / UI (1.3.1) ---
# The player is reflection-free, but optional decoder extension classes may be loaded
# reflectively and common track/metadata models are inspected by UI helpers; keep the
# common model package and suppress warnings for optional/legacy API references.
-dontwarn androidx.media3.**
-keep class androidx.media3.common.** { *; }
-keep class androidx.media3.ui.** { *; }

# --- Compose (kept by default R8 rules; add safety) ---
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }

# --- ffmpeg-kit (native libs + JNI) ---
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**
-keep class com.arthenica.smartexception.** { *; }

# --- ML Kit translation (optional, keep model classes) ---
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# --- Room (if/when database module adds entities) ---
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
