package org.stalker.securesms.groups.ui;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.stalker.securesms.R;
import org.stalker.securesms.util.FeatureFlags;

public final class GroupLimitDialog {

  public static void showHardLimitMessage(@NonNull Context context) {
    new MaterialAlertDialogBuilder(context)
                   .setTitle(R.string.ContactSelectionListFragment_maximum_group_size_reached)
                   .setMessage(context.getString(R.string.ContactSelectionListFragment_signal_groups_can_have_a_maximum_of_d_members, FeatureFlags.groupLimits().getHardLimit()))
                   .setPositiveButton(android.R.string.ok, null)
                   .show();
  }

  public static void showRecommendedLimitMessage(@NonNull Context context) {
    new MaterialAlertDialogBuilder(context)
                   .setTitle(R.string.ContactSelectionListFragment_recommended_member_limit_reached)
                   .setMessage(context.getString(R.string.ContactSelectionListFragment_signal_groups_perform_best_with_d_members_or_fewer, FeatureFlags.groupLimits().getRecommendedLimit()))
                   .setPositiveButton(android.R.string.ok, null)
                   .show();
  }
}
