package com.excp.podroid.util

import android.util.Log
import java.io.File

/**
 * Forces the exec bit on a native binary (downloaded QEMU/bridge/launcher .so).
 *
 * `java.io.File.setExecutable()` is unreliable on Android — on some devices it
 * silently no-ops, leaving the file at 0644 so `ProcessBuilder` exec fails with
 * error=13 Permission denied. The toybox `chmod` binary at /system/bin/chmod
 * always works. We try the shell chmod first and verify with canExecute(),
 * logging the outcome so a stubborn device leaves a trace in logcat.
 */
object ExecPerms {
    private const val TAG = "ExecPerms"

    /**
     * Make [file] executable. Returns true if the file is executable after the
     * attempt (either it already was, or chmod succeeded).
     */
    fun makeExecutable(file: File): Boolean {
        if (!file.exists()) return false
        if (file.canExecute()) return true

        // 1) Preferred: real chmod binary (reliable on Android).
        runCatching {
            val p = ProcessBuilder("/system/bin/chmod", "755", file.absolutePath)
                .redirectErrorStream(true)
                .start()
            p.waitFor()
            Log.i(TAG, "chmod 755 ${file.absolutePath} -> exit ${p.exitValue()}")
        }.onFailure { Log.w(TAG, "shell chmod failed for ${file.absolutePath}", it) }

        // 2) Fallback: Java API (may no-op on some devices).
        if (!file.canExecute()) {
            runCatching { file.setExecutable(true, false) }
                .onFailure { Log.w(TAG, "File.setExecutable threw for ${file.absolutePath}", it) }
        }

        val ok = file.canExecute()
        Log.i(TAG, "${file.absolutePath} executable=$ok mode=${file.canRead()}/${file.canWrite()}/${ok}")
        return ok
    }
}