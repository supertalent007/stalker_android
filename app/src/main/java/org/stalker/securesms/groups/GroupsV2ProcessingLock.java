package org.stalker.securesms.groups;

import androidx.annotation.WorkerThread;

import org.signal.core.util.ThreadUtil;
import org.signal.core.util.logging.Log;
import org.stalker.securesms.crypto.ReentrantSessionLock;
import org.stalker.securesms.database.SignalDatabase;
import org.stalker.securesms.util.FeatureFlags;

import java.io.Closeable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class GroupsV2ProcessingLock {

  private static final String TAG = Log.tag(GroupsV2ProcessingLock.class);

  private GroupsV2ProcessingLock() {
  }

  private static final ReentrantLock lock = new ReentrantLock();

  @WorkerThread
  public static Closeable acquireGroupProcessingLock() throws GroupChangeBusyException {
    if (FeatureFlags.internalUser()) {
      if (!lock.isHeldByCurrentThread()) {
        if (SignalDatabase.inTransaction()) {
          throw new AssertionError("Tried to acquire the group lock inside of a database transaction!");
        }
        if (ReentrantSessionLock.INSTANCE.isHeldByCurrentThread()) {
          throw new AssertionError("Tried to acquire the group lock inside of the ReentrantSessionLock!!");
        }
      }
    }
    return acquireGroupProcessingLock(5000);
  }

  @WorkerThread
  public static Closeable acquireGroupProcessingLock(long timeoutMs) throws GroupChangeBusyException {
    ThreadUtil.assertNotMainThread();

    try {
      if (!lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
        throw new GroupChangeBusyException("Failed to get a lock on the group processing in the timeout period");
      }
      return lock::unlock;
    } catch (InterruptedException e) {
      Log.w(TAG, e);
      throw new GroupChangeBusyException(e);
    }
  }
}