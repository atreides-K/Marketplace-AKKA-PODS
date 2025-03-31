package pods.akka.actors;

// NEW IMPLEMENTATION

import akka.actor.typed.Behavior;
import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public class PostOrder extends AbstractBehavior<PostOrder.Command> {

    // Command protocol
    public interface Command {}

    // Initial command to start order processing.
    public static final class StartOrder implements Command {
        // public final int orderId;
        public final int userId;
        public final List<OrderActor.OrderItem> items;
        public final ActorRef<PostOrderResponse> replyTo;
        public StartOrder(int userId, List<OrderActor.OrderItem> items, ActorRef<PostOrderResponse> replyTo) {
            // this.orderId = orderId;
            this.userId = userId;
            this.items = items;
            this.replyTo = replyTo;
        }
    }
        // Internal message to capture OrderActor initialization result.
        private static final class OrderInitialized implements Command {
        public final OrderActor.OperationResponse response;
        public OrderInitialized(OrderActor.OperationResponse response) {
            this.response = response;
        }
    }
    // Internal message for successful user validation.
    private static final class UserValidated implements Command {
        public final boolean discountAvailable;
        public UserValidated(boolean discountAvailable) {
            this.discountAvailable = discountAvailable;
        }
    }

    // Internal message for failed user validation.
    private static final class UserValidationFailed implements Command {
        public final String errorMessage;
        public UserValidationFailed(String errorMessage) {
            this.errorMessage = errorMessage;
        }
    }

    // Message carrying the response from product deduction.
    private static final class DeductionResponse implements Command {
        public final int product_id;
        public final ProductActor.OperationResponse response;
        public DeductionResponse(int product_id, ProductActor.OperationResponse response) {
            this.product_id = product_id;
            this.response = response;
        }
    }

    // Message for wallet check result.
    private static final class WalletCheckResult implements Command {
        public final boolean success;
        public final int balance;
        public final String message;
        public WalletCheckResult(boolean success, int balance, String message) {
            this.success = success;
            this.balance = balance;
            this.message = message;
        }
    }

    // Message for wallet debit result.
    private static final class WalletDebitResult implements Command {
        public final boolean success;
        public final String message;
        public WalletDebitResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    // Message for discount update result.
    private static final class DiscountUpdated implements Command {
        public final boolean success;
        public final String message;
        public DiscountUpdated(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    // Internal message to signal that an OrderActor has been spawned and its details retrieved.
    private static final class OrderCreated implements Command {
        public final OrderActor.OrderResponse orderResponse;
        public OrderCreated(OrderActor.OrderResponse orderResponse) {
            this.orderResponse = orderResponse;
        }
    }

    // Final completion message.
    private static final class OrderProcessingComplete implements Command {
        public final boolean success;
        public final String message;
        public OrderProcessingComplete(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

      // The response message sent back to the Gateway, now including complete order details.
      public static final class PostOrderResponse {
        public final boolean success;
        public final String message;
        public final OrderActor.OrderResponse orderResponse;

        public PostOrderResponse(boolean success, String message, OrderActor.OrderResponse orderResponse) {
            this.success = success;
            this.message = message;
            this.orderResponse = orderResponse;
        }
    }
    // State variables.
    private String orderId;
    private int userId;
    private List<OrderActor.OrderItem> items;
    private ActorRef<PostOrderResponse> pendingReplyTo;
    private boolean discountAvailable;
    private int pendingDeductionResponses;
    private int totalPriceFromProducts;
    private boolean deductionFailed;

    private final HttpClient httpClient;
    private final ClusterSharding sharding;

    // Factory method.
    public static Behavior<Command> create() {
        return Behaviors.setup(PostOrder::new);
    }

    private PostOrder(ActorContext<Command> context) {
        super(context);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.sharding = ClusterSharding.get(context.getSystem());
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(StartOrder.class, this::onStartOrder)
                .onMessage(UserValidated.class, this::onUserValidated)
                .onMessage(UserValidationFailed.class, this::onUserValidationFailed)
                .onMessage(DeductionResponse.class, this::onDeductionResponse)
                .onMessage(WalletCheckResult.class, this::onWalletCheckResult)
                .onMessage(WalletDebitResult.class, this::onWalletDebitResult)
                .onMessage(DiscountUpdated.class, this::onDiscountUpdated)
                .onMessage(OrderInitialized.class, this::onOrderInitialized)
                .onMessage(OrderCreated.class, this::onOrderCreated)
                .onMessage(OrderProcessingComplete.class, this::onOrderProcessingComplete)
                .build();
    }

    // Step 1: Validate user via HTTP call.
    private Behavior<Command> onStartOrder(StartOrder cmd) {
        // this.orderId = cmd.orderId;
        this.orderId = UUID.randomUUID().toString(); // Generate a unique string ID
        this.userId = cmd.userId;
        this.items = cmd.items;
        this.pendingReplyTo = cmd.replyTo;
        this.pendingDeductionResponses = 0;
        this.totalPriceFromProducts = 0;
        this.deductionFailed = false;

        getContext().getLog().info("Starting order processing for and userId {}", userId);

        HttpRequest userRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080/users/" + userId))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        httpClient.sendAsync(userRequest, BodyHandlers.ofString())
            .whenComplete((resp, ex) -> {
                if (ex != null || resp.statusCode() != 200) {
                    getContext().getSelf().tell(new UserValidationFailed("User validation failed: " + (ex != null ? ex.getMessage() : "Status " + resp.statusCode())));
                } else {
                    // For simplicity, assume response contains "discount_availed": true/false.
                    boolean discountAvailed = resp.body().contains("\"discount_availed\": true");
                    // Discount is available if not already availed.
                    // boolean discountAvail = !discountAvailed;
                    getContext().getSelf().tell(new UserValidated(discountAvailed));
                }
            });
        return this;
    }

    private Behavior<Command> onUserValidated(UserValidated msg) {
        discountAvailable = msg.discountAvailable;
        getContext().getLog().info("User validated. Discount available: {}", discountAvailable);
        // Step 2: For each product in the order, send a deduction message.
        pendingDeductionResponses = items.size();
        for (OrderActor.OrderItem item : items) {
            int prodId = item.product_id;
            int qty = item.quantity;
            // Get the ProductActor reference from a registry.
            EntityRef<ProductActor.Command> productActor = sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(item.product_id));
            // Create a message adapter to convert the OperationResponse to a DeductionResponse.
            ActorRef<ProductActor.OperationResponse> adapter = getContext().messageAdapter(ProductActor.OperationResponse.class,
                    response -> new DeductionResponse(prodId, response));
            productActor.tell(new ProductActor.DeductStock(qty, adapter));
        }
        return this;
    }

    private Behavior<Command> onUserValidationFailed(UserValidationFailed msg) {
        getContext().getLog().error("User validation failed: {}", msg.errorMessage);
        getContext().getSelf().tell(new OrderProcessingComplete(false, msg.errorMessage));
        return this;
    }

    // Step 3: Handle each product deduction response.
    private Behavior<Command> onDeductionResponse(DeductionResponse msg) {
        pendingDeductionResponses--;
        if (!msg.response.success) {
            deductionFailed = true;
            getContext().getLog().error("Deduction failed for product {}: {}", msg.product_id, msg.response.message);
        } else {
            totalPriceFromProducts += msg.response.priceDeducted;
        }
        if (pendingDeductionResponses == 0) {
            if (deductionFailed) {
                // Rollback: Restock products for each order item.
                for (OrderActor.OrderItem item : items) {
                    EntityRef<ProductActor.Command> productActor = sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(item.product_id));
                    productActor.tell(new ProductActor.AddStock(item.quantity));
                }
                getContext().getSelf().tell(new OrderProcessingComplete(false, "Product stock deduction failed"));
            } else {
                // All product deductions succeeded. Apply discount if available.
                int finalPrice = discountAvailable ? (int) (totalPriceFromProducts * 0.9) : totalPriceFromProducts;
                totalPriceFromProducts = finalPrice;
                getContext().getLog().info("Product deductions succeeded. Total price after discount (if any): {}", totalPriceFromProducts);
                // Step 4: Check wallet balance.
                HttpRequest walletCheckRequest = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:8082/wallets/" + userId))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                httpClient.sendAsync(walletCheckRequest, BodyHandlers.ofString())
                        .whenComplete((resp, ex) -> {
                            if (ex != null || resp.statusCode() != 200) {
                                getContext().getSelf().tell(new WalletCheckResult(false, 0, "Wallet service error"));
                            } else {
                                int balance = parseWalletBalance(resp.body());
                                getContext().getSelf().tell(new WalletCheckResult(true, balance, ""));
                            }
                        });
            }
        }
        return this;
    }

    // Step 5: Handle wallet check result.
    private Behavior<Command> onWalletCheckResult(WalletCheckResult msg) {
        if (!msg.success) {
            getContext().getLog().error("Wallet check failed: {}", msg.message);
            // Rollback product stock.
            for (OrderActor.OrderItem item : items) {
                EntityRef<ProductActor.Command> productActor = sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(item.product_id));
                productActor.tell(new ProductActor.AddStock(item.quantity));
            }
            getContext().getSelf().tell(new OrderProcessingComplete(false, "Wallet check failed"));
        } else {
            if (msg.balance < totalPriceFromProducts) {
                getContext().getLog().error("Insufficient wallet balance: {} available, {} required", msg.balance, totalPriceFromProducts);
                // Rollback product stock.
                for (OrderActor.OrderItem item : items) {
                    EntityRef<ProductActor.Command> productActor = sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(item.product_id));
                    productActor.tell(new ProductActor.AddStock(item.quantity));
                }
                getContext().getSelf().tell(new OrderProcessingComplete(false, "Insufficient wallet balance"));
            } else {
                // Step 6: Debit the wallet.
                String debitJson = "{\"action\": \"debit\", \"amount\": " + totalPriceFromProducts + "}";
                HttpRequest debitRequest = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:8082/wallets/" + userId))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(debitJson))
                        .build();
                httpClient.sendAsync(debitRequest, BodyHandlers.ofString())
                        .whenComplete((resp, ex) -> {
                            if (ex != null || resp.statusCode() != 200) {
                                getContext().getSelf().tell(new WalletDebitResult(false, "Wallet debit failed"));
                            } else {
                                getContext().getSelf().tell(new WalletDebitResult(true, ""));
                            }
                        });
            }
        }
        return this;
    }

    // Step 7: Handle wallet debit result.
    private Behavior<Command> onWalletDebitResult(WalletDebitResult msg) {
        if (!msg.success) {
            getContext().getLog().error("Wallet debit failed: {}", msg.message);
            // Rollback product stock.
            for (OrderActor.OrderItem item : items) {
                EntityRef<ProductActor.Command> productActor = sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(item.product_id));
                productActor.tell(new ProductActor.AddStock(item.quantity));
            }
            getContext().getSelf().tell(new OrderProcessingComplete(false, "Wallet debit failed"));
        } else {
            // Step 8: Update discount status.
            String discountJson = "{\"id\": " + userId + ", \"discount_availed\": true}";
            HttpRequest discountRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8080/users/" + userId + "/discount"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(discountJson))
                    .build();
            httpClient.sendAsync(discountRequest, BodyHandlers.ofString())
                    .whenComplete((resp, ex) -> {
                        if (ex != null || resp.statusCode() != 200) {
                            getContext().getSelf().tell(new DiscountUpdated(false, "Discount update failed"));
                            
                        } else {
                            getContext().getSelf().tell(new DiscountUpdated(true, ""));
                        }
                    });
        }
        return this;
    }

    // Step 9: Handle discount update result.
    private Behavior<Command> onDiscountUpdated(DiscountUpdated msg) {
        if (!msg.success) {
            getContext().getLog().error("Discount update failed: {}", msg.message);
            getContext().getSelf().tell(new OrderProcessingComplete(false, "Discount update failed"));
        } else {
             // All steps succeeded; initialize the OrderActor as a sharded entity.
             EntityRef<OrderActor.Command> orderEntity =
                     sharding.entityRefFor(OrderActor.TypeKey, String.valueOf(orderId));
             // Create a message adapter to receive the initialization response.
             ActorRef<OrderActor.OperationResponse> initAdapter = getContext().messageAdapter(OrderActor.OperationResponse.class,
                     resp -> new OrderInitialized(resp));
             // Send an InitializeOrder message with the necessary fields.
             orderEntity.tell(new OrderActor.InitializeOrder(orderId, userId, items, totalPriceFromProducts, "PLACED", initAdapter));
        }
        return this;
    }

    // Step 10: Handle the OrderInitialized response and then request order details.
    private Behavior<Command> onOrderInitialized(OrderInitialized msg) {
        if (!msg.response.success) {
            getContext().getLog().error("Order initialization failed: {}", msg.response.message);
            getContext().getSelf().tell(new OrderProcessingComplete(false, "Order initialization failed"));
            return this;
        } else {
            // Now request the complete order details.
            getContext().getLog().info("Order initialized successfully. Requesting order details for OrderId: {}", orderId);
            EntityRef<OrderActor.Command> orderEntity =
                    sharding.entityRefFor(OrderActor.TypeKey, String.valueOf(orderId));
            ActorRef<OrderActor.OrderResponse> adapter = getContext().messageAdapter(OrderActor.OrderResponse.class,
                    orderResp -> new OrderCreated(orderResp));
            orderEntity.tell(new OrderActor.GetOrder(adapter));
        }
        return this;
    }

    // Final step: Complete the order processing.
    // Step 11: Once the OrderActor responds with its details, send the final response.
    private Behavior<Command> onOrderCreated(OrderCreated msg) {
        OrderActor.OrderResponse orderResp = msg.orderResponse;
        // getContext().getSelf().tell(new OrderProcessingComplete(true, "Order created successfully")); dont think this is req coz we are handlin success reponse here and is causing some prob because we kill PostOrder actor at the end
        // Save the order details in the final response.
        getContext().getLog().info("Order created with details: OrderId: {}, UserId: {}, TotalPrice: {}, Status: {}",
                orderResp.orderId, orderResp.userId, orderResp.totalPrice, orderResp.status);
        // Reply with full order details.
        pendingReplyTo.tell(new PostOrderResponse(true, "Order created successfully", orderResp));
        return Behaviors.stopped();//kill PostOrder actor
    }

    // Final step: In case of failure.
    private Behavior<Command> onOrderProcessingComplete(OrderProcessingComplete msg) {
        if (!msg.success) {
            pendingReplyTo.tell(new PostOrderResponse(false, "Order " + orderId + " failed: " + msg.message, null));
        }
        return Behaviors.stopped();
    }

    // Helper method to parse wallet balance from a JSON response.
    private int parseWalletBalance(String responseBody) {
        try {
            int index = responseBody.indexOf("\"balance\":");
            if (index < 0) return 0;
            int start = index + 10;
            int end = responseBody.indexOf("}", start);
            String balanceStr = responseBody.substring(start, end).trim().replaceAll("[^0-9]", "");
            return Integer.parseInt(balanceStr);
        } catch (Exception e) {
            return 0;
        }
    }
}