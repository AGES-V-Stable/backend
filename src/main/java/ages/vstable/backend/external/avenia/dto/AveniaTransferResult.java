package ages.vstable.backend.external.avenia.dto;

public record AveniaTransferResult(
        AveniaQuoteResponse quote,
        AveniaTicketResponse ticket
) {
}
