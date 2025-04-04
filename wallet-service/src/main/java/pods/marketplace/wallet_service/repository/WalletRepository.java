package pods.marketplace.wallet_service.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import pods.marketplace.wallet_service.model.Wallet;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.user_id = :userId")
    Optional<Wallet> findWalletForUpdate(@Param("userId") int userId);
}
