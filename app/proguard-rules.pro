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

# kotlinx.serialization: keep the SDK's @Serializable models (gotrue
# UserInfo/Session, postgrest error responses) — their serializers are
# looked up reflectively via the serialization plugin's generated
# companion; the default rules cover user code but not library beans
# surfaced through generic decoders.
-keepclassmembers class io.github.jan.supabase.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.jan.supabase.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# R8 8 full mode: keep the generated serializers' fields for the SDK models
# (same rationale as above, stricter mode requires it).
-if @kotlinx.serialization.Serializable class io.github.jan.supabase.** {
    static **$Companion;
}
-keepclassmembers class io.github.jan.supabase.** {
    static <1>$Companion;
}
-if @kotlinx.serialization.Serializable class io.github.jan.supabase.** {
    public static ** INSTANCE;
}
-keepclassmembers class io.github.jan.supabase.** {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
