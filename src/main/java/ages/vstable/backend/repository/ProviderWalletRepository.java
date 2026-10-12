package ages.vstable.backend.repository;

import ages.vstable.backend.entity.ProviderWalletEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProviderWalletRepository extends JpaRepository<ProviderWalletEntity, UUID> {
    Optional<ProviderWalletEntity> findByProviderAccountIdAndNetwork(UUID providerAccountId, String network);
}
