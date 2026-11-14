package ages.vstable.backend.dto.support;

import ages.vstable.backend.external.ai.SupportChatProperties;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SupportChatDtoTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("SupportChatMessageDTO: valid message should have zero violations")
    void supportChatMessageDTO_valid() {
        SupportChatMessageDTO dto = SupportChatMessageDTO.builder()
                .role("user")
                .content("Qual o status da minha transação?")
                .build();

        Set<ConstraintViolation<SupportChatMessageDTO>> violations = validator.validate(dto);
        assertThat(violations).isEmpty();
        assertThat(dto.getRole()).isEqualTo("user");
        assertThat(dto.getContent()).isEqualTo("Qual o status da minha transação?");
        assertThat(dto.toString()).contains("user");
    }

    @Test
    @DisplayName("SupportChatMessageDTO: invalid role or blank content should fail validation")
    void supportChatMessageDTO_invalid() {
        SupportChatMessageDTO dto = new SupportChatMessageDTO("invalid-role", "   ");

        Set<ConstraintViolation<SupportChatMessageDTO>> violations = validator.validate(dto);
        assertThat(violations).hasSize(2);
    }

    @Test
    @DisplayName("SupportChatRequestDTO: valid request should have zero violations")
    void supportChatRequestDTO_valid() {
        SupportChatRequestDTO dto = SupportChatRequestDTO.builder()
                .message("Como funciona o 2FA?")
                .history(List.of(new SupportChatMessageDTO("assistant", "Olá!")))
                .build();

        Set<ConstraintViolation<SupportChatRequestDTO>> violations = validator.validate(dto);
        assertThat(violations).isEmpty();
        assertThat(dto.getMessage()).isEqualTo("Como funciona o 2FA?");
        assertThat(dto.getHistory()).hasSize(1);
    }

    @Test
    @DisplayName("SupportChatRequestDTO: blank message or excessive history should fail validation")
    void supportChatRequestDTO_invalid() {
        List<SupportChatMessageDTO> bigHistory = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            bigHistory.add(new SupportChatMessageDTO("user", "msg " + i));
        }

        SupportChatRequestDTO dto = new SupportChatRequestDTO("", bigHistory);

        Set<ConstraintViolation<SupportChatRequestDTO>> violations = validator.validate(dto);
        assertThat(violations).hasSize(2);
    }

    @Test
    @DisplayName("SupportChatResponseDTO: getters, setters, builder and equality")
    void supportChatResponseDTO_behavior() {
        OffsetDateTime now = OffsetDateTime.now();
        List<String> suggestions = List.of("Como usar o PIX?", "Quais as taxas?");

        SupportChatResponseDTO dto1 = SupportChatResponseDTO.builder()
                .reply("Resposta completa.")
                .provider("gemini")
                .suggestedQuestions(suggestions)
                .timestamp(now)
                .build();

        SupportChatResponseDTO dto2 = new SupportChatResponseDTO();
        dto2.setReply("Resposta completa.");
        dto2.setProvider("gemini");
        dto2.setSuggestedQuestions(suggestions);
        dto2.setTimestamp(now);

        assertThat(dto1).isEqualTo(dto2);
        assertThat(dto1.hashCode()).isEqualTo(dto2.hashCode());
        assertThat(dto1.getReply()).isEqualTo("Resposta completa.");
        assertThat(dto1.getProvider()).isEqualTo("gemini");
        assertThat(dto1.getSuggestedQuestions()).isEqualTo(suggestions);
        assertThat(dto1.getTimestamp()).isEqualTo(now);
        assertThat(dto1.toString()).contains("Resposta completa.");
    }

    @Test
    @DisplayName("SupportChatProperties: verify defaults and mutations")
    void supportChatProperties_defaultsAndMutations() {
        SupportChatProperties properties = new SupportChatProperties();

        assertThat(properties.getProvider()).isEqualTo("gemini");
        assertThat(properties.getApiKey()).isEmpty();
        assertThat(properties.getGeminiModel()).isEqualTo("gemini-flash-lite-latest");
        assertThat(properties.getGeminiBaseUrl()).isEqualTo("https://generativelanguage.googleapis.com");
        assertThat(properties.getDeepseekModel()).isEqualTo("deepseek-chat");
        assertThat(properties.getDeepseekBaseUrl()).isEqualTo("https://api.deepseek.com");

        properties.setProvider("deepseek");
        properties.setApiKey("sk-custom");
        properties.setGeminiModel("gemini-1.5-pro");
        properties.setGeminiBaseUrl("https://custom-gemini.test");
        properties.setDeepseekModel("deepseek-reasoner");
        properties.setDeepseekBaseUrl("https://custom-deepseek.test");

        assertThat(properties.getProvider()).isEqualTo("deepseek");
        assertThat(properties.getApiKey()).isEqualTo("sk-custom");
        assertThat(properties.getGeminiModel()).isEqualTo("gemini-1.5-pro");
        assertThat(properties.getGeminiBaseUrl()).isEqualTo("https://custom-gemini.test");
        assertThat(properties.getDeepseekModel()).isEqualTo("deepseek-reasoner");
        assertThat(properties.getDeepseekBaseUrl()).isEqualTo("https://custom-deepseek.test");
    }
}
