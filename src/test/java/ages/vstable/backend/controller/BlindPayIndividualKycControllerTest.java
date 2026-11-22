package ages.vstable.backend.controller;

import ages.vstable.backend.exception.GlobalExceptionHandler;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.external.blindpay.BlindPayGateway;
import ages.vstable.backend.repository.BlindPayIndividualKycRepository;
import ages.vstable.backend.service.blindpay.BlindPayIndividualKycService;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BlindPayIndividualKycControllerTest {
    @Test
    void status_whenEnabledAndNoKycYet_returnsInitialStatus() throws Exception {
        var gateway = mock(BlindPayGateway.class);
        var repository = mock(BlindPayIndividualKycRepository.class);
        var service = new BlindPayIndividualKycService(gateway, repository, mock(ages.vstable.backend.repository.ProviderAccountRepository.class));
        ReflectionTestUtils.setField(service, "enabled", true);
        var user = UserEntity.builder().id(UUID.randomUUID()).build();
        when(repository.findById(user.getId())).thenReturn(Optional.empty());
        var mvc = MockMvcBuilders.standaloneSetup(new BlindPayIndividualKycController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null));
        try {
            mvc.perform(get("/v1/blindpay/kyc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.termsAccepted").value(false))
                    .andExpect(jsonPath("$.submitted").value(false));
        } finally {
            SecurityContextHolder.clearContext();
        }
        verifyNoInteractions(gateway);
    }

    @Test
    void status_whenIndividualKycDisabled_returns503InsteadOfInternalError() throws Exception {
        var gateway = mock(BlindPayGateway.class);
        var repository = mock(BlindPayIndividualKycRepository.class);
        var service = new BlindPayIndividualKycService(gateway, repository, mock(ages.vstable.backend.repository.ProviderAccountRepository.class));
        var mvc = MockMvcBuilders.standaloneSetup(new BlindPayIndividualKycController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(get("/v1/blindpay/kyc"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Individual KYC is disabled for this environment"));
        verifyNoInteractions(gateway, repository);
    }
}
