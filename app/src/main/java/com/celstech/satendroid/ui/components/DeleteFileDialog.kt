package com.celstech.satendroid.ui.components

import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.celstech.satendroid.ui.models.LocalItem
import com.celstech.satendroid.utils.FileUtils

/**
 * 権限を要求してファイルを削除するコンポーネント
 */
@Composable
fun DeleteFileWithPermission(
    item: LocalItem.ZipFile,
    onDeleteResult: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var permissionRequested by remember { mutableStateOf(false) }
    
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onDeleteResult(FileUtils.deleteFileWithPermission(context, item))
        } else {
            onDeleteResult(false)
        }
    }
    
    LaunchedEffect(Unit) {
        val hasPermission = FileUtils.hasStoragePermission(context)
        
        if (hasPermission) {
            onDeleteResult(FileUtils.deleteFileWithPermission(context, item))
        } else if (!permissionRequested) {
            permissionRequested = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                FileUtils.openStoragePermissionSettings(context)
                onDeleteResult(false)
            } else {
                launcher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else {
            onDeleteResult(false)
        }
    }
}
