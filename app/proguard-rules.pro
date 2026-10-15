-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.nutritrack.**$$serializer { *; }
-keepclassmembers class com.example.nutritrack.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.nutritrack.** {
    kotlinx.serialization.KSerializer serializer(...);
}
