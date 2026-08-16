# --- PROTEZIONE TorP2PChat SUPER MASTER (Versione Corretta) ---

# Mantieni i nomi originali per la serializzazione GSON (Vitali per i nuovi modelli)
-keepclassmembers class com.p2p.supermaster.MasterCollaborator { *; }
-keep class com.p2p.supermaster.MasterCollaborator { *; }
-keepclassmembers class com.p2p.supermaster.RechargeEvent { *; }
-keep class com.p2p.supermaster.RechargeEvent { *; }
-keepclassmembers class com.p2p.supermaster.service.SuperBackupData { *; }
-keep class com.p2p.supermaster.service.SuperBackupData { *; }

# Crittografia
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
-keep class com.p2p.supermaster.crypto.** { *; }

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
