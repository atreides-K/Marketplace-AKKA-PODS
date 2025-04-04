package pods.marketplace.wallet_service.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
public class Wallet {

    @Id
    private Integer user_id;  // Primary key
    private Integer balance;
    
    public Wallet(Integer user_id, Integer balance)
    {
        this.user_id = user_id;
        this.balance = balance;
    }

}
