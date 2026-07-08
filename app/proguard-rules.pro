# =============================================================
#  Manga app ProGuard rules
# =============================================================

# ---------- Kotlin ----------
-keepattributes *Annotation*, InnerClasses
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# ---------- Gson (used for cache serialization) ----------
# Gson uses reflection on the data classes, so we need to keep them.
-keep class com.yazan.manga.data.MangaListItem { *; }
-keep class com.yazan.manga.data.MangaDetails { *; }
-keep class com.yazan.manga.data.MangaChapter { *; }
-keep class com.yazan.manga.data.MangaSourceInfo { *; }
-keep class com.yazan.manga.data.ChapterPage { *; }
-keep class com.yazan.manga.data.DownloadManager$DownloadedChapter { *; }
-keep class com.yazan.manga.data.CacheManager { *; }

# Gson itself
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes EnclosingMethod

# ---------- OkHttp ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ---------- Firebase ----------
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**

# ---------- Glide ----------
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder { *** rewind(); }

# ---------- Material Components ----------
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ---------- Shimmer ----------
-keep class com.facebook.shimmer.** { *; }

# ---------- App's own data classes (safety net for reflection) ----------
-keep class com.yazan.manga.data.** { <fields>; }

# ---------- Strip logging in release ----------
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    # Keep warnings and errors so we can debug production issues
    # public static int w(...);
    # public static int e(...);
}
