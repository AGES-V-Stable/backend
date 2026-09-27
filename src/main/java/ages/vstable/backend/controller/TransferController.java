package ages.vstable.backend.controller;

import ages.vstable.backend.dto.transfer.TransferCreateRequest;
import ages.vstable.backend.dto.transfer.TransferCreateResponse;
import ages.vstable.backend.dto.transfer.TransferQuoteRequest;
import ages.vstable.backend.dto.transfer.TransferQuoteResponse;
import ages.vstable.backend.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/transfers")
@RequiredArgsConstructor
@Tag(name = "Transfers")
public class TransferController {

    private final TransferService transferService;

    @PostMapping("/quote")
    @Operation(summary = "Retrieves a transfer quote from Avenia")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Quote retrieved successfully"),
            @ApiResponse(responseCode = "400", description = "Missing or invalid field"),
            @ApiResponse(responseCode = "502", description = "Integration error with Avenia"),
    })
    public ResponseEntity<TransferQuoteResponse> quote(
            @Valid @RequestBody TransferQuoteRequest request) {

        return ResponseEntity.ok(transferService.quote(request));
    }

    @PostMapping
    @Operation(summary = "Creates a transfer via Avenia")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Transfer accepted for processing"),
            @ApiResponse(responseCode = "400", description = "Missing or invalid field"),
            @ApiResponse(responseCode = "502", description = "Integration error with Avenia"),
    })
    public ResponseEntity<TransferCreateResponse> create(
            @Valid @RequestBody TransferCreateRequest request) {

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(transferService.create(request));
    }
}
