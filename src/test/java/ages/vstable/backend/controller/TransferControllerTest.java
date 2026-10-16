package ages.vstable.backend.controller;

import ages.vstable.backend.dto.transfer.TransferFilter;
import ages.vstable.backend.dto.transfer.TransferResponse;
import ages.vstable.backend.entity.enums.TransactionStatus;
import ages.vstable.backend.entity.enums.TransferDirection;
import ages.vstable.backend.exception.GlobalExceptionHandler;
import ages.vstable.backend.exception.NotFoundException;
import ages.vstable.backend.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class TransferControllerTest {

    @Mock
    private TransferService transferService;

    @InjectMocks
    private TransferController transferController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(transferController)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void findAll_parsesFiltersAndReturnsSpringPage() throws Exception {
        TransferResponse transfer = new TransferResponse();
        transfer.setId(UUID.randomUUID());
        transfer.setStatus(TransactionStatus.SETTLED);
        transfer.setForeignAmount(new BigDecimal("10.50"));
        when(transferService.findTransfers(any(TransferFilter.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(transfer), PageRequest.of(1, 12), 13));

        mockMvc.perform(get("/v1/transfers")
                        .param("page", "1")
                        .param("size", "12")
                        .param("status", "SETTLED")
                        .param("direction", "PAYMENT")
                        .param("startDate", "2026-09-01")
                        .param("endDate", "2026-09-30")
                        .param("minAmount", "10.5")
                        .param("search", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("SETTLED"))
                .andExpect(jsonPath("$.content[0].foreignAmount").value("10.50"))
                .andExpect(jsonPath("$.totalElements").value(13))
                .andExpect(jsonPath("$.number").value(1));

        ArgumentCaptor<TransferFilter> captor = ArgumentCaptor.forClass(TransferFilter.class);
        verify(transferService).findTransfers(captor.capture(), any(Pageable.class));
        TransferFilter filter = captor.getValue();
        assertThat(filter.status()).isEqualTo(TransactionStatus.SETTLED);
        assertThat(filter.direction()).isEqualTo(TransferDirection.PAYMENT);
        assertThat(filter.startDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(filter.minAmount()).isEqualByComparingTo("10.5");
        assertThat(filter.search()).isEqualTo("acme");
    }

    @Test
    void findAll_unknownStatus_returns400() throws Exception {
        mockMvc.perform(get("/v1/transfers").param("status", "Concluída"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(transferService);
    }

    @Test
    void findById_unknownTransfer_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(transferService.findById(id)).thenThrow(new NotFoundException("Transferência não encontrada"));

        mockMvc.perform(get("/v1/transfers/{id}", id))
                .andExpect(status().isNotFound());
    }
}
