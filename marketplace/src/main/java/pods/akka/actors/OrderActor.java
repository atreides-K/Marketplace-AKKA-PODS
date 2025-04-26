package pods.akka.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import pods.akka.CborSerializable;

import java.util.List;
import java.util.ArrayList;

// Import SLF4J Logger
import org.slf4j.Logger;

public class OrderActor extends AbstractBehavior<OrderActor.Command> {

    // Interface and Message classes
    public interface Command extends CborSerializable {}

    // Use String type for OrderEntity keys
    public static final EntityTypeKey<Command> TypeKey =
        EntityTypeKey.create(Command.class, "OrderEntity");

    // --- Message: InitializeOrder ---
    public static final class InitializeOrder implements Command {
        // CHANGED: orderId is now String
        public final String orderId;
        public final int userId;
        public final List<OrderItem> items;
        public final int totalPrice;
        public final String initialStatus;
        public final ActorRef<OperationResponse> replyTo;

        @JsonCreator
        public InitializeOrder(
            // CHANGED: @JsonProperty type to String
            @JsonProperty("orderId") String orderId,
            @JsonProperty("userId") int userId,
            @JsonProperty("items") List<OrderItem> items,
            @JsonProperty("totalPrice") int totalPrice,
            @JsonProperty("initialStatus") String initialStatus,
            @JsonProperty("replyTo") ActorRef<OperationResponse> replyTo) {
            this.orderId = orderId; // Assign String
            this.userId = userId;
            this.items = items == null ? new ArrayList<>() : items;
            this.totalPrice = totalPrice;
            this.initialStatus = initialStatus;
            this.replyTo = replyTo;
        }
    }

    // --- Message: GetOrder ---
    public static final class GetOrder implements Command {
        public final ActorRef<OrderResponse> replyTo;
         @JsonCreator
        public GetOrder(@JsonProperty("replyTo") ActorRef<OrderResponse> replyTo) {
            this.replyTo = replyTo;
        }
    }

    // --- Message: UpdateStatus ---
    public static final class UpdateStatus implements Command {
        public final String newStatus;
        public final ActorRef<OperationResponse> replyTo;
         @JsonCreator
        public UpdateStatus(
            @JsonProperty("newStatus") String newStatus,
            @JsonProperty("replyTo") ActorRef<OperationResponse> replyTo) {
            this.newStatus = newStatus;
            this.replyTo = replyTo;
        }
    }

    // --- Reply: OrderResponse ---
    public static final class OrderResponse implements CborSerializable {
        // CHANGED: order_id is now String
        public final String order_id;
        public final int user_id;
        public final List<OrderItem> items;
        public final int total_price;
        public final String status;

        @JsonCreator
        public OrderResponse(
            // CHANGED: @JsonProperty type to String
            @JsonProperty("order_id") String orderId,
            @JsonProperty("user_id") int userId,
            @JsonProperty("items") List<OrderItem> items,
            @JsonProperty("total_price") int totalPrice,
            @JsonProperty("status") String status) {
            this.order_id = orderId; // Assign String
            this.user_id = userId;
            this.items = items == null ? new ArrayList<>() : items;
            this.total_price = totalPrice;
            this.status = status;
        }
        // CHANGED: Default constructor uses String ID
         public OrderResponse() { this("UNKNOWN", 0, new ArrayList<>(), 0, "Unknown"); }
    }

    // --- Reply: OperationResponse ---
    public static final class OperationResponse implements CborSerializable {
        public final boolean success;
        // Kept as String (consistent with entityId)
        public final String order_id;
        public final String status;
        public final String message;

        @JsonCreator
        public OperationResponse(
            @JsonProperty("success") boolean success,
            @JsonProperty("order_id") String order_id,
            @JsonProperty("status") String status,
            @JsonProperty("message") String message) {
            this.success = success;
            this.order_id = order_id;
            this.status = status;
            this.message = message;
        }
         public OperationResponse() { this(false, "UNKNOWN", "Unknown", "Default constructor"); }
    }

    // --- Nested Class: OrderItem ---
    public static final class OrderItem implements CborSerializable {
        public final int product_id;
        public final int quantity;

        @JsonCreator
        public OrderItem(
            @JsonProperty("product_id") int product_id,
            @JsonProperty("quantity") int quantity) {
            this.product_id = product_id;
            this.quantity = quantity;
        }
         public OrderItem() { this(0, 0); }
    }

    // --- State ---
    private final String entityId; // Store the String ID provided by sharding
    // REMOVED: private int orderIdInt;
    // ADDED: Store the String ID from InitializeOrder message
    private String orderId;
    private int userId;
    private List<OrderItem> items;
    private int totalPrice;
    private String status;
    private boolean initialized;

    // --- Logger ---
    private final Logger log; // SLF4J Logger

    // --- Factory and Constructor ---
    public static Behavior<Command> create(String entityId) {
        // entityId provided by sharding IS the String Order ID (UUID)
        return Behaviors.setup(context -> new OrderActor(context, entityId));
    }

