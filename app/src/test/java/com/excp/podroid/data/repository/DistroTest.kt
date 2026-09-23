package com.excp.podroid.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistroTest {

    @Test
    fun assetNamesMatchReleaseAssets() {
        assertEquals("kali-rootfs.squashfs", Distro.KALI.asset)
        assertEquals("debian-rootfs.squashfs", Distro.DEBIAN.asset)
        assertEquals("ubuntu-rootfs.squashfs", Distro.UBUNTU.asset)
    }

    @Test
    fun presetUrlsPointAtSharedLatestRelease() {
        for (d in Distro.values()) {
            val url = SystemImageRepository.presetUrl(d)
            assertEquals("${SystemImageRepository.ROOTFS_BASE_URL}/${d.asset}", url)
            assertTrue(url.endsWith(d.asset))
        }
    }

    @Test
    fun kaliPresetDoesNotAliasOtherDistros() {
        val kali = SystemImageRepository.presetUrl(Distro.KALI)
        assertTrue(!kali.contains("ubuntu") && !kali.contains("debian-rootfs"))
        assertEquals(kali, SystemImageRepository.ROOTFS_URL_DEFAULT)
    }

    @Test
    fun valueOfFallsBackOnUnknownStoredName() {
        // Mirrors SystemImageRepository.distro()'s runCatching guard: unknown
        // stored names must not crash and must resolve to the KALI default.
        val fallback = runCatching { Distro.valueOf("nonexistent") }
            .getOrDefault(Distro.KALI)
        assertEquals(Distro.KALI, fallback)
    }
}
