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
                .hasMessage("iban ou accountNumber: Campo obrigatório.");
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

    @Test
    void shouldRejectPixBeneficiaryWithoutPixKey() {
        BeneficiaryCreateRequest request = new BeneficiaryCreateRequest();
        request.setReceivingMethod(ReceivingMethod.PIX_KEY);
        request.setNickname("Fornecedor PIX");
        request.setCurrency("BRL");

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pixKey: Campo obrigatório.");
    }

    @Test
    void shouldAcceptPixBeneficiaryWithBrlOrWithoutCurrency() {
        BeneficiaryCreateRequest withBrl = pixRequest();
        BeneficiaryCreateRequest withoutCurrency = pixRequest();
        withoutCurrency.setCurrency(null);

        assertThatCode(() -> validator.validate(withBrl)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(withoutCurrency)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectPixBeneficiaryWithNonBrlCurrency() {
        BeneficiaryCreateRequest request = pixRequest();
        request.setCurrency("USD");

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("currency deve ser BRL para beneficiário PIX");
    }

    @Test
    void shouldAcceptBrlBankAccountOnPixRail() {
        assertThatCode(() -> validator.validate(brlBankAccountRequest())).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectBrlBankAccountWithAnotherCurrency() {
        BeneficiaryCreateRequest request = brlBankAccountRequest();
        request.setCurrency("USD");

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("currency deve ser BRL para o paymentRail informado");
    }

    @Test
    void shouldRejectBrlBankAccountWithoutAccountType() {
        BeneficiaryCreateRequest request = brlBankAccountRequest();
        request.setAccountType(null);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("accountType: Campo obrigatório.");
    }

    @Test
    void shouldAcceptEurBankAccountOnSepaRail() {
        assertThatCode(() -> validator.validate(sepaRequest())).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectSepaBankAccountWithoutIban() {
        BeneficiaryCreateRequest request = sepaRequest();
        request.setIban(" ");

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("iban: Campo obrigatório.");
    }

    @Test
    void shouldRejectSepaBankAccountWithAlpha2CountryCode() {
        BeneficiaryCreateRequest request = sepaRequest();
        request.setCountryCode("FR");

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("countryCode deve ter 3 caracteres");
    }

    @Test
    void shouldRejectUnsupportedPaymentRail() {
        BeneficiaryCreateRequest request = bankAccountRequest();
        request.setPaymentRail("boleto");

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("paymentRail não suportado pela Avenia: boleto");
    }

    @Test
    void shouldTreatMissingPaymentRailAsSwift() {
        BeneficiaryCreateRequest request = bankAccountRequest();
        request.setPaymentRail(null);
        request.setCurrency("BRL");

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("currency não suportada para transferência SWIFT: BRL");
    }

    @Test
    void shouldAcceptSwiftBankAccountIdentifiedByIban() {
        BeneficiaryCreateRequest request = bankAccountRequest();
        request.setAccountNumber(null);
        request.setIban("GB29NWBK60161331926819");

        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    private BeneficiaryCreateRequest pixRequest() {
        BeneficiaryCreateRequest request = new BeneficiaryCreateRequest();
        request.setReceivingMethod(ReceivingMethod.PIX_KEY);
        request.setNickname("Fornecedor PIX");
        request.setPixKey("financeiro@fornecedor.test");
        request.setCurrency("BRL");
        return request;
    }

    private BeneficiaryCreateRequest brlBankAccountRequest() {
        BeneficiaryCreateRequest request = new BeneficiaryCreateRequest();
        request.setReceivingMethod(ReceivingMethod.BANK_ACCOUNT);
        request.setNickname("Fornecedor Nacional");
        request.setPaymentRail("pix");
        request.setCurrency("BRL");
        request.setBankCode("341");
        request.setBranchNumber("0001");
        request.setAccountNumber("12345-6");
        request.setAccountType("checking");
        return request;
    }

    private BeneficiaryCreateRequest sepaRequest() {
        BeneficiaryCreateRequest request = new BeneficiaryCreateRequest();
        request.setReceivingMethod(ReceivingMethod.BANK_ACCOUNT);
        request.setNickname("Supplier EUR");
        request.setPaymentRail("sepa");
        request.setCurrency("EUR");
        request.setIban("FR1420041010050500013M02606");
        request.setCountryCode("FRA");
        return request;
    }

    private BeneficiaryCreateRequest bankAccountRequest() {
        BeneficiaryCreateRequest request = new BeneficiaryCreateRequest();
        request.setReceivingMethod(ReceivingMethod.BANK_ACCOUNT);
        request.setNickname("Fornecedor Principal");
        request.setBankName("Banco Teste");
        request.setSwiftBic("TESTBRSPXXX");
        request.setAccountNumber("12345-6");
        request.setCurrency("USD");
        request.setPaymentRail("international_swift");
        request.setAddressLine1("123 Main Street");
        request.setCity("New York");
        request.setStateProvinceRegion("NY");
        request.setPostalCode("10001");
        request.setCountryCode("US");
        request.setBankCountryCode("US");
        return request;
    }

    private BeneficiaryCreateRequest walletRequest() {
        BeneficiaryCreateRequest request = new BeneficiaryCreateRequest();
        request.setReceivingMethod(ReceivingMethod.CRYPTO_WALLET);
        request.setNickname("Carteira Principal");
        request.setWalletAddress("0xABCDEF1234567890");
        request.setBlockchainNetwork(BlockchainNetwork.ethereum);
        request.setCurrency("USDC");
        return request;
    }
}
