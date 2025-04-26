package pods.akka.actors;

import akka.actor.typed.Behavior;
import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import pods.akka.CborSerializable;

// Import Jackson annotations
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ProductActor extends AbstractBehavior<ProductActor.Command> {

    // Define message protocol for ProductActor
    public interface Command extends CborSerializable {}

    public static final EntityTypeKey<Command> TypeKey =
        EntityTypeKey.create(ProductActor.Command.class, "ProductEntity");

    // Message to InitializeProduct the product state with an added "name" field.
    public static final class InitializeProduct implements Command {
        public final int productId;
        public final String name;
        public final String description;
        public final int price;
        public final int stockQuantity;
        @JsonCreator
        public InitializeProduct(
            @JsonProperty("productId") String productId, // Keep as String if sent that way initially
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("price") int price,
            @JsonProperty("stockQuantity") int stockQuantity) {
            this.productId = Integer.valueOf(productId);
            this.name = name;
            this.description = description;
            this.price = price;
            this.stockQuantity = stockQuantity;
        }
    }

    public static final class GetProduct implements Command {
        public final akka.actor.typed.ActorRef<ProductResponse> replyTo;
        @JsonCreator
        public GetProduct(@JsonProperty("replyTo")akka.actor.typed.ActorRef<ProductResponse> replyTo) {
            this.replyTo = replyTo;
        }
    }

    public static final class DeductStock implements Command {
        public final int quantity;
        public final akka.actor.typed.ActorRef<OperationResponse> replyTo;
        @JsonCreator
        public DeductStock(@JsonProperty("quantity")int quantity, @JsonProperty("replyTo")akka.actor.typed.ActorRef<OperationResponse> replyTo) {
            this.quantity = quantity;
            this.replyTo = replyTo;
        }
    }

    // Command to add stock (for rollback)
    public static final class AddStock implements Command {
        public final int quantity;
        @JsonCreator
        public AddStock(@JsonProperty("quantity")int quantity) {
            this.quantity = quantity;
        }
    }

    // Reply messages
    public static final class ProductResponse implements CborSerializable {
        public final int id;
        public final String name;
        public final String description;
        public final int price;
        public final int stock_quantity;
        @JsonCreator
        public ProductResponse(
                @JsonProperty("id") int id,
                @JsonProperty("name") String name,
                @JsonProperty("description") String description,
                @JsonProperty("price") int price,
                @JsonProperty("stock_quantity") int availableStock) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.price = price;
            this.stock_quantity = availableStock;
        }
    }

    public static final class OperationResponse implements CborSerializable {
        public final boolean success;
        public final String message;
        public final int priceDeducted;  // computed as quantity * price if deduction succeeds
        @JsonCreator
        public OperationResponse(
                @JsonProperty("success") boolean success,
                @JsonProperty("message") String message,
                @JsonProperty("priceDeducted") int priceDeducted) {
            this.success = success;
            this.message = message;
            this.priceDeducted = priceDeducted;
        }
    }

    public static final class CheckStock implements Command {
        public final int quantity;
        public final ActorRef<StockCheckResponse> replyTo;
        @JsonCreator
        public CheckStock(@JsonProperty("quantity")int quantity, @JsonProperty("replyTo")ActorRef<StockCheckResponse> replyTo) {
            this.quantity = quantity;
            this.replyTo = replyTo;
        }
      }
      
      public static final class StockCheckResponse implements CborSerializable {
        public final boolean sufficient;
        @JsonCreator
        public StockCheckResponse(@JsonProperty("sufficient") boolean sufficient) {
            this.sufficient = sufficient;
        }
      }      

    // Product state
    private int productId = 0;
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
        this.productId = Integer.valueOf(productId);
        getContext().getLog().info("ProductActor created for ID: {}", productId);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(InitializeProduct.class, this::onInitializeProduct)
                .onMessage(GetProduct.class, this::onGetProduct)
                .onMessage(DeductStock.class, this::onDeductStock)
                .onMessage(AddStock.class, this::onAddStock)
                .onMessage(CheckStock.class, this::onCheckStock)
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

    private Behavior<Command> onCheckStock(CheckStock msg) {
        // Do not change any stock here; just check.
        boolean isSufficient = msg.quantity <= stockQuantity;
        msg.replyTo.tell(new StockCheckResponse(isSufficient));
        getContext().getLog().info("Checked stock for {}: requested {}, available {} => {}",
            productId, msg.quantity, stockQuantity, isSufficient);
        return this;
    }
}
