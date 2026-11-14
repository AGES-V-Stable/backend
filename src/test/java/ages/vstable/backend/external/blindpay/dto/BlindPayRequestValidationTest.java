package ages.vstable.backend.external.blindpay.dto;

import ages.vstable.backend.external.blindpay.BlindPayApi;
import org.junit.jupiter.api.Test;

import java.util.List;

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
                .email("ana@acme.test")
                .country("BR")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("kycType must be provided");

        assertThatThrownBy(() -> BlindPayCreateCustomerRequest.builder()
                .type(BlindPayApi.Customer.Type.individual)
                .kycType(BlindPayApi.Customer.KycType.standard)
                .email(" ")
                .country("BR")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("email must be provided");

        assertThatThrownBy(() -> BlindPayCreateCustomerRequest.builder()
                .type(BlindPayApi.Customer.Type.individual)
                .kycType(BlindPayApi.Customer.KycType.standard)
                .email("ana@acme.test")
                .country(" ")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("country must be provided");
    }

    @Test
    void rejectsOwnerWithoutRoleOrWithOwnershipOutOfRange() {
        assertThatThrownBy(() -> BlindPayCreateCustomerRequest.Owner.builder().firstName("Ana").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("owner role must be provided");

        assertThatThrownBy(() -> BlindPayCreateCustomerRequest.Owner.builder()
                .role(BlindPayApi.Customer.OwnerRole.beneficial_owner)
                .ownershipPercentage(-1)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ownershipPercentage must be between 0 and 100");

        assertThatThrownBy(() -> BlindPayCreateCustomerRequest.Owner.builder()
                .role(BlindPayApi.Customer.OwnerRole.beneficial_owner)
                .ownershipPercentage(101)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ownershipPercentage must be between 0 and 100");

        assertThatCode(() -> BlindPayCreateCustomerRequest.Owner.builder()
                .role(BlindPayApi.Customer.OwnerRole.beneficial_owner)
                .ownershipPercentage(0)
                .build()).doesNotThrowAnyException();

        assertThatCode(() -> BlindPayCreateCustomerRequest.Owner.builder()
                .role(BlindPayApi.Customer.OwnerRole.beneficial_owner)
                .ownershipPercentage(100)
                .build()).doesNotThrowAnyException();
    }

    @Test
    void rejectsUpdateCustomerWithoutRequiredFieldsOrOwnerId() {
        assertThatThrownBy(() -> BlindPayUpdateCustomerRequest.builder()
                .email(" ")
                .country("BR")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("email must be provided");

        assertThatThrownBy(() -> BlindPayUpdateCustomerRequest.builder()
                .email("ana@acme.test")
                .country(" ")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("country must be provided");

        assertThatThrownBy(() -> BlindPayUpdateCustomerRequest.builder()
                .email("ana@acme.test")
                .country("BR")
                .owners(List.of(BlindPayCreateCustomerRequest.Owner.builder()
                        .role(BlindPayApi.Customer.OwnerRole.controlling_person)
                        .id(" ")
                        .build()))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("owner id must be provided");
    }

    @Test
    void rejectsWalletAndTosRequestsWithoutRequiredFields() {
        assertThatThrownBy(() -> new BlindPayCreateWalletRequest(null, "ext-1", "Wallet"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("network must be provided");

        assertThatThrownBy(() -> new BlindPayCreateWalletRequest(
                BlindPayApi.BlockchainWallet.Network.base, "ext-1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name must be provided");

        assertThatThrownBy(() -> new BlindPayInitiateTosRequest(null, "re_1", "https://example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("idempotencyKey must be provided");
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
        assertThatThrownBy(() -> quoteBuilder().requestAmount(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestAmount must be provided");

        assertThatThrownBy(() -> quoteBuilder().requestAmount(499L).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestAmount must be at least 500 cents");

        assertThatCode(() -> quoteBuilder().requestAmount(500L).build()).doesNotThrowAnyException();
    }

    @Test
    void rejectsQuoteWithoutDestinationOrRequiredEnumsOrWithTooLongDescription() {
        assertThatThrownBy(() -> quoteBuilder().bankAccountId(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bankAccountId must be provided");

        assertThatThrownBy(() -> quoteBuilder().network(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("network must be provided");

        assertThatThrownBy(() -> quoteBuilder().token(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("token must be provided");

        assertThatThrownBy(() -> quoteBuilder().currencyType(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("currencyType must be provided");

        assertThatCode(() -> quoteBuilder().description("x".repeat(128)).build())
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> quoteBuilder().description("x".repeat(129)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("description must have at most 128 characters");
    }

    @Test
    void requiresQuoteIdAndExactlyOneFundingSourceForPayout() {
        assertThatThrownBy(() -> new BlindPayEvmPayoutRequest(" ", "0xabc", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("quoteId must be provided");

        // A API recusa os dois campos juntos (conferido na instância de desenvolvimento).
        assertThatThrownBy(() -> new BlindPayEvmPayoutRequest("qu_000000000001", "0xabc", "bl_000000000001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exactly one of senderWalletAddress or walletId must be provided");

        assertThatThrownBy(() -> new BlindPayEvmPayoutRequest("qu_000000000001", null, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exactly one of senderWalletAddress or walletId must be provided");
    }

    @Test
    void managedWalletPayoutUsesOnlyTheWalletAddress() {
        BlindPayEvmPayoutRequest managed = BlindPayEvmPayoutRequest.fromManagedWallet("qu_000000000001", "0xabc");

        assertThat(managed.senderWalletAddress()).isEqualTo("0xabc");
        assertThat(managed.walletId()).isNull();
    }

    @Test
    void requiresDestinationWalletRequiredEnumsAndMinimumAmountForPayinQuote() {
        assertThatThrownBy(() -> payinQuoteBuilder().walletId(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exactly one of blockchainWalletId or walletId must be provided");

        assertThatThrownBy(() -> payinQuoteBuilder().blockchainWalletId("bw_000000000001").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Exactly one of blockchainWalletId or walletId must be provided");

        assertThatThrownBy(() -> payinQuoteBuilder().paymentMethod(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("paymentMethod must be provided");

        assertThatThrownBy(() -> payinQuoteBuilder().token(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("token must be provided");

        assertThatThrownBy(() -> payinQuoteBuilder().currencyType(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("currencyType must be provided");

        assertThatThrownBy(() -> payinQuoteBuilder().requestAmount(499L).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestAmount must be at least 500 cents");
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
