# Le binding gomobile expose des classes appelées par JNI depuis le runtime Go.
# R8 ne voit pas ces références : il faut les conserver explicitement, sinon
# l'application plante au premier appel en release avec un NoClassDefFoundError.
-keep class go.** { *; }
-keep class mobile.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# kotlinx.serialization : les sérialiseurs générés sont référencés par
# réflexion via le companion object.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class eu.opennote.data.** {
    *** Companion;
}
-keepclasseswithmembers class eu.opennote.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Tink, la bibliothèque de chiffrement derrière EncryptedSharedPreferences,
# référence des annotations de compilation (errorprone, javax.annotation) qui
# ne sont pas embarquées à l'exécution. Elles n'ont aucun effet au runtime :
# il suffit de faire taire R8, sans quoi le build en release échoue.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
