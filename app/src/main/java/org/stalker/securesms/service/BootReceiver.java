package org.stalker.securesms.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.jobs.MessageFetchJob;

public class BootReceiver extends BroadcastReceiver {

  @Override
  public void onReceive(Context context, Intent intent) {
    ApplicationDependencies.getJobManager().add(new MessageFetchJob());
  }
}
