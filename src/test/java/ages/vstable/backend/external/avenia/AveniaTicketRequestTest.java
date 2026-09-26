package ages.vstable.backend.external.avenia;

import ages.vstable.backend.external.avenia.dto.AveniaTicketRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AveniaTicketRequestTest {

    @Test
    void representsAllSupportedInputAndOutputDetails() {
        UUID beneficiaryId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        AveniaTicketRequest.BrlPixInput pixInput = new AveniaTicketRequest.BrlPixInput("message");
        AveniaTicketRequest.BlockchainOutput blockchainOutput =
                new AveniaTicketRequest.BlockchainOutput(beneficiaryId, "POLYGON", "0x123", "memo");
        AveniaTicketRequest.BrlPixOutput pixOutput = new AveniaTicketRequest.BrlPixOutput(
                beneficiaryId, "pix-key", "message", "User", "123", "0001",
                "12345", "CHECKING", "12345678901", "br-code");
        AveniaTicketRequest.UsdOutput usdOutput =
                new AveniaTicketRequest.UsdOutput(beneficiaryId, "ach", "wire");
        AveniaTicketRequest.EurSepaOutput eurOutput =
                new AveniaTicketRequest.EurSepaOutput(beneficiaryId, "sepa");
        AveniaTicketRequest.SwiftOutput swiftOutput =
                new AveniaTicketRequest.SwiftOutput(beneficiaryId, documentId);

        assertEquals("message", pixInput.additionalData());
        assertEquals("POLYGON", blockchainOutput.walletChain());
        assertEquals("pix-key", pixOutput.pixKey());
        assertEquals("ach", usdOutput.achReference());
        assertEquals("sepa", eurOutput.sepaReference());
        assertEquals(documentId, swiftOutput.uploadedDocumentId());
    }
}
