# --- PROTEZIONE TorP2PChat (Versione Corretta per GSON) ---

# Mantieni i nomi originali delle classi e dei campi nei modelli di dati
# Questo è VITALI per far sì che i backup e i salvataggi JSON funzionino correttamente
-keepclassmembers class com.p2p.torchat.model.** { *; }
-keep class com.p2p.torchat.model.** { *; }

# Proteggi i dati di backup e i payload di rete
-keepclassmembers class com.p2p.torchat.service.AppBackupData { *; }
-keep class com.p2p.torchat.service.AppBackupData { *; }
-keepclassmembers class com.p2p.torchat.service.NetworkPayload { *; }
-keep class com.p2p.torchat.service.NetworkPayload { *; }
-keepclassmembers class com.p2p.torchat.service.PayloadType { *; }
-keep class com.p2p.torchat.service.PayloadType { *; }
-keepclassmembers class com.p2p.torchat.service.AttachmentMetadata { *; }
-keep class com.p2p.torchat.service.AttachmentMetadata { *; }

# Mantieni gli algoritmi crittografici
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
-keep class com.p2p.torchat.crypto.** { *; }

# Regole specifiche per GSON (Indispensabili per TypeToken)
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.stream.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepnames class * extends com.google.gson.reflect.TypeToken

# MLKit & CameraX (Prevenzione crash in release)
-keep class com.google.mlkit.** { *; }
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
-dontwarn com.google.mlkit.**

# --- OFFUSCAMENTO BILANCIATO ---
# Rimosso '-repackageclasses' per evitare conflitti di pacchetti
-overloadaggressively
-allowaccessmodification
-optimizationpasses 5

# --- RIMOZIONE LOG ---
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# Manutenzione generale
-dontwarn com.google.zxing.**
-dontwarn io.coil.**
-dontwarn okio.**
