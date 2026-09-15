package ages.vstable.backend.service;

import ages.vstable.backend.dto.onboarding.OnboardingRequestDTO;
import ages.vstable.backend.dto.onboarding.OnboardingResponseDTO;
import ages.vstable.backend.entity.AveniaKycVerificationEntity;
import ages.vstable.backend.entity.EmpresaEntity;
import ages.vstable.backend.entity.UsuarioEntity;
import ages.vstable.backend.entity.enums.ComplianceStatus;
import ages.vstable.backend.exception.ConflictException;
import ages.vstable.backend.exception.UnprocessableEntityException;
import ages.vstable.backend.repository.AveniaKycVerificationRepository;
import ages.vstable.backend.repository.EmpresaRepository;
import ages.vstable.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern SENHA_FORTE_PATTERN = Pattern.compile("^(?=.*\\d)(?=.*[^a-zA-Z0-9]).{8,}$");

    private final UsuarioRepository usuarioRepository;
    private final EmpresaRepository empresaRepository;
    private final AveniaKycVerificationRepository aveniaKycVerificationRepository;
    private final EmpresaDadosValidator empresaDadosValidator;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public OnboardingResponseDTO realizarOnboarding(OnboardingRequestDTO request) {
        validatePayload(request);

        EmpresaDadosNormalizados dadosEmpresa = empresaDadosValidator.normalize(
                request.getRazaoSocial(), request.getCnpj(), request.getPais(),
                request.getCep(), request.getCidade(), request.getEstado());

        if (usuarioRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("E-mail já cadastrado");
        }
        if (empresaRepository.existsByCnpj(dadosEmpresa.cnpj())) {
            throw new ConflictException("CNPJ já cadastrado");
        }

        try {
            return criarEmpresaUsuarioEKyc(request, dadosEmpresa);
        } catch (DataIntegrityViolationException e) {
            if (usuarioRepository.existsByEmail(request.getEmail())) {
                throw new ConflictException("E-mail já cadastrado");
            }
            if (empresaRepository.existsByCnpj(dadosEmpresa.cnpj())) {
                throw new ConflictException("CNPJ já cadastrado");
            }
            throw e;
        }
    }

    private OnboardingResponseDTO criarEmpresaUsuarioEKyc(OnboardingRequestDTO request, EmpresaDadosNormalizados dadosEmpresa) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        EmpresaEntity empresa = new EmpresaEntity();
        empresa.setLegalName(dadosEmpresa.razaoSocial());
        empresa.setTradeName(normalizeOptional(request.getNomeFantasia()));
        empresa.setCnpj(dadosEmpresa.cnpj());
        empresa.setCountry(dadosEmpresa.pais());
        empresa.setZipCode(dadosEmpresa.cep());
        empresa.setCity(dadosEmpresa.cidade());
        empresa.setState(dadosEmpresa.estado());
        empresa.setKybStatus(ComplianceStatus.PENDING);
        empresa.setAmlStatus(ComplianceStatus.PENDING);
        empresa.setAvailableBalanceBrl(BigDecimal.ZERO);
        empresa.setCreatedAt(now);
        empresa.setUpdatedAt(now);
        empresa = empresaRepository.saveAndFlush(empresa);

        UsuarioEntity usuario = new UsuarioEntity();
        usuario.setCompanyId(empresa.getId());
        usuario.setFullName(request.getNomeCompleto());
        usuario.setEmail(request.getEmail());
        usuario.setPasswordSalt(BCrypt.gensalt());
        usuario.setPasswordHash(passwordEncoder.encode(request.getSenha()));
        usuario.setCreatedAt(now);
        usuario.setUpdatedAt(now);
        usuario = usuarioRepository.saveAndFlush(usuario);

        AveniaKycVerificationEntity kyc = new AveniaKycVerificationEntity();
        kyc.setUserId(usuario.getId());
        kyc.setStatus(ComplianceStatus.PENDING);
        kyc.setResponsePayload("{}");
        kyc.setCreatedAt(now);
        kyc.setUpdatedAt(now);
        kyc = aveniaKycVerificationRepository.save(kyc);

        return OnboardingResponseDTO.builder()
                .usuarioId(usuario.getId())
                .empresaId(empresa.getId())
                .verificacaoKycId(kyc.getId())
                .build();
    }

    private void validatePayload(OnboardingRequestDTO request) {
        if (!EMAIL_PATTERN.matcher(request.getEmail()).matches()) {
            throw new UnprocessableEntityException("E-mail em formato inválido");
        }
        if (request.getSenha() == null || !request.getSenha().equals(request.getConfirmarSenha())) {
            throw new UnprocessableEntityException("Senha e confirmação não coincidem");
        }
        if (!SENHA_FORTE_PATTERN.matcher(request.getSenha()).matches()) {
            throw new UnprocessableEntityException("Senha deve ter ao menos 8 caracteres, incluindo número e caractere especial");
        }
    }

    private String normalizeOptional(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
