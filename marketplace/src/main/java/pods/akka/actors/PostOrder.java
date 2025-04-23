package pods.akka.actors;

import akka.actor.typed.Behavior;
import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import com.fasterxml.jackson.annotation.JsonCreator; // Needed for Jackson
import com.fasterxml.jackson.annotation.JsonProperty; // Needed for Jackson
import pods.akka.CborSerializable; // Import CborSerializable

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashMap; // Import HashMap
import java.util.UUID;
import java.util.concurrent.CompletableFuture; // Import CompletableFuture
import java.util.concurrent.CompletionStage; // Import CompletionStage
import java.util.stream.Collectors; // Import Collectors

// PHASE 2: Worker actor, likely created via Router. Needs serialization for messages.
public class PostOrder extends AbstractBehavior<PostOrder.Command> {

    // PHASE 2 CHANGE: Command interface must be serializable
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

    // Internal messages for state transitions
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


    // Response sent back to the original requester (e.g., Gateway via Ask).
    // PHASE 2 CHANGE: Response must be serializable
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
            this(false, "Default constructor", null);
        }
    }

    // --- State Variables ---
    private String orderId; // PHASE 2 CHANGE: Use String for UUID
    private int userId;
    private List<OrderActor.OrderItem> items;
    private ActorRef<PostOrderResponse> pendingReplyTo;

    // State for multi-step process
    private boolean discountAvailable;
    private Map<Integer, Boolean> stockCheckResults;
    private Map<Integer, ProductActor.OperationResponse> deductionResults;
    private Map<Integer, Integer> deductedQuantities; // Track successful deductions for rollback
    private int finalPrice;
    private boolean processingFailed = false; // Flag to prevent further steps on failure

    private final HttpClient httpClient;
    private final ClusterSharding sharding;
    private final ActorContext<Command> context; // Store context for async callbacks

    // --- Constructor and Factory ---
    public static Behavior<Command> create() {
        return Behaviors.setup(PostOrder::new);
    }

    private PostOrder(ActorContext<Command> context) {
        super(context);
        this.context = context; // Store context
        this.httpClient = HttpClient.newBuilder()
                                   .connectTimeout(Duration.ofSeconds(5))
                                   .build();
        this.sharding = ClusterSharding.get(context.getSystem());
        context.getLog().info("PostOrder worker actor started.");
    }

    // --- Receive Logic ---
    @Override
    public Receive<Command> createReceive() {
        // Initial state: waiting for StartOrder
        return newReceiveBuilder()
                .onMessage(StartOrder.class, this::onStartOrder)
                .build();
    }

    // --- Step Handlers ---

    // Step 1: Start Order, Validate User
    private Behavior<Command> onStartOrder(StartOrder cmd) {
        if (processingFailed) return Behaviors.stopped(); // Should not happen here, but safety

        // PHASE 2 CHANGE: Generate String UUID for Order ID
        this.orderId = UUID.randomUUID().toString();
        this.userId = cmd.userId;
        this.items = cmd.items;
        this.pendingReplyTo = cmd.replyTo;
        this.discountAvailable = false; // Default
        this.stockCheckResults = new HashMap<>();
        this.deductionResults = new HashMap<>();
        this.deductedQuantities = new HashMap<>();
        this.finalPrice = 0;

        context.getLog().info("PostOrder processing request for userId {}, generated orderId {}", userId, orderId);

        if (items == null || items.isEmpty()) {
            context.getLog().error("Order {} rejected: No items provided.", orderId);
            return failOrder("Order must contain items.");
        }

        // PHASE 2 CHANGE: Use localhost
        HttpRequest userRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080/users/" + userId)) // Use localhost
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        // Asynchronously validate user
        httpClient.sendAsync(userRequest, BodyHandlers.ofString())
            .whenCompleteAsync((resp, ex) -> {
                if (ex != null || resp.statusCode() != 200) {
                    String error = (ex != null) ? ex.getMessage() : "Status " + resp.statusCode();
                    context.getLog().error("User validation failed for user {}: {}", userId, error);
                    context.getSelf().tell(new UserValidationResponse(false, false, "User validation failed: " + error));
                } else {
                    boolean discountAvailed = resp.body().contains("\"discount_availed\":true"); // Basic JSON check
                    context.getLog().info("User {} validated. Discount already availed: {}", userId, discountAvailed);
                    context.getSelf().tell(new UserValidationResponse(true, !discountAvailed, "User validated"));
                }
            }, context.getExecutionContext());

        // Transition to waiting for user validation response
        return Behaviors.receive(Command.class)
            .onMessage(UserValidationResponse.class, this::onUserValidationResponse)
             .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder) // Handle early failure
            .build();
    }

    // Step 2: Handle User Validation, Start Stock Check
    private Behavior<Command> onUserValidationResponse(UserValidationResponse msg) {
         if (processingFailed) return Behaviors.stopped();
         if (!msg.success) {
             return failOrder(msg.message);
         }
         this.discountAvailable = msg.discountAvailable;
         context.getLog().info("Discount available for order {}: {}", orderId, this.discountAvailable);

         // Start checking stock for all items concurrently
         context.getLog().info("Order {}: Starting stock check for {} items.", orderId, items.size());
         for (OrderActor.OrderItem item : items) {
             if (item.quantity <= 0) {
                 return failOrder("Item " + item.product_id + " has invalid quantity: " + item.quantity);
             }
             EntityRef<ProductActor.Command> productEntity =
                 sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(item.product_id));

             // Ask each product actor
             context.ask(
                 ProductActor.StockCheckResponse.class,
                 productEntity,
                 Duration.ofSeconds(3), // Shorter timeout for stock check
                 (ActorRef<ProductActor.StockCheckResponse> adapter) -> new ProductActor.CheckStock(item.quantity, adapter),
                 (response, failure) -> {
                     if (response != null) {
                         return new StockChecked(item.product_id, response.sufficient);
                     } else {
                         context.getLog().error("Stock check failed for product {}: {}", item.product_id, failure);
                         return new StockChecked(item.product_id, false); // Treat timeout/failure as insufficient stock
                     }
                 }
             );
         }

         // Transition to waiting for all stock check responses
         return Behaviors.receive(Command.class)
             .onMessage(StockChecked.class, this::onStockCheckedResult)
             .onMessage(StockCheckComplete.class, this::onStockCheckComplete) // If aggregation logic sends this
             .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder) // Handle early failure
             .build();
    }

     // Step 2b: Collect Stock Check Results
    private Behavior<Command> onStockCheckedResult(StockChecked msg) {
        if (processingFailed) return Behaviors.stopped();
        context.getLog().debug("Order {}: Received stock check for product {}: sufficient={}", orderId, msg.productId, msg.sufficient);
        stockCheckResults.put(msg.productId, msg.sufficient);

        // Check if all responses are received
        if (stockCheckResults.size() == items.size()) {
            boolean allSufficient = stockCheckResults.values().stream().allMatch(Boolean::booleanValue);
            if (allSufficient) {
                 context.getLog().info("Order {}: All stock checks passed.", orderId);
                 context.getSelf().tell(new StockCheckComplete(true, "All stock available"));
            } else {
                List<Integer> failedProducts = stockCheckResults.entrySet().stream()
                    .filter(entry -> !entry.getValue())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
                context.getLog().error("Order {}: Stock check failed for products: {}", orderId, failedProducts);
                // Trigger failure immediately
                return failOrder("Insufficient stock for products: " + failedProducts);
            }
        }
        // Remain in the same state, waiting for more stock check results
        return Behaviors.same();
    }


    // Step 3: Handle Stock Check Completion, Start Stock Deduction
    private Behavior<Command> onStockCheckComplete(StockCheckComplete msg) {
        if (processingFailed) return Behaviors.stopped();
        if (!msg.success) { // Should have been caught by failOrder earlier, but double-check
            return failOrder(msg.message);
        }

        // Start deducting stock for all items concurrently
        context.getLog().info("Order {}: Starting stock deduction for {} items.", orderId, items.size());
        for (OrderActor.OrderItem item : items) {
            EntityRef<ProductActor.Command> productEntity =
                sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(item.product_id));

            // Ask product actor to deduct stock
            context.ask(
                ProductActor.OperationResponse.class,
                productEntity,
                Duration.ofSeconds(5),
                (ActorRef<ProductActor.OperationResponse> adapter) -> new ProductActor.DeductStock(item.quantity, adapter),
                (response, failure) -> {
                    if (response != null) {
                        return new StockDeducted(item.product_id, item.quantity, response);
                    } else {
                        context.getLog().error("Stock deduction failed for product {}: {}", item.product_id, failure);
                        // Create a failure response to trigger rollback
                        return new StockDeducted(item.product_id, item.quantity, new ProductActor.OperationResponse(false, "Timeout or communication error", 0));
                    }
                }
            );
        }

        // Transition to waiting for all deduction responses
        return Behaviors.receive(Command.class)
            .onMessage(StockDeducted.class, this::onStockDeductedResult)
             .onMessage(StockDeductionComplete.class, this::onStockDeductionComplete)
            .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
            .build();
    }

     // Step 3b: Collect Stock Deduction Results
    private Behavior<Command> onStockDeductedResult(StockDeducted msg) {
         if (processingFailed) return Behaviors.stopped();
         context.getLog().debug("Order {}: Received stock deduction for product {}: success={}, priceDeducted={}",
                orderId, msg.productId, msg.response.success, msg.response.priceDeducted);
         deductionResults.put(msg.productId, msg.response);

         if (msg.response.success) {
            // Track successfully deducted items/quantities for potential rollback
            deductedQuantities.put(msg.productId, msg.quantity);
            this.finalPrice += msg.response.priceDeducted; // Accumulate price
         } else {
            // If any deduction fails, mark the entire process as failed immediately
            context.getLog().error("Order {}: Stock deduction failed for product {}: {}", orderId, msg.productId, msg.response.message);
             return failOrder("Stock deduction failed for product " + msg.productId + ": " + msg.response.message);
         }

         // Check if all responses received
         if (deductionResults.size() == items.size()) {
            // Since we fail fast on the first error, reaching here means all deductions succeeded
            context.getLog().info("Order {}: All stock deductions successful. Accumulated price: {}", orderId, this.finalPrice);

            // Apply discount if available
            if (this.discountAvailable) {
                int discountedPrice = (int) (this.finalPrice * 0.9); // Apply 10% discount
                 context.getLog().info("Order {}: Applying 10% discount. Price reduced from {} to {}", orderId, this.finalPrice, discountedPrice);
                 this.finalPrice = discountedPrice;
            }
            context.getSelf().tell(new StockDeductionComplete(true, this.finalPrice, "Deduction successful"));
         }
         // Remain waiting for more deduction results
        return Behaviors.same();
    }

    // Step 4: Handle Deduction Completion, Check Wallet
    private Behavior<Command> onStockDeductionComplete(StockDeductionComplete msg) {
        if (processingFailed) return Behaviors.stopped();
        if (!msg.success) { // Should have been caught earlier
            return failOrder(msg.message);
        }
        this.finalPrice = msg.finalPrice; // Final price after potential discount

        context.getLog().info("Order {}: Checking wallet balance for user {}. Required amount: {}", orderId, userId, finalPrice);

        // PHASE 2 CHANGE: Use localhost
        HttpRequest walletCheckRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:8082/wallets/" + userId)) // Use localhost
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

        httpClient.sendAsync(walletCheckRequest, BodyHandlers.ofString())
            .whenCompleteAsync((resp, ex) -> {
                if (ex != null || resp.statusCode() != 200) {
                    String error = (ex != null) ? ex.getMessage() : "Status " + resp.statusCode();
                    context.getLog().error("Wallet check failed for user {}: {}", userId, error);
                    context.getSelf().tell(new WalletCheckResponse(false, "Wallet check communication failed: " + error));
                } else {
                    try {
                        // Basic JSON parsing - needs improvement for robustness
                        String body = resp.body();
                        int balance = parseWalletBalance(body); // Use helper
                        context.getLog().info("Wallet balance for user {}: {}", userId, balance);
                        if (balance >= finalPrice) {
                            context.getSelf().tell(new WalletCheckResponse(true, "Sufficient balance"));
                        } else {
                            context.getLog().error("Insufficient wallet balance for user {}. Required: {}, Available: {}", userId, finalPrice, balance);
                            context.getSelf().tell(new WalletCheckResponse(false, "Insufficient wallet balance"));
                        }
                    } catch (Exception e) {
                         context.getLog().error("Failed to parse wallet balance response for user {}: {}", userId, e.getMessage());
                         context.getSelf().tell(new WalletCheckResponse(false, "Failed to parse wallet balance response"));
                    }
                }
            }, context.getExecutionContext());

         // Transition to waiting for wallet check result
         return Behaviors.receive(Command.class)
            .onMessage(WalletCheckResponse.class, this::onWalletCheckResponse)
            .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
            .build();
    }

    // Step 5: Handle Wallet Check, Debit Wallet
    private Behavior<Command> onWalletCheckResponse(WalletCheckResponse msg) {
        if (processingFailed) return Behaviors.stopped();
        if (!msg.success) {
            // Wallet check failed (communication or insufficient funds), trigger rollback
            return failOrder(msg.message);
        }

        context.getLog().info("Order {}: Wallet check successful for user {}. Debiting amount: {}", orderId, userId, finalPrice);

        String debitJson = String.format("{\"action\": \"debit\", \"amount\": %d}", finalPrice);
        // PHASE 2 CHANGE: Use localhost
        HttpRequest debitRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8082/wallets/" + userId)) // Use localhost
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(debitJson))
                .build();

        httpClient.sendAsync(debitRequest, BodyHandlers.ofString())
            .whenCompleteAsync((resp, ex) -> {
                 if (ex != null || resp.statusCode() != 200) {
                    String error = (ex != null) ? ex.getMessage() : "Status " + resp.statusCode();
                    context.getLog().error("Wallet debit failed for user {}: {}", userId, error);
                    context.getSelf().tell(new WalletDebitResponse(false, "Wallet debit failed: " + error));
                } else {
                    context.getLog().info("Wallet debited successfully for user {}. Amount: {}", userId, finalPrice);
                    context.getSelf().tell(new WalletDebitResponse(true, "Wallet debited successfully"));
                }
            }, context.getExecutionContext());

         // Transition to waiting for wallet debit result
         return Behaviors.receive(Command.class)
            .onMessage(WalletDebitResponse.class, this::onWalletDebitResponse)
            .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
            .build();
    }

    // Step 6: Handle Wallet Debit, Update Discount (if applicable)
    private Behavior<Command> onWalletDebitResponse(WalletDebitResponse msg) {
        if (processingFailed) return Behaviors.stopped();
        if (!msg.success) {
            // Wallet debit failed, trigger rollback
            return failOrder(msg.message);
        }

        // If discount was available and applied, update user status
        if (this.discountAvailable) {
            context.getLog().info("Order {}: Updating discount availed status for user {}", orderId, userId);
            String discountJson = String.format("{\"id\": %d, \"discount_availed\": true}", userId);
            // PHASE 2 CHANGE: Use localhost
            HttpRequest discountRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8080/users/" + userId + "/discount")) // Use localhost
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(discountJson))
                    .build();

            httpClient.sendAsync(discountRequest, BodyHandlers.ofString())
                .whenCompleteAsync((resp, ex) -> {
                     if (ex != null || resp.statusCode() != 200) {
                        // Log error, but potentially proceed with order creation anyway?
                        // Decide on failure policy here. For now, log and signal non-fatal failure.
                        String error = (ex != null) ? ex.getMessage() : "Status " + resp.statusCode();
                        context.getLog().error("Discount update failed for user {}: {}. Proceeding with order creation.", userId, error);
                        context.getSelf().tell(new DiscountUpdateResponse(false, "Discount update failed: " + error));
                    } else {
                         context.getLog().info("Discount status updated successfully for user {}", userId);
                         context.getSelf().tell(new DiscountUpdateResponse(true, "Discount updated"));
                    }
                }, context.getExecutionContext());

            // Transition to waiting for discount update response
             return Behaviors.receive(Command.class)
                .onMessage(DiscountUpdateResponse.class, this::onDiscountUpdateResponse)
                .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
                .build();
        } else {
            // No discount applied, proceed directly to order initialization
            context.getLog().info("Order {}: No discount applied. Proceeding to initialize order entity.", orderId);
            // Simulate a successful discount update step to proceed
            context.getSelf().tell(new DiscountUpdateResponse(true, "No discount applicable"));
             return Behaviors.receive(Command.class)
                .onMessage(DiscountUpdateResponse.class, this::onDiscountUpdateResponse) // Will immediately receive the self-sent message
                .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
                .build();
        }
    }

     // Step 7: Handle Discount Update, Initialize Order Actor
    private Behavior<Command> onDiscountUpdateResponse(DiscountUpdateResponse msg) {
        if (processingFailed) return Behaviors.stopped();
        // Log if discount update failed, but proceed anyway as per current logic
        if (!msg.success) {
             context.getLog().warn("Order {}: Proceeding despite discount update failure: {}", orderId, msg.message);
        }

        context.getLog().info("Order {}: Initializing OrderActor entity.", orderId);
        EntityRef<OrderActor.Command> orderEntity =
                sharding.entityRefFor(OrderActor.TypeKey, orderId);

        // Parse orderId to int ONLY IF OrderActor.InitializeOrder requires int
        // If OrderActor uses String ID internally, keep it as String.
        // Assuming OrderActor.InitializeOrder takes int for now, based on previous code.
        int orderIdInt;
        try {
             // This relies on the UUID hash being parseable as int, which is NOT GUARANTEED
             // It's better to modify OrderActor to accept String ID or use a sequential int ID generator.
             // Using UUID hash directly is very risky. Generating a simpler int ID:
             orderIdInt = Math.abs(orderId.hashCode()); // Revert to simpler int ID generation
            //  context.log.warn("Using hashCode derived int ID: {}", orderIdInt);
        } catch (NumberFormatException e) {
            // context.log.error("Cannot create int order ID from UUID string {}. Failing order.", orderId);
            return failOrder("Internal error generating order ID.");
        }


        context.ask(
            OrderActor.OperationResponse.class,
            orderEntity,
            Duration.ofSeconds(5),
            // Create the InitializeOrder message
            (ActorRef<OrderActor.OperationResponse> adapter) ->
                new OrderActor.InitializeOrder(orderIdInt, userId, items, finalPrice, "PLACED", adapter),
            // Handle the response
            (response, failure) -> {
                 if (response != null && response.success) {
                     return new OrderInitializationResponse(true, response, "Order entity initialized");
                 } else {
                     String error = (response != null) ? response.message : "Timeout or communication error";
                     context.getLog().error("OrderActor initialization failed for order {}: {}", orderId, error);
                     return new OrderInitializationResponse(false, response, "Order entity initialization failed: " + error);
                 }
            }
        );

        // Transition to waiting for order initialization response
        return Behaviors.receive(Command.class)
            .onMessage(OrderInitializationResponse.class, this::onOrderInitializationResponse)
            .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
            .build();
    }


     // Step 8: Handle Order Initialization, Get Final Order Details
    private Behavior<Command> onOrderInitializationResponse(OrderInitializationResponse msg) {
        if (processingFailed) return Behaviors.stopped();
        if (!msg.success) {
             // Order initialization failed. This is critical. Need to rollback wallet debit?
             context.getLog().error("CRITICAL: Order {} failed during OrderActor initialization: {}. Wallet debit might need manual rollback.", orderId, msg.message);
             // Trigger rollback for products (wallet is harder)
             return failOrder("Order entity initialization failed: " + msg.message);
        }

        context.getLog().info("Order {}: OrderActor initialized successfully. Fetching final order details.", orderId);
        EntityRef<OrderActor.Command> orderEntity =
                sharding.entityRefFor(OrderActor.TypeKey, orderId);

        context.ask(
            OrderActor.OrderResponse.class,
            orderEntity,
            Duration.ofSeconds(5),
            (ActorRef<OrderActor.OrderResponse> adapter) -> new OrderActor.GetOrder(adapter),
            (response, failure) -> {
                 if (response != null) {
                     return new FinalOrderDetailsReceived(response);
                 } else {
                     context.getLog().error("Failed to get final order details for {}: {}", orderId, failure);
                     // Order is created, but we can't get details. Reply with failure message but indicate order might exist.
                     return new OrderCreationFailed("Failed to retrieve final order details, but order might be created (ID: " + orderId + ")");
                 }
            }
        );

         // Transition to waiting for final order details
         return Behaviors.receive(Command.class)
            .onMessage(FinalOrderDetailsReceived.class, this::onFinalOrderDetailsReceived)
            .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
            .build();
    }

     // Step 9: Final Success - Reply and Stop
    private Behavior<Command> onFinalOrderDetailsReceived(FinalOrderDetailsReceived msg) {
        if (processingFailed) return Behaviors.stopped();
        context.getLog().info("Order {} created successfully. Replying to requester.", orderId);
        pendingReplyTo.tell(new PostOrderResponse(true, "Order created successfully", msg.orderResponse));
        // Order successful, stop the worker actor
        return Behaviors.stopped();
    }


    // --- Failure Handling ---

    // Centralized failure logic
    private Behavior<Command> failOrder(String reason) {
         if (processingFailed) return Behaviors.same(); // Avoid duplicate rollbacks/replies
         processingFailed = true; // Mark as failed
         context.getLog().error("Order {} failed: {}", orderId, reason);

         // Perform rollback for deducted products
         rollbackProductStock("Order failure: " + reason);

         // Send failure response immediately
         context.getSelf().tell(new OrderCreationFailed(reason));

         // Transition to final failure state
         return Behaviors.receive(Command.class)
             .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder)
              // Ignore any further messages that might arrive from steps already in progress
             .onAnyMessage(msg -> {
                 context.getLog().debug("Order {} ignoring message {} after failure.", orderId, msg.getClass().getSimpleName());
                 return Behaviors.same();
             })
             .build();
    }

     // Final state after failure has been triggered
    private Behavior<Command> finalizeFailedOrder(OrderCreationFailed msg) {
        context.getLog().debug("Finalizing failed order {}. Replying with failure.", orderId);
        pendingReplyTo.tell(new PostOrderResponse(false, msg.reason, null));
        // Stop the worker actor
        return Behaviors.stopped();
    }


    // Helper method for product stock rollback
    private void rollbackProductStock(String reason) {
         context.getLog().warn("Order {}: Rolling back product stock due to failure: {}", orderId, reason);
         if (deductedQuantities.isEmpty()) {
             context.getLog().info("Order {}: No product stock deductions to roll back.", orderId);
             return;
         }
         for (Map.Entry<Integer, Integer> entry : deductedQuantities.entrySet()) {
             int prodId = entry.getKey();
             int qty = entry.getValue();
             if (qty > 0) {
                 EntityRef<ProductActor.Command> productEntity =
                     sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(prodId));
                 productEntity.tell(new ProductActor.AddStock(qty));
                 context.getLog().info("Order {}: Sent AddStock rollback for product {} (quantity: {})", orderId, prodId, qty);
             }
         }
         deductedQuantities.clear(); // Clear map after rollback initiated
    }

     // Helper to parse wallet balance (basic)
    private int parseWalletBalance(String responseBody) throws NumberFormatException {
        // Very basic parsing, assumes format like {"balance": 123}
        // Needs robust JSON parsing in real application (e.g., using Jackson)
        String balanceLabel = "\"balance\":";
        int startIndex = responseBody.indexOf(balanceLabel);
        if (startIndex == -1) throw new NumberFormatException("Balance field not found");
        startIndex += balanceLabel.length();
        int endIndex = responseBody.indexOf("}", startIndex);
        if (endIndex == -1) endIndex = responseBody.length(); // If it's the last field
        String balanceStr = responseBody.substring(startIndex, endIndex).trim();
        return Integer.parseInt(balanceStr);
    }
}