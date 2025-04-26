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
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List; // Import List

// Import SLF4J Logger
import org.slf4j.Logger;

// PHASE 2: Worker actor, likely created via Router. Needs serialization for messages.
public class DeleteOrder extends AbstractBehavior<DeleteOrder.Command> {

    // Get logger instance
    private final Logger log = getContext().getLog();

    // --- Command Protocol ---
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

    // --- Internal Messages ---

    // Renamed: Message carrying the result of the initial GetOrder ask
    private static final class InitialOrderCheckResult implements Command {
        public final OrderActor.OrderResponse orderDetails; // Null if not found/error
        public final String failureReason; // Null if success
        public InitialOrderCheckResult(OrderActor.OrderResponse details, String failureReason) {
            this.orderDetails = details;
            this.failureReason = failureReason;
        }
    }

    // Renamed: Message carrying the result of the UpdateStatus ask
    private static final class OrderStatusUpdateResult implements Command {
        public final OrderActor.OperationResponse updateResponse; // Null if failure
        public final String failureReason; // Null if success
        public OrderStatusUpdateResult(OrderActor.OperationResponse response, String failureReason) {
            this.updateResponse = response;
            this.failureReason = failureReason;
        }
    }

    // Message carrying the result of the Wallet credit HTTP call
    private static final class WalletCreditResult implements Command {
        public final boolean success;
        public final String message;
        public WalletCreditResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    // Generic internal failure message (alternative to dedicated failure messages)
    // Can be used if an unrecoverable error occurs outside of specific ask/http calls
    private static final class ProcessingFailed implements Command {
        public final String reason;
        public ProcessingFailed(String reason) {
            this.reason = reason;
        }
    }


    // --- Response sent back to the original requester ---
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
         // Default constructor
         public DeleteOrderResponse() {
            this(false, "Default constructor message");
        }
    }

    // --- State Variables ---
    private String orderId;
    private int userId; // Needed for wallet interaction
    private int totalPrice; // Needed for wallet interaction
    private List<OrderActor.OrderItem> itemsToRestock; // Store items from valid order
    private ActorRef<DeleteOrderResponse> pendingReplyTo;
    private EntityRef<OrderActor.Command> orderEntity; // Ref to the specific sharded OrderActor

    private final HttpClient httpClient;
    private final ClusterSharding sharding;
    private final ActorContext<Command> context; // Store context


    // --- Constructor and Factory ---
    public static Behavior<Command> create() {
        return Behaviors.setup(DeleteOrder::new);
    }

    private DeleteOrder(ActorContext<Command> context) {
        super(context);
        this.context = context;
        this.httpClient = HttpClient.newBuilder()
                                   .connectTimeout(Duration.ofSeconds(5))
                                   .build();
        this.sharding = ClusterSharding.get(context.getSystem());
        this.itemsToRestock = new ArrayList<>(); // Initialize list
        log.info("DeleteOrder worker actor started. Path: {}", context.getSelf().path());
    }

