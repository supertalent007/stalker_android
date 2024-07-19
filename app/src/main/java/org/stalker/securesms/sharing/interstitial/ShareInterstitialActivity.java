package org.stalker.securesms.sharing.interstitial;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.annimon.stream.Stream;
import com.bumptech.glide.Glide;

import org.stalker.securesms.PassphraseRequiredActivity;
import org.stalker.securesms.R;
import org.stalker.securesms.components.LinkPreviewView;
import org.stalker.securesms.components.SelectionAwareEmojiEditText;
import org.stalker.securesms.linkpreview.LinkPreviewRepository;
import org.stalker.securesms.linkpreview.LinkPreviewViewModel;
import org.stalker.securesms.recipients.Recipient;
import org.stalker.securesms.sharing.MultiShareArgs;
import org.stalker.securesms.sharing.MultiShareDialogs;
import org.stalker.securesms.sharing.ShareFlowConstants;
import org.stalker.securesms.util.DynamicNoActionBarTheme;
import org.stalker.securesms.util.DynamicTheme;
import org.stalker.securesms.util.ViewUtil;
import org.stalker.securesms.util.text.AfterTextChanged;
import org.stalker.securesms.util.views.CircularProgressMaterialButton;

import java.util.Objects;

/**
 * Handles display and editing of a text message (with possible link preview) before it is forwarded
 * to multiple users.
 */
public class ShareInterstitialActivity extends PassphraseRequiredActivity {

  private static final String ARGS = "args";

  private ShareInterstitialViewModel     viewModel;
  private LinkPreviewViewModel           linkPreviewViewModel;
  private CircularProgressMaterialButton confirm;
  private RecyclerView                   contactsRecycler;
  private Toolbar                        toolbar;
  private LinkPreviewView                preview;

  private final DynamicTheme                      dynamicTheme = new DynamicNoActionBarTheme();
  private final ShareInterstitialSelectionAdapter adapter      = new ShareInterstitialSelectionAdapter();

  public static Intent createIntent(@NonNull Context context, @NonNull MultiShareArgs multiShareArgs) {
    Intent intent = new Intent(context, ShareInterstitialActivity.class);

    intent.putExtra(ARGS, multiShareArgs);

    return intent;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState, boolean ready) {
    dynamicTheme.onCreate(this);
    setContentView(R.layout.share_interstitial_activity);

    MultiShareArgs args = getIntent().getParcelableExtra(ARGS);

    initializeViewModels(args);
    initializeViews(args);
    initializeObservers();
  }

  @Override
  protected void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
  }

  private void initializeViewModels(@NonNull MultiShareArgs args) {
    ShareInterstitialRepository        repository = new ShareInterstitialRepository();
    ShareInterstitialViewModel.Factory factory    = new ShareInterstitialViewModel.Factory(args, repository);

    viewModel = new ViewModelProvider(this, factory).get(ShareInterstitialViewModel.class);

    LinkPreviewRepository        linkPreviewRepository       = new LinkPreviewRepository();
    LinkPreviewViewModel.Factory linkPreviewViewModelFactory = new LinkPreviewViewModel.Factory(linkPreviewRepository);

    linkPreviewViewModel = new ViewModelProvider(this, linkPreviewViewModelFactory).get(LinkPreviewViewModel.class);

    boolean hasSms = Stream.of(args.getRecipientSearchKeys())
                           .anyMatch(c -> {
                             Recipient recipient = Recipient.resolved(c.getRecipientId());
                             if (recipient.isDistributionList()) {
                               return false;
                             }

                             return !recipient.isRegistered();
                           });

    if (hasSms) {
      linkPreviewViewModel.onTransportChanged(true);
    }
  }

  private void initializeViews(@NonNull MultiShareArgs args) {
    confirm = findViewById(R.id.share_confirm);
    toolbar = findViewById(R.id.toolbar);
    preview = findViewById(R.id.link_preview);

    confirm.setOnClickListener(unused -> onConfirm());

    SelectionAwareEmojiEditText text = findViewById(R.id.text);

    toolbar.setNavigationOnClickListener(unused -> finish());

    text.addTextChangedListener(new AfterTextChanged(editable -> {
      linkPreviewViewModel.onTextChanged(this, editable.toString(), text.getSelectionStart(), text.getSelectionEnd());
      viewModel.onDraftTextChanged(editable.toString());
    }));

    //noinspection CodeBlock2Expr
    text.setOnSelectionChangedListener(((selStart, selEnd) -> {
      linkPreviewViewModel.onTextChanged(this, text.getText().toString(), text.getSelectionStart(), text.getSelectionEnd());
    }));

    preview.setCloseClickedListener(linkPreviewViewModel::onUserCancel);

    int defaultRadius = getResources().getDimensionPixelSize(R.dimen.thumbnail_default_radius);
    preview.setCorners(defaultRadius, defaultRadius);

    text.setText(args.getDraftText());
    ViewUtil.focusAndMoveCursorToEndAndOpenKeyboard(text);

    contactsRecycler = findViewById(R.id.selected_list);
    contactsRecycler.setAdapter(adapter);

    RecyclerView.ItemAnimator itemAnimator = Objects.requireNonNull(contactsRecycler.getItemAnimator());
    ShareFlowConstants.applySelectedContactsRecyclerAnimationSpeeds(itemAnimator);

    confirm.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
      int pad = Math.abs(v.getWidth() + ViewUtil.dpToPx(16));
      ViewUtil.setPaddingEnd(contactsRecycler, pad);
    });
  }

  private void initializeObservers() {
    viewModel.getRecipients().observe(this, models -> adapter.submitList(models,
                                                                         () -> contactsRecycler.scrollToPosition(models.size() - 1)));
    viewModel.hasDraftText().observe(this, this::handleHasDraftText);

    linkPreviewViewModel.getLinkPreviewState().observe(this, linkPreviewState -> {
      preview.setVisibility(View.VISIBLE);
      if (linkPreviewState.error != null) {
        preview.setNoPreview(linkPreviewState.error);
        viewModel.onLinkPreviewChanged(null);
      } else if (linkPreviewState.isLoading) {
        preview.setLoading();
        viewModel.onLinkPreviewChanged(null);
      } else if (linkPreviewState.linkPreview.isPresent()) {
        preview.setLinkPreview(Glide.with(this), linkPreviewState.linkPreview.get(), true);
        viewModel.onLinkPreviewChanged(linkPreviewState.linkPreview.get());
      } else if (!linkPreviewState.hasLinks()) {
        preview.setVisibility(View.GONE);
        viewModel.onLinkPreviewChanged(null);
      }
    });
  }

  private void handleHasDraftText(boolean hasDraftText) {
    confirm.setEnabled(hasDraftText);
    confirm.setAlpha(hasDraftText ? 1f : 0.5f);
  }

  private void onConfirm() {
    confirm.setSpinning();

    viewModel.send(results -> {
      MultiShareDialogs.displayResultDialog(this, results, () -> {
        setResult(RESULT_OK);
        finish();
      });
    });
  }
}
