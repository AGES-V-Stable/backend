package ages.vstable.backend.controller;

import ages.vstable.backend.dto.quote.QuoteRequest;
import ages.vstable.backend.dto.quote.QuoteResponse;
import ages.vstable.backend.entity.UserEntity;
import ages.vstable.backend.service.quote.QuoteOrchestratorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/quotes")
@RequiredArgsConstructor
public class QuoteController {

    private final QuoteOrchestratorService quoteOrchestratorService;

    @PostMapping
    public ResponseEntity<QuoteResponse> quote(
            @AuthenticationPrincipal UserEntity currentUser,
            @Valid @RequestBody QuoteRequest request) {
        return ResponseEntity.ok(quoteOrchestratorService.quote(currentUser, request));
    }
}
