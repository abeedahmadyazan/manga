package com.yazan.manga.data

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest

/**
 * AntiTampering — verifies the app hasn't been re-packaged or modified.
 *
 * Two checks:
 *
 * 1. Signature verification: compares the APK's signing certificate against
 *    a known-good SHA-256 hash. If someone re-signs the APK with a
 *    different key (re-packaging attack), this check fails.
 *
 * 2. Emulator/root detection: best-effort heuristics to detect if the app
 *    is running on an emulator or a rooted device. We don't block these
 *    outright (legitimate users sometimes root), but we tag the device
 *    fingerprint so the server side can apply stricter rules.
 *
 * Usage: call AntiTampering.verifySignature() once at app startup. If it
 * fails, disable sync features (comments, lists) but keep the reader
 * working — don't punish users who installed a re-packaged APK from
 * a third party.
 */
object AntiTampering {

    private const val TAG = "AntiTampering"

    /**
     * The expected SHA-256 of the app's signing certificate, hex-encoded.
     *
     * This is the SHA-256 of our production release keystore (alias "manga").
     * If someone re-signs the APK with a different key (re-packaging attack),
     * this check fails and sync features get disabled.
     *
     * To compute the hash for a new keystore:
     *   keytool -list -v -keystore manga-release.keystore -alias manga \
     *     -storepass YOUR_PASS | grep SHA256
     */
    private const val EXPECTED_CERT_SHA256: String = "eaf8316349e3cb7afb9bb7cb563963ac12f5447dc096afae2982ba9fd7344a80"

    /**
     * Returns true if the app's signing certificate matches the expected hash
     * (or if EXPECTED_CERT_SHA256 is empty, in which case we accept any
     * signature — useful during development).
     */
    fun verifySignature(context: Context): Boolean {
        if (EXPECTED_CERT_SHA256.isBlank()) {
            // Development mode — accept anything
            return true
        }
        return try {
            val sigHash = getApkSignatureHash(context)
            val ok = sigHash.equals(EXPECTED_CERT_SHA256, ignoreCase = true)
            if (!ok) {
                Log.w(TAG, "Signature mismatch! Expected=$EXPECTED_CERT_SHA256 actual=$sigHash")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification failed: ${e.message}")
            false
        }
    }

    /** Compute SHA-256 hex hash of the APK's signing certificate. */
    private fun getApkSignatureHash(context: Context): String {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
        }

        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
                ?: throw Exception("No signing info")
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
            else signingInfo.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures ?: throw Exception("No signatures")
        }

        if (signatures.isEmpty()) throw Exception("Empty signatures array")
        val cert = signatures[0].toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(cert)
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ============================================================
    //  Emulator / root detection (heuristic, not blocking)
    // ============================================================

    /** Returns true if running on an emulator (best-effort heuristic). */
    fun isEmulator(): Boolean {
        val checks = listOf(
            Build.FINGERPRINT.startsWith("generic"),
            Build.FINGERPRINT.startsWith("unknown"),
            Build.MODEL.contains("google_sdk", ignoreCase = true),
            Build.MODEL.contains("Emulator", ignoreCase = true),
            Build.MODEL.contains("Android SDK built for x86", ignoreCase = true),
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true),
            Build.BRAND.startsWith("generic", ignoreCase = true),
            Build.DEVICE.startsWith("generic", ignoreCase = true),
            Build.PRODUCT.contains("sdk_google", ignoreCase = true),
            Build.HARDWARE.contains("goldfish", ignoreCase = true),
            Build.HARDWARE.contains("ranchu", ignoreCase = true)
        )
        return checks.count { it } >= 3
    }

    /**
     * Returns true if the device is likely rooted. We don't block, just flag.
     * (Rooted users can be legit; we just want to apply stricter rules.)
     */
    fun isLikelyRooted(): Boolean {
        val rootIndicators = listOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        return rootIndicators.any { java.io.File(it).exists() }
    }

    /**
     * Returns a stable device fingerprint that doesn't depend on hardware
     * identifiers (which require permission on newer Android). The fingerprint
     * is unique enough for rate-limiting purposes (1 user per device).
     */
    fun deviceFingerprint(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val emulatorTag = if (isEmulator()) "-emu" else ""
        val rootTag = if (isLikelyRooted()) "-root" else ""
        val combined = "$androidId$emulatorTag$rootTag"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(combined.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
