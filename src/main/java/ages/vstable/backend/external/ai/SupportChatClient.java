package ages.vstable.backend.external.ai;

import ages.vstable.backend.dto.support.SupportChatMessageDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
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
        String rawKey = properties.getApiKey() != null ? properties.getApiKey().trim() : "";
        String apiKey = stripQuotes(rawKey);
        String provider = properties.getProvider() != null
                ? properties.getProvider().trim().toLowerCase(Locale.ROOT)
                : "gemini";

        if (apiKey.isEmpty()) {
            log.warn("AI_SUPPORT_API_KEY não configurada; usando assistente local da V-Stable");
        }
        if (!apiKey.isEmpty() && !"local".equals(provider)) {
            boolean useDeepSeek = "deepseek".equals(provider) || ("auto".equals(provider) && apiKey.startsWith("sk-"));
            String activeProvider = useDeepSeek ? "deepseek" : "gemini";
            try {
                log.info("Chamando provedor de IA '{}'...", activeProvider);
                String reply = useDeepSeek
                        ? callDeepSeekCompatible(apiKey, systemPrompt, history, userMessage)
                        : callGemini(apiKey, systemPrompt, history, userMessage);

                if (reply != null && !reply.isBlank()) {
                    return new SupportChatGatewayResult(reply.trim(), activeProvider);
                }
            } catch (Exception ex) {
                log.warn("External AI provider '{}' unavailable ({}): {}",
                        activeProvider, ex.getClass().getSimpleName(), ex.getMessage());
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

        List<Map<String, Object>> contents = new ArrayList<>();
        boolean hasUserStarted = false;
        String lastRole = null;
        if (history != null) {
            for (SupportChatMessageDTO msg : history) {
                if (msg == null || msg.getContent() == null || msg.getContent().isBlank()) {
                    continue;
                }
                String role = "assistant".equalsIgnoreCase(msg.getRole()) ? "model" : "user";
                // Gemini requires the conversation history to start with a "user" turn
                if (!hasUserStarted) {
                    if (!"user".equals(role)) {
                        continue;
                    }
                    hasUserStarted = true;
                }
                // Gemini requires roles to alternate
                if (role.equals(lastRole)) {
                    continue;
                }
                contents.add(Map.of(
                        "role", role,
                        "parts", List.of(Map.of("text", msg.getContent().trim()))
                ));
                lastRole = role;
            }
        }
        if (!"user".equals(lastRole)) {
            contents.add(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", userMessage))
            ));
        } else {
            // If the last history turn was already "user", combine or replace to maintain alternation
            contents.add(Map.of(
                    "role", "model",
                    "parts", List.of(Map.of("text", "Entendido."))
            ));
            contents.add(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", userMessage))
            ));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("systemInstruction", Map.of(
                "parts", List.of(Map.of("text", systemPrompt))
        ));
        payload.put("contents", contents);
        payload.put("generationConfig", Map.of(
                "temperature", 0.2,
                "maxOutputTokens", 500
        ));

        List<String> candidateModels = new ArrayList<>();
        if (properties.getGeminiModel() != null && !properties.getGeminiModel().isBlank()) {
            candidateModels.add(properties.getGeminiModel().trim());
        }
        for (String fallback : List.of("gemini-flash-lite-latest", "gemini-flash-latest", "gemini-3.5-flash-lite", "gemini-3.5-flash")) {
            if (!candidateModels.contains(fallback)) {
                candidateModels.add(fallback);
            }
        }

        for (String model : candidateModels) {
            try {
                String cleanModel = model.startsWith("models/") ? model.substring(7) : model;
                URI uri = URI.create(baseUrl + "/v1beta/models/" + cleanModel + ":generateContent");
                log.info("Tentando chamada ao modelo: {}", cleanModel);
                byte[] responseBytes = restClient.post()
                        .uri(uri)
                        .header("x-goog-api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.ALL)
                        .body(objectMapper.writeValueAsString(payload))
                        .retrieve()
                        .body(byte[].class);

                if (responseBytes == null || responseBytes.length == 0) {
                    continue;
                }
                String rawResponse = new String(responseBytes, java.nio.charset.StandardCharsets.UTF_8);
                JsonNode root = objectMapper.readTree(rawResponse);
                JsonNode textNode = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
                if (!textNode.isMissingNode() && !textNode.asText().isBlank()) {
                    return textNode.asText();
                }
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                if (status == 503 || status == 404 || status == 429) {
                    log.warn("Modelo Gemini '{}' indisponível ({}); tentando próximo modelo...",
                            model, status);
                    continue;
                }
                throw ex;
            } catch (org.springframework.web.client.RestClientException ex) {
                log.warn("Falha de rede com modelo Gemini '{}' ({}); tentando próximo...",
                        model, ex.getClass().getSimpleName());
            }
        }
        return null;
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

        if (containsAny(normalized, "avenia", "blindpay", "provedor", "provedores", "orquestrador", "parceiro")) {
            return """
                    A V-Stable opera com uma estrutura **multi-provedor de liquidez institucional** utilizando provedores parceiros regulados de liquidez e pagamento:

                    1. **Entrada em BRL e Liquidação Cripto:**
                       - Entrada de recursos via **PIX em BRL** no Brasil;
                       - Emissão e trânsito em stablecoins: **USDC, USDT, BRLA e EURC**;
                       - Suporte a transferências diretas para **Wallet Cripto** em múltiplas blockchains (*Polygon, Celo, Ethereum, Gnosis, Moonbeam, Tron e Base*) e liquidação bancária via SWIFT.
                    2. **Liquidação Bancária Local em Moeda Fiduciária:**
                       - Liquidação em moeda fiduciária direto na conta bancária do beneficiário por meio de provedores parceiros regulados:
                       - **EUA (USD):** Trilhos ACH, Wire e RTP (tempo real);
                       - **Europa (EUR):** Trilho SEPA e SEPA Instant;
                       - **Reino Unido (GBP):** Trilho Faster Payments;
                       - **América Latina:** México (**SPEI / MXN**) e Argentina (**CVU/CBU/ALIAS / ARS**);
                       - **Global:** SWIFT para mais de 16 moedas globais.
                    3. **Busca automática pela Melhor Cotação:**
                       - Ao solicitar uma cotação, a plataforma consulta os provedores em tempo real e entrega automaticamente a **Melhor Cotação** disponível com menores taxas e spread.

                    %s""".formatted(userContextSummary).trim();
        }

        if (containsAny(normalized, "moeda", "moedas", "pais", "paises", "mexico", "spei", "argentina", "ars", "cbu", "cvu", "dolar", "euro", "libra", "gbp")) {
            return """
                    A V-Stable conecta sua empresa a dezenas de corredores internacionais via Conta Bancária e Carteira Cripto:

                    1. **América do Norte e Europa (Trilhos Locais de Baixo Custo):**
                       - **Estados Unidos (USD):** Trilhos `ACH` (1-2 dias úteis), `Wire` (mesmo dia) e `RTP` (em minutos);
                       - **Zona do Euro (EUR):** Trilho `SEPA` e `SEPA Instant` para todos os países da União Europeia;
                       - **Reino Unido (GBP):** Trilho `Faster Payments`.
                    2. **América Latina (Trilhos Locais):**
                       - **México (MXN):** Liquidação instantânea via trilho `SPEI` utilizando a chave CLABE do fornecedor;
                       - **Argentina (ARS):** Transferências locais para contas bancárias argentinas com `CVU`, `CBU` ou `ALIAS`;
                       - **Brasil (BRL):** Depósitos e retiradas via `PIX`.
                    3. **Grade Global SWIFT (16+ Moedas Internacionais):**
                       - `USD`, `EUR`, `GBP`, `CAD` (Canadá), `AUD` (Austrália), `CHF` (Suíça), `JPY` (Japão), `CNY` (China), `HKD` (Hong Kong), `SGD` (Cingapura), `NZD` (Nova Zelândia), `THB` (Tailândia), `PHP` (Filipinas), `INR` (Índia), `KRW` (Coreia do Sul) e `IDR` (Indonésia).
                    4. **Qualquer outro país via Wallet Cripto:**
                       - Se o fornecedor estiver em jurisdição sem trilho bancário direto (como Colômbia), é possível enviar em **USDC** ou **USDT** diretamente para uma carteira Web3.

                    %s""".formatted(userContextSummary).trim();
        }

        if (containsAny(normalized, "colombia", "cop", "bloquead", "corredor")) {
            return """
                    As transações bancárias diretas em **COP (Peso Colombiano)** estão indisponíveis no momento por regras de corredor de liquidação e compliance da V-Stable:

                    1. **Por que o trilho bancário local em COP está indisponível?**
                       - O recebimento bancário local em pesos colombianos ainda está em fase final de liberação técnica e regulatória.
                       - O trilho SWIFT padrão da V-Stable atende a lista oficial de 16 moedas globais (onde o COP não faz parte da grade direta).
                    2. **Como enviar dinheiro para um fornecedor ou parceiro na Colômbia hoje:**
                       - **Opção 1 (Wallet Cripto - Recomendada):** Cadastre o beneficiário em **Beneficiários > Cadastrar beneficiário** com o método **Wallet Cripto** em redes como **Polygon, Celo, Ethereum, Gnosis, Moonbeam ou Tron** para liquidar em **USDC** ou **USDT** sem barreiras bancárias locais.
                       - **Opção 2 (Conta Internacional em USD/EUR):** Se o fornecedor colombiano possuir conta internacional capaz de receber em **USD** (ACH/Wire/SWIFT) ou **EUR** (SEPA/SWIFT), cadastre-o informando a moeda `USD` ou `EUR`.

                    %s""".formatted(userContextSummary).trim();
        }

        if (containsAny(normalized, "rede", "blockchain", "polygon", "celo", "ethereum", "tron", "gnosis", "moonbeam", "base", "token", "usdc", "usdt", "brla", "eurc")) {
            return """
                    A V-Stable suporta emissão, trânsito e pagamento nas seguintes **redes blockchain e stablecoins**:

                    1. **Stablecoins Suportadas:**
                       - **USDC** (USD Coin - dólar digital auditado);
                       - **USDT** (Tether USD);
                       - **BRLA** (Real brasileiro tokenizado);
                       - **EURC** (Euro digital).
                    2. **Redes Blockchain Suportadas:**
                       - **Polygon (PoS):** Baixíssimo custo de gás e liquidação em segundos (ideal para transferências comerciais frequentes);
                       - **Celo:** Foco em pagamentos ágeis para dispositivos móveis e taxas mínimas;
                       - **Base (Layer 2 Coinbase):** Alta performance e segurança ancorada na Ethereum;
                       - **Ethereum (Mainnet):** Camada de liquidação institucional de alta segurança;
                       - **Tron:** Ampla liquidação de USDT;
                       - **Gnosis & Moonbeam:** Interoperabilidade e contratos inteligentes EVM.

                    %s""".formatted(userContextSummary).trim();
        }

        if (containsAny(normalized, "2fa", "totp", "qr", "autenticador", "codigo", "configuracoes", "celular", "bloqueio")) {
            return """
                    Sobre a **Autenticação em Duas Etapas (2FA / TOTP)** na V-Stable:

                    1. **Onde ativar ou reconfigurar:** Acesse **Configurações > Segurança da Conta** no menu lateral.
                    2. **Troca de celular / Reconfigurar 2FA:** Para gerar um novo QR Code quando o 2FA já está ativo, o sistema exige que você informe o **código atual de 6 dígitos** (revogando imediatamente o segredo anterior por segurança).
                    3. **Dica ao reescanear o QR Code:** Remova o card antigo da V-Stable no seu app autenticador (Google ou Microsoft Authenticator) antes de ler o novo QR Code para não misturar o código antigo com o novo.
                    4. **Uso único:** Cada código de 6 dígitos do autenticador é de **uso único** dentro da sua janela de validade e não pode ser reutilizado.
                    5. **Limite de tentativas:** Após **5 tentativas incorretas**, a verificação é bloqueada temporariamente por **15 minutos** contra ataques de força bruta.

                    %s""".formatted(userContextSummary).trim();
        }

        if (containsAny(normalized, "compliance", "kyc", "kyb", "aml", "documento", "cadastro", "pendente", "aprovad", "liveness")) {
            return """
                    A **Verificação de identidade (KYC)** acompanha o cadastro do representante:

                    1. **Etapas do Cadastro e Verificação:** Cadastro da Empresa (CNPJ) ➔ Representante Legal ➔ Ativação do 2FA ➔ Aceite dos **termos do provedor de pagamento** e Termos de Uso ➔ Envio do Documento de Identidade (até **3 MB por arquivo**) e Prova de Vida (*Liveness*).
                    2. **Liberação e Reabertura do KYC:** O cadastro é aprovado conforme o resultado da verificação individual de identidade. Caso precise corrigir dados ou reenviar arquivos, é possível **reiniciar/refazer o processo de verificação** quando necessário. Transferências continuam sujeitas às permissões, ao 2FA e às condições do provedor de pagamento.

                    %s""".formatted(userContextSummary).trim();
        }

        if (containsAny(normalized, "cotacao", "taxa", "iof", "spread", "transferencia", "prazo", "avenia", "blindpay", "saldo")) {
            return """
                    Sobre **Cotações, Taxas e Transferências** na V-Stable:

                    1. **Saldo empresarial em BRL:** As transferências utilizam o saldo empresarial em BRL para liquidação; quando habilitado em ambiente de testes/simulação, é possível adicionar saldo simulado com emissão de recibo.
                    2. **Cotação em tempo real:** Ao iniciar uma transferência em **Transferências**, a V-Stable consulta automaticamente provedores parceiros regulados de liquidez e pagamento e trava a cotação por alguns minutos.
                    3. **Transparência de custos:** O painel lateral exibe a taxa de câmbio efetiva, taxas de rede/provedor e o valor líquido exato que chegará ao beneficiário.
                    4. **Confirmação, Comprovante em PDF e Acompanhamento:** Na etapa final (**Autenticação**), informe o código de 6 dígitos do seu autenticador antes de concluir a operação. Após finalizada, é possível fazer o **download do comprovante em PDF**, com **acompanhamento e atualização automática do status das transferências**.

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

        if (containsAny(normalized, "membro", "membros", "convite", "convites", "papel", "papeis", "permissao", "permissoes", "owner", "operator", "viewer", "revogar", "representante", "visualizar", "operar", "perfil", "perfis")) {
            return """
                    Sobre **Gestão de Membros, Perfis e Convites** na V-Stable:

                    1. **Perfis de acesso na empresa:**
                       - **Representante:** Gestão completa da conta da empresa, envio de convites para membros, alteração de permissões, revogação de acesso e operações financeiras.
                       - **Membro com permissão para operar:** Pode realizar cotações, transferências e gerenciar beneficiários.
                       - **Membro com acesso apenas de visualização:** Pode consultar e acompanhar informações, sem permissão para operar.
                    2. **Convites e Termos vigentes:** O envio de convites ocorre por **link seguro** e o aceite exige concordância com os **Termos de Uso e Política de Privacidade vigentes**.
                    3. **Alteração e Revogação:** O **Representante** pode alterar as permissões de um membro ou realizar a **revogação imediata de acesso** à empresa.

                    %s""".formatted(userContextSummary).trim();
        }

        if (containsAny(normalized, "dashboard", "economia", "comprovante", "pdf", "conciliacao", "extrato", "acompanhamento")) {
            return """
                    Sobre **Dashboard, Economia Estimada e Comprovante em PDF** na V-Stable:

                    1. **Câmbio de referência:** O dashboard exibe cotações de referência `BRL -> USDC` e `BRL -> USDT` (rota PIX -> Polygon, sem tarifas embutidas), com atualização periódica e filtros de histórico e status.
                    2. **Economia estimada:** A plataforma calcula a economia estimada das operações frente ao **spread bancário de referência de 4%%**.
                    3. **Comprovante em PDF e Acompanhamento:** Transferências concluídas permitem baixar o **comprovante em PDF**, além de contarem com **acompanhamento e atualização automática do status das transferências**.

                    %s""".formatted(userContextSummary).trim();
        }

        return """
                Olá! Sou o **Assistente Virtual da V-Stable**. Posso ajudar você com dúvidas sobre:

                - **Países e moedas suportadas** (ex.: por que um país/moeda como a Colômbia está bloqueado via banco e como usar Wallet Cripto ou USD);
                - **Segurança e 2FA (TOTP)** (ativação, troca de QR Code e bloqueio de segurança);
                - **Cadastro de Beneficiários** (SWIFT, IBAN, ACH, SEPA e Redes Blockchain);
                - **Cotações, Taxas e Verificação de identidade (KYC)**.

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

    private static String stripQuotes(String text) {
        if (text == null) {
            return "";
        }
        String s = text.trim();
        while (s.startsWith("\"") || s.startsWith("'")) {
            s = s.substring(1).trim();
        }
        while (s.endsWith("\"") || s.endsWith("'")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }
}
