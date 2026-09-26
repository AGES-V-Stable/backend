package ages.vstable.backend.external.avenia;

import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import ages.vstable.backend.external.avenia.dto.AveniaTicketRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTicketResponse;

public interface AveniaGateway {

    AveniaQuoteResponse createQuote(AveniaQuoteRequest request);

    AveniaTicketResponse createTicket(AveniaTicketRequest request);
}
