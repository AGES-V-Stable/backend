package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.AuthenticationConfigurer;
import ages.vstable.backend.configuration.security.SecurityConfiguration;
import ages.vstable.backend.dto.transaction.TransactionVolumeResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.entity.enums.CompanyRole;
import ages.vstable.backend.service.TransactionVolumeService;
import ages.vstable.backend.service.UserService;
import ages.vstable.backend.support.TestCompanyUsers;
import ages.vstable.backend.utils.JwtTokenUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
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
        UserEntity user = TestCompanyUsers.member(companyId, CompanyRole.OWNER);
        when(transactionVolumeService.getSettledVolume(companyId, null, null))
                .thenReturn(new TransactionVolumeResponse("12.75"));

        mockMvc.perform(get("/v1/dashboard/transaction-volume").with(authenticated(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactedVolumeBrl").value("12.75"));
    }

    @ParameterizedTest
    @EnumSource(CompanyRole.class)
    void getTransactionVolume_withActiveMemberOfAnyRoleReturnsTheVolume(CompanyRole role) throws Exception {
        UUID companyId = UUID.randomUUID();
        when(transactionVolumeService.getSettledVolume(companyId, null, null))
                .thenReturn(new TransactionVolumeResponse("12.75"));

        mockMvc.perform(get("/v1/dashboard/transaction-volume")
                        .with(authenticated(TestCompanyUsers.member(companyId, role))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactedVolumeBrl").value("12.75"));
    }

    @Test
    void getTransactionVolume_withRemovedMembershipReturns403AccessRevokedAndDoesNotReadTheVolume() throws Exception {
        UserEntity user = TestCompanyUsers.removedMember(UUID.randomUUID(), CompanyRole.OWNER);

        mockMvc.perform(get("/v1/dashboard/transaction-volume").with(authenticated(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_REVOKED"));

        verifyNoInteractions(transactionVolumeService);
    }

    @Test
    void getTransactionVolume_withUserWithoutMembershipReturns403AccessDeniedAndDoesNotReadTheVolume()
            throws Exception {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).companyId(UUID.randomUUID()).build();

        mockMvc.perform(get("/v1/dashboard/transaction-volume").with(authenticated(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMPANY_ACCESS_DENIED"));

        verifyNoInteractions(transactionVolumeService);
    }

    private static RequestPostProcessor authenticated(UserEntity user) {
        return authentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }
}
