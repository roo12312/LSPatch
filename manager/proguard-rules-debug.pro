-dontobfuscate
-keep class com.beust.jcommander.** { *; }
-keep class com.lspatch.android.Patcher$Options { *; }
-keep class com.lspatch.android.share.LSPConfig { *; }
-keep class com.lspatch.android.share.PatchConfig { *; }
-keepclassmembers class org.lsposed.patch.LSPatch {
    private <fields>;
}
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue
