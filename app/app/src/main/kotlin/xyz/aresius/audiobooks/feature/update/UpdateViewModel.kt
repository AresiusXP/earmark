package xyz.aresius.audiobooks.feature.update

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import xyz.aresius.audiobooks.core.update.UpdateManager
import xyz.aresius.audiobooks.core.update.UpdateState

/** The banner and the Settings section both observe the app-scoped [UpdateManager]. */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val manager: UpdateManager,
) : ViewModel() {
    val state: StateFlow<UpdateState> = manager.state

    fun checkNow() = manager.checkNow()
    fun dismiss() = manager.dismiss()
    fun download() = manager.download(thenInstall = true)
    fun cancelDownload() = manager.cancelDownload()
    fun install() = manager.install()
    fun unknownSourcesSettingsOpened() = manager.unknownSourcesSettingsOpened()
    fun cancelInstallPermissionPrompt() = manager.cancelInstallPermissionPrompt()
    fun onResumed() = manager.onResumed()
}
