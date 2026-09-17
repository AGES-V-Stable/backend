package ages.vstable.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import lombok.experimental.SuperBuilder;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Entity
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Table(name = "users")
public class UserEntity extends BaseEntity implements UserDetails {

    @Column(name = "company_id")
    private UUID companyId;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USUARIO"));
    }

    @Override
    public String getPassword() {
        return getPasswordHash();
    }

    @Override
    public String getUsername() {
        return getEmail();
    }
}
