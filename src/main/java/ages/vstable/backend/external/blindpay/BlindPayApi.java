package ages.vstable.backend.external.blindpay;

/**
 * Field names, enums and limits of the BlindPay API (base URL in
 * {@link BlindPayProperties}). Reference only, mirrors the provider's contract —
 * no behavior here yet; see {@link BlindPayClient}.
 */
public final class BlindPayApi {

    private BlindPayApi() {
    }

    /** Every resource id is a 15-char string prefixed by its kind, e.g. "in_000000000000". */
    public static final class IdPrefix {
        private IdPrefix() {
        }

        public static final String INSTANCE = "in_";
        public static final String CUSTOMER = "re_";
        public static final String BANK_ACCOUNT = "ba_";
        public static final String VIRTUAL_ACCOUNT = "va_";
        public static final String BLOCKCHAIN_WALLET = "bw_";
        public static final String OFFRAMP_WALLET = "ow_";
        public static final String QUOTE = "qu_";
        public static final String PAYIN_QUOTE = "pq_";
        public static final String PAYOUT = "po_";
        public static final String PAYIN = "pi_";
        public static final String WALLET = "bl_";
        public static final String TRANSFER = "tr_";
        public static final String PAYABLE = "pb_";
        public static final String WEBHOOK_ENDPOINT = "we_";
        public static final String PARTNER_FEE = "fe_";
        public static final String BENEFICIAL_OWNER = "ub_";
        public static final String USER = "us_";
        public static final String TERMS_OF_SERVICE = "to_";
        public static final String LIMIT_INCREASE = "rl_";
        public static final String YIELD_TRANSACTION = "yt_";
    }

    /** Every request/response amount is an integer of cents (1000 = $10.00), never a float. */
    public static final class Instance {
        private Instance() {
        }

        public static final String NAME = "name";
        public static final String CUSTOMER_INVITE_REDIRECT_URL = "customer_invite_redirect_url";
        public static final String EMAIL_NOTIFICATIONS = "email_notifications";
        public static final String REQUIRE_PASSKEY = "require_passkey";
        public static final String COMPLIANCE_EMAILS = "compliance_emails";
        public static final String CUSTOMER_RFI_EMAILS_ENABLED = "customer_rfi_emails_enabled";
        public static final String USER_ROLE = "user_role";

        public enum Role {
            owner, admin, finance, checker, operations, developer, viewer
        }
    }

    /** POST/GET/PUT/DELETE .../instances/{instance_id}/customers */
    public static final class Customer {
        private Customer() {
        }

        public static final String TYPE = "type";
        public static final String KYC_TYPE = "kyc_type";
        public static final String KYC_STATUS = "kyc_status";
        public static final String AML_STATUS = "aml_status";
        public static final String EMAIL = "email";
        public static final String TAX_ID = "tax_id";
        public static final String ADDRESS_LINE_1 = "address_line_1";
        public static final String ADDRESS_LINE_2 = "address_line_2";
        public static final String CITY = "city";
        public static final String STATE_PROVINCE_REGION = "state_province_region";
        public static final String COUNTRY = "country";
        public static final String POSTAL_CODE = "postal_code";
        public static final String PHONE_NUMBER = "phone_number";
        public static final String FIRST_NAME = "first_name";
        public static final String LAST_NAME = "last_name";
        public static final String DATE_OF_BIRTH = "date_of_birth";
        public static final String ID_DOC_COUNTRY = "id_doc_country";
        public static final String ID_DOC_TYPE = "id_doc_type";
        public static final String ID_DOC_FRONT_FILE = "id_doc_front_file";
        public static final String ID_DOC_BACK_FILE = "id_doc_back_file";
        public static final String SELFIE_FILE = "selfie_file";
        public static final String LEGAL_NAME = "legal_name";
        public static final String FORMATION_DATE = "formation_date";
        public static final String WEBSITE = "website";
        public static final String BUSINESS_TYPE = "business_type";
        public static final String BUSINESS_DESCRIPTION = "business_description";
        public static final String BUSINESS_INDUSTRY = "business_industry";
        public static final String ESTIMATED_ANNUAL_REVENUE = "estimated_annual_revenue";
        public static final String SOURCE_OF_WEALTH = "source_of_wealth";
        public static final String PUBLICLY_TRADED = "publicly_traded";
        public static final String OCCUPATION = "occupation";
        public static final String OWNERS = "owners";
        public static final String OWNERSHIP_PERCENTAGE = "ownership_percentage";
        public static final String PURPOSE_OF_TRANSACTIONS = "purpose_of_transactions";
        public static final String ACCOUNT_PURPOSE = "account_purpose";
        public static final String IS_FBO = "is_fbo";
        public static final String IS_NESTED = "is_nested";
        public static final String LIMIT = "limit";
        public static final String TOS_ID = "tos_id";
        public static final String EXTERNAL_ID = "external_id";

