package com.celstech.satendroid.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * ZIPファイルからサムネイルを生成するユーティリティクラス
 */
object ThumbnailGenerator {
    
    private const val THUMBNAIL_SIZE = 120 // dp to px conversion needed
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    
    /**
     * ZIPファイル内の最初の画像からサムネイルを生成
     * @param file ZIPファイル
     * @return サムネイル画像またはnull
     */
    suspend fun generateThumbnail(file: File): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || !file.isFile || file.extension.lowercase() != "zip") {
                return@withContext null
            }
            
            ZipFile(file).use { zipFile ->
                // ZIP内の画像ファイルを探す
                val imageEntry = zipFile.entries().asSequence()
                    .filter { entry ->
                        !entry.isDirectory && 
                        entry.name.substringAfterLast('.', "").lowercase() in imageExtensions
                    }
                    .sortedBy { it.name.lowercase() }
                    .firstOrNull()
                
                imageEntry?.let { entry ->
                    zipFile.getInputStream(entry).use { inputStream ->
                        val originalBitmap = BitmapFactory.decodeStream(inputStream)
                        originalBitmap?.let { bitmap ->
                            createThumbnail(bitmap)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * ビットマップからサムネイルを作成
     * @param originalBitmap 元の画像
     * @return サムネイル画像
     */
    private fun createThumbnail(originalBitmap: Bitmap): Bitmap {
        val size = THUMBNAIL_SIZE
        
        // アスペクト比を保持してリサイズ
        val aspectRatio = originalBitmap.width.toFloat() / originalBitmap.height.toFloat()
        val (targetWidth, targetHeight) = if (aspectRatio > 1f) {
            size to (size / aspectRatio).toInt()
        } else {
            (size * aspectRatio).toInt() to size
        }
        
        // リサイズ
        val resizedBitmap = Bitmap.createScaledBitmap(
            originalBitmap, 
            targetWidth, 
            targetHeight, 
            true
        )
        
        // 正方形のサムネイルを作成（中央クロップ）
        val thumbnail = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(thumbnail)
        
        // 背景を白で塗りつぶし
        canvas.drawColor(android.graphics.Color.WHITE)
        
        // 中央に配置
        val left = (size - targetWidth) / 2f
        val top = (size - targetHeight) / 2f
        
        canvas.drawBitmap(resizedBitmap, left, top, null)
        
        // メモリ解放
        if (originalBitmap != resizedBitmap) {
            resizedBitmap.recycle()
        }
        
        return thumbnail
    }
    
    /**
     * デフォルトのZIPファイルアイコンを生成
     * @return デフォルトアイコン
     */
    fun createDefaultZipIcon(): Bitmap {
        val size = THUMBNAIL_SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = true
            textSize = size * 0.6f
            textAlign = Paint.Align.CENTER
        }
        
        // 背景
        canvas.drawColor(android.graphics.Color.LTGRAY)
        
        // ZIPアイコン文字
        val textBounds = Rect()
        val text = "🗜️"
        paint.getTextBounds(text, 0, text.length, textBounds)
        canvas.drawText(
            text,
            size / 2f,
            size / 2f + textBounds.height() / 2f,
            paint
        )
        
        return bitmap
    }
}
