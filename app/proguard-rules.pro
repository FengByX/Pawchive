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
# Hilt generated components and injection bindings
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class dagger.** { *; }
# Hilt generated component holders (Application, Activity, etc.)
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
# Keep Hilt-generated MembersInjector and Factory classes for all @HiltAndroidApp, @HiltWorker
-keep class * {
    @dagger.hilt.android.HiltAndroidApp <init>(...);
}
-keep class * {
    @dagger.hilt.android.HiltWorker <init>(...);
}
-keep class *_Factory { *; }
-keep class *_MembersInjector { *; }
-keep class *_Component { *; }
-keep class *_Component$Builder { *; }
# Keep Hilt generated root components
-keep class **_HiltComponents { *; }
-keep class **_HiltModules { *; }
# Keep kotlin.Metadata for Hilt's use of Kotlin reflection
-keepattributes kotlin.Metadata
-keep class kotlin.Metadata { *; }

# ---------- AndroidX / Jetpack ----------
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ---------- Media3 / ExoPlayer ----------
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ---------- EncryptedSharedPreferences / Tink ----------
-keep class androidx.security.crypto.** { *; }
# Tink depends on google-api-client as optional; we don't use Tink's remote key fetching
-dontwarn com.google.api.client.http.GenericUrl
-dontwarn com.google.api.client.http.HttpHeaders
-dontwarn com.google.api.client.http.HttpRequest
-dontwarn com.google.api.client.http.HttpRequestFactory
-dontwarn com.google.api.client.http.HttpResponse
-dontwarn com.google.api.client.http.HttpTransport
-dontwarn com.google.api.client.http.javanet.NetHttpTransport$Builder
-dontwarn com.google.api.client.http.javanet.NetHttpTransport
-dontwarn org.joda.time.Instant

# ---------- WorkManager ----------
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# ---------- General ----------
# Keep crash handler class names for readable stack traces
-keepnames class com.pawchive.** { *; }
