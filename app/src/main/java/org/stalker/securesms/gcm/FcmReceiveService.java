package org.stalker.securesms.gcm;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.signal.core.util.logging.Log;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.jobs.FcmRefreshJob;
import org.stalker.securesms.jobs.SubmitRateLimitPushChallengeJob;
import org.stalker.securesms.keyvalue.SignalStore;
import org.stalker.securesms.registration.PushChallengeRequest;
import org.stalker.securesms.util.NetworkUtil;
import org.stalker.securesms.util.SignalLocalMetrics;

import java.util.Locale;

public class FcmReceiveService extends FirebaseMessagingService {

  private static final String TAG = Log.tag(FcmReceiveService.class);

  @Override
  public void onMessageReceived(RemoteMessage remoteMessage) {
    Log.i(TAG, String.format(Locale.US,
                             "onMessageReceived() ID: %s, Delay: %d (Server offset: %d), Priority: %d, Original Priority: %d, Network: %s",
                             remoteMessage.getMessageId(),
                             (System.currentTimeMillis() - remoteMessage.getSentTime()),
                             SignalStore.misc().getLastKnownServerTimeOffset(),
                             remoteMessage.getPriority(),
                             remoteMessage.getOriginalPriority(),
                             NetworkUtil.getNetworkStatus(this)));

    String registrationChallenge = remoteMessage.getData().get("challenge");
    String rateLimitChallenge    = remoteMessage.getData().get("rateLimitChallenge");

    if (registrationChallenge != null) {
      handleRegistrationPushChallenge(registrationChallenge);
    } else if (rateLimitChallenge != null) {
      handleRateLimitPushChallenge(rateLimitChallenge);
    } else {
      handleReceivedNotification(ApplicationDependencies.getApplication(), remoteMessage);
    }
  }

  @Override
  public void onDeletedMessages() {
    Log.w(TAG, "onDeleteMessages() -- Messages may have been dropped. Doing a normal message fetch.");
    handleReceivedNotification(ApplicationDependencies.getApplication(), null);
  }

  @Override
  public void onNewToken(String token) {
    Log.i(TAG, "onNewToken()");

    if (!SignalStore.account().isRegistered()) {
      Log.i(TAG, "Got a new FCM token, but the user isn't registered.");
      return;
    }

    ApplicationDependencies.getJobManager().add(new FcmRefreshJob());
  }

  @Override
  public void onMessageSent(@NonNull String s) {
    Log.i(TAG, "onMessageSent()" + s);
  }

  @Override
  public void onSendError(@NonNull String s, @NonNull Exception e) {
    Log.w(TAG, "onSendError()", e);
  }

  private static void handleReceivedNotification(Context context, @Nullable RemoteMessage remoteMessage) {
    boolean highPriority = remoteMessage != null && remoteMessage.getPriority() == RemoteMessage.PRIORITY_HIGH;
    try {
      Log.d(TAG, String.format(Locale.US, "[handleReceivedNotification] API: %s, RemoteMessagePriority: %s", Build.VERSION.SDK_INT, remoteMessage != null ? remoteMessage.getPriority() : "n/a"));

      if (highPriority) {
        FcmFetchManager.startForegroundService(context);
      } else if (Build.VERSION.SDK_INT < 26) {
        FcmFetchManager.startBackgroundService(context);
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to start service.", e);
      SignalLocalMetrics.FcmServiceStartFailure.onFcmFailedToStart();
    }

    FcmFetchManager.enqueueFetch(context, highPriority);
  }

  private static void handleRegistrationPushChallenge(@NonNull String challenge) {
    Log.d(TAG, "Got a registration push challenge.");
    PushChallengeRequest.postChallengeResponse(challenge);
  }

  private static void handleRateLimitPushChallenge(@NonNull String challenge) {
    Log.d(TAG, "Got a rate limit push challenge.");
    ApplicationDependencies.getJobManager().add(new SubmitRateLimitPushChallengeJob(challenge));
  }
}