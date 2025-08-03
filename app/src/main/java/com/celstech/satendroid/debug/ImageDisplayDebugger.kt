package com.celstech.satendroid.debug

import android.content.Context
import android.net.Uri
import com.celstech.satendroid.utils.DirectZipImageHandler
import com.celstech.satendroid.utils.ZipImageEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 画像表示問題のデバッグ用クラス
 */
class ImageDisplayDebugger(private val context: Context) {
    
    private val directZipHandler = DirectZipImageHandler(context)
    
    /**
     * ZIPファイルから画像エントリを取得してデバッグ情報を出力
     */
    suspend fun debugZipImageEntries(zipUri: Uri, zipFile: File? = null): List<ZipImageEntry> {
        return withContext(Dispatchers.IO) {
            try {
                println("=== ImageDisplayDebugger START ===")
                println("DEBUG: ZIP URI: $zipUri")
                println("DEBUG: ZIP File: ${zipFile?.absolutePath}")
                println("DEBUG: ZIP File exists: ${zipFile?.exists()}")
                println("DEBUG: ZIP File size: ${zipFile?.length()} bytes")
                
                val imageEntries = directZipHandler.getImageEntriesFromZip(zipUri, zipFile)
                
                println("DEBUG: Found ${imageEntries.size} image entries")
                
                imageEntries.forEachIndexed { index, entry ->
                    println("DEBUG: Entry $index:")
                    println("  - ID: ${entry.id}")
                    println("  - Entry Name: ${entry.entryName}")
                    println("  - File Name: ${entry.fileName}")
                    println("  - Size: ${entry.size} bytes")
                    println("  - Index: ${entry.index}")
                }
                
                // 最初の画像を実際に読み込んでテスト
                if (imageEntries.isNotEmpty()) {
                    val firstEntry = imageEntries[0]
                    println("DEBUG: Testing first image: ${firstEntry.fileName}")
                    
                    try {
                        val imageData = directZipHandler.getImageData(firstEntry)
                        
                        if (imageData != null) {
                            println("DEBUG: Successfully loaded first image: ${imageData.size} bytes")
                            
                            // ファイルヘッダーをチェック
                            if (imageData.size >= 10) {
                                val header = imageData.take(10).joinToString(" ") { "0x%02x".format(it) }
                                println("DEBUG: Image header: $header")
                                
                                // 画像形式の推定
                                val format = when {
                                    imageData.size >= 2 && imageData[0] == 0xFF.toByte() && imageData[1] == 0xD8.toByte() -> "JPEG"
                                    imageData.size >= 8 && imageData[1] == 'P'.code.toByte() && imageData[2] == 'N'.code.toByte() && imageData[3] == 'G'.code.toByte() -> "PNG"
                                    imageData.size >= 6 && imageData[0] == 'G'.code.toByte() && imageData[1] == 'I'.code.toByte() && imageData[2] == 'F'.code.toByte() -> "GIF"
                                    else -> "Unknown"
                                }
                                println("DEBUG: Detected image format: $format")
                            }
                        } else {
                            println("ERROR: Failed to load first image - imageData is null")
                        }
                    } catch (e: Exception) {
                        println("ERROR: Exception while loading first image: ${e.message}")
                        e.printStackTrace()
                    }
                }
                
                println("=== ImageDisplayDebugger END ===")
                return@withContext imageEntries
                
            } catch (e: Exception) {
                println("ERROR: ImageDisplayDebugger failed: ${e.message}")
                e.printStackTrace()
                return@withContext emptyList()
            }
        }
    }
    
    /**
     * 特定の画像エントリをテスト
     */
    suspend fun debugSpecificImage(imageEntry: ZipImageEntry): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                println("=== Debugging specific image: ${imageEntry.fileName} ===")
                
                val imageData = directZipHandler.getImageData(imageEntry)
                
                if (imageData == null) {
                    println("ERROR: imageData is null")
                    return@withContext false
                }
                
                if (imageData.isEmpty()) {
                    println("ERROR: imageData is empty")
                    return@withContext false
                }
                
                println("DEBUG: Image data size: ${imageData.size} bytes")
                
                // Bitmapデコードテスト
                try {
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                    
                    if (bitmap == null) {
                        println("ERROR: Failed to decode bitmap")
                        return@withContext false
                    }
                    
                    println("DEBUG: Bitmap decoded successfully: ${bitmap.width}x${bitmap.height}")
                    
                    if (bitmap.isRecycled) {
                        println("WARNING: Bitmap is recycled")
                    }
                    
                    return@withContext true
                    
                } catch (e: Exception) {
                    println("ERROR: Bitmap decode failed: ${e.message}")
                    return@withContext false
                }
                
            } catch (e: Exception) {
                println("ERROR: debugSpecificImage failed: ${e.message}")
                e.printStackTrace()
                return@withContext false
            }
        }
    }
    
    /**
     * DirectZipImageHandlerの状態をデバッグ
     */
    fun debugHandlerStatus() {
        try {
            println("=== DirectZipImageHandler Status ===")
            val memoryStatus = directZipHandler.getMemoryAndPreloadStatus()
            println(memoryStatus)
            
            val performanceMetrics = directZipHandler.performanceMetrics.value
            println("Performance Metrics:")
            println("  - Cache Hit Rate: ${performanceMetrics.cacheHitRate * 100}%")
            println("  - Average Load Time: ${performanceMetrics.averageLoadTime}ms")
            println("  - Total Requests: ${performanceMetrics.totalRequests}")
            println("  - Memory Usage: ${performanceMetrics.memoryUsageBytes / 1024 / 1024}MB")
            println("  - Active Preloads: ${performanceMetrics.activePreloads}")
            println("================================")
            
        } catch (e: Exception) {
            println("ERROR: Failed to get handler status: ${e.message}")
        }
    }
}
