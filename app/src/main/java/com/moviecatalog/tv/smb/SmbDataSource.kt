package com.moviecatalog.tv.smb

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.smb.SmbRandomAccessFile
import java.io.EOFException

private const val TAG = "MovieCatalog"

// Matroska/MP4 metadata parsing (and ExoPlayer's own extractor probing) reads in very small chunks -
// sometimes 1-4 bytes at a time. Passing each of those straight to jcifs means a separate SMB round
// trip per call, which was observed taking 90+ seconds just to parse a file's headers before any
// video appeared. This local buffer absorbs those into far fewer, larger network reads.
private const val BUFFER_SIZE = 256 * 1024

/**
 * Streams a movie straight off the NAS over SMB for ExoPlayer, without downloading it first.
 * The player's MediaItem URI is "smbcatalog:<url-encoded catalog path>" (e.g. from movies.json's
 * "path" field); [SmbClient.resolvePlayableFile] turns that into the actual video file.
 */
class SmbDataSource(private val client: SmbClient) : BaseDataSource(/* isNetwork= */ true) {

    private var raf: SmbRandomAccessFile? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened = false  // only call transferEnded() if open() actually got that far

    private val readBuffer = ByteArray(BUFFER_SIZE)
    private var bufferPos = 0     // next unread byte in readBuffer
    private var bufferLen = 0     // valid bytes currently in readBuffer

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val catalogPath = Uri.decode(dataSpec.uri.schemeSpecificPart)
        val file = client.resolvePlayableFile(catalogPath)
        val fileLength = file.length()
        Log.d(TAG, "SmbDataSource.open: ${file.path} ($fileLength bytes), position=${dataSpec.position}")
        val handle = SmbRandomAccessFile(file, "r")
        handle.seek(dataSpec.position)
        raf = handle
        bufferPos = 0
        bufferLen = 0

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else fileLength - dataSpec.position
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        if (bufferPos == bufferLen) {
            bufferLen = try {
                raf!!.read(readBuffer, 0, minOf(BUFFER_SIZE.toLong(), bytesRemaining).toInt())
            } catch (e: Exception) {
                Log.e(TAG, "SmbDataSource: buffered read from NAS failed", e)
                throw e
            }
            bufferPos = 0
            if (bufferLen <= 0) throw EOFException("Unexpected end of file from NAS")
        }

        val toCopy = minOf(length, bufferLen - bufferPos)
        System.arraycopy(readBuffer, bufferPos, buffer, offset, toCopy)
        bufferPos += toCopy
        bytesRemaining -= toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            raf?.close()
        } finally {
            raf = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val client: SmbClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(client)
    }
}
