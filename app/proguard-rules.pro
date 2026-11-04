# Project specific ProGuard rules for KryptonIDE.

# ── General Optimizations & Stacktraces ──
-keepattributes SourceFile,LineNumberTable,*Annotation*,InnerClasses,Signature,EnclosingMethod
-renamesourcefileattribute SourceFile

# ── AndroidX & Compose ──
-dontwarn androidx.**
-keep class androidx.compose.** { *; }

# ── AndroidX Security Crypto & Tink ──
-dontwarn androidx.security.crypto.**
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class com.google.crypto.tink.** { *; }

# ── Room Database ──
-dontwarn androidx.room.paging.**
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.RoomDatabase$Callback
-keep class * extends androidx.room.migration.Migration

# ── Kotlin & Coroutines ──
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# ── Kotlinx Serialization ──
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# ── OkHttp3 & Okio ──
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# ── JNI & Native Bridges ──
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.sednium.kryptonide.agent.hardware.OortyNativeBridge { *; }

# ── WebView & JavaScript Interfaces ──
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.sednium.kryptonide.ui.monaco.MonacoBridge { *; }
-keep class com.sednium.kryptonide.ui.preview.PreviewConsoleBridge { *; }

# ── Sora Editor (Rosemoe Sora Editor & TextMate) ──
-keep class io.github.rosemoe.sora.** { *; }
-keep interface io.github.rosemoe.sora.** { *; }
-dontwarn io.github.rosemoe.sora.**
-keep class org.eclipse.tm4e.** { *; }
-dontwarn org.eclipse.tm4e.**
-keep class org.jruby.joni.** { *; }
-keep class org.jruby.jcodings.** { *; }

# ── JGit (Eclipse JGit & JSch) ──
-keep class org.eclipse.jgit.** { *; }
-keep interface org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.slf4j.**

# ── Apache HTTP & Java Extensions (Missing on Android) ──
-dontwarn org.apache.http.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**

# ── Google Play Services & Google API Client ──
-dontwarn com.google.android.gms.**
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.drive.** { *; }
-dontwarn com.google.api.client.**
-dontwarn com.google.api.services.drive.**

# ── Coil ──
-dontwarn coil.**
-keep class coil.** { *; }

# ── Lucide Icons ──
-keep class com.composables.icons.lucide.** { *; }
