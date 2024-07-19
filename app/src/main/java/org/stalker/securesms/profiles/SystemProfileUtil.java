package org.stalker.securesms.profiles;


import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.ContactsContract;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.concurrent.ListenableFuture;
import org.signal.core.util.concurrent.SettableFuture;
import org.signal.core.util.logging.Log;
import org.stalker.securesms.mms.MediaConstraints;
import org.stalker.securesms.util.BitmapDecodingException;
import org.stalker.securesms.util.BitmapUtil;

public class SystemProfileUtil {

  private static final String TAG = Log.tag(SystemProfileUtil.class);

  @SuppressLint("StaticFieldLeak")
  public  static ListenableFuture<byte[]> getSystemProfileAvatar(final @NonNull Context context, MediaConstraints mediaConstraints) {
    SettableFuture<byte[]> future = new SettableFuture<>();

    new AsyncTask<Void, Void, byte[]>() {
      @Override
      protected @Nullable byte[] doInBackground(Void... params) {
        try (Cursor cursor = context.getContentResolver().query(ContactsContract.Profile.CONTENT_URI, null, null, null, null)) {
          while (cursor != null && cursor.moveToNext()) {
            String photoUri = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Profile.PHOTO_URI));

            if (!TextUtils.isEmpty(photoUri)) {
              try {
                BitmapUtil.ScaleResult result = BitmapUtil.createScaledBytes(context, Uri.parse(photoUri), mediaConstraints);
                return result.getBitmap();
              } catch (BitmapDecodingException e) {
                Log.w(TAG, e);
              }
            }
          }
        } catch (SecurityException se) {
          Log.w(TAG, se);
        }

        return null;
      }

      @Override
      protected void onPostExecute(@Nullable byte[] result) {
        future.set(result);
      }

    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

    return future;
  }

  @SuppressLint("StaticFieldLeak")
  public static ListenableFuture<String> getSystemProfileName(final @NonNull Context context) {
    SettableFuture<String> future = new SettableFuture<>();

    new AsyncTask<Void, Void, String>() {
      @Override
      protected String doInBackground(Void... params) {
        String name = null;

        try (Cursor cursor =  context.getContentResolver().query(ContactsContract.Profile.CONTENT_URI, null, null, null, null)) {
          if (cursor != null && cursor.moveToNext()) {
            name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Profile.DISPLAY_NAME));
          }
        } catch (SecurityException se) {
          Log.w(TAG, se);
        }

        if (name == null) {
          AccountManager accountManager = AccountManager.get(context);
          Account[]      accounts       = accountManager.getAccountsByType("com.google");

          for (Account account : accounts) {
            if (!TextUtils.isEmpty(account.name)) {
              if (account.name.contains("@")) {
                name = account.name.substring(0, account.name.indexOf("@")).replace('.', ' ');
              } else {
                name = account.name.replace('.', ' ');
              }

              break;
            }
          }
        }

        return name;
      }

      @Override
      protected void onPostExecute(@Nullable String result) {
        future.set(result);
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

    return future;
  }


}
