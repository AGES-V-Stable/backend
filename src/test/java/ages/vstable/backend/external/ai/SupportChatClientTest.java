package ages.vstable.backend.external.ai;

import ages.vstable.backend.dto.support.SupportChatMessageDTO;
import ages.vstable.backend.logging.LogCapture;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SupportChatClientTest {

    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com";
    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";

    private MockRestServiceServer mockServer;
    private SupportChatProperties properties;
    private ObjectMapper objectMapper;
    private SupportChatClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        properties = new SupportChatProperties();
        properties.setApiKey("test-gemini-key");
        properties.setProvider("gemini");
        properties.setGeminiBaseUrl(GEMINI_BASE_URL);
        properties.setDeepseekBaseUrl(DEEPSEEK_BASE_URL);
        properties.setGeminiModel("gemini-flash-lite-latest");
        properties.setDeepseekModel("deepseek-chat");

        objectMapper = JsonMapper.builder().build();
        client = new SupportChatClient(properties, objectMapper, builder);
    }

    @Test
    @DisplayName("When API key is empty or whitespace, should log warning and fall back to built-in knowledge reply")
    void generateReply_whenApiKeyEmpty_returnsBuiltInKnowledge() {
        properties.setApiKey("   ");

        try (var capture = new LogCapture(SupportChatClient.class)) {
            var result = client.generateReply("system", List.of(), "Como funciona o 2FA?", "Contexto");

            assertThat(result.provider()).isEqualTo("vstable-assistant");
            assertThat(result.reply()).contains("Autenticação em Duas Etapas").contains("Contexto");
            assertThat(capture.events()).hasSize(1);
            assertThat(capture.events().getFirst().getLevel()).isEqualTo(Level.WARN);
            assertThat(capture.events().getFirst().getFormattedMessage())
                    .contains("AI_SUPPORT_API_KEY não configurada");
        }
    }

    @Test
    @DisplayName("When API key is enclosed in quotes and baseUrl has trailing slash, should strip both and proceed")
    void generateReply_whenApiKeyHasQuotes_stripsQuotesCorrectly() {
        properties.setApiKey("\"\"'secret-key'\"\"");
        properties.setGeminiBaseUrl(GEMINI_BASE_URL + "/");

        mockServer.expect(once(), requestTo(GEMINI_BASE_URL + "/v1beta/models/gemini-flash-lite-latest:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "secret-key"))
                .andRespond(withSuccess("""
                        {
                          "candidates": [
                            {
                              "content": {
                                "parts": [
                                  { "text": "Resposta oficial do Gemini." }
                                ]
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.generateReply("system", List.of(), "Pergunta", "Contexto");

        assertThat(result.provider()).isEqualTo("gemini");
        assertThat(result.reply()).isEqualTo("Resposta oficial do Gemini.");
        mockServer.verify();
    }

    @Test
    @DisplayName("When provider is set to local with valid API key, should bypass external API call without empty-key warning")
    void generateReply_whenProviderIsLocal_returnsBuiltInKnowledge() {
        properties.setProvider("local");

        try (var capture = new LogCapture(SupportChatClient.class)) {
            var result = client.generateReply("system", List.of(), "Qual o prazo de transferência?", "Contexto");

            assertThat(result.provider()).isEqualTo("vstable-assistant");
            assertThat(result.reply()).contains("Cotações, Taxas e Transferências");
            assertThat(capture.events()).isEmpty();
        }
    }

    @Test
    @DisplayName("When Gemini responds successfully with null history, should send user turn and return reply")
    void generateReply_geminiSuccess_returnsGeminiReply() {
        mockServer.expect(once(), requestTo(GEMINI_BASE_URL + "/v1beta/models/gemini-flash-lite-latest:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-gemini-key"))
                .andExpect(content().json("""
                        {
                          "systemInstruction": {
                            "parts": [{"text": "system"}]
                          },
                          "contents": [
                            {"role": "user", "parts": [{"text": "Como transferir?"}]}
                          ],
                          "generationConfig": {
                            "temperature": 0.2,
                            "maxOutputTokens": 500
                          }
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {
                          "candidates": [
                            {
                              "content": {
                                "parts": [
                                  { "text": "   Olá, você pode transferir via PIX e Avenia.   " }
                                ]
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.generateReply("system", null, "Como transferir?", "Contexto");

        assertThat(result.provider()).isEqualTo("gemini");
        assertThat(result.reply()).isEqualTo("Olá, você pode transferir via PIX e Avenia.");
        mockServer.verify();
    }

    @Test
    @DisplayName("When configured model starts with 'models/', should strip prefix from URI")
    void generateReply_geminiWithModelsPrefix_stripsPrefix() {
        properties.setGeminiModel("models/gemini-custom");

        mockServer.expect(once(), requestTo(GEMINI_BASE_URL + "/v1beta/models/gemini-custom:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "candidates": [
                            {
                              "content": {
                                "parts": [
                                  { "text": "Sucesso com modelo customizado." }
                                ]
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.generateReply("system", List.of(), "Pergunta", "Contexto");

        assertThat(result.reply()).isEqualTo("Sucesso com modelo customizado.");
        mockServer.verify();
    }

    @Test
    @DisplayName("When Gemini first model returns 503, should try fallback model and succeed")
    void generateReply_geminiFirstModel503_triesNextModelAndSucceeds() {
        mockServer.expect(once(), requestTo(GEMINI_BASE_URL + "/v1beta/models/gemini-flash-lite-latest:generateContent"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        mockServer.expect(once(), requestTo(GEMINI_BASE_URL + "/v1beta/models/gemini-flash-latest:generateContent"))
                .andRespond(withSuccess("""
                        {
                          "candidates": [
                            {
                              "content": {
                                "parts": [
                                  { "text": "Resposta do modelo de fallback." }
                                ]
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.generateReply("system", List.of(), "Como funciona?", "Contexto");

        assertThat(result.provider()).isEqualTo("gemini");
        assertThat(result.reply()).isEqualTo("Resposta do modelo de fallback.");
        mockServer.verify();
    }

    @Test
    @DisplayName("When Gemini returns 404 or 429, should continue to next candidate model")
    void generateReply_gemini404And429_triesNextModels() {
        mockServer.expect(once(), requestTo(GEMINI_BASE_URL + "/v1beta/models/gemini-flash-lite-latest:generateContent"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        mockServer.expect(once(), requestTo(GEMINI_BASE_URL + "/v1beta/models/gemini-flash-latest:generateContent"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        mockServer.expect(once(), requestTo(GEMINI_BASE_URL + "/v1beta/models/gemini-3.5-flash-lite:generateContent"))
                .andRespond(withSuccess("""
                        {
                          "candidates": [
                            {
                              "content": {
                                "parts": [
                                  { "text": "Resposta do terceiro modelo." }
                                ]
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.generateReply("system", List.of(), "Pergunta", "Contexto");

        assertThat(result.reply()).isEqualTo("Resposta do terceiro modelo.");
        mockServer.verify();
    }

    @Test
    @DisplayName("When Gemini returns empty response or missing text node, should continue to next candidate")
    void generateReply_geminiEmptyResponse_triesNextModel() {
        mockServer.expect(once(), requestTo(GEMINI_BASE_URL + "/v1beta/models/gemini-flash-lite-latest:generateContent"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        mockServer.expect(once(), requestTo(GEMINI_BASE_URL + "/v1beta/models/gemini-flash-latest:generateContent"))
                .andRespond(withSuccess("""
                        { "candidates": [] }
                        """, MediaType.APPLICATION_JSON));
        mockServer.expect(once(), requestTo(GEMINI_BASE_URL + "/v1beta/models/gemini-3.5-flash-lite:generateContent"))
                .andRespond(withSuccess("""
                        {
                          "candidates": [
                            {
                              "content": {
                                "parts": [
                                  { "text": "Resposta válida." }
                                ]
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.generateReply("system", List.of(), "Pergunta", "Contexto");

        assertThat(result.reply()).isEqualTo("Resposta válida.");
        mockServer.verify();
    }

    @Test
    @DisplayName("When Gemini throws 400 Bad Request, should not retry and fall back to built-in knowledge")
    void generateReply_gemini400_fallsBackToBuiltInKnowledge() {
        mockServer.expect(once(), requestTo(GEMINI_BASE_URL + "/v1beta/models/gemini-flash-lite-latest:generateContent"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        var result = client.generateReply("system", List.of(), "Pergunta sobre 2FA", "Contexto");

        assertThat(result.provider()).isEqualTo("vstable-assistant");
        assertThat(result.reply()).contains("Autenticação em Duas Etapas");
        mockServer.verify();
    }

    @Test
    @DisplayName("When conversation history has complex alternation, should format roles for Gemini properly")
    void generateReply_geminiHistoryAlternationRules() {
        List<SupportChatMessageDTO> history = new ArrayList<>();
        // 1. Null message and null content (should be skipped)
        history.add(null);
        history.add(new SupportChatMessageDTO("user", null));
        // 2. Assistant message first (should be ignored until first user turn)
        history.add(new SupportChatMessageDTO("assistant", "Olá! Como posso ajudar?"));
        // 3. Blank message (should be skipped)
        history.add(new SupportChatMessageDTO("user", "   "));
        // 4. First user turn
        history.add(new SupportChatMessageDTO("user", "Primeira pergunta"));
        // 5. Consecutive user turn (should be skipped to alternate)
        history.add(new SupportChatMessageDTO("user", "Segunda pergunta do mesmo turno"));
        // 6. Assistant turn
        history.add(new SupportChatMessageDTO("assistant", "Resposta do assistente"));
        // 7. Consecutive assistant turn (should be skipped to alternate)
        history.add(new SupportChatMessageDTO("assistant", "Segunda resposta consecutiva"));
        // 8. User turn
        history.add(new SupportChatMessageDTO("user", "Última pergunta do histórico"));

        mockServer.expect(once(), requestTo(GEMINI_BASE_URL + "/v1beta/models/gemini-flash-lite-latest:generateContent"))
                .andExpect(content().json("""
                        {
                          "systemInstruction": {
                            "parts": [{"text": "system instruction"}]
                          },
                          "contents": [
                            {"role": "user", "parts": [{"text": "Primeira pergunta"}]},
                            {"role": "model", "parts": [{"text": "Resposta do assistente"}]},
                            {"role": "user", "parts": [{"text": "Última pergunta do histórico"}]},
                            {"role": "model", "parts": [{"text": "Entendido."}]},
                            {"role": "user", "parts": [{"text": "Mensagem atual"}]}
                          ],
                          "generationConfig": {
                            "temperature": 0.2,
                            "maxOutputTokens": 500
                          }
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {
                          "candidates": [
                            {
                              "content": {
                                "parts": [
                                  { "text": "Resposta com histórico." }
                                ]
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.generateReply("system instruction", history, "Mensagem atual", "Contexto");

        assertThat(result.reply()).isEqualTo("Resposta com histórico.");
        mockServer.verify();
    }

    @Test
    @DisplayName("When all Gemini candidate models fail or return empty, callGemini returns null and generateReply falls back")
    void callGemini_whenAllCandidatesExhausted_returnsNullAndFallsBack() {
        for (String model : List.of(
                "gemini-flash-lite-latest",
                "gemini-flash-latest",
                "gemini-3.5-flash-lite",
                "gemini-3.5-flash")) {
            mockServer.expect(once(), requestTo(GEMINI_BASE_URL + "/v1beta/models/" + model + ":generateContent"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }

        String directResult = ReflectionTestUtils.invokeMethod(
                client, "callGemini", "test-gemini-key", "system", List.of(), "Pergunta");

        assertThat(directResult).isNull();
        mockServer.verify();
    }

    @Test
    @DisplayName("When DeepSeek provider is configured, should filter invalid history and call DeepSeek chat completions endpoint")
    void generateReply_deepSeekProvider_success() {
        properties.setProvider("deepseek");
        properties.setApiKey("sk-deepseek-key");

        mockServer.expect(once(), requestTo(DEEPSEEK_BASE_URL + "/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-deepseek-key"))
                .andExpect(content().json("""
                        {
                          "model": "deepseek-chat",
                          "messages": [
                            {"role": "system", "content": "system"},
                            {"role": "user", "content": "Oi"},
                            {"role": "assistant", "content": "Olá"},
                            {"role": "user", "content": "Como funciona o PIX?"}
                          ],
                          "temperature": 0.25,
                          "max_tokens": 600
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "Resposta do DeepSeek."
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<SupportChatMessageDTO> history = new ArrayList<>();
        history.add(null);
        history.add(new SupportChatMessageDTO("user", null));
        history.add(new SupportChatMessageDTO("user", "   "));
        history.add(new SupportChatMessageDTO("user", "  Oi  "));
        history.add(new SupportChatMessageDTO("assistant", "  Olá  "));

        var result = client.generateReply("system", history, "Como funciona o PIX?", "Contexto");

        assertThat(result.provider()).isEqualTo("deepseek");
        assertThat(result.reply()).isEqualTo("Resposta do DeepSeek.");
        mockServer.verify();
    }

    @Test
    @DisplayName("When provider is 'auto', apiKey starts with 'sk-' and history is null, should select DeepSeek and trim trailing slash")
    void generateReply_autoProviderWithSkKey_usesDeepSeek() {
        properties.setProvider("auto");
        properties.setApiKey("sk-auto-detected-key");
        properties.setDeepseekBaseUrl(DEEPSEEK_BASE_URL + "/");

        mockServer.expect(once(), requestTo(DEEPSEEK_BASE_URL + "/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-auto-detected-key"))
                .andExpect(content().json("""
                        {
                          "model": "deepseek-chat",
                          "messages": [
                            {"role": "system", "content": "system"},
                            {"role": "user", "content": "Pergunta"}
                          ],
                          "temperature": 0.25,
                          "max_tokens": 600
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {
                              "message": {
                                "content": "Resposta auto DeepSeek."
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.generateReply("system", null, "Pergunta", "Contexto");

        assertThat(result.provider()).isEqualTo("deepseek");
        assertThat(result.reply()).isEqualTo("Resposta auto DeepSeek.");
        mockServer.verify();
    }

    @Test
    @DisplayName("When DeepSeek returns blank response body, callDeepSeekCompatible returns null")
    void callDeepSeekCompatible_whenResponseBlank_returnsNull() {
        mockServer.expect(once(), requestTo(DEEPSEEK_BASE_URL + "/v1/chat/completions"))
                .andRespond(withSuccess("   ", MediaType.APPLICATION_JSON));

        String directResult = ReflectionTestUtils.invokeMethod(
                client, "callDeepSeekCompatible", "sk-test", "system", List.of(), "Pergunta");

        assertThat(directResult).isNull();
        mockServer.verify();
    }

    @Test
    @DisplayName("When DeepSeek returns error or missing content, should fall back to built-in knowledge")
    void generateReply_deepSeekFailure_fallsBackToBuiltInKnowledge() {
        properties.setProvider("deepseek");
        properties.setApiKey("sk-test");

        mockServer.expect(once(), requestTo(DEEPSEEK_BASE_URL + "/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        var result = client.generateReply("system", List.of(), "Qual a taxa da Avenia?", "Contexto");

        assertThat(result.provider()).isEqualTo("vstable-assistant");
        assertThat(result.reply()).contains("Avenia").contains("BlindPay");
        mockServer.verify();
    }

    @ParameterizedTest
    @MethodSource("domainTopics")
    @DisplayName("Built-in knowledge assistant should accurately match domain topics and include user context")
    void generateBuiltInKnowledgeReply_matchesAllDomainTopics(String message, String expectedSectionSnippet) {
        properties.setProvider("local");

        var result = client.generateReply("system", List.of(), message, "Contexto de Teste");

        assertThat(result.provider()).isEqualTo("vstable-assistant");
        assertThat(result.reply())
                .contains(expectedSectionSnippet)
                .contains("Contexto de Teste");
    }

    static Stream<Arguments> domainTopics() {
        return Stream.of(
                Arguments.of(
                        "Como funciona a integração com a Avenia e BlindPay?",
                        "multi-provedor de liquidez institucional"),
                Arguments.of(
                        "Quais são os países, moedas e trilhos suportados como SPEI e Argentina?",
                        "dezenas de corredores internacionais"),
                Arguments.of(
                        "Por que transações com a Colômbia em COP estão bloqueadas?",
                        "COP (Peso Colombiano)"),
                Arguments.of(
                        "Quais redes blockchain e stablecoins são suportadas como Polygon e USDC?",
                        "redes blockchain e stablecoins"),
                Arguments.of(
                        "Como reconfigurar meu QR Code do autenticador 2FA?",
                        "Autenticação em Duas Etapas (2FA / TOTP)"),
                Arguments.of(
                        "O que é necessário para aprovação de compliance KYB e AML?",
                        "Compliance Institucional (KYB / AML)"),
                Arguments.of(
                        "Como é calculada a cotação, taxa e spread de uma transferência?",
                        "Cotações, Taxas e Transferências"),
                Arguments.of(
                        "Como cadastrar um beneficiário internacional com IBAN ou wallet?",
                        "Para cadastrar um **Beneficiário** na V-Stable:"),
                Arguments.of(
                        "Olá, tudo bem?",
                        "Olá! Sou o **Assistente Virtual da V-Stable**.")
        );
    }
}
