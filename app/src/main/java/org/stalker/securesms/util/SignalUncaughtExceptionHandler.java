package org.stalker.securesms.util;

import android.database.sqlite.SQLiteDatabaseCorruptException;

import androidx.annotation.NonNull;

import org.signal.core.util.ExceptionUtil;
import org.signal.core.util.logging.Log;
import org.stalker.securesms.database.LogDatabase;
import org.stalker.securesms.database.SearchTable;
import org.stalker.securesms.database.SignalDatabase;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.keyvalue.SignalStore;

import java.io.IOException;

import javax.net.ssl.SSLException;

import io.reactivex.rxjava3.exceptions.OnErrorNotImplementedException;

public class SignalUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

  private static final String TAG = Log.tag(SignalUncaughtExceptionHandler.class);

  private final Thread.UncaughtExceptionHandler originalHandler;

  public SignalUncaughtExceptionHandler(@NonNull Thread.UncaughtExceptionHandler originalHandler) {
    this.originalHandler = originalHandler;
  }

  @Override
  public void uncaughtException(@NonNull Thread t, @NonNull Throwable e) {
    // Seeing weird situations where SSLExceptions aren't being caught as IOExceptions
    if (e instanceof SSLException) {
      if (e instanceof IOException) {
        Log.w(TAG, "Uncaught SSLException! It *is* an IOException!", e);
      } else {
        Log.w(TAG, "Uncaught SSLException! It is *not* an IOException!", e);
      }
      return;
    }

    if (e instanceof SQLiteDatabaseCorruptException) {
      if (e.getMessage().indexOf("message_fts") >= 0) {
        Log.w(TAG, "FTS corrupted! Resetting FTS index.");
        SignalDatabase.messageSearch().fullyResetTables();
      } else {
        Log.w(TAG, "Some non-FTS related corruption?");
      }
    }

    if (e instanceof OnErrorNotImplementedException && e.getCause() != null) {
      e = e.getCause();
    }

    String exceptionName = e.getClass().getCanonicalName();
    if (exceptionName == null) {
      exceptionName = e.getClass().getName();
    }

    Log.e(TAG, "", e, true);
    LogDatabase.getInstance(ApplicationDependencies.getApplication()).crashes().saveCrash(System.currentTimeMillis(), exceptionName, e.getMessage(), ExceptionUtil.convertThrowableToString(e));
    SignalStore.blockUntilAllWritesFinished();
    Log.blockUntilAllWritesFinished();
    ApplicationDependencies.getJobManager().flush();
    originalHandler.uncaughtException(t, ExceptionUtil.joinStackTraceAndMessage(e));
  }
}
