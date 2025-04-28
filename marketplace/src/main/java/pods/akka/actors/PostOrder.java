package pods.akka.actors;

import akka.actor.typed.Behavior;
import akka.actor.typed.DispatcherSelector;
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
import pods.akka.CborSerializable; // Assuming you have this marker interface
import org.slf4j.Logger;

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
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class PostOrder extends AbstractBehavior<PostOrder.Command> {

    private final Logger log = getContext().getLog();
    private final Executor ioExecutor;
    private final HttpClient httpClient;
    private final ClusterSharding sharding;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- State Enum ---
    private enum State {
        INITIALIZING,
        VALIDATING_USER,
        CHECKING_STOCK,
        DEDUCTING_STOCK,
        CHECKING_WALLET,
        DEBITING_WALLET,
        UPDATING_DISCOUNT,
        INITIALIZING_ORDER,
        GETTING_FINAL_DETAILS,
        PROCESSING_FAILED, // Intermediate state during failure handling
        FINAL_STATE // Terminal state (either success or failed reply sent)
    }

    private State currentState = State.INITIALIZING;

    // --- Command Protocol ---
    public interface Command extends CborSerializable {}

    // Initial command to start order processing.
    public static final class StartOrder implements Command {
        public final int userId;
        public final List<OrderActor.OrderItem> items; // Assuming OrderActor.OrderItem is defined elsewhere
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
        final OrderActor.OrderResponse orderResponse; // Assuming OrderActor.OrderResponse defined
        FinalOrderDetailsReceived(OrderActor.OrderResponse orderResponse) { this.orderResponse = orderResponse; }
    }
    private static final class OrderCreationFailed implements Command { // Generic failure message
        final String reason;
        OrderCreationFailed(String reason) { this.reason = reason; }
    }

    // Internal message for receiving individual stock check results
    private record StockChecked(int productId, boolean sufficient, String failureReason) implements Command {
        // Overloaded constructor for success case
        StockChecked(int productId, boolean sufficient) {
            this(productId, sufficient, null);
        }
    }
    // Internal message for receiving individual deduction results
    private record StockDeducted(int productId, int quantity, ProductActor.OperationResponse response, String failureReason) implements Command {
        // Overloaded constructor for success case
        StockDeducted(int productId, int quantity, ProductActor.OperationResponse response) {
            this(productId, quantity, response, null);
        }
    }

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
         // Default constructor might be needed for some serialization libraries
         public PostOrderResponse() {
            this(false, "Default constructor message", null);
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
    private boolean processingFailed = false; // Flag to prevent duplicate failure actions

    // --- Constructor and Factory ---
    public static Behavior<Command> create() {
        return Behaviors.setup(PostOrder::new);
    }

    private PostOrder(ActorContext<Command> context) {
        super(context);
        this.httpClient = HttpClient.newBuilder()
                                   .connectTimeout(Duration.ofSeconds(5)) // Standard timeout for HTTP calls
                                   .build();
        this.sharding = ClusterSharding.get(context.getSystem());
        // Ensure you have the "blocking-io-dispatcher" configured in application.conf
        this.ioExecutor = context.getSystem().dispatchers().lookup(
            DispatcherSelector.fromConfig("blocking-io-dispatcher"));
        log.info("PostOrder worker actor started. Path: {}. Initial State: {}", context.getSelf().path(), currentState);
        log.info("PostOrder worker using I/O Executor: {}", ioExecutor);
    }

    // --- Single Receive Logic ---
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            // --- Message Handlers ---
            .onMessage(StartOrder.class, this::onStartOrder)
            .onMessage(UserValidationResponse.class, this::onUserValidationResponse)
            .onMessage(StockChecked.class, this::onStockCheckedResult)
            .onMessage(StockCheckComplete.class, this::onStockCheckComplete)
            .onMessage(StockDeducted.class, this::onStockDeductedResult)
            .onMessage(StockDeductionComplete.class, this::onStockDeductionComplete)
            .onMessage(WalletCheckResponse.class, this::onWalletCheckResponse)
            .onMessage(WalletDebitResponse.class, this::onWalletDebitResponse)
            .onMessage(DiscountUpdateResponse.class, this::onDiscountUpdateResponse)
            .onMessage(OrderInitializationResponse.class, this::onOrderInitializationResponse)
            .onMessage(FinalOrderDetailsReceived.class, this::onFinalOrderDetailsReceived)
            .onMessage(OrderCreationFailed.class, this::finalizeFailedOrder) // Handles the self-sent failure trigger
            .build();
    }

    // --- Step Handlers (Modified for Single Receive) ---

    // Step 1: Start Order, Validate User
    private Behavior<Command> onStartOrder(StartOrder cmd) {
        // Only process StartOrder if in the initial state
        if (currentState != State.INITIALIZING) {
            log.warn("Order '{}': Received StartOrder in unexpected state: {}. Ignoring.", orderId, currentState);
            return Behaviors.same();
        }
        log.debug("onStartOrder: Received for userId {}", cmd.userId);

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
        this.processingFailed = false; // Reset failure flag

        log.info("Order '{}': Processing request for userId {}", orderId, userId);

        // Basic validation
        if (items == null || items.isEmpty()) {
            log.error("Order '{}': Rejected: No items provided.", orderId);
            return failOrder("Order must contain items.");
        }
        if (this.pendingReplyTo == null) {
             log.error("Order '{}': Rejected: Missing replyTo actor reference. Stopping.", orderId);
             return Behaviors.stopped();
        }

        // Step 1.1: Validate User via HTTP
        log.debug("Order '{}': Sending user validation request for userId {}", orderId, userId);
        HttpRequest userRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080/users/" + userId)) // Assuming Account service runs here
                .timeout(Duration.ofSeconds(5))
                .GET().build();

        httpClient.sendAsync(userRequest, BodyHandlers.ofString())
            .whenCompleteAsync((resp, ex) -> {
                String currentOrderId = this.orderId; // Capture orderId for async block
                if (ex != null || resp.statusCode() != 200) {
                    String error = (ex != null) ? extractRelevantError(ex) : "HTTP Status " + resp.statusCode();
                    log.error("Order '{}': User validation HTTP failed for user {}: {}", currentOrderId, userId, error, ex);
                    getContext().getSelf().tell(new UserValidationResponse(false, false, "User validation failed: " + error));
                } else {
                    try {
                         boolean discountAvailed = resp.body().contains("\"discount_availed\":true");
                         log.info("Order '{}': User {} validated. Discount already availed: {}", currentOrderId, userId, discountAvailed);
                         getContext().getSelf().tell(new UserValidationResponse(true, !discountAvailed, "User validated"));
                    } catch (Exception parseEx) {
                         log.error("Order '{}': Failed to parse user validation response body: {}", currentOrderId, parseEx.getMessage(), parseEx);
                         getContext().getSelf().tell(new UserValidationResponse(false, false, "User validation failed: Response parse error"));
                    }
                }
            }, ioExecutor);

        currentState = State.VALIDATING_USER;
        log.debug("Order '{}': State transition to: {}", orderId, currentState);
        return Behaviors.same();
    }

    // Step 2: Handle User Validation, Start Stock Check
    private Behavior<Command> onUserValidationResponse(UserValidationResponse msg) {
        if (currentState != State.VALIDATING_USER) {
            log.warn("Order '{}': Received UserValidationResponse in unexpected state: {}. Ignoring.", orderId, currentState);
            return Behaviors.same();
        }
        log.debug("Order '{}': onUserValidationResponse received. Success: {}", orderId, msg.success);

        if (!msg.success) {
            log.error("Order '{}': Failing due to unsuccessful user validation: {}", orderId, msg.message);
            return failOrder(msg.message); // Use the message from the response
        }
        this.discountAvailable = msg.discountAvailable;
        log.info("Order '{}': Discount available: {}", orderId, this.discountAvailable);

        // Step 2.1: Start checking stock
        log.info("Order '{}': Starting stock check for {} items.", orderId, items.size());
        if (items.isEmpty()) {
             log.warn("Order '{}': No items found at stock check phase.", orderId);
              getContext().getSelf().tell(new StockCheckComplete(true, "No items to check stock for"));
        } else {
            for (OrderActor.OrderItem item : items) {
                if (item.quantity <= 0) {
                    log.error("Order '{}': Invalid quantity {} for product {}", orderId, item.quantity, item.product_id);
                    return failOrder("Item " + item.product_id + " has invalid quantity: " + item.quantity);
                }
                log.debug("Order '{}': Asking stock for product {}", orderId, item.product_id);
                EntityRef<ProductActor.Command> productEntity =
                    sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(item.product_id));

                final int currentProductId = item.product_id; // Capture for lambda
                getContext().ask(
                    ProductActor.StockCheckResponse.class,
                    productEntity,
                    Duration.ofSeconds(3), // Reduced timeout for quicker failure detection?
                    (ActorRef<ProductActor.StockCheckResponse> adapter) -> new ProductActor.CheckStock(item.quantity, adapter),
                    (response, failure) -> {
                        String capturedOrderId = this.orderId; // Capture orderId for async block
                        if (response != null) {
                            log.debug("Order '{}': Stock check response for product {}: {}", capturedOrderId, currentProductId, response.sufficient);
                            return new StockChecked(currentProductId, response.sufficient);
                        } else {
                            String reason = extractRelevantError(failure);
                            log.error("Order '{}': Stock check ask failed for product {}: {}", capturedOrderId, currentProductId, reason, failure);
                            return new StockChecked(currentProductId, false, "Stock check ask failed: " + reason); // Treat failure as insufficient, include reason
                        }
                    }
                );
            }
        }

        currentState = State.CHECKING_STOCK;
        log.debug("Order '{}': State transition to: {}", orderId, currentState);
        return Behaviors.same();
    }

     // Step 2b: Collect Stock Check Results
     private Behavior<Command> onStockCheckedResult(StockChecked msg) {
         if (currentState != State.CHECKING_STOCK) {
             log.warn("Order '{}': Received StockChecked in unexpected state: {}. Ignoring.", orderId, currentState);
             return Behaviors.same();
         }
         if (processingFailed) {
             log.debug("Order '{}': Ignoring StockChecked for product {} as processing already failed.", orderId, msg.productId);
             return Behaviors.same();
         }

         log.debug("Order '{}': onStockCheckedResult received for product {}: sufficient={}", orderId, msg.productId, msg.sufficient);
         stockCheckResults.put(msg.productId, msg.sufficient);

         // If this specific check failed due to an ask/timeout, fail the order immediately
         if (!msg.sufficient && msg.failureReason != null) {
            log.error("Order '{}': Stock check failed for product {} due to: {}", orderId, msg.productId, msg.failureReason);
            return failOrder("Stock check failed for product " + msg.productId + ": " + msg.failureReason);
         }

         // Check if all responses have been received
         if (stockCheckResults.size() == items.size()) {
             log.debug("Order '{}': All stock checks received. Aggregating results.", orderId);
             boolean allSufficient = stockCheckResults.values().stream().allMatch(Boolean::booleanValue);
             if (allSufficient) {
                  log.info("Order '{}': All stock checks passed. Triggering completion.", orderId);
                  getContext().getSelf().tell(new StockCheckComplete(true, "All stock available"));
             } else {
                 List<Integer> failedProducts = stockCheckResults.entrySet().stream()
                     .filter(entry -> !entry.getValue())
                     .map(Map.Entry::getKey)
                     .collect(Collectors.toList());
                 log.error("Order '{}': Stock check failed for products (insufficient): {}", orderId, failedProducts);
                 return failOrder("Insufficient stock for products: " + failedProducts);
             }
         } else {
              log.debug("Order '{}': Waiting for more stock checks (received {} of {})", orderId, stockCheckResults.size(), items.size());
         }
         return Behaviors.same();
     }

    // Step 3: Handle Stock Check Completion, Start Stock Deduction
    private Behavior<Command> onStockCheckComplete(StockCheckComplete msg) {
        if (currentState != State.CHECKING_STOCK) {
             log.warn("Order '{}': Received StockCheckComplete in unexpected state: {}. Ignoring.", orderId, currentState);
             return Behaviors.same();
        }
        if (processingFailed) {
            log.debug("Order '{}': Ignoring StockCheckComplete as processing failed.", orderId);
            return Behaviors.same();
        }

        log.debug("Order '{}': onStockCheckComplete received. Success: {}", orderId, msg.success);
        if (!msg.success) {
             log.error("Order '{}': Failing due to explicit StockCheckComplete failure: {}", orderId, msg.message);
             return failOrder(msg.message);
        }

        // Step 3.1: Start deducting stock
        log.info("Order '{}': Starting stock deduction for {} items.", orderId, items.size());
        if (items.isEmpty()) {
            log.warn("Order '{}': No items to deduct stock from.", orderId);
            getContext().getSelf().tell(new StockDeductionComplete(true, 0, "No stock to deduct"));
        } else {
            for (OrderActor.OrderItem item : items) {
                log.debug("Order '{}': Asking deduction for product {}", orderId, item.product_id);
                EntityRef<ProductActor.Command> productEntity =
                    sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(item.product_id));

                final int currentProductId = item.product_id; // Capture for lambda
                final int currentQuantity = item.quantity;   // Capture for lambda
                getContext().ask(
                    ProductActor.OperationResponse.class,
                    productEntity,
                    Duration.ofSeconds(5),
                    (ActorRef<ProductActor.OperationResponse> adapter) -> new ProductActor.DeductStock(item.quantity, adapter),
                    (response, failure) -> {
                        String capturedOrderId = this.orderId; // Capture orderId for async block
                        if (response != null) {
                             log.debug("Order '{}': Stock deduction response for product {}: Success={}, Message='{}'",
                                       capturedOrderId, currentProductId, response.success, response.message);
                             // Crucially, response.success should be false if ProductActor check failed
                             return new StockDeducted(currentProductId, currentQuantity, response);
                        } else {
                            String reason = extractRelevantError(failure);
                            log.error("Order '{}': Stock deduction ask failed for product {}: {}", capturedOrderId, currentProductId, reason, failure);
                            // Create a failure response to trigger rollback in the next step
                            return new StockDeducted(currentProductId, currentQuantity,
                                new ProductActor.OperationResponse(false, "Ask failed: " + reason, 0), // Include reason in message
                                "Ask failed: " + reason);
                        }
                    }
                );
            }
        }

        currentState = State.DEDUCTING_STOCK;
        log.debug("Order '{}': State transition to: {}", orderId, currentState);
        return Behaviors.same();
    }

    // Step 3b: Collect Stock Deduction Results
    private Behavior<Command> onStockDeductedResult(StockDeducted msg) {
         if (currentState != State.DEDUCTING_STOCK) {
             log.warn("Order '{}': Received StockDeducted in unexpected state: {}. Ignoring.", orderId, currentState);
             return Behaviors.same();
         }
         if (processingFailed) {
            log.debug("Order '{}': Ignoring StockDeducted for product {} as processing failed.", orderId, msg.productId);
            return Behaviors.same();
         }

         log.debug("Order '{}': onStockDeductedResult received for product {}: success={}, message='{}'",
                   orderId, msg.productId, msg.response.success, msg.response.message);
         deductionResults.put(msg.productId, msg.response);

         // Check if the deduction failed (either explicitly from ProductActor or due to ask failure)
         if (!msg.response.success) {
            String failureReason = (msg.failureReason != null) ? msg.failureReason : msg.response.message;
            log.error("Order '{}': Stock deduction failed for product {}: {}", orderId, msg.productId, failureReason);
            // Use a specific reason for failure
            return failOrder("Stock deduction failed for product " + msg.productId + ": " + failureReason);
         } else {
            // Deduction succeeded for this item
            deductedQuantities.put(msg.productId, msg.quantity);
            this.finalPrice += msg.response.priceDeducted; // Accumulate price
            log.debug("Order '{}': Product {} deducted. Accumulated price: {}", orderId, msg.productId, this.finalPrice);
         }

         // Check if all responses received
         if (deductionResults.size() == items.size()) {
            log.info("Order '{}': All stock deductions processed. Accumulated price before discount: {}", orderId, this.finalPrice);
            // Apply discount if available
            if (this.discountAvailable) {
                int originalPrice = this.finalPrice;
                this.finalPrice = (int) (this.finalPrice * 0.9); // Apply 10% discount
                log.info("Order '{}': Applying 10% discount. Price reduced from {} to {}", orderId, originalPrice, this.finalPrice);
            }
            // Send message to self to proceed
            log.debug("Order '{}': Sending StockDeductionComplete to self.", orderId);
            getContext().getSelf().tell(new StockDeductionComplete(true, this.finalPrice, "Deduction successful"));
         } else {
              log.debug("Order '{}': Waiting for more stock deductions (received {} of {})", orderId, deductionResults.size(), items.size());
         }
         return Behaviors.same();
    }

    // Step 4: Handle Deduction Completion, Check Wallet
    private Behavior<Command> onStockDeductionComplete(StockDeductionComplete msg) {
        if (currentState != State.DEDUCTING_STOCK) {
             log.warn("Order '{}': Received StockDeductionComplete in unexpected state: {}. Ignoring.", orderId, currentState);
             return Behaviors.same();
        }
        if (processingFailed) {
            log.debug("Order '{}': Ignoring StockDeductionComplete as processing failed.", orderId);
            return Behaviors.same();
        }

        log.debug("Order '{}': onStockDeductionComplete received. Success: {}, Final Price: {}", orderId, msg.success, msg.finalPrice);
        if (!msg.success) {
            log.error("Order '{}': Failing due to explicit StockDeductionComplete failure: {}", orderId, msg.message);
            return failOrder(msg.message);
        }
        this.finalPrice = msg.finalPrice; // Store final price

        // Step 4.1: Check Wallet via HTTP
        log.info("Order '{}': Checking wallet balance for user {}. Required amount: {}", orderId, userId, finalPrice);
        HttpRequest walletCheckRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:8082/wallets/" + userId))
            .timeout(Duration.ofSeconds(5))
            .GET().build();

        httpClient.sendAsync(walletCheckRequest, BodyHandlers.ofString())
            .whenCompleteAsync((resp, ex) -> {
                String currentOrderId = this.orderId; // Capture orderId
                 if (ex != null || resp.statusCode() != 200) {
                    String error = (ex != null) ? extractRelevantError(ex) : "HTTP Status " + resp.statusCode();
                    log.error("Order '{}': Wallet check HTTP failed for user {}: {}", currentOrderId, userId, error, ex);
                    getContext().getSelf().tell(new WalletCheckResponse(false, "Wallet check communication failed: " + error));
                } else {
                    try {
                        String body = resp.body();
                        int balance = parseWalletBalanceRobust(body);
                        log.info("Order '{}': Wallet balance for user {}: {}", currentOrderId, userId, balance);
                        if (balance >= finalPrice) {
                            getContext().getSelf().tell(new WalletCheckResponse(true, "Sufficient balance"));
                        } else {
                            log.error("Order '{}': Insufficient wallet balance for user {}. Required: {}, Available: {}", currentOrderId, userId, finalPrice, balance);
                            getContext().getSelf().tell(new WalletCheckResponse(false, "Insufficient wallet balance ("+balance+" < "+finalPrice+")"));
                        }
                    } catch (Exception e) {
                         log.error("Order '{}': Failed to parse wallet balance response for user {}: {}", currentOrderId, userId, e.getMessage(), e);
                         getContext().getSelf().tell(new WalletCheckResponse(false, "Failed to parse wallet balance response"));
                    }
                }
            }, ioExecutor);

        currentState = State.CHECKING_WALLET;
        log.debug("Order '{}': State transition to: {}", orderId, currentState);
        return Behaviors.same();
    }

    // Step 5: Handle Wallet Check, Debit Wallet
    private Behavior<Command> onWalletCheckResponse(WalletCheckResponse msg) {
        if (currentState != State.CHECKING_WALLET) {
             log.warn("Order '{}': Received WalletCheckResponse in unexpected state: {}. Ignoring.", orderId, currentState);
             return Behaviors.same();
        }
        if (processingFailed) {
            log.debug("Order '{}': Ignoring WalletCheckResponse as processing failed.", orderId);
            return Behaviors.same();
        }

        log.debug("Order '{}': onWalletCheckResponse received. Success: {}", orderId, msg.success);
        if (!msg.success) {
            log.error("Order '{}': Failing due to wallet check failure: {}", orderId, msg.message);
            return failOrder(msg.message); // Rollback stock
        }

        // Step 5.1: Debit Wallet via HTTP
        log.info("Order '{}': Wallet check successful. Debiting user {} amount: {}", orderId, userId, finalPrice);
        String debitJson = String.format("{\"action\": \"debit\", \"amount\": %d}", finalPrice);
        HttpRequest debitRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8082/wallets/" + userId))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(debitJson)).build();

        httpClient.sendAsync(debitRequest, BodyHandlers.ofString())
            .whenCompleteAsync((resp, ex) -> {
                 String currentOrderId = this.orderId; // Capture orderId
                 if (ex != null || resp.statusCode() != 200) {
                    String error = (ex != null) ? extractRelevantError(ex) : "HTTP Status " + resp.statusCode();
                    log.error("Order '{}': Wallet debit HTTP failed for user {}: {}", currentOrderId, userId, error, ex);
                    getContext().getSelf().tell(new WalletDebitResponse(false, "Wallet debit failed: " + error));
                } else {
                    log.info("Order '{}': Wallet debited successfully for user {}. Amount: {}", currentOrderId, userId, finalPrice);
                    getContext().getSelf().tell(new WalletDebitResponse(true, "Wallet debited successfully"));
                }
            }, ioExecutor);

        currentState = State.DEBITING_WALLET;
        log.debug("Order '{}': State transition to: {}", orderId, currentState);
        return Behaviors.same();
    }

    // Step 6: Handle Wallet Debit, Update Discount (if applicable)
    private Behavior<Command> onWalletDebitResponse(WalletDebitResponse msg) {
         if (currentState != State.DEBITING_WALLET) {
             log.warn("Order '{}': Received WalletDebitResponse in unexpected state: {}. Ignoring.", orderId, currentState);
             return Behaviors.same();
         }
         if (processingFailed) {
            log.debug("Order '{}': Ignoring WalletDebitResponse as processing failed.", orderId);
            return Behaviors.same();
         }

         log.debug("Order '{}': onWalletDebitResponse received. Success: {}", orderId, msg.success);
         if (!msg.success) {
             log.error("Order '{}': Failing due to wallet debit failure: {}", orderId, msg.message);
             return failOrder(msg.message); // CRITICAL: Wallet debit failed AFTER stock deduction. Rollback stock.
         }

         // Step 6.1: Update Discount Status via HTTP (if applicable)
         if (this.discountAvailable) {
            log.info("Order '{}': Updating discount availed status for user {}", orderId, userId);
            String discountJson = String.format("{\"id\": %d, \"discount_availed\": true}", userId);
            HttpRequest discountRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8080/users/" + userId + "/discount"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(discountJson)).build();

            httpClient.sendAsync(discountRequest, BodyHandlers.ofString())
                .whenCompleteAsync((resp, ex) -> {
                     String currentOrderId = this.orderId; // Capture orderId
                     if (ex != null || resp.statusCode() != 200) {
                        String error = (ex != null) ? extractRelevantError(ex) : "HTTP Status " + resp.statusCode();
                        // Log error but proceed anyway (business decision?)
                        log.error("Order '{}': Discount update HTTP failed for user {}: {}. Proceeding with order creation.", currentOrderId, userId, error, ex);
                        getContext().getSelf().tell(new DiscountUpdateResponse(false, "Discount update failed: " + error));
                    } else {
                         log.info("Order '{}': Discount status updated successfully for user {}", currentOrderId, userId);
                         getContext().getSelf().tell(new DiscountUpdateResponse(true, "Discount updated"));
                    }
                }, ioExecutor);

            currentState = State.UPDATING_DISCOUNT;
            log.debug("Order '{}': State transition to: {}", orderId, currentState);
         } else {
             log.info("Order '{}': No discount applicable. Proceeding to initialize order entity.", orderId);
             getContext().getSelf().tell(new DiscountUpdateResponse(true, "No discount applicable"));
         }
         return Behaviors.same();
    }

     // Step 7: Handle Discount Update, Initialize Order Actor
    private Behavior<Command> onDiscountUpdateResponse(DiscountUpdateResponse msg) {
        // Can arrive from DEBITING_WALLET or UPDATING_DISCOUNT
        if (currentState != State.DEBITING_WALLET && currentState != State.UPDATING_DISCOUNT) {
             log.warn("Order '{}': Received DiscountUpdateResponse in unexpected state: {}. Ignoring.", orderId, currentState);
             return Behaviors.same();
        }
        if (processingFailed) {
            log.debug("Order '{}': Ignoring DiscountUpdateResponse as processing failed.", orderId);
            return Behaviors.same();
        }

        log.debug("Order '{}': onDiscountUpdateResponse received. Success: {}", orderId, msg.success);
        if (!msg.success) {
             log.warn("Order '{}': Proceeding despite discount update failure: {}", orderId, msg.message);
        }

        // Step 7.1: Initialize Order Actor via context.ask
        log.info("Order '{}': Initializing OrderActor entity.", orderId);
        EntityRef<OrderActor.Command> orderEntity = sharding.entityRefFor(OrderActor.TypeKey, orderId);

        getContext().ask(
            OrderActor.OperationResponse.class,
            orderEntity,
            Duration.ofSeconds(5),
            (ActorRef<OrderActor.OperationResponse> adapter) ->
                new OrderActor.InitializeOrder(orderId, userId, items, finalPrice, "PLACED", adapter),
            (response, failure) -> {
                 String capturedOrderId = this.orderId; // Capture orderId
                 if (response != null && response.success) {
                     log.debug("Order '{}': Order init ask succeeded.", capturedOrderId);
                     return new OrderInitializationResponse(true, response, "Order entity initialized");
                 } else {
                     String errorReason = "Unknown error during order init";
                     if (failure != null) errorReason = "Ask Failure: " + extractRelevantError(failure);
                     else if (response != null) errorReason = "Actor Reply Failure: " + response.message;
                     log.error("Order '{}': OrderActor initialization ask failed: {}", capturedOrderId, errorReason, failure);
                     // Create failure response with the captured reason
                     return new OrderInitializationResponse(false, response, "Order entity initialization failed: " + errorReason);
                 }
            }
        );

        currentState = State.INITIALIZING_ORDER;
        log.debug("Order '{}': State transition to: {}", orderId, currentState);
        return Behaviors.same();
    }

    // Step 8: Handle Order Initialization, Get Final Order Details
    private Behavior<Command> onOrderInitializationResponse(OrderInitializationResponse msg) {
         if (currentState != State.INITIALIZING_ORDER) {
             log.warn("Order '{}': Received OrderInitializationResponse in unexpected state: {}. Ignoring.", orderId, currentState);
             return Behaviors.same();
         }
         if (processingFailed) {
            log.debug("Order '{}': Ignoring OrderInitializationResponse as processing failed.", orderId);
            return Behaviors.same();
         }

         log.debug("Order '{}': onOrderInitializationResponse received. Success: {}", orderId, msg.success);
         if (!msg.success) {
             log.error("CRITICAL: Order '{}' failed during OrderActor initialization: {}. Wallet debit might need manual rollback.", orderId, msg.message);
             return failOrder(msg.message); // Use specific reason from init failure
         }

         // Step 8.1: Get final order details using context.ask
         log.info("Order '{}': OrderActor initialized successfully. Fetching final order details.", orderId);
         EntityRef<OrderActor.Command> orderEntity = sharding.entityRefFor(OrderActor.TypeKey, orderId);

         getContext().ask(
             OrderActor.OrderResponse.class,
             orderEntity,
             Duration.ofSeconds(5),
             (ActorRef<OrderActor.OrderResponse> adapter) -> new OrderActor.GetOrder(adapter),
             (response, failure) -> {
                  String capturedOrderId = this.orderId; // Capture orderId
                  if (response != null) {
                      if (!response.order_id.equals(capturedOrderId)) {
                           log.error("Order '{}': Received FinalOrderDetails but ID mismatch! Expected '{}', Got '{}'. Treating as failure.", capturedOrderId, capturedOrderId, response.order_id);
                            return new OrderCreationFailed("Internal error: Mismatched order details retrieved.");
                      }
                      log.debug("Order '{}': GetOrder ask succeeded.", capturedOrderId);
                      return new FinalOrderDetailsReceived(response);
                  } else {
                      String reason = extractRelevantError(failure);
                      log.error("Order '{}': Failed to get final order details: {}", capturedOrderId, reason, failure);
                      return new OrderCreationFailed("Failed to retrieve final order details after creation (ID: " + capturedOrderId + "): " + reason);
                  }
             }
         );

         currentState = State.GETTING_FINAL_DETAILS;
         log.debug("Order '{}': State transition to: {}", orderId, currentState);
         return Behaviors.same();
    }

     // Step 9: Final Success - Reply and Stop
    private Behavior<Command> onFinalOrderDetailsReceived(FinalOrderDetailsReceived msg) {
        if (currentState != State.GETTING_FINAL_DETAILS) {
             log.warn("Order '{}': Received FinalOrderDetailsReceived in unexpected state: {}. Ignoring.", orderId, currentState);
             return Behaviors.same();
        }
        if (processingFailed) {
            log.warn("Order '{}': Ignoring FinalOrderDetailsReceived as processing failed (should not happen here).", orderId);
            return Behaviors.same();
        }

        log.debug("Order '{}': onFinalOrderDetailsReceived received.", orderId);
        log.info("Order '{}': SUCCESS. Created successfully. Replying to original requester.", orderId);

        if (pendingReplyTo != null) {
             pendingReplyTo.tell(new PostOrderResponse(true, "Order created successfully", msg.orderResponse));
        } else {
             log.error("Order '{}': Cannot reply success, pendingReplyTo is null!", orderId);
        }

        currentState = State.FINAL_STATE;
        log.debug("Order '{}': Stopping actor after successful reply.", orderId);
        return Behaviors.stopped();
    }

    // --- Failure Handling ---

    // Centralized method to trigger the failure sequence
    private Behavior<Command> failOrder(String reason) {
         if (processingFailed) {
             log.warn("Order '{}': Failure already triggered, ignoring duplicate failOrder call (Reason: {}).", orderId != null ? orderId : "UNKNOWN", reason);
             return Behaviors.same();
         }
         processingFailed = true; // Mark as failed FIRST
         currentState = State.PROCESSING_FAILED; // Enter intermediate failure state
         String currentOrderId = (orderId != null) ? orderId : "UNKNOWN_YET";
         // Log the specific reason for failure
         log.error("Order '{}': FAILED. Reason: {}. Initiating rollback and failure reply.", currentOrderId, reason);

         // Perform compensation logic (rollback stock)
         rollbackProductStock("Order failure: " + reason);

         // Send message to self to trigger final reply and stop
         getContext().getSelf().tell(new OrderCreationFailed(reason));

         return Behaviors.same();
    }

     // Handles the self-sent failure message to reply and stop
    private Behavior<Command> finalizeFailedOrder(OrderCreationFailed msg) {
        if (currentState == State.FINAL_STATE) {
             log.warn("Order '{}': Already in final state. Ignoring duplicate finalizeFailedOrder.", orderId != null ? orderId : "UNKNOWN");
             return Behaviors.same();
        }

        String currentOrderId = (orderId != null) ? orderId : "UNKNOWN";
        log.debug("Order '{}': Finalizing failure. Replying with reason: {}", currentOrderId, msg.reason);

        if (pendingReplyTo != null) {
             // Reply with the specific reason captured in OrderCreationFailed
             pendingReplyTo.tell(new PostOrderResponse(false, msg.reason, null));
        } else {
             log.error("Order '{}': Cannot reply failure, pendingReplyTo is null!", currentOrderId);
        }

        currentState = State.FINAL_STATE;
        log.debug("Order '{}': Stopping actor after failure reply.", currentOrderId);
        return Behaviors.stopped();
    }

    // --- Helper Methods ---

    // Helper method for product stock rollback (compensation)
    private void rollbackProductStock(String reason) {
         String currentOrderId = (orderId != null) ? orderId : "UNKNOWN";
         log.warn("Order '{}': Rolling back product stock due to failure: {}", currentOrderId, reason);
         if (deductedQuantities == null || deductedQuantities.isEmpty()) {
             log.info("Order '{}': No product stock deductions to roll back.", currentOrderId);
             return;
         }
         log.debug("Order '{}': Rolling back deductions for products: {}", currentOrderId, deductedQuantities.keySet());
         for (Map.Entry<Integer, Integer> entry : deductedQuantities.entrySet()) {
             int prodId = entry.getKey();
             int qty = entry.getValue();
             if (qty > 0) {
                 try {
                     EntityRef<ProductActor.Command> productEntity =
                         sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(prodId));
                     productEntity.tell(new ProductActor.AddStock(qty));
                     log.info("Order '{}': Sent AddStock rollback for product {} (quantity: {})", currentOrderId, prodId, qty);
                 } catch (Exception e) {
                     log.error("Order '{}': Error sending AddStock rollback for product {}: {}", currentOrderId, prodId, e.getMessage(), e);
                 }
             }
         }
         deductedQuantities.clear();
    }

    // Helper method for robust JSON parsing of wallet balance
     private int parseWalletBalanceRobust(String responseBody) throws Exception {
        // ... (implementation identical to previous version) ...
        log.debug("parseWalletBalanceRobust: Attempting to parse wallet balance response body.");
        if (responseBody == null || responseBody.trim().isEmpty()) {
             log.error("parseWalletBalanceRobust: Wallet balance response body is null or empty.");
             throw new Exception("Wallet balance response body is null or empty.");
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.has("balance") && root.get("balance").isInt()) {
                 int balance = root.get("balance").asInt();
                 log.debug("parseWalletBalanceRobust: Parsed balance from JSON 'balance' field: {}", balance);
                 return balance;
            } else {
                 log.warn("parseWalletBalanceRobust: JSON field 'balance' not found or not an integer in body: '{}'. Attempting to parse whole body as int.", responseBody);
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

    // Helper method to extract a concise error message from exceptions
    private String extractRelevantError(Throwable failure) {
        if (failure == null) return "Unknown failure";
        // Unwrap CompletionException if present (common with async operations)
        Throwable cause = (failure instanceof CompletionException && failure.getCause() != null) ? failure.getCause() : failure;
        if (cause instanceof TimeoutException) {
            return "TimeoutException: " + cause.getMessage();
        } else if (cause instanceof akka.pattern.AskTimeoutException) {
             return "AskTimeoutException: " + cause.getMessage();
        }
        // Add more specific checks if needed (e.g., java.net.http.HttpTimeoutException)
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }


    // --- Placeholder Definitions for External Actor Messages ---
    // Add these if OrderActor and ProductActor classes/messages are not accessible
    // in the same compilation unit or via imports. Adjust fields as needed.
}
  
 // End of PostOrder class