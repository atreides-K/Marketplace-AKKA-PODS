package pods.akka.actors;

import akka.actor.typed.Behavior;
import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.Behaviors; // Correct import
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import pods.akka.CborSerializable;

public class ProductActor extends AbstractBehavior<ProductActor.Command> {

    // Define message protocol for ProductActor
    public interface Command extends CborSerializable {} // Commands are serializable

    public static final EntityTypeKey<Command> TypeKey =
        EntityTypeKey.create(Command.class, "ProductEntity"); // Use Command interface

    // Message to Initialize the product state with an added "name" field.
    public static final class InitializeProduct implements Command {
        public final int productId;
        public final String name;
        public final String description;
        public final int price;
        public final int stockQuantity;
        // Constructor accepts String ID as potentially coming from CSV/Sharding
        public InitializeProduct(String productId, String name, String description, int price, int stockQuantity) {
            // Consider adding try-catch for parsing if productId format isn't guaranteed
            this.productId = Integer.parseInt(productId);
            this.name = name;
            this.description = description;
            this.price = price;
            this.stockQuantity = stockQuantity;
        }
    }

    public static final class GetProduct implements Command {
        public final ActorRef<ProductResponse> replyTo; // Use ActorRef from Akka Typed
        public GetProduct(ActorRef<ProductResponse> replyTo) {
            this.replyTo = replyTo;
        }
    }

    public static final class DeductStock implements Command {
        public final int quantity;
        public final ActorRef<OperationResponse> replyTo;
        public DeductStock(int quantity, ActorRef<OperationResponse> replyTo) {
            this.quantity = quantity;
            this.replyTo = replyTo;
        }
    }

    public static final class AddStock implements Command {
        public final int quantity;
        public AddStock(int quantity) {
            this.quantity = quantity;
        }
    }

    public static final class CheckStock implements Command {
        public final int quantity;
        public final ActorRef<StockCheckResponse> replyTo;
        public CheckStock(int quantity, ActorRef<StockCheckResponse> replyTo) {
            this.quantity = quantity;
            this.replyTo = replyTo;
        }
    }

