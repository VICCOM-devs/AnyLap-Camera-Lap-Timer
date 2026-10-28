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
# Every @Serializable class in the app (events, updates, …), not only data/ and cloud/.
-keepclassmembers @kotlinx.serialization.Serializable class app.anylap.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.anylap.**$$serializer { *; }
# Play In-App Updates + code scanner ship their own rules; keep the update entry points readable in crashes.
-keepattributes SourceFile,LineNumberTable
