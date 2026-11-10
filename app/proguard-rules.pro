# Keep Media3 session service and playback components available for Android.
-keep class androidx.media3.** { *; }

# Preserve Kotlin metadata used by reflection and serialization libraries.
-keep class kotlin.Metadata { *; }

# Keep application classes that may be referenced from the Android manifest.
-keep class com.bsm.musicplayer.** extends android.app.Service { *; }
