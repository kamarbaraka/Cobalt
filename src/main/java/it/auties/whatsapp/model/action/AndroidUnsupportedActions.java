package it.auties.whatsapp.model.action;

import it.auties.protobuf.base.ProtobufProperty;
import it.auties.whatsapp.binary.BinaryPatchType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import static it.auties.protobuf.base.ProtobufType.BOOL;

/**
 * A model clas that represents unsupported actions for android
 */
@AllArgsConstructor
@Data
@Builder
@Jacksonized
@Accessors(fluent = true)
public final class AndroidUnsupportedActions implements Action {
    /**
     * Whether unsupported actions are allowed
     */
    @ProtobufProperty(index = 1, type = BOOL)
    private boolean allowed;

    /**
     * The name of this action
     *
     * @return a non-null string
     */
    @Override
    public String indexName() {
        return "android_unsupported_actions";
    }

    /**
     * The version of this action
     *
     * @return a non-null string
     */
    @Override
    public int actionVersion() {
        return 4;
    }

    /**
     * The type of this action
     *
     * @return a non-null string
     */
    @Override
    public BinaryPatchType actionType() {
        return null;
    }
}
