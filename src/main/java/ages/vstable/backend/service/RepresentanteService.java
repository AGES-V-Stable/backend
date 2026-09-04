package ages.vstable.backend.service;

import ages.vstable.backend.dto.representante.RepresentanteCreateRequest;
import ages.vstable.backend.dto.representante.RepresentanteResponse;
import ages.vstable.backend.entity.ProgressoCadastroEntity;
import ages.vstable.backend.repository.ProgressoCadastroRepository;
import ages.vstable.backend.util.CpfUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.Period;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RepresentanteService {

    private static final int IDADE_MINIMA = 18;

    private final ProgressoCadastroRepository progressoCadastroRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RepresentanteResponse create(RepresentanteCreateRequest request) {

        validateCpf(request.getCpf());
        validateAge(request.getDataNascimento());

        ProgressoCadastroEntity progresso = progressoCadastroRepository
                .findByEmailContato(request.getEmail())
                .orElseGet(() -> {
                    ProgressoCadastroEntity novo = new ProgressoCadastroEntity();
                    novo.setEmailContato(request.getEmail());
                    novo.setEtapaAtual(1);
                    return novo;
                });

        progresso.setDadosTemporarios(serialize(request));
        progresso.setEtapaAtual(2);

        ProgressoCadastroEntity salvo = progressoCadastroRepository.save(progresso);

        RepresentanteResponse response = new RepresentanteResponse();
        response.setProgressoId(salvo.getId());
        response.setEtapaAtual(salvo.getEtapaAtual());

        return response;
    }

    private void validateCpf(String cpf) {
        if (!CpfUtils.isValid(cpf)) {
            throw new IllegalArgumentException("CPF inválido");
        }
    }

    private void validateAge(LocalDate dataNascimento) {
        if (Period.between(dataNascimento, LocalDate.now()).getYears() < IDADE_MINIMA) {
            throw new IllegalArgumentException("O representante precisa ser maior de idade");
        }
    }

    private String serialize(RepresentanteCreateRequest request) {
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("representante", request);
        return objectMapper.writeValueAsString(dados);
    }
}
