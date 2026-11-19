# CleanHome: eigene Klassen vollstaendig behalten (Manifest-Einstiege, Lambdas, JSON),
# R8 optimiert sie trotzdem und entfernt Ungenutztes aus Bibliotheken (zxing).
-keep class de.cleanhome.launcher.** { *; }
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
