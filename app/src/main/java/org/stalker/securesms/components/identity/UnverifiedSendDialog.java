package org.stalker.securesms.components.identity;

import android.content.Context;
import android.content.DialogInterface;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import org.stalker.securesms.R;
import org.stalker.securesms.crypto.ReentrantSessionLock;
import org.stalker.securesms.database.IdentityTable;
import org.stalker.securesms.database.model.IdentityRecord;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.signal.core.util.concurrent.SimpleTask;
import org.whispersystems.signalservice.api.SignalSessionLock;

import java.util.List;

public class UnverifiedSendDialog extends AlertDialog.Builder implements DialogInterface.OnClickListener {

  private final List<IdentityRecord> untrustedRecords;
  private final ResendListener       resendListener;

  public UnverifiedSendDialog(@NonNull Context context,
                              @NonNull String message,
                              @NonNull List<IdentityRecord> untrustedRecords,
                              @NonNull ResendListener resendListener)
  {
    super(context);
    this.untrustedRecords = untrustedRecords;
    this.resendListener   = resendListener;

    setTitle(R.string.UnverifiedSendDialog_send_message);
    setIcon(R.drawable.symbol_error_triangle_fill_24);
    setMessage(message);
    setPositiveButton(R.string.UnverifiedSendDialog_send, this);
    setNegativeButton(android.R.string.cancel, null);
  }

  @Override
  public void onClick(DialogInterface dialog, int which) {
    SimpleTask.run(() -> {
      try(SignalSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
        for (IdentityRecord identityRecord : untrustedRecords) {
          ApplicationDependencies.getProtocolStore().aci().identities().setVerified(identityRecord.getRecipientId(),
                                                                                    identityRecord.getIdentityKey(),
                                                                                    IdentityTable.VerifiedStatus.DEFAULT);
        }
      }
      return null;
    }, nothing -> resendListener.onResendMessage());
  }

  public interface ResendListener {
    public void onResendMessage();
  }
}
