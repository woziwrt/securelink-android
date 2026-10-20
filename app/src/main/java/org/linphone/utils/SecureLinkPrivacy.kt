/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (c) 2026 Petr Wozniak <petr.wozniak@gmail.com>
 *
 * This file is part of SecureLink, a modified version of linphone-android.
 */
package org.linphone.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.annotation.WorkerThread
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import org.linphone.core.tools.Log

/**
 * v1 agreement (as iOS 850f40f17): a photo or video leaves the phone without metadata – no location, time or device.
 * A photo is re-encoded from its pixels (nothing of the original file survives, its orientation is applied), a video
 * is copied into a new file with picture and sound only, without re-encoding. If cleaning fails, the file is not sent.
 */
object SecureLinkPrivacy {
    private const val TAG = "[SecureLink Privacy]"

    /**
     * Cleans [path], the app's own copy. Returns the path of the clean file (a HEIC/WebP photo becomes a .jpg) or
     * null when the file must not be sent.
     */
    @WorkerThread
    fun stripMetadata(path: String): String? {
        val mime = FileUtils.getMimeType(
            FileUtils.getMimeTypeFromExtension(FileUtils.getExtensionFromFileName(path))
        )
        return try {
            when (mime) {
                FileUtils.MimeType.Image -> stripImage(path)
                FileUtils.MimeType.Video -> if (stripVideo(path)) path else null
                else -> path // documents are sent as they are (as iOS)
            }
        } catch (e: Exception) {
            Log.e("$TAG Cleaning [$path] failed: $e")
            null
        }
    }

    private fun stripImage(path: String): String? {
        val original = BitmapFactory.decodeFile(path) ?: return null.also { Log.e("$TAG Cannot decode image") }
        val rotation = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val bitmap = if (rotation != 0f) {
            val matrix = Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        } else {
            original
        }
        val png = path.lowercase().endsWith(".png")
        val tmp = File("$path.clean")
        FileOutputStream(tmp).use { out ->
            bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, out)
        }
        // the result is checked: no location or camera fields may be left
        val check = ExifInterface(tmp.absolutePath)
        if (check.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null || check.getAttribute(ExifInterface.TAG_MAKE) != null ||
            check.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) != null
        ) {
            tmp.delete()
            Log.e("$TAG Metadata still present after re-encoding")
            return null
        }
        // a HEIC/WebP photo becomes a JPEG: the name must say so for the receiver
        val target = if (png || path.lowercase().endsWith(".jpg") || path.lowercase().endsWith(".jpeg")) {
            File(path)
        } else {
            File(path).also { it.delete() }
            File(path.substringBeforeLast('.') + ".jpg")
        }
        if (!tmp.renameTo(target)) return null
        Log.i("$TAG Image cleaned (rotation $rotation) → [${target.name}]")
        return target.absolutePath
    }

    private fun stripVideo(path: String): Boolean {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)
        val tmp = File("$path.clean")
        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val trackMap = HashMap<Int, Int>()
        var bufferSize = 1024 * 1024
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue // no metadata tracks
            if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
            }
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
            extractor.selectTrack(i)
            trackMap[i] = muxer.addTrack(format)
        }
        if (trackMap.isEmpty()) {
            extractor.release()
            muxer.release()
            tmp.delete()
            return false
        }
        muxer.start()
        val buffer = ByteBuffer.allocate(bufferSize)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val track = trackMap[extractor.sampleTrackIndex]
            if (track != null) {
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(track, buffer, info)
            }
            extractor.advance()
        }
        muxer.stop()
        muxer.release()
        extractor.release()
        if (!tmp.renameTo(File(path))) return false
        Log.i("$TAG Video cleaned ([${trackMap.size}] tracks)")
        return true
    }
}
