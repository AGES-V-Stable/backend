package ages.vstable.backend.dto.balance;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AddBalanceRequestTest {

    private static final ValidatorFactory FACTORY =
            Validation.buildDefaultValidatorFactory();

    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        FACTORY.close();
    }

    @Test
    void aceitaValorValido() {
        AddBalanceRequest request =
            new AddBalanceRequest(new BigDecimal("100.00"));

        assertThat(VALIDATOR.validate(request)).isEmpty();
    }

    @Test
    void rejeitaValorZero() {
        AddBalanceRequest request =
            new AddBalanceRequest(BigDecimal.ZERO);

        assertThat(VALIDATOR.validate(request)).isNotEmpty();
    }

    @Test
    void rejeitaMaisDeDuasCasasDecimais() {
        AddBalanceRequest request =
            new AddBalanceRequest(new BigDecimal("1.001"));

        assertThat(VALIDATOR.validate(request)).isNotEmpty();
    }

    @Test
    void rejeitaValorAusente() {
        AddBalanceRequest request =
            new AddBalanceRequest(null);

        assertThat(VALIDATOR.validate(request)).isNotEmpty();
    }

    @Test
    void rejeitaValorNegativo() {
        AddBalanceRequest request =
            new AddBalanceRequest(new BigDecimal("-1.00"));

        assertThat(VALIDATOR.validate(request)).isNotEmpty();
    }

    @Test
    void rejeitaMaisDeTrezeDigitosInteiros() {
        AddBalanceRequest request =
            new AddBalanceRequest(new BigDecimal("10000000000000.00"));

        assertThat(VALIDATOR.validate(request)).isNotEmpty();
    }

    @Test
    void aceitaValorMinimo() {
        AddBalanceRequest request =
            new AddBalanceRequest(new BigDecimal("0.01"));

        assertThat(VALIDATOR.validate(request)).isEmpty();
    }

}
