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

# NOTE on kotlinx.serialization: no extra keep rules are needed here —
# kotlinx-serialization (1.6.3) ships consumer rules inside its artifact
# (META-INF/proguard/kotlinx-serialization.pro) which AGP merges
# automatically, covering the SDK's @Serializable models (gotrue
# UserInfo/Session, postgrest responses). Hand-writing backreference rules
# for them is redundant AND rejected by R8's parser in member position.