        public enum Type {
            individual, business
        }

        public enum KycType {
            light, standard, enhanced
        }

        public enum KycStatus {
            verifying, approved, rejected, deprecated, pending_review,
            awaiting_contract, compliance_request, approved_rfi
        }

        public enum AmlStatus {
            clear, hit, error
        }

        public enum IdDocType {
            PASSPORT, ID_CARD, DRIVERS
        }

        public enum OwnerRole {
            beneficial_controlling, beneficial_owner, controlling_person
        }

        public enum TaxType {
            SSN, ITIN
        }

        public enum BusinessType {
            corporation, llc, partnership, sole_proprietorship, trust, non_profit
        }
    }

    /** RFI (Request for Information) — compliance asking a customer/instance for more data. */
    public static final class Rfi {
        private Rfi() {
        }

        public static final String STATUS = "status";
        public static final String REQUEST = "request";
        public static final String RESPONSE = "response";
        public static final String EXPIRES_AT = "expires_at";
        public static final String SUBMITTED_AT = "submitted_at";

        public enum Status {
            pending, submitted, expired, cancelled
        }
    }

    /** POST/GET/DELETE .../customers/{customer_id}/bank-accounts */
    public static final class BankAccount {
        private BankAccount() {
        }

        public static final String TYPE = "type";
        public static final String STATUS = "status";
        public static final String NAME = "name";
        public static final String BENEFICIARY_NAME = "beneficiary_name";
        public static final String RECIPIENT_RELATIONSHIP = "recipient_relationship";
        public static final String PIX_KEY = "pix_key";
        public static final String ROUTING_NUMBER = "routing_number";
        public static final String ACCOUNT_NUMBER = "account_number";
        public static final String ACCOUNT_TYPE = "account_type";
        public static final String ACCOUNT_CLASS = "account_class";
        public static final String SWIFT_CODE_BIC = "swift_code_bic";
        public static final String SWIFT_ACCOUNT_NUMBER_IBAN = "swift_account_number_iban";
        public static final String SEPA_IBAN = "sepa_iban";
        public static final String SPEI_CLABE = "spei_clabe";
        public static final String TRANSFERS_TYPE = "transfers_type";
        public static final String TRANSFERS_ACCOUNT = "transfers_account";
        public static final String PLAID_CONNECTED_AT = "plaid_connected_at";

        public enum Type {
            wire, ach, pix, pix_safe, ted, spei_bitso, transfers_bitso,
            ach_cop_bitso, international_swift, rtp, sepa
        }

        public enum Status {
            verifying, approved, rejected, deprecated
        }

        public enum AccountType {
            checking, saving
        }

        public enum AccountClass {
            individual, business
        }

        public enum TransfersType {
            CVU, CBU, ALIAS
        }
    }

    /** POST/GET/PUT .../customers/{customer_id}/virtual-accounts */
    public static final class VirtualAccount {
        private VirtualAccount() {
        }

        public static final String BANKING_PARTNER = "banking_partner";
        public static final String KYC_STATUS = "kyc_status";
        public static final String TOKEN = "token";
        public static final String BLOCKCHAIN_WALLET_ID = "blockchain_wallet_id";
        public static final String PARTNER_FEE_ID = "partner_fee_id";
        public static final String SOLE_PROPRIETOR_DOC_TYPE = "sole_proprietor_doc_type";
        public static final String SOLE_PROPRIETOR_DOC_FILE = "sole_proprietor_doc_file";

        public enum BankingPartner {
            jpmorgan, citi, hsbc, cfsb, portage
        }

        public enum Token {
            USDC, USDT, USDB
        }
    }

    /** POST/GET/DELETE .../customers/{customer_id}/blockchain-wallets */
    public static final class BlockchainWallet {
        private BlockchainWallet() {
        }

        public static final String NAME = "name";
        public static final String NETWORK = "network";
        public static final String ADDRESS = "address";
        public static final String SIGNATURE_TX_HASH = "signature_tx_hash";
        public static final String IS_ACCOUNT_ABSTRACTION = "is_account_abstraction";

        public enum Network {
            base, sepolia, arbitrum_sepolia, base_sepolia, arbitrum, polygon,
            polygon_amoy, ethereum, stellar, stellar_testnet, tron, solana,
            solana_devnet, tempo, tempo_testnet, arc, arc_testnet
        }
    }

    /** POST/GET .../bank-accounts/{bank_account_id}/offramp-wallets */
    public static final class OfframpWallet {
        private OfframpWallet() {
        }

