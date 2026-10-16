package ages.vstable.backend.dto.blindpay;

import ages.vstable.backend.external.blindpay.BlindPayApi;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BlindPayKybRequestValidationTest {

    private static final String FILE = "https://files.blindpay.test/doc.pdf";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void completeBusinessRequest_isValid() {
        assertThat(validator.validate(request(owner(FILE), "BR", FILE, "1000000_9999999"))).isEmpty();
    }

    @Test
    void missingIncorporationDocumentAndOwners_areReported() {
        BlindPayKybRequest request = new BlindPayKybRequest("finance@acme.test", "Acme", null, "Rua A", null,
                "POA", "RS", "BR", "90000000", null, LocalDate.of(2020, 1, 1), null,
                BlindPayApi.Customer.BusinessType.llc, "Importação", "423690", "1000000_9999999",
                "investments", "business_expenses", null, null, null, null, null, FILE,
                null, null, null, null, List.of());

        assertThat(fields(validator.validate(request))).contains("incorporationDocFile", "owners");
    }

    @Test
    void invalidCountryRevenueAndNonHttpsFile_areReported() {
        Set<ConstraintViolation<BlindPayKybRequest>> violations =
                validator.validate(request(owner("http://files/doc.png"), "Brasil", "ftp://doc", "lots"));

        assertThat(fields(violations)).contains(
                "country", "incorporationDocFile", "estimatedAnnualRevenue", "owners[0].idDocFrontFile");
    }

    private List<String> fields(Set<ConstraintViolation<BlindPayKybRequest>> violations) {
        return violations.stream().map(violation -> violation.getPropertyPath().toString()).toList();
    }

    private BlindPayKybRequest request(BlindPayKybRequest.Owner owner, String country, String incorporation,
                                       String revenue) {
        return new BlindPayKybRequest("finance@acme.test", "Acme Importadora LTDA", null, "Av. Ipiranga, 6681",
                null, "Porto Alegre", "RS", country, "90619900", "+5551999999999", LocalDate.of(2020, 4, 23),
                "https://acme.test", BlindPayApi.Customer.BusinessType.corporation, "Importação de eletrônicos",
                "423690", revenue, "business_dividends_or_profits", "business_expenses", null,
                "business_transactions", null, false, incorporation, FILE, null, null, null, null, List.of(owner));
    }

    private BlindPayKybRequest.Owner owner(String idDocFrontFile) {
        return new BlindPayKybRequest.Owner(null, BlindPayApi.Customer.OwnerRole.beneficial_controlling,
                "Ana", "Souza", LocalDate.of(1985, 3, 10), "12345678909", null, "Rua A, 10", null,
                "Porto Alegre", "RS", "BR", "90000000", "BR", BlindPayApi.Customer.IdDocType.PASSPORT,
                idDocFrontFile, null, null, null, 100, "CEO");
    }
}
