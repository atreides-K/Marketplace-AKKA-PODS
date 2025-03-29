package pods.example;

import akka.actor.typed.Behavior;
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Main class
public class Main {
    public static Behavior<Void> create() {
        return Behaviors.setup(context -> {
            int numAccounts = 1000;
            int initialBalance = 2000;
            Map<Integer, ActorRef<Account.Command>> accounts = new HashMap<>();
            for (int i = 0; i < numAccounts; i++) {
                accounts.put(i, context.spawn(Account.create(initialBalance), "account-" + i));
            }
            System.out.println("Successfully created "+numAccounts+" accounts");

            try {
                List<String> lines = Files.readAllLines(Paths.get("transactions.txt"));
                int transactionId = 0;
                for (String line : lines) {
                    String[] parts = line.split(",");
                    int fromId = Integer.parseInt(parts[0].trim());
                    int toId = Integer.parseInt(parts[1].trim());
                    int amount = Integer.parseInt(parts[2].trim());
                    context.spawn(Transaction.create(amount, accounts.get(fromId), accounts.get(toId)), "transaction"+ transactionId+"-" + fromId + "-" + toId);
                    transactionId++;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            return Behaviors.empty();
        });
    }

    public static void main(String[] args) {
        ActorSystem.create(Main.create(), "AccountSystem");
    }
}
