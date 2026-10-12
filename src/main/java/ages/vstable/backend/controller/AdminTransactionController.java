package ages.vstable.backend.controller;

import ages.vstable.backend.dto.transaction.TransactionDetailsResponseDTO;
import ages.vstable.backend.dto.transaction.TransactionFilterDTO;
import ages.vstable.backend.dto.transaction.TransactionHistoryResponseDTO;
import ages.vstable.backend.service.TransactionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/transferencias")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminTransactionController {

    private final TransactionQueryService transactionQueryService;

    @GetMapping
    public ResponseEntity<Page<TransactionHistoryResponseDTO>> getHistory(
            @RequestParam(required = false) UUID companyId,
            TransactionFilterDTO filter,
            Pageable pageable) {

        Page<TransactionHistoryResponseDTO> result = transactionQueryService.getHistory(companyId, filter, pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionDetailsResponseDTO> getDetails(@PathVariable UUID id) {
        
        TransactionDetailsResponseDTO result = transactionQueryService.getDetails(id, null);
        return ResponseEntity.ok(result);
    }
}

