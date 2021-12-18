package it.auties.whatsapp.protobuf.signal.session;

import com.fasterxml.jackson.annotation.*;
import java.util.*;
import lombok.*;
import lombok.experimental.Accessors;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@Accessors(fluent = true)
public class SenderKeyRecordStructure {
  @JsonProperty("1")
  @JsonPropertyDescription("SenderKeyStateStructure")
  @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
  private List<SenderKeyStateStructure> senderKeyStates;
}
