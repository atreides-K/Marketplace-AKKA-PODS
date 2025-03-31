package pods.akka.actors;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import pods.akka.CborSerializable;

public class ProductActor extends AbstractBehavior<ProductActor.Command> {

    // Define message protocol for ProductActor
    public interface Command extends CborSerializable {}

    public static final EntityTypeKey<Command> TypeKey =
        EntityTypeKey.create(ProductActor.Command.class, "ProductEntity");

    // Message to InitializeProduct the product state with an added "name" field.
    public static final class InitializeProduct implements Command {
        public final String productId;
        public final String name;
        public final String description;
        public final int price;
        public final int stockQuantity;
        public InitializeProduct(String productId, String name, String description, int price, int stockQuantity) {
            this.productId = productId;
            this.name = name;
            this.description = description;
            this.price = price;
            this.stockQuantity = stockQuantity;
        }
    }

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
        public AddStock(int quantity) {
            this.quantity = quantity;
        }
    }

    // Reply messages
    public static final class ProductResponse {
        public final String id;
        public final String name;
        public final String description;
        public final int price;
        public final int stock_quantity;
        public ProductResponse(String id, String name, String description, int price, int availableStock) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.price = price;
            this.stock_quantity = availableStock;
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
    private String productId = "unknown";
    private String name = "unknown";
    private String description = "unknown";
    private int price = -1;
    private int stockQuantity = 0;

    // Factory method to create a ProductActor.
    // Note: The initial values here are dummy; they will be overwritten by InitializeProduct.
    public static Behavior<Command> create(String productId) {
        return akka.actor.typed.javadsl.Behaviors.setup(context ->
                new ProductActor(context, productId));
    }

    private ProductActor(ActorContext<Command> context, String productId) {
        super(context);
        this.productId = productId;
        getContext().getLog().info("ProductActor created for ID: {}", productId);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(InitializeProduct.class, this::onInitializeProduct)
                .onMessage(GetProduct.class, this::onGetProduct)
                .onMessage(DeductStock.class, this::onDeductStock)
                .onMessage(AddStock.class, this::onAddStock)
                .build();
    }

    private Behavior<Command> onInitializeProduct(InitializeProduct msg) {
        this.productId = msg.productId;
        this.name = msg.name;
        this.description = msg.description;
        this.price = msg.price;
        this.stockQuantity = msg.stockQuantity;
        getContext().getLog().info("ProductActor {} Initialized: Name: {}, Description: {}, Price: {}, Stock: {}", 
                productId, name, description, price, stockQuantity);
        return this;
    }

    private Behavior<Command> onGetProduct(GetProduct msg) {
        getContext().getLog().info("Received GetProduct request for {}", productId);
        msg.replyTo.tell(new ProductResponse(productId, name, description, price, stockQuantity));
        getContext().getLog().info("Replied with ProductResponse: ID: {}, Name: {}, Description: {}, Price: {}, Available Stock: {}", 
                productId, name, description, price, stockQuantity);
        return this;
    }

    private Behavior<Command> onDeductStock(DeductStock msg) {
        getContext().getLog().info("Received DeductStock request for {}: Quantity: {}", productId, msg.quantity);
        if (msg.quantity <= stockQuantity) {
            stockQuantity -= msg.quantity;
            int deducted = msg.quantity * price;
            msg.replyTo.tell(new OperationResponse(true, "Stock deducted successfully.", deducted));
            getContext().getLog().info("Stock deducted successfully for {}. Remaining Stock: {}, Price Deducted: {}", 
                    productId, stockQuantity, deducted);
        } else {
            msg.replyTo.tell(new OperationResponse(false, "Insufficient stock.", 0));
            getContext().getLog().info("Insufficient stock for {}. Requested: {}, Available: {}", productId, msg.quantity, stockQuantity);
        }
        return this;
    }

    private Behavior<Command> onAddStock(AddStock msg) {
        getContext().getLog().info("Received AddStock request for {}: Quantity: {}", productId, msg.quantity);
        stockQuantity += msg.quantity;
        getContext().getLog().info("Stock added for {}. New Stock: {}", productId, stockQuantity);
        return this;
    }
}
