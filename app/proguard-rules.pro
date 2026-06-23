# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools.

# Disable obfuscation (do not rename classes, fields, or methods)
-dontobfuscate

# Keep source file + line numbers so crash traces stay readable (File.kt:NN), paired
# with -dontobfuscate. proguard-android-optimize.txt already keeps these; we declare it
# explicitly so readable traces survive regardless of the base config.
-keepattributes SourceFile,LineNumberTable

# Keep Ktor serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Suppress R8 warnings for missing optional dependencies
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Logging policy: KEEP all android.util.Log calls in release builds.
# R8 only strips Log.* when an `-assumenosideeffects class android.util.Log { ... }`
# rule is present. We intentionally do NOT add one, so Log.d/Log.i survive minification
# and on-device diagnostics (e.g. DailyGraphRenderer / DailyViewLogic render decisions)
# remain available in release. Do not add an assumenosideeffects rule for android.util.Log.
