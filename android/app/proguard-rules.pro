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
-keepclassmembers class eu.ocnotes.data.** {
    *** Companion;
}
-keepclasseswithmembers class eu.ocnotes.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Le rapport de diagnostic imprime le fichier et la ligne de chaque cadre de
# pile. Ni R8 ni les fichiers de règles par défaut d'AGP ne conservent ces deux
# attributs : sans les lignes qui suivent, une trace de release affiche
# « Unknown Source » et `mapping.txt` ne restitue plus que les identifiants.
# `-renamesourcefileattribute` garde l'attribut sans exposer les noms d'origine.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
