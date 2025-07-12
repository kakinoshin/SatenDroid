package com.celstech.satendroid.utils

import android.net.Uri
import java.io.File

/**
 * ZIP内の画像エントリを表すデータクラス
 */
data class ZipImageEntry(
    val zipUri: Uri,
    val zipFile: File?,
    val entryName: String,
    val fileName: String,
    val size: Long,
    val index: Int
) {
    // 一意のIDを生成（Coilのキーとして使用）
    val id: String
        get() = "${zipUri}#${entryName}"
}
