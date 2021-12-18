package it.auties.whatsapp.protobuf.button;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import it.auties.whatsapp.protobuf.info.NativeFlowInfo;
import lombok.*;
import lombok.experimental.Accessors;

import java.util.Arrays;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@Accessors(fluent = true)
public class Button {
  @JsonProperty("4")
  @JsonPropertyDescription("NativeFlowInfo")
  private NativeFlowInfo nativeFlowInfo;

  @JsonProperty("3")
  @JsonPropertyDescription("ButtonType")
  private ButtonType type;

  @JsonProperty("2")
  @JsonPropertyDescription("ButtonText")
  private ButtonText buttonText;

  @JsonProperty("1")
  @JsonPropertyDescription("string")
  private String buttonId;

  @Accessors(fluent = true)
  public enum ButtonType {
    UNKNOWN(0),
    RESPONSE(1),
    NATIVE_FLOW(2);

    private final @Getter int index;

    ButtonType(int index) {
      this.index = index;
    }

    @JsonCreator
    public static ButtonType forIndex(int index) {
      return Arrays.stream(values())
          .filter(entry -> entry.index() == index)
          .findFirst()
          .orElse(null);
    }
  }
}
