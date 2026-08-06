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

# Firebase discovers its components (Crashlytics, Installations, ...) reflectively via
# ComponentDiscovery, which instantiates each ComponentRegistrar through its no-arg
# constructor. R8 strips those "unused" constructors even with -dontobfuscate, and
# ComponentDiscovery then logs NoSuchMethodException and silently skips the component —
# crash reporting dies with only a W-level logcat line. Keep the registrars whole.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}

# WorkManager instantiates InputMergers and (fallback path) Workers reflectively by class
# name. R8 stripped OverwritingInputMerger's no-arg constructor, which made EVERY worker
# run fail with "Could not create Input Merger" — i.e. no background fetch ever executed
# in a release build (caught in the 2026-07-09 release smoke test). Keep both hierarchies.
-keep class * extends androidx.work.InputMerger {
    <init>();
}
-keep class * extends androidx.work.ListenableWorker {
    <init>(...);
}

# Logging policy: KEEP all android.util.Log calls in release builds.
# R8 only strips Log.* when an `-assumenosideeffects class android.util.Log { ... }`
# rule is present. We intentionally do NOT add one, so Log.d/Log.i survive minification
# and on-device diagnostics (e.g. DailyGraphRenderer / DailyViewLogic render decisions)
# remain available in release. Do not add an assumenosideeffects rule for android.util.Log.

# Hilt generated entry points, injectors, and components.
# Prevents R8 tree shaking from stripping _GeneratedInjector interfaces (e.g.
# WeatherWidgetProvider_GeneratedInjector) which causes NoClassDefFoundError crashes on startup.
-keep class * implements dagger.hilt.internal.GeneratedComponent { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponentManager { *; }
-keep class * implements dagger.hilt.internal.TestSingletonComponent { *; }
-keep interface * extends dagger.hilt.internal.GeneratedComponent { *; }
-keep interface **_GeneratedInjector { *; }
-keep class **_HiltComponents** { *; }
-keep class **.Hilt_* { *; }

