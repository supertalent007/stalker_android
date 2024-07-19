package org.stalker.securesms.registration.secondary

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.Test
import org.stalker.securesms.crypto.IdentityKeyUtil
import org.stalker.securesms.devicelist.protos.DeviceName
import java.nio.charset.Charset

class DeviceNameCipherTest {

  @Test
  fun encryptDeviceName() {
    val deviceName = "xXxCoolDeviceNamexXx"
    val identityKeyPair = IdentityKeyUtil.generateIdentityKeyPair()

    val encryptedDeviceName = DeviceNameCipher.encryptDeviceName(deviceName.toByteArray(Charset.forName("UTF-8")), identityKeyPair)

    val plaintext = DeviceNameCipher.decryptDeviceName(DeviceName.ADAPTER.decode(encryptedDeviceName), identityKeyPair)!!

    assertThat(String(plaintext, Charset.forName("UTF-8")), `is`(deviceName))
  }
}
