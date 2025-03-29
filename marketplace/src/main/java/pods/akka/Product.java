package pods.akka;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

// Product Actor
// should be shared Entity with type
public class Product extends AbstractBehavior<Product.Command> {
    public interface Command {}

    public static class Buy implements Command {
        // i think here all relevant fields should be included, like name, price, etc.as per request
        public final int quantity;
        // public final ActorRef<Order.Response> replyTo;

        public Buy(int quantity ) {
            this.quantity = quantity;
            // this.replyTo = replyTo;
        }
    }

    // public static class SubtractFromBalance implements Command {
    //     public final int amount;
    //     public final ActorRef<Order.Response> replyTo;

    //     public SubtractFromBalance(int amount, ActorRef<Order.Response> replyTo) {
    //         this.amount = amount;
    //         this.replyTo = replyTo;
    //     }
    // }

    private int quantity;
    // other fields like name, price, etc.

    private Product(ActorContext<Command> context, int initialBalance) {
        super(context);
        this.quantity = initialBalance;
    }

    // public static Behavior<Command> create(int initialBalance) {
    //     return Behaviors.setup(context -> new Product(context, initialBalance));
    // }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Buy.class, this::onBuy)
                .build();
    }

    private Behavior<Command> onBuy(Buy req) {
        this.quantity -= req.quantity;
        // msg.replyTo.tell(new Order.AddOK());
        return this;
    }

//     private Behavior<Command> onSubtractFromBalance(SubtractFromBalance msg) {
//         if (msg.amount <= balance) {
//             this.balance -= msg.amount;
//             msg.replyTo.tell(new Order.SubtractOK());
//         } else {
//             msg.replyTo.tell(new Order.SubtractFailed());
//         }
//         return this;
//     }
}
