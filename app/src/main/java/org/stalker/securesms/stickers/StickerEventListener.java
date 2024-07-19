package org.stalker.securesms.stickers;

import androidx.annotation.NonNull;

import org.stalker.securesms.database.model.StickerRecord;

public interface StickerEventListener {
  void onStickerSelected(@NonNull StickerRecord sticker);

  void onStickerManagementClicked();
}
