package org.stalker.securesms.util;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Stream;

import org.json.JSONException;
import org.json.JSONObject;
import org.signal.core.util.SetUtil;
import org.signal.core.util.logging.Log;
import org.stalker.securesms.BuildConfig;
import org.stalker.securesms.dependencies.ApplicationDependencies;
import org.stalker.securesms.groups.SelectionLimits;
import org.stalker.securesms.jobs.RemoteConfigRefreshJob;
import org.stalker.securesms.keyvalue.SignalStore;
import org.stalker.securesms.messageprocessingalarm.RoutineMessageFetchReceiver;
import org.whispersystems.signalservice.api.RemoteConfigResult;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * A location for flags that can be set locally and remotely. These flags can guard features that
 * are not yet ready to be activated.
 *
 * When creating a new flag:
 * - Create a new string constant. This should almost certainly be prefixed with "android."
 * - Add a method to retrieve the value using {@link #getBoolean(String, boolean)}. You can also add
 *   other checks here, like requiring other flags.
 * - If you want to be able to change a flag remotely, place it in {@link #REMOTE_CAPABLE}.
 * - If you would like to force a value for testing, place an entry in {@link #FORCED_VALUES}.
 *   Do not commit changes to this map!
 *
 * Other interesting things you can do:
 * - Make a flag {@link #HOT_SWAPPABLE}
 * - Make a flag {@link #STICKY} -- booleans only!
 * - Register a listener for flag changes in {@link #FLAG_CHANGE_LISTENERS}
 */
public final class FeatureFlags {

  private static final String TAG = Log.tag(FeatureFlags.class);

  private static final long FETCH_INTERVAL = TimeUnit.HOURS.toMillis(2);

  private static final String PAYMENTS_KILL_SWITCH              = "android.payments.kill";
  private static final String GROUPS_V2_RECOMMENDED_LIMIT       = "global.groupsv2.maxGroupSize";
  private static final String GROUPS_V2_HARD_LIMIT              = "global.groupsv2.groupSizeHardLimit";
  private static final String GROUP_NAME_MAX_LENGTH             = "global.groupsv2.maxNameLength";
  private static final String INTERNAL_USER                     = "android.internalUser";
  private static final String VERIFY_V2                         = "android.verifyV2";
  private static final String CLIENT_EXPIRATION                 = "android.clientExpiration";
  private static final String CUSTOM_VIDEO_MUXER                = "android.customVideoMuxer.1";
  private static final String CDS_REFRESH_INTERVAL              = "cds.syncInterval.seconds";
  private static final String CDS_FOREGROUND_SYNC_INTERVAL      = "cds.foregroundSyncInterval.seconds";
  private static final String AUTOMATIC_SESSION_RESET           = "android.automaticSessionReset.2";
  private static final String AUTOMATIC_SESSION_INTERVAL        = "android.automaticSessionResetInterval";
  private static final String DEFAULT_MAX_BACKOFF               = "android.defaultMaxBackoff";
  private static final String SERVER_ERROR_MAX_BACKOFF          = "android.serverErrorMaxBackoff";
  private static final String OKHTTP_AUTOMATIC_RETRY            = "android.okhttpAutomaticRetry";
  private static final String SHARE_SELECTION_LIMIT             = "android.share.limit";
  private static final String ANIMATED_STICKER_MIN_MEMORY       = "android.animatedStickerMinMemory";
  private static final String ANIMATED_STICKER_MIN_TOTAL_MEMORY = "android.animatedStickerMinTotalMemory";
  private static final String MESSAGE_PROCESSOR_ALARM_INTERVAL  = "android.messageProcessor.alarmIntervalMins";
  private static final String MESSAGE_PROCESSOR_DELAY           = "android.messageProcessor.foregroundDelayMs";
  private static final String MEDIA_QUALITY_LEVELS              = "android.mediaQuality.levels";
  private static final String RETRY_RECEIPT_LIFESPAN            = "android.retryReceiptLifespan";
  private static final String RETRY_RESPOND_MAX_AGE             = "android.retryRespondMaxAge";
  private static final String SENDER_KEY_MAX_AGE                = "android.senderKeyMaxAge";
  private static final String RETRY_RECEIPTS                    = "android.retryReceipts";
  private static final String MAX_GROUP_CALL_RING_SIZE          = "global.calling.maxGroupCallRingSize";
  private static final String STORIES_TEXT_FUNCTIONS            = "android.stories.text.functions";
  private static final String HARDWARE_AEC_BLOCKLIST_MODELS     = "android.calling.hardwareAecBlockList";
  private static final String SOFTWARE_AEC_BLOCKLIST_MODELS     = "android.calling.softwareAecBlockList";
  private static final String USE_HARDWARE_AEC_IF_OLD           = "android.calling.useHardwareAecIfOlderThanApi29";
  private static final String PAYMENTS_COUNTRY_BLOCKLIST        = "global.payments.disabledRegions";
  private static final String STORIES_AUTO_DOWNLOAD_MAXIMUM     = "android.stories.autoDownloadMaximum";
  private static final String TELECOM_MANUFACTURER_ALLOWLIST    = "android.calling.telecomAllowList";
  private static final String TELECOM_MODEL_BLOCKLIST           = "android.calling.telecomModelBlockList";
  private static final String CAMERAX_MODEL_BLOCKLIST           = "android.cameraXModelBlockList";
  private static final String CAMERAX_MIXED_MODEL_BLOCKLIST     = "android.cameraXMixedModelBlockList";
  private static final String PAYMENTS_REQUEST_ACTIVATE_FLOW    = "android.payments.requestActivateFlow";
  public static final  String GOOGLE_PAY_DISABLED_REGIONS       = "global.donations.gpayDisabledRegions";
  public static final  String CREDIT_CARD_DISABLED_REGIONS      = "global.donations.ccDisabledRegions";
  public static final  String PAYPAL_DISABLED_REGIONS           = "global.donations.paypalDisabledRegions";
  private static final String CDS_HARD_LIMIT                    = "android.cds.hardLimit";
  private static final String PAYPAL_ONE_TIME_DONATIONS         = "android.oneTimePayPalDonations.2";
  private static final String PAYPAL_RECURRING_DONATIONS        = "android.recurringPayPalDonations.3";
  private static final String ANY_ADDRESS_PORTS_KILL_SWITCH     = "android.calling.fieldTrial.anyAddressPortsKillSwitch";
  private static final String AD_HOC_CALLING                    = "android.calling.ad.hoc.3";
  private static final String MAX_ATTACHMENT_COUNT              = "android.attachments.maxCount";
  private static final String MAX_ATTACHMENT_RECEIVE_SIZE_BYTES = "global.attachments.maxReceiveBytes";
  private static final String MAX_ATTACHMENT_SIZE_BYTES         = "global.attachments.maxBytes";
  private static final String SVR2_KILLSWITCH                   = "android.svr2.killSwitch";
  private static final String CDS_DISABLE_COMPAT_MODE           = "cds.disableCompatibilityMode";
  private static final String FCM_MAY_HAVE_MESSAGES_KILL_SWITCH = "android.fcmNotificationFallbackKillSwitch";
  public static final  String PROMPT_FOR_NOTIFICATION_LOGS      = "android.logs.promptNotifications";
  private static final String PROMPT_FOR_NOTIFICATION_CONFIG    = "android.logs.promptNotificationsConfig";
  public static final  String PROMPT_BATTERY_SAVER              = "android.promptBatterySaver";
  public static final  String INSTANT_VIDEO_PLAYBACK            = "android.instantVideoPlayback.1";
  public static final  String CRASH_PROMPT_CONFIG               = "android.crashPromptConfig";
  private static final String SEPA_DEBIT_DONATIONS              = "android.sepa.debit.donations.5";
  private static final String IDEAL_DONATIONS                   = "android.ideal.donations.5";
  public static final  String IDEAL_ENABLED_REGIONS             = "global.donations.idealEnabledRegions";
  public static final  String SEPA_ENABLED_REGIONS              = "global.donations.sepaEnabledRegions";
  private static final String NOTIFICATION_THUMBNAIL_BLOCKLIST  = "android.notificationThumbnailProductBlocklist";
  private static final String CALLING_RAISE_HAND                = "android.calling.raiseHand";
  private static final String USE_ACTIVE_CALL_MANAGER           = "android.calling.useActiveCallManager.5";
  private static final String GIF_SEARCH                        = "global.gifSearch";
  private static final String AUDIO_REMUXING                    = "android.media.audioRemux.1";
  private static final String VIDEO_RECORD_1X_ZOOM              = "android.media.videoCaptureDefaultZoom";
  private static final String RETRY_RECEIPT_MAX_COUNT           = "android.retryReceipt.maxCount";
  private static final String RETRY_RECEIPT_MAX_COUNT_RESET_AGE = "android.retryReceipt.maxCountResetAge";
  private static final String PREKEY_FORCE_REFRESH_INTERVAL     = "android.prekeyForceRefreshInterval";
  private static final String CDSI_LIBSIGNAL_NET                = "android.cds.libsignal.3";
  private static final String RX_MESSAGE_SEND                   = "android.rxMessageSend.2";
  private static final String LINKED_DEVICE_LIFESPAN_SECONDS    = "android.linkedDeviceLifespanSeconds";
  private static final String MESSAGE_BACKUPS                   = "android.messageBackups";
  private static final String CAMERAX_CUSTOM_CONTROLLER         = "android.cameraXCustomController";
  private static final String REGISTRATION_V2                   = "android.registration.v2";
  private static final String LIBSIGNAL_WEB_SOCKET_ENABLED      = "android.libsignalWebSocketEnabled";
  private static final String RESTORE_POST_REGISTRATION         = "android.registration.restorePostRegistration";
  private static final String LIBSIGNAL_WEB_SOCKET_SHADOW_PCT   = "android.libsignalWebSocketShadowingPercentage";

  /**
   * We will only store remote values for flags in this set. If you want a flag to be controllable
   * remotely, place it in here.
   */
  @VisibleForTesting
  static final Set<String> REMOTE_CAPABLE = SetUtil.newHashSet(
      PAYMENTS_KILL_SWITCH,
      GROUPS_V2_RECOMMENDED_LIMIT, GROUPS_V2_HARD_LIMIT,
      INTERNAL_USER,
      VERIFY_V2,
      CLIENT_EXPIRATION,
      CUSTOM_VIDEO_MUXER,
      CDS_REFRESH_INTERVAL,
      CDS_FOREGROUND_SYNC_INTERVAL,
      GROUP_NAME_MAX_LENGTH,
      AUTOMATIC_SESSION_RESET,
      AUTOMATIC_SESSION_INTERVAL,
      DEFAULT_MAX_BACKOFF,
      SERVER_ERROR_MAX_BACKOFF,
      OKHTTP_AUTOMATIC_RETRY,
      SHARE_SELECTION_LIMIT,
      ANIMATED_STICKER_MIN_MEMORY,
      ANIMATED_STICKER_MIN_TOTAL_MEMORY,
      MESSAGE_PROCESSOR_ALARM_INTERVAL,
      MESSAGE_PROCESSOR_DELAY,
      MEDIA_QUALITY_LEVELS,
      RETRY_RECEIPT_LIFESPAN,
      RETRY_RESPOND_MAX_AGE,
      RETRY_RECEIPTS,
      MAX_GROUP_CALL_RING_SIZE,
      SENDER_KEY_MAX_AGE,
      STORIES_TEXT_FUNCTIONS,
      HARDWARE_AEC_BLOCKLIST_MODELS,
      SOFTWARE_AEC_BLOCKLIST_MODELS,
      USE_HARDWARE_AEC_IF_OLD,
      PAYMENTS_COUNTRY_BLOCKLIST,
      STORIES_AUTO_DOWNLOAD_MAXIMUM,
      TELECOM_MANUFACTURER_ALLOWLIST,
      TELECOM_MODEL_BLOCKLIST,
      CAMERAX_MODEL_BLOCKLIST,
      CAMERAX_MIXED_MODEL_BLOCKLIST,
      PAYMENTS_REQUEST_ACTIVATE_FLOW,
      GOOGLE_PAY_DISABLED_REGIONS,
      CREDIT_CARD_DISABLED_REGIONS,
      PAYPAL_DISABLED_REGIONS,
      CDS_HARD_LIMIT,
      PAYPAL_ONE_TIME_DONATIONS,
      PAYPAL_RECURRING_DONATIONS,
      ANY_ADDRESS_PORTS_KILL_SWITCH,
      MAX_ATTACHMENT_COUNT,
      MAX_ATTACHMENT_RECEIVE_SIZE_BYTES,
      MAX_ATTACHMENT_SIZE_BYTES,
      AD_HOC_CALLING,
      SVR2_KILLSWITCH,
      CDS_DISABLE_COMPAT_MODE,
      FCM_MAY_HAVE_MESSAGES_KILL_SWITCH,
      PROMPT_FOR_NOTIFICATION_LOGS,
      PROMPT_FOR_NOTIFICATION_CONFIG,
      PROMPT_BATTERY_SAVER,
      INSTANT_VIDEO_PLAYBACK,
      CRASH_PROMPT_CONFIG,
      SEPA_DEBIT_DONATIONS,
      IDEAL_DONATIONS,
      IDEAL_ENABLED_REGIONS,
      SEPA_ENABLED_REGIONS,
      NOTIFICATION_THUMBNAIL_BLOCKLIST,
      CALLING_RAISE_HAND,
      USE_ACTIVE_CALL_MANAGER,
      GIF_SEARCH,
      AUDIO_REMUXING,
      VIDEO_RECORD_1X_ZOOM,
      RETRY_RECEIPT_MAX_COUNT,
      RETRY_RECEIPT_MAX_COUNT_RESET_AGE,
      PREKEY_FORCE_REFRESH_INTERVAL,
      CDSI_LIBSIGNAL_NET,
      RX_MESSAGE_SEND,
      LINKED_DEVICE_LIFESPAN_SECONDS,
      CAMERAX_CUSTOM_CONTROLLER,
      LIBSIGNAL_WEB_SOCKET_ENABLED,
      LIBSIGNAL_WEB_SOCKET_SHADOW_PCT
  );

  @VisibleForTesting
  static final Set<String> NOT_REMOTE_CAPABLE = SetUtil.newHashSet(MESSAGE_BACKUPS, REGISTRATION_V2, RESTORE_POST_REGISTRATION);

  /**
   * Values in this map will take precedence over any value. This should only be used for local
   * development. Given that you specify a default when retrieving a value, and that we only store
   * remote values for things in {@link #REMOTE_CAPABLE}, there should be no need to ever *commit*
   * an addition to this map.
   */
  @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
  @VisibleForTesting
  static final Map<String, Object> FORCED_VALUES = new HashMap<String, Object>() {{
  }};

  /**
   * By default, flags are only updated once at app start. This is to ensure that values don't
   * change within an app session, simplifying logic. However, given that this can delay how often
   * a flag is updated, you can put a flag in here to mark it as 'hot swappable'. Flags in this set
   * will be updated arbitrarily at runtime. This will make values more responsive, but also places
   * more burden on the reader to ensure that the app experience remains consistent.
   */
  @VisibleForTesting
  static final Set<String> HOT_SWAPPABLE = SetUtil.newHashSet(
      VERIFY_V2,
      CLIENT_EXPIRATION,
      CUSTOM_VIDEO_MUXER,
      CDS_REFRESH_INTERVAL,
      CDS_FOREGROUND_SYNC_INTERVAL,
      GROUP_NAME_MAX_LENGTH,
      AUTOMATIC_SESSION_RESET,
      AUTOMATIC_SESSION_INTERVAL,
      DEFAULT_MAX_BACKOFF,
      SERVER_ERROR_MAX_BACKOFF,
      OKHTTP_AUTOMATIC_RETRY,
      SHARE_SELECTION_LIMIT,
      ANIMATED_STICKER_MIN_MEMORY,
      ANIMATED_STICKER_MIN_TOTAL_MEMORY,
      MESSAGE_PROCESSOR_ALARM_INTERVAL,
      MESSAGE_PROCESSOR_DELAY,
      MEDIA_QUALITY_LEVELS,
      RETRY_RECEIPT_LIFESPAN,
      RETRY_RESPOND_MAX_AGE,
      RETRY_RECEIPTS,
      MAX_GROUP_CALL_RING_SIZE,
      SENDER_KEY_MAX_AGE,
      HARDWARE_AEC_BLOCKLIST_MODELS,
      SOFTWARE_AEC_BLOCKLIST_MODELS,
      USE_HARDWARE_AEC_IF_OLD,
      PAYMENTS_COUNTRY_BLOCKLIST,
      TELECOM_MANUFACTURER_ALLOWLIST,
      TELECOM_MODEL_BLOCKLIST,
      CAMERAX_MODEL_BLOCKLIST,
      PAYMENTS_REQUEST_ACTIVATE_FLOW,
      CDS_HARD_LIMIT,
      MAX_ATTACHMENT_COUNT,
      MAX_ATTACHMENT_RECEIVE_SIZE_BYTES,
      MAX_ATTACHMENT_SIZE_BYTES,
      SVR2_KILLSWITCH,
      CDS_DISABLE_COMPAT_MODE,
      FCM_MAY_HAVE_MESSAGES_KILL_SWITCH,
      PROMPT_FOR_NOTIFICATION_LOGS,
      PROMPT_FOR_NOTIFICATION_CONFIG,
      PROMPT_BATTERY_SAVER,
      CRASH_PROMPT_CONFIG,
      NOTIFICATION_THUMBNAIL_BLOCKLIST,
      CALLING_RAISE_HAND,
      VIDEO_RECORD_1X_ZOOM,
      RETRY_RECEIPT_MAX_COUNT,
      RETRY_RECEIPT_MAX_COUNT_RESET_AGE,
      PREKEY_FORCE_REFRESH_INTERVAL,
      CDSI_LIBSIGNAL_NET,
      RX_MESSAGE_SEND,
      LINKED_DEVICE_LIFESPAN_SECONDS,
      CAMERAX_CUSTOM_CONTROLLER
  );

  /**
   * Flags in this set will stay true forever once they receive a true value from a remote config.
   */
  @VisibleForTesting
  static final Set<String> STICKY = SetUtil.newHashSet(
      VERIFY_V2,
      SVR2_KILLSWITCH,
      FCM_MAY_HAVE_MESSAGES_KILL_SWITCH
  );

  /**
   * Listeners that are called when the value in {@link #REMOTE_VALUES} changes. That means that
   * hot-swappable flags will have this invoked as soon as we know about that change, but otherwise
   * these will only run during initialization.
   *
   * These can be called on any thread, including the main thread, so be careful!
   *
   * Also note that this doesn't play well with {@link #FORCED_VALUES} -- changes there will not
   * trigger changes in this map, so you'll have to do some manual hacking to get yourself in the
   * desired test state.
   */
  private static final Map<String, OnFlagChange> FLAG_CHANGE_LISTENERS = new HashMap<String, OnFlagChange>() {{
    put(MESSAGE_PROCESSOR_ALARM_INTERVAL, change -> RoutineMessageFetchReceiver.startOrUpdateAlarm(ApplicationDependencies.getApplication()));
  }};

  private static final Map<String, Object> REMOTE_VALUES = new TreeMap<>();

  private FeatureFlags() {}

  public static synchronized void init() {
    Map<String, Object> current = parseStoredConfig(SignalStore.remoteConfigValues().getCurrentConfig());
    Map<String, Object> pending = parseStoredConfig(SignalStore.remoteConfigValues().getPendingConfig());
    Map<String, Change> changes = computeChanges(current, pending);

    SignalStore.remoteConfigValues().setCurrentConfig(mapToJson(pending));
    REMOTE_VALUES.putAll(pending);
    triggerFlagChangeListeners(changes);

    Log.i(TAG, "init() " + REMOTE_VALUES.toString());
  }

  public static void refreshIfNecessary() {
    long timeSinceLastFetch = System.currentTimeMillis() - SignalStore.remoteConfigValues().getLastFetchTime();

    if (timeSinceLastFetch < 0 || timeSinceLastFetch > FETCH_INTERVAL) {
      Log.i(TAG, "Scheduling remote config refresh.");
      ApplicationDependencies.getJobManager().add(new RemoteConfigRefreshJob());
    } else {
      Log.i(TAG, "Skipping remote config refresh. Refreshed " + timeSinceLastFetch + " ms ago.");
    }
  }

  @WorkerThread
  public static void refreshSync() throws IOException {
    RemoteConfigResult result = ApplicationDependencies.getSignalServiceAccountManager().getRemoteConfig();
    FeatureFlags.update(result.getConfig());
  }

  public static synchronized void update(@NonNull Map<String, Object> config) {
    Map<String, Object> memory  = REMOTE_VALUES;
    Map<String, Object> disk    = parseStoredConfig(SignalStore.remoteConfigValues().getPendingConfig());
    UpdateResult        result  = updateInternal(config, memory, disk, REMOTE_CAPABLE, HOT_SWAPPABLE, STICKY);

    SignalStore.remoteConfigValues().setPendingConfig(mapToJson(result.getDisk()));
    REMOTE_VALUES.clear();
    REMOTE_VALUES.putAll(result.getMemory());
    triggerFlagChangeListeners(result.getMemoryChanges());

    SignalStore.remoteConfigValues().setLastFetchTime(System.currentTimeMillis());

    Log.i(TAG, "[Memory] Before: " + memory.toString());
    Log.i(TAG, "[Memory] After : " + result.getMemory().toString());
    Log.i(TAG, "[Disk]   Before: " + disk.toString());
    Log.i(TAG, "[Disk]   After : " + result.getDisk().toString());
  }

  /**
   * Maximum number of members allowed in a group.
   */
  public static SelectionLimits groupLimits() {
    return new SelectionLimits(getInteger(GROUPS_V2_RECOMMENDED_LIMIT, 151),
                               getInteger(GROUPS_V2_HARD_LIMIT, 1001));
  }

  /** Payments Support */
  public static boolean payments() {
    return !getBoolean(PAYMENTS_KILL_SWITCH, false);
  }

  /** Internal testing extensions. */
  public static boolean internalUser() {
    return getBoolean(INTERNAL_USER, false) || Environment.IS_NIGHTLY || Environment.IS_STAGING;
  }

  /** Whether or not to use the UUID in verification codes. */
  public static boolean verifyV2() {
    return getBoolean(VERIFY_V2, false);
  }

  /** The raw client expiration JSON string. */
  public static String clientExpiration() {
    return getString(CLIENT_EXPIRATION, null);
  }

  /** Whether to use the custom streaming muxer or built in android muxer. */
  public static boolean useStreamingVideoMuxer() {
    return getBoolean(CUSTOM_VIDEO_MUXER, false);
  }

  /** The time in between routine CDS refreshes, in seconds. */
  public static int cdsRefreshIntervalSeconds() {
    return getInteger(CDS_REFRESH_INTERVAL, (int) TimeUnit.HOURS.toSeconds(48));
  }

  /** The minimum time in between foreground CDS refreshes initiated via message requests, in milliseconds. */
  public static Long cdsForegroundSyncInterval() {
    return TimeUnit.SECONDS.toMillis(getInteger(CDS_FOREGROUND_SYNC_INTERVAL, (int) TimeUnit.HOURS.toSeconds(4)));
  }

  public static @NonNull SelectionLimits shareSelectionLimit() {
    int limit = getInteger(SHARE_SELECTION_LIMIT, 5);
    return new SelectionLimits(limit, limit);
  }

  /** The maximum number of grapheme */
  public static int getMaxGroupNameGraphemeLength() {
    return Math.max(32, getInteger(GROUP_NAME_MAX_LENGTH, -1));
  }

  /** Whether or not to allow automatic session resets. */
  public static boolean automaticSessionReset() {
    return getBoolean(AUTOMATIC_SESSION_RESET, true);
  }

  /** How often we allow an automatic session reset. */
  public static int automaticSessionResetIntervalSeconds() {
    return getInteger(AUTOMATIC_SESSION_RESET, (int) TimeUnit.HOURS.toSeconds(1));
  }

  /** The default maximum backoff for jobs. */
  public static long getDefaultMaxBackoff() {
    return TimeUnit.SECONDS.toMillis(getInteger(DEFAULT_MAX_BACKOFF, 60));
  }

  /** The maximum backoff for network jobs that hit a 5xx error. */
  public static long getServerErrorMaxBackoff() {
    return TimeUnit.SECONDS.toMillis(getInteger(SERVER_ERROR_MAX_BACKOFF, (int) TimeUnit.HOURS.toSeconds(6)));
  }

  /** Whether or not to allow automatic retries from OkHttp */
  public static boolean okHttpAutomaticRetry() {
    return getBoolean(OKHTTP_AUTOMATIC_RETRY, true);
  }

  /** The minimum memory class required for rendering animated stickers in the keyboard and such */
  public static int animatedStickerMinimumMemoryClass() {
    return getInteger(ANIMATED_STICKER_MIN_MEMORY, 193);
  }

  /** The minimum total memory for rendering animated stickers in the keyboard and such */
  public static int animatedStickerMinimumTotalMemoryMb() {
    return getInteger(ANIMATED_STICKER_MIN_TOTAL_MEMORY, (int) ByteUnit.GIGABYTES.toMegabytes(3));
  }

  public static @NonNull String getMediaQualityLevels() {
    return getString(MEDIA_QUALITY_LEVELS, "");
  }

  /** Whether or not sending or responding to retry receipts is enabled. */
  public static boolean retryReceipts() {
    return getBoolean(RETRY_RECEIPTS, true);
  }

  /** How old a message is allowed to be while still resending in response to a retry receipt . */
  public static long retryRespondMaxAge() {
    return getLong(RETRY_RESPOND_MAX_AGE, TimeUnit.DAYS.toMillis(14));
  }

  /**
   * The max number of retry receipts sends we allow (within @link{#retryReceiptMaxCountResetAge()}) before we consider the volume too large and stop responding.
   */
  public static long retryReceiptMaxCount() {
    return getLong(RETRY_RECEIPT_MAX_COUNT, 10);
  }

  /**
   * If the last retry receipt send was older than this, then we reset the retry receipt sent count. (For use with @link{#retryReceiptMaxCount()})
   */
  public static long retryReceiptMaxCountResetAge() {
    return getLong(RETRY_RECEIPT_MAX_COUNT_RESET_AGE, TimeUnit.HOURS.toMillis(3));
  }

  /** How long a sender key can live before it needs to be rotated. */
  public static long senderKeyMaxAge() {
    return Math.min(getLong(SENDER_KEY_MAX_AGE, TimeUnit.DAYS.toMillis(14)), TimeUnit.DAYS.toMillis(90));
  }

  /** Max group size that can be use group call ringing. */
  public static long maxGroupCallRingSize() {
    return getLong(MAX_GROUP_CALL_RING_SIZE, 16);
  }

  /** A comma-separated list of country codes where payments should be disabled. */
  public static String paymentsCountryBlocklist() {
    return getString(PAYMENTS_COUNTRY_BLOCKLIST, "98,963,53,850,7");
  }

  /**
   * Whether users can apply alignment and scale to text posts
   *
   * NOTE: This feature is still under ongoing development, do not enable.
   */
  public static boolean storiesTextFunctions() {
    return getBoolean(STORIES_TEXT_FUNCTIONS, false);
  }

  /** A comma-separated list of models that should *not* use hardware AEC for calling. */
  public static @NonNull String hardwareAecBlocklistModels() {
    return getString(HARDWARE_AEC_BLOCKLIST_MODELS, "");
  }

  /** A comma-separated list of models that should *not* use software AEC for calling. */
  public static @NonNull String softwareAecBlocklistModels() {
    return getString(SOFTWARE_AEC_BLOCKLIST_MODELS, "");
  }

  /** A comma-separated list of manufacturers that *should* use Telecom for calling. */
  public static @NonNull String telecomManufacturerAllowList() {
    return getString(TELECOM_MANUFACTURER_ALLOWLIST, "");
  }

  /** A comma-separated list of manufacturers that *should* use Telecom for calling. */
  public static @NonNull String telecomModelBlockList() {
    return getString(TELECOM_MODEL_BLOCKLIST, "");
  }

  /** A comma-separated list of manufacturers that should *not* use CameraX. */
  public static @NonNull String cameraXModelBlocklist() {
    return getString(CAMERAX_MODEL_BLOCKLIST, "");
  }

  /** A comma-separated list of manufacturers that should *not* use CameraX mixed mode. */
  public static @NonNull String cameraXMixedModelBlocklist() {
    return getString(CAMERAX_MIXED_MODEL_BLOCKLIST, "");
  }

  /** Whether or not hardware AEC should be used for calling on devices older than API 29. */
  public static boolean useHardwareAecIfOlderThanApi29() {
    return getBoolean(USE_HARDWARE_AEC_IF_OLD, false);
  }

  /**
   * Prefetch count for stories from a given user.
   */
  public static int storiesAutoDownloadMaximum() {
    return getInteger(STORIES_AUTO_DOWNLOAD_MAXIMUM, 2);
  }

  /** Whether client supports sending a request to another to activate payments */
  public static boolean paymentsRequestActivateFlow() {
    return getBoolean(PAYMENTS_REQUEST_ACTIVATE_FLOW, false);
  }

  /**
   * @return Serialized list of regions in which Google Pay is disabled for donations
   */
  public static @NonNull String googlePayDisabledRegions() {
    return getString(GOOGLE_PAY_DISABLED_REGIONS, "*");
  }

  /**
   * @return Serialized list of regions in which credit cards are disabled for donations
   */
  public static @NonNull String creditCardDisabledRegions() {
    return getString(CREDIT_CARD_DISABLED_REGIONS, "*");
  }

  /**
   * @return Serialized list of regions in which PayPal is disabled for donations
   */
  public static @NonNull String paypalDisabledRegions() {
    return getString(PAYPAL_DISABLED_REGIONS, "*");
  }

  /**
   * If the user has more than this number of contacts, the CDS request will certainly be rejected, so we must fail.
   */
  public static int cdsHardLimit() {
    return getInteger(CDS_HARD_LIMIT, 50_000);
  }

  /**
   * Whether or not we should allow PayPal payments for one-time donations
   */
  public static boolean paypalOneTimeDonations() {
    return getBoolean(PAYPAL_ONE_TIME_DONATIONS, Environment.IS_STAGING);
  }

  /**
   * Whether or not we should allow PayPal payments for recurring donations
   */
  public static boolean paypalRecurringDonations() {
    return getBoolean(PAYPAL_RECURRING_DONATIONS, Environment.IS_STAGING);
  }

  /**
   * Enable/disable RingRTC field trial for "AnyAddressPortsKillSwitch"
   */
  public static boolean callingFieldTrialAnyAddressPortsKillSwitch() {
    return getBoolean(ANY_ADDRESS_PORTS_KILL_SWITCH, false);
  }

  /**
   * Enable/disable for notification when we cannot fetch messages despite receiving an urgent push.
   */
  public static boolean fcmMayHaveMessagesNotificationKillSwitch() {
    return getBoolean(FCM_MAY_HAVE_MESSAGES_KILL_SWITCH, false);
  }

  /**
   * Whether or not ad-hoc calling is enabled
   */
  public static boolean adHocCalling() {
    return getBoolean(AD_HOC_CALLING, false);
  }

  /** Maximum number of attachments allowed to be sent/received. */
  public static int maxAttachmentCount() {
    return getInteger(MAX_ATTACHMENT_COUNT, 32);
  }

  /** Maximum attachment size for ciphertext in bytes. */
  public static long maxAttachmentReceiveSizeBytes() {
    long maxAttachmentSize = maxAttachmentSizeBytes();
    long maxReceiveSize    = getLong(MAX_ATTACHMENT_RECEIVE_SIZE_BYTES, (int) (maxAttachmentSize * 1.25));
    return Math.max(maxAttachmentSize, maxReceiveSize);
  }

  /** Maximum attachment ciphertext size when sending in bytes */
  public static long maxAttachmentSizeBytes() {
    return getLong(MAX_ATTACHMENT_SIZE_BYTES, ByteUnit.MEGABYTES.toBytes(100));
  }

  /**
   * Allow the video players to read from the temporary download files for attachments.
   * @return whether this functionality is enabled.
   */
  public static boolean instantVideoPlayback() {
    return getBoolean(INSTANT_VIDEO_PLAYBACK, false);
  }

  public static String promptForDelayedNotificationLogs() {
    return getString(PROMPT_FOR_NOTIFICATION_LOGS, "*");
  }

  public static String delayedNotificationsPromptConfig() {
    return getString(PROMPT_FOR_NOTIFICATION_CONFIG, "");
  }

  public static String promptBatterySaver() {
    return getString(PROMPT_BATTERY_SAVER, "*");
  }

  /** Config object for what crashes to prompt about. */
  public static String crashPromptConfig() {
    return getString(CRASH_PROMPT_CONFIG, "");
  }

  /**
   * Whether or not SEPA debit payments for donations are enabled.
   * WARNING: This feature is under heavy development and is *not* ready for wider use.
   */
  public static boolean sepaDebitDonations() {
    return getBoolean(SEPA_DEBIT_DONATIONS, false);
  }

  public static boolean idealDonations() {
    return getBoolean(IDEAL_DONATIONS, false);
  }

  public static String idealEnabledRegions() {
    return getString(IDEAL_ENABLED_REGIONS, "");
  }

  public static String sepaEnabledRegions() {
    return getString(SEPA_ENABLED_REGIONS, "");
  }

  /**
   * Whether or not group call raise hand is enabled.
   */
  public static boolean groupCallRaiseHand() {
    return getBoolean(CALLING_RAISE_HAND, false);
  }

  /** List of device products that are blocked from showing notification thumbnails. */
  public static String notificationThumbnailProductBlocklist() {
    return getString(NOTIFICATION_THUMBNAIL_BLOCKLIST, "");
  }

  /** Whether or not to use active call manager instead of WebRtcCallService. */
  public static boolean useActiveCallManager() {
    return getBoolean(USE_ACTIVE_CALL_MANAGER, false);
  }

  /** Whether the in-app GIF search is available for use. */
  public static boolean gifSearchAvailable() {
    return getBoolean(GIF_SEARCH, true);
  }

  /** Allow media converters to remux audio instead of transcoding it. */
  public static boolean allowAudioRemuxing() {
    return getBoolean(AUDIO_REMUXING, false);
  }

  /** Get the default video zoom, expressed as 10x the actual Float value due to the service limiting us to whole numbers. */
  public static boolean startVideoRecordAt1x() {
    return getBoolean(VIDEO_RECORD_1X_ZOOM, false);
  }

  /** How often we allow a forced prekey refresh. */
  public static long preKeyForceRefreshInterval() {
    return getLong(PREKEY_FORCE_REFRESH_INTERVAL, TimeUnit.HOURS.toMillis(1));
  }

  /** Make CDSI lookups via libsignal-net instead of native websocket. */
  public static boolean useLibsignalNetForCdsiLookup() {
    return getBoolean(CDSI_LIBSIGNAL_NET, false);
  }

  /** Use Rx threading model to do sends. */
  public static boolean useRxMessageSending() {
    return getBoolean(RX_MESSAGE_SEND, false);
  }

  /** The lifespan of a linked device (i.e. the time it can be inactive for before it expires), in milliseconds. */
  public static long linkedDeviceLifespan() {
    long seconds = getLong(LINKED_DEVICE_LIFESPAN_SECONDS, TimeUnit.DAYS.toSeconds(30));
    return TimeUnit.SECONDS.toMillis(seconds);
  }

  /**
   * Enable Message Backups UI
   * Note: This feature is in active development and is not intended to currently function.
   */
  public static boolean messageBackups() {
    return BuildConfig.MESSAGE_BACKUP_RESTORE_ENABLED || getBoolean(MESSAGE_BACKUPS, false);
  }

  /** Whether or not to use the custom CameraX controller class */
  public static boolean customCameraXController() {
    return getBoolean(CAMERAX_CUSTOM_CONTROLLER, false);
  }

  /** Whether or not to use the V2 refactor of registration. */
  public static boolean registrationV2() {
    return getBoolean(REGISTRATION_V2, false);
  }

  /** Whether unauthenticated chat web socket is backed by libsignal-net */
  public static boolean libSignalWebSocketEnabled() { return getBoolean(LIBSIGNAL_WEB_SOCKET_ENABLED, false); }

  /** Whether or not to launch the restore activity after registration is complete, rather than before. */
  public static boolean restoreAfterRegistration() {
    return getBoolean(RESTORE_POST_REGISTRATION, false);
  }

  /**
   * Percentage [0, 100] of web socket requests that will be "shadowed" by sending
   * an unauthenticated keep-alive via libsignal-net. Default: 0
   */
  public static  int libSignalWebSocketShadowingPercentage() {
    int value = getInteger(LIBSIGNAL_WEB_SOCKET_SHADOW_PCT, 0);
    return Math.max(0, Math.min(value, 100));
  }

  /** Only for rendering debug info. */
  public static synchronized @NonNull Map<String, Object> getMemoryValues() {
    return new TreeMap<>(REMOTE_VALUES);
  }

  /** Only for rendering debug info. */
  public static synchronized @NonNull Map<String, Object> getDiskValues() {
    return new TreeMap<>(parseStoredConfig(SignalStore.remoteConfigValues().getCurrentConfig()));
  }

  /** Only for rendering debug info. */
  public static synchronized @NonNull Map<String, Object> getPendingDiskValues() {
    return new TreeMap<>(parseStoredConfig(SignalStore.remoteConfigValues().getPendingConfig()));
  }

  /** Only for rendering debug info. */
  public static synchronized @NonNull Map<String, Object> getForcedValues() {
    return new TreeMap<>(FORCED_VALUES);
  }

  @VisibleForTesting
  static @NonNull UpdateResult updateInternal(@NonNull Map<String, Object> remote,
                                              @NonNull Map<String, Object> localMemory,
                                              @NonNull Map<String, Object> localDisk,
                                              @NonNull Set<String>         remoteCapable,
                                              @NonNull Set<String>         hotSwap,
                                              @NonNull Set<String>         sticky)
  {
    Map<String, Object> newMemory = new TreeMap<>(localMemory);
    Map<String, Object> newDisk   = new TreeMap<>(localDisk);

    Set<String> allKeys = new HashSet<>();
    allKeys.addAll(remote.keySet());
    allKeys.addAll(localDisk.keySet());
    allKeys.addAll(localMemory.keySet());

    Stream.of(allKeys)
          .filter(remoteCapable::contains)
          .forEach(key -> {
            Object remoteValue = remote.get(key);
            Object diskValue   = localDisk.get(key);
            Object newValue    = remoteValue;

            if (newValue != null && diskValue != null && newValue.getClass() != diskValue.getClass()) {
              Log.w(TAG, "Type mismatch! key: " + key);

              newDisk.remove(key);

              if (hotSwap.contains(key)) {
                newMemory.remove(key);
              }

              return;
            }

            if (sticky.contains(key) && (newValue instanceof Boolean || diskValue instanceof Boolean)) {
              newValue = diskValue == Boolean.TRUE ? Boolean.TRUE : newValue;
            } else if (sticky.contains(key)) {
              Log.w(TAG, "Tried to make a non-boolean sticky! Ignoring. (key: " + key + ")");
            }

            if (newValue != null) {
              newDisk.put(key, newValue);
            } else {
              newDisk.remove(key);
            }

            if (hotSwap.contains(key)) {
              if (newValue != null) {
                newMemory.put(key, newValue);
              } else {
                newMemory.remove(key);
              }
            }
          });

    Stream.of(allKeys)
          .filterNot(remoteCapable::contains)
          .filterNot(key -> sticky.contains(key) && localDisk.get(key) == Boolean.TRUE)
          .forEach(key -> {
            newDisk.remove(key);

            if (hotSwap.contains(key)) {
              newMemory.remove(key);
            }
          });

    return new UpdateResult(newMemory, newDisk, computeChanges(localMemory, newMemory));
  }

  @VisibleForTesting
  static @NonNull Map<String, Change> computeChanges(@NonNull Map<String, Object> oldMap, @NonNull Map<String, Object> newMap) {
    Map<String, Change> changes = new HashMap<>();
    Set<String>         allKeys = new HashSet<>();

    allKeys.addAll(oldMap.keySet());
    allKeys.addAll(newMap.keySet());

    for (String key : allKeys) {
      Object oldValue = oldMap.get(key);
      Object newValue = newMap.get(key);

      if (oldValue == null && newValue == null) {
        throw new AssertionError("Should not be possible.");
      } else if (oldValue != null && newValue == null) {
        changes.put(key, Change.REMOVED);
      } else if (newValue != oldValue && newValue instanceof Boolean) {
        changes.put(key, (boolean) newValue ? Change.ENABLED : Change.DISABLED);
      } else if (!Objects.equals(oldValue, newValue)) {
        changes.put(key, Change.CHANGED);
      }
    }

    return changes;
  }

  private static @NonNull VersionFlag getVersionFlag(@NonNull String key) {
    int versionFromKey = getInteger(key, 0);

    if (versionFromKey == 0) {
      return VersionFlag.OFF;
    }

    if (BuildConfig.CANONICAL_VERSION_CODE >= versionFromKey) {
      return VersionFlag.ON;
    } else {
      return VersionFlag.ON_IN_FUTURE_VERSION;
    }
  }

  public static long getBackgroundMessageProcessInterval() {
    int delayMinutes = getInteger(MESSAGE_PROCESSOR_ALARM_INTERVAL, (int) TimeUnit.HOURS.toMinutes(6));
    return TimeUnit.MINUTES.toMillis(delayMinutes);
  }

  /**
   * How long before a "Checking messages" foreground notification is shown to the user.
   */
  public static long getBackgroundMessageProcessForegroundDelay() {
    return getInteger(MESSAGE_PROCESSOR_DELAY, 300);
  }

  /**
   * Whether or not SVR2 should be used at all. Defaults to true. In practice this is reserved as a killswitch.
   */
  public static boolean svr2() {
    // Despite us always inverting the value, it's important that this defaults to false so that the STICKY property works as intended
    return !getBoolean(SVR2_KILLSWITCH, false);
  }

  private enum VersionFlag {
    /** The flag is no set */
    OFF,

    /** The flag is set on for a version higher than the current client version */
    ON_IN_FUTURE_VERSION,

    /** The flag is set on for this version or earlier */
    ON
  }

  private static boolean getBoolean(@NonNull String key, boolean defaultValue) {
    Boolean forced = (Boolean) FORCED_VALUES.get(key);
    if (forced != null) {
      return forced;
    }

    Object remote = REMOTE_VALUES.get(key);
    if (remote instanceof Boolean) {
      return (boolean) remote;
    } else if (remote instanceof String) {
      String stringValue = ((String) remote).toLowerCase();
      if (stringValue.equals("true")) {
        return true;
      } else if (stringValue.equals("false")) {
        return false;
      } else {
        Log.w(TAG, "Expected a boolean for key '" + key + "', but got something else (" + stringValue + ")! Falling back to the default.");
      }
    } else if (remote != null) {
      Log.w(TAG, "Expected a boolean for key '" + key + "', but got something else! Falling back to the default.");
    }

    return defaultValue;
  }

  private static int getInteger(@NonNull String key, int defaultValue) {
    Integer forced = (Integer) FORCED_VALUES.get(key);
    if (forced != null) {
      return forced;
    }

    Object remote = REMOTE_VALUES.get(key);
    if (remote instanceof String) {
      try {
        return Integer.parseInt((String) remote);
      } catch (NumberFormatException e) {
        Log.w(TAG, "Expected an int for key '" + key + "', but got something else! Falling back to the default.");
      }
    }

    return defaultValue;
  }

  private static long getLong(@NonNull String key, long defaultValue) {
    Long forced = (Long) FORCED_VALUES.get(key);
    if (forced != null) {
      return forced;
    }

    Object remote = REMOTE_VALUES.get(key);
    if (remote instanceof String) {
      try {
        return Long.parseLong((String) remote);
      } catch (NumberFormatException e) {
        Log.w(TAG, "Expected a long for key '" + key + "', but got something else! Falling back to the default.");
      }
    }

    return defaultValue;
  }

  private static String getString(@NonNull String key, String defaultValue) {
    String forced = (String) FORCED_VALUES.get(key);
    if (forced != null) {
      return forced;
    }

    Object remote = REMOTE_VALUES.get(key);
    if (remote instanceof String) {
      return (String) remote;
    }

    return defaultValue;
  }

  private static Map<String, Object> parseStoredConfig(String stored) {
    Map<String, Object> parsed = new HashMap<>();

    if (TextUtils.isEmpty(stored)) {
      Log.i(TAG, "No remote config stored. Skipping.");
      return parsed;
    }

    try {
      JSONObject       root = new JSONObject(stored);
      Iterator<String> iter = root.keys();

      while (iter.hasNext()) {
        String key = iter.next();
        parsed.put(key, root.get(key));
      }
    } catch (JSONException e) {
      throw new AssertionError("Failed to parse! Cleared storage.");
    }

    return parsed;
  }

  private static @NonNull String mapToJson(@NonNull Map<String, Object> map) {
    try {
      JSONObject json = new JSONObject();

      for (Map.Entry<String, Object> entry : map.entrySet()) {
        json.put(entry.getKey(), entry.getValue());
      }

      return json.toString();
    } catch (JSONException e) {
      throw new AssertionError(e);
    }
  }

  private static void triggerFlagChangeListeners(Map<String, Change> changes) {
    for (Map.Entry<String, Change> change : changes.entrySet()) {
      OnFlagChange listener = FLAG_CHANGE_LISTENERS.get(change.getKey());

      if (listener != null) {
        Log.i(TAG, "Triggering change listener for: " + change.getKey());
        listener.onFlagChange(change.getValue());
      }
    }
  }

  @VisibleForTesting
  static final class UpdateResult {
    private final Map<String, Object> memory;
    private final Map<String, Object> disk;
    private final Map<String, Change> memoryChanges;

    UpdateResult(@NonNull Map<String, Object> memory, @NonNull Map<String, Object> disk, @NonNull Map<String, Change> memoryChanges) {
      this.memory        = memory;
      this.disk          = disk;
      this.memoryChanges = memoryChanges;
    }

    public @NonNull Map<String, Object> getMemory() {
      return memory;
    }

    public @NonNull Map<String, Object> getDisk() {
      return disk;
    }

    public @NonNull Map<String, Change> getMemoryChanges() {
      return memoryChanges;
    }
  }

  @VisibleForTesting
  interface OnFlagChange {
    void onFlagChange(@NonNull Change change);
  }

  enum Change {
    ENABLED, DISABLED, CHANGED, REMOVED
  }
}
