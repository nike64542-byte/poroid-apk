package com.excp.podroid.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The guest distro selected in the first-run wizard. Exactly one rootfs is
 * downloaded per install; switching means Settings → Reset VM (wipe) and
 * running the wizard again — there is no runtime distro switch.
 */
enum class Distro(
    val asset: String,
    val versionedAsset: String,
    val packageUpdateCommand: String,
) {
    KALI("kali-rootfs.squashfs", "kali-rootfs-rolling.squashfs", "apt-get update"),
    DEBIAN("debian-rootfs.squashfs", "debian-rootfs-12.squashfs", "apt-get update"),
    UBUNTU("ubuntu-rootfs.squashfs", "ubuntu-rootfs-24.04.squashfs", "apt-get update"),
    FEDORA("fedora-rootfs.squashfs", "fedora-rootfs-42.squashfs", "dnf makecache --refresh"),
    ROCKY("rocky-rootfs.squashfs", "rocky-rootfs-9.squashfs", "dnf makecache --refresh"),
    ALMA("alma-rootfs.squashfs", "alma-rootfs-9.squashfs", "dnf makecache --refresh"),
    OPENSUSE("opensuse-rootfs.squashfs", "opensuse-rootfs-15.6.squashfs", "zypper --non-interactive refresh"),
    ARCH("arch-rootfs.squashfs", "arch-rootfs-rolling.squashfs", "pacman -Sy --noconfirm"),
    MANJARO("manjaro-rootfs.squashfs", "manjaro-rootfs-rolling.squashfs", "pacman -Sy --noconfirm"),
    GENTOO("gentoo-rootfs.squashfs", "gentoo-rootfs-rolling.squashfs", "emerge --sync --quiet"),
}

/**
 * Manages the versioned VM-image downloads (kernel, initramfs, rootfs, QEMU).
 *
 * Nothing is bundled in the APK — the first-run wizard picks a [Distro]
 * (seeding its preset rootfs URL on the shared poroid-rootfs release; all four
 * URLs stay user-editable) and this repository streams them to
 * [context.filesDir] where the VM engines already look:
 *
 *   - kernel  -> filesDir/vmlinuz-virt
 *   - initram -> filesDir/initrd.img
 *   - rootfs  -> filesDir/<distro asset>  (e.g. kali-rootfs.squashfs)
 *   - QEMU    -> filesDir/qemu-assets.tar.gz, then unpacked:
 *                  *.so           -> filesDir/  (libqemu-system-aarch64.so,
 *                                                libslirp.so,
 *                                                libpodroid-bridge.so,
 *                                                libpodroid-launcher.so)
 *                  qemu/efi*.rom  -> filesDir/qemu/efi-virtio.rom
 *                  qemu/keymaps/  -> filesDir/qemu/keymaps/
 *
 * QemuEngine reads .so from filesDir first (falls back to the APK jniLibs
 * copy), so the downloaded binaries win without an app reinstall.
 */
@Singleton
class SystemImageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    companion object {
        private val KEY_KERNEL_URL = stringPreferencesKey("system_image_kernel_url")
        private val KEY_INITRD_URL = stringPreferencesKey("system_image_initrd_url")
        private val KEY_ROOTFS_URL = stringPreferencesKey("system_image_rootfs_url")
        private val KEY_QEMU_URL   = stringPreferencesKey("system_image_qemu_url")
        private val KEY_DOWNLOADED = booleanPreferencesKey("system_image_downloaded")
        private val KEY_DISTRO     = stringPreferencesKey("system_image_distro")

        const val KERNEL_URL_DEFAULT   = "https://github.com/nike64542-byte/poroid-kernel/releases/download/latest/vmlinuz-virt"
        const val INITRD_URL_DEFAULT   = "https://github.com/nike64542-byte/poroid-rootfs/releases/download/latest/initrd.img"
        const val ROOTFS_BASE_URL      = "https://github.com/nike64542-byte/poroid-rootfs/releases/download/latest"
        const val ROOTFS_URL_DEFAULT   = "$ROOTFS_BASE_URL/kali-rootfs.squashfs"
        const val QEMU_URL_DEFAULT     = "https://github.com/nike64542-byte/poroid-qemu/releases/download/latest/qemu-assets.tar.gz"

