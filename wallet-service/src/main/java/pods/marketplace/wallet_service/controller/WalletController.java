package pods.marketplace.wallet_service.controller;

import pods.marketplace.wallet_service.model.Wallet;
import pods.marketplace.wallet_service.repository.WalletRepository;

//import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.transaction.Transactional;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletRepository walletRepository;

    public WalletController(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    // 1. GET /wallets/{userId}
    @Transactional
    @GetMapping("/{user_id}")
    public ResponseEntity<Wallet> getWallet(@PathVariable Integer user_id) {
        return walletRepository.findWalletForUpdate(user_id)
                .map(wallet -> ResponseEntity.ok(wallet))
                .orElse(ResponseEntity.notFound().build());
    }

    // 2. PUT /wallets/{userId}
    @Transactional
    @PutMapping("/{userId}")
    public ResponseEntity<Wallet> updateWallet(
            @PathVariable Integer userId,
            @RequestBody WalletUpdateRequest request
    ) {
        // Retrieve or create wallet
        Wallet wallet = walletRepository.findWalletForUpdate(userId)
            .orElse(new Wallet(userId, 0));

        System.out.println("Wallet before update: " + wallet);

        if ("debit".equalsIgnoreCase(request.getAction())) {
            System.out.println("Action: debit, Amount: " + request.getAmount());
            // Check balance
            if (wallet.getBalance() < request.getAmount()) {
                // Insufficient funds
                System.out.println("Insufficient balance for debit action. UserId: " + userId + ", Requested Amount: " + request.getAmount() + ", Current Balance: " + wallet.getBalance());
                return ResponseEntity.badRequest().build();
            }
            wallet.setBalance(wallet.getBalance() - request.getAmount());
            System.out.println("Debit successful. UserId: " + userId + ", Debited Amount: " + request.getAmount() + ", New Balance: " + wallet.getBalance());
        } else if ("credit".equalsIgnoreCase(request.getAction())) {
            System.out.println("Action: credit, Amount: " + request.getAmount());
            wallet.setBalance(wallet.getBalance() + request.getAmount());
            System.out.println("Credit successful. UserId: " + userId + ", Credited Amount: " + request.getAmount() + ", New Balance: " + wallet.getBalance());
        } else {
            // Invalid action
            System.out.println("Invalid action: " + request.getAction() + " for UserId: " + userId);
            return ResponseEntity.badRequest().build();
        }

        System.out.println("Wallet after update: " + wallet);

        // Save the updated wallet
        walletRepository.save(wallet);
        System.out.println("Wallet saved successfully for UserId: " + userId);
        return ResponseEntity.ok(wallet);
    }

    // 3. DELETE /wallets/{userId}
    @Transactional
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deleteWallet(@PathVariable Integer userId) {
        return walletRepository.findWalletForUpdate(userId)
                .map(existingWallet -> {
                    walletRepository.delete(existingWallet);
                    return ResponseEntity.ok().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // 4. DELETE /wallets
    @Transactional
    @DeleteMapping
    public ResponseEntity<Void> deleteAllWallets() {
        walletRepository.deleteAll();
        return ResponseEntity.ok().build();
    }

    // Helper class for request body
    static class WalletUpdateRequest {
        private String action;
        private int amount;

        public String getAction() {
            return action;
        }
        public void setAction(String action) {
            this.action = action;
        }
        public int getAmount() {
            return amount;
        }
        public void setAmount(int amount) {
            this.amount = amount;
        }
    }
}
