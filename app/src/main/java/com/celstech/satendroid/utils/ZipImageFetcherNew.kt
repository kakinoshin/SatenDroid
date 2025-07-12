// このファイルは一時的に無効化されています
// 必要なクラス（ZipImageEntry、DirectZipImageHandler）が無効化されているため
// コンパイルエラーを解決するため一時的にコメントアウト

/*
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
 * Coil用のカスタムFetcher - ZIP内の画像を直接読み込み
 * Bitmapを使用するシンプルな実装
 */
class ZipImageFetcherNew(
    private val data: ZipImageEntry,
    private val options: Options,
    private val zipHandler: DirectZipImageHandler
) : Fetcher {
    
    override suspend fun fetch(): FetchResult {
        // キャッシュされた画像データを取得
        val imageData = zipHandler.getCachedImageData(data)
            ?: throw IllegalStateException("Image not cached: ${data.fileName}")
        
        // ByteArrayからBitmapを作成
        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            ?: throw IllegalStateException("Failed to decode image: ${data.fileName}")
        
        // BitmapをDrawableに変換してCoilに返す
        return DrawableResult(
            drawable = bitmap.toDrawable(options.context.resources),
            isSampled = false,
            dataSource = DataSource.DISK
        )
    }
    
    class Factory(private val zipHandler: DirectZipImageHandler) : Fetcher.Factory<ZipImageEntry> {
        override fun create(data: ZipImageEntry, options: Options, imageLoader: ImageLoader): Fetcher {
            return ZipImageFetcherNew(data, options, zipHandler)
        }
    }
}
*/