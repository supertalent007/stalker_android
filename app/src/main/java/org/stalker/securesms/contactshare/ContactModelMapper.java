package org.stalker.securesms.contactshare;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.logging.Log;
import org.stalker.securesms.attachments.Attachment;
import org.stalker.securesms.attachments.PointerAttachment;
import org.whispersystems.signalservice.api.InvalidMessageStructureException;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.api.messages.shared.SharedContact;
import org.whispersystems.signalservice.api.util.AttachmentPointerUtil;
import org.whispersystems.signalservice.internal.push.DataMessage;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import static org.stalker.securesms.contactshare.Contact.Avatar;
import static org.stalker.securesms.contactshare.Contact.Email;
import static org.stalker.securesms.contactshare.Contact.Name;
import static org.stalker.securesms.contactshare.Contact.Phone;
import static org.stalker.securesms.contactshare.Contact.PostalAddress;

public class ContactModelMapper {

  private static final String TAG = Log.tag(ContactModelMapper.class);

  public static SharedContact.Builder localToRemoteBuilder(@NonNull Contact contact) {
    List<SharedContact.Phone>         phoneNumbers    = new ArrayList<>(contact.getPhoneNumbers().size());
    List<SharedContact.Email>         emails          = new ArrayList<>(contact.getEmails().size());
    List<SharedContact.PostalAddress> postalAddresses = new ArrayList<>(contact.getPostalAddresses().size());

    for (Phone phone : contact.getPhoneNumbers()) {
      phoneNumbers.add(new SharedContact.Phone.Builder().setValue(phone.getNumber())
                                                        .setType(localToRemoteType(phone.getType()))
                                                        .setLabel(phone.getLabel())
                                                        .build());
    }

    for (Email email : contact.getEmails()) {
      emails.add(new SharedContact.Email.Builder().setValue(email.getEmail())
                                                  .setType(localToRemoteType(email.getType()))
                                                  .setLabel(email.getLabel())
                                                  .build());
    }

    for (PostalAddress postalAddress : contact.getPostalAddresses()) {
      postalAddresses.add(new SharedContact.PostalAddress.Builder().setType(localToRemoteType(postalAddress.getType()))
                                                                   .setLabel(postalAddress.getLabel())
                                                                   .setStreet(postalAddress.getStreet())
                                                                   .setPobox(postalAddress.getPoBox())
                                                                   .setNeighborhood(postalAddress.getNeighborhood())
                                                                   .setCity(postalAddress.getCity())
                                                                   .setRegion(postalAddress.getRegion())
                                                                   .setPostcode(postalAddress.getPostalCode())
                                                                   .setCountry(postalAddress.getCountry())
                                                                   .build());
    }

    SharedContact.Name name = new SharedContact.Name.Builder().setDisplay(contact.getName().getDisplayName())
                                                              .setGiven(contact.getName().getGivenName())
                                                              .setFamily(contact.getName().getFamilyName())
                                                              .setPrefix(contact.getName().getPrefix())
                                                              .setSuffix(contact.getName().getSuffix())
                                                              .setMiddle(contact.getName().getMiddleName())
                                                              .build();

    return new SharedContact.Builder().setName(name)
                                      .withOrganization(contact.getOrganization())
                                      .withPhones(phoneNumbers)
                                      .withEmails(emails)
                                      .withAddresses(postalAddresses);
  }

  public static Contact remoteToLocal(@NonNull SharedContact sharedContact) {
    Name name = new Name(sharedContact.getName().getDisplay().orElse(null),
        sharedContact.getName().getGiven().orElse(null),
        sharedContact.getName().getFamily().orElse(null),
        sharedContact.getName().getPrefix().orElse(null),
        sharedContact.getName().getSuffix().orElse(null),
        sharedContact.getName().getMiddle().orElse(null));

    List<Phone> phoneNumbers = new LinkedList<>();
    if (sharedContact.getPhone().isPresent()) {
      for (SharedContact.Phone phone : sharedContact.getPhone().get()) {
        phoneNumbers.add(new Phone(phone.getValue(),
                                   remoteToLocalType(phone.getType()),
                                   phone.getLabel().orElse(null)));
      }
    }

    List<Email> emails = new LinkedList<>();
    if (sharedContact.getEmail().isPresent()) {
      for (SharedContact.Email email : sharedContact.getEmail().get()) {
        emails.add(new Email(email.getValue(),
                             remoteToLocalType(email.getType()),
                             email.getLabel().orElse(null)));
      }
    }

    List<PostalAddress> postalAddresses = new LinkedList<>();
    if (sharedContact.getAddress().isPresent()) {
      for (SharedContact.PostalAddress postalAddress : sharedContact.getAddress().get()) {
        postalAddresses.add(new PostalAddress(remoteToLocalType(postalAddress.getType()),
                                              postalAddress.getLabel().orElse(null),
                                              postalAddress.getStreet().orElse(null),
                                              postalAddress.getPobox().orElse(null),
                                              postalAddress.getNeighborhood().orElse(null),
                                              postalAddress.getCity().orElse(null),
                                              postalAddress.getRegion().orElse(null),
                                              postalAddress.getPostcode().orElse(null),
                                              postalAddress.getCountry().orElse(null)));
      }
    }

    Avatar avatar = null;
    if (sharedContact.getAvatar().isPresent()) {
      Attachment attachment = PointerAttachment.forPointer(Optional.of(sharedContact.getAvatar().get().getAttachment().asPointer())).get();
      boolean    isProfile  = sharedContact.getAvatar().get().isProfile();

      avatar = new Avatar(null, attachment, isProfile);
    }

    return new Contact(name, sharedContact.getOrganization().orElse(null), phoneNumbers, emails, postalAddresses, avatar);
  }

