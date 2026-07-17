package app.clearspace.network.security.guard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * MetadataStripper (Scrambled Exif Style)
 * Removes EXIF and GPS metadata from images before they are saved to the Vault
 * or shared over the P2P network.
 * Achieves this by decoding the image to a raw Bitmap and re-encoding it to WebP.
 */
class MetadataStripper(private val context: Context) {

    /**
     * Strips metadata by decoding and re-encoding as a WebP image.
     * WebP is chosen for efficiency and because re-encoding inherently drops EXIF.
     * 
     * @param inputStream The raw image input stream containing potential metadata.
     * @param quality Encoding quality (0-100). 100 forces lossless in older WebP versions.
     * @return A ByteArray containing the clean, metadata-free image.
     */
    fun scrubImageMetadata(inputStream: InputStream, quality: Int = 100): ByteArray? {
        return try {
            // Decode the image into a raw bitmap (drops metadata in memory)
            val bitmap = BitmapFactory.decodeStream(inputStream) ?: return null
            
            // Re-encode to WebP
            val outputStream = ByteArrayOutputStream()
            
            // Android 11+ (API 30+) recommends WEBP_LOSSLESS or WEBP_LOSSY
            val compressFormat = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            
            bitmap.compress(compressFormat, quality, outputStream)
            bitmap.recycle() // Free memory immediately
            
            Log.d(TAG, "Image metadata stripped successfully via WebP re-encoding.")
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to strip image metadata", e)
            null
        }
    }
    
    /**
     * Strips metadata and writes directly to a safe output file in the app's private storage.
     */
    fun scrubAndSaveImage(inputStream: InputStream, outputFile: File, quality: Int = 100): Boolean {
        val cleanBytes = scrubImageMetadata(inputStream, quality) ?: return false
        return try {
            FileOutputStream(outputFile).use { fos ->
                fos.write(cleanBytes)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write clean image to file", e)
            false
        }
    }

    companion object {
        private const val TAG = "MetadataStripper"
    }
}
