package ages.vstable.backend.dto.blindpay;

import ages.vstable.backend.external.blindpay.BlindPayApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Cadastro KYB da empresa na BlindPay ({@code type=business}, {@code kyc_type=standard}).
 * CNPJ, {@code external_id} e {@code tos_id} vêm do backend, nunca do cliente HTTP.
 * Arquivos são URLs devolvidas por {@code POST /v1/blindpay/compliance/documents}.
 * Nenhum destes dados é persistido no backend.
 */
public record BlindPayKybRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 255) String legalName,
        @Size(max = 255) String alternateName,
        @NotBlank @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 100) String stateProvinceRegion,
        @NotBlank @Pattern(regexp = COUNTRY, message = COUNTRY_MESSAGE) String country,
        @NotBlank @Size(max = 20) String postalCode,
        @Pattern(regexp = PHONE, message = "must use the E.164 format, e.g. +5551999999999") String phoneNumber,
        @NotNull @PastOrPresent LocalDate formationDate,
        @Pattern(regexp = HTTPS_URL, message = HTTPS_URL_MESSAGE) @Size(max = 2048) String website,
        @NotNull BlindPayApi.Customer.BusinessType businessType,
        @NotBlank @Size(max = 1000) String businessDescription,
        @NotBlank @Pattern(regexp = "\\d{6}", message = "must be a 6-digit NAICS code") String businessIndustry,
        @NotBlank @Pattern(regexp = ESTIMATED_ANNUAL_REVENUE, message = ALLOWED_VALUE_MESSAGE)
        String estimatedAnnualRevenue,
        @NotBlank @Pattern(regexp = SOURCE_OF_WEALTH, message = ALLOWED_VALUE_MESSAGE) String sourceOfWealth,
        @NotBlank @Pattern(regexp = ACCOUNT_PURPOSE, message = ALLOWED_VALUE_MESSAGE) String accountPurpose,
        @Size(max = 512) String accountPurposeOther,
        @Pattern(regexp = PURPOSE_OF_TRANSACTIONS, message = ALLOWED_VALUE_MESSAGE) String purposeOfTransactions,
        @Size(max = 1000) String purposeOfTransactionsExplanation,
        Boolean publiclyTraded,
        @NotBlank @Pattern(regexp = HTTPS_URL, message = HTTPS_URL_MESSAGE) @Size(max = 2048)
        String incorporationDocFile,
        @NotBlank @Pattern(regexp = HTTPS_URL, message = HTTPS_URL_MESSAGE) @Size(max = 2048)
        String proofOfOwnershipDocFile,
        @Pattern(regexp = PROOF_OF_ADDRESS_DOC_TYPE, message = ALLOWED_VALUE_MESSAGE) String proofOfAddressDocType,
        @Pattern(regexp = HTTPS_URL, message = HTTPS_URL_MESSAGE) @Size(max = 2048) String proofOfAddressDocFile,
        @Pattern(regexp = SOURCE_OF_FUNDS_DOC_TYPE, message = ALLOWED_VALUE_MESSAGE) String sourceOfFundsDocType,
        @Pattern(regexp = HTTPS_URL, message = HTTPS_URL_MESSAGE) @Size(max = 2048) String sourceOfFundsDocFile,
        @NotEmpty @Size(max = 10) List<@NotNull @Valid Owner> owners
) {

    static final String COUNTRY = "[A-Z]{2}";
    static final String COUNTRY_MESSAGE = "must be an ISO 3166-1 alpha-2 code, e.g. BR";
    static final String PHONE = "\\+[1-9]\\d{7,14}";
    static final String HTTPS_URL = "https://\\S+";
    static final String HTTPS_URL_MESSAGE = "must be an https URL";
    static final String ALLOWED_VALUE_MESSAGE = "is not an accepted value";
    static final String ESTIMATED_ANNUAL_REVENUE = "0_99999|100000_999999|1000000_9999999|10000000_49999999"
            + "|50000000_249999999|250000000_plus";
    static final String SOURCE_OF_WEALTH = "business_dividends_or_profits|investments|asset_sales"
            + "|client_investor_contributions|gambling|charitable_contributions|inheritance"
            + "|affiliate_or_royalty_income|salary_or_wages|savings";
    static final String ACCOUNT_PURPOSE = "charitable_donations|ecommerce_retail_payments|investment_purposes"
            + "|business_expenses|payments_to_friends_or_family_abroad|personal_or_living_expenses|protect_wealth"
            + "|purchase_goods_and_services|receive_payments_for_goods_and_services|tax_optimization"
            + "|third_party_money_transmission|payroll|treasury_management|other";
    static final String PURPOSE_OF_TRANSACTIONS = "business_transactions|charitable_donations|investment_purposes"
            + "|payments_to_friends_or_family_abroad|personal_or_living_expenses|protect_wealth"
            + "|purchase_good_and_services|receive_payment_for_freelancing|receive_salary|other";
    static final String PROOF_OF_ADDRESS_DOC_TYPE = "UTILITY_BILL|BANK_STATEMENT|RENTAL_AGREEMENT|TAX_DOCUMENT"
            + "|GOVERNMENT_CORRESPONDENCE";
    static final String SOURCE_OF_FUNDS_DOC_TYPE = "business_income|gambling_proceeds|gifts|government_benefits"
            + "|inheritance|investment_loans|pension_retirement|salary|sale_of_assets_real_estate|savings|esops"
            + "|investment_proceeds|someone_else_funds";

    public BlindPayKybRequest {
        owners = owners == null ? null : List.copyOf(owners);
    }

    /**
     * Sócio (beneficial owner, 25% ou mais) ou controlador. {@code id} ({@code ub_...})
     * identifica um sócio já cadastrado e é exigido somente na atualização.
     */
    public record Owner(
            @Size(min = 15, max = 15) String id,
            @NotNull BlindPayApi.Customer.OwnerRole role,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotNull @Past LocalDate dateOfBirth,
            @NotBlank @Size(max = 50) String taxId,
            BlindPayApi.Customer.TaxType taxType,
            @NotBlank @Size(max = 255) String addressLine1,
            @Size(max = 255) String addressLine2,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 100) String stateProvinceRegion,
            @NotBlank @Pattern(regexp = COUNTRY, message = COUNTRY_MESSAGE) String country,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Pattern(regexp = COUNTRY, message = COUNTRY_MESSAGE) String idDocCountry,
            @NotNull BlindPayApi.Customer.IdDocType idDocType,
            @NotBlank @Pattern(regexp = HTTPS_URL, message = HTTPS_URL_MESSAGE) @Size(max = 2048)
            String idDocFrontFile,
            @Pattern(regexp = HTTPS_URL, message = HTTPS_URL_MESSAGE) @Size(max = 2048) String idDocBackFile,
            @Pattern(regexp = PROOF_OF_ADDRESS_DOC_TYPE, message = ALLOWED_VALUE_MESSAGE)
            String proofOfAddressDocType,
            @Pattern(regexp = HTTPS_URL, message = HTTPS_URL_MESSAGE) @Size(max = 2048)
            String proofOfAddressDocFile,
            @Min(0) @Max(100) Integer ownershipPercentage,
            @Size(max = 128) String title
    ) {
    }
}
