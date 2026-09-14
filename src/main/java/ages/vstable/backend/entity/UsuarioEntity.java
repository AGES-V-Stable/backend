package ages.vstable.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Table(name = "usuarios")
public class UsuarioEntity implements UserDetails {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "empresa_id")
    private UUID empresaId;

    @Column(name = "nome_completo", nullable = false, length = 255)
    private String nomeCompleto;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "hash_senha", nullable = false, length = 255)
    private String hashSenha;

    @Column(name = "criado_em")
    private OffsetDateTime criadoEm;

    @Column(name = "atualizado_em")
    private OffsetDateTime atualizadoEm;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USUARIO"));
    }

    @Override
    public String getPassword() {
        return hashSenha;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
