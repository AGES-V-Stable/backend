package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.AuthenticationConfigurer;
import ages.vstable.backend.configuration.security.SecurityConfiguration;
import ages.vstable.backend.dto.transaction.TransactionVolumeResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.TransactionVolumeService;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionVolumeController.class)
@Import({SecurityConfiguration.class, AuthenticationConfigurer.class})
class TransactionVolumeControllerSecurityTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean
    private TransactionVolumeService transactionVolumeService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenUtils jwtTokenUtils;

    @MockitoBean
    private AuthenticationProvider authenticationProvider;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void getTransactionVolume_withoutAuthenticationReturns401AndDoesNotReadTheVolume() throws Exception {
        mockMvc.perform(get("/v1/dashboard/transaction-volume"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(transactionVolumeService);
    }

    @Test
    void getTransactionVolume_withAuthenticatedCompanyUserReturnsTheVolume() throws Exception {
        UUID companyId = UUID.randomUUID();
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).companyId(companyId).build();
        when(transactionVolumeService.getSettledVolume(companyId, null))
                .thenReturn(new TransactionVolumeResponse("12.75"));

        mockMvc.perform(get("/v1/dashboard/transaction-volume")
                        .with(authentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactedVolumeBrl").value("12.75"));
    }
}
