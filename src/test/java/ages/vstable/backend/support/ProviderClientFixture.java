package ages.vstable.backend.support;

import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.AveniaProperties;
import ages.vstable.backend.external.avenia.AveniaRequestSigner;
import ages.vstable.backend.external.avenia.dto.AveniaQuoteRequest;
import ages.vstable.backend.external.blindpay.BlindPayClient;
import ages.vstable.backend.external.blindpay.BlindPayProperties;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;

/** Clientes reais com credenciais descartaveis, para transporte simulado ou loopback. */
public final class ProviderClientFixture {
    private ProviderClientFixture() { }

    public static Runnable call(String provider, String origin, RestClient.Builder builder) throws Exception {
        if (provider.equals("blindpay")) {
            BlindPayProperties properties = new BlindPayProperties();
            properties.setBaseUrl(origin);
            properties.setApiKey(UUID.randomUUID().toString());
            properties.setInstanceId("synthetic");
            BlindPayClient client = new BlindPayClient(properties, new ObjectMapper(), builder);
            return () -> client.getCustomer("synthetic");
        }
        if (!provider.equals("avenia")) throw new IllegalArgumentException("Unknown synthetic provider");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        AveniaProperties properties = new AveniaProperties();
        properties.setBaseUrl(origin);
        properties.setApiKey(UUID.randomUUID().toString());
        properties.setPrivateKey("-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----");
        AveniaClient client = new AveniaClient(properties, new AveniaRequestSigner(), new ObjectMapper(), builder);
        AveniaQuoteRequest request = new AveniaQuoteRequest("BRL", "PIX", "USDC", "POLYGON",
                new BigDecimal("100"), null, false, false, null, null, null, null, null, null, null, "synthetic");
        return () -> client.createQuote(request);
    }
}
