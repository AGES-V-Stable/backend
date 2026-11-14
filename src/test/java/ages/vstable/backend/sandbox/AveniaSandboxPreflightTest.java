package ages.vstable.backend.sandbox;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag("avenia-security-sandbox")
@EnabledIfEnvironmentVariable(named = "AVENIA_SECURITY_SANDBOX_ENABLED", matches = "true")
class AveniaSandboxPreflightTest {
    @Test
    void verifyAccountPermissionsAndCallbackDestinationsWithoutWrites() throws Exception {
        if (!System.getProperty("avenia.assessment.phase", "preflight").equals("preflight")) return;
        AveniaSandboxSession s = new AveniaSandboxSession();
        try {
            int before = s.calls.size();
            JsonNode account = s.read("/v2/account/account-info");
            s.accountConfirmed = "900f6fe481e2ca36".equals(AveniaSandboxSession.fingerprint(account.path("id").asString()));
            s.record("TC-SBX-AV-01.1", s.accountConfirmed ? "PASS" : "BLOQUEADO", "Autenticacao RSA e conta",
                    "HTTP 200 e conta sandbox esperada", s.accountConfirmed ? "Conta confirmada; ID e dados pessoais omitidos" : "Conta nao corresponde",
                    "AveniaClient.get real via adaptador de teste + signer RSA de producao",
                    Map.of("account_type", account.path("accountInfo").path("accountType").asString("UNKNOWN")), before);
            if (!s.accountConfirmed) throw new IllegalStateException("Account mismatch; stop");
            before = s.calls.size();
            JsonNode access = s.read("/v2/account/access-info");
            Map<String, Object> permissions = AveniaSandboxSession.permissionFlags(access);
            s.record("TC-SBX-AV-01.2", permissions.isEmpty() ? "BLOQUEADO" : "PASS", "Permissoes do acesso", "Consultar flags sem exportar PII",
                    "Consulta autenticada concluida", "Cliente/signer reais; leitura de preparacao", permissions, before);
            before = s.calls.size();
            JsonNode metadata = s.read("/v2/account/metadata");
            Map<String, Object> flags = new LinkedHashMap<>();
            for (String key : List.of("skipLiveness", "easierLevel1", "brlUnlocked", "usdUnlocked", "eurUnlocked", "arsUnlocked", "copUnlocked", "mxnUnlocked", "swiftUsdUnlocked")) {
                if (metadata.path(key).isBoolean()) flags.put(key, metadata.path(key).asBoolean());
            }
            s.record("TC-SBX-AV-01.3", "PASS", "Liberacoes por moeda/KYC", "Consultar liberacoes reais", "Flags consultadas; nao equivalem a KYB institucional", "Sandbox real", flags, before);
            before = s.calls.size();
            JsonNode limits = s.read("/v2/account/limits");
            s.record("TC-SBX-AV-01.4", "PASS", "Limites operacionais", "Consultar bloqueio sem publicar saldos ou valores da conta",
                    "Limites consultados; valores nao exportados", "Sandbox real",
                    Map.of("blocked", limits.path("limitInfo").path("blocked").asBoolean(true),
                            "currency_entries", limits.path("limitInfo").path("limits").size()), before);
            before = s.calls.size();
            JsonNode webhooks = s.read("/v2/notifications/webhooks/");
            boolean shape = webhooks.path("webhooks").isArray();
            int count = webhooks.path("webhooks").size();
            s.evidence.put("callback_preflight", shape && count == 0 ? "NO_REGISTERED_DESTINATIONS" : "WRITES_BLOCKED_SHARED_OR_UNKNOWN_DESTINATION");
            s.record("TC-SBX-AV-01.5", shape && count == 0 ? "PASS" : "BLOQUEADO", "Destinos de callbacks",
                    "Nenhum destino compartilhado afetado", shape && count == 0 ? "Lista vazia; configuracao preservada" : "Ha destinos ou contrato inesperado; nao executar escritas",
                    "Consulta de configuracao real; URLs omitidas", Map.of("registered_destinations", count, "expected_shape", shape), before);
            s.finish("FINISHED");
        } catch (Exception ex) {
            s.evidence.put("stopped_error_type", ex.getClass().getSimpleName());
            s.finish("INTERRUPTED");
            throw new AssertionError("Preflight interrupted; inspect sanitized assessment.json");
        }
    }
}
