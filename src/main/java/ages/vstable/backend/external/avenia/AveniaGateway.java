package ages.vstable.backend.external.avenia;

import ages.vstable.backend.external.avenia.dto.AveniaBeneficiaryResponse;
import ages.vstable.backend.external.avenia.dto.AveniaBrlBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaEurBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteResponse;
import ages.vstable.backend.external.avenia.dto.AveniaSwiftBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTicketRequest;
import ages.vstable.backend.external.avenia.dto.AveniaTicketResponse;
import ages.vstable.backend.external.avenia.dto.AveniaUsdBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaWalletBeneficiaryRequest;

public interface AveniaGateway {

    AveniaQuoteResponse createQuote(AveniaQuoteRequest request);

    AveniaTicketResponse createTicket(AveniaTicketRequest request);

    AveniaBeneficiaryResponse createBrlBeneficiary(AveniaBrlBeneficiaryRequest request);

    AveniaBeneficiaryResponse createUsdBeneficiary(AveniaUsdBeneficiaryRequest request);

    AveniaBeneficiaryResponse createEurBeneficiary(AveniaEurBeneficiaryRequest request);

    AveniaBeneficiaryResponse createSwiftBeneficiary(AveniaSwiftBeneficiaryRequest request);

    AveniaBeneficiaryResponse createWalletBeneficiary(AveniaWalletBeneficiaryRequest request);
}
