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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the versioned system-image downloads (kernel, initramfs, rootfs).
 *
 * The VM engines read fixed paths in [context.filesDir] (`vmlinuz-virt`,
 * `initrd.img`, `kali-rootfs.squashfs`). [SystemImageRepository] downloads the
 * user-specified URLs there so no engine code changes; it simply replaces the
 * "bundled asset" source with a "downloaded from URL" source.
 *
 * The three download targets mapped to the filenames the engines expect:
 *   - kernel  -> filesDir/vmlinuz-virt
 *   - initram -> filesDir/initrd.img
 *   - rootfs  -> filesDir/kali-rootfs.squashfs
 */
@Singleton
class SystemImageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private val KEY_KERNEL_URL = stringPreferencesKey("system_image_kernel_url")
        private val KEY_INITRD_URL = stringPreferencesKey("system_image_initrd_url")
        private val KEY_ROOTFS_URL = stringPreferencesKey("system_image_rootfs_url")
        private val KEY_DOWNLOADED = booleanPreferencesKey("system_image_downloaded")

        const val KERNEL_URL_DEFAULT   = "https://github.com/nike64542-byte/poroid-kernel/releases/latest/download/vmlinuz-virt"
        const val INITRD_URL_DEFAULT   = "https://github.com/nike64542-byte/poroid-rootfs/releases/latest/download/initrd.img"
        const val ROOTFS_URL_DEFAULT   = "https://github.com/nike64542-byte/poroid-rootfs/releases/latest/download/kali-rootfs.squashfs"
    }

    private val prefs = context.dataStore.data
        .catch { e -> if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }

    fun filesDir(): File = context.filesDir
    fun kernelFile(): File = File(context.filesDir, "vmlinuz-virt")
    fun initrdFile(): File = File(context.filesDir, "initrd.img")
    fun rootfsFile(): File = File(context.filesDir, "kali-rootfs.squashfs")

    suspend fun kernelUrl(): String = (prefs.first()[KEY_KERNEL_URL] ?: KERNEL_URL_DEFAULT)
    suspend fun initrdUrl(): String = (prefs.first()[KEY_INITRD_URL] ?: INITRD_URL_DEFAULT)
    suspend fun rootfsUrl(): String = (prefs.first()[KEY_ROOTFS_URL] ?: ROOTFS_URL_DEFAULT)

    /** True once the user has successfully downloaded all three images this install. */
    suspend fun isDownloaded(): Boolean = prefs.first()[KEY_DOWNLOADED] ?: false

    suspend fun setUrls(kernel: String, initrd: String, rootfs: String) {
        context.dataStore.edit {
            it[KEY_KERNEL_URL] = kernel.trim()
            it[KEY_INITRD_URL] = initrd.trim()
            it[KEY_ROOTFS_URL] = rootfs.trim()
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
            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var total = 0L
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

    /** Downloads all three images; returns per-file byte counts. */
    suspend fun downloadAll(): Map<String, Long> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, Long>()
        result["kernel"] = download(kernelUrl(), kernelFile())
        result["initrd"] = download(initrdUrl(), initrdFile())
        result["rootfs"] = download(rootfsUrl(), rootfsFile())
        markDownloaded(true)
        result
    }
}