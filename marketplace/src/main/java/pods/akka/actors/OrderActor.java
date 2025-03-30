package pods.akka.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.Behaviors;
import java.util.List;

public class OrderActor extends AbstractBehavior<OrderActor.Command> {

    // Message protocol for OrderActor.
    public interface Command {}

    // Message to get the order details.
    public static final class GetOrder implements Command {
        public final ActorRef<OrderResponse> replyTo;
        public GetOrder(ActorRef<OrderResponse> replyTo) {
            this.replyTo = replyTo;
        }
    }

    // Message to update the order status.
    public static final class UpdateStatus implements Command {
        public final String newStatus;
        public final ActorRef<OperationResponse> replyTo;
        public UpdateStatus(String newStatus, ActorRef<OperationResponse> replyTo) {
            this.newStatus = newStatus;
            this.replyTo = replyTo;
        }
    }

    // Response message carrying order details.
    public static final class OrderResponse {
        public final int orderId;
        public final int userId;
        public final List<OrderItem> items;
        public final int totalPrice;
        public final String status;
        public OrderResponse(int orderId, int userId, List<OrderItem> items, int totalPrice, String status) {
            this.orderId = orderId;
            this.userId = userId;
            this.items = items;
            this.totalPrice = totalPrice;
            this.status = status;
        }
    }

    // Operation response for status updates.
    public static final class OperationResponse {
        public final boolean success;
        public final String message;
        public OperationResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    // A simple representation of an order item.
    public static final class OrderItem {
        public final int productId;
        public final int quantity;
        public OrderItem(int productId, int quantity) {
            this.productId = productId;
            this.quantity = quantity;
        }
    }

    // Order state.
    private final int orderId;
    private final int userId;
    private final List<OrderItem> items;
    private int totalPrice; // Final computed price after processing.
    private String status;

    // Factory method to create an OrderActor.
    public static Behavior<Command> create(int orderId, int userId, List<OrderItem> items, int totalPrice, String initialStatus) {
        return Behaviors.setup(context -> new OrderActor(context, orderId, userId, items, totalPrice, initialStatus));
    }

    private OrderActor(ActorContext<Command> context, int orderId, int userId, List<OrderItem> items, int totalPrice, String initialStatus) {
        super(context);
        this.orderId = orderId;
        this.userId = userId;
        this.items = items;
        this.totalPrice = totalPrice;
        this.status = initialStatus;
        getContext().getLog().info("OrderActor created with orderId: {}, userId: {}, initialStatus: {}", orderId, userId, initialStatus);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(GetOrder.class, this::onGetOrder)
                .onMessage(UpdateStatus.class, this::onUpdateStatus)
                .build();
    }

    private Behavior<Command> onGetOrder(GetOrder msg) {
        getContext().getLog().info("Received GetOrder request for orderId: {}", orderId);
        msg.replyTo.tell(new OrderResponse(orderId, userId, items, totalPrice, status));
        getContext().getLog().info("Replied with order details for orderId: {}", orderId);
        return this;
    }

    private Behavior<Command> onUpdateStatus(UpdateStatus msg) {
        getContext().getLog().info("Received UpdateStatus request for orderId: {} with newStatus: {}", orderId, msg.newStatus);
        this.status = msg.newStatus;
        msg.replyTo.tell(new OperationResponse(true, "Order status updated to " + status));
        getContext().getLog().info("Order status updated for orderId: {} to newStatus: {}", orderId, status);
        return this;
    }
}
