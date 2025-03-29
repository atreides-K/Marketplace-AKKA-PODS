package pods.example;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

// Account Actor
public class Account extends AbstractBehavior<Account.Command> {
    public interface Command {}

    public static class AddToBalance implements Command {
        public final int amount;
        public final ActorRef<Transaction.Response> replyTo;

        public AddToBalance(int amount, ActorRef<Transaction.Response> replyTo) {
            this.amount = amount;
            this.replyTo = replyTo;
        }
    }

    public static class SubtractFromBalance implements Command {
        public final int amount;
        public final ActorRef<Transaction.Response> replyTo;

        public SubtractFromBalance(int amount, ActorRef<Transaction.Response> replyTo) {
            this.amount = amount;
            this.replyTo = replyTo;
        }
    }

    private int balance;

    private Account(ActorContext<Command> context, int initialBalance) {
        super(context);
        this.balance = initialBalance;
    }

    public static Behavior<Command> create(int initialBalance) {
        return Behaviors.setup(context -> new Account(context, initialBalance));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(AddToBalance.class, this::onAddToBalance)
                .onMessage(SubtractFromBalance.class, this::onSubtractFromBalance)
                .build();
    }

    private Behavior<Command> onAddToBalance(AddToBalance msg) {
        this.balance += msg.amount;
        msg.replyTo.tell(new Transaction.AddOK());
        return this;
    }

    private Behavior<Command> onSubtractFromBalance(SubtractFromBalance msg) {
        if (msg.amount <= balance) {
            this.balance -= msg.amount;
            msg.replyTo.tell(new Transaction.SubtractOK());
        } else {
            msg.replyTo.tell(new Transaction.SubtractFailed());
        }
        return this;
    }
}
