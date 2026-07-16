# --- Stability over size (PG) ---
# Obfuscation/optimization have caused release-only crashes with the vendored gomobile core,
# Hilt-generated reflection, and kotlinx.serialization's runtime-name lookups. Shrinking (unused
# class/member removal) still runs; only renaming + bytecode optimization passes are disabled.
-dontobfuscate
-dontoptimize

# --- Firebase Crashlytics / Analytics / Performance: keep lightly (no full-package -keep needed with -dontobfuscate,
# but pin down the reflective bits so a future re-enable of obfuscation stays safe) ---
-keep class com.google.firebase.crashlytics.** { *; }
-keep class com.google.firebase.analytics.** { *; }
-keep class com.google.firebase.perf.** { *; }
-keepattributes SourceFile,LineNumberTable
-dontwarn com.google.firebase.**

# --- Vendored Xray core (gomobile / JNI) ---
-keep class libv2ray.** { *; }
-keep class go.** { *; }
-keep class hev.htproxy.TProxyService { *; }
-keepclassmembers class hev.htproxy.TProxyService {
    native <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.v2rayez.app.domain.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.v2rayez.app.domain.model.**$$serializer { *; }
-keepclassmembers class com.v2rayez.app.domain.model.** {
    *** Companion;
}

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**

# --- BouncyCastle (MITM CA generation / import) ---
# Android's platform "BC" stub must be replaced at runtime; keep the full provider SPIs
# so R8 cannot strip SHA256withRSA / ContentSigner support.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- Keep VPN service / tile / receiver entry points ---
-keep class com.v2rayez.app.data.service.** { *; }
