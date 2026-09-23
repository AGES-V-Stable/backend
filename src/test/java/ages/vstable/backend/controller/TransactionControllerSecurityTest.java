package ages.vstable.backend.controller;

import ages.vstable.backend.dto.transaction.TransactionHistoryResponseDTO;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.TransactionQueryService;
import ages.vstable.backend.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    @MockitoBean
    private TransactionQueryService transactionQueryService;

    @MockBean
    @MockitoBean
    private UserService userService;

    @Test
    @WithMockUser(username = "cliente@teste.com", roles = {"USUARIO"})
    void testClienteAcessandoRotaCliente_Success() throws Exception {
        UserEntity mockUser = new UserEntity();
        mockUser.setCompanyId(UUID.randomUUID());
        
        when(userService.getByEmail("cliente@teste.com")).thenReturn(mockUser);
        
        Page<TransactionHistoryResponseDTO> mockPage = new PageImpl<>(List.of());
        when(transactionQueryService.getHistory(eq(mockUser.getCompanyId()), any(), any())).thenReturn(mockPage);

        mockMvc.perform(get("/api/transferencias"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "cliente@teste.com", roles = {"USUARIO"})
    void testClienteAcessandoRotaAdmin_Returns403() throws Exception {
        // Usuário normal tentando acessar rota de admin (falta ROLE_ADMIN)
        mockMvc.perform(get("/api/admin/transferencias"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin@teste.com", roles = {"ADMIN"})
    void testAdminAcessandoRotaAdmin_Success() throws Exception {
        Page<TransactionHistoryResponseDTO> mockPage = new PageImpl<>(List.of());
        when(transactionQueryService.getHistory(any(), any(), any())).thenReturn(mockPage);

        mockMvc.perform(get("/api/admin/transferencias"))
                .andExpect(status().isOk());
    }
}

