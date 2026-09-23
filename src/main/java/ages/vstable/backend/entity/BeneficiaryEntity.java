package ages.vstable.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "beneficiaries")
public class BeneficiaryEntity {
    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "nickname", nullable = false, length = 100)
    private String nickname;
    
    @Column(name = "account_holder_name", length = 255)
    private String accountHolderName;
}

