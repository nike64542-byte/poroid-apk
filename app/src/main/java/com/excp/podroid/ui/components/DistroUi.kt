package com.excp.podroid.ui.components

import androidx.annotation.StringRes
import com.excp.podroid.R
import com.excp.podroid.data.repository.Distro

/** Display-label resource for a guest distro (wizard chips, Settings → About). */
@StringRes
fun distroLabelRes(distro: Distro): Int = when (distro) {
    Distro.KALI -> R.string.distro_kali
    Distro.DEBIAN -> R.string.distro_debian
    Distro.UBUNTU -> R.string.distro_ubuntu
    Distro.FEDORA -> R.string.distro_fedora
    Distro.ROCKY -> R.string.distro_rocky
    Distro.ALMA -> R.string.distro_alma
    Distro.OPENSUSE -> R.string.distro_opensuse
    Distro.ARCH -> R.string.distro_arch
    Distro.MANJARO -> R.string.distro_manjaro
    Distro.GENTOO -> R.string.distro_gentoo
}
