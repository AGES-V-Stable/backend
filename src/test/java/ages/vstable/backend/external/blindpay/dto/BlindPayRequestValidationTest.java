package ages.vstable.backend.external.blindpay.dto;

import ages.vstable.backend.external.blindpay.BlindPayApi;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlindPayRequestValidationTest {

    @Test
    void rejectsCustomerWithoutRequiredFields() {
        assertThatThrownBy(() -> BlindPayCreateCustomerRequest.builder()
                .kycType(BlindPayApi.Customer.KycType.standard)
                .email("ana@acme.test")
                .country("BR")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("type must be provided");

        assertThatThrownBy(() -> BlindPayCreateCustomerRequest.builder()
                .type(BlindPayApi.Customer.Type.individual)
                .kycType(BlindPayApi.Customer.KycType.standard)
                .email(" ")
                .country("BR")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("email must be provided");
    }

    @Test
    void rejectsOwnerWithoutRoleOrWithOwnershipOutOfRange() {
        assertThatThrownBy(() -> BlindPayCreateCustomerRequest.Owner.builder().firstName("Ana").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("owner role must be provided");

        assertThatThrownBy(() -> BlindPayCreateCustomerRequest.Owner.builder()
                .role(BlindPayApi.Customer.OwnerRole.beneficial_owner)
                .ownershipPercentage(101)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ownershipPercentage must be between 0 and 100");
    }

    @Test
    void rejectsBankAccountWithoutTypeOrName() {
        assertThatThrownBy(() -> BlindPayCreateBankAccountRequest.builder().name("Fornecedor").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("type must be provided");

        assertThatThrownBy(() -> BlindPayCreateBankAccountRequest.builder()
                .type(BlindPayApi.BankAccount.Type.pix)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name must be provided");
    }

    @Test
    void rejectsQuoteBelowMinimumAmountOfFiveDollars() {
        assertThatThrownBy(() -> quoteBuilder().requestAmount(499L).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestAmount must be at least 500 cents");

        assertThatCode(() -> quoteBuilder().requestAmount(500L).build()).doesNotThrowAnyException();
    }

    @Test
    void rejectsQuoteWithoutDestinationOrWithTooLongDescription() {
        assertThatThrownBy(() -> quoteBuilder().bankAccountId(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bankAccountId must be provided");

        assertThatThrownBy(() -> quoteBuilder().description("x".repeat(129)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("description must have at most 128 characters");
    }

    @Test
    void requiresExactlyOneFundingSourceForPayout() {
        assertThatThrownBy(() -> new BlindPayEvmPayoutRequest("qu_000000000001", "0xabc", "bl_000000000001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exactly one of senderWalletAddress or walletId must be provided");

        assertThatThrownBy(() -> new BlindPayEvmPayoutRequest("qu_000000000001", null, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exactly one of senderWalletAddress or walletId must be provided");

        BlindPayEvmPayoutRequest managed = BlindPayEvmPayoutRequest.fromManagedWallet("qu_000000000001", "bl_1");
        assertThat(managed.walletId()).isEqualTo("bl_1");
        assertThat(managed.senderWalletAddress()).isNull();
    }

    @Test
    void requiresExactlyOneDestinationWalletForPayinQuote() {
        assertThatThrownBy(() -> payinQuoteBuilder().walletId(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exactly one of blockchainWalletId or walletId must be provided");

        assertThatThrownBy(() -> payinQuoteBuilder().blockchainWalletId("bw_000000000001").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exactly one of blockchainWalletId or walletId must be provided");

        assertThatThrownBy(() -> payinQuoteBuilder().paymentMethod(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("paymentMethod must be provided");
    }

    private BlindPayQuoteRequest.BlindPayQuoteRequestBuilder quoteBuilder() {
        return BlindPayQuoteRequest.builder()
                .bankAccountId("ba_000000000001")
                .network(BlindPayApi.BlockchainWallet.Network.base)
                .token(BlindPayApi.VirtualAccount.Token.USDC)
                .currencyType(BlindPayApi.Quote.CurrencyType.sender)
                .requestAmount(1_000L);
    }

    private BlindPayPayinQuoteRequest.BlindPayPayinQuoteRequestBuilder payinQuoteBuilder() {
        return BlindPayPayinQuoteRequest.builder()
                .walletId("bl_000000000001")
                .paymentMethod(BlindPayApi.Payin.PaymentMethod.pix)
                .token(BlindPayApi.VirtualAccount.Token.USDC)
                .currencyType(BlindPayApi.Quote.CurrencyType.sender)
                .requestAmount(1_000L);
    }
}
