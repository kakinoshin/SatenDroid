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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.celstech.satendroid.navigation.FileNavigationManager
import com.celstech.satendroid.ui.components.PerformanceAlert
import com.celstech.satendroid.ui.components.PerformanceMonitor
import com.celstech.satendroid.ui.components.PerformanceToggleButton
import com.celstech.satendroid.utils.DirectZipImageHandler
import com.celstech.satendroid.utils.ZipImageEntry
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * ZIP内画像を直接表示する画像ビューア画面
 * テンポラリファイルに展開せずに直接ZIP内の画像を表示する
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
    cacheManager: com.celstech.satendroid.cache.ImageCacheManager,
    directZipHandler: DirectZipImageHandler? = null,
    onPageChanged: ((currentPage: Int, totalPages: Int, zipFile: File) -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val reverseSwipeDirection by cacheManager.reverseSwipeDirection.collectAsState()
    val context = LocalContext.current

    // State for page jump slider
    var showPageSlider by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableStateOf(0f) }
    
    // パフォーマンス監視UI用の状態
    var showPerformanceMonitor by remember { mutableStateOf(false) }

    // System UI visibility control with Fire OS compatibility
    fun setSystemUIVisibility(visible: Boolean) {
        val activity = context as? Activity ?: return
        val window = activity.window

        try {
            if (visible) {
                // Show status bar and navigation bar
                // 新しいAPI
                val windowInsetsController =
                    WindowCompat.getInsetsController(window, window.decorView)
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())

                // 古いAPI（互換性のため）
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        )

                // Fire OS特有の対応
                window.statusBarColor = android.graphics.Color.BLACK
                window.navigationBarColor = android.graphics.Color.BLACK
            } else {
                // Hide status bar and navigation bar
                // 新しいAPI
                val windowInsetsController =
                    WindowCompat.getInsetsController(window, window.decorView)
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
                windowInsetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

                // 古いAPI（Fire OS互換性のため）
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        )

                // Fire OS特有の対応 - ステータスバーとナビゲーションバーを透明に
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT

                // フルスクリーンフラグを明示的に設定
                @Suppress("DEPRECATION")
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            }
        } catch (e: Exception) {
            // Fire OSで例外が発生した場合のフォールバック
            android.util.Log.w("ImageViewer", "Failed to set system UI visibility: ${e.message}")

            // 最低限の古いAPIでの対応
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

    // Initialize slider value when slider is first shown
    LaunchedEffect(showPageSlider) {
        if (showPageSlider) {
            sliderValue = pagerState.currentPage.toFloat()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Full-screen image pager
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { index -> imageEntries[index].id },
            userScrollEnabled = !showPageSlider, // Disable swipe when slider is shown
            reverseLayout = reverseSwipeDirection
        ) { index ->
            Box(modifier = Modifier.fillMaxSize()) {
                var isLoading by remember { mutableStateOf(true) }
                
                val painter = rememberAsyncImagePainter(
                    model = imageEntries[index],
                    onState = { state ->
                        isLoading = state is AsyncImagePainter.State.Loading
                    }
                )
                
                Image(
                    painter = painter,
                    contentDescription = "Image ${index + 1}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                
                // ローディングインジケーター
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.8f)),
                        contentAlignment = Alignment.Center
                    ) {
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
                        }
                    }
                }
            }
        }

        // Top clickable area for going back (only when slider is not shown)
        if (!showPageSlider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clickable { onBackToFiles() }
                    .align(Alignment.TopCenter)
            )
        }

        // Center clickable area for toggling top bar (only when slider is not shown)
        if (!showPageSlider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clickable { onToggleTopBar() }
                    .align(Alignment.Center)
            )
        }

        // Bottom clickable area for showing page slider (only when slider is not shown)
        if (!showPageSlider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clickable { showPageSlider = true }
                    .align(Alignment.BottomCenter)
            )
        }

        // File navigation buttons - Show when at first image and there's a previous file
        if (!showPageSlider && fileNavigationInfo != null && pagerState.currentPage == 0 && fileNavigationInfo.hasPreviousFile) {
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

        // File navigation buttons - Show when at last image and there's a next file
        if (!showPageSlider && fileNavigationInfo != null && pagerState.currentPage == imageEntries.size - 1 && fileNavigationInfo.hasNextFile) {
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


        // Full screen clickable overlay when slider is shown (to hide slider and jump to page)
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
        }

        // Top bar with image info
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
                        text = "Image ${pagerState.currentPage + 1} of ${imageEntries.size}",
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

                    // Show current zip file info
                    if (currentZipFile != null) {
                        Text(
                            text = "from: ${currentZipFile.name}",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "上部タップ：ファイル一覧に戻る　中央タップ：UI非表示",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Page jump slider
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
                    .clickable { /* Consume clicks to prevent hiding slider */ }
            ) {
                Column {
                    // Thumbnail and file info section
                    if (imageEntries.isNotEmpty()) {
                        val targetIndex = sliderValue.roundToInt().coerceIn(0, imageEntries.size - 1)
                        val targetEntry = imageEntries[targetIndex]

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Thumbnail
                            Box(
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            ) {
                                var thumbnailLoading by remember { mutableStateOf(true) }
                                
                                val thumbnailPainter = rememberAsyncImagePainter(
                                    model = targetEntry,
                                    onState = { state ->
                                        thumbnailLoading = state is AsyncImagePainter.State.Loading
                                    }
                                )
                                
                                Image(
                                    painter = thumbnailPainter,
                                    contentDescription = "Thumbnail",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                
                                if (thumbnailLoading) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.7f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            color = Color.White,
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            }

                            // File info
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "Page ${targetIndex + 1} of ${imageEntries.size}",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = targetEntry.fileName,
                                    color = Color.White.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

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
                        text = "スライダー以外の場所をタップして選択したページにジャンプ",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        
        // パフォーマンス監視UI
        if (directZipHandler != null) {
            // パフォーマンス監視パネル
            PerformanceMonitor(
                zipHandler = directZipHandler,
                isVisible = showPerformanceMonitor,
                modifier = Modifier.align(Alignment.TopStart)
            )
            
            // パフォーマンス警告
            PerformanceAlert(
                zipHandler = directZipHandler,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (showPerformanceMonitor) 200.dp else 0.dp)
            )
            
            // パフォーマンス監視トグルボタン（開発者用）
            if (!showPageSlider && !showTopBar) {
                PerformanceToggleButton(
                    isVisible = showPerformanceMonitor,
                    onToggle = { showPerformanceMonitor = it },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                )
            }
        }
    }
}
