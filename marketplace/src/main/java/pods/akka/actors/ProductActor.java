package pods.akka.actors;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import pods.akka.CborSerializable;

public class ProductActor extends AbstractBehavior<ProductActor.Command> {

    // Define message protocol for ProductActor
    public interface Command extends CborSerializable{}
        public static final EntityTypeKey<Command> TypeKey =
			    EntityTypeKey.create(ProductActor.Command.class, "ProductEntity");

    public static final class GetProduct implements Command {
        public final akka.actor.typed.ActorRef<ProductResponse> replyTo;
        public GetProduct(akka.actor.typed.ActorRef<ProductResponse> replyTo) {
            this.replyTo = replyTo;
        }
    }

    public static final class DeductStock implements Command {
        public final int quantity;
        public final akka.actor.typed.ActorRef<OperationResponse> replyTo;
        public DeductStock(int quantity, akka.actor.typed.ActorRef<OperationResponse> replyTo) {
            this.quantity = quantity;
            this.replyTo = replyTo;
        }
    }

    // Command to add stock (for rollback)
    public static final class AddStock implements Command {
        public final int quantity;
        // In this simplified example, we do not expect a reply.
        public AddStock(int quantity) {
            this.quantity = quantity;
        }
    }

    // Reply messages
    public static final class ProductResponse {
        public final String id;
        public final String description;
        public final int price;
        public final int availableStock;
        public ProductResponse(String id, String description, int price, int availableStock) {
            this.id = id;
            this.description = description;
            this.price = price;
            this.availableStock = availableStock;
        }
    }

    public static final class OperationResponse {
        public final boolean success;
        public final String message;
        public final int priceDeducted;  // computed as quantity * price if deduction succeeds
        public OperationResponse(boolean success, String message, int priceDeducted) {
            this.success = success;
            this.message = message;
            this.priceDeducted = priceDeducted;
        }
    }

    // Product state
    private final String productId;
    private final String description;
    private final int price;
    private int stockQuantity;

    // Factory method to create a ProductActor
    public static Behavior<Command> create(String productId, String description, int price, int initialStock) {
        return akka.actor.typed.javadsl.Behaviors.setup(context ->
                new ProductActor(context, productId, description, price, initialStock));
    }

    private ProductActor(ActorContext<Command> context, String productId, String description, int price, int initialStock) {
        super(context);
        this.productId = productId;
        this.description = description;
        this.price = price;
        this.stockQuantity = initialStock;
        getContext().getLog().info("ProductActor created with ID: {}, Description: {}, Price: {}, Initial Stock: {}", productId, description, price, initialStock);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(GetProduct.class, this::onGetProduct)
                .onMessage(DeductStock.class, this::onDeductStock)
                .onMessage(AddStock.class, this::onAddStock)
                .build();
    }

    private Behavior<Command> onGetProduct(GetProduct msg) {
        getContext().getLog().info("Received GetProduct request");
        msg.replyTo.tell(new ProductResponse(productId, description, price, stockQuantity));
        getContext().getLog().info("Replied with ProductResponse: ID: {}, Description: {}, Price: {}, Available Stock: {}", productId, description, price, stockQuantity);
        return this;
    }

    private Behavior<Command> onDeductStock(DeductStock msg) {
        getContext().getLog().info("Received DeductStock request: Quantity: {}", msg.quantity);
        if (msg.quantity <= stockQuantity) {
            stockQuantity -= msg.quantity;
            int deducted = msg.quantity * price;
            msg.replyTo.tell(new OperationResponse(true, "Stock deducted successfully.", deducted));
            getContext().getLog().info("Stock deducted successfully. Remaining Stock: {}, Price Deducted: {}", stockQuantity, deducted);
        } else {
            msg.replyTo.tell(new OperationResponse(false, "Insufficient stock.", 0));
            getContext().getLog().info("Insufficient stock. Requested: {}, Available: {}", msg.quantity, stockQuantity);
        }
        return this;
    }

    private Behavior<Command> onAddStock(AddStock msg) {
        getContext().getLog().info("Received AddStock request: Quantity: {}", msg.quantity);
        stockQuantity += msg.quantity;
        getContext().getLog().info("Stock added successfully. New Stock: {}", stockQuantity);
        return this;
    }
}
