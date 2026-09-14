# Add project-specific ProGuard rules here.
-keep class org.maplibre.** { *; }

# ── supabase-kt (2.2.2) / ktor 2.3.9 ────────────────────────────────────────
# ktor-client's JvmLogger optionally binds to slf4j at runtime. The slf4j
# *impl* classes (StaticLoggerBinder etc.) are compile-only artifacts that no
# Android build ships — R8 3.x+ hard-fails on them unless told they are
# legitimately absent. ktor falls back to its Android/console logger when the
# binder is missing, so a plain -dontwarn is the documented, behavior-safe
# fix (ktor docs: "add slf4j or dontwarn in R8/ProGuard").
-dontwarn org.slf4j.impl.**
-dontwarn org.slf4j.**

# ── kotlinx.serialization (canonical R8 rules from the serialization README;
#    AGP's defaults cover app code but library beans decoded reflectively —
#    gotrue UserInfo/Session, postgrest error responses — need them here).
#    NOTE: the <1> backreferences from the -if conditions belong in the CLASS
#    position of the following keep rules, not in member names.

# Keep the generated serializer() of @Serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion;
}

# Keep `Companion` of @Serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion;
}
-keepclassmembers class <1> {
    static <1>$Companion;
}

# Keep serializable primitive fields of @Serializable classes.
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    <fields>;
}

# Keep serializer() of @Serializable objects (object declarations).
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
