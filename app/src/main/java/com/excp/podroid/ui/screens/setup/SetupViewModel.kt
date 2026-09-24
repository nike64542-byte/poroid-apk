package com.excp.podroid.ui.screens.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.Distro
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.data.repository.SystemImageRepository
import com.excp.podroid.engine.VmEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val systemImageRepository: SystemImageRepository,
    private val engine: VmEngine,
) : ViewModel() {

    private val _setupComplete = MutableStateFlow(false)
    val setupComplete: StateFlow<Boolean> = _setupComplete.asStateFlow()

    private val _distro = MutableStateFlow(Distro.KALI)
    val distro: StateFlow<Distro> = _distro.asStateFlow()

    private val _downloaded = MutableStateFlow(false)
    val downloaded: StateFlow<Boolean> = _downloaded.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadUiState>(DownloadUiState.Idle)
    val downloadState: StateFlow<DownloadUiState> = _downloadState.asStateFlow()

    init {
        viewModelScope.launch {
            _distro.value = systemImageRepository.distro()
            _downloaded.value = systemImageRepository.isDownloaded()
        }
    }

    /**
     * First-run-only distro pick: persists the selection and clears the
     * downloaded flag. Runtime switching is not supported — Settings →
     * Reset VM wipes DataStore and re-runs this wizard.
     */
    fun selectDistro(distro: Distro) {
        _distro.value = distro
        _downloaded.value = false
        _downloadState.value = DownloadUiState.Idle
        viewModelScope.launch {
            systemImageRepository.setDistro(distro)
        }
    }

    fun startDownload() {
        viewModelScope.launch {
            val kernel = systemImageRepository.kernelUrl()
            val initrd = systemImageRepository.initrdUrl()
            val rootfs = systemImageRepository.rootfsUrl()
            val qemu = systemImageRepository.qemuUrl()
            if (kernel.isBlank() || initrd.isBlank() || rootfs.isBlank() || qemu.isBlank()) {
                _downloadState.value = DownloadUiState.Error("下载准备失败")
                return@launch
            }
            _downloadState.value = DownloadUiState.Downloading(0f)
            try {
                systemImageRepository.setUrls(kernel, initrd, rootfs, qemu)
                val bytes = systemImageRepository.downloadAll { progress ->
                    _downloadState.value = DownloadUiState.Downloading(progress)
                }
                _downloaded.value = true
                _downloadState.value = DownloadUiState.Done(bytes)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (_: Exception) {
                _downloadState.value = DownloadUiState.Error("下载失败")
            }
        }
    }

    /**
     * USB passthrough is QEMU-only (it rides the QMP control socket the AVF
     * backend lacks). Reading backendId here is cheap and side-effect-free.
     * During first-run the engine pick may still be settling on its QEMU seed,
     * so on an AVF device this can briefly read available; the choice is inert
     * on AVF anyway (no QMP, no qemu-xhci) and Settings shows the correct gate.
     */
    fun usbPassthroughAvailable(): Boolean = engine.backendId == "qemu"

    /**
     * Persists all setup choices in a single DataStore transaction so a process
     * kill mid-write can't leave the app in a half-completed setup state.
     */
    fun completeSetup(
        storageSizeGb: Int,
        vmRamMb: Int,
        vmCpus: Int,
        sshEnabled: Boolean,
        storageAccessEnabled: Boolean,
        usbPassthroughEnabled: Boolean,
        loadBalanceEnabled: Boolean,
        bandwidthMbps: Int,
    ) {
        viewModelScope.launch {
            settingsRepository.completeSetup(
                storageSizeGb = storageSizeGb,
                vmRamMb = vmRamMb,
                vmCpus = vmCpus,
                sshEnabled = sshEnabled,
                storageAccessEnabled = storageAccessEnabled,
                usbPassthroughEnabled = usbPassthroughEnabled,
                loadBalanceEnabled = loadBalanceEnabled,
                bandwidthMbps = bandwidthMbps,
            )
            _setupComplete.value = true
        }
    }
}

sealed interface DownloadUiState {
    data object Idle : DownloadUiState
    data class Downloading(val progress: Float) : DownloadUiState
    data class Done(val bytes: Map<String, Long>) : DownloadUiState
    data class Error(val message: String) : DownloadUiState
}
