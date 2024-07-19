/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import org.signal.core.util.logging.Log
import org.stalker.securesms.jobmanager.impl.NetworkConstraint
import org.stalker.securesms.util.ServiceUtil

/**
 * Backcompat listener for determining when the network connection is lost.
 * On API 28+, [onNetworkLost] is invoked when the system notifies the app that the network is lost.
 * On earlier versions, [onNetworkLost] is invoked on any network change (gained, lost, losing, etc)
 * Therefore, [onNetworkLost] is a higher-order function, which takes a function to determine conditionally if it should run.
 * API 28+ only runs on lost networks, so it provides a conditional that's always true because that is guaranteed by the call site.
 * Earlier versions use [NetworkConstraint.isMet] to query the current network state upon receiving the broadcast.
 */
class NetworkConnectionListener(private val context: Context, private val onNetworkLost: (() -> Boolean) -> Unit) {
  companion object {
    private val TAG = Log.tag(NetworkConnectionListener::class.java)
  }

  private val connectivityManager = ServiceUtil.getConnectivityManager(context)

  private val networkChangedCallback: ConnectivityManager.NetworkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onUnavailable() {
      super.onUnavailable()
      Log.d(TAG, "ConnectivityManager.NetworkCallback onUnavailable()")
      onNetworkLost { true }
    }

    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
      super.onBlockedStatusChanged(network, blocked)
      Log.d(TAG, "ConnectivityManager.NetworkCallback onBlockedStatusChanged()")
      onNetworkLost { blocked }
    }

    override fun onAvailable(network: Network) {
      super.onAvailable(network)
      Log.d(TAG, "ConnectivityManager.NetworkCallback onAvailable()")
      onNetworkLost { false }
    }

    override fun onLost(network: Network) {
      super.onLost(network)
      Log.d(TAG, "ConnectivityManager.NetworkCallback onLost()")
      onNetworkLost { true }
    }
  }

  private val connectionReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      Log.d(TAG, "BroadcastReceiver onReceive().")
      onNetworkLost { !NetworkConstraint.isMet(context) }
    }
  }

  fun register() {
    if (Build.VERSION.SDK_INT >= 28) {
      connectivityManager.registerDefaultNetworkCallback(networkChangedCallback)
    } else {
      context.registerReceiver(connectionReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
    }
  }

  fun unregister() {
    if (Build.VERSION.SDK_INT >= 28) {
      connectivityManager.unregisterNetworkCallback(networkChangedCallback)
    } else {
      context.unregisterReceiver(connectionReceiver)
    }
  }
}
