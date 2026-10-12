package ages.vstable.backend.external.avenia;

import ages.vstable.backend.entity.BeneficiaryEntity;
import ages.vstable.backend.entity.enums.BankAccountType;
import ages.vstable.backend.entity.enums.BlockchainNetwork;
import ages.vstable.backend.entity.enums.ReceivingMethod;
import ages.vstable.backend.external.avenia.dto.AveniaBeneficiaryResponse;
import ages.vstable.backend.external.avenia.dto.AveniaBrlBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaEurBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaSwiftBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaUsdBeneficiaryRequest;
import ages.vstable.backend.external.avenia.dto.AveniaWalletBeneficiaryRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AveniaBeneficiaryServiceTest {

    @Mock
    private AveniaGateway gateway;

    @Test
    void register_withoutSubAccount_rejectsBeforeCallingGateway() {
        BeneficiaryEntity beneficiary = baseBeneficiary()
                .receivingMethod(ReceivingMethod.PIX_KEY)
                .pixKey("pix@example.com")
                .build();

        assertThatThrownBy(() -> new AveniaBeneficiaryService(gateway).register(beneficiary, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subAccountId");

        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    @Test
    void register_pixKey_usesBrlEndpointAndReturnsBankAccountId() {
        UUID externalId = UUID.randomUUID();
        when(gateway.createBrlBeneficiary(any())).thenReturn(new AveniaBeneficiaryResponse(externalId));
        BeneficiaryEntity beneficiary = baseBeneficiary()
                .receivingMethod(ReceivingMethod.PIX_KEY)
                .pixKey("pix@example.com")
                .currency("BRL")
                .build();

        var registration = new AveniaBeneficiaryService(gateway).register(beneficiary, "sub-1");

        ArgumentCaptor<AveniaBrlBeneficiaryRequest> captor =
                ArgumentCaptor.forClass(AveniaBrlBeneficiaryRequest.class);
        verify(gateway).createBrlBeneficiary(captor.capture());
        assertThat(captor.getValue().pixKey()).isEqualTo("pix@example.com");
        assertThat(captor.getValue().accountNumber()).isNull();
        assertThat(captor.getValue().subAccountId()).isEqualTo("sub-1");
        assertThat(registration.bankAccountId()).isEqualTo(externalId);
        assertThat(registration.walletId()).isNull();
    }

    @Test
    void register_wallet_usesWalletEndpointAndUppercaseChain() {
        UUID externalId = UUID.randomUUID();
        when(gateway.createWalletBeneficiary(any())).thenReturn(new AveniaBeneficiaryResponse(externalId));
        BeneficiaryEntity beneficiary = baseBeneficiary()
                .receivingMethod(ReceivingMethod.CRYPTO_WALLET)
                .walletAddress("0xabc")
                .blockchainNetwork(BlockchainNetwork.polygon)
                .currency("USDC")
                .build();

        var registration = new AveniaBeneficiaryService(gateway).register(beneficiary, "sub-1");

        ArgumentCaptor<AveniaWalletBeneficiaryRequest> captor =
                ArgumentCaptor.forClass(AveniaWalletBeneficiaryRequest.class);
        verify(gateway).createWalletBeneficiary(captor.capture());
        assertThat(captor.getValue().walletAddress()).isEqualTo("0xabc");
        assertThat(captor.getValue().walletChain()).isEqualTo("POLYGON");
        assertThat(registration.walletId()).isEqualTo(externalId);
    }

    @Test
    void register_usdWire_usesUsdContract() {
        UUID externalId = UUID.randomUUID();
        when(gateway.createUsdBeneficiary(any())).thenReturn(new AveniaBeneficiaryResponse(externalId));
        BeneficiaryEntity beneficiary = baseBeneficiary()
                .receivingMethod(ReceivingMethod.BANK_ACCOUNT)
                .paymentRail("wire")
                .currency("USD")
                .accountNumber("123456")
                .routingNumber("021000021")
                .bankName("Test Bank")
                .addressLine1("123 Main St")
                .city("New York")
                .stateProvinceRegion("NY")
                .postalCode("10001")
                .countryCode("USA")
                .build();

        new AveniaBeneficiaryService(gateway).register(beneficiary, "sub-1");

        ArgumentCaptor<AveniaUsdBeneficiaryRequest> captor =
                ArgumentCaptor.forClass(AveniaUsdBeneficiaryRequest.class);
        verify(gateway).createUsdBeneficiary(captor.capture());
        assertThat(captor.getValue().bankAccountNumber()).isEqualTo("123456");
        assertThat(captor.getValue().bankRoutingNumber()).isEqualTo("021000021");
        assertThat(captor.getValue().beneficiaryAddress().country()).isEqualTo("USA");
    }

    @Test
    void register_eurSepa_usesEurContract() {
        when(gateway.createEurBeneficiary(any()))
                .thenReturn(new AveniaBeneficiaryResponse(UUID.randomUUID()));
        BeneficiaryEntity beneficiary = baseBeneficiary()
                .receivingMethod(ReceivingMethod.BANK_ACCOUNT)
                .paymentRail("sepa")
                .currency("EUR")
                .iban("FR186323717830259KW1JGZSE48")
                .swiftBic("COBADEFFXXX")
                .countryCode("FRA")
                .build();

        new AveniaBeneficiaryService(gateway).register(beneficiary, "sub-1");

        ArgumentCaptor<AveniaEurBeneficiaryRequest> captor =
                ArgumentCaptor.forClass(AveniaEurBeneficiaryRequest.class);
        verify(gateway).createEurBeneficiary(captor.capture());
        assertThat(captor.getValue().iban()).isEqualTo("FR186323717830259KW1JGZSE48");
        assertThat(captor.getValue().country()).isEqualTo("FRA");
    }

    @Test
    void register_swift_usesSwiftContractAndSelectsIban() {
        when(gateway.createSwiftBeneficiary(any()))
                .thenReturn(new AveniaBeneficiaryResponse(UUID.randomUUID()));
        BeneficiaryEntity beneficiary = baseBeneficiary()
                .receivingMethod(ReceivingMethod.BANK_ACCOUNT)
                .paymentRail("international_swift")
                .currency("GBP")
                .iban("GB29NWBK60161331926819")
                .swiftBic("NWBKGB2L")
                .bankName("NatWest")
                .bankCountryCode("GB")
                .addressLine1("1 King Street")
                .city("London")
                .stateProvinceRegion("England")
                .postalCode("EC2V 8AU")
                .countryCode("GB")
                .routingNumber("20-20-15")
                .build();

        new AveniaBeneficiaryService(gateway).register(beneficiary, "sub-1");

        ArgumentCaptor<AveniaSwiftBeneficiaryRequest> captor =
                ArgumentCaptor.forClass(AveniaSwiftBeneficiaryRequest.class);
        verify(gateway).createSwiftBeneficiary(captor.capture());
        assertThat(captor.getValue().beneficiaryType()).isEqualTo("company");
        assertThat(captor.getValue().accountStandard()).isEqualTo("iban");
        assertThat(captor.getValue().accountValue()).isEqualTo("GB29NWBK60161331926819");
        assertThat(captor.getValue().currency()).isEqualTo("GBP");
        assertThat(captor.getValue().bankOtherIdentifier()).isEqualTo("20-20-15");
    }

    private BeneficiaryEntity.BeneficiaryEntityBuilder baseBeneficiary() {
        return BeneficiaryEntity.builder()
                .nickname("Supplier")
                .internalDescription("Primary account")
                .beneficiaryType("LEGAL_ENTITY")
                .accountHolderName("Supplier Ltd")
                .identificationDocument("123456789")
                .accountType(BankAccountType.checking);
    }
}
