package com.emerge.app.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.emerge.app.BuildConfig
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class UpdateStage { AVAILABLE, DOWNLOADING, READY, VERIFYING }
internal data class UpdateUiState(val release: UpdateRelease, val stage: UpdateStage = UpdateStage.AVAILABLE,
    val progress: Int? = null, val error: String? = null, val installPending: Boolean = false)

internal class UpdateViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val endpoint = BuildConfig.UPDATE_MANIFEST_URL.trim()
    private val repository = AppUpdateRepository(application)
    private val preferences by lazy { application.getSharedPreferences("app_updates", 0) }
    private val mutableUi = MutableStateFlow<UpdateUiState?>(null)
    val ui = mutableUi.asStateFlow()
    private var checkJob: Job? = null
    private var operation: Job? = null
    private var downloaded: File? = null
    private val mutableCheckStatus = MutableStateFlow<String?>(null)
    val checkStatus = mutableCheckStatus.asStateFlow()

    init {
        // Restore the offer and downloaded file across the system's install-permission screen.
        // A new build with an empty endpoint must remain disabled even with saved state.
        if (endpoint.isNotEmpty() && saved.get<String>("update_endpoint") == endpoint) runCatching {
            saved.get<Array<String>>("update_release")?.let { fields ->
                val release = UpdatePolicy.release(fields[0].toLong(), fields[1], fields[2], fields[3])
                if (release.versionCode > BuildConfig.VERSION_CODE) {
                    downloaded = saved.get<String>("update_file")?.takeIf { it.matches(Regex("update-[a-zA-Z0-9-]+\\.apk")) }
                        ?.let { File(application.cacheDir, "app-updates/$it") }?.takeIf { it.isFile }
                    mutableUi.value = UpdateUiState(release, if (downloaded != null) UpdateStage.READY else UpdateStage.AVAILABLE)
                }
            }
        }
    }

    fun checkOnForeground() {
        if (endpoint.isEmpty() || checkJob?.isActive == true || mutableUi.value != null) return
        val now = System.currentTimeMillis()
        val sameEndpoint = preferences.getString("endpoint", null) == endpoint
        if (sameEndpoint && !UpdatePolicy.checkDue(now, preferences.getLong("attempt", 0), preferences.getLong("success", 0))) return
        preferences.edit().putString("endpoint", endpoint).putLong("attempt", now).apply {
            if (!sameEndpoint) remove("success")
        }.apply()
        checkJob = viewModelScope.launch {
            try {
                val release = repository.check(endpoint)
                preferences.edit().putLong("success", System.currentTimeMillis()).apply()
                if (release != null) {
                    saved["update_endpoint"] = endpoint
                    saved["update_release"] = arrayOf(release.versionCode.toString(), release.versionName, release.apkUrl, release.sha256)
                    mutableUi.value = UpdateUiState(release)
                }
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                // Automatic checks never interrupt normal use on offline or invalid responses.
            }
        }
    }

    fun checkNow() {
        if (endpoint.isEmpty() || checkJob?.isActive == true || mutableUi.value != null) return
        mutableCheckStatus.value = "正在检查…"
        preferences.edit().putString("endpoint", endpoint).putLong("attempt", System.currentTimeMillis()).apply()
        checkJob = viewModelScope.launch {
            try {
                val release = repository.check(endpoint)
                preferences.edit().putLong("success", System.currentTimeMillis()).apply()
                if (release != null) {
                    saved["update_endpoint"] = endpoint
                    saved["update_release"] = arrayOf(release.versionCode.toString(), release.versionName, release.apkUrl, release.sha256)
                    mutableUi.value = UpdateUiState(release)
                    mutableCheckStatus.value = null
                } else {
                    mutableCheckStatus.value = "已是最新版本"
                }
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                mutableCheckStatus.value = "检查失败，请稍后重试"
            }
        }
    }

    fun download() {
        val current = mutableUi.value ?: return
        if (operation?.isActive == true || current.stage != UpdateStage.AVAILABLE) return
        mutableUi.value = current.copy(stage = UpdateStage.DOWNLOADING, error = null, progress = null)
        operation = viewModelScope.launch {
            try {
                val file = repository.download(current.release) { progress ->
                    mutableUi.update { state -> state?.takeIf { it.stage == UpdateStage.DOWNLOADING }?.copy(progress = progress) ?: state }
                }
                downloaded = file
                saved["update_file"] = file.name
                mutableUi.value = current.copy(stage = UpdateStage.READY, error = null)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                mutableUi.value = current.copy(error = (error as? UpdateException)?.message ?: "更新下载失败，请重试。")
            }
        }
    }

    fun requestInstall() {
        val current = mutableUi.value ?: return
        val file = downloaded ?: return
        if (operation?.isActive == true || current.stage != UpdateStage.READY) return
        mutableUi.value = current.copy(stage = UpdateStage.VERIFYING, error = null)
        operation = viewModelScope.launch {
            try {
                repository.verifyForInstall(file, current.release)
                mutableUi.value = current.copy(installPending = true, error = null)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                file.delete()
                downloaded = null
                saved.remove<String>("update_file")
                mutableUi.value = current.copy(stage = UpdateStage.AVAILABLE,
                    error = (error as? UpdateException)?.message ?: "更新包已失效，请重新下载。")
            }
        }
    }

    fun consumeInstall(): File? {
        val current = mutableUi.value ?: return null
        if (!current.installPending) return null
        mutableUi.value = current.copy(installPending = false)
        return downloaded
    }

    fun installError(message: String) { mutableUi.update { it?.copy(error = message, installPending = false) } }

    fun dismiss() {
        operation?.cancel()
        mutableUi.value = null
        saved.remove<String>("update_endpoint")
        saved.remove<Array<String>>("update_release")
        saved.remove<String>("update_file")
        // The installer may still be reading the file. Old files are cleaned on a later download.
        downloaded = null
    }
}