        public static final String EXTERNAL_ID = "external_id";
        public static final String CIRCLE_WALLET_ID = "circle_wallet_id";
        public static final String NETWORK = "network";
        public static final String ADDRESS = "address";
    }

    /** POST .../instances/{instance_id}/quotes (payout) and .../quotes/fx */
    public static final class Quote {
        private Quote() {
        }

        public static final String BANK_ACCOUNT_ID = "bank_account_id";
        public static final String PAYABLE_ID = "payable_id";
        public static final String NETWORK = "network";
        public static final String TOKEN = "token";
        public static final String DESCRIPTION = "description";
        public static final String PARTNER_FEE_ID = "partner_fee_id";
        public static final String REFUND_WALLET_ADDRESS = "refund_wallet_address";
        public static final String CURRENCY_TYPE = "currency_type";
        public static final String COVER_FEES = "cover_fees";
        public static final String REQUEST_AMOUNT = "request_amount";
        public static final String EXPIRES_AT = "expires_at";
        public static final String COMMERCIAL_QUOTATION = "commercial_quotation";
        public static final String BLINDPAY_QUOTATION = "blindpay_quotation";
        public static final String RECEIVER_AMOUNT = "receiver_amount";
        public static final String SENDER_AMOUNT = "sender_amount";

        public static final int REQUEST_AMOUNT_MIN_CENTS = 500;

        public enum CurrencyType {
            sender, receiver
        }
    }

    /** POST .../instances/{instance_id}/payouts/{stellar|solana|evm} */
    public static final class Payout {
        private Payout() {
        }

        public static final String QUOTE_ID = "quote_id";
        public static final String SENDER_WALLET_ADDRESS = "sender_wallet_address";
        public static final String WALLET_ID = "wallet_id";
        public static final String SIGNED_TRANSACTION = "signed_transaction";
        public static final String STATUS = "status";
        public static final String STEP = "step";
        public static final String BILLING_FEE_AMOUNT = "billing_fee_amount";
        public static final String TRANSACTION_FEE_AMOUNT = "transaction_fee_amount";
        public static final String PARTNER_FEE = "partner_fee";
        public static final String TRANSACTION_DOCUMENT_TYPE = "transaction_document_type";
        public static final String TRANSACTION_DOCUMENT_ID = "transaction_document_id";
        public static final String TRANSACTION_DOCUMENT_FILE = "transaction_document_file";

        public enum Status {
            processing, failed, refunded, completed, on_hold
        }

        /** Shared across payout/payin/transfer tracking_* objects. */
        public enum Step {
            processing, on_hold, pending_review, pending_refund_review, completed
        }

        public enum DocumentType {
            invoice, purchase_order, delivery_slip, contract,
            customs_declaration, bill_of_lading, others
        }
    }

    /** POST .../payin-quotes and .../payins/evm */
    public static final class Payin {
        private Payin() {
        }

        public static final String PAYIN_QUOTE_ID = "payin_quote_id";
        public static final String PAYMENT_METHOD = "payment_method";
        public static final String FUNDING_BANK_ACCOUNT_ID = "funding_bank_account_id";
        public static final String IS_OTC = "is_otc";
        public static final String STATUS = "status";
        public static final String PIX_CODE = "pix_code";
        public static final String MEMO_CODE = "memo_code";
        public static final String CLABE = "clabe";
        public static final String MANUAL_EXECUTION_STATUS = "manual_execution_status";
        public static final String PAYMENT_TOKEN = "payment_token";
        public static final String CARDHOLDER_NAME = "cardholder_name";
        public static final String BILLING_ADDRESS = "billing_address";

        public enum Status {
            processing, on_hold, failed, refunded, completed
        }

        public enum PaymentMethod {
            ach, ach_pull, wire, pix, card, ted, spei, transfers, pse,
            international_swift, rtp
        }

        public enum ManualExecutionStatus {
            pending, concluded, failed
        }
    }

    /** POST/GET/DELETE .../customers/{customer_id}/wallets (BlindPay-managed, supports yield) */
    public static final class Wallet {
        private Wallet() {
        }

        public static final String NAME = "name";
        public static final String EXTERNAL_ID = "external_id";
        public static final String NETWORK = "network";
        public static final String YIELD_STATUS = "yield_status";
        public static final String FEE_BPS = "fee_bps";
        public static final String EARNING_AMOUNT = "earning_amount";
        public static final String APY = "apy";

        public enum YieldStatus {
            disabled, enabled, disabling
        }

        public enum YieldTransactionType {
            deposit, redeem, fee
        }

        public enum YieldTrigger {
            sweep, payout, transfer, disable, fee_collection
        }
    }

