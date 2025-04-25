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

    // Interface and Message classes (ensure they implement CborSerializable)
    public interface Command extends CborSerializable {}

    public static final EntityTypeKey<Command> TypeKey =
        EntityTypeKey.create(Command.class, "OrderEntity");

    public static final class InitializeOrder implements Command {
        public final int orderId;
        public final int userId;
        public final List<OrderItem> items;
        public final int totalPrice;
        public final String initialStatus;
        public final ActorRef<OperationResponse> replyTo;

        @JsonCreator
        public InitializeOrder(
            @JsonProperty("orderId") int orderId,
            @JsonProperty("userId") int userId,
            @JsonProperty("items") List<OrderItem> items,
            @JsonProperty("totalPrice") int totalPrice,
            @JsonProperty("initialStatus") String initialStatus,
            @JsonProperty("replyTo") ActorRef<OperationResponse> replyTo) {
            this.orderId = orderId;
            this.userId = userId;
            this.items = items == null ? new ArrayList<>() : items;
            this.totalPrice = totalPrice;
            this.initialStatus = initialStatus;
            this.replyTo = replyTo;
        }
    }

    public static final class GetOrder implements Command {
        public final ActorRef<OrderResponse> replyTo;
         @JsonCreator
        public GetOrder(@JsonProperty("replyTo") ActorRef<OrderResponse> replyTo) {
            this.replyTo = replyTo;
        }
    }

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

    public static final class OrderResponse implements CborSerializable {
        public final int order_id;
        public final int user_id;
        public final List<OrderItem> items;
        public final int total_price;
        public final String status;

        @JsonCreator
        public OrderResponse(
            @JsonProperty("order_id") int orderId,
            @JsonProperty("user_id") int userId,
            @JsonProperty("items") List<OrderItem> items,
            @JsonProperty("total_price") int totalPrice,
            @JsonProperty("status") String status) {
            this.order_id = orderId;
            this.user_id = userId;
            this.items = items == null ? new ArrayList<>() : items;
            this.total_price = totalPrice;
            this.status = status;
        }
         public OrderResponse() { this(0, 0, new ArrayList<>(), 0, "Unknown"); }
    }

    public static final class OperationResponse implements CborSerializable {
        public final boolean success;
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
         public OperationResponse() { this(false, "0", "Unknown", "Default constructor"); }
    }

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
    private int orderIdInt; // Parsed int version for internal use/response if needed
    private int userId;
    private List<OrderItem> items;
    private int totalPrice;
    private String status;
    private boolean initialized;
    
    // --- Logger ---
    private final Logger log; // SLF4J Logger

    // --- Factory and Constructor ---
    public static Behavior<Command> create(String entityId) {
        return Behaviors.setup(context -> new OrderActor(context, entityId));
    }

    private OrderActor(ActorContext<Command> context, String entityId) {
        super(context);
        this.entityId = entityId; // Store String ID
        this.log = context.getLog(); // Get SLF4J Logger
        this.initialized = false; 
        this.status = "PendingInitialization"; 
        // log.info("OrderActor created for entityId: {}", entityId); // ADDED LOG
    }

    // --- Receive Logic ---
    @Override
    public Receive<Command> createReceive() {
        return uninitialized();
    }

     private Receive<Command> uninitialized() {
        return newReceiveBuilder()
            .onMessage(InitializeOrder.class, this::onInitializeOrder)
            .onMessage(GetOrder.class, msg -> {
                log.warn("OrderActor {} received GetOrder before initialization.", entityId); // ADDED LOG
                msg.replyTo.tell(new OrderResponse(0, 0, new ArrayList<>(), 0, "NotInitialized")); 
                return Behaviors.same();
            })
            .onMessage(UpdateStatus.class, msg -> {
                log.warn("OrderActor {} received UpdateStatus before initialization.", entityId); // ADDED LOG
                msg.replyTo.tell(new OperationResponse(false, entityId, this.status, "Order not initialized"));
                return Behaviors.same();
            })
            .build();
     }

    private Receive<Command> initialized() {
         return newReceiveBuilder()
            .onMessage(InitializeOrder.class, msg -> {
                 log.warn("OrderActor {} received InitializeOrder again. Ignoring.", entityId); // ADDED LOG
                 msg.replyTo.tell(new OperationResponse(false, String.valueOf(msg.orderId), this.status, "Order already initialized"));
                 return Behaviors.same(); 
            })
            .onMessage(GetOrder.class, this::onGetOrder)
            .onMessage(UpdateStatus.class, this::onUpdateStatus)
            .build();
    }

    private Behavior<Command> onInitializeOrder(InitializeOrder msg) {
         // Validate if message ID matches entity ID
         if (!String.valueOf(msg.orderId).equals(this.entityId)) {
             log.error("InitializeOrder ID mismatch! Actor entityId: {}, Message orderId: {}. Ignoring.",
                this.entityId, msg.orderId); // ADDED LOG
             msg.replyTo.tell(new OperationResponse(false, String.valueOf(msg.orderId), "Unknown", "Initialization ID mismatch"));
             return Behaviors.same(); // Stay uninitialized
         }

        // Initialize state
        this.orderIdInt = msg.orderId; // Store the int version too
        this.userId = msg.userId;
        this.items = msg.items == null ? new ArrayList<>() : msg.items;
        this.totalPrice = msg.totalPrice;
        this.status = msg.initialStatus == null ? "UNKNOWN" : msg.initialStatus;
        this.initialized = true;

        // ADDED LOG for successful initialization
        log.info("OrderActor initialized: entityId={}, orderId={}, userId={}, itemsCount={}, totalPrice={}, status={}",
                entityId, orderIdInt, userId, items.size(), totalPrice, status);

        // Reply success
        msg.replyTo.tell(new OperationResponse(true, entityId, status, "Order initialized successfully"));

        // Transition to initialized behavior
        return initialized();
    }

    // --- Methods below are called when in 'initialized' state ---

    private Behavior<Command> onGetOrder(GetOrder msg) {
        log.info("OrderActor {} received GetOrder request.", entityId); // ADDED LOG
        msg.replyTo.tell(new OrderResponse(orderIdInt, userId, items, totalPrice, status));
        return this;
    }

    private Behavior<Command> onUpdateStatus(UpdateStatus msg) {
        // Prevent updates on terminal states
        if ("DELIVERED".equalsIgnoreCase(this.status) || "CANCELLED".equalsIgnoreCase(this.status)) {
            // ADDED LOG for attempt to update terminal state
            log.warn("OrderActor {} received UpdateStatus ({}) but order is already in terminal state ({}). Ignoring.", 
                    entityId, msg.newStatus, this.status);
            msg.replyTo.tell(new OperationResponse(false, entityId, this.status, "Order is already in a terminal state (" + this.status + ")"));
        } else {
            String oldStatus = this.status;
            this.status = msg.newStatus;
            // ADDED LOG for successful status update
            log.info("OrderActor {} status updated from {} to {}", entityId, oldStatus, this.status);
            msg.replyTo.tell(new OperationResponse(true, entityId, this.status, "Order status updated to " + this.status));
        }
        return this;
    }
}