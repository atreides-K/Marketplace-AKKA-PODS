package pods.akka.actors;

import akka.actor.typed.Behavior;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty; // <-- Import JsonProperty
import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import pods.akka.CborSerializable;
import org.slf4j.Logger; // Using SLF4J Logger

public class ProductActor extends AbstractBehavior<ProductActor.Command> {

    // Define message protocol
    public interface Command extends CborSerializable {}

    public static final EntityTypeKey<Command> TypeKey =
        EntityTypeKey.create(Command.class, "ProductEntity");

    // --- Commands ---

    // InitializeProduct: Sent locally during startup usually, but good practice
    public static final class InitializeProduct implements Command {
        public final int productId;
        public final String name;
        public final String description;
        public final int price;
        public final int stockQuantity;

        // Note: No JsonCreator needed IF this message is guaranteed to only be created locally
        // and sent via tell(). If it could potentially be serialized (e.g., for testing),
        // adding annotations would be required. For now, assuming local creation.
        public InitializeProduct(String productId, String name, String description, int price, int stockQuantity) {
            this.productId = Integer.parseInt(productId); // Consider info handling
            this.name = name;
            this.description = description;
            this.price = price;
            this.stockQuantity = stockQuantity;
        }
    }

    // GetProduct: Used in AskPattern, definitely needs annotations
    public static final class GetProduct implements Command {
        public final ActorRef<ProductResponse> replyTo;

        @JsonCreator // Annotation for Jackson
        public GetProduct(
                @JsonProperty("replyTo") ActorRef<ProductResponse> replyTo // Annotation for Jackson parameter
        ) {
            this.replyTo = replyTo;
        }
    }

    // DeductStock: Used in AskPattern
    public static final class DeductStock implements Command {
        public final int quantity;
        public final ActorRef<OperationResponse> replyTo;

        @JsonCreator
        public DeductStock(
                @JsonProperty("quantity") int quantity,
                @JsonProperty("replyTo") ActorRef<OperationResponse> replyTo
        ) {
            this.quantity = quantity;
            this.replyTo = replyTo;
        }
    }

    // AddStock: Typically sent via tell, might not strictly need annotations
    // Adding them for safety/consistency if serialization possibility exists
    public static final class AddStock implements Command {
        public final int quantity;

        @JsonCreator
        public AddStock(@JsonProperty("quantity") int quantity) {
            this.quantity = quantity;
        }
    }

    // CheckStock: Used in AskPattern
    public static final class CheckStock implements Command {
        public final int quantity;
        public final ActorRef<StockCheckResponse> replyTo;

        @JsonCreator
        public CheckStock(
                @JsonProperty("quantity") int quantity,
                @JsonProperty("replyTo") ActorRef<StockCheckResponse> replyTo
        ) {
            this.quantity = quantity;
            this.replyTo = replyTo;
        }
    }

    // --- Replies ---

