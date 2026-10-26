-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.anylap.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.anylap.data.**$$serializer { *; }
-keepclassmembers class app.anylap.cloud.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.anylap.cloud.**$$serializer { *; }
# Ktor / supabase-kt reference optional JVM classes that don't exist on Android.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn io.ktor.server.**
