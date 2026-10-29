package ages.vstable.backend.external.blindpay;

import ages.vstable.backend.external.blindpay.dto.BlindPayBankAccountResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayCreateBankAccountRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayCreateCustomerRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayCreateWalletRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayCustomerCreatedResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayCustomerResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayEvmPayoutRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayInitiateTosRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayInitiateTosResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayinQuoteRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayinQuoteResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayinResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayPayoutResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayQuoteRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayQuoteResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayRfiResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayRfiSubmitResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayUpdateCustomerRequest;
import ages.vstable.backend.external.blindpay.dto.BlindPayUploadResponse;
import ages.vstable.backend.external.blindpay.dto.BlindPayWalletResponse;

import java.util.Map;

/**
 * Operações da BlindPay usadas pelo backend. Services devem depender desta
 * interface, e não de {@link BlindPayClient}, para testar regras sem rede.
 *
 * <p>Toda operação de escrita aceita uma {@code idempotencyKey}: repetir a chamada
 * com a mesma chave e o mesmo corpo devolve a resposta original sem executar a
 * operação de novo (a BlindPay guarda a chave por 24h). Use um identificador
 * estável do lado do backend, como o id da transação local. {@code null} envia a
 * requisição sem o header.
 */
public interface BlindPayGateway {

    BlindPayCustomerCreatedResponse createCustomer(BlindPayCreateCustomerRequest request, String idempotencyKey);

    BlindPayCustomerResponse getCustomer(String customerId);

    void updateCustomer(String customerId, BlindPayUpdateCustomerRequest request, String idempotencyKey);

    /** Inicia o aceite hospedado dos termos e devolve a URL a abrir no navegador do cliente. */
    BlindPayInitiateTosResponse initiateTermsOfService(BlindPayInitiateTosRequest request);

    BlindPayUploadResponse uploadFile(
            BlindPayApi.Upload.Bucket bucket, String filename, String contentType, byte[] content);

    /** @return o RFI pendente do cliente, ou {@code null} se não houver */
    BlindPayRfiResponse getCustomerRfi(String customerId);

    BlindPayRfiSubmitResponse submitCustomerRfi(
            String customerId, Map<String, Object> response, String idempotencyKey);

    BlindPayBankAccountResponse createBankAccount(
            String customerId, BlindPayCreateBankAccountRequest request, String idempotencyKey);

    BlindPayBankAccountResponse getBankAccount(String customerId, String bankAccountId);

    BlindPayWalletResponse createWallet(
            String customerId, BlindPayCreateWalletRequest request, String idempotencyKey);

    /** Carteira gerenciada pela BlindPay, com o endereço usado como origem dos payouts. */
    BlindPayWalletResponse getWallet(String customerId, String walletId);

    BlindPayQuoteResponse createQuote(BlindPayQuoteRequest request, String idempotencyKey);

    BlindPayPayoutResponse createEvmPayout(BlindPayEvmPayoutRequest request, String idempotencyKey);

    BlindPayPayoutResponse getPayout(String payoutId);

    BlindPayPayinQuoteResponse createPayinQuote(BlindPayPayinQuoteRequest request, String idempotencyKey);

    BlindPayPayinResponse createEvmPayin(String payinQuoteId, String idempotencyKey);

    BlindPayPayinResponse getPayin(String payinId);
}
