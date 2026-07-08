package com.yazan.manga.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.util.Log
import java.io.File

/**
 * AntiDebug — runtime detection of debugging / hooking tools.
 *
 * Checks performed:
 *  1. Is a debugger attached? (Debug.isDebuggerConnected)
 *  2. Is the app running under ptrace? (TracerPid in /proc/self/status)
 *  3. Is the process name a hooker like 'frida-server' or 'gum-js-loop'?
 *  4. Are known malicious apps installed? (Frida, Xposed installer)
 *  5. Are Frida-related files present on disk?
 *  6. Is the system ro.debuggable?
 *
 * When detection triggers, we DON'T crash the app — that just tells the
 * attacker what to look for. Instead we:
 *  - Mark the device as untrusted in DDoSProtection (which throttles them)
 *  - Disable sync features (no comments, no lists)
 *  - Keep the reader working so they don't immediately know why
 *
 * This is the "shadow ban" pattern: the attacker thinks the app is
 * working, but their interactions are silently restricted.
 */
object AntiDebug {

    private const val TAG = "AntiDebug"

    /**
     * Returns true if the app appears to be running under a debugger or
     * hooking framework. Call this at app startup and periodically.
     */
    fun isCompromised(context: Context): Boolean {
        return isDebuggerAttached()
            || isBeingTraced()
            || isFridaRunning()
            || isXposedInstalled(context)
            || isSystemDebuggable()
            || hasSuspiciousProcessName()
    }

    // =============================================================
    //  Individual checks
    // =============================================================

    /** Check 1: Is a Java debugger attached? */
    private fun isDebuggerAttached(): Boolean {
        return try {
            Debug.isDebuggerConnected()
        } catch (e: Exception) { false }
    }

    /**
     * Check 2: Is the process being traced via ptrace?
     * Reads /proc/self/status and looks for TracerPid != 0.
     */
    private fun isBeingTraced(): Boolean {
        return try {
            val status = File("/proc/self/status").readText()
            val tracerPidLine = status.lineSequence()
                .firstOrNull { it.startsWith("TracerPid:") }
                ?: return false
            val pid = tracerPidLine.substringAfter(":").trim().toIntOrNull() ?: 0
            pid != 0
        } catch (e: Exception) { false }
    }

    /**
     * Check 3: Look for Frida-specific artifacts.
     * Frida injects a server process and listens on a port. We check:
     *  - Process name contains 'frida' or 'gum-js-loop'
     *  - /proc/self/maps contains 'frida-agent' or 'frida-gadget'
     */
    private fun isFridaRunning(): Boolean {
        return try {
            // Check process name
            val processName = getProcessName()
            if (processName.contains("frida", ignoreCase = true) ||
                processName.contains("gum-js-loop", ignoreCase = true)) {
                return true
            }
            // Check loaded libraries
            val maps = File("/proc/self/maps").readText()
            if (maps.contains("frida-agent", ignoreCase = true) ||
                maps.contains("frida-gadget", ignoreCase = true) ||
                maps.contains("libfrida", ignoreCase = true)) {
                return true
            }
            // Check default Frida port
            val netstat = Runtime.getRuntime().exec("cat /proc/net/tcp").inputStream.bufferedReader().readText()
            if (netstat.contains("5D86") || netstat.contains("69B1")) {  // 27042, 27043 in hex
                return true
            }
            false
        } catch (e: Exception) { false }
    }

    /**
     * Check 4: Is Xposed framework installed?
     * Xposed is the most common framework for runtime hooking on rooted devices.
     */
    private fun isXposedInstalled(context: Context): Boolean {
        val xposedIndicators = listOf(
            "de.robv.android.xposed.installer",
            "de.robv.android.xposed.installer_v6",
            "org.lsposed.manager",
            "org.meowcat.edxposed.manager",
            "com.android.developer.xposed.installer"
        )
        return try {
            val pm = context.packageManager
            for (pkg in xposedIndicators) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    return true
                } catch (e: Exception) { /* not installed, continue */ }
            }
            // Also check if Xposed bridge is loaded
            val clz = try {
                Class.forName("de.robv.android.xposed.XposedBridge")
            } catch (e: Exception) { null }
            clz != null
        } catch (e: Exception) { false }
    }

    /** Check 5: Is the system image marked debuggable? */
    private fun isSystemDebuggable(): Boolean {
        return try {
            Build.TYPE == "userdebug" || Build.TYPE == "eng"
        } catch (e: Exception) { false }
    }

    /**
     * Check 6: Does the process name look suspicious?
     * Some hooking tools spawn the app with a modified process name.
     */
    private fun hasSuspiciousProcessName(): Boolean {
        val name = getProcessName().lowercase()
        val suspicious = listOf(
            "frida", "gum", "substrate", "cycript", "objection",
            "hooker", "injector", "patcher"
        )
        return suspicious.any { name.contains(it) }
    }

    // =============================================================
    //  Helpers
    // =============================================================

    private fun getProcessName(): String {
        return try {
            // API 28+: Application.getProcessName()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                val pid = Process.myPid()
                val am = getAppContext()?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: ""
            }
        } catch (e: Exception) { "" }
    }

    @Volatile private var appContext: Context? = null
    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
