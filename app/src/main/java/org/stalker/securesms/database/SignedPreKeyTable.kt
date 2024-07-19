package org.stalker.securesms.database

import android.content.Context
import androidx.core.content.contentValuesOf
import org.signal.core.util.Base64
import org.signal.core.util.SqlUtil
import org.signal.core.util.deleteAll
import org.signal.core.util.logging.Log
import org.signal.core.util.requireInt
import org.signal.core.util.requireLong
import org.signal.core.util.requireNonNullString
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.whispersystems.signalservice.api.push.ServiceId
import java.io.IOException
import java.util.LinkedList

class SignedPreKeyTable(context: Context, databaseHelper: SignalDatabase) : DatabaseTable(context, databaseHelper) {
  companion object {
    private val TAG = Log.tag(SignedPreKeyTable::class.java)

    const val TABLE_NAME = "signed_prekeys"
    const val ID = "_id"
    const val ACCOUNT_ID = "account_id"
    const val KEY_ID = "key_id"
    const val PUBLIC_KEY = "public_key"
    const val PRIVATE_KEY = "private_key"
    const val SIGNATURE = "signature"
    const val TIMESTAMP = "timestamp"

    const val CREATE_TABLE = """
      CREATE TABLE $TABLE_NAME (
        $ID INTEGER PRIMARY KEY,
        $ACCOUNT_ID TEXT NOT NULL,
        $KEY_ID INTEGER NOT NULL, 
        $PUBLIC_KEY TEXT NOT NULL,
        $PRIVATE_KEY TEXT NOT NULL,
        $SIGNATURE TEXT NOT NULL, 
        $TIMESTAMP INTEGER DEFAULT 0,
        UNIQUE($ACCOUNT_ID, $KEY_ID)
    )
    """

    const val PNI_ACCOUNT_ID = "PNI"
  }

  fun get(serviceId: ServiceId, keyId: Int): SignedPreKeyRecord? {
    readableDatabase.query(TABLE_NAME, null, "$ACCOUNT_ID = ? AND $KEY_ID = ?", SqlUtil.buildArgs(serviceId.toAccountId(), keyId), null, null, null).use { cursor ->
      if (cursor.moveToFirst()) {
        try {
          val publicKey = Curve.decodePoint(Base64.decode(cursor.requireNonNullString(PUBLIC_KEY)), 0)
          val privateKey = Curve.decodePrivatePoint(Base64.decode(cursor.requireNonNullString(PRIVATE_KEY)))
          val signature = Base64.decode(cursor.requireNonNullString(SIGNATURE))
          val timestamp = cursor.requireLong(TIMESTAMP)
          return SignedPreKeyRecord(keyId, timestamp, ECKeyPair(publicKey, privateKey), signature)
        } catch (e: InvalidKeyException) {
          Log.w(TAG, e)
        } catch (e: IOException) {
          Log.w(TAG, e)
        }
      }
    }
    return null
  }

  fun getAll(serviceId: ServiceId): List<SignedPreKeyRecord> {
    val results: MutableList<SignedPreKeyRecord> = LinkedList()

    readableDatabase.query(TABLE_NAME, null, "$ACCOUNT_ID = ?", SqlUtil.buildArgs(serviceId.toAccountId()), null, null, null).use { cursor ->
      while (cursor.moveToNext()) {
        try {
          val keyId = cursor.requireInt(KEY_ID)
          val publicKey = Curve.decodePoint(Base64.decode(cursor.requireNonNullString(PUBLIC_KEY)), 0)
          val privateKey = Curve.decodePrivatePoint(Base64.decode(cursor.requireNonNullString(PRIVATE_KEY)))
          val signature = Base64.decode(cursor.requireNonNullString(SIGNATURE))
          val timestamp = cursor.requireLong(TIMESTAMP)
          results.add(SignedPreKeyRecord(keyId, timestamp, ECKeyPair(publicKey, privateKey), signature))
        } catch (e: InvalidKeyException) {
          Log.w(TAG, e)
        } catch (e: IOException) {
          Log.w(TAG, e)
        }
      }
    }

    return results
  }

  fun insert(serviceId: ServiceId, keyId: Int, record: SignedPreKeyRecord) {
    val contentValues = contentValuesOf(
      ACCOUNT_ID to serviceId.toAccountId(),
      KEY_ID to keyId,
      PUBLIC_KEY to Base64.encodeWithPadding(record.keyPair.publicKey.serialize()),
      PRIVATE_KEY to Base64.encodeWithPadding(record.keyPair.privateKey.serialize()),
      SIGNATURE to Base64.encodeWithPadding(record.signature),
      TIMESTAMP to record.timestamp
    )
    writableDatabase.replace(TABLE_NAME, null, contentValues)
  }

  fun delete(serviceId: ServiceId, keyId: Int) {
    writableDatabase.delete(TABLE_NAME, "$ACCOUNT_ID = ? AND $KEY_ID = ?", SqlUtil.buildArgs(serviceId.toAccountId(), keyId))
  }

  fun debugDeleteAll() {
    writableDatabase.deleteAll(OneTimePreKeyTable.TABLE_NAME)
  }

  private fun ServiceId.toAccountId(): String {
    return when (this) {
      is ServiceId.ACI -> this.toString()
      is ServiceId.PNI -> PNI_ACCOUNT_ID
    }
  }
}
