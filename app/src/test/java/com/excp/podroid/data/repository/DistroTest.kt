package com.excp.podroid.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistroTest {

    private val expected = mapOf(
        Distro.KALI to Triple("kali-rootfs.squashfs", "kali-rootfs-rolling.squashfs", "apt-get update"),
        Distro.DEBIAN to Triple("debian-rootfs.squashfs", "debian-rootfs-12.squashfs", "apt-get update"),
        Distro.UBUNTU to Triple("ubuntu-rootfs.squashfs", "ubuntu-rootfs-24.04.squashfs", "apt-get update"),
        Distro.FEDORA to Triple("fedora-rootfs.squashfs", "fedora-rootfs-42.squashfs", "dnf makecache --refresh"),
        Distro.ROCKY to Triple("rocky-rootfs.squashfs", "rocky-rootfs-9.squashfs", "dnf makecache --refresh"),
        Distro.ALMA to Triple("alma-rootfs.squashfs", "alma-rootfs-9.squashfs", "dnf makecache --refresh"),
        Distro.OPENSUSE to Triple("opensuse-rootfs.squashfs", "opensuse-rootfs-15.6.squashfs", "zypper --non-interactive refresh"),
        Distro.ARCH to Triple("arch-rootfs.squashfs", "arch-rootfs-rolling.squashfs", "pacman -Sy --noconfirm"),
        Distro.MANJARO to Triple("manjaro-rootfs.squashfs", "manjaro-rootfs-rolling.squashfs", "pacman -Sy --noconfirm"),
        Distro.GENTOO to Triple("gentoo-rootfs.squashfs", "gentoo-rootfs-rolling.squashfs", "emerge --sync --quiet"),
    )

    @Test
    fun everyDistroHasStableVersionedAssetAndUpdateCommand() {
        assertEquals(expected, Distro.values().associateWith { Triple(it.asset, it.versionedAsset, it.packageUpdateCommand) })
    }

    @Test
    fun versionedPresetUrlUsesTheSameReleaseBase() {
        for (distro in Distro.values()) {
            assertEquals(
                "${SystemImageRepository.ROOTFS_BASE_URL}/${distro.versionedAsset}",
                SystemImageRepository.versionedPresetUrl(distro),
            )
        }
    }

    @Test
    fun packageUpdateStateIsPerDistro() {
        assertEquals("first_package_update_done_manjaro", firstPackageUpdateKeyName(Distro.MANJARO))
        assertEquals("first_package_update_done_gentoo", firstPackageUpdateKeyName(Distro.GENTOO))
    }

    @Test
    fun legacyAptKeyOnlyMigratesKali() {
        assertTrue(legacyFirstUpdateApplies(Distro.KALI))
        assertFalse(legacyFirstUpdateApplies(Distro.FEDORA))
    }

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

    @Test
    fun allTenDistrosHaveReleaseAssets() {
        val expected = mapOf(
            Distro.KALI to "kali-rootfs.squashfs",
            Distro.DEBIAN to "debian-rootfs.squashfs",
            Distro.UBUNTU to "ubuntu-rootfs.squashfs",
            Distro.FEDORA to "fedora-rootfs.squashfs",
            Distro.ROCKY to "rocky-rootfs.squashfs",
            Distro.ALMA to "alma-rootfs.squashfs",
            Distro.OPENSUSE to "opensuse-rootfs.squashfs",
            Distro.ARCH to "arch-rootfs.squashfs",
            Distro.MANJARO to "manjaro-rootfs.squashfs",
            Distro.GENTOO to "gentoo-rootfs.squashfs",
        )
        assertEquals(expected, Distro.values().associateWith { it.asset })
    }
}
