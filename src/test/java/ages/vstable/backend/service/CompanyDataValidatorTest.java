package ages.vstable.backend.service;

import ages.vstable.backend.dto.company.CompanyNormalizedData;
import ages.vstable.backend.exception.UnprocessableEntityException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompanyDataValidatorTest {

    private final CompanyDataValidator validator =
            new CompanyDataValidator();

    private static final String VALID_CNPJ = "11222333000181";
    private static final String VALID_CNPJ_MASKED =
            "11.222.333/0001-81";

    private static final String VALID_CEP = "90000000";
    private static final String VALID_CEP_MASKED = "90000-000";

    // ---------------------------------------------------------
    // Successful normalization
    // ---------------------------------------------------------

    @Test
    void shouldNormalizeValidBrazilianCompanyData() {
        CompanyNormalizedData result = validator.normalize(
                "  Empresa Teste LTDA  ",
                VALID_CNPJ_MASKED,
                "Brasil",
                VALID_CEP_MASKED,
                " Porto Alegre ",
                " RS "
        );

        assertThat(result.legalName())
                .isEqualTo("Empresa Teste LTDA");

        assertThat(result.cnpj())
                .isEqualTo(VALID_CNPJ);

        assertThat(result.country())
                .isEqualTo("Brasil");

        assertThat(result.zipCode())
                .isEqualTo(VALID_CEP);

        assertThat(result.city())
                .isEqualTo("Porto Alegre");

        assertThat(result.state())
                .isEqualTo("RS");
    }

    @Test
    void shouldNormalizeCnpjWithDigitsOnly() {
        CompanyNormalizedData result = validator.normalize(
                "Empresa Teste",
                VALID_CNPJ,
                "Brasil",
                VALID_CEP,
                "Porto Alegre",
                "RS"
        );

        assertThat(result.cnpj()).isEqualTo(VALID_CNPJ);
    }

    @Test
    void shouldNormalizeCnpjWithMask() {
        CompanyNormalizedData result = validator.normalize(
                "Empresa Teste",
                VALID_CNPJ_MASKED,
                "Brasil",
                VALID_CEP,
                "Porto Alegre",
                "RS"
        );

        assertThat(result.cnpj()).isEqualTo(VALID_CNPJ);
    }

    @Test
    void shouldNormalizeCepWithDigitsOnly() {
        CompanyNormalizedData result = validator.normalize(
                "Empresa Teste",
                VALID_CNPJ,
                "Brasil",
                VALID_CEP,
                "Porto Alegre",
                "RS"
        );

        assertThat(result.zipCode()).isEqualTo(VALID_CEP);
    }

    @Test
    void shouldNormalizeCepWithMask() {
        CompanyNormalizedData result = validator.normalize(
                "Empresa Teste",
                VALID_CNPJ,
                "Brasil",
                VALID_CEP_MASKED,
                "Porto Alegre",
                "RS"
        );

        assertThat(result.zipCode()).isEqualTo(VALID_CEP);
    }

    @Test
    void shouldReturnNullWhenCityIsNull() {
        CompanyNormalizedData result = validator.normalize(
                "Empresa Teste",
                VALID_CNPJ,
                "Brasil",
                VALID_CEP,
                null,
                "RS"
        );

        assertThat(result.city()).isNull();
    }

    @Test
    void shouldReturnNullWhenCityIsBlank() {
        CompanyNormalizedData result = validator.normalize(
                "Empresa Teste",
                VALID_CNPJ,
                "Brasil",
                VALID_CEP,
                "   ",
                "RS"
        );

        assertThat(result.city()).isNull();
    }

    @Test
    void shouldAcceptNonBrazilianCountryWithoutCepFormatValidation() {
        CompanyNormalizedData result = validator.normalize(
                "Empresa Teste",
                VALID_CNPJ,
                "Argentina",
                "ABC-123",
                "Buenos Aires",
                "BA"
        );

        assertThat(result.country()).isEqualTo("Argentina");
        assertThat(result.zipCode()).isEqualTo("ABC-123");
    }

    // ---------------------------------------------------------
    // Required fields
    // ---------------------------------------------------------

    @Test
    void shouldRejectBlankLegalName() {
        assertThatThrownBy(() -> validator.normalize(
                "   ",
                VALID_CNPJ,
                "Brasil",
                VALID_CEP,
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("legalName: must not be blank");
    }

    @Test
    void shouldRejectNullLegalName() {
        assertThatThrownBy(() -> validator.normalize(
                null,
                VALID_CNPJ,
                "Brasil",
                VALID_CEP,
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("legalName: must not be blank");
    }

    @Test
    void shouldRejectLegalNameShorterThanThreeCharacters() {
        assertThatThrownBy(() -> validator.normalize(
                "AB",
                VALID_CNPJ,
                "Brasil",
                VALID_CEP,
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("legalName: length must be between 3 and 255");
    }

    @Test
    void shouldRejectBlankCountry() {
        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                VALID_CNPJ,
                "   ",
                VALID_CEP,
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("country: must not be blank");
    }

    @Test
    void shouldRejectBlankState() {
        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                VALID_CNPJ,
                "Brasil",
                VALID_CEP,
                "Porto Alegre",
                "   "
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("state: must not be blank");
    }

    @Test
    void shouldRejectBlankZipCode() {
        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                VALID_CNPJ,
                "Brasil",
                "   ",
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("zipCode: must not be blank");
    }

    // ---------------------------------------------------------
    // Maximum length
    // ---------------------------------------------------------

    @Test
    void shouldRejectLegalNameLongerThan255Characters() {
        String legalName = "A".repeat(256);

        assertThatThrownBy(() -> validator.normalize(
                legalName,
                VALID_CNPJ,
                "Brasil",
                VALID_CEP,
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("legalName: maximum length is 255");
    }

    @Test
    void shouldRejectCountryLongerThan100Characters() {
        String country = "A".repeat(101);

        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                VALID_CNPJ,
                country,
                VALID_CEP,
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("country: maximum length is 100");
    }

    @Test
    void shouldRejectStateLongerThan100Characters() {
        String state = "A".repeat(101);

        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                VALID_CNPJ,
                "Brasil",
                VALID_CEP,
                "Porto Alegre",
                state
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("state: maximum length is 100");
    }

    @Test
    void shouldRejectCityLongerThan255Characters() {
        String city = "A".repeat(256);

        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                VALID_CNPJ,
                "Brasil",
                VALID_CEP,
                city,
                "RS"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("city: maximum length is 255");
    }

    @Test
    void shouldRejectZipCodeLongerThan20Characters() {
        String zipCode = "A".repeat(21);

        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                VALID_CNPJ,
                "Brasil",
                zipCode,
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("zipCode: maximum length is 20");
    }

    // ---------------------------------------------------------
    // CNPJ validation
    // ---------------------------------------------------------

    @Test
    void shouldRejectNullCnpj() {
        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                null,
                "Brasil",
                VALID_CEP,
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("CNPJ has an invalid format");
    }

    @Test
    void shouldRejectBlankCnpj() {
        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                "   ",
                "Brasil",
                VALID_CEP,
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("CNPJ has an invalid format");
    }

    @Test
    void shouldRejectCnpjWithWrongLength() {
        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                "123456789",
                "Brasil",
                VALID_CEP,
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("CNPJ has an invalid format");
    }

    @Test
    void shouldRejectCnpjWithLetters() {
        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                "1122233300018A",
                "Brasil",
                VALID_CEP,
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("CNPJ has an invalid format");
    }

    @Test
    void shouldRejectCnpjWithInvalidMask() {
        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                "11.222.333/0001-810",
                "Brasil",
                VALID_CEP,
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("CNPJ has an invalid format");
    }

    @Test
    void shouldRejectCnpjWithAllEqualDigits() {
        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                "11111111111111",
                "Brasil",
                VALID_CEP,
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("CNPJ has an invalid format");
    }

    @Test
    void shouldRejectCnpjWithInvalidFirstVerificationDigit() {
        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                "11222333000191",
                "Brasil",
                VALID_CEP,
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("CNPJ has an invalid format");
    }

    @Test
    void shouldRejectCnpjWithInvalidSecondVerificationDigit() {
        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                "11222333000180",
                "Brasil",
                VALID_CEP,
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("CNPJ has an invalid format");
    }

    // ---------------------------------------------------------
    // CEP validation
    // ---------------------------------------------------------

    @Test
    void shouldRejectBrazilianCepWithWrongLength() {
        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                VALID_CNPJ,
                "Brasil",
                "1234567",
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("Zip code has an invalid format");
    }

    @Test
    void shouldRejectBrazilianCepWithLetters() {
        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                VALID_CNPJ,
                "Brasil",
                "90000-00A",
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("Zip code has an invalid format");
    }

    @Test
    void shouldRejectBrazilianCepWithInvalidMask() {
        assertThatThrownBy(() -> validator.normalize(
                "Empresa Teste",
                VALID_CNPJ,
                "Brasil",
                "9000-0000",
                "Porto Alegre",
                "RS"
        ))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("Zip code has an invalid format");
    }

    @Test
    void shouldAcceptLowercaseBrazilCountry() {
        CompanyNormalizedData result = validator.normalize(
                "Empresa Teste",
                VALID_CNPJ,
                "brasil",
                VALID_CEP,
                "Porto Alegre",
                "RS"
        );

        assertThat(result.zipCode()).isEqualTo(VALID_CEP);
    }
}