package it.auties.whatsapp.model.signal.message;

import it.auties.protobuf.base.ProtobufProperty;
import it.auties.whatsapp.util.BytesHelper;
import it.auties.whatsapp.util.Protobuf;
import it.auties.whatsapp.util.Spec;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import java.util.Arrays;

import static it.auties.protobuf.base.ProtobufType.BYTES;
import static it.auties.protobuf.base.ProtobufType.UINT32;

@AllArgsConstructor
@Data
@Builder
@Jacksonized
@Accessors(fluent = true)
public final class SignalPreKeyMessage implements SignalProtocolMessage {
    private int version;

    @ProtobufProperty(index = 1, type = UINT32)
    private Integer preKeyId;

    @ProtobufProperty(index = 2, type = BYTES)
    private byte[] baseKey;

    @ProtobufProperty(index = 3, type = BYTES)
    private byte[] identityKey;

    @ProtobufProperty(index = 4, type = BYTES)
    private byte[] serializedSignalMessage;

    @ProtobufProperty(index = 5, type = UINT32)
    private Integer registrationId;

    @ProtobufProperty(index = 6, type = UINT32)
    private Integer signedPreKeyId;

    private byte[] serialized;

    public SignalPreKeyMessage(Integer preKeyId, byte[] baseKey, byte[] identityKey, byte[] serializedSignalMessage, int registrationId, int signedPreKeyId) {
        this.version = Spec.Signal.CURRENT_VERSION;
        this.preKeyId = preKeyId;
        this.baseKey = baseKey;
        this.identityKey = identityKey;
        this.serializedSignalMessage = serializedSignalMessage;
        this.registrationId = registrationId;
        this.signedPreKeyId = signedPreKeyId;
        this.serialized = BytesHelper.concat(serializedVersion(), Protobuf.writeMessage(this));
    }

    public static SignalPreKeyMessage ofSerialized(byte[] serialized) {
        return Protobuf.readMessage(Arrays.copyOfRange(serialized, 1, serialized.length), SignalPreKeyMessage.class)
                .version(BytesHelper.bytesToVersion(serialized[0]))
                .serialized(serialized);
    }

    public SignalMessage signalMessage() {
        return SignalMessage.ofSerialized(serializedSignalMessage);
    }
}
