# Il guardaroba è letto da JSON a runtime (nessuna reflection sulle classi): nessuna regola speciale.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Vosk passa da JNA (riflessione sulle strutture native): classi e campi vanno tenuti.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
