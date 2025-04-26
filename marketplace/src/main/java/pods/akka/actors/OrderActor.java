package pods.akka.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import pods.akka.CborSerializable;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class OrderActor extends AbstractBehavior<OrderActor.Command> implements CborSerializable {

    // Message protocol for OrderActor.
    public interface Command extends CborSerializable {}

    public static final EntityTypeKey<Command> TypeKey =
        EntityTypeKey.create(OrderActor.Command.class, "OrderEntity");

    // Initialization message to set the order details.
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
            this.items = items;
            this.totalPrice = totalPrice;
            this.initialStatus = initialStatus;
            this.replyTo = replyTo;
        }
    }

    // Message to get the order details.
    public static final class GetOrder implements Command {
        public final ActorRef<OrderResponse> replyTo;
        @JsonCreator
        public GetOrder(@JsonProperty("replyTo") ActorRef<OrderResponse> replyTo) {
            this.replyTo = replyTo;
        }
    }

    // Message to update the order status.
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

    // Response message carrying order details.
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
            this.items = items;
            this.total_price = totalPrice;
            this.status = status;
        }
    }

    // Operation response for status updates.
    public static final class OperationResponse implements CborSerializable {
        public final boolean success;
        public final String order_id;
        public final String status;
        public final String message;
        
        @JsonCreator
        public OperationResponse(
                @JsonProperty("success") boolean success,
                @JsonProperty("order_id") String order_id, // Keep as String if sent that way
                @JsonProperty("status") String status,
                @JsonProperty("message") String message) {
            this.success = success;
            this.order_id = order_id;
            this.status = status;
            this.message = message;
        }
    }  

    // A simple representation of an order item.
    public static final class OrderItem implements CborSerializable {
        public final int product_id;
        public final int quantity;
        public OrderItem() {
            this.product_id = 0;
            this.quantity = 0;
        }
        @JsonCreator // Explicitly mark the main constructor
        public OrderItem(
                @JsonProperty("product_id") int product_id,
                @JsonProperty("quantity") int quantity) {
            this.product_id = product_id;
            this.quantity = quantity;
        }
    }

    // A static counter to generate unique order item ids.
    private static final AtomicInteger orderItemIdCounter = new AtomicInteger(1);

    // Order state.
    private int orderId;
    private int userId;
    private List<OrderItem> items;
    private int totalPrice;
    private String status;
    private boolean initialized;

    // Factory method to create an OrderActor.
    public static Behavior<Command> create(String entityId) {
        // entityId can be converted to int orderId if needed.
        return Behaviors.setup(context -> new OrderActor(context));
    }

    private OrderActor(ActorContext<Command> context) {
        super(context);
        this.initialized = false;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(InitializeOrder.class, this::onInitializeOrder)
            .onMessage(GetOrder.class, this::onGetOrder)
            .onMessage(UpdateStatus.class, this::onUpdateStatus)
            .build();
    }

    private Behavior<Command> onInitializeOrder(InitializeOrder msg) {
        if (!initialized) {
            this.orderId = msg.orderId;
            this.userId = msg.userId;
            // Transform the simple items:
            this.items = msg.items;
            this.totalPrice = msg.totalPrice;
            this.status = msg.initialStatus;
            this.initialized = true;
            getContext().getLog().info("OrderActor initialized: OrderId: {}, UserId: {}, TotalPrice: {}, Status: {}",
                    orderId, userId, totalPrice, status);
                    msg.replyTo.tell(new OperationResponse(true, String.valueOf(orderId), status, "Order initialized"));
                } else {
                    msg.replyTo.tell(new OperationResponse(false, String.valueOf(orderId), status, "Order already initialized"));
                }
        return this;
    }

    private Behavior<Command> onGetOrder(GetOrder msg) {
        if (!initialized) {
            msg.replyTo.tell(new OrderResponse(0, 0, null, 0, "NotInitialized"));
        } else {
            msg.replyTo.tell(new OrderResponse(orderId, userId, items, totalPrice, status));
        }
        return this;
    }

    private Behavior<Command> onUpdateStatus(UpdateStatus msg) {
        if (!initialized) {
            msg.replyTo.tell(new OperationResponse(false, String.valueOf(orderId), status, "Order not initialized"));
        } else if ("DELIVERED".equalsIgnoreCase(this.status) || "CANCELLED".equalsIgnoreCase(this.status)) {
            // If the order is already cancelled, do not allow any further status change.
            msg.replyTo.tell(new OperationResponse(false, String.valueOf(orderId), status, "Order is already cancelled or delivered, cannot update status"));
        } else {
            this.status = msg.newStatus;
            msg.replyTo.tell(new OperationResponse(true, String.valueOf(orderId), status, "Order status updated to " + status));
        }
        return this;
    }
    
}
