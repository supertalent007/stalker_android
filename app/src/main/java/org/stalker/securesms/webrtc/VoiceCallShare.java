package org.stalker.securesms.webrtc;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.TextUtils;

import org.signal.core.util.logging.Log;
import org.stalker.securesms.WebRtcCallActivity;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.recipients.Recipient;
import org.signal.core.util.concurrent.SimpleTask;

public class VoiceCallShare extends Activity {
  
  private static final String TAG = Log.tag(VoiceCallShare.class);

  private static final String VIDEO_CALL_MIME_TYPE = "vnd.android.cursor.item/vnd.org.stalker.securesms.videocall";
  
  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);

    if (getIntent().getData() != null && "content".equals(getIntent().getData().getScheme())) {
      Cursor cursor = null;
      
      try {
        cursor = getContentResolver().query(getIntent().getData(), null, null, null, null);

        if (cursor != null && cursor.moveToNext()) {
          String destination = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.Data.DATA1));

          SimpleTask.run(() -> Recipient.external(this, destination), recipient -> {
            if (!TextUtils.isEmpty(destination)) {
              if (VIDEO_CALL_MIME_TYPE.equals(getIntent().getType())) {
                ApplicationDependencies.getSignalCallManager().startOutgoingVideoCall(recipient);
              } else {
                ApplicationDependencies.getSignalCallManager().startOutgoingAudioCall(recipient);
              }

              Intent activityIntent = new Intent(this, WebRtcCallActivity.class);
              activityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
              startActivity(activityIntent);
            }
          });
        }
      } finally {
        if (cursor != null) cursor.close();
      }
    }
    
    finish();
  }
}
