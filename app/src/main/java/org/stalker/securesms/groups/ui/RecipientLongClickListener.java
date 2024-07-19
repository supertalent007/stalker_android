package org.stalker.securesms.groups.ui;

import androidx.annotation.NonNull;

import org.stalker.securesms.recipients.Recipient;

public interface RecipientLongClickListener {
  boolean onLongClick(@NonNull Recipient recipient);
}
