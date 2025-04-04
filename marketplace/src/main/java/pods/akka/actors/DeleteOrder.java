package pods.akka.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.ChildFailed;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.EntityRef;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Optional;

public class DeleteOrder extends AbstractBehavior<DeleteOrder.Command> {

    // Command protocol
    public interface Command {}

    // Initial command to start order deletion.
    public static final class StartDelete implements Command {
        public final String orderId;
        public final ActorRef<DeleteOrderResponse> replyTo;
        public StartDelete(String orderId, ActorRef<DeleteOrderResponse> replyTo) {
            this.orderId = orderId;
            this.replyTo = replyTo;
        }
    }

    // Internal message indicating that order status update is done.
    private static final class OrderStatusUpdated implements Command {
        public final boolean success;
        public final String message;
        public OrderStatusUpdated(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    // Internal message carrying the response from the GetOrder call.
    private static final class OrderDetailsReceived implements Command {
        public final OrderActor.OrderResponse orderDetails;
        public OrderDetailsReceived(OrderActor.OrderResponse orderDetails) {
            this.orderDetails = orderDetails;
        }
    }

    // Internal message when the order is not found.
    private static final class OrderNotFound implements Command {}

    // Internal message when the order is found.
    private static final class OrderFound implements Command {
        public final OrderActor.OrderResponse orderDetails;
        public OrderFound(OrderActor.OrderResponse orderDetails) {
            this.orderDetails = orderDetails;
        }
    }

    // Message for wallet credit result.
    private static final class WalletCreditResult implements Command {
        public final boolean success;
        public final String message;
        public WalletCreditResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    // Final completion message.
    private static final class DeletionProcessingComplete implements Command {
        public final boolean success;
        public final String message;
        public DeletionProcessingComplete(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    // Response sent back to the Gateway.
    public static final class DeleteOrderResponse {
        public final boolean success;
        public final String message;
        public DeleteOrderResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    // HttpClient for making external HTTP calls.
    private final HttpClient httpClient;
    private final ClusterSharding sharding;

    // We now hold a reference to the sharded OrderActor.
    private EntityRef<OrderActor.Command> orderEntity;

    // State variables.
    private String orderId;
    private int userId;
    private ActorRef<DeleteOrderResponse> pendingReplyTo;
    //private ActorRef<OrderActor.Command> orderActor; // reference to the OrderActor

    public static Behavior<Command> create() {
        return Behaviors.setup(DeleteOrder::new);
    }

    private DeleteOrder(ActorContext<Command> context) {
        super(context);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.sharding = ClusterSharding.get(getContext().getSystem());
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(StartDelete.class, this::onStartDelete)
                .onMessage(OrderNotFound.class, this::onOrderNotFound)
                .onMessage(OrderFound.class, this::onOrderFound)
                .onMessage(OrderStatusUpdated.class, this::onOrderStatusUpdated)
                .onMessage(OrderDetailsReceived.class, this::onOrderDetailsReceived)
                .onMessage(WalletCreditResult.class, this::onWalletCreditResult)
                .onMessage(DeletionProcessingComplete.class, this::onDeletionProcessingComplete)
                .build();
    }

    // Step 1: Check if the OrderActor exists.
    private Behavior<Command> onStartDelete(StartDelete cmd) {
        this.orderId = cmd.orderId;
        this.pendingReplyTo = cmd.replyTo;

        // Look up the OrderActor by using its sharded entity ref.
        this.orderEntity = sharding.entityRefFor(OrderActor.TypeKey, String.valueOf(orderId));
        
        // Send a GetOrder message to check if the order exists.
        ActorRef<OrderActor.OrderResponse> adapter = getContext().messageAdapter(OrderActor.OrderResponse.class, orderResp -> {
            if (orderResp.order_id == 0 || "NotInitialized".equals(orderResp.status)) {
                return new OrderNotFound();
            } else {
                return new OrderFound(orderResp);
            }
        });
        orderEntity.tell(new OrderActor.GetOrder(adapter));
        return this;
    }

    // If no order is found, immediately reply with failure.
    private Behavior<Command> onOrderNotFound(OrderNotFound msg) {
        getContext().getLog().error("Order {} not found.", orderId);
        pendingReplyTo.tell(new DeleteOrderResponse(false, "Order not found"));
        return Behaviors.stopped();
    }
    
    // If the order exists, proceed.
    private Behavior<Command> onOrderFound(OrderFound msg) {
        getContext().getLog().info("Order {} found. Proceeding to cancellation.", orderId);
        // Step 2: Update order status to "CANCELLED".
        ActorRef<OrderActor.OperationResponse> statusAdapter =
                getContext().messageAdapter(OrderActor.OperationResponse.class,
                        response -> new OrderStatusUpdated(response.success, response.message));
        orderEntity.tell(new OrderActor.UpdateStatus("CANCELLED", statusAdapter));
        return this;
    }

    // Step 3: Handle response from OrderActor status update.
    private Behavior<Command> onOrderStatusUpdated(OrderStatusUpdated msg) {
        if (!msg.success) {
            getContext().getLog().error("Failed to update order status: {}", msg.message);
            pendingReplyTo.tell(new DeleteOrderResponse(false, "Failed to cancel order: " + msg.message));
            return Behaviors.stopped();
        } else {
            getContext().getLog().info("Order status updated to CANCELLED for order {}", orderId);
            // Step 4: Query the order details to get total price and items.
            ActorRef<OrderActor.OrderResponse> getAdapter = getContext().messageAdapter(OrderActor.OrderResponse.class,
                    orderResp -> new OrderDetailsReceived(orderResp));
            orderEntity.tell(new OrderActor.GetOrder(getAdapter));
            return this;
        }
    }

    // Step 5: Process the order details.
    private Behavior<Command> onOrderDetailsReceived(OrderDetailsReceived msg) {
        OrderActor.OrderResponse details = msg.orderDetails;
        // For deletion, we need the total price and the list of items.
        int totalPrice = details.total_price;
        // Step 6: Restock each product in the order.
        for (OrderActor.OrderItem item : details.items) {
            EntityRef<ProductActor.Command> productEntity =
                    sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(item.product_id));
            productEntity.tell(new ProductActor.AddStock(item.quantity));
        }
        getContext().getLog().info("Restocked products for order {}", orderId);
        // Step 7: Credit the wallet with the total price.
        String creditJson = "{\"action\": \"credit\", \"amount\": " + totalPrice + "}";
        userId = details.user_id; // Get userId from order details
        getContext().getLog().info("Crediting wallet for user {} with amount {}", userId, totalPrice);
        HttpRequest creditRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://host.docker.internal:8082/wallets/" + userId))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(creditJson))
                .build();
        httpClient.sendAsync(creditRequest, BodyHandlers.ofString())
                .whenComplete((resp, ex) -> {
                    if (ex != null || resp.statusCode() != 200) {
                        getContext().getSelf().tell(new WalletCreditResult(false, "Wallet credit failed"));
                    } else {
                        getContext().getSelf().tell(new WalletCreditResult(true, ""));
                    }
                });
        return this;
    }

    // Step 8: Handle wallet credit result.
    private Behavior<Command> onWalletCreditResult(WalletCreditResult msg) {
        if (!msg.success) {
            getContext().getLog().error("Wallet credit failed: {}", msg.message);
            getContext().getSelf().tell(new DeletionProcessingComplete(false, "Wallet credit failed"));
        } else {
            getContext().getLog().info("Wallet credited successfully for order {}", orderId);
            getContext().getSelf().tell(new DeletionProcessingComplete(true, "Order cancelled successfully"));
        }
        return this;
    }

    // Final step: Complete the deletion process.
    private Behavior<Command> onDeletionProcessingComplete(DeletionProcessingComplete msg) {
        pendingReplyTo.tell(new DeleteOrderResponse(msg.success,
                "Order " + orderId + (msg.success ? " cancelled successfully." : " cancellation failed: " + msg.message)));
        return Behaviors.stopped();
    }
}
