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
            // 高速化されたDirectZipImageHandlerを使用
            val imageData = zipHandler.getImageData(data)
            
            if (imageData == null || imageData.isEmpty()) {
                throw IllegalStateException("Failed to load image or data is empty: ${data.fileName}")
            }
            
            println("DEBUG: Successfully loaded image data: ${imageData.size} bytes for ${data.fileName}")
            
            // デコードオプションの設定（ダウンサンプリング用）
            val decodeOptions = BitmapFactory.Options().apply {
                // まずサイズだけを取得
                inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(imageData, 0, imageData.size, this)
                
                // 適切な inSampleSize を計算
                inSampleSize = calculateInSampleSize(this, options.size.width.px, options.size.height.px)
                inJustDecodeBounds = false
                
                // メモリ節約設定
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            
            val bitmapDecodeStart = System.currentTimeMillis()
            
            // 設定したオプションでデコード
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size, decodeOptions)
            
            if (bitmap == null) {
                throw IllegalStateException("Failed to decode image: ${data.fileName}")
            }
            
            val totalTime = System.currentTimeMillis() - startTime
            val decodeTime = System.currentTimeMillis() - bitmapDecodeStart
            
            println("DEBUG: ZipImageFetcherNew completed ${data.fileName} in ${totalTime}ms (decode: ${decodeTime}ms, sampleSize: ${decodeOptions.inSampleSize})")
            
            return DrawableResult(
                drawable = bitmap.toDrawable(options.context.resources),
                isSampled = decodeOptions.inSampleSize > 1,
                dataSource = DataSource.DISK
            )
        } catch (e: Exception) {
            println("ERROR: ZipImageFetcherNew failed for ${data.fileName}: ${e.message}")
            throw e
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (reqWidth > 0 && reqHeight > 0) {
            if (height > reqHeight || width > reqWidth) {
                val halfHeight: Int = height / 2
                val halfWidth: Int = width / 2

                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
        }
        return inSampleSize
    }

    private val coil.size.Dimension.px: Int
        get() = when (this) {
            is coil.size.Dimension.Pixels -> px
            else -> 0
        }

    
    class Factory(private val zipHandler: DirectZipImageHandler) : Fetcher.Factory<ZipImageEntry> {
        override fun create(data: ZipImageEntry, options: Options, imageLoader: ImageLoader): Fetcher {
            println("DEBUG: ZipImageFetcherNew.Factory creating fetcher for ${data.fileName}")
            return ZipImageFetcherNew(data, options, zipHandler)
        }
    }
}
