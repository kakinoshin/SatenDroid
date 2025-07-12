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
        
        try {
            // 高速化されたDirectZipImageHandlerを使用
            val imageData = zipHandler.getImageData(data)
                ?: throw IllegalStateException("Failed to load image: ${data.fileName}")
            
            val bitmapDecodeStart = System.currentTimeMillis()
            
            // ByteArrayからBitmapを作成
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                ?: throw IllegalStateException("Failed to decode image: ${data.fileName}")
            
            val totalTime = System.currentTimeMillis() - startTime
            val decodeTime = System.currentTimeMillis() - bitmapDecodeStart
            
            println("DEBUG: ZipImageFetcherNew completed ${data.fileName} in ${totalTime}ms (decode: ${decodeTime}ms)")
            
            // BitmapをDrawableに変換してCoilに返す
            return DrawableResult(
                drawable = bitmap.toDrawable(options.context.resources),
                isSampled = false,
                dataSource = DataSource.DISK
            )
        } catch (e: Exception) {
            val totalTime = System.currentTimeMillis() - startTime
            println("DEBUG: ZipImageFetcherNew failed for ${data.fileName} after ${totalTime}ms: ${e.message}")
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