    // ProductResponse: Sent as reply in AskPattern
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
                @JsonProperty("stock_quantity") int availableStock
        ) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.price = price;
            this.stock_quantity = availableStock;
        }
        // Default constructor not needed when using @JsonCreator on the parameterized one
        // public ProductResponse() { ... }
    }

    // OperationResponse: Sent as reply in AskPattern
    public static final class OperationResponse implements CborSerializable {
        public final boolean success;
        public final String message;
        public final int priceDeducted;

        @JsonCreator
        public OperationResponse(
                @JsonProperty("success") boolean success,
                @JsonProperty("message") String message,
                @JsonProperty("priceDeducted") int priceDeducted
        ) {
            this.success = success;
            this.message = message;
            this.priceDeducted = priceDeducted;
        }
         // Default constructor not needed when using @JsonCreator
        // public OperationResponse() { ... }
    }

    // StockCheckResponse: Sent as reply in AskPattern
    public static final class StockCheckResponse implements CborSerializable {
        public final boolean sufficient;

        @JsonCreator
        public StockCheckResponse(@JsonProperty("sufficient") boolean sufficient) {
            this.sufficient = sufficient;
        }
         // Default constructor not needed when using @JsonCreator
        // public StockCheckResponse() { ... }
    }

    // --- Actor State and Logic ---

    private final Logger log; // Use SLF4J logger
    private final int productId; // Final after constructor
    private String name = null;
    private String description = null;
    private int price = -1;
    private int stockQuantity = -1;
    private boolean initialized = false;

    public static Behavior<Command> create(String entityId) {
        return Behaviors.setup(context -> new ProductActor(context, entityId));
    }

    private ProductActor(ActorContext<Command> context, String entityId) {
        super(context);
        this.log = context.getLog(); // Get logger from context
        try {
            this.productId = Integer.parseInt(entityId);
        } catch (NumberFormatException e) {
            log.info("Invalid Product ID format for entityId: {}. Stopping actor.", entityId, e);
            throw new IllegalArgumentException("Invalid Product ID format: " + entityId, e);
        }
        log.info("ProductActor created for ID: {}", this.productId);
    }

    @Override
    public Receive<Command> createReceive() {
        
        // Start in uninitialized state
        if (!initialized) {
            return uninitialized();
        } else {
            return initialized();
        }
    }

    private Receive<Command> uninitialized() {
        log.info("Actor {} entering uninitialized state", productId); // Add log
        return newReceiveBuilder()
            .onMessage(InitializeProduct.class, this::onInitializeProduct)
            .onMessage(GetProduct.class, msg -> {
                // log.warn("ProductActor {} received GetProduct before initialization.", productId); // LOG REMOVED
                msg.replyTo.tell(new ProductResponse(productId, "Not Initialized", "", -1, -1));
                return Behaviors.same();
            })
            .onMessage(CheckStock.class, msg -> {
                 // log.warn("ProductActor {} received CheckStock before initialization.", productId); // LOG REMOVED
                 msg.replyTo.tell(new StockCheckResponse(false));
                 return Behaviors.same();
            })
             .onMessage(DeductStock.class, msg -> {
                 // log.warn("ProductActor {} received DeductStock before initialization.", productId); // LOG REMOVED
                 msg.replyTo.tell(new OperationResponse(false, "Product not initialized.", 0));
                 return Behaviors.same();
            })
             .onMessage(AddStock.class, msg -> {
                 // log.warn("ProductActor {} received AddStock before initialization. Ignoring.", productId); // LOG REMOVED
                 return Behaviors.same();
            })
            .build();
    }

     private Receive<Command> initialized() {
        log.info("Actor {} entering initialized state", productId); // Add log
        return newReceiveBuilder()
            .onMessage(InitializeProduct.class, msg -> {
                // log.warn("ProductActor {} received InitializeProduct again. Overwriting state.", productId); // LOG REMOVED
                // Re-run initialization logic and stay in initialized state
                return onInitializeProduct(msg); 
            })
            .onMessage(GetProduct.class, this::onGetProduct)
            .onMessage(CheckStock.class, this::onCheckStock)
            .onMessage(DeductStock.class, this::onDeductStock)
            .onMessage(AddStock.class, this::onAddStock)
            .build();
    }


    private Behavior<Command> onInitializeProduct(InitializeProduct msg) {
        if (msg.productId != this.productId) {
            // log.info("InitializeProduct ID mismatch! Actor ID: {}, Message ID: {}. Ignoring.", // LOG REMOVED
            //    this.productId, msg.productId);
             return Behaviors.same(); 
        }

        this.name = msg.name;
        this.description = msg.description;
        this.price = msg.price;
        this.stockQuantity = msg.stockQuantity;
        this.initialized = true; 

        // log.info("ProductActor {} Initialized: Name: {}, Price: {}, Stock: {}", // LOG REMOVED
        //        productId, name, price, stockQuantity);

        // Transition to the initialized behavior
        log.info("ProductActor {} Initialized [...]", productId); // Keep this
        return initialized();
    }

    private Behavior<Command> onGetProduct(GetProduct msg) {
        try {
            log.info("Actor {} handling GetProduct. Is Initialized: {}", productId, this.initialized); 
            if (!initialized) { // Double-check state just in case
                log.warn("Actor {} handling GetProduct but state is !initialized. Replying Not Initialized.", productId);
                msg.replyTo.tell(new ProductResponse(productId, "Not Initialized", "", -1, -1));
            } else {
                // This is the normal path
                log.info("Actor {} GetProduct responding with current state.", productId);
                msg.replyTo.tell(new ProductResponse(productId, name, description, price, stockQuantity));
            }
            // Stay in the current behavior (initialized)
            return this; 
        } catch (Exception e) {
            // Log unexpected errors during GetProduct handling
            log.error("!!! Unhandled exception in onGetProduct for Product {} !!! Stopping actor.", productId, e);
            // We probably shouldn't reply if an error occurred, let the Ask timeout on the client side.
            // Stopping the actor is often the safest approach on unexpected errors.
            return Behaviors.stopped();
        }
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
            // log.info("Stock deducted for {}. Remaining: {}", productId, stockQuantity); // LOG REMOVED
        } else {
            msg.replyTo.tell(new OperationResponse(false, "Insufficient stock. Available: " + stockQuantity, 0));
        }
        return this;
    }

    private Behavior<Command> onAddStock(AddStock msg) {
        if (msg.quantity > 0) {
            stockQuantity += msg.quantity;
            // log.info("Stock added for {}. New Stock: {}", productId, stockQuantity); // LOG REMOVED
        } else {
             // log.warn("Attempted to add zero or negative stock ({}) for product {}. Ignoring.", msg.quantity, productId); // LOG REMOVED
        }
        return this;
    }
}