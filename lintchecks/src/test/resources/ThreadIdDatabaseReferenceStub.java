package org.stalker.securesms.database;

interface ThreadIdDatabaseReference {
  void remapThread(long fromId, long toId);
}