    // --- Receive Logic ---
    // The main behavior handles the initial StartDelete and internal messages sequentially
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(StartDelete.class, this::onStartDelete)
            .onMessage(InitialOrderCheckResult.class, this::onInitialOrderCheckResult)
            .onMessage(OrderStatusUpdateResult.class, this::onOrderStatusUpdateResult)
            .onMessage(WalletCreditResult.class, this::onWalletCreditResult)
            // Optional: Handle ProcessingFailed if used elsewhere
            // .onMessage(ProcessingFailed.class, this::onProcessingFailed)
            .build();
    }

    // --- Step Handlers ---

    // Step 1: Receive StartDelete, check if OrderActor exists and is valid for cancellation.
    private Behavior<Command> onStartDelete(StartDelete cmd) {
        log.debug("onStartDelete: Received for orderId '{}'", cmd.orderId);
        this.orderId = cmd.orderId;
        this.pendingReplyTo = cmd.replyTo;

        if (this.orderId == null || this.orderId.trim().isEmpty()) {
            log.error("onStartDelete: Received StartDelete with null or empty orderId.");
            return finalizeFailure("Invalid Order ID provided.");
        }
        if (this.pendingReplyTo == null) {
            log.error("onStartDelete: Received StartDelete for order '{}' but missing replyTo actor.", orderId);
            // Cannot reply, just stop.
            return Behaviors.stopped();
        }

        log.info("onStartDelete: Processing request for orderId: {}", orderId);
        try {
            // Get reference using the String orderId
            this.orderEntity = sharding.entityRefFor(OrderActor.TypeKey, orderId);
        } catch (Exception e) {
             log.error("onStartDelete: Failed to get EntityRef for order '{}': {}", orderId, e.getMessage(), e);
             return finalizeFailure("Internal error processing order ID.");
        }

        // Ask the OrderActor for its current state
        log.debug("onStartDelete: Asking OrderActor for details of order '{}'", orderId);
        context.ask(
            OrderActor.OrderResponse.class, // Expected response type
            orderEntity,                    // Target actor
            Duration.ofSeconds(5),          // Timeout
            // Message factory
            (ActorRef<OrderActor.OrderResponse> adapter) -> new OrderActor.GetOrder(adapter),
            // Map response/failure to internal message
            (response, failure) -> {
                 if (response != null) {
                     // Check if order exists and is in a cancellable state
                     if ("NotInitialized".equals(response.status) || response.order_id == null || response.order_id.trim().isEmpty() ) {
                          log.warn("onStartDelete: Order '{}' not found or not initialized.", orderId);
                          return new InitialOrderCheckResult(null, "Order not found or not initialized.");
                     } else if ("CANCELLED".equalsIgnoreCase(response.status) || "DELIVERED".equalsIgnoreCase(response.status)) {
                          log.warn("onStartDelete: Order '{}' is already in a terminal state: {}", orderId, response.status);
                          return new InitialOrderCheckResult(null, "Order is already in a terminal state: " + response.status);
                     } else {
                         // Order found and is in a valid state to be cancelled
                         log.info("onStartDelete: Order '{}' found. Status: {}, User: {}, Price: {}. Proceeding with cancellation.",
                                  orderId, response.status, response.user_id, response.total_price);
                         return new InitialOrderCheckResult(response, null); // Success case
                     }
                } else {
                    // Ask failed (timeout or other error)
                    log.error("onStartDelete: Failed to get order details for '{}': {}", orderId, failure != null ? failure.getMessage() : "Unknown Ask failure", failure);
                    return new InitialOrderCheckResult(null, "Failed to communicate with OrderActor (timeout or error).");
                }
            }
        );

        // Actor stays in the main behavior, waiting for InitialOrderCheckResult
        log.debug("onStartDelete: Waiting for InitialOrderCheckResult for order '{}'", orderId);
        return newReceiveBuilder()
            .onMessage(StartDelete.class, this::onStartDelete)
            .onMessage(InitialOrderCheckResult.class, this::onInitialOrderCheckResult)
            .onMessage(OrderStatusUpdateResult.class, this::onOrderStatusUpdateResult)
            .onMessage(WalletCreditResult.class, this::onWalletCreditResult)
            // Optional: Handle ProcessingFailed if used elsewhere
            // .onMessage(ProcessingFailed.class, this::onProcessingFailed)
            .build();
    }

    // Step 2: Handle the result of the initial order check.
    private Behavior<Command> onInitialOrderCheckResult(InitialOrderCheckResult msg) {
        log.debug("onInitialOrderCheckResult: Received for order '{}'. Failure reason: {}", orderId, msg.failureReason);
        if (msg.failureReason != null) {
            // Order not found, already terminal, or communication failed
            return finalizeFailure(msg.failureReason);
        }

        // Order is valid, store details and attempt to update status to CANCELLED
        OrderActor.OrderResponse details = msg.orderDetails;
        this.userId = details.user_id;
        this.totalPrice = details.total_price;
        this.itemsToRestock = details.items == null ? new ArrayList<>() : details.items; // Ensure list is not null

        log.info("onInitialOrderCheckResult: Order '{}' validated. Attempting to set status to CANCELLED.", orderId);
        context.ask(
            OrderActor.OperationResponse.class, // Expected response type
            orderEntity,                        // Target (already retrieved)
            Duration.ofSeconds(5),              // Timeout
            // Message factory
            (ActorRef<OrderActor.OperationResponse> adapter) -> new OrderActor.UpdateStatus("CANCELLED", adapter),
            // Map response/failure to internal message
            (response, failure) -> {
                 if (response != null && response.success) {
                     log.info("onInitialOrderCheckResult: Order '{}' status successfully updated to CANCELLED by OrderActor.", orderId);
                     return new OrderStatusUpdateResult(response, null); // Success case
                 } else {
                     String errorMsg = "Unknown error";
                     if (failure != null) {
                        errorMsg = "Ask Failure: " + failure.getMessage();
                     } else if (response != null) {
                        errorMsg = "Actor Reply Failure: " + response.message;
                     }
                     log.error("onInitialOrderCheckResult: Failed to update order '{}' status to CANCELLED: {}", orderId, errorMsg, failure);
                     // Even if status update fails, maybe proceed with refund/restock? Depends on requirements.
                     // For now, let's treat this as a failure of the delete operation.
                     return new OrderStatusUpdateResult(null, "Failed to update order status: " + errorMsg);
                 }
            }
        );

        // Actor stays in the main behavior, waiting for OrderStatusUpdateResult
        log.debug("onInitialOrderCheckResult: Waiting for OrderStatusUpdateResult for order '{}'", orderId);
        return newReceiveBuilder()
            .onMessage(StartDelete.class, this::onStartDelete)
            .onMessage(InitialOrderCheckResult.class, this::onInitialOrderCheckResult)
            .onMessage(OrderStatusUpdateResult.class, this::onOrderStatusUpdateResult)
            .onMessage(WalletCreditResult.class, this::onWalletCreditResult)
            // Optional: Handle ProcessingFailed if used elsewhere
            // .onMessage(ProcessingFailed.class, this::onProcessingFailed)
            .build();
    }


    // Step 3: Order status updated to CANCELLED, now restock products.
    private Behavior<Command> onOrderStatusUpdateResult(OrderStatusUpdateResult msg) {
        log.debug("onOrderStatusUpdateResult: Received for order '{}'. Failure reason: {}", orderId, msg.failureReason);
        if (msg.failureReason != null) {
            // Failed to update status. Decide if we should still try to refund/restock?
            // For now, treat as fatal error for the delete operation.
             return finalizeFailure(msg.failureReason);
        }

        // Status is CANCELLED in OrderActor, proceed with side effects
        log.info("onOrderStatusUpdateResult: Order '{}' status confirmed CANCELLED. Proceeding to restock products.", orderId);
        if (itemsToRestock == null || itemsToRestock.isEmpty()) {
             log.warn("onOrderStatusUpdateResult: No items found to restock for order '{}'. Proceeding to credit wallet.", orderId);
             // Proceed directly to wallet credit
             return onCreditWallet();
        }

        log.debug("onOrderStatusUpdateResult: Sending AddStock commands for {} item types.", itemsToRestock.size());
        for (OrderActor.OrderItem item : itemsToRestock) {
             if (item.quantity > 0) {
                 try {
                    EntityRef<ProductActor.Command> productEntity =
                            sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(item.product_id));
                    // Send AddStock (fire-and-forget)
                    productEntity.tell(new ProductActor.AddStock(item.quantity));
                    log.debug("onOrderStatusUpdateResult: Sent AddStock for product {} (qty: {})", item.product_id, item.quantity);
                 } catch (Exception e) {
                      log.error("onOrderStatusUpdateResult: Error sending AddStock for product {}: {}", item.product_id, e.getMessage(), e);
                      // Log error but continue - best effort restock
                 }
            } else {
                  log.warn("onOrderStatusUpdateResult: Skipping restock for product {} due to zero/negative quantity: {}", item.product_id, item.quantity);
            }
        }

        log.info("onOrderStatusUpdateResult: Restock commands sent for order '{}'. Proceeding to credit wallet.", orderId);
        // Proceed to wallet credit after initiating restock
        return onCreditWallet();
    }

    // Step 4: Initiate wallet credit (called from previous step)
    private Behavior<Command> onCreditWallet() {
         log.debug("onCreditWallet: Initiating wallet credit for order '{}'. User: {}, Amount: {}", orderId, userId, totalPrice);
         // Sanity checks
         if (userId <= 0) {
              log.error("onCreditWallet: Cannot credit wallet for order '{}', invalid userId: {}", orderId, userId);
              // This indicates a flaw earlier, but fail here rather than proceed.
              return finalizeFailure("Internal error: Missing user ID for wallet credit.");
         }

        if (totalPrice <= 0) {
             log.warn("onCreditWallet: Total price for order '{}' is zero or negative ({}). Skipping wallet credit.", orderId, totalPrice);
             // Send self-message to finalize successfully without credit
             context.getSelf().tell(new WalletCreditResult(true, "Skipped credit for zero/negative amount"));
        } else {
            // Step 4.1: Credit Wallet via HTTP
            log.info("onCreditWallet: Attempting to credit wallet for user {} with amount {} for cancelled order '{}'", userId, totalPrice, orderId);
            String creditJson = String.format("{\"action\": \"credit\", \"amount\": %d}", totalPrice);
            HttpRequest creditRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8082/wallets/" + userId)) // Assuming Wallet service endpoint
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(creditJson))
                    .build();

            httpClient.sendAsync(creditRequest, BodyHandlers.ofString())
                .whenCompleteAsync((resp, ex) -> { // Handle response asynchronously
                    log.debug("onCreditWallet: Wallet credit response received (or exception).");
                    if (ex != null || resp.statusCode() != 200) {
                        String error = (ex != null) ? ex.getMessage() : "Status code " + resp.statusCode();
                        log.error("onCreditWallet: Wallet credit HTTP failed for user {}: {}", userId, error, ex);
                        // Send internal failure message
                        context.getSelf().tell(new WalletCreditResult(false, "Wallet credit failed: " + error));
                    } else {
                        log.info("onCreditWallet: Wallet credited successfully for user {}. Amount: {}", userId, totalPrice);
                        // Send internal success message
                        context.getSelf().tell(new WalletCreditResult(true, "Wallet credited successfully"));
                    }
                }, context.getExecutionContext()); // Use Akka dispatcher
        }

        // Actor stays in the main behavior, waiting for WalletCreditResult
        log.debug("onCreditWallet: Waiting for WalletCreditResult for order '{}'", orderId);
        return this;
    }

    // Step 5: Handle wallet credit result and finalize.
    private Behavior<Command> onWalletCreditResult(WalletCreditResult msg) {
        log.debug("onWalletCreditResult: Received for order '{}'. Success: {}", orderId, msg.success);
        if (msg.success) {
            log.info("Order '{}' cancellation process completed successfully.", orderId);
            return finalizeSuccess("Order " + orderId + " cancelled successfully.");
        } else {
             // Wallet credit failed AFTER order status was set to CANCELLED.
             log.error("CRITICAL: Order '{}' cancelled, but wallet credit failed: {}. Manual intervention likely required.", orderId, msg.message);
             // Reply with failure but indicate the order *is* cancelled.
             return finalizeFailure("Order " + orderId + " cancelled, but refund failed: " + msg.message);
        }
    }

    // --- Final Reply and Stop ---

    // Helper for successful completion
    private Behavior<Command> finalizeSuccess(String message) {
        log.info("finalizeSuccess: Order '{}'. Message: {}", orderId, message);
        if (pendingReplyTo != null) {
            pendingReplyTo.tell(new DeleteOrderResponse(true, message));
        } else {
             log.error("finalizeSuccess: Cannot reply success for order '{}', pendingReplyTo is null!", orderId);
        }
        log.debug("finalizeSuccess: Stopping actor for order '{}'.", orderId);
        return Behaviors.stopped();
    }

    // Helper for failed completion
    private Behavior<Command> finalizeFailure(String reason) {
        log.error("finalizeFailure: Order '{}'. Reason: {}", orderId, reason);
        if (pendingReplyTo != null) {
            pendingReplyTo.tell(new DeleteOrderResponse(false, reason));
        } else {
             log.error("finalizeFailure: Cannot reply failure for order '{}', pendingReplyTo is null!", orderId);
        }
        log.debug("finalizeFailure: Stopping actor for order '{}'.", orderId);
        return Behaviors.stopped();
    }

    // REMOVED: Previous onProcessingFailed, merged logic into finalizeFailure
    // REMOVED: Previous onRestockProducts, merged logic into onOrderStatusUpdateResult/onCreditWallet
    // REMOVED: Previous onOrderStatusUpdateAttempt, logic integrated into onInitialOrderCheckResult/onOrderStatusUpdateResult
}