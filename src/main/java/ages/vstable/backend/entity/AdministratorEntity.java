package ages.vstable.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@SuperBuilder
@NoArgsConstructor
@Getter
@Setter
@Table(name = "administrators")
public class AdministratorEntity extends BaseEntity {
}
