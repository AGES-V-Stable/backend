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
    void walletQualification_acceptsExchangeCustody() {
        BeneficiaryCreateRequest request = walletRequest();
        request.setIsSelfCustody(false);
        request.setCustodianName("Test Exchange");

        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @Test
    void walletQualification_rejectsMissingCustodyChoice() {
        BeneficiaryCreateRequest request = walletRequest();
        request.setIsSelfCustody(null);

        assertThatThrownBy(() -> validator.validate(request)).hasMessageContaining("isSelfCustody");
    }

    @Test
    void walletQualification_rejectsExchangeWithoutCustodian() {
        BeneficiaryCreateRequest request = walletRequest();
        request.setIsSelfCustody(false);

        assertThatThrownBy(() -> validator.validate(request)).hasMessageContaining("custodianName");
    }

    @Test
    void walletQualification_rejectsCustodianOnSelfCustody() {
        BeneficiaryCreateRequest request = walletRequest();
        request.setCustodianName("Test Exchange");

        assertThatThrownBy(() -> validator.validate(request)).hasMessageContaining("custodianName");
    }

    @Test
    void walletQualification_rejectsMissingOrInvalidCountry() {
        BeneficiaryCreateRequest request = walletRequest();
        for (String country : new String[]{null, "", "BRA", "12"}) {
            request.setCounterpartyCountry(country);
            assertThatThrownBy(() -> validator.validate(request)).hasMessageContaining("counterpartyCountry");
        }
    }

    @Test
    void walletQualification_acceptsBoundary100CharacterNames() {
        BeneficiaryCreateRequest request = walletRequest();
        request.setIsSelfCustody(false);
        request.setCounterpartyName("X".repeat(100));
        request.setCustodianName("Y".repeat(100));

        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @Test
    void walletQualification_rejectsMissingLongOrControlCharacterNames() {
        BeneficiaryCreateRequest request = walletRequest();
        for (String name : new String[]{null, " ", "X".repeat(101), "Test\nRecipient"}) {
            request.setCounterpartyName(name);
            assertThatThrownBy(() -> validator.validate(request)).hasMessageContaining("counterpartyName");
        }
    }

    @Test
    void walletQualification_rejectsEconomicGroupForDomesticWallet() {
        BeneficiaryCreateRequest request = walletRequest();
        request.setIsSameEconomicGroup(false);

        assertThatThrownBy(() -> validator.validate(request)).hasMessageContaining("isSameEconomicGroup");
    }

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
        request.setIsSelfCustody(true);
        request.setCounterpartyName("Test Recipient");
        request.setCounterpartyCountry("BR");
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

    @Test
    void shouldRejectWalletMissingCurrency() {
        BeneficiaryCreateRequest request = walletRequest();
        request.setCurrency("   ");

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("currency: Campo obrigatório.");
    }

    @Test
    void shouldRejectBrlBankAccountMissingRequiredFields() {
        BeneficiaryCreateRequest missingCurrency = brlBankAccountRequest();
        missingCurrency.setCurrency(" ");
        assertThatThrownBy(() -> validator.validate(missingCurrency))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("currency: Campo obrigatório.");

        BeneficiaryCreateRequest missingBankCode = brlBankAccountRequest();
        missingBankCode.setBankCode(" ");
        assertThatThrownBy(() -> validator.validate(missingBankCode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bankCode: Campo obrigatório.");

        BeneficiaryCreateRequest missingBranch = brlBankAccountRequest();
        missingBranch.setBranchNumber(" ");
        assertThatThrownBy(() -> validator.validate(missingBranch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("branchNumber: Campo obrigatório.");

        BeneficiaryCreateRequest missingAccount = brlBankAccountRequest();
        missingAccount.setAccountNumber(" ");
        assertThatThrownBy(() -> validator.validate(missingAccount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("accountNumber: Campo obrigatório.");
    }

    @Test
    void shouldValidateUsdBankAccountOnAchAndWireRails() {
        BeneficiaryCreateRequest validUsd = usdBankAccountRequest();
        assertThatCode(() -> validator.validate(validUsd)).doesNotThrowAnyException();

        BeneficiaryCreateRequest wrongCurrency = usdBankAccountRequest();
        wrongCurrency.setCurrency("EUR");
        assertThatThrownBy(() -> validator.validate(wrongCurrency))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("currency deve ser USD para o paymentRail informado");

        BeneficiaryCreateRequest missingBankName = usdBankAccountRequest();
        missingBankName.setBankName(" ");
        assertThatThrownBy(() -> validator.validate(missingBankName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bankName: Campo obrigatório.");

        BeneficiaryCreateRequest missingAccountNumber = usdBankAccountRequest();
        missingAccountNumber.setAccountNumber(" ");
        assertThatThrownBy(() -> validator.validate(missingAccountNumber))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("accountNumber: Campo obrigatório.");

        BeneficiaryCreateRequest missingRoutingNumber = usdBankAccountRequest();
        missingRoutingNumber.setRoutingNumber(" ");
        assertThatThrownBy(() -> validator.validate(missingRoutingNumber))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("routingNumber: Campo obrigatório.");

        BeneficiaryCreateRequest missingAddressLine1 = usdBankAccountRequest();
        missingAddressLine1.setAddressLine1(" ");
        assertThatThrownBy(() -> validator.validate(missingAddressLine1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("addressLine1: Campo obrigatório.");

        BeneficiaryCreateRequest missingCity = usdBankAccountRequest();
        missingCity.setCity(" ");
        assertThatThrownBy(() -> validator.validate(missingCity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("city: Campo obrigatório.");

        BeneficiaryCreateRequest missingState = usdBankAccountRequest();
        missingState.setStateProvinceRegion(" ");
        assertThatThrownBy(() -> validator.validate(missingState))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("stateProvinceRegion: Campo obrigatório.");

        BeneficiaryCreateRequest missingPostalCode = usdBankAccountRequest();
        missingPostalCode.setPostalCode(" ");
        assertThatThrownBy(() -> validator.validate(missingPostalCode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("postalCode: Campo obrigatório.");

        BeneficiaryCreateRequest missingCountryCode = usdBankAccountRequest();
        missingCountryCode.setCountryCode(" ");
        assertThatThrownBy(() -> validator.validate(missingCountryCode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("countryCode: Campo obrigatório.");

        BeneficiaryCreateRequest invalidCountryCode = usdBankAccountRequest();
        invalidCountryCode.setCountryCode("US");
        assertThatThrownBy(() -> validator.validate(invalidCountryCode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("countryCode deve ter 3 caracteres");
    }

    @Test
    void shouldRejectSepaBankAccountWithWrongCurrency() {
        BeneficiaryCreateRequest request = sepaRequest();
        request.setCurrency("USD");

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("currency deve ser EUR para o paymentRail informado");
    }

    @Test
    void shouldRejectSwiftBankAccountMissingAddressAndCountryCodes() {
        BeneficiaryCreateRequest missingAddress = bankAccountRequest();
        missingAddress.setAddressLine1(" ");
        assertThatThrownBy(() -> validator.validate(missingAddress))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("addressLine1: Campo obrigatório.");

        BeneficiaryCreateRequest missingCity = bankAccountRequest();
        missingCity.setCity(" ");
        assertThatThrownBy(() -> validator.validate(missingCity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("city: Campo obrigatório.");

        BeneficiaryCreateRequest invalidCountry = bankAccountRequest();
        invalidCountry.setCountryCode("USA");
        assertThatThrownBy(() -> validator.validate(invalidCountry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("countryCode deve ter 2 caracteres");

        BeneficiaryCreateRequest invalidBankCountry = bankAccountRequest();
        invalidBankCountry.setBankCountryCode("USA");
        assertThatThrownBy(() -> validator.validate(invalidBankCountry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bankCountryCode deve ter 2 caracteres");
    }

    private BeneficiaryCreateRequest usdBankAccountRequest() {
        BeneficiaryCreateRequest request = new BeneficiaryCreateRequest();
        request.setReceivingMethod(ReceivingMethod.BANK_ACCOUNT);
        request.setNickname("Fornecedor USD");
        request.setPaymentRail("ach");
        request.setCurrency("USD");
        request.setBankName("Bank of America");
        request.setAccountNumber("987654321");
        request.setRoutingNumber("026009593");
        request.setAddressLine1("100 Federal St");
        request.setCity("Boston");
        request.setStateProvinceRegion("MA");
        request.setPostalCode("02110");
        request.setCountryCode("USA");
        return request;
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
        request.setIsSelfCustody(true);
        request.setCounterpartyName("Test Recipient");
        request.setCounterpartyCountry("BR");
        request.setWalletAddress("0xABCDEF1234567890");
        request.setBlockchainNetwork(BlockchainNetwork.ethereum);
        request.setCurrency("USDC");
        return request;
    }
}
