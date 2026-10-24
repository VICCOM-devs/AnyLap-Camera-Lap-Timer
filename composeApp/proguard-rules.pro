-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.anylap.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.anylap.data.**$$serializer { *; }
