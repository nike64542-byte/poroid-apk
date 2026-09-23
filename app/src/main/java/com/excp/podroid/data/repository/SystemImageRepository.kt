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
 * Manages the versioned VM-image downloads (kernel, initramfs, rootfs, QEMU).
 *
 * Nothing is bundled in the APK — the user pastes the four download URLs in
 * the first-run setup wizard and this repository streams them to
 * [context.filesDir] where the VM engines already look:
 *
 *   - kernel  -> filesDir/vmlinuz-virt
 *   - initram -> filesDir/initrd.img
 *   - rootfs  -> filesDir/kali-rootfs.squashfs
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
) {

    companion object {
        private val KEY_KERNEL_URL = stringPreferencesKey("system_image_kernel_url")
        private val KEY_INITRD_URL = stringPreferencesKey("system_image_initrd_url")
        private val KEY_ROOTFS_URL = stringPreferencesKey("system_image_rootfs_url")
        private val KEY_QEMU_URL   = stringPreferencesKey("system_image_qemu_url")
        private val KEY_DOWNLOADED = booleanPreferencesKey("system_image_downloaded")

        const val KERNEL_URL_DEFAULT   = "https://github.com/nike64542-byte/poroid-kernel/releases/latest/download/vmlinuz-virt"
        const val INITRD_URL_DEFAULT   = "https://github.com/nike64542-byte/poroid-rootfs/releases/latest/download/initrd.img"
        const val ROOTFS_URL_DEFAULT   = "https://github.com/nike64542-byte/poroid-rootfs/releases/latest/download/kali-rootfs.squashfs"
        const val QEMU_URL_DEFAULT     = "https://github.com/nike64542-byte/poroid-qemu/releases/latest/download/qemu-assets.tar.gz"
    }

    private val prefs = context.dataStore.data
        .catch { e -> if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }

    fun filesDir(): File = context.filesDir
    fun kernelFile(): File = File(context.filesDir, "vmlinuz-virt")
    fun initrdFile(): File = File(context.filesDir, "initrd.img")
    fun rootfsFile(): File = File(context.filesDir, "kali-rootfs.squashfs")
    fun qemuArchiveFile(): File = File(context.filesDir, "qemu-assets.tar.gz")

    suspend fun kernelUrl(): String = (prefs.first()[KEY_KERNEL_URL] ?: KERNEL_URL_DEFAULT)
    suspend fun initrdUrl(): String = (prefs.first()[KEY_INITRD_URL] ?: INITRD_URL_DEFAULT)
    suspend fun rootfsUrl(): String = (prefs.first()[KEY_ROOTFS_URL] ?: ROOTFS_URL_DEFAULT)
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
     * Downloads a URL to [dest] via a streaming HTTP GET. Atomic (tmp + rename)
     * so the engines never read a partial file. Returns bytes downloaded.
     */
    suspend fun download(url: String, dest: File): Long = withContext(Dispatchers.IO) {
        val tmp = File(dest.parentFile, dest.name + ".download")
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "Podroid")
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code for $url")
            }
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
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
     *   - qemu/keymaps/*           -> filesDir/qemu/keymaps/
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

    /** Downloads everything; returns per-file byte counts. */
    suspend fun downloadAll(): Map<String, Long> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, Long>()
        result["kernel"] = download(kernelUrl(), kernelFile())
        result["initrd"] = download(initrdUrl(), initrdFile())
        result["rootfs"] = download(rootfsUrl(), rootfsFile())
        result["qemu"] = download(qemuUrl(), qemuArchiveFile())
        unpackQemuArchive(qemuArchiveFile())
        markDownloaded(true)
        result
    }
}