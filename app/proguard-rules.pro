# =============================================================
#  Manga app ProGuard rules — hardened for anti-reverse-engineering
# =============================================================

# ---------- Kotlin ----------
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# ---------- Gson (used for cache serialization) ----------
# Only keep the FIELDS of the data classes ( Gson needs them for reflection).
# Methods get obfuscated so callers can't be reverse-engineered.
-keep class com.yazan.manga.data.MangaListItem { <fields>; }
-keep class com.yazan.manga.data.MangaDetails { <fields>; }
-keep class com.yazan.manga.data.MangaChapter { <fields>; }
-keep class com.yazan.manga.data.MangaSourceInfo { <fields>; }
-keep class com.yazan.manga.data.ChapterPage { <fields>; }
-keep class com.yazan.manga.data.DownloadManager$DownloadedChapter { <fields>; }

# Gson itself — needs to stay
-keep class com.google.gson.** { *; }

# ---------- OkHttp ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.internal.platform.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---------- Firebase ----------
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**

# ---------- Glide ----------
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }

# ---------- Material Components ----------
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ---------- Shimmer ----------
-keep class com.facebook.shimmer.** { *; }

# =============================================================
#  Anti-reverse-engineering hardening
# =============================================================

# ---------- Aggressive obfuscation ----------
# Use the dictionary-based obfuscation: replaces class/method/field names
# with meaningless characters like 'a', 'b', 'c' instead of 'a', 'b', 'c'
# followed by incrementing numbers. Harder to read in decompilers.
-obfuscationdictionary ../proguard-dictionary.txt
-classobfuscationdictionary ../proguard-dictionary.txt
-packageobfuscationdictionary ../proguard-dictionary.txt

# ---------- String encryption ----------
# R8 doesn't natively encrypt strings, but we can flatten them and use
# resource lookups instead. For now, we rely on the fact that obfuscated
# class names make it harder to find string usages.

# ---------- Strip logging in release ----------
# Strip ALL log levels — even warnings and errors. In production, logging
# is a liability (it leaks API URLs, internal flow, error messages).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static java.lang.String getStackTraceString(...);
}

# ---------- Remove debug info ----------
# Strip line numbers from stack traces — makes crash logs useless to
# attackers trying to understand the code flow.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile
# Actually, drop SourceFile too:
#-keepattributes !SourceFile

# ---------- Optimize aggressively ----------
# Multiple optimization passes catch more dead code.
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''
-overloadaggressively

# ---------- Merge interfaces ----------
# Where possible, merge interfaces into concrete classes. Reduces the
# number of types an attacker has to map.
-mergeinterfacesaggressively

# ---------- Unboxing enums ----------
# Convert enum fields to int constants. Harder to read in decompiler.
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# =============================================================
#  API Client + In-App Update (Vercel API integration)
# =============================================================

# Keep ApiClient entirely — it uses reflection and callbacks
-keep class com.yazan.manga.data.ApiClient { *; }

# Keep InAppUpdateManager
-keep class com.yazan.manga.data.InAppUpdateManager { *; }

# Keep CloudCommentsManager (rewritten to use ApiClient)
-keep class com.yazan.manga.data.CloudCommentsManager { *; }
-keep class com.yazan.manga.data.CloudCommentsManager$Comment { *; }
-keep class com.yazan.manga.data.CloudCommentsManager$Report { *; }

# Keep AuthManager CloudUser (used by ApiClient)
-keep class com.yazan.manga.data.AuthManager$CloudUser { *; }
-keep class com.yazan.manga.data.AuthManager$User { *; }

# Keep ReadingHistoryManager HistoryEntry (used by ApiClient)
-keep class com.yazan.manga.data.ReadingHistoryManager$HistoryEntry { *; }

# Keep all data classes used by the API
-keep class com.yazan.manga.data.** { <fields>; }
