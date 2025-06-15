package com.celstech.satendroid.utils

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

class ZipImageHandler(private val context: Context) {
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
    fun clearExtractedFiles(files: List<File>) {
        files.forEach { file ->
            try {
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}