        /** Preset rootfs URL for a distro on the shared poroid-rootfs release. */
        fun presetUrl(distro: Distro): String = "$ROOTFS_BASE_URL/${distro.asset}"
        fun versionedPresetUrl(distro: Distro): String = "$ROOTFS_BASE_URL/${distro.versionedAsset}"
    }

    private val prefs = context.dataStore.data
        .catch { e -> if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }

    fun filesDir(): File = context.filesDir
    fun kernelFile(): File = File(context.filesDir, "vmlinuz-virt")
    fun initrdFile(): File = File(context.filesDir, "initrd.img")
    suspend fun rootfsFile(): File = File(context.filesDir, distro().asset)
    fun qemuArchiveFile(): File = File(context.filesDir, "qemu-assets.tar.gz")

    /** Selected guest distro; defaults to [Distro.KALI] (or falls back on a bad stored value). */
    suspend fun distro(): Distro {
        val stored = prefs.first()[KEY_DISTRO] ?: return Distro.KALI
        return runCatching { Distro.valueOf(stored) }.getOrDefault(Distro.KALI)
    }

    /**
     * Selects the guest distro and repoints the rootfs URL at its preset on
     * the shared release. Only called from the first-run wizard (a runtime
     * switch is not supported — Settings → Reset VM wipes and re-runs it).
     * Clears the downloaded flag so the new rootfs must be fetched.
     */
    suspend fun setDistro(distro: Distro) {
        val previous = distro()
        context.dataStore.edit {
            it[KEY_DISTRO] = distro.name
            it[KEY_ROOTFS_URL] = presetUrl(distro)
            it[KEY_DOWNLOADED] = false
        }
        if (previous != distro) settingsRepository.clearFirstPackageUpdateDone(distro)
    }

    suspend fun kernelUrl(): String = (prefs.first()[KEY_KERNEL_URL] ?: KERNEL_URL_DEFAULT)
    suspend fun initrdUrl(): String = (prefs.first()[KEY_INITRD_URL] ?: INITRD_URL_DEFAULT)
    suspend fun rootfsUrl(): String {
        val p = prefs.first()
        return p[KEY_ROOTFS_URL] ?: presetUrl(distro())
    }
    suspend fun qemuUrl(): String = (prefs.first()[KEY_QEMU_URL] ?: QEMU_URL_DEFAULT)

    /** True once the user has successfully downloaded everything this install. */
    suspend fun isDownloaded(): Boolean = prefs.first()[KEY_DOWNLOADED] ?: false

    suspend fun setUrls(kernel: String, initrd: String, rootfs: String, qemu: String) {
        context.dataStore.edit {
            it[KEY_KERNEL_URL] = kernel.trim()
            it[KEY_INITRD_URL] = initrd.trim()
            it[KEY_ROOTFS_URL] = rootfs.trim()
            it[KEY_QEMU_URL] = qemu.trim()
        }
    }

    /** Marks download complete. Cleared whenever [setUrls] changes so a URL edit re-downloads. */
    suspend fun markDownloaded(done: Boolean) {
        context.dataStore.edit { it[KEY_DOWNLOADED] = done }
    }

    /**
     * Ensures [file] (a downloaded .so that QEMU/launcher/bridge exec as a
     * program) carries the exec bit. Downloads default to 0644; without this a
     * launch fails with error=13 Permission denied. Self-heals an already-downloaded
     * file so the user doesn't have to re-download to fix permissions.
     */
    fun ensureExecutable(file: File): File {
        if (file.exists() && !file.canExecute()) {
            com.excp.podroid.util.ExecPerms.makeExecutable(file)
        }
        return file
    }

    /**
     * Downloads a URL to [dest] via a streaming HTTP GET. Atomic (tmp + rename)
     * so the engines never read a partial file. Returns bytes downloaded.
     */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        // Users often paste URLs with a stray leading/trailing space or a
        // mid-string newline; strip ALL whitespace so "repo /releases/…"
        // still resolves. Also collapse any accidental paste of the tag page.
        val cleanUrl = url.filter { !it.isWhitespace() }
        val tmp = File(dest.parentFile, dest.name + ".download")
        var connection: HttpURLConnection? = null
        try {
            connection = URL(cleanUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "Podroid")
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code for $cleanUrl")
            }
            // Content-Length is present after GitHub's 302 → asset redirect.
            val expected = runCatching { connection.contentLengthLong }.getOrDefault(-1L)
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        if (expected > 0) onProgress(total, expected)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (!tmp.renameTo(dest)) {
                if (!dest.delete() || !tmp.renameTo(dest)) {
                    throw IOException("atomic rename to ${dest.name} failed")
                }
            }
            // The QEMU binaries (qemu/launcher/bridge) are executed as programs
            // by ProcessBuilder, not dlopen'd as libraries — downloaded files
            // default to 0644 (no +x), which fails with error=13. Force the
            // exec bit on any .so we fetched.
            if (dest.name.endsWith(".so")) {
                com.excp.podroid.util.ExecPerms.makeExecutable(dest)
            }
            total
        } finally {
            runCatching { tmp.delete() }
            connection?.disconnect()
        }
    }

    /**
     * Unpacks the QEMU asset tarball (tar.gz) the engines need:
     *   - the four *.so binaries   -> filesDir/
     *   - qemu/efi-virtio.rom      -> filesDir/qemu/efi-virtio.rom
     *   - qemu/keymaps dir         -> filesDir/qemu/keymaps/
     *
     * Java has no tar reader, so shell out to `tar` (present on Android).
     */
    suspend fun unpackQemuArchive(archive: File) {
        val proc = ProcessBuilder(
            "tar", "xzf", archive.absolutePath,
            "-C", context.filesDir.absolutePath,
        ).redirectErrorStream(true).start()
        proc.waitFor()
        if (proc.exitValue() != 0) {
            throw IOException("tar failed to unpack ${archive.name}")
        }
    }

    /** The four QEMU .so files that must exist for the VM to launch. */
    private val qemuBinaryNames = listOf(
        "libqemu-system-aarch64.so",
        "libslirp.so",
        "libpodroid-bridge.so",
        "libpodroid-launcher.so",
    )

    private fun qemuBinaryUrl(name: String): String =
        "https://github.com/nike64542-byte/poroid-qemu/releases/download/latest/$name"

    /**
     * Downloads the four QEMU binaries individually (they are NOT inside
     * qemu-assets.tar.gz — that archive only has efi-virtio.rom + keymaps).
     * onProgress reports progress across all four combined.
     */
    private suspend fun downloadQemuBinaries(onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): Long {
        // Approximate total (the main .so dominates; sizes from the Release).
        val sizes = mapOf(
            "libqemu-system-aarch64.so" to 128_600_000L,
            "libslirp.so" to 3_300_000L,
            "libpodroid-bridge.so" to 50_000L,
            "libpodroid-launcher.so" to 50_000L,
        )
        var grandTotal = sizes.values.sum()
        var done = 0L
        for (name in qemuBinaryNames) {
            download(qemuBinaryUrl(name), File(context.filesDir, name)) { d, t ->
                onProgress(done + d, grandTotal)
            }
            done += sizes[name] ?: 0L
        }
        return done
    }

    /** Downloads everything; returns per-file byte counts. [onProgress] reports 0..1 overall. */
    suspend fun downloadAll(onProgress: (Float) -> Unit = {}): Map<String, Long> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, Long>()
        // Weights per item; the big QEMU .so dominates the time.
        val weights = listOf(0.10f, 0.10f, 0.15f, 0.65f)

        suspend fun runStep(weightIndex: Int, block: suspend ((done: Long, total: Long) -> Unit) -> Long): Long {
            val startAcc = (0 until weightIndex).sumOf { weights[it].toDouble() }.toFloat()
            val bytes = block { done, total ->
                val frac = if (total > 0) (done.toFloat() / total) else 0f
                onProgress((startAcc + frac * weights[weightIndex]).coerceIn(0f, 1f))
            }
            return bytes
        }

        result["kernel"] = runStep(0) { p -> download(kernelUrl(), kernelFile(), p) }
        result["initrd"] = runStep(1) { p -> download(initrdUrl(), initrdFile(), p) }
        result["rootfs"] = runStep(2) { p -> download(rootfsUrl(), rootfsFile(), p) }
        result["qemu"] = runStep(3) { p -> downloadQemuBinaries(p) }

        // qemu-assets.tar.gz (efi rom + keymaps) is optional — engines don't
        // reference them. Download+unpack best-effort; never fails the flow.
        runCatching { download(qemuUrl(), qemuArchiveFile()) }.getOrNull()?.let {
            runCatching { unpackQemuArchive(qemuArchiveFile()) }
        }
        markDownloaded(true)
        result
    }
}