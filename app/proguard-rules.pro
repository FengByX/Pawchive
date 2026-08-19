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

# ---------- Hilt / Dagger / AssistedInject ----------
# Hilt AAR 已通过 consumer proguard 自动保留 @HiltAndroidApp/@HiltAndroidEntryPoint
# 生成的 *_HiltModules / *_Factory / *_MembersInjector / Hilt_* 包装类。
# 这里只补充 R8 不自动识别的部分，且避免过度保留（dagger.**{*;} 反而让 R8 无法做正确优化）。

# 1) javax.inject 注解与 Dagger 核心反射入口（@Inject/@Qualifier/@Named/@Singleton 等）
-keepattributes Signature, InnerClasses, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep class javax.inject.** { *; }
-keep class dagger.** {
    <fields>;
    <methods>;
}
-dontwarn dagger.internal.codegen.**

# 2) Hilt 生成的组件与 Holder：**_HiltComponents、**_HiltModules、Hilt_* 包装类
-keep class **_HiltComponents { *; }
-keep class **_HiltModules { *; }
-keep class Hilt_* { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# 3) @HiltWorker 关键：HiltWorkerFactory 反射查找 "WorkerClassName_AssistedFactory"。
#    如果这个 Factory 类被 R8 重命名或移除，createWorker() 直接抛异常、Work 立刻 FAILED。
-keep class * {
    @androidx.hilt.work.HiltWorker <init>(...);
}
-keep class **_AssistedFactory { *; }

# 4) @AssistedInject / @AssistedFactory：用户自定义工厂接口名可能不以上述后缀结尾
-keep class * {
    @dagger.assisted.AssistedInject <init>(...);
}
-keep @dagger.assisted.AssistedFactory interface * { *; }

# 5) Dagger 生成的绑定类（避免过度保留，但 HiltWorker 必须的 Factory 不被误删）
-keep,allowobfuscation,allowshrinking class **_Factory { <init>(...); <methods>; }
-keep,allowobfuscation,allowshrinking class **_MembersInjector { <init>(...); <methods>; }

# 6) Kotlin Metadata：Kotlin 构造函数参数名反射需要它
-keepattributes kotlin.Metadata
-keep class kotlin.Metadata { *; }
-keep class kotlin.coroutines.Continuation { *; }

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


# okdownload
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings
-keep class com.liulishuo.okdownload.** { *; }
-keep class com.liulishuo.okdownload.connection.okhttp.** { *; }
