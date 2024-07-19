package org.stalker.securesms.avatar.photo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import org.stalker.securesms.avatar.Avatar
import org.stalker.securesms.avatar.AvatarBundler
import org.stalker.securesms.components.FragmentWrapperActivity

class PhotoEditorActivity : FragmentWrapperActivity() {

  override fun attachBaseContext(newBase: Context) {
    delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
    super.attachBaseContext(newBase)
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    super.onCreate(savedInstanceState, ready)

    supportFragmentManager.setFragmentResultListener(PhotoEditorFragment.REQUEST_KEY_EDIT, this) { _, bundle ->
      setResult(Activity.RESULT_OK, Intent().putExtras(bundle))
      finishAfterTransition()
    }
  }

  override fun getFragment(): Fragment = PhotoEditorFragment().apply {
    arguments = intent.extras
  }

  class Contract : ActivityResultContract<Avatar.Photo, Avatar.Photo?>() {
    override fun createIntent(context: Context, input: Avatar.Photo): Intent {
      return Intent(context, PhotoEditorActivity::class.java).apply {
        putExtras(PhotoEditorActivityArgs.Builder(AvatarBundler.bundlePhoto(input)).build().toBundle())
      }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Avatar.Photo? {
      val extras = intent?.extras
      if (resultCode != Activity.RESULT_OK || extras == null) {
        return null
      }

      return AvatarBundler.extractPhoto(extras)
    }
  }
}
