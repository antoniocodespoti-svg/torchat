# --- PROTEZIONE TorP2PChat MASTER (Versione Corretta) ---

# Mantieni i nomi originali per la serializzazione GSON
-keepclassmembers class com.p2p.tormaster.service.MasterBackupData { *; }
-keep class com.p2p.tormaster.service.MasterBackupData { *; }
-keepclassmembers class com.p2p.tormaster.MainActivity$MasterCollaborator { *; }
-keep class com.p2p.tormaster.MainActivity$MasterCollaborator { *; }

# Crittografia
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
-keep class com.p2p.tormaster.crypto.** { *; }

# GSON & Reflect
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep class com.google.gson.stream.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepnames class * extends com.google.gson.reflect.TypeToken

# MLKit & CameraX
-keep class com.google.mlkit.** { *; }
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
-dontwarn com.google.mlkit.**

# --- OFFUSCAMENTO ---
-overloadaggressively
-allowaccessmodification
-optimizationpasses 5

# --- RIMOZIONE LOG ---
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** i(...);
    public static *** v(...);
    public static *** w(...);
    public static *** e(...);
}

-dontwarn com.google.zxing.**
