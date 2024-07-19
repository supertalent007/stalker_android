package org.stalker.securesms.groups.v2;

import org.junit.Test;
import org.signal.core.util.logging.Log;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.stalker.securesms.crypto.ProfileKeyUtil;
import org.stalker.securesms.testutil.LogRecorder;
import org.whispersystems.signalservice.api.push.ServiceId.ACI;

import java.util.Collections;
import java.util.UUID;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.stalker.securesms.groups.v2.ChangeBuilder.changeBy;
import static org.stalker.securesms.groups.v2.ChangeBuilder.changeByUnknown;
import static org.stalker.securesms.testutil.LogRecorder.hasMessages;

public final class ProfileKeySetTest {

  @Test
  public void empty_change() {
    ACI           editor        = ACI.from(UUID.randomUUID());
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(editor).build());

    assertTrue(profileKeySet.getProfileKeys().isEmpty());
    assertTrue(profileKeySet.getAuthoritativeProfileKeys().isEmpty());
  }

  @Test
  public void new_member_is_not_authoritative() {
    ACI           editor        = ACI.from(UUID.randomUUID());
    ACI           newMember     = ACI.from(UUID.randomUUID());
    ProfileKey    profileKey    = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(editor).addMember(newMember, profileKey).build());

    assertTrue(profileKeySet.getAuthoritativeProfileKeys().isEmpty());
    assertThat(profileKeySet.getProfileKeys(), is(Collections.singletonMap(newMember, profileKey)));
  }

  @Test
  public void new_member_by_self_is_authoritative() {
    ACI           newMember     = ACI.from(UUID.randomUUID());
    ProfileKey    profileKey    = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(newMember).addMember(newMember, profileKey).build());

    assertTrue(profileKeySet.getProfileKeys().isEmpty());
    assertThat(profileKeySet.getAuthoritativeProfileKeys(), is(Collections.singletonMap(newMember, profileKey)));
  }

  @Test
  public void new_member_by_self_promote_is_authoritative() {
    ACI           newMember     = ACI.from(UUID.randomUUID());
    ProfileKey    profileKey    = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(newMember).promote(newMember, profileKey).build());

    assertTrue(profileKeySet.getProfileKeys().isEmpty());
    assertThat(profileKeySet.getAuthoritativeProfileKeys(), is(Collections.singletonMap(newMember, profileKey)));
  }

  @Test
  public void new_member_by_promote_by_other_editor_is_not_authoritative() {
    ACI           editor        = ACI.from(UUID.randomUUID());
    ACI           newMember     = ACI.from(UUID.randomUUID());
    ProfileKey    profileKey    = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(editor).promote(newMember, profileKey).build());

    assertTrue(profileKeySet.getAuthoritativeProfileKeys().isEmpty());
    assertThat(profileKeySet.getProfileKeys(), is(Collections.singletonMap(newMember, profileKey)));
  }

  @Test
  public void new_member_by_promote_by_unknown_editor_is_not_authoritative() {
    ACI           newMember     = ACI.from(UUID.randomUUID());
    ProfileKey    profileKey    = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeByUnknown().promote(newMember, profileKey).build());

    assertTrue(profileKeySet.getAuthoritativeProfileKeys().isEmpty());
    assertThat(profileKeySet.getProfileKeys(), is(Collections.singletonMap(newMember, profileKey)));
  }

  @Test
  public void profile_key_update_by_self_is_authoritative() {
    ACI           member        = ACI.from(UUID.randomUUID());
    ProfileKey    profileKey    = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(member).profileKeyUpdate(member, profileKey).build());

    assertTrue(profileKeySet.getProfileKeys().isEmpty());
    assertThat(profileKeySet.getAuthoritativeProfileKeys(), is(Collections.singletonMap(member, profileKey)));
  }

  @Test
  public void profile_key_update_by_another_is_not_authoritative() {
    ACI           editor        = ACI.from(UUID.randomUUID());
    ACI           member        = ACI.from(UUID.randomUUID());
    ProfileKey    profileKey    = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(editor).profileKeyUpdate(member, profileKey).build());

    assertTrue(profileKeySet.getAuthoritativeProfileKeys().isEmpty());
    assertThat(profileKeySet.getProfileKeys(), is(Collections.singletonMap(member, profileKey)));
  }

  @Test
  public void multiple_updates_overwrite() {
    ACI           editor        = ACI.from(UUID.randomUUID());
    ACI           member        = ACI.from(UUID.randomUUID());
    ProfileKey    profileKey1   = ProfileKeyUtil.createNew();
    ProfileKey    profileKey2   = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(editor).profileKeyUpdate(member, profileKey1).build());
    profileKeySet.addKeysFromGroupChange(changeBy(editor).profileKeyUpdate(member, profileKey2).build());

    assertTrue(profileKeySet.getAuthoritativeProfileKeys().isEmpty());
    assertThat(profileKeySet.getProfileKeys(), is(Collections.singletonMap(member, profileKey2)));
  }

  @Test
  public void authoritative_takes_priority_when_seen_first() {
    ACI           editor        = ACI.from(UUID.randomUUID());
    ACI           member        = ACI.from(UUID.randomUUID());
    ProfileKey    profileKey1   = ProfileKeyUtil.createNew();
    ProfileKey    profileKey2   = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(member).profileKeyUpdate(member, profileKey1).build());
    profileKeySet.addKeysFromGroupChange(changeBy(editor).profileKeyUpdate(member, profileKey2).build());

    assertTrue(profileKeySet.getProfileKeys().isEmpty());
    assertThat(profileKeySet.getAuthoritativeProfileKeys(), is(Collections.singletonMap(member, profileKey1)));
  }

  @Test
  public void authoritative_takes_priority_when_seen_second() {
    ACI           editor        = ACI.from(UUID.randomUUID());
    ACI           member        = ACI.from(UUID.randomUUID());
    ProfileKey    profileKey1   = ProfileKeyUtil.createNew();
    ProfileKey    profileKey2   = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(editor).profileKeyUpdate(member, profileKey1).build());
    profileKeySet.addKeysFromGroupChange(changeBy(member).profileKeyUpdate(member, profileKey2).build());

    assertTrue(profileKeySet.getProfileKeys().isEmpty());
    assertThat(profileKeySet.getAuthoritativeProfileKeys(), is(Collections.singletonMap(member, profileKey2)));
  }

  @Test
  public void bad_profile_key() {
    LogRecorder   logRecorder   = new LogRecorder();
    ACI           editor        = ACI.from(UUID.randomUUID());
    ACI           member        = ACI.from(UUID.randomUUID());
    byte[]        badProfileKey = new byte[10];
    ProfileKeySet profileKeySet = new ProfileKeySet();

    Log.initialize(logRecorder);
    profileKeySet.addKeysFromGroupChange(changeBy(editor).profileKeyUpdate(member, badProfileKey).build());

    assertTrue(profileKeySet.getProfileKeys().isEmpty());
    assertTrue(profileKeySet.getAuthoritativeProfileKeys().isEmpty());
    assertThat(logRecorder.getWarnings(), hasMessages("Bad profile key in group"));
  }

  @Test
  public void new_requesting_member_if_editor_is_authoritative() {
    ACI           editor        = ACI.from(UUID.randomUUID());
    ProfileKey    profileKey    = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(editor).requestJoin(profileKey).build());

    assertThat(profileKeySet.getAuthoritativeProfileKeys(), is(Collections.singletonMap(editor, profileKey)));
    assertTrue(profileKeySet.getProfileKeys().isEmpty());
  }

  @Test
  public void new_requesting_member_if_not_editor_is_not_authoritative() {
    ACI           editor        = ACI.from(UUID.randomUUID());
    ACI           requesting    = ACI.from(UUID.randomUUID());
    ProfileKey    profileKey    = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(editor).requestJoin(requesting, profileKey).build());

    assertTrue(profileKeySet.getAuthoritativeProfileKeys().isEmpty());
    assertThat(profileKeySet.getProfileKeys(), is(Collections.singletonMap(requesting, profileKey)));
  }
}
