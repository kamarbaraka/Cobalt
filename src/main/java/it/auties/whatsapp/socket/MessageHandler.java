package it.auties.whatsapp.socket;

import static it.auties.whatsapp.api.ErrorHandler.Location.MESSAGE;
import static it.auties.whatsapp.model.request.Node.ofAttributes;
import static it.auties.whatsapp.model.request.Node.ofChildren;
import static it.auties.whatsapp.model.sync.HistorySync.HistorySyncHistorySyncType.RECENT;
import static it.auties.whatsapp.util.Specification.Signal.MSG;
import static it.auties.whatsapp.util.Specification.Signal.PKMSG;
import static it.auties.whatsapp.util.Specification.Signal.SKMSG;
import static java.util.Map.of;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.partitioningBy;
import static java.util.stream.Collectors.toUnmodifiableList;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import it.auties.bytes.Bytes;
import it.auties.whatsapp.api.ErrorHandler.Location;
import it.auties.whatsapp.crypto.AesGmc;
import it.auties.whatsapp.crypto.GroupBuilder;
import it.auties.whatsapp.crypto.GroupCipher;
import it.auties.whatsapp.crypto.Hkdf;
import it.auties.whatsapp.crypto.SessionBuilder;
import it.auties.whatsapp.crypto.SessionCipher;
import it.auties.whatsapp.model.action.ContactAction;
import it.auties.whatsapp.model.business.BusinessVerifiedNameCertificate;
import it.auties.whatsapp.model.business.BusinessVerifiedNameDetails;
import it.auties.whatsapp.model.chat.Chat;
import it.auties.whatsapp.model.chat.ChatEphemeralTimer;
import it.auties.whatsapp.model.chat.GroupMetadata;
import it.auties.whatsapp.model.contact.Contact;
import it.auties.whatsapp.model.contact.ContactJid;
import it.auties.whatsapp.model.contact.ContactJid.Type;
import it.auties.whatsapp.model.contact.ContactStatus;
import it.auties.whatsapp.model.info.MessageIndexInfo;
import it.auties.whatsapp.model.info.MessageInfo;
import it.auties.whatsapp.model.message.model.MessageCategory;
import it.auties.whatsapp.model.message.model.MessageContainer;
import it.auties.whatsapp.model.message.model.MessageKey;
import it.auties.whatsapp.model.message.model.MessageStatus;
import it.auties.whatsapp.model.message.model.MessageType;
import it.auties.whatsapp.model.message.server.DeviceSentMessage;
import it.auties.whatsapp.model.message.server.ProtocolMessage;
import it.auties.whatsapp.model.message.server.SenderKeyDistributionMessage;
import it.auties.whatsapp.model.message.standard.PollCreationMessage;
import it.auties.whatsapp.model.message.standard.PollUpdateMessage;
import it.auties.whatsapp.model.message.standard.ReactionMessage;
import it.auties.whatsapp.model.poll.PollUpdateEncryptedOptions;
import it.auties.whatsapp.model.request.Attributes;
import it.auties.whatsapp.model.request.MessageSendRequest;
import it.auties.whatsapp.model.request.Node;
import it.auties.whatsapp.model.setting.EphemeralSetting;
import it.auties.whatsapp.model.signal.keypair.SignalPreKeyPair;
import it.auties.whatsapp.model.signal.keypair.SignalSignedKeyPair;
import it.auties.whatsapp.model.signal.message.SignalDistributionMessage;
import it.auties.whatsapp.model.signal.message.SignalMessage;
import it.auties.whatsapp.model.signal.message.SignalPreKeyMessage;
import it.auties.whatsapp.model.signal.sender.SenderKeyName;
import it.auties.whatsapp.model.sync.HistorySync;
import it.auties.whatsapp.model.sync.PushName;
import it.auties.whatsapp.util.BytesHelper;
import it.auties.whatsapp.util.JacksonProvider;
import it.auties.whatsapp.util.KeyHelper;
import it.auties.whatsapp.util.Medias;
import it.auties.whatsapp.util.Specification;
import it.auties.whatsapp.util.Validate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

