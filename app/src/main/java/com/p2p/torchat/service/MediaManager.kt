package com.p2p.torchat.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

class MediaManager(private val context: Context) {
    /**
     * Reads an image from Uri, strips all metadata by re-compressing,
     * and returns the clean ByteArray as JPEG.
     */
    fun stripImageMetadata(uri: Uri): ByteArray? {
        return try {
            val bitmap =
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }

            val outputStream = ByteArrayOutputStream()
            // Compression to JPEG strips all original EXIF tags automatically in Android
            bitmap?.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)

            outputStream.toByteArray()
        } catch (e: java.io.IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads a generic file and returns its content as ByteArray.
     */
    fun getFileBytes(uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            }
        } catch (e: java.io.IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets the display name and size of a file for metadata purposes.
     */
    fun getFileDetails(uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = 0L
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex != -1) name = it.getString(nameIndex)
                if (sizeIndex != -1) size = it.getLong(sizeIndex)
            }
        }
        return Pair(name, size)
    }
}