  public static Contact remoteToLocal(@NonNull DataMessage.Contact contact) {
    DataMessage.Contact.Name contactName = contact.name != null ? contact.name : new DataMessage.Contact.Name();
    Name name = new Name(contactName.displayName,
                         contactName.givenName,
                         contactName.familyName,
                         contactName.prefix,
                         contactName.suffix,
                         contactName.middleName);

    List<Phone> phoneNumbers = new ArrayList<>(contact.number.size());
    for (DataMessage.Contact.Phone phone : contact.number) {
      if (phone.value_ != null) {
        phoneNumbers.add(new Phone(phone.value_,
                                   remoteToLocalType(phone.type),
                                   phone.label));
      }
    }

    List<Email> emails = new ArrayList<>(contact.email.size());
    for (DataMessage.Contact.Email email : contact.email) {
      if (email.value_ != null) {
        emails.add(new Email(email.value_,
                             remoteToLocalType(email.type),
                             email.label));
      }
    }

    List<PostalAddress> postalAddresses = new ArrayList<>(contact.address.size());
    for (DataMessage.Contact.PostalAddress postalAddress : contact.address) {
      postalAddresses.add(new PostalAddress(remoteToLocalType(postalAddress.type),
                                            postalAddress.label,
                                            postalAddress.street,
                                            postalAddress.pobox,
                                            postalAddress.neighborhood,
                                            postalAddress.city,
                                            postalAddress.region,
                                            postalAddress.postcode,
                                            postalAddress.country));
    }

    Avatar avatar = null;
    if (contact.avatar != null && contact.avatar.avatar != null) {
      try {
        SignalServiceAttachmentPointer attachmentPointer = AttachmentPointerUtil.createSignalAttachmentPointer(contact.avatar.avatar);
        Attachment                     attachment        = PointerAttachment.forPointer(Optional.of(attachmentPointer.asPointer())).get();
        boolean                        isProfile         = Boolean.TRUE.equals(contact.avatar.isProfile);

        avatar = new Avatar(null, attachment, isProfile);
      } catch (InvalidMessageStructureException e) {
        Log.w(TAG, "Unable to create avatar for contact", e);
      }
    }

    return new Contact(name, contact.organization, phoneNumbers, emails, postalAddresses, avatar);
  }

  private static Phone.Type remoteToLocalType(@Nullable SharedContact.Phone.Type type) {
    if (type == null) return Phone.Type.CUSTOM;

    switch (type) {
      case HOME:   return Phone.Type.HOME;
      case MOBILE: return Phone.Type.MOBILE;
      case WORK:   return Phone.Type.WORK;
      default:     return Phone.Type.CUSTOM;
    }
  }

  private static Phone.Type remoteToLocalType(@Nullable DataMessage.Contact.Phone.Type type) {
    if (type == null) return Phone.Type.CUSTOM;

    switch (type) {
      case HOME:   return Phone.Type.HOME;
      case MOBILE: return Phone.Type.MOBILE;
      case WORK:   return Phone.Type.WORK;
      default:     return Phone.Type.CUSTOM;
    }
  }

  private static Email.Type remoteToLocalType(@Nullable SharedContact.Email.Type type) {
    if (type == null) return Email.Type.CUSTOM;

    switch (type) {
      case HOME:   return Email.Type.HOME;
      case MOBILE: return Email.Type.MOBILE;
      case WORK:   return Email.Type.WORK;
      default:     return Email.Type.CUSTOM;
    }
  }

  private static Email.Type remoteToLocalType(@Nullable DataMessage.Contact.Email.Type type) {
    if (type == null) return Email.Type.CUSTOM;

    switch (type) {
      case HOME:   return Email.Type.HOME;
      case MOBILE: return Email.Type.MOBILE;
      case WORK:   return Email.Type.WORK;
      default:     return Email.Type.CUSTOM;
    }
  }

  private static PostalAddress.Type remoteToLocalType(@Nullable SharedContact.PostalAddress.Type type) {
    if (type == null) return PostalAddress.Type.CUSTOM;

    switch (type) {
      case HOME:   return PostalAddress.Type.HOME;
      case WORK:   return PostalAddress.Type.WORK;
      default:     return PostalAddress.Type.CUSTOM;
    }
  }

  private static PostalAddress.Type remoteToLocalType(@Nullable DataMessage.Contact.PostalAddress.Type type) {
    if (type == null) return PostalAddress.Type.CUSTOM;

    switch (type) {
      case HOME:   return PostalAddress.Type.HOME;
      case WORK:   return PostalAddress.Type.WORK;
      default:     return PostalAddress.Type.CUSTOM;
    }
  }

  private static SharedContact.Phone.Type localToRemoteType(Phone.Type type) {
    switch (type) {
      case HOME:   return SharedContact.Phone.Type.HOME;
      case MOBILE: return SharedContact.Phone.Type.MOBILE;
      case WORK:   return SharedContact.Phone.Type.WORK;
      default:     return SharedContact.Phone.Type.CUSTOM;
    }
  }

  private static SharedContact.Email.Type localToRemoteType(Email.Type type) {
    switch (type) {
      case HOME:   return SharedContact.Email.Type.HOME;
      case MOBILE: return SharedContact.Email.Type.MOBILE;
      case WORK:   return SharedContact.Email.Type.WORK;
      default:     return SharedContact.Email.Type.CUSTOM;
    }
  }

  private static SharedContact.PostalAddress.Type localToRemoteType(PostalAddress.Type type) {
    switch (type) {
      case HOME: return SharedContact.PostalAddress.Type.HOME;
      case WORK: return SharedContact.PostalAddress.Type.WORK;
      default:   return SharedContact.PostalAddress.Type.CUSTOM;
    }
  }
}
