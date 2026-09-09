package ages.vstable.backend.service;

import ages.vstable.backend.dto.compliance.LivenessStartResponse;
import ages.vstable.backend.dto.compliance.LivenessSubmitRequest;
import ages.vstable.backend.entity.ProgressoCadastroEntity;
import ages.vstable.backend.external.avenia.AveniaClient;
import ages.vstable.backend.external.avenia.dto.AveniaDocumentResponse;
import ages.vstable.backend.repository.ProgressoCadastroRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ComplianceLivenessService {

    private final ProgressoCadastroRepository progressoCadastroRepository;
    private final AveniaClient aveniaClient;

    public Optional<LivenessStartResponse> iniciar(UUID progressoCadastroId) {
        return progressoCadastroRepository.findById(progressoCadastroId)
                .map(progresso -> {
                    AveniaDocumentResponse aveniaResponse = aveniaClient.iniciarLiveness();

                    LivenessStartResponse response = new LivenessStartResponse();
                    response.setId(aveniaResponse.getId());
                    response.setSessionId(aveniaResponse.getSessionId());
                    response.setLivenessUrl(aveniaResponse.getLivenessUrl());
                    response.setValidateLivenessToken(aveniaResponse.getValidateLivenessToken());
                    return response;
                });
    }

    public boolean concluir(UUID progressoCadastroId, LivenessSubmitRequest request) {
        return progressoCadastroRepository.findById(progressoCadastroId)
                .map(progresso -> {
                    progresso.setLivenessId(request.getLivenessId());
                    progressoCadastroRepository.save(progresso);
                    return true;
                })
                .orElse(false);
    }
}
