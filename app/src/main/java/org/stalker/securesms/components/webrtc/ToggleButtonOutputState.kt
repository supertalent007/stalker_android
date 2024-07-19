package org.stalker.securesms.components.webrtc

import kotlin.math.min

/**
 * This holds UI state for [WebRtcAudioOutputToggleButton]
 */
class ToggleButtonOutputState {
  private val availableOutputs: LinkedHashSet<WebRtcAudioOutput> = linkedSetOf(WebRtcAudioOutput.SPEAKER)
  private var selectedDevice = 0
    set(value) {
      if (value >= availableOutputs.size) {
        throw IndexOutOfBoundsException("Index: $value, size: ${availableOutputs.size}")
      }
      field = value
    }

  var isEarpieceAvailable: Boolean
    get() = availableOutputs.contains(WebRtcAudioOutput.HANDSET)
    set(value) {
      if (value) {
        availableOutputs.add(WebRtcAudioOutput.HANDSET)
      } else {
        availableOutputs.remove(WebRtcAudioOutput.HANDSET)
        selectedDevice = min(selectedDevice, availableOutputs.size - 1)
      }
    }

  var isBluetoothHeadsetAvailable: Boolean
    get() = availableOutputs.contains(WebRtcAudioOutput.BLUETOOTH_HEADSET)
    set(value) {
      if (value) {
        availableOutputs.add(WebRtcAudioOutput.BLUETOOTH_HEADSET)
      } else {
        availableOutputs.remove(WebRtcAudioOutput.BLUETOOTH_HEADSET)
        selectedDevice = min(selectedDevice, availableOutputs.size - 1)
      }
    }
  var isWiredHeadsetAvailable: Boolean
    get() = availableOutputs.contains(WebRtcAudioOutput.WIRED_HEADSET)
    set(value) {
      if (value) {
        availableOutputs.add(WebRtcAudioOutput.WIRED_HEADSET)
      } else {
        availableOutputs.remove(WebRtcAudioOutput.WIRED_HEADSET)
        selectedDevice = min(selectedDevice, availableOutputs.size - 1)
      }
    }

  @Deprecated("Used only for onSaveInstanceState.")
  fun getBackingIndexForBackup(): Int {
    return selectedDevice
  }

  @Deprecated("Used only for onRestoreInstanceState.")
  fun setBackingIndexForRestore(index: Int) {
    selectedDevice = 0
  }

  fun getCurrentOutput(): WebRtcAudioOutput {
    return getOutputs()[selectedDevice]
  }

  fun setCurrentOutput(outputType: WebRtcAudioOutput): Boolean {
    val newIndex = getOutputs().indexOf(outputType)
    return if (newIndex < 0) {
      false
    } else {
      selectedDevice = newIndex
      true
    }
  }

  fun getOutputs(): List<WebRtcAudioOutput> {
    return availableOutputs.toList()
  }

  fun peekNext(): WebRtcAudioOutput {
    val peekIndex = (selectedDevice + 1) % availableOutputs.size
    return getOutputs()[peekIndex]
  }
}
