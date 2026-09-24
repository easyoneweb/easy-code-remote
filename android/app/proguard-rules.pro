# R8 / ProGuard keep rules for the release build.
# kotlinx.serialization: keep generated serializers (annotated classes).
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.easycoderemote.**$$serializer { *; }
-keepclassmembers class com.easycoderemote.** {
    *** Companion;
}
-keepclasseswithmembers class com.easycoderemote.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio (consumed by OkHttp's own consumer rules; keep for safety).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Retrofit is not used; Room is kept by its own rules when enabled via KSP.
-keep class * extends androidx.room.RoomDatabase { *; }

# kotlinx.coroutines debug agents / reflection
-dontwarn kotlinx.coroutines.debug.**