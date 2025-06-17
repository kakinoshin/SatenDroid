package com.celstech.satendroid.utils

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.celstech.satendroid.ui.models.LocalItem
import java.io.File

/**
 * ファイル操作関連のユーティリティ関数
 */
object FileUtils {
    
    /**
     * 権限を持つファイルの削除処理
     */
    fun deleteFileWithPermission(context: Context, item: LocalItem.ZipFile): Boolean {
        try {
            if (item.file.delete()) {
                return true
            }
        } catch (_: SecurityException) {
            // 続行してMediaStore経由で削除を試みる
        } catch (_: Exception) {
            return false
        }
        
        // MediaStore経由でUriを検索して削除
        return try {
            val filePath = item.file.absolutePath
            val fileName = item.file.name
            val fileSize = item.file.length()
            val contentResolver = context.contentResolver
            val uriExternal = android.provider.MediaStore.Files.getContentUri("external")
            val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)
            
            // まずDATA列で検索
            var selection = android.provider.MediaStore.MediaColumns.DATA + "=?"
            var selectionArgs = arrayOf(filePath)
            var cursor = contentResolver.query(uriExternal, projection, selection, selectionArgs, null)
            
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID))
                    val uri = ContentUris.withAppendedId(uriExternal, id)
                    val rowsDeleted = contentResolver.delete(uri, null, null)
                    if (rowsDeleted > 0) return true
                }
            }
            
            // DATA列で見つからない場合、display_nameとサイズで検索
            selection = android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + 
                       android.provider.MediaStore.MediaColumns.SIZE + "=?"
            selectionArgs = arrayOf(fileName, fileSize.toString())
            cursor = contentResolver.query(uriExternal, projection, selection, selectionArgs, null)
            
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID))
                    val uri = ContentUris.withAppendedId(uriExternal, id)
                    val rowsDeleted = contentResolver.delete(uri, null, null)
                    if (rowsDeleted > 0) return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 外部ストレージアクセス権限をチェック
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * ストレージ権限設定画面を開く
     */
    fun openStoragePermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = "package:${context.packageName}".toUri()
            context.startActivity(intent)
        }
    }
}
