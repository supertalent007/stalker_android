/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.backup.v2.stream

import org.signal.core.util.stream.MacOutputStream
import org.signal.core.util.writeVarInt32
import org.stalker.securesms.backup.v2.proto.BackupInfo
import org.stalker.securesms.backup.v2.proto.Frame
import org.stalker.securesms.util.Util
import org.whispersystems.signalservice.api.backup.BackupKey
import org.whispersystems.signalservice.api.push.ServiceId.ACI
import java.io.IOException
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Provides the ability to write backup frames in a streaming fashion to a target [OutputStream].
 * As it's being written, it will be both encrypted and compressed. Specifically, the backup frames
 * are gzipped, that gzipped data is encrypted, and then an HMAC of the encrypted data is appended
 * to the end of the [outputStream].
 */
class EncryptedBackupWriter(
  key: BackupKey,
  aci: ACI,
  private val outputStream: OutputStream,
  private val append: (ByteArray) -> Unit
) : BackupExportWriter {

  private val mainStream: PaddedGzipOutputStream
  private val macStream: MacOutputStream

  init {
    val keyMaterial = key.deriveBackupSecrets(aci)

    val iv: ByteArray = Util.getSecretBytes(16)
    outputStream.write(iv)
    outputStream.flush()

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
      init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyMaterial.cipherKey, "AES"), IvParameterSpec(iv))
    }

    val mac = Mac.getInstance("HmacSHA256").apply {
      init(SecretKeySpec(keyMaterial.macKey, "HmacSHA256"))
      update(iv)
    }

    macStream = MacOutputStream(outputStream, mac)
    val cipherStream = CipherOutputStream(macStream, cipher)

    mainStream = PaddedGzipOutputStream(cipherStream)
  }

  override fun write(header: BackupInfo) {
    val headerBytes = header.encode()

    mainStream.writeVarInt32(headerBytes.size)
    mainStream.write(headerBytes)
  }

  @Throws(IOException::class)
  override fun write(frame: Frame) {
    val frameBytes: ByteArray = frame.encode()

    mainStream.writeVarInt32(frameBytes.size)
    mainStream.write(frameBytes)
  }

  @Throws(IOException::class)
  override fun close() {
    // We need to close the main stream in order for the gzip and all the cipher operations to fully finish before
    // we can calculate the MAC. Unfortunately flush()/finish() is not sufficient. So we have to defer to the
    // caller to append the bytes to the end of the data however they see fit (like appending to a file).
    mainStream.close()
    val mac = macStream.mac.doFinal()
    append(mac)
  }
}
