package com.celstech.satendroid.utils

import android.graphics.BitmapFactory
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options

/**
 * Coil用のカスタムFetcher - ZIP内の画像を直接読み込み（高速化版）
 */
class ZipImageFetcherNew(
    private val data: ZipImageEntry,
    private val options: Options,
    private val zipHandler: DirectZipImageHandler
) : Fetcher {
    
    override suspend fun fetch(): FetchResult {
        val startTime = System.currentTimeMillis()
        println("DEBUG: ZipImageFetcherNew.fetch() called for ${data.fileName}")
        println("DEBUG: ZIP URI: ${data.zipUri}")
        println("DEBUG: Entry name: ${data.entryName}")
        
        try {
            // ZIP ファイルの存在確認
            if (data.zipFile != null) {
                println("DEBUG: ZIP file path: ${data.zipFile.absolutePath}")
                println("DEBUG: ZIP file exists: ${data.zipFile.exists()}")
                println("DEBUG: ZIP file size: ${data.zipFile.length()} bytes")
            }
            
            // 高速化されたDirectZipImageHandlerを使用
            val imageData = zipHandler.getImageData(data)
            
            if (imageData == null) {
                println("ERROR: ZipImageFetcherNew - imageData is null for ${data.fileName}")
                println("ERROR: ZIP URI: ${data.zipUri}")
                println("ERROR: Entry name: ${data.entryName}")
                println("ERROR: ZIP file: ${data.zipFile?.absolutePath}")
                throw IllegalStateException("Failed to load image: ${data.fileName}")
            }
            
            if (imageData.isEmpty()) {
                println("ERROR: ZipImageFetcherNew - imageData is empty for ${data.fileName}")
                throw IllegalStateException("Image data is empty: ${data.fileName}")
            }
            
            println("DEBUG: Successfully loaded image data: ${imageData.size} bytes for ${data.fileName}")
            
            // ファイルヘッダーを確認
            if (imageData.size >= 10) {
                val header = imageData.take(10).joinToString(" ") { "0x%02x".format(it) }
                println("DEBUG: Image header: $header")
            }
            
            val bitmapDecodeStart = System.currentTimeMillis()
            
            // ByteArrayからBitmapを作成
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            
            if (bitmap == null) {
                println("ERROR: ZipImageFetcherNew - Failed to decode bitmap for ${data.fileName}")
                println("ERROR: Image data size: ${imageData.size} bytes")
                if (imageData.size >= 10) {
                    println("ERROR: First 10 bytes: ${imageData.take(10).joinToString { "0x%02x".format(it) }}")
                }
                
                // ファイル形式の推定
                val format = when {
                    imageData.size >= 2 && imageData[0] == 0xFF.toByte() && imageData[1] == 0xD8.toByte() -> "JPEG"
                    imageData.size >= 8 && imageData[1] == 'P'.code.toByte() && imageData[2] == 'N'.code.toByte() && imageData[3] == 'G'.code.toByte() -> "PNG"
                    imageData.size >= 6 && imageData[0] == 'G'.code.toByte() && imageData[1] == 'I'.code.toByte() && imageData[2] == 'F'.code.toByte() -> "GIF"
                    else -> "Unknown"
                }
                println("ERROR: Detected format: $format")
                
                throw IllegalStateException("Failed to decode image: ${data.fileName}")
            }
            
            if (bitmap.isRecycled) {
                println("ERROR: ZipImageFetcherNew - Bitmap is recycled for ${data.fileName}")
                throw IllegalStateException("Bitmap is recycled: ${data.fileName}")
            }
            
            val totalTime = System.currentTimeMillis() - startTime
            val decodeTime = System.currentTimeMillis() - bitmapDecodeStart
            
            println("DEBUG: ZipImageFetcherNew completed ${data.fileName} in ${totalTime}ms (decode: ${decodeTime}ms)")
            println("DEBUG: Bitmap: ${bitmap.width}x${bitmap.height}, config: ${bitmap.config}")
            
            // BitmapをDrawableに変換してCoilに返す
            return DrawableResult(
                drawable = bitmap.toDrawable(options.context.resources),
                isSampled = false,
                dataSource = DataSource.DISK
            )
        } catch (e: Exception) {
            val totalTime = System.currentTimeMillis() - startTime
            println("ERROR: ZipImageFetcherNew failed for ${data.fileName} after ${totalTime}ms")
            println("ERROR: Exception type: ${e::class.java.simpleName}")
            println("ERROR: Exception message: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
    
    class Factory(private val zipHandler: DirectZipImageHandler) : Fetcher.Factory<ZipImageEntry> {
        override fun create(data: ZipImageEntry, options: Options, imageLoader: ImageLoader): Fetcher {
            println("DEBUG: ZipImageFetcherNew.Factory creating fetcher for ${data.fileName}")
            return ZipImageFetcherNew(data, options, zipHandler)
        }
    }
}
