package ages.vstable.backend.controller;

import ages.vstable.backend.dto.transaction.TransactionDetailsResponseDTO;
import ages.vstable.backend.dto.transaction.TransactionFilterDTO;
import ages.vstable.backend.dto.transaction.TransactionHistoryResponseDTO;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.TransactionQueryService;
import ages.vstable.backend.service.UserService;
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
    private final UserService userService;

    @GetMapping
    public ResponseEntity<Page<TransactionHistoryResponseDTO>> getHistory(
            TransactionFilterDTO filter,
            Pageable pageable,
            Authentication authentication) {
        
        UserEntity user = userService.getByEmail(authentication.getName());
        UUID companyId = user.getCompanyId();

        Page<TransactionHistoryResponseDTO> result = transactionQueryService.getHistory(companyId, filter, pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionDetailsResponseDTO> getDetails(
            @PathVariable UUID id,
            Authentication authentication) {
        
        UserEntity user = userService.getByEmail(authentication.getName());
        UUID companyId = user.getCompanyId();

        TransactionDetailsResponseDTO result = transactionQueryService.getDetails(id, companyId);
        return ResponseEntity.ok(result);
    }
}

