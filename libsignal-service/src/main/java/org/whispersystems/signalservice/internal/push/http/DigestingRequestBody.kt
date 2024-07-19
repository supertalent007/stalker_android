package org.whispersystems.signalservice.internal.push.http

import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.internal.http.UnrepeatableRequestBody
import okio.BufferedSink
import org.signal.libsignal.protocol.incrementalmac.ChunkSizeChoice
import org.signal.libsignal.protocol.logging.Log
import org.whispersystems.signalservice.api.crypto.DigestingOutputStream
import org.whispersystems.signalservice.api.crypto.SkippingOutputStream
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.internal.crypto.AttachmentDigest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * This [RequestBody] encrypts the data written to it before it is sent.
 */
class DigestingRequestBody(
  private val inputStream: InputStream,
  private val outputStreamFactory: OutputStreamFactory,
  private val contentType: String,
  private val contentLength: Long,
  private val incremental: Boolean,
  private val progressListener: SignalServiceAttachment.ProgressListener?,
  private val cancelationSignal: CancelationSignal?,
  private val contentStart: Long
) : RequestBody(), UnrepeatableRequestBody {
  var attachmentDigest: AttachmentDigest? = null

  init {
    require(contentLength >= contentStart)
    require(contentStart >= 0)
  }

  override fun contentType(): MediaType? {
    return MediaType.parse(contentType)
  }

  @Throws(IOException::class)
  override fun writeTo(sink: BufferedSink) {
    val digestStream = ByteArrayOutputStream()
    val inner = SkippingOutputStream(contentStart, sink.outputStream())
    val isIncremental = incremental && outputStreamFactory is AttachmentCipherOutputStreamFactory
    val sizeChoice: ChunkSizeChoice = ChunkSizeChoice.inferChunkSize(contentLength.toInt())
    val outputStream: DigestingOutputStream = if (isIncremental) {
      (outputStreamFactory as AttachmentCipherOutputStreamFactory).createIncrementalFor(inner, contentLength, sizeChoice, digestStream)
    } else {
      outputStreamFactory.createFor(inner)
    }

    val buffer = ByteArray(8192)
    var read: Int
    var total: Long = 0

    while (inputStream.read(buffer, 0, buffer.size).also { read = it } != -1) {
      if (cancelationSignal?.isCanceled == true) {
        throw IOException("Canceled!")
      }
      outputStream.write(buffer, 0, read)
      total += read.toLong()
      progressListener?.onAttachmentProgress(contentLength, total)
    }

    outputStream.flush()

    val incrementalDigest: ByteArray = if (isIncremental) {
      if (contentLength != total) {
        Log.w(TAG, "Content uploaded ${logMessage(total, contentLength)} bytes compared to expected!")
      } else {
        Log.d(TAG, "Wrote the expected number of bytes.")
      }
      outputStream.close()
      digestStream.close()
      digestStream.toByteArray()
    } else {
      ByteArray(0)
    }

    attachmentDigest = AttachmentDigest(outputStream.transmittedDigest, incrementalDigest, sizeChoice.sizeInBytes)
  }

  override fun contentLength(): Long {
    return if (contentLength > 0) contentLength - contentStart else -1
  }

  private fun logMessage(actual: Long, expected: Long): String {
    val difference = actual - expected
    return if (difference > 0) {
      "+$difference"
    } else {
      difference.toString()
    }
  }

  companion object {
    const val TAG = "DigestingRequestBody"
  }
}
