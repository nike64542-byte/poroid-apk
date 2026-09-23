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
}
