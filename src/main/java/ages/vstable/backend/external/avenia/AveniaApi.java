package ages.vstable.backend.external.avenia;

/**
 * Field names, enums and limits of the Avenia sandbox API (base URL in
 * {@link AveniaProperties}). Reference only, mirrors the provider's contract —
 * no behavior here yet; see {@link AveniaClient}.
 */
public final class AveniaApi {

    private AveniaApi() {
    }

    /** POST /v2/auth/login, /v2/auth/validate-login, /v2/auth/refresh */
    public static final class Auth {
        private Auth() {
        }

        public static final String EMAIL = "email";
        public static final String PASSWORD = "password";
        public static final String EMAIL_TOKEN = "emailToken";
        public static final String ACCESS_TOKEN = "accessToken";
        public static final String REFRESH_TOKEN = "refreshToken";
    }

    /** POST /v2/account/sub-accounts — one per PME client. */
    public static final class SubAccount {
        private SubAccount() {
        }

        public static final String ID = "id";
        public static final String SUB_ACCOUNT_ID = "subAccountId";
        public static final String MAIN_ACCOUNT_ID = "mainAccountId";
        public static final String ACCOUNT_TYPE = "accountType";
        public static final String NAME = "name";
        public static final String CREATED_AT = "createdAt";
        public static final String CURSOR = "cursor";

        public enum AccountType {
            INDIVIDUAL
        }
    }

    /** POST /v2/kyc/new-level-1/web-sdk */
    public static final class Kyc {
        private Kyc() {
        }

        public static final String REDIRECT_URL = "redirectUrl";
        public static final String ATTEMPT_ID = "attemptId";
        public static final String AUTHORIZED_REPRESENTATIVE_URL = "authorizedRepresentativeUrl";
        public static final String BASIC_COMPANY_DATA_URL = "basicCompanyDataUrl";
        public static final String KYC_URL = "kycUrl";
    }

    /** POST /v2/account/beneficiaries/bank-accounts/{brl|swift|usd|eur|cop|ars|mxn}/ */
    public static final class Beneficiary {
        private Beneficiary() {
        }

        public static final String ALIAS = "alias";
        public static final String DESCRIPTION = "description";
        public static final String PIX_KEY = "pixKey";
        public static final String TAX_ID = "taxId";
        public static final String USER_NAME = "userName";
        public static final String BANK_CODE = "bankCode";
        public static final String BRANCH_CODE = "branchCode";
        public static final String ACCOUNT_NUMBER = "accountNumber";
        public static final String ACCOUNT_TYPE = "accountType";
        public static final String BENEFICIARY_NAME = "beneficiaryName";
        public static final String BENEFICIARY_TYPE = "beneficiaryType";
        public static final String ACCOUNT_STANDARD = "accountStandard";
        public static final String ACCOUNT_VALUE = "accountValue";
        public static final String BIC = "bic";
        public static final String BANK_NAME = "bankName";
        public static final String BANK_COUNTRY = "bankCountry";
        public static final String BENEFICIARY_STREET = "beneficiaryStreet";
        public static final String BENEFICIARY_CITY = "beneficiaryCity";
        public static final String BENEFICIARY_STATE = "beneficiaryState";
        public static final String BENEFICIARY_POSTAL_CODE = "beneficiaryPostalCode";
        public static final String BENEFICIARY_COUNTRY = "beneficiaryCountry";
        public static final String CURRENCY = "currency";
        public static final String INTERMEDIARY_BIC = "intermediaryBic";
        public static final String BANK_OTHER_IDENTIFIER = "bankOtherIdentifier";
        public static final String CREATED_AFTER = "createdAfter";
        public static final String CREATED_BEFORE = "createdBefore";

        /** IDs used when assembling a ticket. */
        public static final String BENEFICIARY_BRL_BANK_ACCOUNT_ID = "beneficiaryBrlBankAccountId";
        public static final String BENEFICIARY_USD_BANK_ACCOUNT_ID = "beneficiaryUsdBankAccountId";
        public static final String BENEFICIARY_EUR_BANK_ACCOUNT_ID = "beneficiaryEurBankAccountId";
        public static final String BENEFICIARY_COP_BANK_ACCOUNT_ID = "beneficiaryCopBankAccountId";
        public static final String BENEFICIARY_ARS_BANK_ACCOUNT_ID = "beneficiaryArsBankAccountId";
        public static final String BENEFICIARY_MXN_BANK_ACCOUNT_ID = "beneficiaryMxnBankAccountId";
        public static final String BENEFICIARY_SWIFT_BANK_ACCOUNT_ID = "beneficiarySwiftBankAccountId";
        public static final String BENEFICIARY_WALLET_ID = "beneficiaryWalletId";

        public enum AccountType {
            checking, payment, savings, salary
        }

        public enum BeneficiaryType {
            company, individual
        }

        public enum AccountStandard {
            iban, account_number
        }
    }

    /** POST /v2/account/documents — required for SWIFT beneficiaries. */
    public static final class Document {
        private Document() {
        }