    // Reply messages
    // PHASE 2 CHANGE: Added CborSerializable for network transfer
    public static final class ProductResponse implements CborSerializable {
        public final int id;
        public final String name;
        public final String description;
        public final int price;
        public final int stock_quantity;
        // Constructor matching GetProduct reply
        public ProductResponse(int id, String name, String description, int price, int availableStock) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.price = price;
            this.stock_quantity = availableStock;
        }
        // Default constructor for Jackson (if needed, though often inferred)
        public ProductResponse() {
            this.id = 0; this.name = ""; this.description = ""; this.price = -1; this.stock_quantity = 0;
        }
    }

    // PHASE 2 CHANGE: Added CborSerializable for network transfer
    public static final class OperationResponse implements CborSerializable {
        public final boolean success;
        public final String message;
        public final int priceDeducted;  // computed as quantity * price if deduction succeeds
        public OperationResponse(boolean success, String message, int priceDeducted) {
            this.success = success;
            this.message = message;
            this.priceDeducted = priceDeducted;
        }
         // Default constructor for Jackson (if needed)
        public OperationResponse() {
            this.success = false; this.message = ""; this.priceDeducted = 0;
        }
    }

    // StockCheckResponse needs to be serializable as it's sent back in Ask
    public static final class StockCheckResponse implements CborSerializable {
        public final boolean sufficient;
        public StockCheckResponse(boolean sufficient) {
            this.sufficient = sufficient;
        }
         // Default constructor for Jackson (if needed)
        public StockCheckResponse() {
            this.sufficient = false;
        }
    }

    // Product state
    private int productId; // Initialized from entityId via constructor
    private String name = null; // Mark as uninitialized explicitly
    private String description = null;
    private int price = -1;
    private int stockQuantity = -1; // Use -1 to indicate not yet initialized
    private boolean initialized = false;

    public static Behavior<Command> create(String entityId) {
        // Pass entityId to constructor
        return Behaviors.setup(context -> new ProductActor(context, entityId));
    }

    private ProductActor(ActorContext<Command> context, String entityId) {
        super(context);
        try {
            // Initialize productId from the sharding entity ID
            this.productId = Integer.parseInt(entityId);
        } catch (NumberFormatException e) {
            getContext().getLog().error("Invalid Product ID format for entityId: {}", entityId);
            // Decide how to handle - stop, log, etc. Stopping might be safest.
            throw e; // Rethrow to signal failure during setup
        }
        getContext().getLog().info("ProductActor created for ID: {}", this.productId);
    }

    @Override
    public Receive<Command> createReceive() {
        // Initial behavior handles initialization first
        return uninitialized();
    }

    private Receive<Command> uninitialized() {
        return newReceiveBuilder()
            .onMessage(InitializeProduct.class, this::onInitializeProduct)
            .onMessage(GetProduct.class, msg -> {
                getContext().getLog().warn("ProductActor {} received GetProduct before initialization.", productId);
                // Reply indicating not found/initialized (use specific fields or a dedicated message)
                msg.replyTo.tell(new ProductResponse(productId, "Not Initialized", "", -1, -1));
                return Behaviors.same();
            })
            .onMessage(CheckStock.class, msg -> {
                 getContext().getLog().warn("ProductActor {} received CheckStock before initialization.", productId);
                 msg.replyTo.tell(new StockCheckResponse(false)); // Cannot satisfy stock check
                 return Behaviors.same();
            })
             .onMessage(DeductStock.class, msg -> {
                 getContext().getLog().warn("ProductActor {} received DeductStock before initialization.", productId);
                 msg.replyTo.tell(new OperationResponse(false, "Product not initialized.", 0));
                 return Behaviors.same();
            })
             .onMessage(AddStock.class, msg -> {
                 getContext().getLog().warn("ProductActor {} received AddStock before initialization. Ignoring.", productId);
                 // No reply needed for AddStock
                 return Behaviors.same();
            })
            .build();
    }

     private Receive<Command> initialized() {
        return newReceiveBuilder()
            .onMessage(InitializeProduct.class, msg -> {
                // Allow re-initialization? Or log warning? Current replaces state.
                getContext().getLog().warn("ProductActor {} received InitializeProduct again. Overwriting state.", productId);
                return onInitializeProduct(msg); // Re-run initialization logic
            })
            .onMessage(GetProduct.class, this::onGetProduct)
            .onMessage(CheckStock.class, this::onCheckStock)
            .onMessage(DeductStock.class, this::onDeductStock)
            .onMessage(AddStock.class, this::onAddStock)
            .build();
    }


    private Behavior<Command> onInitializeProduct(InitializeProduct msg) {
        // Check if the message ID matches the actor's ID (sanity check)
        if (msg.productId != this.productId) {
             getContext().getLog().error("InitializeProduct ID mismatch! Actor ID: {}, Message ID: {}. Ignoring.",
                this.productId, msg.productId);
             // Decide if this is an error or just needs logging
             return Behaviors.same(); // Stay in current state (initialized or not)
        }

        this.name = msg.name;
        this.description = msg.description;
        this.price = msg.price;
        this.stockQuantity = msg.stockQuantity;
        this.initialized = true; // Mark as initialized

        getContext().getLog().info("ProductActor {} Initialized: Name: {}, Price: {}, Stock: {}",
                productId, name, price, stockQuantity);

        // Transition to the initialized behavior
        return initialized();
    }

    // Methods below are only called when in the 'initialized' state

    private Behavior<Command> onGetProduct(GetProduct msg) {
        msg.replyTo.tell(new ProductResponse(productId, name, description, price, stockQuantity));
        return this;
    }

     private Behavior<Command> onCheckStock(CheckStock msg) {
        boolean isSufficient = msg.quantity > 0 && msg.quantity <= stockQuantity;
        msg.replyTo.tell(new StockCheckResponse(isSufficient));
        return this;
    }

    private Behavior<Command> onDeductStock(DeductStock msg) {
        if (msg.quantity <= 0) {
             msg.replyTo.tell(new OperationResponse(false, "Cannot deduct zero or negative quantity.", 0));
        } else if (msg.quantity <= stockQuantity) {
            stockQuantity -= msg.quantity;
            int deductionAmount = msg.quantity * price;
            msg.replyTo.tell(new OperationResponse(true, "Stock deducted successfully.", deductionAmount));
            getContext().getLog().info("Stock deducted for {}. Remaining: {}", productId, stockQuantity);
        } else {
            msg.replyTo.tell(new OperationResponse(false, "Insufficient stock. Available: " + stockQuantity, 0));
        }
        return this;
    }

    private Behavior<Command> onAddStock(AddStock msg) {
        if (msg.quantity > 0) {
            stockQuantity += msg.quantity;
            getContext().getLog().info("Stock added for {}. New Stock: {}", productId, stockQuantity);
        } else {
             getContext().getLog().warn("Attempted to add zero or negative stock ({}) for product {}. Ignoring.", msg.quantity, productId);
        }
        // AddStock doesn't have a replyTo
        return this;
    }
}