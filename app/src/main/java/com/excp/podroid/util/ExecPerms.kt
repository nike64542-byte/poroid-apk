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

    /** Octal mode like `0100644` (type bits + rwx), or `?` when stat fails. */
    fun modeString(file: File): String =
        runCatching { "0" + Integer.toOctalString(android.system.Os.stat(file.absolutePath).st_mode) }
            .getOrDefault("?")

    /** SELinux label of the file, or `?` when unavailable. */
    fun selinuxContext(file: File): String =
        runCatching {
            String(
                android.system.Os.getxattr(file.absolutePath, "security.selinux"),
                Charsets.UTF_8
            ).trim { it.code <= 32 }
        }.getOrDefault("?")

    /**
     * One-line file state for diagnostics: mode / exec / size / selinux label.
     * This is the evidence that distinguishes "0644 chmod never ran" from
     * "0755 but SELinux still denies exec".
     */
    fun describe(file: File): String =
        if (!file.exists()) "${file.name}: (missing)"
        else "${file.name}: mode=${modeString(file)} exec=${file.canExecute()} " +
            "size=${file.length()} selinux=${selinuxContext(file)}"

    /**
     * Make [file] executable. Returns true if the file is executable after the
     * attempt (either it already was, or chmod succeeded). Always logs — the
     * early-return branch included — so the ExecPerms tag in an exported log
     * proves this code ran.
     */
    fun makeExecutable(file: File): Boolean {
        if (!file.exists()) {
            Log.w(TAG, "makeExecutable: ${file.absolutePath} missing")
            return false
        }
        if (file.canExecute()) {
            Log.i(TAG, "already executable: ${describe(file)}")
            return true
        }

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
        if (ok) {
            Log.i(TAG, "chmod ok: ${describe(file)}")
        } else {
            Log.e(TAG, "chmod FAILED (will likely hit error=13): ${describe(file)}")
        }
        return ok
    }
}