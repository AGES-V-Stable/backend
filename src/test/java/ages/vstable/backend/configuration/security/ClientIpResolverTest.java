package ages.vstable.backend.configuration.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientIpResolverTest {

    private static final String ADAPTER = "127.0.0.1";

    // ---- sem proxy confiável (padrão fora da Lambda) ----

    @Test
    void zeroHops_ignoresTheForwardedHeaderEvenFromLoopback() {
        // Arrange
        MockHttpServletRequest request = request(ADAPTER, "203.0.113.9");

        // Act / Assert
        assertThat(new ClientIpResolver(0).resolve(request)).isEqualTo(ADAPTER);
    }

    @Test
    void externalConnection_neverTrustsTheForwardedHeader() {
        // Arrange: alguém fala direto com o servidor e envia o cabeçalho
        MockHttpServletRequest request = request("198.51.100.20", "203.0.113.9");

        // Act / Assert
        assertThat(new ClientIpResolver(1).resolve(request)).isEqualTo("198.51.100.20");
        assertThat(new ClientIpResolver(2).resolve(request)).isEqualTo("198.51.100.20");
    }

    // ---- um proxy confiável (API Gateway regional) ----

    @Test
    void oneHop_usesTheEntryAppendedByTheGateway() {
        // Act / Assert
        assertThat(new ClientIpResolver(1).resolve(request(ADAPTER, "203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test
    void oneHop_spoofedEntriesOnTheLeftAreIgnored() {
        // Arrange: o cliente envia IPs falsos; o gateway acrescenta o IP real no fim
        MockHttpServletRequest request = request(ADAPTER, "10.0.0.1, 1.2.3.4, 203.0.113.9");

        // Act / Assert
        assertThat(new ClientIpResolver(1).resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void oneHop_spoofedSeparateHeaderBeforeTheGatewayEntryIsIgnored() {
        // Arrange: cabeçalhos repetidos são lidos na ordem; o último item continua sendo o do gateway
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/company-invitations/accept");
        request.setRemoteAddr(ADAPTER);
        request.addHeader(ClientIpResolver.FORWARDED_FOR, "1.2.3.4");
        request.addHeader(ClientIpResolver.FORWARDED_FOR, "5.6.7.8, 203.0.113.9");

        // Act / Assert
        assertThat(new ClientIpResolver(1).resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void oneHop_ipv6LoopbackAndIpv6Client_areSupported() {
        // Arrange
        MockHttpServletRequest request = request("0:0:0:0:0:0:0:1", "2001:db8::7");

        // Act / Assert
        assertThat(new ClientIpResolver(1).resolve(request)).isEqualTo("2001:db8::7");
    }

    // ---- vários proxies confiáveis (CloudFront + API Gateway) ----

    @Test
    void twoHops_skipsTheLastProxyAndUsesTheClientAppendedByTheFirst() {
        // Arrange: falso, cliente (acrescentado pelo CloudFront), CloudFront (acrescentado pelo API Gateway)
        MockHttpServletRequest request = request(ADAPTER, "1.2.3.4, 203.0.113.9, 130.176.0.10");

        // Act / Assert
        assertThat(new ClientIpResolver(2).resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void oneHopConfiguredBehindTwoProxies_degradesToTheProxyAddressButIsNeverSpoofable() {
        // Arrange
        MockHttpServletRequest request = request(ADAPTER, "1.2.3.4, 203.0.113.9, 130.176.0.10");

        // Act / Assert: agrupa pelo IP do proxy (limita mais), nunca pelo valor enviado pelo cliente
        assertThat(new ClientIpResolver(1).resolve(request)).isEqualTo("130.176.0.10");
    }

    // ---- sem item confiável ----

    @Test
    void missingHeader_fallsBackToTheConnectionAddress() {
        // Arrange
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/company-invitations/accept");
        request.setRemoteAddr(ADAPTER);

        // Act / Assert
        assertThat(new ClientIpResolver(1).resolve(request)).isEqualTo(ADAPTER);
    }

    @Test
    void fewerEntriesThanTrustedProxies_fallsBackToTheConnectionAddress() {
        // Arrange: com dois proxies esperados, um único item pode ter vindo do cliente
        MockHttpServletRequest request = request(ADAPTER, "203.0.113.9");

        // Act / Assert
        assertThat(new ClientIpResolver(2).resolve(request)).isEqualTo(ADAPTER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.9, ", "203.0.113.9, unknown", "203.0.113.9, <script>",
            "203.0.113.9, cafe", "203.0.113.9, 1.2.3.4.5.6.7.8.9.10.11.12.13.14.15.16.17.18.19.20.21.22.23"})
    void trustedPositionWithoutAnIpLiteral_fallsBackToTheConnectionAddress(String header) {
        // Act / Assert
        assertThat(new ClientIpResolver(1).resolve(request(ADAPTER, header))).isEqualTo(ADAPTER);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, ClientIpResolver.MAX_TRUSTED_PROXY_HOPS + 1})
    void invalidHopConfiguration_isRejectedAtStartup(int hops) {
        // Act / Assert
        assertThatThrownBy(() -> new ClientIpResolver(hops)).isInstanceOf(IllegalArgumentException.class);
    }

    private static MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/company-invitations/accept");
        request.setRemoteAddr(remoteAddr);
        request.addHeader(ClientIpResolver.FORWARDED_FOR, forwardedFor);
        return request;
    }
}
