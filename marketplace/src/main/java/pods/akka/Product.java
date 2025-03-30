package pods.akka;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;

// Product Actor
// should be shared Entity with type
public class Product extends AbstractBehavior<Product.Command> {
    // Cbor necessary fr persistence
    public interface Command extends CborSerializable{}

    public static final EntityTypeKey<Command> TypeKey =
			    EntityTypeKey.create(Product.Command.class, "ProductEntity");

    public static Behavior<Command> create(String entityId) {
        return Behaviors.setup(context -> new Product(entityId, context));
    }
    private Product(String entityId, ActorContext<Command> context) {
        super(context);
        // Initialize fields as needed, e.g., quantity or other attributes
        this.quantity = 0; // Default value or logic based on entityId
        getContext().getLog().info("Product Actor created with entityId: {}", entityId);
    }
 
    public static final record GetProduct(String productId, ActorRef<Gateway.Response> replyTo) implements Command{

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
        System.out.println("Inside newly created" + initialBalance);
        this.quantity = initialBalance;
    }

    

    // public static Behavior<Command> create(int initialBalance) {
    //     return Behaviors.setup(context -> new Product(context, initialBalance));
    // }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(GetProduct.class, this::onGetProduct)
                .build();
    }

    private Behavior<Command> onGetProduct(GetProduct req) {
        
        req.replyTo.tell(new Gateway.Response("ola"));
        return this;
    }
 
    // private Behavior<Command> onSubtractFromBalance(SubtractFromBalance msg) {
    //     if (msg.amount <= balance) {
    //         this.balance -= msg.amount;
    //         msg.replyTo.tell(new Order.SubtractOK());
    //     } else {
    //         msg.replyTo.tell(new Order.SubtractFailed());
    //     }
    //     return this;
    // }
}
