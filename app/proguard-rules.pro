# =============================================
# Pawchive ProGuard / R8 Rules (PERF-009)
# =============================================

# ---------- Retrofit ----------
# Retain generic type information for Retrofit service interfaces
-keepattributes Signature, Exceptions, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep,allowobfuscation,allowshrinking interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# Retrofit + Kotlin suspend functions use continuation
-keep class kotlin.coroutines.Continuation { *; }

# ---------- OkHttp ----------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ---------- Gson ----------
# Keep all API model classes used by Gson (field names must match JSON keys)
-keep class com.pawchive.core.model.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Prevent R8 from stripping interface information from TypeAdapter, TypeAdapterFactory,
# JsonSerializer, JsonDeserializer instances (so they can be used in @Adapters)
-keep class * extends com.google.gson.TypeAdapter { *; }
-keep class * implements com.google.gson.TypeAdapterFactory { *; }
-keep class * implements com.google.gson.JsonSerializer { *; }
-keep class * implements com.google.gson.JsonDeserializer { *; }

# ---------- Room ----------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# ---------- Coil ----------
-dontwarn coil.**

# ---------- Hilt / Dagger ----------
# Hilt generated components
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ---------- AndroidX / Jetpack ----------
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ---------- Media3 / ExoPlayer ----------
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ---------- EncryptedSharedPreferences ----------
-keep class androidx.security.crypto.** { *; }

# ---------- WorkManager ----------
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# ---------- General ----------
# Keep crash handler class names for readable stack traces
-keepnames class com.pawchive.** { *; }
