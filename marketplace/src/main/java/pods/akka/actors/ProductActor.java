package pods.akka.actors;

import akka.actor.typed.Behavior;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import akka.actor.typed.ActorRef;
import akka.actor.typed.PostStop; // Import PostStop
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
    // (Ensure all command classes with parameters have @JsonCreator/@JsonProperty)

    public static final class InitializeProduct implements Command {
        public final int productId;
        public final String name;
        public final String description;
        public final int price;
        public final int stockQuantity;

        // Assuming local creation, no annotations needed, but add if serialized
        public InitializeProduct(String productId, String name, String description, int price, int stockQuantity) {
            this.productId = Integer.parseInt(productId);
            this.name = name;
            this.description = description;
            this.price = price;
            this.stockQuantity = stockQuantity;
        }
    }

    public static final class GetProduct implements Command {
        public final ActorRef<ProductResponse> replyTo;
        @JsonCreator
        public GetProduct(@JsonProperty("replyTo") ActorRef<ProductResponse> replyTo) {
            this.replyTo = replyTo;
        }
    }

    public static final class DeductStock implements Command {
        public final int quantity;
        public final ActorRef<OperationResponse> replyTo;
        @JsonCreator
        public DeductStock(@JsonProperty("quantity") int quantity, @JsonProperty("replyTo") ActorRef<OperationResponse> replyTo) {
            this.quantity = quantity;
            this.replyTo = replyTo;
        }
    }

    public static final class AddStock implements Command {
        public final int quantity;
        @JsonCreator
        public AddStock(@JsonProperty("quantity") int quantity) {
            this.quantity = quantity;
        }
    }

    public static final class CheckStock implements Command {
        public final int quantity;
        public final ActorRef<StockCheckResponse> replyTo;
        @JsonCreator
        public CheckStock(@JsonProperty("quantity") int quantity, @JsonProperty("replyTo") ActorRef<StockCheckResponse> replyTo) {
            this.quantity = quantity;
            this.replyTo = replyTo;
        }
    }

    // --- Replies ---
    // (Ensure all reply classes have @JsonCreator/@JsonProperty or default constructor)

    public static final class ProductResponse implements CborSerializable {
        public final int id;
        public final String name;
        public final String description;
        public final int price;
        public final int stock_quantity;
        @JsonCreator
        public ProductResponse(@JsonProperty("id") int id, @JsonProperty("name") String name, @JsonProperty("description") String description, @JsonProperty("price") int price, @JsonProperty("stock_quantity") int availableStock) {
            this.id = id; this.name = name; this.description = description; this.price = price; this.stock_quantity = availableStock;
        }
        // Default constructor for flexibility if needed (though JsonCreator usually sufficient)
         public ProductResponse() { this(0, "Default", "Default", -1, -1); }
    }

    public static final class OperationResponse implements CborSerializable {
        public final boolean success;
        public final String message;
        public final int priceDeducted;
        @JsonCreator
        public OperationResponse(@JsonProperty("success") boolean success, @JsonProperty("message") String message, @JsonProperty("priceDeducted") int priceDeducted) {
            this.success = success; this.message = message; this.priceDeducted = priceDeducted;
        }
         public OperationResponse() { this(false, "Default", 0); }
    }

    public static final class StockCheckResponse implements CborSerializable {
        public final boolean sufficient;
        @JsonCreator
        public StockCheckResponse(@JsonProperty("sufficient") boolean sufficient) { this.sufficient = sufficient; }
         public StockCheckResponse() { this(false); }
    }

    // --- Actor State and Logic ---

    private final Logger log;
    private final int productId;
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
        this.log = context.getLog(); // Use SLF4J Logger
        try {
            this.productId = Integer.parseInt(entityId);
        } catch (NumberFormatException e) {
            log.error("[PID:{}] Invalid Product ID format for entityId: '{}'. Actor FAILED TO START.", entityId, entityId, e);
            // Stop immediately if ID is invalid
            throw new IllegalArgumentException("Invalid Product ID format: " + entityId, e);
        }
        log.info("[PID:{}] ProductActor CREATED at path: {} on Node: {}",
             this.productId, context.getSelf().path().toString(), context.getSystem().address());

        // Keep the original log too:
        log.info("[PID:{}] ProductActor Constructor ENDED. Initialized flag: {}", this.productId, this.initialized);
        
        log.info("[PID:{}] ProductActor CREATED on Node: {}", this.productId, context.getSystem().address());
    }

    // --- Behaviors ---

    @Override
    public Receive<Command> createReceive() {
        // Determine initial state based on the initialized flag
        // Although constructor sets initialized=false, check just in case createReceive is called later somehow
        log.info("[PID:{}] ***** createReceive() CALLED ***** Initialized flag before logic: {}", productId, this.initialized);
        return initialized();

        // shit man what is this spaghetti
        // thuii
        // if (!initialized) {
        //     return initialized();
        // } else {
        //     // This case should normally not be hit immediately after creation
        //     log.warn("[PID:{}] createReceive called but actor was already initialized? Entering initialized state.", productId);
        //     return initialized();
        // }
    }

    // private Receive<Command> uninitialized() {
    //     log.info("[PID:{}] Actor entering UNINITIALIZED state.", productId); // INFO level for state change
    //     log.info("[PID:{}] [HASH:{}] Handling GetProduct. Initialized Flag: {}", productId, this.hashCode(), this.initialized);
    //     return newReceiveBuilder()
    //         .onMessage(InitializeProduct.class, this::onInitializeProduct)
    //         // Log clearly when requests arrive in wrong state
    //         // .onMessage(GetProduct.class, msg -> {
    //         //     log.info("[PID:{}] [HASH:{}] Handling GetProduct. Initialized Flag: {}", productId, this.hashCode(), this.initialized);
    //         //     log.warn("[PID:{}]goofy Received GetProduct while UNINITIALIZED. Replying Not Initialized.", productId);
    //         //     msg.replyTo.tell(new ProductResponse(productId, "Not Initialized", "", -1, -1));
    //         //     return Behaviors.same();
    //         // })
    //         .onMessage(CheckStock.class, msg -> {
    //              log.warn("[PID:{}] Received CheckStock while UNINITIALIZED. Replying Not Sufficient.", productId);
    //              msg.replyTo.tell(new StockCheckResponse(false));
    //              return Behaviors.same();
    //         })
    //          .onMessage(DeductStock.class, msg -> {
    //              log.warn("[PID:{}] Received DeductStock while UNINITIALIZED. Replying Not Initialized.", productId);
    //              msg.replyTo.tell(new OperationResponse(false, "Product not initialized.", 0));
    //              return Behaviors.same();
    //         })
    //          .onMessage(AddStock.class, msg -> {
    //              log.warn("[PID:{}] Received AddStock while UNINITIALIZED. Ignoring.", productId);
    //              return Behaviors.same();
    //         })
    //         // Add PostStop logging
    //         .onSignal(PostStop.class, signal -> onPostStop())
    //         .build();
    // }

     private Receive<Command> initialized() {
        log.info("[PID:{}] [HASH:{}] Handling GetProduct. Initialized Flag: {}", productId, this.hashCode(), this.initialized); // INFO level for state change
        return newReceiveBuilder()
            .onMessage(InitializeProduct.class, msg -> {
                log.warn("[PID:{}] Received InitializeProduct again while already INITIALIZED. Overwriting state.", productId);
                // Re-run initialization logic safely
                return onInitializeProduct(msg);
            })
            .onMessage(GetProduct.class, this::onGetProduct)
            .onMessage(CheckStock.class, this::onCheckStock)
            .onMessage(DeductStock.class, this::onDeductStock)
            .onMessage(AddStock.class, this::onAddStock)
             // Add PostStop logging
            .onSignal(PostStop.class, signal -> onPostStop())
            .build();
    }

    // --- Message Handlers with try-catch ---

    private Behavior<Command> onInitializeProduct(InitializeProduct msg) {
        log.debug("[PID:{}] Handling InitializeProduct message", productId);
        try {
            if (msg.productId != this.productId) {
                 log.error("[PID:{}] InitializeProduct ID mismatch! Actor ID: {}, Message ID: {}. Ignoring.",
                    this.productId, msg.productId);
                 return Behaviors.same(); // Stay in current state (likely uninitialized)
            }

            this.name = msg.name;
            this.description = msg.description;
            this.price = msg.price;
            this.stockQuantity = msg.stockQuantity;
            boolean becomingInitialized = !this.initialized; // Track if this is the first init
            this.initialized = true;

            log.info("[PID:{}] INITIALIZED/RE-INITIALIZED. Name='{}', Price={}, Stock={}",
                     productId, name, price, stockQuantity);

            // Only transition if it wasn't already initialized
            if (becomingInitialized) {
                 return initialized();
            } else {
                 return this; // Stay in initialized state if re-initialized
            }
        } catch (Exception e) {
             log.error("[PID:{}] !!! Exception in onInitializeProduct !!! Stopping actor.", productId, e);
             log.debug("[PID:{}] onInitializeProduct returning Behaviors.stopped(). Initialized Flag WAS: {}", productId, this.initialized);
             return Behaviors.stopped();
        }
    }

    private Behavior<Command> onGetProduct(GetProduct msg) {
        try {
            log.info("[PID:{}] [HASH:{}] Handlingz GetProduct. Initialized Flag: {}", productId, this.hashCode(), this.initialized);
            log.info("[PID:{}] Handling GetProductz. Current Initialized Flag: {}", productId, this.initialized);
            if (!initialized) {
                log.warn("[PID:{}] Replying Not Initialized to GetProduct.", productId);
                msg.replyTo.tell(new ProductResponse(productId, "Not Initialized", "", -1, -1));
            } else {
                log.debug("[PID:{}] Replying with current state to GetProduct.", productId);
                msg.replyTo.tell(new ProductResponse(productId, name, description, price, stockQuantity));
            }
            // **** ADD LOG BEFORE RETURN ****
            log.debug("[PID:{}] onGetProduct returning 'this'. Initialized Flag NOW: {}", productId, this.initialized); 
            return this; 
        } catch (Exception e) {
            log.error("[PID:{}] !!! Exception in onGetProduct !!! Stopping actor.", productId, e);
             // **** ADD LOG BEFORE RETURN ****
             log.debug("[PID:{}] onGetProduct returning Behaviors.stopped(). Initialized Flag WAS: {}", productId, this.initialized);
            return Behaviors.stopped();
        }
    }

    private Behavior<Command> onCheckStock(CheckStock msg) {
        log.debug("[PID:{}] Handling CheckStock ({}). Initialized: {}", productId, msg.quantity, this.initialized);
        try {
             if (!initialized) {
                  log.warn("[PID:{}] Replying Not Sufficient to CheckStock (not initialized).", productId);
                 msg.replyTo.tell(new StockCheckResponse(false));
                 return Behaviors.same(); // Stay uninitialized
             }
            boolean isSufficient = msg.quantity > 0 && msg.quantity <= stockQuantity;
            log.debug("[PID:{}] CheckStock result: {}", productId, isSufficient);
            msg.replyTo.tell(new StockCheckResponse(isSufficient));
            log.info("[PID:{}] [HASH:{}] onCheckStock returning this", productId, this.hashCode());
            return this;
        } catch (Exception e) {
             log.error("[PID:{}] !!! Exception in onCheckStock !!! Stopping actor.", productId, e);
             // Don't reply on error, let Ask timeout
             return Behaviors.stopped();
        }
    }

    private Behavior<Command> onDeductStock(DeductStock msg) {
         log.debug("[PID:{}] Handling DeductStock ({}). Initialized: {}", productId, msg.quantity, this.initialized);
         try {
             if (!initialized) {
                  log.warn("[PID:{}] Replying Not Initialized to DeductStock.", productId);
                 msg.replyTo.tell(new OperationResponse(false, "Product not initialized.", 0));
                 return Behaviors.same(); // Stay uninitialized
             }
            if (msg.quantity <= 0) {
                 log.warn("[PID:{}] Attempted to deduct invalid quantity: {}", productId, msg.quantity);
                 msg.replyTo.tell(new OperationResponse(false, "Cannot deduct zero or negative quantity.", 0));
            } else if (msg.quantity <= stockQuantity) {
                stockQuantity -= msg.quantity;
                int deductionAmount = msg.quantity * price;
                log.info("[PID:{}] Stock deducted: {}. Remaining: {}. Price Deducted: {}", productId, msg.quantity, stockQuantity, deductionAmount);
                msg.replyTo.tell(new OperationResponse(true, "Stock deducted successfully.", deductionAmount));
            } else {
                 log.warn("[PID:{}] Insufficient stock for deduction. Requested: {}, Available: {}", productId, msg.quantity, stockQuantity);
                msg.replyTo.tell(new OperationResponse(false, "Insufficient stock. Available: " + stockQuantity, 0));
            }
            log.info("[PID:{}] [HASH:{}] onCheckStock returning this", productId, this.hashCode());
            return this;
        } catch (Exception e) {
             log.error("[PID:{}] !!! Exception in onDeductStock !!! Stopping actor.", productId, e);
             // Don't reply on error, let Ask timeout
             return Behaviors.stopped();
        }
    }

    private Behavior<Command> onAddStock(AddStock msg) {
        log.debug("[PID:{}] Handling AddStock ({}). Initialized: {}", productId, msg.quantity, this.initialized);
         try {
             if (!initialized) {
                  log.warn("[PID:{}] Received AddStock before initialization. Ignoring.", productId);
                  return Behaviors.same(); // Stay uninitialized
             }
            if (msg.quantity > 0) {
                stockQuantity += msg.quantity;
                log.info("[PID:{}] Stock added: {}. New Stock: {}", productId, msg.quantity, stockQuantity);
            } else {
                 log.warn("[PID:{}] Attempted to add zero or negative stock ({}) . Ignoring.", productId, msg.quantity);
            }
            // AddStock doesn't have a replyTo
            log.info("[PID:{}] [HASH:{}] onCheckStock returning this", productId, this.hashCode());
            return this;
         } catch (Exception e) {
              log.error("[PID:{}] !!! Exception in onAddStock !!! Stopping actor.", productId, e);
              return Behaviors.stopped();
         }
    }

    // --- Lifecycle Hook ---

    private Behavior<Command> onPostStop() {
        log.info("[PID:{}] ProductActor stopped.", productId);
        log.warn("[PID:{}] ***** ProductActor STOPPED ***** Path: {}", productId, getContext().getSelf().path().toString());
        return Behaviors.same(); // No further behavior changes on stop
    }

} // End of ProductActor class