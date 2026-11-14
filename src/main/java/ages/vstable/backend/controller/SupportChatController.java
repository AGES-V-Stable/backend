package ages.vstable.backend.controller;

import ages.vstable.backend.dto.support.SupportChatRequestDTO;
import ages.vstable.backend.dto.support.SupportChatResponseDTO;
import ages.vstable.backend.service.SupportChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/support")
@RequiredArgsConstructor
@Tag(name = "Support AI Assistant", description = "AI-powered support chatbot for V-Stable users")
public class SupportChatController {

    private final SupportChatService supportChatService;

    @PostMapping("/chat")
    @Operation(
            summary = "Send a question to the V-Stable AI Support Assistant",
            description = "Processes a user support question using the configured AI provider (Gemini, DeepSeek, or built-in V-Stable assistant) enriched with read-only account compliance/2FA status."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assistant reply generated successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid message payload"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @ApiResponse(responseCode = "423", description = "Rate limit exceeded")
    })
    public ResponseEntity<SupportChatResponseDTO> chat(
            Authentication authentication,
            @Valid @RequestBody SupportChatRequestDTO request
    ) {
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(supportChatService.chat(authentication, request));
    }
}