    /** POST .../transfer-quotes and .../transfers (wallet-to-wallet, cross-chain) */
    public static final class Transfer {
        private Transfer() {
        }

        public static final String TRANSFER_QUOTE_ID = "transfer_quote_id";
        public static final String WALLET_ID = "wallet_id";
        public static final String AMOUNT_REFERENCE = "amount_reference";
        public static final String SENDER_TOKEN = "sender_token";
        public static final String CUSTOMER_WALLET_ADDRESS = "customer_wallet_address";
        public static final String CUSTOMER_TOKEN = "customer_token";
        public static final String CUSTOMER_NETWORK = "customer_network";
        public static final String STATUS = "status";

        public enum Status {
            processing, failed, refunded, completed
        }
    }

    /** POST/GET/DELETE .../instances/{instance_id}/payables (invoice / boleto / PIX QR) */
    public static final class Payable {
        private Payable() {
        }

        public static final String FROM = "from";
        public static final String TO = "to";
        public static final String CURRENCY = "currency";
        public static final String LINE_ITEMS = "line_items";
        public static final String NOTE = "note";
        public static final String DISCOUNT = "discount";
        public static final String TAXES = "taxes";
        public static final String BOLETO_BARCODE = "boleto_barcode";
        public static final String PIX_QRCODE = "pix_qrcode";
        public static final String DOCUMENT_FILE = "document_file";
        public static final String DUE_DATE = "due_date";
        public static final String SCHEDULED_AT = "scheduled_at";
        public static final String STATUS = "status";
        public static final String AMOUNT = "amount";
        public static final String PAYOUT_ID = "payout_id";
        public static final String BANK_ACCOUNT = "bank_account";
        public static final String LAST_ATTEMPT_STATUS = "last_attempt_status";

        public enum Currency {
            BRL, USD, MXN, COP, ARS, EUR
        }

        public enum Status {
            draft, processing, completed, canceled
        }

        public enum LastAttemptStatus {
            failed, refunded
        }
    }

    /** POST/GET/DELETE .../instances/{instance_id}/webhook-endpoints */
    public static final class WebhookEndpoint {
        private WebhookEndpoint() {
        }

        public static final String URL = "url";
        public static final String EVENTS = "events";
        public static final String LAST_EVENT_AT = "last_event_at";
        public static final String KEY = "key";

        public enum Event {
            customer_new, customer_update, customer_delete,
            bankAccount_new,
            payout_new, payout_update, payout_complete, payout_partnerFee,
            blockchainWallet_new,
            payin_new, payin_update, payin_complete, payin_partnerFee,
            tos_accept,
            limitIncrease_new, limitIncrease_update,
            virtualAccount_new, virtualAccount_complete,
            transfer_new, transfer_update, transfer_complete,
            wallet_new, wallet_update, wallet_inbound,
            payable_new, payable_update, payable_complete
        }
    }

    /** GET .../billing/fees and .../partner-fees */
    public static final class Fee {
        private Fee() {
        }

        public static final String PAYIN_FLAT = "payin_flat";
        public static final String PAYIN_PERCENTAGE = "payin_percentage";
        public static final String PAYOUT_FLAT = "payout_flat";
        public static final String PAYOUT_PERCENTAGE = "payout_percentage";
        public static final String NAME = "name";
        public static final String VIRTUAL_ACCOUNT_SET = "virtual_account_set";
    }

    /** POST /v1/upload, /v1/upload/analyze, /v1/upload/extract, /v1/presign */
    public static final class Upload {
        private Upload() {
        }

        public static final String FILE = "file";
        public static final String BUCKET = "bucket";
        public static final String FILE_URL = "file_url";
        public static final String EXPIRES_AT = "expires_at";

        public enum Bucket {
            avatar, onboarding, limit_increase, documents
        }

        /** /v1/upload/extract is rate-limited to this many requests/min per instance. */
        public static final int EXTRACT_RATE_LIMIT_PER_MINUTE = 60;
    }

    /** Shared request conventions. */
    public static final class Request {
        private Request() {
        }

        public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
        public static final String LIMIT = "limit";
        public static final String OFFSET = "offset";
        public static final String STARTING_AFTER = "starting_after";
        public static final String ENDING_BEFORE = "ending_before";
        public static final String DATA = "data";
        public static final String PAGINATION = "pagination";
        public static final String HAS_MORE = "has_more";
    }

    /** Standard error envelope on every non-2xx response. */
    public static final class Error {
        private Error() {
        }

        public static final String SUCCESS = "success";
        public static final String CODE = "code";
        public static final String MESSAGE = "message";
        public static final String DESCRIPTION = "description";
        public static final String ERRORS = "errors";
        public static final String TRACE_ID = "trace_id";
    }
}
