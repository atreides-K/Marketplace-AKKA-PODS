package pods.akka.actors;

import akka.actor.typed.Behavior;
import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import pods.akka.CborSerializable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

// Import SLF4J Logger
import org.slf4j.Logger;

// PHASE 2: Worker actor, likely created via Router. Needs serialization for messages.
public class PostOrder extends AbstractBehavior<PostOrder.Command> {

    // Get logger instance
    private final Logger log = getContext().getLog();

    // --- Command Protocol ---
    public interface Command extends CborSerializable {}

    // Initial command to start order processing.
    public static final class StartOrder implements Command {
        public final int userId;
        public final List<OrderActor.OrderItem> items;
        public final ActorRef<PostOrderResponse> replyTo;

        @JsonCreator
        public StartOrder(
            @JsonProperty("userId") int userId,
            @JsonProperty("items") List<OrderActor.OrderItem> items,
            @JsonProperty("replyTo") ActorRef<PostOrderResponse> replyTo) {
            this.userId = userId;
            this.items = items;
            this.replyTo = replyTo;
        }
    }

    // --- Internal messages for state transitions ---
    private static final class UserValidationResponse implements Command {
        final boolean success;
        final boolean discountAvailable;
        final String message;
        UserValidationResponse(boolean success, boolean discountAvailable, String message) {
            this.success = success; this.discountAvailable = discountAvailable; this.message = message; }
    }
    private static final class StockCheckComplete implements Command {
        final boolean success; // True if all checks passed
        final String message;
        StockCheckComplete(boolean success, String message) { this.success = success; this.message = message; }
    }
    private static final class StockDeductionComplete implements Command {
         final boolean success;
         final int finalPrice;
         final String message;
         StockDeductionComplete(boolean success, int finalPrice, String message) {
             this.success = success; this.finalPrice = finalPrice; this.message = message;
         }
    }
    private static final class WalletCheckResponse implements Command {
        final boolean success; // True if wallet check passed (communication + balance)
        final String message;
        WalletCheckResponse(boolean success, String message) { this.success = success; this.message = message; }
    }
    private static final class WalletDebitResponse implements Command {
         final boolean success;
         final String message;
         WalletDebitResponse(boolean success, String message) { this.success = success; this.message = message; }
    }
    private static final class DiscountUpdateResponse implements Command {
        final boolean success; // Whether discount update call succeeded
        final String message;
        DiscountUpdateResponse(boolean success, String message) { this.success = success; this.message = message; }
    }
    private static final class OrderInitializationResponse implements Command {
         final boolean success;
         final OrderActor.OperationResponse originalResponse; // Keep original for details
         final String message;
         OrderInitializationResponse(boolean success, OrderActor.OperationResponse originalResponse, String message) {
             this.success = success; this.originalResponse = originalResponse; this.message = message;
         }
    }
     private static final class FinalOrderDetailsReceived implements Command {
        final OrderActor.OrderResponse orderResponse;
        FinalOrderDetailsReceived(OrderActor.OrderResponse orderResponse) { this.orderResponse = orderResponse; }
    }
    private static final class OrderCreationFailed implements Command { // Generic failure message
        final String reason;
        OrderCreationFailed(String reason) { this.reason = reason; }
    }

    // Internal message for receiving individual stock check results
    private record StockChecked(int productId, boolean sufficient) implements Command {}
    // Internal message for receiving individual deduction results
    private record StockDeducted(int productId, int quantity, ProductActor.OperationResponse response) implements Command {}


    // --- Response sent back to the original requester (e.g., Gateway via Ask). ---
    public static final class PostOrderResponse implements CborSerializable {
        public final boolean success;
        public final String message;
        public final OrderActor.OrderResponse orderResponse; // Include full order details on success

        @JsonCreator
        public PostOrderResponse(
            @JsonProperty("success") boolean success,
            @JsonProperty("message") String message,
            @JsonProperty("orderResponse") OrderActor.OrderResponse orderResponse) { // Can be null on failure
            this.success = success;
            this.message = message;
            this.orderResponse = orderResponse;
        }
         // Default constructor for Jackson
         public PostOrderResponse() {
            this(false, "Default constructor message", null); // Provide default message
        }
    }

    // --- State Variables ---
    private String orderId; // String UUID
    private int userId;
    private List<OrderActor.OrderItem> items;
    private ActorRef<PostOrderResponse> pendingReplyTo;
    private boolean discountAvailable;
    private Map<Integer, Boolean> stockCheckResults;
    private Map<Integer, ProductActor.OperationResponse> deductionResults;
    private Map<Integer, Integer> deductedQuantities;
    private int finalPrice;
    private boolean processingFailed = false;

    private final HttpClient httpClient;
    private final ClusterSharding sharding;
    private final ActorContext<Command> context;
    private final ObjectMapper objectMapper = new ObjectMapper(); // For robust JSON parsing


