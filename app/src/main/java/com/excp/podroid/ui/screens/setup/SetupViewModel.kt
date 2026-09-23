package com.excp.podroid.ui.screens.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    private val _kernelUrl = MutableStateFlow<String?>(null)
    val kernelUrl: StateFlow<String?> = _kernelUrl.asStateFlow()
    private val _initrdUrl = MutableStateFlow<String?>(null)
    val initrdUrl: StateFlow<String?> = _initrdUrl.asStateFlow()
    private val _rootfsUrl = MutableStateFlow<String?>(null)
    val rootfsUrl: StateFlow<String?> = _rootfsUrl.asStateFlow()

    private val _downloaded = MutableStateFlow(false)
    val downloaded: StateFlow<Boolean> = _downloaded.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadUiState>(DownloadUiState.Idle)
    val downloadState: StateFlow<DownloadUiState> = _downloadState.asStateFlow()

    init {
        viewModelScope.launch {
            _kernelUrl.value = systemImageRepository.kernelUrl()
            _initrdUrl.value = systemImageRepository.initrdUrl()
            _rootfsUrl.value = systemImageRepository.rootfsUrl()
            _downloaded.value = systemImageRepository.isDownloaded()
        }
    }

    /** Persists URL edits immediately; clears the downloaded flag so a URL change re-downloads. */
    fun updateUrls(kernel: String, initrd: String, rootfs: String) {
        viewModelScope.launch {
            systemImageRepository.setUrls(kernel, initrd, rootfs)
            systemImageRepository.markDownloaded(false)
            _downloaded.value = false
        }
    }

    fun startDownload() {
        val kernel = _kernelUrl.value.orEmpty().trim()
        val initrd = _initrdUrl.value.orEmpty().trim()
        val rootfs = _rootfsUrl.value.orEmpty().trim()
        if (kernel.isEmpty() || initrd.isEmpty() || rootfs.isEmpty()) {
            _downloadState.value = DownloadUiState.Error("三个下载地址都需要填写")
            return
        }
        viewModelScope.launch {
            _downloadState.value = DownloadUiState.Downloading(0f)
            try {
                // First persist URLs so a crash still records what the user chose.
                systemImageRepository.setUrls(kernel, initrd, rootfs)
                val bytes = systemImageRepository.downloadAll()
                _downloaded.value = true
                _downloadState.value = DownloadUiState.Done(bytes)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Exception) {
                _downloadState.value = DownloadUiState.Error(e.message ?: "下载失败")
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
