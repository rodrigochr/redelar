# kotlinx.serialization: mantém os serializers gerados das classes @Serializable
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.redelar.bluestempo.**$$serializer { *; }
-keepclassmembers class com.redelar.bluestempo.** { *** Companion; }
-keepclasseswithmembers class com.redelar.bluestempo.** { kotlinx.serialization.KSerializer serializer(...); }