    // --- Constructor and Factory ---
    public static Behavior<Command> create() {
        return Behaviors.setup(PostOrder::new);
    }

    private PostOrder(ActorContext<Command> context) {
        super(context);
        this.context = context;
        this.httpClient = HttpClient.newBuilder()
                                   .connectTimeout(Duration.ofSeconds(5)) // Standard timeout for HTTP calls
                                   .build();
        this.sharding = ClusterSharding.get(context.getSystem());
        log.info("PostOrder worker actor started. Path: {}", context.getSelf().path());
    }

    // --- Receive Logic ---
    @Override
    public Receive<Command> createReceive() {
        // Initial behavior waiting for the starting command
        return newReceiveBuilder()
                .onMessage(StartOrder.class, this::onStartOrder)
                .build();
    }

    // --- Step Handlers (with added logging) ---

    // Step 1: Start Order, Validate User
    private Behavior<Command> onStartOrder(StartOrder cmd) {
        log.debug("onStartOrder: Received for userId {}", cmd.userId);
        if (processingFailed) { // Should not happen here, but safety first
             log.warn("onStartOrder: Received StartOrder but already marked as failed. Stopping.");
             return Behaviors.stopped();
        }

        // Initialize state for this order request
        this.orderId = UUID.randomUUID().toString(); // Generate String UUID
        this.userId = cmd.userId;
        this.items = cmd.items;
        this.pendingReplyTo = cmd.replyTo;
        this.discountAvailable = false; // Default
        this.stockCheckResults = new HashMap<>();
        this.deductionResults = new HashMap<>();
        this.deductedQuantities = new HashMap<>();
        this.finalPrice = 0;

        log.info("onStartOrder: Processing request for userId {}, generated orderId '{}'", userId, orderId);

        // Basic validation
        if (items == null || items.isEmpty()) {
            log.error("onStartOrder: Order '{}' rejected: No items provided.", orderId);
            return failOrder("Order must contain items.");
        }
        // Optional: More validation (e.g., check if replyTo is null)
        if (this.pendingReplyTo == null) {
             log.error("onStartOrder: Order '{}' rejected: Missing replyTo actor reference.", orderId);
             // Cannot easily failOrder here as we don't know who to reply to. Log and stop.
             return Behaviors.stopped();
        }

        // Step 1.1: Validate User via HTTP
        log.debug("onStartOrder: Sending user validation request for userId {}", userId);
        HttpRequest userRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080/users/" + userId)) // Assuming Account service runs here
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        // Use sendAsync for non-blocking HTTP call
        httpClient.sendAsync(userRequest, BodyHandlers.ofString())
            .whenCompleteAsync((resp, ex) -> { // Handle response/exception asynchronously
                log.debug("onStartOrder: User validation response received (or exception).");
                if (ex != null || resp.statusCode() != 200) {
                    String error = (ex != null) ? ex.getMessage() : "Status " + resp.statusCode();
                    log.error("onStartOrder: User validation HTTP failed for user {}: {}", userId, error, ex);
                    // Send failure message back to self to trigger failOrder logic
                    context.getSelf().tell(new UserValidationResponse(false, false, "User validation failed: " + error));
                } else {
                    try {
                         // Basic check for discount status in response body
                         boolean discountAvailed = resp.body().contains("\"discount_availed\":true");
                         log.info("onStartOrder: User {} validated. Discount already availed: {}", userId, discountAvailed);
                         // Send success message back to self
                         context.getSelf().tell(new UserValidationResponse(true, !discountAvailed, "User validated"));
                    } catch (Exception parseEx) {
                         log.error("onStartOrder: Failed to parse user validation response body: {}", parseEx.getMessage(), parseEx);
                         context.getSelf().tell(new UserValidationResponse(false, false, "User validation failed: Response parse error"));
                    }
                }
            }, context.getExecutionContext()); // Use Akka dispatcher for callback

        // Transition to waiting for the UserValidationResponse internal message
        log.debug("onStartOrder: Transitioning to wait for UserValidationResponse");
        return Behaviors.receive(Command.class)
            .onMessage(UserValidationResponse.class, this::onUserValidationResponse)
            .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder) // Handle early failure from callbacks
            .build();
    }

    // Step 2: Handle User Validation, Start Stock Check
    private Behavior<Command> onUserValidationResponse(UserValidationResponse msg) {
        log.debug("onUserValidationResponse: Received. Success: {}", msg.success);
        if (processingFailed) {
            log.warn("onUserValidationResponse: Already failed, stopping.");
            return Behaviors.stopped();
        }
        if (!msg.success) {
            log.error("onUserValidationResponse: Failing order due to unsuccessful user validation: {}", msg.message);
            return failOrder(msg.message);
        }
        this.discountAvailable = msg.discountAvailable;
        log.info("onUserValidationResponse: Discount available for order '{}': {}", orderId, this.discountAvailable);

        // Step 2.1: Start checking stock for all items concurrently using context.ask
        log.info("onUserValidationResponse: Order '{}': Starting stock check for {} items.", orderId, items.size());
        if (items.isEmpty()) { // Should have been caught earlier, but double check
             log.warn("onUserValidationResponse: No items found in order '{}' at stock check phase.", orderId);
             // If no items, technically stock check passes, proceed? Or fail? Let's proceed.
              context.getSelf().tell(new StockCheckComplete(true, "No items to check stock for"));
        } else {
            for (OrderActor.OrderItem item : items) {
                if (item.quantity <= 0) {
                    log.error("onUserValidationResponse: Invalid quantity {} for product {}", item.quantity, item.product_id);
                    return failOrder("Item " + item.product_id + " has invalid quantity: " + item.quantity);
                }
                log.debug("onUserValidationResponse: Asking stock for product {}", item.product_id);
                EntityRef<ProductActor.Command> productEntity =
                    sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(item.product_id)); // Use String ID

                // Use context.ask to query the ProductActor
                context.ask(
                    ProductActor.StockCheckResponse.class, // Expected response type
                    productEntity,                          // Target actor reference
                    Duration.ofSeconds(3),                  // Timeout for this ask
                    // Function to create the message, providing the adapter for reply
                    (ActorRef<ProductActor.StockCheckResponse> adapter) -> new ProductActor.CheckStock(item.quantity, adapter),
                    // Function to map the response/failure to an internal message for self
                    (response, failure) -> {
                        if (response != null) {
                            // Success case
                            log.debug("onUserValidationResponse: Stock check response for product {}: {}", item.product_id, response.sufficient);
                            return new StockChecked(item.product_id, response.sufficient);
                        } else {
                            // Failure case (timeout or other exception)
                            log.error("onUserValidationResponse: Stock check ask failed for product {}: {}", item.product_id, failure != null ? failure.getMessage() : "Unknown reason", failure);
                            return new StockChecked(item.product_id, false); // Treat failure as insufficient stock
                        }
                    }
                );
            }
        }

        // Transition to waiting for all StockChecked internal messages
        log.debug("onUserValidationResponse: Transitioning to wait for StockChecked results");
        return Behaviors.receive(Command.class)
            .onMessage(StockChecked.class, this::onStockCheckedResult)
            .onMessage(StockCheckComplete.class, this::onStockCheckComplete) // Handles the self-sent message if needed
            .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
            .build();
    }

     // Step 2b: Collect Stock Check Results
     private Behavior<Command> onStockCheckedResult(StockChecked msg) {
        // ADD try block around the entire handler logic
        try {
            log.debug("onStockCheckedResult: Received for product {}: sufficient={}", msg.productId, msg.sufficient);
            if (processingFailed) {
                 log.warn("onStockCheckedResult: Already failed, ignoring stock check result.");
                 // Return same to stay in the ignore state defined by failOrder
                 // or potentially stop if preferred, but same is safer for now.
                 return Behaviors.same();
            }
            stockCheckResults.put(msg.productId, msg.sufficient);

            // Check if all responses have been received
            if (stockCheckResults.size() == items.size()) {
                log.debug("onStockCheckedResult: All stock checks received for order '{}'. Aggregating results.", orderId);
                boolean allSufficient = stockCheckResults.values().stream().allMatch(Boolean::booleanValue);
                if (allSufficient) {
                     log.info("onStockCheckedResult: Order '{}': All stock checks passed.", orderId);
                     // Send message to self to proceed to the next major step
                     log.debug("onStockCheckedResult: *** BEFORE sending StockCheckComplete to self ({}) ***", context.getSelf().path()); // Keep this log
                     context.getSelf().tell(new StockCheckComplete(true, "All stock available"));
                     log.debug("onStockCheckedResult: *** AFTER sending StockCheckComplete to self ***"); // Keep this log
                } else {
                    // Find which products failed
                    List<Integer> failedProducts = stockCheckResults.entrySet().stream()
                        .filter(entry -> !entry.getValue())
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList());
                    log.error("onStockCheckedResult: Order '{}': Stock check failed for products: {}", orderId, failedProducts);
                    log.debug("onStockCheckedResult: Returning failOrder behavior due to insufficient stock."); // Add log before return
                    return failOrder("Insufficient stock for products: " + failedProducts); // Fail fast
                }
            } else {
                 // Still waiting for more responses
                 log.debug("onStockCheckedResult: Waiting for more stock checks (received {} of {})", stockCheckResults.size(), items.size());
            }
            // Stay in the current behavior (the one defined in onUserValidationResponse)
            // waiting for more StockChecked OR the StockCheckComplete message
            log.debug("onStockCheckedResult: Returning this (staying in current behavior)"); // Add log before return
            return Behaviors.receive(Command.class)
            .onMessage(StockCheckComplete.class, this::onStockCheckComplete) // Handles the self-sent message if needed
            .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
            .build();

        } catch (Exception e) { // ADD catch block for any unexpected Exception
             log.error("!!! EXCEPTION in onStockCheckedResult for order '{}' !!!", orderId, e); // Log the exception
             // If an exception happens, trigger failure explicitly
             log.debug("onStockCheckedResult: Returning failOrder behavior due to exception."); // Add log before return
             return failOrder("Internal error during stock check aggregation: " + e.getMessage());
        }
    }

    // Step 3: Handle Stock Check Completion, Start Stock Deduction
    private Behavior<Command> onStockCheckComplete(StockCheckComplete msg) {
        log.debug("onStockCheckComplete: Received. Success: {}", msg.success);
        if (processingFailed) {
             log.warn("onStockCheckComplete: Already failed, stopping.");
             return Behaviors.stopped();
        }
        if (!msg.success) { // Should usually be caught earlier by failOrder, but good safeguard
             log.error("onStockCheckComplete: Received explicit failure message: {}", msg.message);
             return failOrder(msg.message);
        }

        // Step 3.1: Start deducting stock for all items concurrently
        log.info("onStockCheckComplete: Order '{}': Starting stock deduction for {} items.", orderId, items.size());
        if (items.isEmpty()) { // Handle case where there are no items
            log.warn("onStockCheckComplete: No items in order '{}' to deduct stock from.", orderId);
            context.getSelf().tell(new StockDeductionComplete(true, 0, "No stock to deduct")); // Proceed with 0 price
        } else {
            for (OrderActor.OrderItem item : items) {
                log.debug("onStockCheckComplete: Asking deduction for product {}", item.product_id);
                EntityRef<ProductActor.Command> productEntity =
                    sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(item.product_id));

                // Use context.ask to request deduction
                context.ask(
                    ProductActor.OperationResponse.class, // Expected response type
                    productEntity,                           // Target
                    Duration.ofSeconds(5),                   // Timeout
                    // Message factory
                    (ActorRef<ProductActor.OperationResponse> adapter) -> new ProductActor.DeductStock(item.quantity, adapter),
                    // Response/failure mapper
                    (response, failure) -> {
                        if (response != null) {
                             log.debug("onStockCheckComplete: Stock deduction response for product {}: {}", item.product_id, response.success);
                             return new StockDeducted(item.product_id, item.quantity, response);
                        } else {
                            log.error("onStockCheckComplete: Stock deduction ask failed for product {}: {}", item.product_id, failure != null ? failure.getMessage() : "Unknown reason", failure);
                            // Create a failure response to trigger rollback in the next step
                            return new StockDeducted(item.product_id, item.quantity, new ProductActor.OperationResponse(false, "Timeout or communication error during deduction", 0));
                        }
                    }
                );
            }
        }

        // Transition to waiting for all StockDeducted internal messages
        log.debug("onStockCheckComplete: Transitioning to wait for StockDeducted results");
        return Behaviors.receive(Command.class)
            .onMessage(StockDeducted.class, this::onStockDeductedResult)
            .onMessage(StockDeductionComplete.class, this::onStockDeductionComplete) // Handles self-sent message
            .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
            .build();
    }

     // Step 3b: Collect Stock Deduction Results
    private Behavior<Command> onStockDeductedResult(StockDeducted msg) {
         log.debug("onStockDeductedResult: Received for product {}: success={}", msg.productId, msg.response.success);
         if (processingFailed) {
            log.warn("onStockDeductedResult: Already failed, ignoring.");
            return Behaviors.same();
         }
         deductionResults.put(msg.productId, msg.response);

         if (msg.response.success) {
            // Track successfully deducted item/quantity for potential rollback
            deductedQuantities.put(msg.productId, msg.quantity);
            this.finalPrice += msg.response.priceDeducted; // Accumulate price
            log.debug("onStockDeductedResult: Product {} deducted. Accumulated price: {}", msg.productId, this.finalPrice);
         } else {
            // Fail fast if any deduction fails
            log.error("onStockDeductedResult: Order '{}': Stock deduction failed for product {}: {}", orderId, msg.productId, msg.response.message);
            return failOrder("Stock deduction failed for product " + msg.productId + ": " + msg.response.message);
         }

         // Check if all responses received
         if (deductionResults.size() == items.size()) {
            log.info("onStockDeductedResult: Order '{}': All stock deductions received. Accumulated price before discount: {}", orderId, this.finalPrice);
            // Apply discount if available
            if (this.discountAvailable) {
                int originalPrice = this.finalPrice;
                this.finalPrice = (int) (this.finalPrice * 0.9); // Apply 10% discount
                log.info("onStockDeductedResult: Order '{}': Applying 10% discount. Price reduced from {} to {}", orderId, originalPrice, this.finalPrice);
            }
            // Send message to self to proceed
            log.debug("onStockDeductedResult: Sending StockDeductionComplete to self.");
            context.getSelf().tell(new StockDeductionComplete(true, this.finalPrice, "Deduction successful"));
         } else {
              log.debug("onStockDeductedResult: Waiting for more stock deductions (received {} of {})", deductionResults.size(), items.size());
         }
         // Stay in the current behavior waiting for more results or the completion message
        return Behaviors.receive(Command.class)
        .onMessage(StockDeductionComplete.class, this::onStockDeductionComplete) // Handles self-sent message
        .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
        .build();

    }

    // Step 4: Handle Deduction Completion, Check Wallet
    private Behavior<Command> onStockDeductionComplete(StockDeductionComplete msg) {
        log.debug("onStockDeductionComplete: Received self-message. Success: {}, Final Price: {}", msg.success, msg.finalPrice);
        if (processingFailed) {
             log.warn("onStockDeductionComplete: Already failed, stopping.");
             return Behaviors.stopped();
        }
        if (!msg.success) { // Should be caught earlier, but safeguard
            log.error("onStockDeductionComplete: Received explicit failure message: {}", msg.message);
            return failOrder(msg.message);
        }
        this.finalPrice = msg.finalPrice; // Store final price

        // Step 4.1: Check Wallet via HTTP
        log.info("onStockDeductionComplete: Order '{}': Checking wallet balance for user {}. Required amount: {}", orderId, userId, finalPrice);
        HttpRequest walletCheckRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:8082/wallets/" + userId)) // Assuming Wallet service runs here
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

        httpClient.sendAsync(walletCheckRequest, BodyHandlers.ofString())
            .whenCompleteAsync((resp, ex) -> {
                 log.debug("onStockDeductionComplete: Wallet check response received (or exception).");
                 if (ex != null || resp.statusCode() != 200) {
                    String error = (ex != null) ? ex.getMessage() : "Status " + resp.statusCode();
                    log.error("onStockDeductionComplete: Wallet check HTTP failed for user {}: {}", userId, error, ex);
                    context.getSelf().tell(new WalletCheckResponse(false, "Wallet check communication failed: " + error));
                } else {
                    try {
                        String body = resp.body();
                        int balance = parseWalletBalanceRobust(body); // Use robust helper
                        log.info("onStockDeductionComplete: Wallet balance for user {}: {}", userId, balance);
                        if (balance >= finalPrice) {
                            context.getSelf().tell(new WalletCheckResponse(true, "Sufficient balance"));
                        } else {
                            log.error("onStockDeductionComplete: Insufficient wallet balance for user {}. Required: {}, Available: {}", userId, finalPrice, balance);
                            context.getSelf().tell(new WalletCheckResponse(false, "Insufficient wallet balance"));
                        }
                    } catch (Exception e) {
                         log.error("onStockDeductionComplete: Failed to parse wallet balance response for user {}: {}", userId, e.getMessage(), e);
                         context.getSelf().tell(new WalletCheckResponse(false, "Failed to parse wallet balance response"));
                    }
                }
            }, context.getExecutionContext()); // Use Akka dispatcher

         log.debug("onStockDeductionComplete: Transitioning to wait for WalletCheckResponse");
         return Behaviors.receive(Command.class)
            .onMessage(WalletCheckResponse.class, this::onWalletCheckResponse)
            .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
            .build();
    }

    // Step 5: Handle Wallet Check, Debit Wallet
    private Behavior<Command> onWalletCheckResponse(WalletCheckResponse msg) {
        log.debug("onWalletCheckResponse: Received. Success: {}", msg.success);
        if (processingFailed) {
            log.warn("onWalletCheckResponse: Already failed, stopping.");
            return Behaviors.stopped();
        }
        if (!msg.success) {
            log.error("onWalletCheckResponse: Failing order due to wallet check failure: {}", msg.message);
            return failOrder(msg.message); // Triggers product rollback
        }

        // Step 5.1: Debit Wallet via HTTP
        log.info("onWalletCheckResponse: Order '{}': Wallet check successful for user {}. Debiting amount: {}", orderId, userId, finalPrice);
        String debitJson = String.format("{\"action\": \"debit\", \"amount\": %d}", finalPrice);
        HttpRequest debitRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8082/wallets/" + userId))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(debitJson))
                .build();

        httpClient.sendAsync(debitRequest, BodyHandlers.ofString())
            .whenCompleteAsync((resp, ex) -> {
                 log.debug("onWalletCheckResponse: Wallet debit response received (or exception).");
                 if (ex != null || resp.statusCode() != 200) {
                    String error = (ex != null) ? ex.getMessage() : "Status " + resp.statusCode();
                    log.error("onWalletCheckResponse: Wallet debit HTTP failed for user {}: {}", userId, error, ex);
                    context.getSelf().tell(new WalletDebitResponse(false, "Wallet debit failed: " + error));
                } else {
                    log.info("onWalletCheckResponse: Wallet debited successfully for user {}. Amount: {}", userId, finalPrice);
                    context.getSelf().tell(new WalletDebitResponse(true, "Wallet debited successfully"));
                }
            }, context.getExecutionContext()); // Use Akka dispatcher

         log.debug("onWalletCheckResponse: Transitioning to wait for WalletDebitResponse");
         return Behaviors.receive(Command.class)
            .onMessage(WalletDebitResponse.class, this::onWalletDebitResponse)
            .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
            .build();
    }

    // Step 6: Handle Wallet Debit, Update Discount (if applicable)
    private Behavior<Command> onWalletDebitResponse(WalletDebitResponse msg) {
        log.debug("onWalletDebitResponse: Received. Success: {}", msg.success);
        if (processingFailed) {
            log.warn("onWalletDebitResponse: Already failed, stopping.");
            return Behaviors.stopped();
        }
        if (!msg.success) {
             log.error("onWalletDebitResponse: Failing order due to wallet debit failure: {}", msg.message);
             // Wallet debit failed AFTER stock deduction. CRITICAL. Need rollback.
             return failOrder(msg.message);
        }

        // Step 6.1: Update Discount Status via HTTP (if applicable)
        if (this.discountAvailable) {
            log.info("onWalletDebitResponse: Order '{}': Updating discount availed status for user {}", orderId, userId);
            String discountJson = String.format("{\"id\": %d, \"discount_availed\": true}", userId);
            HttpRequest discountRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8080/users/" + userId + "/discount")) // Assuming Account service endpoint
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(discountJson))
                    .build();

            httpClient.sendAsync(discountRequest, BodyHandlers.ofString())
                .whenCompleteAsync((resp, ex) -> {
                     log.debug("onWalletDebitResponse: Discount update response received (or exception).");
                     if (ex != null || resp.statusCode() != 200) {
                        String error = (ex != null) ? ex.getMessage() : "Status " + resp.statusCode();
                        // Log error but proceed anyway (business decision)
                        log.error("onWalletDebitResponse: Discount update HTTP failed for user {}: {}. Proceeding with order creation.", userId, error, ex);
                        context.getSelf().tell(new DiscountUpdateResponse(false, "Discount update failed: " + error));
                    } else {
                         log.info("onWalletDebitResponse: Discount status updated successfully for user {}", userId);
                         context.getSelf().tell(new DiscountUpdateResponse(true, "Discount updated"));
                    }
                }, context.getExecutionContext()); // Use Akka dispatcher

            log.debug("onWalletDebitResponse: Transitioning to wait for DiscountUpdateResponse");
            return Behaviors.receive(Command.class)
                .onMessage(DiscountUpdateResponse.class, this::onDiscountUpdateResponse)
                .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
                .build();
        } else {
            // No discount to update, skip to next step
            log.info("onWalletDebitResponse: Order '{}': No discount applicable. Proceeding to initialize order entity.", orderId);
            context.getSelf().tell(new DiscountUpdateResponse(true, "No discount applicable")); // Trigger next step immediately
            // Define behavior to handle this self-sent message
            return Behaviors.receive(Command.class)
                .onMessage(DiscountUpdateResponse.class, this::onDiscountUpdateResponse)
                .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
                .build();
        }
    }

     // Step 7: Handle Discount Update, Initialize Order Actor
    private Behavior<Command> onDiscountUpdateResponse(DiscountUpdateResponse msg) {
        log.debug("onDiscountUpdateResponse: Received. Success: {}", msg.success);
        if (processingFailed) {
             log.warn("onDiscountUpdateResponse: Already failed, stopping.");
             return Behaviors.stopped();
        }
        if (!msg.success) {
             // Log warning but continue, as decided in previous step
             log.warn("onDiscountUpdateResponse: Order '{}': Proceeding despite discount update failure: {}", orderId, msg.message);
        }

        // Step 7.1: Initialize Order Actor via context.ask
        log.info("onDiscountUpdateResponse: Order '{}': Initializing OrderActor entity.", orderId);
        // Use the String orderId for sharding
        EntityRef<OrderActor.Command> orderEntity =
                sharding.entityRefFor(OrderActor.TypeKey, orderId);

        // REMOVED: int orderIdInt calculation

        log.debug("onDiscountUpdateResponse: Asking OrderActor to initialize with String orderId '{}'", orderId);
        context.ask(
            OrderActor.OperationResponse.class, // Expected response type
            orderEntity,                          // Target actor
            Duration.ofSeconds(5),                // Timeout for this ask
            // CORRECTED: Create InitializeOrder message with String orderId
            (ActorRef<OrderActor.OperationResponse> adapter) ->
                new OrderActor.InitializeOrder(orderId, userId, items, finalPrice, "PLACED", adapter),
            // Map response/failure to internal message
            (response, failure) -> {
                 if (response != null && response.success) {
                     log.debug("onDiscountUpdateResponse: Order init ask succeeded.");
                     return new OrderInitializationResponse(true, response, "Order entity initialized");
                 } else {
                     String error = "Unknown Error";
                     if (failure != null) {
                         error = "Ask Failure: " + failure.getMessage();
                     } else if (response != null) {
                         error = "Actor Reply Failure: " + response.message;
                     }
                     // Log full details of failure
                     log.error("onDiscountUpdateResponse: OrderActor initialization ask failed for order '{}': {}", orderId, error, failure);
                     return new OrderInitializationResponse(false, response, "Order entity initialization failed: " + error);
                 }
            }
        );

        log.debug("onDiscountUpdateResponse: Transitioning to wait for OrderInitializationResponse");
        return Behaviors.receive(Command.class)
            .onMessage(OrderInitializationResponse.class, this::onOrderInitializationResponse)
            .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
            .build();
    }

    // Step 8: Handle Order Initialization, Get Final Order Details
    private Behavior<Command> onOrderInitializationResponse(OrderInitializationResponse msg) {
        log.debug("onOrderInitializationResponse: Received. Success: {}", msg.success);
        if (processingFailed) {
            log.warn("onOrderInitializationResponse: Already failed, stopping.");
            return Behaviors.stopped();
        }
        if (!msg.success) {
             // Order failed AFTER wallet debit. Critical.
             log.error("CRITICAL: Order '{}' failed during OrderActor initialization: {}. Wallet debit might need manual rollback.", orderId, msg.message);
             // Trigger product rollback
             return failOrder("Order entity initialization failed: " + msg.message);
        }

        // Step 8.1: Get final order details using context.ask
        log.info("onOrderInitializationResponse: Order '{}': OrderActor initialized successfully. Fetching final order details.", orderId);
        // Use String orderId for sharding
        EntityRef<OrderActor.Command> orderEntity =
                sharding.entityRefFor(OrderActor.TypeKey, orderId);

        log.debug("onOrderInitializationResponse: Asking OrderActor for final details using GetOrder.");
        context.ask(
            OrderActor.OrderResponse.class, // Expected response type
            orderEntity,                    // Target
            Duration.ofSeconds(5),          // Timeout
            // Message factory
            (ActorRef<OrderActor.OrderResponse> adapter) -> new OrderActor.GetOrder(adapter),
            // Response/failure mapper
            (response, failure) -> {
                 if (response != null) {
                     // Ensure the response ID matches if needed (optional sanity check)
                     if (!response.order_id.equals(this.orderId)) {
                          log.error("onOrderInitializationResponse: Received FinalOrderDetails but ID mismatch! Expected '{}', Got '{}'. Treating as failure.", this.orderId, response.order_id);
                           return new OrderCreationFailed("Internal error: Mismatched order details retrieved.");
                     }
                     log.debug("onOrderInitializationResponse: GetOrder ask succeeded.");
                     return new FinalOrderDetailsReceived(response);
                 } else {
                     log.error("onOrderInitializationResponse: Failed to get final order details for order '{}': {}", orderId, failure != null ? failure.getMessage() : "Unknown reason", failure);
                     // Decide policy: Fail completely or reply success but without full details?
                     // Let's fail completely for now.
                     return new OrderCreationFailed("Failed to retrieve final order details after creation (ID: " + orderId + ")");
                 }
            }
        );

         log.debug("onOrderInitializationResponse: Transitioning to wait for FinalOrderDetailsReceived");
         return Behaviors.receive(Command.class)
            .onMessage(FinalOrderDetailsReceived.class, this::onFinalOrderDetailsReceived)
            .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
            .build();
    }

     // Step 9: Final Success - Reply and Stop
    private Behavior<Command> onFinalOrderDetailsReceived(FinalOrderDetailsReceived msg) {
        log.debug("onFinalOrderDetailsReceived: Received final details for order '{}'", msg.orderResponse.order_id);
        if (processingFailed) {
             log.warn("onFinalOrderDetailsReceived: Processing already marked as failed, stopping.");
             // We might have already replied with failure, avoid replying again.
             return Behaviors.stopped();
        }
        // Final success step
        log.info("Order '{}' created successfully. Replying to original requester.", msg.orderResponse.order_id);
        if (pendingReplyTo != null) {
             pendingReplyTo.tell(new PostOrderResponse(true, "Order created successfully", msg.orderResponse));
        } else {
             log.error("onFinalOrderDetailsReceived: Cannot reply success for order '{}', pendingReplyTo is null!", msg.orderResponse.order_id);
        }
        log.debug("onFinalOrderDetailsReceived: Stopping actor after successful reply.");
        return Behaviors.stopped(); // Success, stop the worker
    }


    // --- Failure Handling ---

    // Centralized method to trigger the failure sequence
    private Behavior<Command> failOrder(String reason) {
         log.debug("failOrder: Initiating failure process for order '{}'. Reason: {}", orderId, reason);
         if (processingFailed) {
             log.warn("failOrder: Failure already triggered for order '{}', ignoring duplicate call.", orderId);
             return Behaviors.same(); // Avoid duplicate rollbacks/replies
         }
         processingFailed = true; // Mark as failed to prevent further actions
         log.error("Order '{}' failed: {}", orderId, reason);

         // Perform compensation logic (rollback stock)
         rollbackProductStock("Order failure: " + reason);

         // Send message to self to trigger final reply and stop
         log.debug("failOrder: Sending OrderCreationFailed to self for order '{}'.", orderId);
         context.getSelf().tell(new OrderCreationFailed(reason));

         // Transition to a final state that only handles OrderCreationFailed and ignores others
         log.debug("failOrder: Transitioning to final failure state for order '{}'.", orderId);
         return Behaviors.receive(Command.class)
             .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
              // Ignore any further internal messages that might arrive late from async operations
             .onAnyMessage(msg -> {
                 log.debug("Order '{}' ignoring message {} after failure initiated.", orderId, msg.getClass().getSimpleName());
                 return Behaviors.same();
             })
             .build();
    }

     // Handles the self-sent failure message to reply and stop
    private Behavior<Command> finalizeFailedOrder(OrderCreationFailed msg) {
        log.debug("finalizeFailedOrder: Finalizing failed order '{}'. Replying with failure.", orderId);
        if (pendingReplyTo != null) {
             pendingReplyTo.tell(new PostOrderResponse(false, msg.reason, null));
        } else {
             // This should ideally not happen if StartOrder validated replyTo
             log.error("finalizeFailedOrder: Cannot reply failure for order '{}', pendingReplyTo is null!", orderId);
        }
        log.debug("finalizeFailedOrder: Stopping actor after failure reply for order '{}'.", orderId);
        return Behaviors.stopped(); // Stop the worker
    }


    // Helper method for product stock rollback (compensation)
    private void rollbackProductStock(String reason) {
         log.warn("rollbackProductStock: Order '{}': Rolling back product stock due to failure: {}", orderId, reason);
         if (deductedQuantities == null || deductedQuantities.isEmpty()) {
             log.info("rollbackProductStock: Order '{}': No product stock deductions to roll back.", orderId);
             return;
         }
         log.debug("rollbackProductStock: Rolling back deductions for products: {}", deductedQuantities.keySet());
         for (Map.Entry<Integer, Integer> entry : deductedQuantities.entrySet()) {
             int prodId = entry.getKey();
             int qty = entry.getValue();
             if (qty > 0) {
                 try {
                     // Use String product ID for sharding
                     EntityRef<ProductActor.Command> productEntity =
                         sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(prodId));
                     // Send AddStock message (fire-and-forget)
                     productEntity.tell(new ProductActor.AddStock(qty));
                     log.info("rollbackProductStock: Order '{}': Sent AddStock rollback for product {} (quantity: {})", orderId, prodId, qty);
                 } catch (Exception e) {
                     // Log error but continue trying to roll back others
                     log.error("rollbackProductStock: Order '{}': Error getting EntityRef or telling AddStock for product {}: {}", orderId, prodId, e.getMessage(), e);
                 }
             }
         }
         // Clear map AFTER initiating rollback messages
         // NOTE: This is best-effort rollback. For production, consider patterns like Saga with explicit compensation steps.
         deductedQuantities.clear();
    }

    // Helper method for robust JSON parsing of wallet balance
     private int parseWalletBalanceRobust(String responseBody) throws Exception {
        log.debug("parseWalletBalanceRobust: Attempting to parse wallet balance response body.");
        if (responseBody == null || responseBody.trim().isEmpty()) {
             log.error("parseWalletBalanceRobust: Wallet balance response body is null or empty.");
             throw new Exception("Wallet balance response body is null or empty.");
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.has("balance") && root.get("balance").isInt()) { // More specific check
                 int balance = root.get("balance").asInt();
                 log.debug("parseWalletBalanceRobust: Parsed balance from JSON 'balance' field: {}", balance);
                 return balance;
            } else {
                 log.warn("parseWalletBalanceRobust: JSON field 'balance' not found or not an integer in body: '{}'. Attempting to parse whole body as int.", responseBody);
                 // Fallback: Try parsing the whole body as an integer
                 return Integer.parseInt(responseBody.trim());
            }
        } catch (NumberFormatException nfe) {
             log.error("parseWalletBalanceRobust: Failed to parse response body as integer: '{}'", responseBody, nfe);
             throw new Exception("Wallet balance response is not valid JSON with an integer 'balance' field, nor is it a simple integer: " + responseBody, nfe);
        } catch (Exception e) { // Catch other JSON parsing errors
             log.error("parseWalletBalanceRobust: Error parsing wallet balance JSON: '{}'", responseBody, e);
             throw new Exception("Error parsing wallet balance JSON: " + e.getMessage(), e);
        }
    }
}