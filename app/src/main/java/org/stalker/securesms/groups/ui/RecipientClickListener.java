package org.stalker.securesms.groups.ui;

import androidx.annotation.NonNull;

import org.stalker.securesms.recipients.Recipient;

public interface RecipientClickListener {
  void onClick(@NonNull Recipient recipient);
}
