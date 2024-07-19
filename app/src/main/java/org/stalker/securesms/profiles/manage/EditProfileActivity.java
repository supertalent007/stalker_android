package org.stalker.securesms.profiles.manage;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.NavDirections;
import androidx.navigation.NavGraph;
import androidx.navigation.fragment.NavHostFragment;

import org.stalker.securesms.PassphraseRequiredActivity;
import org.stalker.securesms.R;
import org.stalker.securesms.reactions.any.ReactWithAnyEmojiBottomSheetDialogFragment;
import org.stalker.securesms.util.DynamicNoActionBarTheme;
import org.stalker.securesms.util.DynamicTheme;
import org.stalker.securesms.util.navigation.SafeNavigation;

/**
 * Activity for editing your profile after you're already registered.
 */
public class EditProfileActivity extends PassphraseRequiredActivity implements ReactWithAnyEmojiBottomSheetDialogFragment.Callback {

  public static final int RESULT_BECOME_A_SUSTAINER = 12382;

  private final DynamicTheme dynamicTheme = new DynamicNoActionBarTheme();

  public static final String START_AT_USERNAME = "start_at_username";
  public static final String START_AT_AVATAR   = "start_at_avatar";

  public static @NonNull Intent getIntent(@NonNull Context context) {
    return new Intent(context, EditProfileActivity.class);
  }

  public static @NonNull Intent getIntentForUsernameEdit(@NonNull Context context) {
    Intent intent = new Intent(context, EditProfileActivity.class);
    intent.putExtra(START_AT_USERNAME, true);
    return intent;
  }

  public static @NonNull Intent getIntentForAvatarEdit(@NonNull Context context) {
    Intent intent = new Intent(context, EditProfileActivity.class);
    intent.putExtra(START_AT_AVATAR, true);
    return intent;
  }

  @Override
  public void onCreate(Bundle bundle, boolean ready) {
    dynamicTheme.onCreate(this);

    setContentView(R.layout.edit_profile_activity);

    if (bundle == null) {
      Bundle   extras = getIntent().getExtras();

      //noinspection ConstantConditions
      NavController navController = ((NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment)).getNavController();

      NavGraph graph  = navController.getGraph();

      navController.setGraph(graph, extras != null ? extras : new Bundle());

      if (extras != null && extras.getBoolean(START_AT_USERNAME, false)) {
        NavDirections action = EditProfileFragmentDirections.actionManageUsername();
        SafeNavigation.safeNavigate(navController, action);
      }

      if (extras != null && extras.getBoolean(START_AT_AVATAR, false)) {
        NavDirections action = EditProfileFragmentDirections.actionManageProfileFragmentToAvatarPicker(null, null);
        SafeNavigation.safeNavigate(navController, action);
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
  }

  @Override
  public void onReactWithAnyEmojiDialogDismissed() {
  }

  @Override
  public void onReactWithAnyEmojiSelected(@NonNull String emoji) {
    NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().getPrimaryNavigationFragment();
    Fragment        activeFragment  = navHostFragment.getChildFragmentManager().getPrimaryNavigationFragment();

    if (activeFragment instanceof EmojiController) {
      ((EmojiController) activeFragment).onEmojiSelected(emoji);
    }
  }

  interface EmojiController {
    void onEmojiSelected(@NonNull String emoji);
  }
}
