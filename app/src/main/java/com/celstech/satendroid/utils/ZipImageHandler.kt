package com.celstech.satendroid.utils

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.celstech.satendroid.cache.ImageCacheManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * 従来のZipImageHandler - テンポラリファイル展開方式（非推奨）
 * 新しいDirectZipImageHandlerを使用してください
 * このクラスは互換性のためのみ保持されています
 */
@Deprecated(
    message = "このクラスは非推奨です。DirectZipImageHandlerを使用してください。",
    replaceWith = ReplaceWith("DirectZipImageHandler")
)
class ZipImageHandler(private val context: Context) {
    private val cacheManager = ImageCacheManager(context)
    
    /**
     * ZIPファイルから画像を抽出（非推奨：テンポラリファイルに展開）
     * 新しいDirectZipImageHandler.getImageEntriesFromZipを使用してください
     */
    @Deprecated(
        message = "この方法はテンポラリファイルを作成します。DirectZipImageHandler.getImageEntriesFromZipを使用してください。",
        replaceWith = ReplaceWith("DirectZipImageHandler.getImageEntriesFromZip(zipUri)")
    )
    fun extractImagesFromZip(zipUri: Uri): List<File> {
        println("WARNING: extractImagesFromZip is deprecated. Use DirectZipImageHandler.getImageEntriesFromZip instead.")
        
        // 非推奨処理として空のリストを返す
        // 実際の展開処理は削除されました
        return emptyList()
    }

    /**
     * 抽出されたテンポラリファイルをクリア（非推奨）
     * 新しい方式ではテンポラリファイルは作成されません
     */
    @Deprecated(
        message = "新しい方式ではテンポラリファイルは作成されません。DirectZipImageHandler.clearMemoryCache()を使用してください。",
        replaceWith = ReplaceWith("DirectZipImageHandler.clearMemoryCache()")
    )
    fun clearExtractedFiles(context: Context, files: List<File>) {
        println("WARNING: clearExtractedFiles is deprecated. No temporary files are created in the new approach.")
        // 新しい方式では何もする必要がない
    }

    /**
     * キャッシュマネージャーを取得
     */
    fun getCacheManager(): ImageCacheManager = cacheManager
    
    /**
     * ファイル識別子を生成（エンコーディング統一）
     */
    fun generateFileIdentifier(zipUri: Uri, zipFile: File? = null): String {
        return try {
            when {
                zipFile != null && zipFile.exists() -> {
                    // Fileオブジェクトがある場合は絶対パスを使用（最も確実）
                    zipFile.absolutePath
                }
                zipUri.scheme == "file" -> {
                    // File URIの場合はパスを正規化
                    val path = zipUri.path ?: zipUri.toString()
                    File(path).absolutePath
                }
                else -> {
                    // Content URIの場合はそのまま使用（正規化困難）
                    zipUri.toString()
                }
            }
        } catch (e: Exception) {
            // フォールバック：URIをそのまま文字列化
            zipUri.toString()
        }
    }
    
    /**
     * 現在の表示位置を保存
     */
    fun saveCurrentPosition(zipUri: Uri, imageIndex: Int, zipFile: File? = null) {
        cacheManager.saveCurrentPosition(zipUri, imageIndex, zipFile)
    }
    
    /**
     * 保存された表示位置を取得
     */
    fun getSavedPosition(zipUri: Uri, zipFile: File? = null): Int? {
        return cacheManager.getSavedPosition(zipUri, zipFile)
    }
    
    /**
     * ZIPファイル削除時の処理
     */
    fun onZipFileDeleted(zipUri: Uri, zipFile: File? = null) {
        cacheManager.onFileDeleted(zipUri, zipFile)
    }

    // ファイルのUriを取得するヘルパー
    private fun getFileUri(context: Context, file: File): Uri? {
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = MediaStore.Files.FileColumns.DATA + " = ?"
        val selectionArgs = arrayOf(file.absolutePath)
        val uriExternal = MediaStore.Files.getContentUri("external")
        context.contentResolver.query(uriExternal, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                return Uri.withAppendedPath(uriExternal, id.toString())
            }
        }
        return null
    }
}
