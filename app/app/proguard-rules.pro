# Minify is off for now. Rules below are kept ready for when it is enabled.
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class xyz.aresius.audiobooks.** { kotlinx.serialization.KSerializer serializer(...); }
