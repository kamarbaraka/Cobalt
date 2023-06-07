package it.auties.whatsapp.model.action;

import it.auties.protobuf.base.ProtobufProperty;
import it.auties.whatsapp.binary.PatchType;
import it.auties.whatsapp.model.sync.RecentEmojiWeight;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

import static it.auties.protobuf.base.ProtobufType.MESSAGE;

/**
 * A model clas that represents a change in the weight of recent emojis
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Data
@Builder
@Jacksonized
@Accessors(fluent = true)
public final class RecentEmojiWeightsAction implements Action {
    /**
     * The weight of the emojis
     */
    @ProtobufProperty(index = 1, type = MESSAGE, implementation = RecentEmojiWeight.class, repeated = true)
    private List<RecentEmojiWeight> weights;

    /**
     * Always throws an exception as this action cannot be serialized
     *
     * @return an exception
     */
    @Override
    public String indexName() {
        throw new UnsupportedOperationException("Cannot send action");
    }

    /**
     * Always throws an exception as this action cannot be serialized
     *
     * @return an exception
     */
    @Override
    public int actionVersion() {
        throw new UnsupportedOperationException("Cannot send action");
    }

    /**
     * Always throws an exception as this action cannot be serialized
     *
     * @return an exception
     */
    @Override
    public PatchType actionType() {
        throw new UnsupportedOperationException("Cannot send action");
    }
}
