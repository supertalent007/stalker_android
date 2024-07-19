package org.stalker.securesms.linkpreview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import org.signal.core.util.Hex;
import org.signal.core.util.Result;
import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.util.Pair;
import org.signal.libsignal.zkgroup.VerificationFailedException;
import org.signal.libsignal.zkgroup.groups.GroupMasterKey;
import org.signal.ringrtc.CallLinkRootKey;
import org.signal.storageservice.protos.groups.local.DecryptedGroupJoinInfo;
import org.stalker.securesms.R;
import org.stalker.securesms.attachments.Attachment;
import org.stalker.securesms.attachments.UriAttachment;
import org.stalker.securesms.calls.links.CallLinks;
import org.stalker.securesms.database.AttachmentTable;
import org.stalker.securesms.database.SignalDatabase;
import org.stalker.securesms.database.model.GroupRecord;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.groups.GroupId;
import org.stalker.securesms.groups.GroupManager;
import org.stalker.securesms.groups.v2.GroupInviteLinkUrl;
import org.stalker.securesms.jobs.AvatarGroupsV2DownloadJob;
import org.stalker.securesms.keyvalue.SignalStore;
import org.stalker.securesms.linkpreview.LinkPreviewUtil.OpenGraph;
import org.stalker.securesms.mms.PushMediaConstraints;
import org.stalker.securesms.net.CallRequestController;
import org.stalker.securesms.net.CompositeRequestController;
import org.stalker.securesms.net.RequestController;
import org.stalker.securesms.net.UserAgentInterceptor;
import org.stalker.securesms.profiles.AvatarHelper;
import org.stalker.securesms.providers.BlobProvider;
import org.stalker.securesms.recipients.Recipient;
import org.stalker.securesms.service.webrtc.links.CallLinkCredentials;
import org.stalker.securesms.service.webrtc.links.ReadCallLinkResult;
import org.stalker.securesms.stickers.StickerRemoteUri;
import org.stalker.securesms.stickers.StickerUrl;
import org.stalker.securesms.util.AvatarUtil;
import org.stalker.securesms.util.BitmapDecodingException;
import org.stalker.securesms.util.ByteUnit;
import org.stalker.securesms.util.ImageCompressionUtil;
import org.stalker.securesms.util.LinkUtil;
import org.stalker.securesms.util.MediaUtil;
import org.stalker.securesms.util.OkHttpUtil;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;
import org.whispersystems.signalservice.api.groupsv2.GroupLinkNotActiveException;
import org.whispersystems.signalservice.api.messages.SignalServiceStickerManifest;
import org.whispersystems.signalservice.api.messages.SignalServiceStickerManifest.StickerInfo;
import org.whispersystems.signalservice.api.util.OptionalUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class LinkPreviewRepository {

  private static final String TAG = Log.tag(LinkPreviewRepository.class);

  private static final CacheControl NO_CACHE = new CacheControl.Builder().noCache().build();

  private static final long FAILSAFE_MAX_TEXT_SIZE  = ByteUnit.MEGABYTES.toBytes(2);
  private static final long FAILSAFE_MAX_IMAGE_SIZE = ByteUnit.MEGABYTES.toBytes(2);

  private final OkHttpClient client;

  public LinkPreviewRepository() {
    this.client = new OkHttpClient.Builder()
                                  .cache(null)
                                  .addInterceptor(new UserAgentInterceptor("WhatsApp/2"))
                                  .build();
  }

  public @NonNull Single<Result<LinkPreview, Error>> getLinkPreview(@NonNull String url) {
    return Single.<Result<LinkPreview, Error>>create(emitter -> {
      RequestController controller = getLinkPreview(ApplicationDependencies.getApplication(),
                                                    url,
                                                    new Callback() {
                                                      @Override
                                                      public void onSuccess(@NonNull LinkPreview linkPreview) {
                                                        emitter.onSuccess(Result.success(linkPreview));
                                                      }

                                                      @Override
                                                      public void onError(@NonNull Error error) {
                                                        emitter.onSuccess(Result.failure(error));
                                                      }
                                                    });

      if (controller != null) {
        emitter.setCancellable(controller::cancel);
      }
    }).subscribeOn(Schedulers.io());
  }

  @Nullable RequestController getLinkPreview(@NonNull Context context,
                                             @NonNull String url,
                                             @NonNull Callback callback)
  {
    if (!SignalStore.settings().isLinkPreviewsEnabled()) {
      throw new IllegalStateException();
    }

    CompositeRequestController compositeController = new CompositeRequestController();

    if (!LinkUtil.isValidPreviewUrl(url)) {
      Log.w(TAG, "Tried to get a link preview for a non-whitelisted domain.");
      callback.onError(Error.PREVIEW_NOT_AVAILABLE);
      return compositeController;
    }

    RequestController metadataController;

    if (StickerUrl.isValidShareLink(url)) {
      metadataController = fetchStickerPackLinkPreview(context, url, callback);
    } else if (GroupInviteLinkUrl.isGroupLink(url)) {
      metadataController = fetchGroupLinkPreview(context, url, callback);
    } else if (CallLinks.isCallLink(url)) {
      metadataController = fetchCallLinkPreview(context, url, callback);
    } else {
      metadataController = fetchMetadata(url, metadata -> {
        if (metadata.isEmpty()) {
          callback.onError(Error.PREVIEW_NOT_AVAILABLE);
          return;
        }

        if (!metadata.getImageUrl().isPresent()) {
          callback.onSuccess(new LinkPreview(url, metadata.getTitle().orElse(""), metadata.getDescription().orElse(""), metadata.getDate(), Optional.empty()));
          return;
        }

        RequestController imageController = fetchThumbnail(metadata.getImageUrl().get(), attachment -> {
          if (!metadata.getTitle().isPresent() && !attachment.isPresent()) {
            callback.onError(Error.PREVIEW_NOT_AVAILABLE);
          } else {
            callback.onSuccess(new LinkPreview(url, metadata.getTitle().orElse(""), metadata.getDescription().orElse(""), metadata.getDate(), attachment));
          }
        });

        compositeController.addController(imageController);
      });
    }

    compositeController.addController(metadataController);
    return compositeController;
  }

  private @NonNull RequestController fetchMetadata(@NonNull String url, Consumer<Metadata> callback) {
    Call call = client.newCall(new Request.Builder().url(url).cacheControl(NO_CACHE).build());

    call.enqueue(new okhttp3.Callback() {
      @Override
      public void onFailure(@NonNull Call call, @NonNull IOException e) {
        Log.w(TAG, "Request failed.", e);
        callback.accept(Metadata.empty());
      }

      @Override
      public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
        if (!response.isSuccessful()) {
          Log.w(TAG, "Non-successful response. Code: " + response.code());
          callback.accept(Metadata.empty());
          return;
        } else if (response.body() == null) {
          Log.w(TAG, "No response body.");
          callback.accept(Metadata.empty());
          return;
        }

        String body;
        try {
          body = OkHttpUtil.readAsString(response.body(), FAILSAFE_MAX_TEXT_SIZE);
        } catch (IOException e) {
          Log.w(TAG, "Failed to read body", e);
          callback.accept(Metadata.empty());
          return;
        }

        OpenGraph        openGraph   = LinkPreviewUtil.parseOpenGraphFields(body);
        Optional<String> title       = openGraph.getTitle();
        Optional<String> description = openGraph.getDescription();
        Optional<String> imageUrl    = openGraph.getImageUrl();
        long             date        = openGraph.getDate();

        if (imageUrl.isPresent() && !LinkUtil.isValidPreviewUrl(imageUrl.get())) {
          Log.i(TAG, "Image URL was invalid or for a non-whitelisted domain. Skipping.");
          imageUrl = Optional.empty();
        }

        callback.accept(new Metadata(title, description, date, imageUrl));
      }
    });

    return new CallRequestController(call);
  }

  private @NonNull RequestController fetchThumbnail(@NonNull String imageUrl, @NonNull Consumer<Optional<Attachment>> callback) {
    Call                  call       = client.newCall(new Request.Builder().url(imageUrl).build());
    CallRequestController controller = new CallRequestController(call);

    SignalExecutors.UNBOUNDED.execute(() -> {
      try (Response response = call.execute()) {
        if (!response.isSuccessful() || response.body() == null) {
          callback.accept(Optional.empty());
          return;
        }

        InputStream bodyStream = response.body().byteStream();
        controller.setStream(bodyStream);

        byte[]                           data        = OkHttpUtil.readAsBytes(bodyStream, FAILSAFE_MAX_IMAGE_SIZE);
        Bitmap                           bitmap      = BitmapFactory.decodeByteArray(data, 0, data.length);
        Optional<Attachment>             thumbnail   = Optional.empty();
        PushMediaConstraints.MediaConfig mediaConfig = PushMediaConstraints.MediaConfig.getDefault(ApplicationDependencies.getApplication());

        if (bitmap != null) {
          for (final int maxDimension : mediaConfig.getImageSizeTargets()) {
            ImageCompressionUtil.Result result = ImageCompressionUtil.compressWithinConstraints(
                ApplicationDependencies.getApplication(),
                MediaUtil.IMAGE_JPEG,
                bitmap,
                maxDimension,
                mediaConfig.getMaxImageFileSize(),
                mediaConfig.getImageQualitySetting()
            );

            if (result != null) {
              thumbnail = Optional.of(bytesToAttachment(result.getData(), result.getWidth(), result.getHeight(), result.getMimeType()));
              break;
            }
          }
        }

        if (bitmap != null) bitmap.recycle();

        callback.accept(thumbnail);
      } catch (IOException | IllegalArgumentException | BitmapDecodingException e) {
        Log.w(TAG, "Exception during link preview image retrieval.", e);
        controller.cancel();
        callback.accept(Optional.empty());
      }
    });

    return controller;
  }

  private static RequestController fetchStickerPackLinkPreview(@NonNull Context context,
                                                               @NonNull String packUrl,
                                                               @NonNull Callback callback)
  {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        Pair<String, String> stickerParams = StickerUrl.parseShareLink(packUrl).orElse(new Pair<>("", ""));
        String               packIdString  = stickerParams.first();
        String               packKeyString = stickerParams.second();
        byte[]               packIdBytes   = Hex.fromStringCondensed(packIdString);
        byte[]               packKeyBytes  = Hex.fromStringCondensed(packKeyString);

        SignalServiceMessageReceiver receiver = ApplicationDependencies.getSignalServiceMessageReceiver();
        SignalServiceStickerManifest manifest = receiver.retrieveStickerManifest(packIdBytes, packKeyBytes);

        String                title        = OptionalUtil.or(manifest.getTitle(), manifest.getAuthor()).orElse("");
        Optional<StickerInfo> firstSticker = Optional.ofNullable(manifest.getStickers().size() > 0 ? manifest.getStickers().get(0) : null);
        Optional<StickerInfo> cover        = OptionalUtil.or(manifest.getCover(), firstSticker);

        if (cover.isPresent()) {
          Bitmap bitmap = Glide.with(context).asBitmap()
                                                .load(new StickerRemoteUri(packIdString, packKeyString, cover.get().getId()))
                                                .skipMemoryCache(true)
                                                .diskCacheStrategy(DiskCacheStrategy.NONE)
                                                .centerInside()
                                                .submit(512, 512)
                                                .get();

          Optional<Attachment> thumbnail = bitmapToAttachment(bitmap, Bitmap.CompressFormat.WEBP, MediaUtil.IMAGE_WEBP);

          callback.onSuccess(new LinkPreview(packUrl, title, "", 0, thumbnail));
        } else {
          callback.onError(Error.PREVIEW_NOT_AVAILABLE);
        }
      } catch (IOException | InvalidMessageException | ExecutionException | InterruptedException e) {
        Log.w(TAG, "Failed to fetch sticker pack link preview.");
        callback.onError(Error.PREVIEW_NOT_AVAILABLE);
      }
    });

    return () -> Log.i(TAG, "Cancelled sticker pack link preview fetch -- no effect.");
  }

  private static RequestController fetchCallLinkPreview(@NonNull Context context,
                                                        @NonNull String callLinkUrl,
                                                        @NonNull Callback callback) {

    CallLinkRootKey callLinkRootKey = CallLinks.parseUrl(callLinkUrl);
    if (callLinkRootKey == null) {
      callback.onError(Error.PREVIEW_NOT_AVAILABLE);
      return () -> { };
    }

    Disposable disposable = ApplicationDependencies.getSignalCallManager()
                                                   .getCallLinkManager()
                                                   .readCallLink(new CallLinkCredentials(callLinkRootKey.getKeyBytes(), null))
                                                   .observeOn(Schedulers.io())
                                                   .subscribe(
                                                        result -> {
                                                          if (result instanceof ReadCallLinkResult.Success) {
                                                            ReadCallLinkResult.Success success = (ReadCallLinkResult.Success) result;
                                                            Log.i(TAG, "Successfully read call link.");

                                                            if (((ReadCallLinkResult.Success) result).getCallLinkState().hasBeenRevoked()) {
                                                              Log.i(TAG, "Call link has been revoked.");
                                                              callback.onError(Error.PREVIEW_NOT_AVAILABLE);
                                                              return;
                                                            }

                                                            // Note: thumbnails are generated recv-side using the CallLinkRootKey
                                                            callback.onSuccess(new LinkPreview(
                                                                callLinkUrl,
                                                                success.getCallLinkState().getName(),
                                                                "",
                                                                0,
                                                                Optional.empty()
                                                            ));
                                                          } else {
                                                            ReadCallLinkResult.Failure failure = (ReadCallLinkResult.Failure) result;
                                                            Log.w(TAG, "Failed to read call link: " + failure);
                                                            callback.onError(Error.PREVIEW_NOT_AVAILABLE);
                                                          }
                                                        },
                                                        error -> {
                                                          Log.w(TAG, "An error occurred: ", error);
                                                          callback.onError(Error.PREVIEW_NOT_AVAILABLE);
                                                        }
                                                    );

    return disposable::dispose;
  }

  private static RequestController fetchGroupLinkPreview(@NonNull Context context,
                                                         @NonNull String groupUrl,
                                                         @NonNull Callback callback)
  {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        GroupInviteLinkUrl groupInviteLinkUrl = GroupInviteLinkUrl.fromUri(groupUrl);
        if (groupInviteLinkUrl == null) {
          throw new AssertionError();
        }

        GroupMasterKey                      groupMasterKey = groupInviteLinkUrl.getGroupMasterKey();
        GroupId.V2            groupId = GroupId.v2(groupMasterKey);
        Optional<GroupRecord> group   = SignalDatabase.groups().getGroup(groupId);

        if (group.isPresent()) {
          Log.i(TAG, "Creating preview for locally available group");

          GroupRecord groupRecord = group.get();
          String      title       = groupRecord.getTitle();
          int                       memberCount = groupRecord.getMembers().size();
          String                    description = getMemberCountDescription(context, memberCount);
          Optional<Attachment>      thumbnail   = Optional.empty();

          if (AvatarHelper.hasAvatar(context, groupRecord.getRecipientId())) {
            Recipient recipient = Recipient.resolved(groupRecord.getRecipientId());
            Bitmap    bitmap    = AvatarUtil.loadIconBitmapSquareNoCache(context, recipient, 512, 512);

            thumbnail = bitmapToAttachment(bitmap, Bitmap.CompressFormat.WEBP, MediaUtil.IMAGE_WEBP);
          }

          callback.onSuccess(new LinkPreview(groupUrl, title, description, 0, thumbnail));
        } else {
          Log.i(TAG, "Group is not locally available for preview generation, fetching from server");

          DecryptedGroupJoinInfo joinInfo    = GroupManager.getGroupJoinInfoFromServer(context, groupMasterKey, groupInviteLinkUrl.getPassword());
          String                 description = getMemberCountDescription(context, joinInfo.memberCount);
          Optional<Attachment>   thumbnail   = Optional.empty();
          byte[]                 avatarBytes = AvatarGroupsV2DownloadJob.downloadGroupAvatarBytes(context, groupMasterKey, joinInfo.avatar);

          if (avatarBytes != null) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.length);

            thumbnail = bitmapToAttachment(bitmap, Bitmap.CompressFormat.WEBP, MediaUtil.IMAGE_WEBP);

            if (bitmap != null) bitmap.recycle();
          }

          callback.onSuccess(new LinkPreview(groupUrl, joinInfo.title, description, 0, thumbnail));
        }
      } catch (ExecutionException | InterruptedException | IOException | VerificationFailedException e) {
        Log.w(TAG, "Failed to fetch group link preview.", e);
        callback.onError(Error.PREVIEW_NOT_AVAILABLE);
      } catch (GroupInviteLinkUrl.InvalidGroupLinkException | GroupInviteLinkUrl.UnknownGroupLinkVersionException e) {
        Log.w(TAG, "Bad group link.", e);
        callback.onError(Error.PREVIEW_NOT_AVAILABLE);
      } catch (GroupLinkNotActiveException e) {
        Log.w(TAG, "Group link not active.", e);
        callback.onError(Error.GROUP_LINK_INACTIVE);
      }
    });

    return () -> Log.i(TAG, "Cancelled group link preview fetch -- no effect.");
  }

  private static @NonNull String getMemberCountDescription(@NonNull Context context, int memberCount) {
    return context.getResources()
                  .getQuantityString(R.plurals.LinkPreviewRepository_d_members,
                                     memberCount,
                                     memberCount);
  }

  private static Optional<Attachment> bitmapToAttachment(@Nullable Bitmap bitmap,
                                                         @NonNull Bitmap.CompressFormat format,
                                                         @NonNull String contentType)
  {
    if (bitmap == null) {
      return Optional.empty();
    }

    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    bitmap.compress(format, 80, baos);

    byte[] bytes = baos.toByteArray();
    return Optional.of(bytesToAttachment(bytes, bitmap.getWidth(), bitmap.getHeight(), contentType));
  }

  private static Attachment bytesToAttachment(byte[] bytes,
                                              int width,
                                              int height,
                                              @NonNull String contentType) {

    Uri uri = BlobProvider.getInstance().forData(bytes).createForSingleSessionInMemory();

    return new UriAttachment(uri,
                             contentType,
                             AttachmentTable.TRANSFER_PROGRESS_STARTED,
                             bytes.length,
                             width,
                             height,
                             null,
                             null,
                             false,
                             false,
                             false,
                             false,
                             null,
                             null,
                             null,
                             null,
                             null);
  }

  private static class Metadata {
    private final Optional<String> title;
    private final Optional<String> description;
    private final long             date;
    private final Optional<String> imageUrl;

    Metadata(Optional<String> title, Optional<String> description, long date, Optional<String> imageUrl) {
      this.title       = title;
      this.description = description;
      this.date        = date;
      this.imageUrl    = imageUrl;
    }

    static Metadata empty() {
      return new Metadata(Optional.empty(), Optional.empty(), 0, Optional.empty());
    }

    Optional<String> getTitle() {
      return title;
    }

    Optional<String> getDescription() {
      return description;
    }

    long getDate() {
      return date;
    }

    Optional<String> getImageUrl() {
      return imageUrl;
    }

    boolean isEmpty() {
      return !title.isPresent() && !imageUrl.isPresent();
    }
  }

  interface Callback {
    void onSuccess(@NonNull LinkPreview linkPreview);

    void onError(@NonNull Error error);
  }
  
  public enum Error {
    PREVIEW_NOT_AVAILABLE,
    GROUP_LINK_INACTIVE
  }
}
