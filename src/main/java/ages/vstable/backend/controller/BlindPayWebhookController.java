package ages.vstable.backend.controller;

import ages.vstable.backend.external.blindpay.BlindPayWebhookVerifier;
import ages.vstable.backend.service.blindpay.BlindPayWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Rota pública para os webhooks da BlindPay. Não usa JWT: a autenticação é a
 * assinatura Svix, conferida sobre o corpo bruto recebido.
 */
@RestController
@RequestMapping("/v1/webhooks/blindpay")
@RequiredArgsConstructor
@Tag(name = "BlindPay - Webhooks")
public class BlindPayWebhookController {

    private final BlindPayWebhookService webhookService;

    @PostMapping
    @Operation(summary = "Recebe webhooks de compliance da BlindPay (customer.new, customer.update, tos.accept)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Evento processado, repetido ou ignorado"),
            @ApiResponse(responseCode = "400", description = "Corpo malformado"),
            @ApiResponse(responseCode = "401", description = "Assinatura inválida ou timestamp fora da tolerância"),
    })
    public ResponseEntity<Void> receive(
            @RequestHeader(value = BlindPayWebhookVerifier.MESSAGE_ID_HEADER, required = false) String messageId,
            @RequestHeader(value = BlindPayWebhookVerifier.TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = BlindPayWebhookVerifier.SIGNATURE_HEADER, required = false) String signature,
            @RequestBody(required = false) byte[] body) {
        BlindPayWebhookService.Result result = webhookService.handle(messageId, timestamp, signature, body);
        if (result == BlindPayWebhookService.Result.INVALID_SIGNATURE) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.noContent().build();
    }
}
