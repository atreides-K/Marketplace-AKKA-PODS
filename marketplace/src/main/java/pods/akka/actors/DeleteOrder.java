package pods.akka.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import pods.akka.CborSerializable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List; // <--- FIX 1: Added import

// PHASE 2: Worker actor, likely created via Router. Needs serialization for messages.
public class DeleteOrder extends AbstractBehavior<DeleteOrder.Command> {

    // PHASE 2 CHANGE: Command interface must be serializable
    public interface Command extends CborSerializable {}

    // Initial command to start order deletion.
    public static final class StartDelete implements Command {
        public final String orderId; // Use String to match OrderActor entityId
        public final ActorRef<DeleteOrderResponse> replyTo;
        @JsonCreator
        public StartDelete(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("replyTo") ActorRef<DeleteOrderResponse> replyTo) {
            this.orderId = orderId;
            this.replyTo = replyTo;
        }
    }

    // Internal message indicating that order status update is done.
    private static final class OrderStatusUpdated implements Command {
        public final OrderActor.OperationResponse response;
        public OrderStatusUpdated(OrderActor.OperationResponse response) {
            this.response = response;
        }
    }

    // Internal message carrying the response from the GetOrder call.
    private static final class OrderDetailsReceived implements Command {
        public final OrderActor.OrderResponse orderDetails;
        public OrderDetailsReceived(OrderActor.OrderResponse orderDetails) {
            this.orderDetails = orderDetails;
        }
    }

    // Internal message for various failures
    private static final class ProcessingFailed implements Command {
        public final String reason;
        public ProcessingFailed(String reason) {
            this.reason = reason;
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

     // Response sent back to the original requester (e.g., Gateway via Ask).
    // PHASE 2 CHANGE: Response must be serializable
    public static final class DeleteOrderResponse implements CborSerializable {
        public final boolean success;
        public final String message;
        @JsonCreator
        public DeleteOrderResponse(
            @JsonProperty("success") boolean success,
            @JsonProperty("message") String message) {
            this.success = success;
            this.message = message;
        }
         // Default constructor for Jackson
         public DeleteOrderResponse() {
            this(false, "Default constructor");
        }
    }

    // State variables.
    private String orderId;
    private int userId; // Needed for wallet interaction
    private int totalPrice; // Needed for wallet interaction
    private List<OrderActor.OrderItem> itemsToRestock; // <--- FIX 1: Uses List
    private ActorRef<DeleteOrderResponse> pendingReplyTo;
    private EntityRef<OrderActor.Command> orderEntity; // Ref to the specific sharded OrderActor

    private final HttpClient httpClient;
    private final ClusterSharding sharding;
    private final ActorContext<Command> context; // Store context

    public static Behavior<Command> create() {
        return Behaviors.setup(DeleteOrder::new);
    }

    private DeleteOrder(ActorContext<Command> context) {
        super(context);
        this.context = context; // Store context for async callbacks
        this.httpClient = HttpClient.newBuilder()
                                   .connectTimeout(Duration.ofSeconds(5))
                                   .build();
        this.sharding = ClusterSharding.get(context.getSystem());
        this.itemsToRestock = new ArrayList<>(); // Initialize list
        context.getLog().info("DeleteOrder worker actor started.");
    }

    // FIX 2: REMOVED the first, incomplete createReceive() definition that was here.

    // --- Step Handlers (unchanged from previous version) ---

    // Step 1: Receive StartDelete, check if OrderActor exists.
    private Behavior<Command> onStartDelete(StartDelete cmd) {
        // ... (logic as before) ...
        this.orderId = cmd.orderId;
        this.pendingReplyTo = cmd.replyTo;
        context.getLog().info("DeleteOrder processing request for orderId: {}", orderId);
        this.orderEntity = sharding.entityRefFor(OrderActor.TypeKey, orderId);
        context.ask(
            OrderActor.OrderResponse.class,
            orderEntity,
            Duration.ofSeconds(5),
            (ActorRef<OrderActor.OrderResponse> adapter) -> new OrderActor.GetOrder(adapter),
            (response, failure) -> { // Logic to handle response/failure and send internal message
                 if (response != null) {
                     if (response.order_id == 0 || "NotInitialized".equals(response.status)) {
                         return new ProcessingFailed("Order not found or not initialized.");
                     } else if ("CANCELLED".equalsIgnoreCase(response.status) || "DELIVERED".equalsIgnoreCase(response.status)) {
                         return new ProcessingFailed("Order is already in a terminal state: " + response.status);
                     } else {
                         this.userId = response.user_id;
                         this.totalPrice = response.total_price;
                         this.itemsToRestock = response.items;
                         // Use OrderStatusUpdated internal message to trigger next step
                         return new OrderStatusUpdated(new OrderActor.OperationResponse(true, orderId, response.status, "Order Found"));
                     }
                } else {
                    context.getLog().error("Failed to get order details for {} within timeout: {}", orderId, failure);
                    return new ProcessingFailed("Failed to communicate with OrderActor (timeout or error).");
                }
            }
        );
        return Behaviors.same();
    }


    // Step 2: Order found, now attempt to update status to CANCELLED
    private Behavior<Command> onOrderStatusUpdateAttempt() {
        // ... (logic as before) ...
         context.getLog().info("Order {} found. Attempting to set status to CANCELLED.", orderId);
        context.ask(
            OrderActor.OperationResponse.class,
            orderEntity,
            Duration.ofSeconds(5),
            (ActorRef<OrderActor.OperationResponse> adapter) -> new OrderActor.UpdateStatus("CANCELLED", adapter),
            (response, failure) -> { // Logic to handle response/failure and send internal message
                 if (response != null && response.success) {
                     // Use OrderDetailsReceived internal message to trigger next step (bit weird, maybe rename?)
                     return new OrderDetailsReceived(new OrderActor.OrderResponse(0, userId, itemsToRestock, totalPrice, "CANCELLED"));
                 } else {
                     String errorMsg = (response != null) ? response.message : "Timeout or communication error";
                     context.getLog().error("Failed to update order {} status to CANCELLED: {}", orderId, errorMsg);
                     return new ProcessingFailed("Failed to update order status: " + errorMsg);
                 }
            }
        );
         return Behaviors.same();
    }


    // Step 3: Order status updated to CANCELLED, now restock products.
    private Behavior<Command> onRestockProducts() {
        // ... (logic as before) ...
        context.getLog().info("Order {} status set to CANCELLED. Proceeding to restock products.", orderId);
        if (itemsToRestock == null || itemsToRestock.isEmpty()) {
             context.getLog().warn("No items found to restock for order {}. Proceeding to credit wallet.", orderId);
             return onCreditWallet(); // Directly proceed
        }
        for (OrderActor.OrderItem item : itemsToRestock) { // logic to tell ProductActors
             if (item.quantity > 0) {
                EntityRef<ProductActor.Command> productEntity =
                        sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(item.product_id));
                productEntity.tell(new ProductActor.AddStock(item.quantity));
            }
        }
        context.getLog().info("Restock commands sent for order {}. Proceeding to credit wallet.", orderId);
        return onCreditWallet(); // Proceed
    }

