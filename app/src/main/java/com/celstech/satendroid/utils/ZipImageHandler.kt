package com.celstech.satendroid.utils

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.celstech.satendroid.cache.ImageCacheManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

class ZipImageHandler(private val context: Context) {
    private val cacheManager = ImageCacheManager(context)
    fun extractImagesFromZip(zipUri: Uri): List<File> {
        val extractedImageFiles = mutableListOf<File>()

        try {
            context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipInputStream ->
                    // Supported image extensions
                    val supportedExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")

                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        // Check if entry is an image file
                        val fileName = entry.name
                        val fileExtension = fileName.substringAfterLast('.', "").lowercase()

                        if (!entry.isDirectory && fileExtension in supportedExtensions) {
                            // Create a temporary file for the image
                            val sanitizedFileName = fileName.substringAfterLast('/')
                            val tempImageFile = File(context.cacheDir, sanitizedFileName)

                            // Extract the image
                            FileOutputStream(tempImageFile).use { output ->
                                zipInputStream.copyTo(output)
                            }

                            extractedImageFiles.add(tempImageFile)
                        }

                        zipInputStream.closeEntry()
                        entry = zipInputStream.nextEntry
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

        // Sort extracted images by filename
        return extractedImageFiles.sortedBy { it.name }
    }

    // Clean up extracted temporary files
    fun clearExtractedFiles(context: Context, files: List<File>) {
        android.util.Log.e("ZipImageHandler", "[START] clearExtractedFiles: files=${files.map { it.absolutePath }}")
        if (files.isEmpty()) {
            android.util.Log.w("ZipImageHandler", "[WARN] clearExtractedFiles: files is empty!")
        }
        files.forEach { file ->
            try {
                android.util.Log.e("ZipImageHandler", "[TRY] Delete: ${file.absolutePath} exists=${file.exists()} canRead=${file.canRead()} canWrite=${file.canWrite()}")
                if (file.exists()) {
                    if (file.absolutePath.startsWith("/storage/emulated/0/")) {
                        val uri = getFileUri(context, file)
                        android.util.Log.e("ZipImageHandler", "[INFO] getFileUri result: $uri")
                        if (uri != null) {
                            val rows = context.contentResolver.delete(uri, null, null)
                            android.util.Log.e("ZipImageHandler", "[INFO] contentResolver.delete rows: $rows")
                            if (rows == 0) {
                                val values = android.content.ContentValues().apply {
                                    put(MediaStore.MediaColumns.DATA, file.absolutePath)
                                }
                                val insertedUri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                                android.util.Log.e("ZipImageHandler", "[INFO] insertedUri: $insertedUri")
                                if (insertedUri != null) {
                                    val delRows = context.contentResolver.delete(insertedUri, null, null)
                                    android.util.Log.e("ZipImageHandler", "[INFO] delete after insert rows: $delRows")
                                } else {
                                    android.util.Log.e("ZipImageHandler", "[ERROR] Failed to insert file into MediaStore: ${file.absolutePath}")
                                }
                            }
                        } else {
                            val deleted = file.delete()
                            android.util.Log.e("ZipImageHandler", "[INFO] file.delete() result: $deleted")
                        }
                    } else {
                        val deleted = file.delete()
                        android.util.Log.e("ZipImageHandler", "[INFO] file.delete() result: $deleted")
                    }
                } else {
                    android.util.Log.w("ZipImageHandler", "[WARN] File does not exist: ${file.absolutePath}")
                }
            } catch (e: Exception) {
                android.util.Log.e("ZipImageHandler", "[EXCEPTION] Failed to delete: ${file.absolutePath}", e)
            }
        }
        android.util.Log.e("ZipImageHandler", "[END] clearExtractedFiles")
    }

    /**
     * キャッシュマネージャーを取得
     */
    fun getCacheManager(): ImageCacheManager = cacheManager

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