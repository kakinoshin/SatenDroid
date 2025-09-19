package com.celstech.satendroid.ui.screens

import android.app.Activity
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.celstech.satendroid.navigation.FileNavigationManager
import com.celstech.satendroid.utils.SimpleReadingDataManager
import com.celstech.satendroid.utils.DirectZipImageHandler
import com.celstech.satendroid.utils.ZipImageEntry
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * ZIP内画像を直接表示する画像ビューア画面 - 修正版
 * 確実に画像が表示されるよう簡略化
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DirectZipImageViewerScreen(
    imageEntries: List<ZipImageEntry>,
    currentZipFile: File?,
    pagerState: PagerState,
    showTopBar: Boolean,
    onToggleTopBar: () -> Unit,
    onBackToFiles: () -> Unit,
    onNavigateToPreviousFile: (() -> Unit)? = null,
    onNavigateToNextFile: (() -> Unit)? = null,
    fileNavigationInfo: FileNavigationManager.NavigationInfo? = null,
    readingDataManager: SimpleReadingDataManager,
    directZipHandler: DirectZipImageHandler? = null,
    onPageChanged: ((currentPage: Int, totalPages: Int, zipFile: File) -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val reverseSwipeDirection by readingDataManager.reverseSwipeDirection.collectAsState()
    val context = LocalContext.current

    // State for page jump slider
    var showPageSlider by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }

    // System UI visibility control (簡略化)
    fun setSystemUIVisibility(visible: Boolean) {
        val activity = context as? Activity ?: return
        val window = activity.window

        try {
            @Suppress("DEPRECATION")
            if (visible) {
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            } else {
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        )
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        } catch (e: Exception) {
            println("WARNING: System UI visibility control failed: ${e.message}")
        }
    }

    // Control system UI visibility based on showTopBar state
    LaunchedEffect(showTopBar) {
        setSystemUIVisibility(showTopBar)
    }

    // Hide system UI when component is first displayed
    LaunchedEffect(Unit) {
        setSystemUIVisibility(false)
    }

    // Restore system UI when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            setSystemUIVisibility(true)
        }
    }

    // Update slider value when page changes (only when slider is not shown)
    LaunchedEffect(pagerState.currentPage) {
        if (!showPageSlider) {
            sliderValue = pagerState.currentPage.toFloat()
        }
    }

    // 読書状態の更新: ページが変更されたときに通知
    LaunchedEffect(pagerState.currentPage, imageEntries.size, currentZipFile) {
        if (imageEntries.isNotEmpty() && currentZipFile != null) {
            onPageChanged?.invoke(pagerState.currentPage, imageEntries.size, currentZipFile)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Full-screen image pager（修正版）
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { index -> imageEntries[index].id },
            userScrollEnabled = !showPageSlider,
            reverseLayout = reverseSwipeDirection
        ) { index ->
            Box(modifier = Modifier.fillMaxSize()) {
                // 画像表示の状態管理
                var hasError by remember { mutableStateOf(false) }
                var errorMessage by remember { mutableStateOf("") }

                // 確実な画像表示（修正版）
                if (directZipHandler != null) {
                    SimpleImageDisplay(
                        imageEntry = imageEntries[index],
                        directZipHandler = directZipHandler,
                        onLoadingStateChange = { _, error, message ->
                            hasError = error
                            errorMessage = message
                        }
                    )
                } else {
                    // フォールバック：エラー表示
                    hasError = true
                    errorMessage = "DirectZipHandler が利用できません"
                }

                // エラー表示（簡略化）
                if (hasError) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Text(
                                text = "画像の読み込みに失敗しました",
                                color = Color.White,
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center
                            )
                            
                            Text(
                                text = imageEntries[index].fileName,
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                            
                            if (errorMessage.isNotEmpty()) {
                                Text(
                                    text = "エラー: $errorMessage",
                                    color = Color.Red.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )
                            }
                            
                            Button(
                                onClick = {
                                    // 再読み込みを試行
                                    hasError = false
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.2f),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("再試行")
                            }
                        }
                    }
                }
            }
        }

        // Clickable areas（簡略化）
        if (!showPageSlider) {
            // Top area - back to files
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clickable { onBackToFiles() }
                    .align(Alignment.TopCenter)
            )

            // Center area - toggle top bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clickable { onToggleTopBar() }
                    .align(Alignment.Center)
            )

            // Bottom area - show page slider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clickable { showPageSlider = true }
                    .align(Alignment.BottomCenter)
            )
        }

        // ナビゲーションボタン（簡略化）
        if (!showPageSlider && fileNavigationInfo != null) {
            // Previous file button
            if (pagerState.currentPage == 0 && fileNavigationInfo.hasPreviousFile) {
                Button(
                    onClick = { onNavigateToPreviousFile?.invoke() },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black.copy(alpha = 0.7f),
                        contentColor = Color.White
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.NavigateBefore,
                            contentDescription = "Previous file"
                        )
                        Text("前のファイル")
                    }
                }
            }

            // Next file button
            if (pagerState.currentPage == imageEntries.size - 1 && fileNavigationInfo.hasNextFile) {
                Button(
                    onClick = { onNavigateToNextFile?.invoke() },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black.copy(alpha = 0.7f),
                        contentColor = Color.White
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("次のファイル")
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                            contentDescription = "Next file"
                        )
                    }
                }
            }
        }

        // Page slider overlay（簡略化）
        if (showPageSlider) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        val targetPage = sliderValue.roundToInt().coerceIn(0, imageEntries.size - 1)
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(targetPage)
                            showPageSlider = false
                        }
                    }
            )

            // Slider UI
            AnimatedVisibility(
                visible = showPageSlider,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.9f))
                        .padding(16.dp)
                        .clickable { /* Consume clicks */ }
                ) {
                    Column {
                        // Current page info
                        if (imageEntries.isNotEmpty()) {
                            val targetIndex = sliderValue.roundToInt().coerceIn(0, imageEntries.size - 1)
                            val targetEntry = imageEntries[targetIndex]

                            Text(
                                text = "ページ ${targetIndex + 1} / ${imageEntries.size}",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                            
                            Text(
                                text = targetEntry.fileName,
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        // Slider
                        Slider(
                            value = sliderValue,
                            onValueChange = { newValue ->
                                sliderValue = newValue
                            },
                            valueRange = 0f..(imageEntries.size - 1).toFloat(),
                            steps = if (imageEntries.size > 2) imageEntries.size - 2 else 0,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "画面をタップして選択したページにジャンプ",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Top bar（簡略化）
        if (showTopBar && !showPageSlider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(16.dp)
                    .align(Alignment.TopCenter)
            ) {
                Column {
                    Text(
                        text = "画像 ${pagerState.currentPage + 1} / ${imageEntries.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (imageEntries.isNotEmpty() && pagerState.currentPage < imageEntries.size) {
                        Text(
                            text = imageEntries[pagerState.currentPage].fileName,
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (currentZipFile != null) {
                        Text(
                            text = "ファイル: ${currentZipFile.name}",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "上部タップ：戻る　中央タップ：UI非表示　下部タップ：ページ移動",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * シンプルな画像表示コンポーネント
 * DirectZipHandlerから直接ByteArrayを取得してAsyncImagePainterに渡す
 */
@Composable
private fun SimpleImageDisplay(
    imageEntry: ZipImageEntry,
    directZipHandler: DirectZipImageHandler,
    onLoadingStateChange: (isLoading: Boolean, hasError: Boolean, errorMessage: String) -> Unit
) {
    var imageData by remember(imageEntry.id) { mutableStateOf<ByteArray?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    // 画像データを取得
    LaunchedEffect(imageEntry.id) {
        isLoading = true
        hasError = false
        onLoadingStateChange(true, false, "")
        
        try {
            println("DEBUG: SimpleImageDisplay loading ${imageEntry.fileName}")
            val data = directZipHandler.getImageData(imageEntry)
            
            if (data != null && data.isNotEmpty()) {
                imageData = data
                isLoading = false
                hasError = false
                onLoadingStateChange(false, false, "")
                println("DEBUG: SimpleImageDisplay loaded ${data.size} bytes for ${imageEntry.fileName}")
            } else {
                isLoading = false
                hasError = true
                errorMessage = "画像データが空または取得できませんでした"
                onLoadingStateChange(false, true, errorMessage)
                println("ERROR: SimpleImageDisplay no data for ${imageEntry.fileName}")
            }
        } catch (e: Exception) {
            isLoading = false
            hasError = true
            errorMessage = e.message ?: "Unknown error"
            onLoadingStateChange(false, true, errorMessage)
            println("ERROR: SimpleImageDisplay exception for ${imageEntry.fileName}: $errorMessage")
            e.printStackTrace()
        }
    }

    // ローディングまたはエラーの場合
    if (isLoading || hasError) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "画像を読み込み中...",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = imageEntry.fileName,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    // 画像表示
    if (imageData != null && !hasError) {
        val painter = rememberAsyncImagePainter(
            model = imageData,
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Error -> {
                        hasError = true
                        errorMessage = state.result.throwable.message ?: "画像デコードエラー"
                        onLoadingStateChange(false, true, errorMessage)
                        println("ERROR: AsyncImagePainter decode error for ${imageEntry.fileName}: $errorMessage")
                    }
                    is AsyncImagePainter.State.Success -> {
                        println("DEBUG: AsyncImagePainter success for ${imageEntry.fileName}")
                    }
                    else -> {
                        // Other states
                    }
                }
            }
        )

        Image(
            painter = painter,
            contentDescription = "Image: ${imageEntry.fileName}",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}