    private OrderActor(ActorContext<Command> context, String entityId) {
        super(context);
        this.entityId = entityId; // Store the String ID from sharding
        this.log = context.getLog();
        this.initialized = false;
        this.status = "PendingInitialization";
        log.info("OrderActor created for entityId: '{}'", entityId);
    }

    // --- Receive Logic ---
    @Override
    public Receive<Command> createReceive() {
        // Start in uninitialized state
        return uninitialized();
    }

     private Receive<Command> uninitialized() {
        return newReceiveBuilder()
            .onMessage(InitializeOrder.class, this::onInitializeOrder)
            .onMessage(GetOrder.class, msg -> {
                log.warn("OrderActor '{}' received GetOrder before initialization.", entityId);
                // Use entityId (String) for default response ID
                msg.replyTo.tell(new OrderResponse(entityId, 0, new ArrayList<>(), 0, "NotInitialized"));
                return Behaviors.same();
            })
            .onMessage(UpdateStatus.class, msg -> {
                log.warn("OrderActor '{}' received UpdateStatus before initialization.", entityId);
                msg.replyTo.tell(new OperationResponse(false, entityId, this.status, "Order not initialized"));
                return Behaviors.same();
            })
            .build();
     }

    private Receive<Command> initialized() {
         return newReceiveBuilder()
            .onMessage(InitializeOrder.class, msg -> {
                 // Check if the message orderId matches the actor's entityId
                 if (!msg.orderId.equals(this.entityId)) {
                    log.error("OrderActor '{}' received InitializeOrder again but with mismatched ID: '{}'. Ignoring.", entityId, msg.orderId);
                    msg.replyTo.tell(new OperationResponse(false, msg.orderId, this.status, "Order already initialized with different ID"));
                 } else {
                    log.warn("OrderActor '{}' received InitializeOrder again with matching ID. Ignoring.", entityId);
                    msg.replyTo.tell(new OperationResponse(false, msg.orderId, this.status, "Order already initialized"));
                 }
                 return Behaviors.same();
            })
            .onMessage(GetOrder.class, this::onGetOrder)
            .onMessage(UpdateStatus.class, this::onUpdateStatus)
            .build();
    }

    private Behavior<Command> onInitializeOrder(InitializeOrder msg) {
         // Sharding guarantees the message is for this entityId.
         // We store the ID from the message, which should match entityId.
         this.orderId = msg.orderId; // Store the String ID from the message
         this.userId = msg.userId;
         this.items = msg.items == null ? new ArrayList<>() : msg.items;
         this.totalPrice = msg.totalPrice;
         this.status = msg.initialStatus == null ? "UNKNOWN" : msg.initialStatus;
         this.initialized = true;

        // Log successful initialization using the String ID
        log.info("OrderActor initialized: entityId='{}', orderId='{}', userId={}, itemsCount={}, totalPrice={}, status={}",
                entityId, orderId, userId, items.size(), totalPrice, status);

        // Reply success using the String orderId from the message (which should match entityId)
        msg.replyTo.tell(new OperationResponse(true, orderId, status, "Order initialized successfully"));

        // Transition to initialized behavior
        return initialized();
    }

    // --- Methods below are called when in 'initialized' state ---

    private Behavior<Command> onGetOrder(GetOrder msg) {
        log.info("OrderActor '{}' received GetOrder request.", entityId);
        // Use the stored String orderId in the response
        msg.replyTo.tell(new OrderResponse(orderId, userId, items, totalPrice, status));
        return  Behaviors.same(); // Stay initialized
    }

    private Behavior<Command> onUpdateStatus(UpdateStatus msg) {
        if (!initialized) { // Safety check, though should only be called in initialized state
             log.error("OrderActor '{}' received UpdateStatus but is not initialized!", entityId);
             msg.replyTo.tell(new OperationResponse(false, entityId, this.status, "Internal error: Actor not initialized"));
             return Behaviors.same();
        }

        // Prevent updates on terminal states
        if ("DELIVERED".equalsIgnoreCase(this.status) || "CANCELLED".equalsIgnoreCase(this.status)) {
            log.warn("OrderActor '{}' received UpdateStatus ('{}') but order is already in terminal state ('{}'). Ignoring.",
                    entityId, msg.newStatus, this.status);
            msg.replyTo.tell(new OperationResponse(false, entityId, this.status, "Order is already in a terminal state (" + this.status + ")"));
        } else if (msg.newStatus == null || msg.newStatus.trim().isEmpty()){
             log.warn("OrderActor '{}' received UpdateStatus with null or empty newStatus. Ignoring.", entityId);
             msg.replyTo.tell(new OperationResponse(false, entityId, this.status, "Invalid new status provided"));
        }
        else {
            String oldStatus = this.status;
            this.status = msg.newStatus.toUpperCase(); // Store status consistently (e.g., uppercase)
            log.info("OrderActor '{}' status updated from '{}' to '{}'", entityId, oldStatus, this.status);
            // Use entityId (which is the String orderId) in response
            msg.replyTo.tell(new OperationResponse(true, entityId, this.status, "Order status updated to " + this.status));
        }
        return Behaviors.same(); // Stay initialized
    }
}