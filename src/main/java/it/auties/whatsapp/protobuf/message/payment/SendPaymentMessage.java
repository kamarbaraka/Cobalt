package it.auties.whatsapp.protobuf.message.payment;

import com.fasterxml.jackson.annotation.JsonProperty;
import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.protobuf.message.model.MessageContainer;
import it.auties.whatsapp.protobuf.message.model.MessageKey;
import it.auties.whatsapp.protobuf.message.model.PaymentMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * A model class that represents a WhatsappMessage to confirm a {@link RequestPaymentMessage}.
 * This class is only a model, this means that changing its values will have no real effect on WhatsappWeb's servers.
 * Instead, methods inside {@link Whatsapp} should be used.
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder(builderMethodName = "newSendPaymentMessage", buildMethodName = "create")
@Accessors(fluent = true)
public final class SendPaymentMessage implements PaymentMessage {
  /**
   * The caption message, that is the message below the payment confirmation
   */
  @JsonProperty("2")
  private MessageContainer noteMessage;

  /**
   * The key of the original {@link RequestPaymentMessage} that this message confirms
   */
  @JsonProperty("3")
  private MessageKey requestMessageKey;
}
