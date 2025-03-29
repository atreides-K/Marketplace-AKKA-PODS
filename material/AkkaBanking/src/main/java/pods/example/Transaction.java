package pods.example;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


// Transaction Actor
public class Transaction extends AbstractBehavior<Transaction.Response> {
    public interface Response {}
    public static class AddOK implements Response {}
    public static class SubtractOK implements Response {}
    public static class SubtractFailed implements Response {}

    private final int amount;
    private final ActorRef<Account.Command> fromAcc;
    private final ActorRef<Account.Command> toAcc;

    private static final Logger logger = LoggerFactory.getLogger(Transaction.class);

    private Transaction(ActorContext<Response> context, int amount, ActorRef<Account.Command> fromAcc, ActorRef<Account.Command> toAcc) {
        super(context);
        this.amount = amount;
        this.fromAcc = fromAcc;
        this.toAcc = toAcc;
        fromAcc.tell(new Account.SubtractFromBalance(this.amount, context.getSelf()));
    }

    public static Behavior<Response> create(int amount, ActorRef<Account.Command> fromAcc, ActorRef<Account.Command> toAcc) {
        return Behaviors.setup(context -> new Transaction(context, amount, fromAcc, toAcc));
    }

    @Override
    public Receive<Response> createReceive() {
        return newReceiveBuilder()
                .onMessage(SubtractFailed.class, msg -> {
                    logger.warn("Transaction failed: Insufficient funds for amount {} in {}", amount, fromAcc);
                    return Behaviors.stopped();
                })
                .onMessage(SubtractOK.class, msg -> {
                    logger.info("Amount {} deducted {}, adding to destination account...", amount, fromAcc);
                    toAcc.tell(new Account.AddToBalance(this.amount, getContext().getSelf()));
                    return this;
                })
                .onMessage(AddOK.class, msg -> {
                    logger.info("Transaction successful: Amount {} added to destination account {}", amount, toAcc);
                    return Behaviors.stopped();
                })
                .build();
    }

}
