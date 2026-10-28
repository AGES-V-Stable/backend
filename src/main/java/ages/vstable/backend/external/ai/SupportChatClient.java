package ages.vstable.backend.external.ai;

import ages.vstable.backend.dto.support.SupportChatMessageDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class SupportChatClient implements SupportChatGateway {

    private final SupportChatProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public SupportChatClient(
            SupportChatProperties properties,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public SupportChatGatewayResult generateReply(
            String systemPrompt,
            List<SupportChatMessageDTO> history,
            String userMessage,
            String userContextSummary
    ) {
        String apiKey = properties.getApiKey() != null ? properties.getApiKey().trim() : "";
        String provider = properties.getProvider() != null
                ? properties.getProvider().trim().toLowerCase(Locale.ROOT)
                : "auto";

        if (!apiKey.isEmpty() && !"local".equals(provider)) {
            try {
                if ("deepseek".equals(provider) || ("auto".equals(provider) && !apiKey.startsWith("AIza"))) {
                    String reply = callDeepSeekCompatible(apiKey, systemPrompt, history, userMessage);
                    if (reply != null && !reply.isBlank()) {
                        return new SupportChatGatewayResult(reply.trim(), "deepseek");
                    }
                } else {
                    String reply = callGemini(apiKey, systemPrompt, history, userMessage);
                    if (reply != null && !reply.isBlank()) {
                        return new SupportChatGatewayResult(reply.trim(), "gemini");
                    }
                }
            } catch (Exception ex) {
                log.warn("External AI provider '{}' unavailable ({}); falling back to built-in V-Stable assistant",
                        provider, ex.getClass().getSimpleName());
            }
        }

        return new SupportChatGatewayResult(
                generateBuiltInKnowledgeReply(userMessage, userContextSummary),
                "vstable-assistant"
        );
    }

    private String callGemini(
            String apiKey,
            String systemPrompt,
            List<SupportChatMessageDTO> history,
            String userMessage
    ) {
        String baseUrl = trimTrailingSlash(properties.getGeminiBaseUrl());
        String model = properties.getGeminiModel();
        URI uri = URI.create(baseUrl + "/v1beta/models/" + model + ":generateContent?key=" + apiKey);

        List<Map<String, Object>> contents = new ArrayList<>();
        if (history != null) {
            for (SupportChatMessageDTO msg : history) {
                if (msg == null || msg.getContent() == null || msg.getContent().isBlank()) {
                    continue;
                }
                String geminiRole = "assistant".equalsIgnoreCase(msg.getRole()) ? "model" : "user";
                contents.add(Map.of(
                        "role", geminiRole,
                        "parts", List.of(Map.of("text", msg.getContent().trim()))
                ));
            }
        }
        contents.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", userMessage))
        ));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("systemInstruction", Map.of(
                "parts", List.of(Map.of("text", systemPrompt))
        ));
        payload.put("contents", contents);
        payload.put("generationConfig", Map.of(
                "temperature", 0.25,
                "maxOutputTokens", 600
        ));

        String rawResponse = restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(payload))
                .retrieve()
                .body(String.class);

        if (rawResponse == null || rawResponse.isBlank()) {
            return null;
        }
        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode textNode = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
        return textNode.isMissingNode() ? null : textNode.asText();
    }

    private String callDeepSeekCompatible(
            String apiKey,
            String systemPrompt,
            List<SupportChatMessageDTO> history,
            String userMessage
    ) {
        String baseUrl = trimTrailingSlash(properties.getDeepseekBaseUrl());
        URI uri = URI.create(baseUrl + "/v1/chat/completions");

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null) {
            for (SupportChatMessageDTO msg : history) {
                if (msg == null || msg.getContent() == null || msg.getContent().isBlank()) {
                    continue;
                }
                String role = "assistant".equalsIgnoreCase(msg.getRole()) ? "assistant" : "user";
                messages.add(Map.of("role", role, "content", msg.getContent().trim()));
            }
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getDeepseekModel());
        payload.put("messages", messages);
        payload.put("temperature", 0.25);
        payload.put("max_tokens", 600);

        String rawResponse = restClient.post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(payload))
                .retrieve()
                .body(String.class);

        if (rawResponse == null || rawResponse.isBlank()) {
            return null;
        }
        JsonNode root = objectMapper.readTree(rawResponse);
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        return contentNode.isMissingNode() ? null : contentNode.asText();
    }

    private String generateBuiltInKnowledgeReply(String userMessage, String userContextSummary) {
        String normalized = normalize(userMessage);

        if (containsAny(normalized, "colombia", "cop", "bloquead", "pais", "moeda", "corredor", "swift")) {
            return """
                    As transações bancárias diretas em **COP (Peso Colombiano)** estão indisponíveis no momento por regras de corredor de liquidação e compliance da V-Stable:

                    1. **Moedas habilitadas via Conta Bancária (SWIFT / Wire / SEPA):** No momento, o trilho bancário internacional suporta **USD, EUR, GBP, CAD, AUD, CHF, JPY, CNY, HKD, SGD, NZD, THB, PHP, INR, KRW e IDR** (além de **BRL** via PIX). O Peso Colombiano (`COP`) ainda não faz parte da grade SWIFT local.
                    2. **Políticas de Compliance e PLD-FT (GAFI/OFAC):** Corredores ou instituições financeiras fora da grade homologada com nossos parceiros liquidantes (Avenia e BlindPay) são bloqueados preventivamente.
                    3. **Como enviar para um fornecedor na Colômbia hoje:**
                       - **Opção 1 (Wallet Cripto):** Cadastre o beneficiário na aba **Beneficiários > Novo Beneficiário** escolhendo o método **Wallet Cripto** (redes *Polygon, Celo, Ethereum, Gnosis, Moonbeam ou Tron*) para liquidar em stablecoin (USDC/USDT).
                       - **Opção 2 (Conta Internacional em USD/EUR):** Caso o fornecedor colombiano possua conta bancária internacional que receba em **USD** ou **EUR** via SWIFT/ACH, basta cadastrá-lo com a moeda `USD` ou `EUR`.

                    %s""".formatted(userContextSummary).trim();
        }

        if (containsAny(normalized, "2fa", "totp", "qr", "autenticador", "codigo", "configuracoes", "celular", "bloqueio")) {
            return """
                    Sobre a **Autenticação em Duas Etapas (2FA / TOTP)** na V-Stable:

                    1. **Onde ativar ou reconfigurar:** Acesse **Configurações > Segurança da Conta** no menu lateral.
                    2. **Troca de celular / Reconfigurar 2FA:** Para gerar um novo QR Code quando o 2FA já está ativo, o sistema exige que você informe o **código atual de 6 dígitos** (revogando imediatamente o segredo anterior por segurança).
                    3. **Dica ao reescanear o QR Code:** Remova o card antigo da V-Stable no seu app autenticador (Google ou Microsoft Authenticator) antes de ler o novo QR Code para não misturar o código antigo com o novo.
                    4. **Limite de tentativas:** Após **5 tentativas incorretas**, a verificação é bloqueada temporariamente por **15 minutos** contra ataques de força bruta.

                    %s""".formatted(userContextSummary).trim();
        }

        if (containsAny(normalized, "compliance", "kyb", "aml", "documento", "cadastro", "pendente", "aprovad", "liveness")) {
            return """
                    O processo de **Compliance Institucional (KYB / AML)** garante a segurança regulatória das operações B2B:

                    1. **Etapas do Onboarding:** Cadastro da Empresa (CNPJ) ➔ Representante Legal ➔ Ativação do 2FA ➔ Envio de Documentos de Compliance e Prova de Vida (*Liveness*).
                    2. **Liberação de Transferências:** Para criar transferências internacionais ou liquidar câmbio, sua empresa precisa estar com o status **KYB = APPROVED** e **AML = APPROVED**, além do **2FA ativo**.

                    %s""".formatted(userContextSummary).trim();
        }

        if (containsAny(normalized, "cotacao", "taxa", "iof", "spread", "transferencia", "prazo", "avenia", "blindpay", "saldo")) {
            return """
                    Sobre **Cotações, Taxas e Transferências** na V-Stable:

                    1. **Cotação em tempo real:** Ao iniciar uma transferência em **Transferências**, a V-Stable consulta automaticamente os provedores de liquidez (**Avenia** e **BlindPay**) e trava a cotação por alguns minutos.
                    2. **Transparência de custos:** O painel lateral exibe a taxa de câmbio efetiva, taxas de rede/provedor e o valor líquido exato que chegará ao beneficiário.
                    3. **Confirmação segura:** Na etapa final (**Autenticação**), é necessário informar o código TOTP de 6 dígitos do seu autenticador antes da execução da ordem.

                    %s""".formatted(userContextSummary).trim();
        }

        if (containsAny(normalized, "beneficiario", "iban", "wallet", "rede", "polygon", "cadastrar")) {
            return """
                    Para cadastrar um **Beneficiário** na V-Stable:

                    1. Acesse **Beneficiários > Cadastrar beneficiário** no menu lateral.
                    2. Escolha a forma de recebimento:
                       - **Conta Bancária:** Informe Banco, SWIFT/BIC, IBAN ou Número da Conta e a Moeda de recebimento (`USD`, `EUR`, `GBP`, etc.).
                       - **Wallet Cripto:** Informe o endereço da carteira (`0x...`) e a rede blockchain (**Polygon, Celo, Ethereum, Gnosis, Moonbeam ou Tron**).

                    %s""".formatted(userContextSummary).trim();
        }

        return """
                Olá! Sou o **Assistente Virtual da V-Stable**. Posso ajudar você com dúvidas sobre:

                - **Países e moedas suportadas** (ex.: por que um país/moeda como a Colômbia está bloqueado via banco e como usar Wallet Cripto ou USD);
                - **Segurança e 2FA (TOTP)** (ativação, troca de QR Code e bloqueio de segurança);
                - **Cadastro de Beneficiários** (SWIFT, IBAN, ACH, SEPA e Redes Blockchain);
                - **Cotações, Taxas e Status de Compliance (KYB/AML)**.

                %s

                Como posso ajudar na sua operação hoje?""".formatted(userContextSummary).trim();
    }

    private static boolean containsAny(String normalizedText, String... keywords) {
        for (String keyword : keywords) {
            if (normalizedText.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