    // Step 4: Restocking done (or skipped), now credit the wallet.
    private Behavior<Command> onCreditWallet() {
         // ... (logic as before) ...
         context.getLog().info("Attempting to credit wallet for user {} with amount {} for cancelled order {}", userId, totalPrice, orderId);
        if (totalPrice <= 0) { // logic for zero price
             context.getLog().warn("Total price for order {} is zero or negative ({}). Skipping wallet credit.", orderId, totalPrice);
             context.getSelf().tell(new WalletCreditResult(true, "Skipped credit for zero/negative amount"));
             // Need to wait for the WalletCreditResult message
             return Behaviors.receive(Command.class)
                    .onMessage(WalletCreditResult.class, this::onWalletCreditResult)
                    .onMessage(ProcessingFailed.class, this::onProcessingFailed)
                    .build();
        }
        String creditJson = String.format("{\"action\": \"credit\", \"amount\": %d}", totalPrice);
        HttpRequest creditRequest = HttpRequest.newBuilder() // logic for http request
                .uri(URI.create("http://localhost:8082/wallets/" + userId))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(creditJson))
                .build();
        httpClient.sendAsync(creditRequest, BodyHandlers.ofString()) // async call
            .whenCompleteAsync((resp, ex) -> { // handle response
                if (ex != null || resp.statusCode() != 200) {
                    String error = (ex != null) ? ex.getMessage() : "Status code " + resp.statusCode();
                    context.getSelf().tell(new WalletCreditResult(false, "Wallet credit failed: " + error));
                } else {
                    context.getSelf().tell(new WalletCreditResult(true, "Wallet credited successfully"));
                }
            }, context.getExecutionContext());
        // Still need to wait for the WalletCreditResult message
         return Behaviors.receive(Command.class)
                .onMessage(WalletCreditResult.class, this::onWalletCreditResult)
                 .onMessage(ProcessingFailed.class, this::onProcessingFailed)
                .build();
    }

    // Step 5: Handle wallet credit result and finalize.
    private Behavior<Command> onWalletCreditResult(WalletCreditResult msg) {
        // ... (logic as before) ...
         if (msg.success) {
            context.getLog().info("Order {} cancellation process completed successfully.", orderId);
            pendingReplyTo.tell(new DeleteOrderResponse(true, "Order " + orderId + " cancelled successfully."));
        } else {
             context.getLog().error("CRITICAL: Order {} cancelled, but wallet credit failed: {}. Manual intervention may be required.", orderId, msg.message);
             pendingReplyTo.tell(new DeleteOrderResponse(false, "Order " + orderId + " cancelled, but refund failed: " + msg.message));
        }
        return Behaviors.stopped();
    }

    // Handle any processing failure during the steps.
    private Behavior<Command> onProcessingFailed(ProcessingFailed msg) {
        // ... (logic as before) ...
         context.getLog().error("Order {} cancellation failed: {}", orderId, msg.reason);
        pendingReplyTo.tell(new DeleteOrderResponse(false, "Order cancellation failed: " + msg.reason));
        return Behaviors.stopped();
    }

    // FIX 2: KEPT the second, complete createReceive() method definition.
    // Re-define receive logic to handle state transitions based on internal messages
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(StartDelete.class, this::onStartDelete)
            // Message received from the ask callback in onStartDelete
            .onMessage(OrderStatusUpdated.class, msg -> { // This message now signals success from GetOrder ask
                if (msg.response.success) {
                    return onOrderStatusUpdateAttempt(); // Proceed to next step
                } else {
                    // This case shouldn't happen if ProcessingFailed is sent correctly
                    return onProcessingFailed(new ProcessingFailed("Order check failed unexpectedly."));
                }
            })
             // Message received from the ask callback in onOrderStatusUpdateAttempt
            .onMessage(OrderDetailsReceived.class, msg -> onRestockProducts()) // This now signals success from UpdateStatus ask
            .onMessage(WalletCreditResult.class, this::onWalletCreditResult) // Handles result from Step 4
            .onMessage(ProcessingFailed.class, this::onProcessingFailed) // Handles failures from asks
            .build();
    }
}