class MessageHandler extends Handler
    implements JacksonProvider {

  private static final int MAX_ATTEMPTS = 3;

  private final SocketHandler socketHandler;
  private final Map<String, Integer> retries;
  private final Cache<ContactJid, GroupMetadata> groupsCache;
  private final Cache<String, List<ContactJid>> devicesCache;
  private final Set<Chat> historyCache;

  protected MessageHandler(SocketHandler socketHandler) {
    this.socketHandler = socketHandler;
    this.retries = new HashMap<>();
    this.groupsCache = createCache(Duration.ofMinutes(5));
    this.devicesCache = createCache(Duration.ofMinutes(5));
    this.historyCache = new HashSet<>();
  }

  private <K, V> Cache<K, V> createCache(Duration duration) {
    return Caffeine.newBuilder()
        .expireAfterWrite(duration)
        .build();
  }

  protected final CompletableFuture<Void> encode(MessageSendRequest request) {
    return CompletableFuture.runAsync(() -> encodeSync(request), getOrCreateService())
        .exceptionallyAsync(throwable -> handleMessageFailure(throwable, request.info()));
  }

  private void encodeSync(MessageSendRequest request) {
    socketHandler.awaitAppReady();
    var resultRequest =
        isConversation(request.info()) ? encodeConversation(request) : encodeGroup(request);
    resultRequest.join();
    saveMessage(request.info(), "unknown");
    attributeMessageReceipt(request.info());
  }

  private CompletableFuture<Node> encodeGroup(MessageSendRequest request) {
    var encodedMessage = BytesHelper.messageToBytes(request.info().message());
    var senderName = new SenderKeyName(request.info().chatJid()
        .toString(), socketHandler.store()
        .userCompanionJid()
        .toSignalAddress());
    var groupBuilder = new GroupBuilder(socketHandler.keys());
    var signalMessage = groupBuilder.createOutgoing(senderName);
    var groupCipher = new GroupCipher(senderName, socketHandler.keys());
    var groupMessage = groupCipher.encrypt(encodedMessage);
    if (request.hasSenderOverride()) {
      return getGroupRetryDevices(request.overrideSender(), request.force())
          .thenComposeAsync(
              allDevices -> createGroupNodes(request.info(), signalMessage, allDevices,
                  request.force()))
          .thenApplyAsync(preKeys -> createEncodedMessageNode(request.info(), preKeys, groupMessage,
              request.additionalAttributes()))
          .thenComposeAsync(socketHandler::send);
    }
    return Optional.ofNullable(
            request.force() ? groupsCache.getIfPresent(request.info().chatJid()) : null)
        .map(CompletableFuture::completedFuture)
        .orElseGet(() -> socketHandler.queryGroupMetadata(request.info().chatJid()))
        .thenComposeAsync(devices -> getGroupDevices(devices, request.force()))
        .thenComposeAsync(allDevices -> createGroupNodes(request.info(), signalMessage, allDevices,
            request.force()))
        .thenApplyAsync(preKeys -> createEncodedMessageNode(request.info(), preKeys, groupMessage,
            request.additionalAttributes()))
        .thenComposeAsync(socketHandler::send);
  }

  private CompletableFuture<Node> encodeConversation(MessageSendRequest request) {
    var encodedMessage = BytesHelper.messageToBytes(request.info().message());
    var deviceMessage = DeviceSentMessage.of(request.info().chatJid()
        .toString(), request.info().message(), null);
    var encodedDeviceMessage = BytesHelper.messageToBytes(deviceMessage);
    var knownDevices = request.hasSenderOverride() ? List.of(request.overrideSender())
        : List.of(socketHandler.store().userCompanionJid().toUserJid(), request.info().chatJid());
    return getDevices(knownDevices, true, request.force())
        .thenComposeAsync(
            allDevices -> createConversationNodes(allDevices, encodedMessage, encodedDeviceMessage,
                request.force()))
        .thenApplyAsync(sessions -> createEncodedMessageNode(request.info(), sessions, null,
            request.additionalAttributes()))
        .thenComposeAsync(socketHandler::send);
  }

  private <T> T handleMessageFailure(Throwable throwable, MessageInfo info) {
    info.status(MessageStatus.ERROR);
    return socketHandler.errorHandler()
        .handleFailure(MESSAGE, throwable);
  }

  private boolean isConversation(MessageInfo info) {
    return info.chatJid()
        .type() == ContactJid.Type.USER || info.chatJid()
        .type() == ContactJid.Type.STATUS;
  }

  private Node createEncodedMessageNode(MessageInfo info, List<Node> preKeys, Node descriptor,
      Map<String, Object> metadata) {
    try {
      var body = new ArrayList<Node>();
      if (!preKeys.isEmpty()) {
        body.add(ofChildren("participants", preKeys));
      }
      if (descriptor != null) {
        body.add(descriptor);
      }
      if (hasPreKeyMessage(preKeys)) {
        var identity = PROTOBUF.writeValueAsBytes(socketHandler.keys()
            .companionIdentity());
        body.add(Node.of("device-identity", identity));
      }
      var attributes = Attributes.ofNullable(metadata)
          .put("id", info.id())
          .put("to", info.chatJid())
          .put("type", "text")
          .put("duration", "900", () -> info.message().type() == MessageType.LIVE_LOCATION)
          .toMap();
      return ofChildren("message", attributes, body);
    } catch (IOException exception) {
      throw new UncheckedIOException("Cannot create encoded message node", exception);
    }
  }

  private boolean hasPreKeyMessage(List<Node> participants) {
    return participants.stream()
        .map(Node::children)
        .flatMap(Collection::stream)
        .map(node -> node.attributes()
            .getOptionalString("type"))
        .flatMap(Optional::stream)
        .anyMatch("pkmsg"::equals);
  }

  private CompletableFuture<List<Node>> createConversationNodes(List<ContactJid> contacts,
      byte[] message, byte[] deviceMessage, boolean force) {
    var partitioned = contacts.stream()
        .collect(partitioningBy(contact -> Objects.equals(contact.user(),
            socketHandler.store().userCompanionJid().user())));
    var companions = querySessions(partitioned.get(true), force)
        .thenApplyAsync(ignored -> createMessageNodes(partitioned.get(true), deviceMessage));
    var others = querySessions(partitioned.get(false), force)
        .thenApplyAsync(ignored -> createMessageNodes(partitioned.get(false), message));
    return companions.thenCombineAsync(others, (first, second) -> toSingleList(first, second));
  }

  private CompletableFuture<List<Node>> createGroupNodes(MessageInfo info,
      byte[] distributionMessage, List<ContactJid> participants, boolean force) {
    Validate.isTrue(info.chat()
        .isGroup(), "Cannot send group message to non-group");
    var missingParticipants = participants.stream()
        .filter(participant -> !info.chat().participantsPreKeys().contains(participant))
        .toList();
    if (missingParticipants.isEmpty()) {
      return completedFuture(List.of());
    }
    var whatsappMessage = new SenderKeyDistributionMessage(info.chatJid()
        .toString(), distributionMessage);
    var paddedMessage = BytesHelper.messageToBytes(whatsappMessage);
    return querySessions(missingParticipants, force)
        .thenApplyAsync(ignored -> createMessageNodes(missingParticipants, paddedMessage))
        .thenApplyAsync(results -> savePreKeys(info.chat(), missingParticipants, results));
  }

  private List<Node> savePreKeys(Chat group, List<ContactJid> missingParticipants,
      List<Node> results) {
    group.participantsPreKeys()
        .addAll(missingParticipants);
    return results;
  }

  protected CompletableFuture<Void> querySessions(List<ContactJid> contacts, boolean force) {
    var missingSessions = contacts.stream()
        .filter(contact -> force || !socketHandler.keys().hasSession(contact.toSignalAddress()))
        .map(contact -> ofAttributes("user", of("jid", contact, "reason", "identity")))
        .toList();
    if (missingSessions.isEmpty()) {
      return completedFuture(null);
    }
    return socketHandler.sendQuery("get", "encrypt", ofChildren("key", missingSessions))
        .thenAcceptAsync(this::parseSessions);
  }

  private List<Node> createMessageNodes(List<ContactJid> contacts, byte[] message) {
    return contacts.stream()
        .map(contact -> createMessageNode(contact, message))
        .toList();
  }

  private Node createMessageNode(ContactJid contact, byte[] message) {
    var cipher = new SessionCipher(contact.toSignalAddress(), socketHandler.keys());
    var encrypted = cipher.encrypt(message);
    return ofChildren("to", of("jid", contact), encrypted);
  }

  private CompletableFuture<List<ContactJid>> getGroupRetryDevices(ContactJid contactJid,
      boolean force) {
    return getDevices(List.of(contactJid), false, force);
  }

  private CompletableFuture<List<ContactJid>> getGroupDevices(GroupMetadata metadata,
      boolean force) {
    groupsCache.put(metadata.jid(), metadata);
    return getDevices(metadata.participantsJids(), false, force);
  }

  private CompletableFuture<List<ContactJid>> getDevices(List<ContactJid> contacts,
      boolean excludeSelf, boolean force) {
    if (force) {
      return queryDevices(contacts, excludeSelf)
          .thenApplyAsync(missingDevices -> excludeSelf ? toSingleList(contacts, missingDevices)
              : missingDevices);
    }
    var partitioned = contacts.stream()
        .collect(partitioningBy(contact -> devicesCache.asMap()
            .containsKey(contact.user()), toUnmodifiableList()));
    var cached = partitioned.get(true)
        .stream()
        .map(ContactJid::user)
        .map(devicesCache::getIfPresent)
        .filter(Objects::nonNull)
        .flatMap(Collection::stream)
        .toList();
    var missing = partitioned.get(false);
    if (missing.isEmpty()) {
      return completedFuture(excludeSelf ?
          toSingleList(contacts, cached) :
          cached);
    }
    return queryDevices(missing, excludeSelf)
        .thenApplyAsync(
            missingDevices -> excludeSelf ? toSingleList(contacts, cached, missingDevices)
                : toSingleList(cached, missingDevices));
  }

  private CompletableFuture<List<ContactJid>> queryDevices(List<ContactJid> contacts,
      boolean excludeSelf) {
    var contactNodes = contacts.stream()
        .map(contact -> ofAttributes("user", of("jid", contact)))
        .toList();
    var body = Node.ofChildren("usync", of("sid", socketHandler.store()
            .nextTag(), "mode", "query", "last", "true", "index", "0", "context", "message"),
        ofChildren("query", ofAttributes("devices", of("version", "2"))),
        ofChildren("list", contactNodes));
    return socketHandler.sendQuery("get", "usync", body)
        .thenApplyAsync(result -> parseDevices(result, excludeSelf));
  }

  private List<ContactJid> parseDevices(Node node, boolean excludeSelf) {
    var results = node.children()
        .stream()
        .map(child -> child.findNode("list"))
        .flatMap(Optional::stream)
        .map(Node::children)
        .flatMap(Collection::stream)
        .map(entry -> parseDevice(entry, excludeSelf))
        .flatMap(Collection::stream)
        .toList();
    devicesCache.putAll(results.stream()
        .collect(groupingBy(ContactJid::user)));
    return results;
  }

  private List<ContactJid> parseDevice(Node wrapper, boolean excludeSelf) {
    var jid = wrapper.attributes()
        .getJid("jid")
        .orElseThrow(() -> new NoSuchElementException("Missing jid for sync device"));
    return wrapper.findNode("devices")
        .orElseThrow(() -> new NoSuchElementException("Missing devices"))
        .findNode("device-list")
        .orElseThrow(() -> new NoSuchElementException("Missing device list"))
        .children()
        .stream()
        .map(child -> parseDeviceId(child, jid, excludeSelf))
        .flatMap(Optional::stream)
        .map(id -> ContactJid.ofDevice(jid.user(), id))
        .toList();
  }

  private Optional<Integer> parseDeviceId(Node child, ContactJid jid, boolean excludeSelf) {
    var deviceId = child.attributes()
        .getInt("id");
    return child.description()
        .equals("device") && (!excludeSelf || deviceId != 0) && (!jid.user()
        .equals(socketHandler.store()
            .userCompanionJid()
            .user()) || socketHandler.store()
        .userCompanionJid()
        .device() != deviceId) && (deviceId == 0 || child.attributes()
        .hasKey("key-index")) ?
        Optional.of(deviceId) :
        Optional.empty();
  }

  private void parseSessions(Node node) {
    node.findNode("list")
        .orElseThrow(() -> new NoSuchElementException("Missing list: %s".formatted(node)))
        .findNodes("user")
        .forEach(this::parseSession);
  }

  private void parseSession(Node node) {
    Validate.isTrue(!node.hasNode("error"), "Erroneous session node", SecurityException.class);
    var jid = node.attributes()
        .getJid("jid")
        .orElseThrow(() -> new NoSuchElementException("Missing jid for session"));
    var registrationId = node.findNode("registration")
        .map(id -> BytesHelper.bytesToInt(id.contentAsBytes()
            .orElseThrow(), 4))
        .orElseThrow(() -> new NoSuchElementException("Missing id"));
    var identity = node.findNode("identity")
        .flatMap(Node::contentAsBytes)
        .map(KeyHelper::withHeader)
        .orElseThrow(() -> new NoSuchElementException("Missing identity"));
    var signedKey = node.findNode("skey")
        .flatMap(SignalSignedKeyPair::of)
        .orElseThrow(() -> new NoSuchElementException("Missing signed key"));
    var key = node.findNode("key")
        .flatMap(SignalSignedKeyPair::of)
        .orElse(null);
    var builder = new SessionBuilder(jid.toSignalAddress(), socketHandler.keys());
    builder.createOutgoing(registrationId, identity, signedKey, key);
  }

  public void decode(Node node) {
    getOrCreateService().execute(() -> {
      try {
        var businessName = getBusinessName(node);
        var encrypted = node.findNodes("enc");
        if (node.hasNode("unavailable") && !node.hasNode("enc")) {
          decode(node, null, businessName);
          return;
        }
        encrypted.forEach(message -> decode(node, message, businessName));
      } catch (Throwable throwable) {
        socketHandler.errorHandler()
            .handleFailure(MESSAGE, throwable);
      }
    });
  }

  private String getBusinessName(Node node) {
    return node.findNode("verified_name")
        .flatMap(Node::contentAsBytes)
        .map(bytes -> {
          try {
            return PROTOBUF.readMessage(bytes, BusinessVerifiedNameCertificate.class);
          } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read business name certificate", exception);
          }
        })
        .map(certificate -> {
          try {
            return PROTOBUF.readMessage(certificate.details(), BusinessVerifiedNameDetails.class);
          } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read business name details", exception);
          }
        })
        .map(BusinessVerifiedNameDetails::name)
        .orElse(null);
  }

  private void decode(Node infoNode, Node messageNode, String businessName) {
    try {
      var pushName = infoNode.attributes()
          .getNullableString("notify");
      var timestamp = infoNode.attributes()
          .getLong("t");
      var id = infoNode.attributes()
          .getRequiredString("id");
      var from = infoNode.attributes()
          .getJid("from")
          .orElseThrow(() -> new NoSuchElementException("Missing from"));
      var recipient = infoNode.attributes()
          .getJid("recipient")
          .orElse(from);
      var participant = infoNode.attributes()
          .getJid("participant")
          .orElse(null);
      var messageBuilder = MessageInfo.builder();
      var keyBuilder = MessageKey.builder();
      if (from.hasServer(ContactJid.Server.WHATSAPP) || from.hasServer(ContactJid.Server.USER)) {
        keyBuilder.chatJid(recipient);
        keyBuilder.senderJid(from);
        keyBuilder.fromMe(Objects.equals(from, socketHandler.store()
            .userCompanionJid()
            .toUserJid()));
        messageBuilder.senderJid(from);
      } else {
        keyBuilder.chatJid(from);
        keyBuilder.senderJid(requireNonNull(participant, "Missing participant in group message"));
        keyBuilder.fromMe(Objects.equals(participant.toUserJid(), socketHandler.store()
            .userCompanionJid()
            .toUserJid()));
        messageBuilder.senderJid(
            requireNonNull(participant, "Missing participant in group message"));
      }
      if (messageNode == null) {
        sendRetryReceipt(timestamp, id, from, recipient, participant, null, null, null);
        return;
      }
      var type = messageNode.attributes()
          .getRequiredString("type");
      var encodedMessage = messageNode.contentAsBytes()
          .orElse(null);
      var decodedMessage = decodeMessageBytes(type, encodedMessage, from, participant);
      if (decodedMessage.hasError()) {
        sendRetryReceipt(timestamp, id, from, recipient, participant, type, encodedMessage,
            decodedMessage);
        return;
      }
      var messageContainer = BytesHelper.bytesToMessage(decodedMessage.message())
          .unbox();
      var key = keyBuilder.id(id)
          .build();
      var info = messageBuilder.key(key)
          .pushName(pushName)
          .status(MessageStatus.DELIVERED)
          .businessVerifiedName(businessName)
          .timestampInSeconds(timestamp)
          .message(messageContainer)
          .build();
      attributeMessageReceipt(info);
      socketHandler.store().attribute(info);
      var category = infoNode.attributes()
          .getString("category");
      saveMessage(info, category);
      socketHandler.sendReceipt(info.chatJid(), info.senderJid(), List.of(info.key()
          .id()), null);
      socketHandler.sendMessageAck(infoNode, infoNode.attributes()
          .toMap());
      socketHandler.onReply(info);
    } catch (Throwable throwable) {
      socketHandler.errorHandler()
          .handleFailure(MESSAGE, throwable);
    }
  }

  private void attributeMessageReceipt(MessageInfo info) {
    var self = socketHandler.store().userCompanionJid().toUserJid();
    if (!info.fromMe() || !info.chatJid().equals(self)) {
      return;
    }
    info.receipt().readTimestamp(info.timestampInSeconds());
    info.receipt().deliveredJids().add(self);
    info.receipt().readJids().add(self);
    info.status(MessageStatus.READ);
  }

  private void handleReactionMessage(MessageInfo info, ReactionMessage reactionMessage) {
    info.ignore(true);
    socketHandler.store()
        .findMessageByKey(reactionMessage.key())
        .ifPresent(message -> message.reactions()
            .add(reactionMessage));
  }

  private void handlePollCreation(MessageContainer messageContainer,
      PollCreationMessage pollCreationMessage) {
    messageContainer.deviceInfo()
        .messageSecret()
        .ifPresent(pollCreationMessage::encryptionKey);
  }

  private void handlePollUpdate(MessageInfo info, PollUpdateMessage pollUpdateMessage) {
    try {
      var originalPollInfo = socketHandler.store()
          .findMessageByKey(pollUpdateMessage.pollCreationMessageKey())
          .orElseThrow(() -> new NoSuchElementException("Missing original poll message"));
      Validate.isTrue(originalPollInfo.message()
              .type() == MessageType.POLL_CREATION, "Original poll message has wrong type: %s",
          originalPollInfo.message()
              .type());
      var originalPollMessage = (PollCreationMessage) originalPollInfo.message()
          .content();
      pollUpdateMessage.pollCreationMessage(originalPollMessage);
      var originalPollSender = originalPollInfo.senderJid()
          .toUserJid()
          .toString()
          .getBytes(StandardCharsets.UTF_8);
      var modificationSenderJid = info.senderJid()
          .toUserJid();
      pollUpdateMessage.voter(modificationSenderJid);
      var modificationSender = modificationSenderJid.toString()
          .getBytes(StandardCharsets.UTF_8);
      var secretName = pollUpdateMessage.secretName()
          .getBytes(StandardCharsets.UTF_8);
      var useSecretPayload = Bytes.of(originalPollInfo.id())
          .append(originalPollSender)
          .append(modificationSender)
          .append(secretName)
          .toByteArray();
      var useCaseSecret = Hkdf.extractAndExpand(originalPollMessage.encryptionKey(),
          useSecretPayload, 32);
      var additionalData = "%s\0%s".formatted(originalPollInfo.id(), modificationSenderJid)
          .getBytes(StandardCharsets.UTF_8);
      var decrypted = AesGmc.decrypt(pollUpdateMessage.encryptedMetadata()
          .iv(), pollUpdateMessage.encryptedMetadata()
          .payload(), useCaseSecret, additionalData);
      var pollVoteMessage = PROTOBUF.readMessage(decrypted, PollUpdateEncryptedOptions.class);
      var selectedOptions = pollVoteMessage.selectedOptions()
          .stream()
          .map(hash -> Bytes.of(hash)
              .toHex())
          .map(originalPollMessage.selectableOptionsHashesMap()::get)
          .filter(Objects::nonNull)
          .toList();
      originalPollMessage.selectedOptionsMap()
          .put(modificationSenderJid, selectedOptions);
      pollUpdateMessage.votes(selectedOptions);
    } catch (Throwable throwable) {
      socketHandler.errorHandler().handleFailure(Location.POLL, null);
    }
  }

  private void sendRetryReceipt(long timestamp, String id, ContactJid from, ContactJid recipient,
      ContactJid participant, String type, byte[] encodedMessage,
      MessageDecodeResult decodedMessage) {
    var attempts = retries.getOrDefault(id, 0);
    if (attempts >= MAX_ATTEMPTS) {
      var cause = decodedMessage != null ?
          decodedMessage.error() :
          new RuntimeException("This message is not available");
      socketHandler.errorHandler()
          .handleFailure(MESSAGE, new RuntimeException(
              "Cannot decrypt message with type %s inside %s from %s".formatted(
                  Objects.requireNonNullElse(type, "unknown"), from,
                  requireNonNullElse(participant, from)), cause));
      return;
    }
    var retryAttributes = Attributes.of()
        .put("id", id)
        .put("type", "retry")
        .put("to", from)
        .put("recipient", recipient, () -> !Objects.equals(recipient, from))
        .put("participant", participant, Objects::nonNull)
        .toMap();
    var retryNode = Node.ofChildren("receipt", retryAttributes, Node.ofAttributes("retry",
            Map.of("count", attempts, "id",
                id, "t", timestamp, "v",
                "1")),
        Node.of("registration", BytesHelper.intToBytes(socketHandler.keys()
            .id(), 4)),
        attempts > 1 || encodedMessage == null ?
            createPreKeyNode() :
            null);
    socketHandler.sendWithNoResponse(retryNode);
    retries.put(id, attempts + 1);
  }

  private Node createPreKeyNode() {
    try {
      var preKey = SignalPreKeyPair.random(socketHandler.keys()
          .lastPreKeyId() + 1);
      var identity = PROTOBUF.writeValueAsBytes(socketHandler.keys()
          .companionIdentity());
      return Node.ofChildren("keys", Node.of("type", Specification.Signal.KEY_BUNDLE_TYPE),
          Node.of("identity",
              socketHandler.keys()
                  .identityKeyPair()
                  .publicKey()),
          preKey.toNode(), socketHandler.keys()
              .signedKeyPair()
              .toNode(), Node.of("device-identity", identity));
    } catch (IOException exception) {
      throw new UncheckedIOException("Cannot create pre key for message retry", exception);
    }
  }

  private MessageDecodeResult decodeMessageBytes(String type, byte[] encodedMessage,
      ContactJid from,
      ContactJid participant) {
    try {
      if (encodedMessage == null) {
        return new MessageDecodeResult(null,
            new IllegalArgumentException("Missing encoded message"));
      }
      var result = switch (type) {
        case SKMSG -> {
          Objects.requireNonNull(participant, "Cannot decipher skmsg without participant");
          var senderName = new SenderKeyName(from.toString(), participant.toSignalAddress());
          var signalGroup = new GroupCipher(senderName, socketHandler.keys());
          yield signalGroup.decrypt(encodedMessage);
        }
        case PKMSG -> {
          var user = from.hasServer(ContactJid.Server.WHATSAPP) ?
              from :
              participant;
          Objects.requireNonNull(user, "Cannot decipher pkmsg without user");
          var session = new SessionCipher(user.toSignalAddress(), socketHandler.keys());
          var preKey = SignalPreKeyMessage.ofSerialized(encodedMessage);
          yield session.decrypt(preKey);
        }
        case MSG -> {
          var user = from.hasServer(ContactJid.Server.WHATSAPP) ?
              from :
              participant;
          Objects.requireNonNull(user, "Cannot decipher msg without user");
          var session = new SessionCipher(user.toSignalAddress(), socketHandler.keys());
          var signalMessage = SignalMessage.ofSerialized(encodedMessage);
          yield session.decrypt(signalMessage);
        }
        default -> throw new IllegalArgumentException(
            "Unsupported encoded message type: %s".formatted(type));
      };
      return new MessageDecodeResult(result, null);
    } catch (Throwable throwable) {
      return new MessageDecodeResult(null, throwable);
    }
  }

  private void saveMessage(MessageInfo info, String category) {
    processMessage(info);
    if (info.chatJid().type() == Type.STATUS) {
      socketHandler.store()
          .addStatus(info);
      socketHandler.onNewStatus(info);
      return;
    }
    if (info.message().hasCategory(MessageCategory.SERVER)) {
      if (info.message().content() instanceof ProtocolMessage protocolMessage) {
        handleProtocolMessage(info, protocolMessage, Objects.equals(category, "peer"));
      }
      return;
    }
    var result = info.chat().addMessage(info);
    if (!result || info.timestampInSeconds() <= socketHandler.store().initializationTimeStamp()) {
      return;
    }
    if (info.chat()
        .archived() && socketHandler.store()
        .unarchiveChats()) {
      info.chat()
          .archived(false);
    }
    info.sender()
        .ifPresent(sender -> sender.lastSeen(ZonedDateTime.now()));
    info.sender()
        .filter(this::isTyping)
        .ifPresent(sender -> sender.lastKnownPresence(ContactStatus.AVAILABLE));
    if (!info.ignore()) {
      info.chat()
          .unreadMessagesCount(info.chat()
              .unreadMessagesCount() + 1);
    }
    socketHandler.onNewMessage(info);
  }

  private void processMessage(MessageInfo info) {
    switch (info.message()
        .content()) {
      case SenderKeyDistributionMessage distributionMessage ->
          handleDistributionMessage(distributionMessage, info.senderJid());
      case PollCreationMessage pollCreationMessage ->
          handlePollCreation(info.message(), pollCreationMessage);
      case PollUpdateMessage pollUpdateMessage -> handlePollUpdate(info, pollUpdateMessage);
      case ReactionMessage reactionMessage -> handleReactionMessage(info, reactionMessage);
      default -> {
      }
    }
  }

  private boolean isTyping(Contact sender) {
    return sender.lastKnownPresence() == ContactStatus.COMPOSING
        || sender.lastKnownPresence() == ContactStatus.RECORDING;
  }

  private void handleDistributionMessage(SenderKeyDistributionMessage distributionMessage,
      ContactJid from) {
    var groupName = new SenderKeyName(distributionMessage.groupId(), from.toSignalAddress());
    var builder = new GroupBuilder(socketHandler.keys());
    var message = SignalDistributionMessage.ofSerialized(distributionMessage.data());
    builder.createIncoming(groupName, message);
  }

  private void handleProtocolMessage(MessageInfo info, ProtocolMessage protocolMessage,
      boolean peer) {
    switch (protocolMessage.protocolType()) {
      case HISTORY_SYNC_NOTIFICATION -> {
        var history = downloadHistorySync(protocolMessage);
        handleHistorySync(history);
        if (history.progress() != null) {
          socketHandler.onHistorySyncProgress(history.progress(), history.syncType() == RECENT);
        }
        socketHandler.sendSyncReceipt(info, "hist_sync");
      }
      case APP_STATE_SYNC_KEY_SHARE -> {
        socketHandler.keys()
            .addAppKeys(protocolMessage.appStateSyncKeyShare().keys());
        if (socketHandler.store().initialSnapshot()) {
          return;
        }
        socketHandler.pullInitialPatches()
            .thenRunAsync(this::subscribeToAllPresences)
            .thenRunAsync(socketHandler::onContacts)
            .thenRunAsync(() -> socketHandler.store().initialSnapshot(true));
      }
      case REVOKE -> socketHandler.store()
          .findMessageById(info.chat(), protocolMessage.key()
              .id())
          .ifPresent(message -> {
            info.chat()
                .removeMessage(message);
            socketHandler.onMessageDeleted(message, true);
          });
      case EPHEMERAL_SETTING -> {
        info.chat()
            .ephemeralMessagesToggleTime(info.timestampInSeconds())
            .ephemeralMessageDuration(ChatEphemeralTimer.of(protocolMessage.ephemeralExpiration()));
        var setting = new EphemeralSetting((int) protocolMessage.ephemeralExpiration(),
            info.timestampInSeconds());
        socketHandler.onSetting(setting);
      }
    }
    // Save data to prevent session termination from messing up the cypher
    socketHandler.store()
        .serialize(true);
    if (!peer) {
      return;
    }
    socketHandler.sendSyncReceipt(info, "peer_msg");
  }

  private HistorySync downloadHistorySync(ProtocolMessage protocolMessage) {
    try {
      var compressed = Medias.download(protocolMessage.historySyncNotification());
      Validate.isTrue(compressed.isPresent(),
          "Cannot download history sync");
      var decompressed = BytesHelper.deflate(compressed.get());
      return PROTOBUF.readMessage(decompressed, HistorySync.class);
    } catch (IOException exception) {
      throw new UncheckedIOException("Cannot read history sync", exception);
    }
  }

  private void handleHistorySync(HistorySync history) {
    switch (history.syncType()) {
      case INITIAL_STATUS_V3 -> {
        history.statusV3Messages()
            .forEach(socketHandler.store()::addStatus);
        socketHandler.onMediaStatus();
      }
      case INITIAL_BOOTSTRAP -> {
        historyCache.addAll(history.conversations());
        history.conversations()
            .forEach(this::updateChatMessages);
        socketHandler.onChats();
      }
      case PUSH_NAME -> history.pushNames()
          .forEach(this::handNewPushName);
      case RECENT, FULL -> handleRecentMessagesListener(history);
      case NON_BLOCKING_DATA -> history.pastParticipants()
          .forEach(pastParticipants -> socketHandler.store()
              .findChatByJid(pastParticipants.groupJid())
              .orElseGet(() -> socketHandler.store()
                  .addChat(pastParticipants.groupJid()))
              .pastParticipants()
              .addAll(pastParticipants.pastParticipants()));
    }
  }

  private void subscribeToAllPresences() {
    if (!socketHandler.options()
        .automaticallySubscribeToPresences()) {
      return;
    }
    socketHandler.store()
        .contacts()
        .forEach(socketHandler::subscribeToPresence);
  }

  private void handleRecentMessagesListener(HistorySync history) {
    history.conversations()
        .forEach(this::updateChatMessages);
    historyCache.forEach(
        cached -> socketHandler.onChatRecentMessages(cached, !history.conversations()
            .contains(cached)));
    historyCache.removeIf(entry -> !history.conversations()
        .contains(entry));
  }

  private void updateChatMessages(Chat carrier) {
    socketHandler.store()
        .findChatByJid(carrier.jid())
        .ifPresentOrElse(chat -> carrier.messages()
            .stream()
            .map(socketHandler.store()::attribute)
            .forEach(chat::addMessage), () -> socketHandler.store().addChat(carrier));
  }

  private void handNewPushName(PushName pushName) {
    var jid = ContactJid.of(pushName.id());
    socketHandler.store()
        .findContactByJid(jid)
        .orElseGet(() -> {
          var contact = socketHandler.store()
              .addContact(jid);
          socketHandler.onNewContact(contact);
          return contact;
        })
        .chosenName(pushName.name());
    var action = ContactAction.of(pushName.name(), null, null);
    socketHandler.onAction(action, MessageIndexInfo.of("contact", null, null, true));
  }

  @SafeVarargs
  private <T> List<T> toSingleList(List<T>... all) {
    return Stream.of(all)
        .filter(Objects::nonNull)
        .flatMap(Collection::stream)
        .toList();
  }

  @Override
  public void dispose() {
    super.dispose();
    retries.clear();
    groupsCache.invalidateAll();
    devicesCache.invalidateAll();
    historyCache.clear();
  }

  private record MessageDecodeResult(byte[] message, Throwable error) {
    public boolean hasError() {
      return error != null;
    }
  }
}
