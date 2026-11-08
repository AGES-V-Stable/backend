package ages.vstable.backend.controller;

import ages.vstable.backend.configuration.security.CompanyAccess;
import ages.vstable.backend.dto.transaction.TransactionFilterDTO;
import ages.vstable.backend.dto.transaction.TransactionHistoryResponseDTO;
import ages.vstable.backend.entity.enums.CompanyPermission;
import ages.vstable.backend.service.TransactionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/transferencias")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionQueryService transactionQueryService;

    @GetMapping
    public ResponseEntity<Page<TransactionHistoryResponseDTO>> getHistory(
            TransactionFilterDTO filter,
            Pageable pageable,
            Authentication authentication) {

        // A empresa vem do vínculo ativo: sem ela o filtro de escopo seria omitido
        // e a consulta devolveria as transações de todas as empresas.
        UUID companyId = CompanyAccess.requireCompanyId(authentication, CompanyPermission.TRANSFER_READ);

        Page<TransactionHistoryResponseDTO> result = transactionQueryService.getHistory(companyId, filter, pageable);
        return ResponseEntity.ok(result);
    }
}
