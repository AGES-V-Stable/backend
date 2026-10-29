package ages.vstable.backend.utils;

import ages.vstable.backend.entity.AdministratorEntity;
import ages.vstable.backend.entity.UserEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TwoFactorSecretConverterTest {

    // Chave de 32 bytes (256 bits) codificada em Base64 para testes
    private static final String VALID_BASE64_KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private TwoFactorSecretConverter converter;

    @BeforeEach
    void setUp() {
        converter = new TwoFactorSecretConverter(VALID_BASE64_KEY);
    }

    @Test
    void encryptAndDecrypt_deveRecuperarSegredoOriginal() {
        // Arrange
        String rawSecret = "JBSWY3DPEHPK3PXP";

        // Act
        String encrypted = converter.convertToDatabaseColumn(rawSecret);
        String decrypted = converter.convertToEntityAttribute(encrypted);

        // Assert
        assertThat(encrypted)
                .isNotNull()
                .isNotEqualTo(rawSecret);
        assertThat(decrypted).isEqualTo(rawSecret);
    }

    @Test
    void convertToDatabaseColumn_deveGerarCiphertextsDiferentesParaMesmoSegredoPorCausaDoIvAleatorio() {
        // Arrange
        String rawSecret = "JBSWY3DPEHPK3PXP";

        // Act
        String encrypted1 = converter.convertToDatabaseColumn(rawSecret);
        String encrypted2 = converter.convertToDatabaseColumn(rawSecret);

        // Assert
        assertThat(encrypted1).isNotEqualTo(encrypted2);
        assertThat(converter.convertToEntityAttribute(encrypted1)).isEqualTo(rawSecret);
        assertThat(converter.convertToEntityAttribute(encrypted2)).isEqualTo(rawSecret);
    }

    @Test
    void converter_deveRetornarNullQuandoEntradaForNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void constructor_deveLancarExcecaoQuandoChaveNaoTiver32Bytes() {
        String shortKey = Base64.getEncoder().encodeToString("chave-curta".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new TwoFactorSecretConverter(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void convertToEntityAttribute_deveFalharQuandoPayloadForAdulteradoOuChaveDiferente() {
        // Arrange
        String encrypted = converter.convertToDatabaseColumn("JBSWY3DPEHPK3PXP");
        String differentKey = Base64.getEncoder()
                .encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));
        TwoFactorSecretConverter otherConverter = new TwoFactorSecretConverter(differentKey);

        // Act & Assert
        assertThatThrownBy(() -> otherConverter.convertToEntityAttribute(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void entidades_naoDevemExporTwoFactorSecretEmJsonNemToString() throws Exception {
        // Arrange
        String secret = "JBSWY3DPEHPK3PXP";
        ObjectMapper mapper = new ObjectMapper();

        UserEntity user = UserEntity.builder()
                .email("user@example.com")
                .twoFactorSecret(secret)
                .twoFactorEnabled(true)
                .build();

        AdministratorEntity admin = AdministratorEntity.builder()
                .email("admin@example.com")
                .twoFactorSecret(secret)
                .twoFactorEnabled(true)
                .require2fa(true)
                .build();

        // Act
        String userJson = mapper.writeValueAsString(user);
        String adminJson = mapper.writeValueAsString(admin);

        // Assert
        assertThat(userJson).doesNotContain(secret).doesNotContain("twoFactorSecret");
        assertThat(adminJson).doesNotContain(secret).doesNotContain("twoFactorSecret");
        assertThat(user.toString()).doesNotContain(secret);
        assertThat(admin.toString()).doesNotContain(secret);
    }
}