package org.stalker.securesms.calls.log

/**
 * Allows user to only display certain classes of calls.
 */
enum class CallLogFilter {
  /**
   * All call logs will be displayed
   */
  ALL,

  /**
   * Only missed calls will be displayed
   */
  MISSED,

  /**
   * Only ad-hoc calls will be returned
   */
  AD_HOC
}
