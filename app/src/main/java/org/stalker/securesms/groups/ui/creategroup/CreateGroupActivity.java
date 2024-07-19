package org.stalker.securesms.groups.ui.creategroup;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.signal.core.util.DimensionUnit;
import org.signal.core.util.Stopwatch;
import org.signal.core.util.concurrent.SimpleTask;
import org.signal.core.util.logging.Log;
import org.stalker.securesms.ContactSelectionActivity;
import org.stalker.securesms.ContactSelectionListFragment;
import org.stalker.securesms.R;
import org.stalker.securesms.contacts.ContactSelectionDisplayMode;
import org.stalker.securesms.contacts.sync.ContactDiscovery;
import org.stalker.securesms.groups.ui.creategroup.details.AddGroupDetailsActivity;
import org.stalker.securesms.keyvalue.SignalStore;
import org.stalker.securesms.recipients.Recipient;
import org.stalker.securesms.recipients.RecipientId;
import org.stalker.securesms.recipients.RecipientRepository;
import org.stalker.securesms.recipients.ui.findby.FindByActivity;
import org.stalker.securesms.recipients.ui.findby.FindByMode;
import org.stalker.securesms.util.FeatureFlags;
import org.stalker.securesms.util.views.SimpleProgressDialog;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class CreateGroupActivity extends ContactSelectionActivity implements ContactSelectionListFragment.FindByCallback {

  private static final String TAG = Log.tag(CreateGroupActivity.class);

  private static final short REQUEST_CODE_ADD_DETAILS = 17275;

  private MaterialButton                     skip;
  private FloatingActionButton               next;
  private ActivityResultLauncher<FindByMode> findByActivityLauncher;


  public static Intent newIntent(@NonNull Context context) {
    Intent intent = new Intent(context, CreateGroupActivity.class);

    intent.putExtra(ContactSelectionListFragment.REFRESHABLE, false);
    intent.putExtra(ContactSelectionActivity.EXTRA_LAYOUT_RES_ID, R.layout.create_group_activity);

    int displayMode = ContactSelectionDisplayMode.FLAG_PUSH;

    intent.putExtra(ContactSelectionListFragment.DISPLAY_MODE, displayMode);
    intent.putExtra(ContactSelectionListFragment.SELECTION_LIMITS, FeatureFlags.groupLimits().excludingSelf());
    intent.putExtra(ContactSelectionListFragment.RV_PADDING_BOTTOM, (int) DimensionUnit.DP.toPixels(64f));
    intent.putExtra(ContactSelectionListFragment.RV_CLIP, false);

    return intent;
  }

  @Override
  public void onCreate(Bundle bundle, boolean ready) {
    super.onCreate(bundle, ready);
    assert getSupportActionBar() != null;
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);

    skip = findViewById(R.id.skip);
    next = findViewById(R.id.next);
    extendSkip();

    skip.setOnClickListener(v -> handleNextPressed());
    next.setOnClickListener(v -> handleNextPressed());

    findByActivityLauncher = registerForActivityResult(new FindByActivity.Contract(), result -> {
      if (result != null) {
        contactsFragment.addRecipientToSelectionIfAble(result);
      }
    });
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }

    return super.onOptionsItemSelected(item);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    if (requestCode == REQUEST_CODE_ADD_DETAILS && resultCode == RESULT_OK) {
      setResult(RESULT_OK);
      finish();
    } else {
      super.onActivityResult(requestCode, resultCode, data);
    }
  }

  @Override
  public void onBeforeContactSelected(boolean isFromUnknownSearchKey, @NonNull Optional<RecipientId> recipientId, String number, @NonNull Consumer<Boolean> callback) {
    if (contactsFragment.hasQueryFilter()) {
      getContactFilterView().clear();
    }

    shrinkSkip();

    if (recipientId.isPresent()) {
      callback.accept(true);
      return;
    }

    AlertDialog progress = SimpleProgressDialog.show(this);

    SimpleTask.run(getLifecycle(), () -> RecipientRepository.lookupNewE164(this, number), result -> {
      progress.dismiss();

      if (result instanceof RecipientRepository.LookupResult.Success) {
        callback.accept(true);
      } else if (result instanceof RecipientRepository.LookupResult.NotFound || result instanceof RecipientRepository.LookupResult.InvalidEntry) {
        new MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.NewConversationActivity__s_is_not_a_signal_user, number))
            .setPositiveButton(android.R.string.ok, null)
            .show();
        callback.accept(false);
      } else {
        new MaterialAlertDialogBuilder(this)
            .setMessage(R.string.NetworkFailure__network_error_check_your_connection_and_try_again)
            .setPositiveButton(android.R.string.ok, null)
            .show();
        callback.accept(false);
      }
    });
  }

  @Override
  public void onContactDeselected(@NonNull Optional<RecipientId> recipientId, String number) {
    if (contactsFragment.hasQueryFilter()) {
      getContactFilterView().clear();
    }

    if (contactsFragment.getSelectedContactsCount() == 0) {
      extendSkip();
    }
  }

  @Override
  public void onSelectionChanged() {
    int selectedMembers = contactsFragment.getSelectedMembersSize();
    int selectedContactsCount = contactsFragment.getTotalMemberCount();
    if (selectedContactsCount == 0) {
      getToolbar().setTitle(getString(R.string.CreateGroupActivity__select_members));
    } else {
      getToolbar().setTitle(getResources().getQuantityString(R.plurals.CreateGroupActivity__d_members, selectedMembers, selectedMembers));
    }
  }

  @Override
  public void onFindByPhoneNumber() {
    findByActivityLauncher.launch(FindByMode.PHONE_NUMBER);
  }

  @Override
  public void onFindByUsername() {
    findByActivityLauncher.launch(FindByMode.USERNAME);
  }

  private void extendSkip() {
    skip.setVisibility(View.VISIBLE);
    next.setVisibility(View.GONE);
  }

  private void shrinkSkip() {
    skip.setVisibility(View.GONE);
    next.setVisibility(View.VISIBLE);
  }

  private void handleNextPressed() {
    Stopwatch                              stopwatch         = new Stopwatch("Recipient Refresh");
    SimpleProgressDialog.DismissibleDialog dismissibleDialog = SimpleProgressDialog.showDelayed(this);

    SimpleTask.run(getLifecycle(), () -> {
      List<RecipientId> ids = contactsFragment.getSelectedContacts()
                                              .stream()
                                              .map(selectedContact -> selectedContact.getOrCreateRecipientId(this))
                                              .collect(Collectors.toList());

      List<Recipient> resolved = Recipient.resolvedList(ids);

      stopwatch.split("resolve");

      Set<Recipient> registeredChecks = resolved.stream()
                                                .filter(r -> !r.isRegistered() || !r.getHasServiceId())
                                                .collect(Collectors.toSet());

      Log.i(TAG, "Need to do " + registeredChecks.size() + " registration checks.");

      for (Recipient recipient : registeredChecks) {
        try {
          ContactDiscovery.refresh(this, recipient, false, TimeUnit.SECONDS.toMillis(10));
        } catch (IOException e) {
          Log.w(TAG, "Failed to refresh registered status for " + recipient.getId(), e);
        }
      }

      stopwatch.split("registered");

      return Recipient.resolvedList(ids);
    }, recipients -> {
      dismissibleDialog.dismiss();
      stopwatch.stop(TAG);

      List<Recipient> notRegistered = recipients.stream().filter(r -> !r.isRegistered() || !r.getHasServiceId()).collect(Collectors.toList());

      if (notRegistered.isEmpty()) {
        startActivityForResult(AddGroupDetailsActivity.newIntent(this, recipients.stream().map(Recipient::getId).collect(Collectors.toList())), REQUEST_CODE_ADD_DETAILS);
      } else {
        String notRegisteredNames = notRegistered.stream().map(r -> r.getDisplayName(this)).collect(Collectors.joining(", "));
        new MaterialAlertDialogBuilder(this)
            .setMessage(getResources().getQuantityString(R.plurals.CreateGroupActivity_not_signal_users, notRegistered.size(), notRegisteredNames))
            .setPositiveButton(android.R.string.ok, null)
            .show();
      }
    });
  }
}
