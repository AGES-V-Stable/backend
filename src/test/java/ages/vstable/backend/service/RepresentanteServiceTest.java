package ages.vstable.backend.service;

import ages.vstable.backend.dto.representante.RepresentanteResponse;
import ages.vstable.backend.entity.UsuarioEntity;
import ages.vstable.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepresentanteServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    private RepresentanteService representanteService;

    @Test
    void findAll_retornaTodosOsRepresentantesSemExporHashDaSenha() {
        representanteService = new RepresentanteService(usuarioRepository);

        UUID empresaId = UUID.randomUUID();
        UsuarioEntity usuario = UsuarioEntity.builder()
                .id(UUID.randomUUID())
                .empresaId(empresaId)
                .nomeCompleto("Joao da Silva")
                .email("joao@example.com")
                .hashSenha("hash-nunca-deve-vazar")
                .build();

        when(usuarioRepository.findAll()).thenReturn(List.of(usuario));

        List<RepresentanteResponse> resultado = representanteService.findAll();

        assertThat(resultado).hasSize(1);
        RepresentanteResponse response = resultado.get(0);
        assertThat(response.getId()).isEqualTo(usuario.getId());
        assertThat(response.getEmpresaId()).isEqualTo(empresaId);
        assertThat(response.getNomeCompleto()).isEqualTo("Joao da Silva");
        assertThat(response.getEmail()).isEqualTo("joao@example.com");
    }

    @Test
    void findAll_semRepresentantes_retornaListaVazia() {
        representanteService = new RepresentanteService(usuarioRepository);

        when(usuarioRepository.findAll()).thenReturn(List.of());

        List<RepresentanteResponse> resultado = representanteService.findAll();

        assertThat(resultado).isEmpty();
    }
}
