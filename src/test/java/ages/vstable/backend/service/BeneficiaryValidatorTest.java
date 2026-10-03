package ages.vstable.backend.service;

import ages.vstable.backend.dto.beneficiary.BeneficiaryCreateRequest;
import ages.vstable.backend.entity.enums.BlockchainNetwork;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BeneficiaryValidatorTest {

    private final BeneficiaryValidator validator = new BeneficiaryValidator();

    @Test
    void shouldAcceptValidBankAccountRequest() {
        BeneficiaryCreateRequest request = bankAccountRequest();

        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @Test
    void shouldAcceptValidWalletRequest() {
        BeneficiaryCreateRequest request = walletRequest();

        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectBankAccountMissingBankName() {
        BeneficiaryCreateRequest request = bankAccountRequest();
        request.setBankName(null);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bankName: Campo obrigatório.");
    }

    @Test
    void shouldRejectBankAccountMissingSwiftBic() {
        BeneficiaryCreateRequest request = bankAccountRequest();
        request.setSwiftBic("   ");

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("swiftBic: Campo obrigatório.");
    }

    @Test
    void shouldRejectBankAccountMissingAccountNumber() {
        BeneficiaryCreateRequest request = bankAccountRequest();
        request.setAccountNumber(null);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("accountNumber: Campo obrigatório.");
    }

    @Test
    void shouldRejectBankAccountMissingCurrency() {
        BeneficiaryCreateRequest request = bankAccountRequest();
        request.setCurrency(null);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("currency: Campo obrigatório.");
    }

    @Test
    void shouldRejectBankAccountMissingNickname() {
        BeneficiaryCreateRequest request = bankAccountRequest();
        request.setNickname(null);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("nickname: Campo obrigatório.");
    }

    @Test
    void shouldRejectWalletMissingWalletAddress() {
        BeneficiaryCreateRequest request = walletRequest();
        request.setWalletAddress(null);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("walletAddress: Campo obrigatório.");
    }

    @Test
    void shouldRejectWalletMissingBlockchainNetwork() {
        BeneficiaryCreateRequest request = walletRequest();
        request.setBlockchainNetwork(null);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("blockchainNetwork: Campo obrigatório.");
    }

    @Test
    void shouldRejectWalletMissingNickname() {
        BeneficiaryCreateRequest request = walletRequest();
        request.setNickname(null);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("nickname: Campo obrigatório.");
    }

    private BeneficiaryCreateRequest bankAccountRequest() {
        BeneficiaryCreateRequest request = new BeneficiaryCreateRequest();
        request.setReceivingMethod(ReceivingMethod.BANK_ACCOUNT);
        request.setNickname("Fornecedor Principal");
        request.setBankName("Banco Teste");
        request.setSwiftBic("TESTBRSPXXX");
        request.setAccountNumber("12345-6");
        request.setCurrency("BRL");
        return request;
    }

    private BeneficiaryCreateRequest walletRequest() {
        BeneficiaryCreateRequest request = new BeneficiaryCreateRequest();
        request.setReceivingMethod(ReceivingMethod.CRYPTO_WALLET);
        request.setNickname("Carteira Principal");
        request.setWalletAddress("0xABCDEF1234567890");
        request.setBlockchainNetwork(BlockchainNetwork.ethereum);
        return request;
    }
}
