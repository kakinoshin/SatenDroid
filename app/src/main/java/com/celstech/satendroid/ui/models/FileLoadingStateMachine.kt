package com.celstech.satendroid.ui.models

import android.net.Uri
import com.celstech.satendroid.utils.ZipImageEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * ファイル読み込み用のState Machine
 * delayによるタイミング調整を排除し、適切な状態遷移で制御
 */
class FileLoadingStateMachine {
    
    sealed class LoadingState {
        object Idle : LoadingState()
        object StoppingUI : LoadingState()
        object CleaningResources : LoadingState()
        object PreparingFile : LoadingState()
        data class Ready(val state: ImageViewerState) : LoadingState()
        data class Error(val message: String, val throwable: Throwable? = null) : LoadingState()
    }
    
    sealed class LoadingAction {
        data class StartLoading(val uri: Uri, val file: File?) : LoadingAction()
        object UIStoppedComplete : LoadingAction()
        object ResourcesClearedComplete : LoadingAction()
        data class FilePreparationComplete(val state: ImageViewerState?) : LoadingAction()
        data class FilePreparationFailed(val error: Throwable) : LoadingAction()
        object Reset : LoadingAction()
    }
    
    private val _currentState = MutableStateFlow<LoadingState>(LoadingState.Idle)
    val currentState: StateFlow<LoadingState> = _currentState.asStateFlow()
    
    private val _pendingRequest = MutableStateFlow<Pair<Uri, File?>?>(null)
    val pendingRequest: StateFlow<Pair<Uri, File?>?> = _pendingRequest.asStateFlow()
    
    /**
     * アクションを処理して状態遷移を実行
     */
    fun processAction(action: LoadingAction): Boolean {
        val currentState = _currentState.value
        
        return when (action) {
            is LoadingAction.StartLoading -> {
                when (currentState) {
                    is LoadingState.Idle -> {
                        _pendingRequest.value = action.uri to action.file
                        _currentState.value = LoadingState.StoppingUI
                        true
                    }
                    else -> {
                        // 他の処理中は新しいリクエストを拒否
                        false
                    }
                }
            }
            
            is LoadingAction.UIStoppedComplete -> {
                when (currentState) {
                    is LoadingState.StoppingUI -> {
                        _currentState.value = LoadingState.CleaningResources
                        true
                    }
                    else -> false
                }
            }
            
            is LoadingAction.ResourcesClearedComplete -> {
                when (currentState) {
                    is LoadingState.CleaningResources -> {
                        _currentState.value = LoadingState.PreparingFile
                        true
                    }
                    else -> false
                }
            }
            
            is LoadingAction.FilePreparationComplete -> {
                when (currentState) {
                    is LoadingState.PreparingFile -> {
                        if (action.state != null) {
                            _currentState.value = LoadingState.Ready(action.state)
                            _pendingRequest.value = null
                        } else {
                            _currentState.value = LoadingState.Error("Failed to prepare file")
                            _pendingRequest.value = null
                        }
                        true
                    }
                    else -> false
                }
            }
            
            is LoadingAction.FilePreparationFailed -> {
                when (currentState) {
                    is LoadingState.PreparingFile -> {
                        _currentState.value = LoadingState.Error(
                            action.error.message ?: "Unknown error",
                            action.error
                        )
                        _pendingRequest.value = null
                        true
                    }
                    else -> false
                }
            }
            
            is LoadingAction.Reset -> {
                _currentState.value = LoadingState.Idle
                _pendingRequest.value = null
                true
            }
        }
    }
    
    /**
     * 現在の状態が指定された状態かチェック
     */
    fun isStoppingUI(): Boolean = _currentState.value is LoadingState.StoppingUI
    fun isCleaningResources(): Boolean = _currentState.value is LoadingState.CleaningResources
    fun isPreparingFile(): Boolean = _currentState.value is LoadingState.PreparingFile
    
    /**
     * エラー状態かチェック
     */
    fun isError(): Boolean = _currentState.value is LoadingState.Error
    
    /**
     * ローディング中かチェック
     */
    fun isLoading(): Boolean {
        return when (_currentState.value) {
            is LoadingState.StoppingUI,
            is LoadingState.CleaningResources,
            is LoadingState.PreparingFile -> true
            else -> false
        }
    }
    
    /**
     * 準備完了状態かチェック
     */
    fun isReady(): Boolean = _currentState.value is LoadingState.Ready
    
    /**
     * 準備完了状態のデータを取得
     */
    fun getReadyState(): ImageViewerState? {
        return (_currentState.value as? LoadingState.Ready)?.state
    }
    
    /**
     * エラー情報を取得
     */
    fun getErrorInfo(): Pair<String, Throwable?>? {
        return (_currentState.value as? LoadingState.Error)?.let { error ->
            error.message to error.throwable
        }
    }
}

/**
 * ImageViewerStateのデータクラス（MainScreenから移動）
 */
data class ImageViewerState(
    val imageEntries: List<ZipImageEntry>,
    val currentZipUri: Uri,
    val currentZipFile: File?,
    val fileNavigationInfo: com.celstech.satendroid.navigation.FileNavigationManager.NavigationInfo?,
    val initialPage: Int = 0
) {
    val fileId: String
        get() = currentZipFile?.absolutePath ?: currentZipUri.toString()
}