        public static final String DOCUMENT_TYPE = "documentType";
        public static final String IS_DOUBLE_SIDED = "isDoubleSided";
        public static final String UPLOAD_URL_FRONT = "uploadURLFront";
        public static final String READY = "ready";

        public enum DocumentType {
            INVOICE
        }
    }

    /** GET /v2/account/quote/fixed-rate */
    public static final class Quote {
        private Quote() {
        }

        public static final String INPUT_CURRENCY = "inputCurrency";
        public static final String INPUT_PAYMENT_METHOD = "inputPaymentMethod";
        public static final String OUTPUT_CURRENCY = "outputCurrency";
        public static final String OUTPUT_PAYMENT_METHOD = "outputPaymentMethod";
        public static final String INPUT_AMOUNT = "inputAmount";
        public static final String OUTPUT_AMOUNT = "outputAmount";
        public static final String INPUT_THIRD_PARTY = "inputThirdParty";
        public static final String OUTPUT_THIRD_PARTY = "outputThirdParty";
        public static final String BLOCKCHAIN_SEND_METHOD = "blockchainSendMethod";
        public static final String MARKUP_FLOATING_FEE = "markupFloatingFee";
        public static final String MARKUP_INPUT_FIXED_FEE = "markupInputFixedFee";
        public static final String MARKUP_OUTPUT_FIXED_FEE = "markupOutputFixedFee";
        public static final String MARKUP_CURRENCY = "markupCurrency";
        public static final String TICKET_REFUND_ID = "ticketRefundId";
        public static final String OUTPUT_BR_CODE = "outputBrCode";
        public static final String QUOTE_TOKEN = "quoteToken";
        public static final String MARKUP_AMOUNT = "markupAmount";
        public static final String BASE_PRICE = "basePrice";
        public static final String PAIR_NAME = "pairName";
        public static final String APPLIED_FEES = "appliedFees";

        /** inputAmount XOR outputAmount; inputThirdParty/outputThirdParty are always false. */
        public static final int QUOTE_TTL_SECONDS = 15;

        public enum Currency {
            BRL, USD, EUR, COP, ARS, MXN, BRLA, USDC, USDCe, USDT, EURC
        }

        public enum BlockchainSendMethod {
            PERMIT, TRANSFER
        }
    }

    /** POST /v2/account/tickets/ */
    public static final class Ticket {
        private Ticket() {
        }

        public static final String QUOTE_TOKEN = "quoteToken";
        public static final String EXTERNAL_ID = "externalId";
        public static final String CUSTOM_DURATION = "customDuration";
        public static final String BR_CODE = "brCode";
        public static final String EXPIRATION = "expiration";
        public static final String STATUS = "status";
        public static final String REASON = "reason";
        public static final String FAILURE_REASON = "failureReason";
        public static final String END_TO_END_ID = "endToEndId";

        public static final int CUSTOM_DURATION_MIN_SECONDS = 300;
        public static final int CUSTOM_DURATION_MAX_SECONDS_WITH_CONVERSION = 600;
        public static final int CUSTOM_DURATION_MAX_SECONDS_NO_CONVERSION = 259200;

        public enum Status {
            UNPAID, PROCESSING, ON_HOLD, PAID, FAILED, PARTIAL_FAILED, CANCELED
        }
    }

    /** POST/PATCH /v2/notifications/webhooks/ */
    public static final class Webhook {
        private Webhook() {
        }

        public static final String WEBHOOK_ID = "webhookId";
        public static final String WEBHOOK_URL = "webhookUrl";
        public static final String SUBSCRIPTIONS = "subscriptions";
        public static final String SIGNATURE_HEADER = "Signature";
        public static final String WEBHOOK_EVENT_ID = "webhookEventId";
        public static final String RESPONSE_STATUS = "responseStatus";
        public static final String ACKNOWLEDGED = "acknowledged";

        public enum Subscription {
            TICKET, KYC, LIMIT_UPDATE
        }

        public enum TicketEvent {
            TICKET_CREATED, DEPOSIT_PROCESSING, TICKET_ON_HOLD, DEPOSIT_SUCCESS,
            DEPOSIT_FAILED, DELIVERY_PROCESSING, DELIVERY_SUCCESS, DELIVERY_FAILED,
            TICKET_COMPLETE
        }
    }

    /** GET /v2/account/statement */
    public static final class Statement {
        private Statement() {
        }

        public static final String TOKEN = "token";
        public static final String BALANCE_CHANGE = "balanceChange";
        public static final String FINAL_BALANCE = "finalBalance";

        public enum Token {
            BRLA, USDC, USDT, EURC
        }
    }

    /** Shared numeric/UUID constants from the sandbox reference. */
    public static final class Limits {
        private Limits() {
        }

        public static final int FIAT_DECIMALS = 2;
        public static final int CRYPTO_DECIMALS = 6;
        public static final int SANDBOX_PIX_AUTOPAY_MAX_BRL = 1000;
        public static final int COP_MIN_PAYIN_AFTER_FEES = 10_000;
        public static final int COP_MXN_MIN_PAYOUT_USDC = 5;
        public static final int BRE_B_CAP_USDC = 3_000;
        public static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
    }
}
