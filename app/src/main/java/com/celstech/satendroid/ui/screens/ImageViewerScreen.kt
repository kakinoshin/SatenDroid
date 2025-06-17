package com.celstech.satendroid.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * 画像表示画面 - 抽出された画像を表示する専用画面
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewerScreen(
    imageFiles: List<File>,
    pagerState: PagerState,
    showTopBar: Boolean,
    onToggleTopBar: () -> Unit,
    onBackToFiles: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    
    // State for page jump slider
    var showPageSlider by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    
    // Update slider value when page changes (only when slider is not shown)
    LaunchedEffect(pagerState.currentPage) {
        if (!showPageSlider) {
            sliderValue = pagerState.currentPage.toFloat()
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
            key = { index -> imageFiles[index].absolutePath },
            userScrollEnabled = !showPageSlider // Disable swipe when slider is shown
        ) { index ->
            Image(
                painter = rememberAsyncImagePainter(model = imageFiles[index]),
                contentDescription = "Image ${index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
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

        // Full screen clickable overlay when slider is shown (to hide slider and jump to page)
        if (showPageSlider) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        val targetPage = sliderValue.roundToInt().coerceIn(0, imageFiles.size - 1)
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
                        text = "Image ${pagerState.currentPage + 1} of ${imageFiles.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (imageFiles.isNotEmpty() && pagerState.currentPage < imageFiles.size) {
                        Text(
                            text = imageFiles[pagerState.currentPage].name,
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Tap top area to go back to file list",
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
                    if (imageFiles.isNotEmpty()) {
                        val targetIndex = sliderValue.roundToInt().coerceIn(0, imageFiles.size - 1)
                        val targetFile = imageFiles[targetIndex]
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Thumbnail
                            Image(
                                painter = rememberAsyncImagePainter(model = targetFile),
                                contentDescription = "Thumbnail",
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            
                            // File info
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "Page ${targetIndex + 1} of ${imageFiles.size}",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = targetFile.name,
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
                        valueRange = 0f..(imageFiles.size - 1).toFloat(),
                        steps = if (imageFiles.size > 2) imageFiles.size - 2 else 0,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Tap outside slider area to jump to selected page",
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
