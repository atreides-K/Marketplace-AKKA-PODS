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

    // Message for stock check response from a ProductActor.
    private static final class StockChecked implements Command {
        public final int productId;
        public final ProductActor.StockCheckResponse response;
        public StockChecked(int productId, ProductActor.StockCheckResponse response) {
        this.productId = productId;
        this.response = response;
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
    private int orderId;
    private int userId;
    private List<OrderActor.OrderItem> items;
    private ActorRef<PostOrderResponse> pendingReplyTo;
    private boolean discountAvailable;
    private int pendingCheckResponses;
    private boolean checkFailed;
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
                .onMessage(StockChecked.class, this::onStockChecked)
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
        this.orderId = Math.abs(UUID.randomUUID().hashCode()); // Generate a unique integer ID
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
                    boolean discountAvailed = resp.body().contains("true");
                    // Discount is available if not already availed.
                    boolean discountAvail = !discountAvailed;
                    System.out.println("Discount available: " + discountAvail + ", Discount availed: " + discountAvailed);
                    getContext().getSelf().tell(new UserValidated(discountAvail));
                }
            });
        return this;
    }

    private Behavior<Command> onUserValidated(UserValidated msg) {
        discountAvailable = msg.discountAvailable;
        getContext().getLog().info("User validated. Discount available: {}", discountAvailable);
        // Initialize the counter for stock checks
        pendingCheckResponses = items.size();
        checkFailed = false;
        // Phase 1: Check stock for each order item.
        for (OrderActor.OrderItem item : items) {
          int prodId = item.product_id;
          int qty = item.quantity;
          EntityRef<ProductActor.Command> productEntity =
              sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(prodId));
          ActorRef<ProductActor.StockCheckResponse> adapter = getContext().messageAdapter(ProductActor.StockCheckResponse.class,
              response -> new StockChecked(prodId, response));
          productEntity.tell(new ProductActor.CheckStock(qty, adapter));
        }
        return this;
      }
    
      private Behavior<Command> onUserValidationFailed(UserValidationFailed msg) {
        getContext().getLog().error("User validation failed: {}", msg.errorMessage);
        getContext().getSelf().tell(new OrderProcessingComplete(false, msg.errorMessage));
        return this;
      }
    
      // Phase 2: Handle stock check responses.
      // Phase 1: Stock check response handler
    private Behavior<Command> onStockChecked(StockChecked msg) {
        pendingCheckResponses--;
        if (!msg.response.sufficient) {
            checkFailed = true;
            getContext().getLog().error("Stock check failed for product {}: insufficient stock", msg.productId);
        }
        // When all stock checks are received:
        if (pendingCheckResponses == 0) {
            if (checkFailed) {
                // One or more products do not have sufficient stock – fail order.
                getContext().getSelf().tell(new OrderProcessingComplete(false, "Insufficient stock for one or more products"));
            } else {
                // All products have sufficient stock; proceed to actual deduction.
                pendingDeductionResponses = items.size();
                totalPriceFromProducts = 0;
                deductionFailed = false;
                for (OrderActor.OrderItem item : items) {
                    int prodId = item.product_id;
                    int qty = item.quantity;
                    EntityRef<ProductActor.Command> productEntity =
                        sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(prodId));
                    ActorRef<ProductActor.OperationResponse> adapter =
                        getContext().messageAdapter(ProductActor.OperationResponse.class,
                            response -> new DeductionResponse(prodId, response));
                    productEntity.tell(new ProductActor.DeductStock(qty, adapter));
                }
            }
        }
        return this;
    }

    // Phase 2: Deduction response handler
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
                // This branch should ideally not occur since we already checked stock,
                // but if it does, fail the order.
                getContext().getSelf().tell(new OrderProcessingComplete(false, "Product stock deduction failed"));
            } else {
                // Proceed with wallet check...
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


    // Add a flag to the PostOrder actor's state:
    private boolean rolledBack = false;

    // Step 5: Handle wallet check result.
    private Behavior<Command> onWalletCheckResult(WalletCheckResult msg) {
        if (!msg.success) {
            getContext().getLog().error("Wallet check failed: {}", msg.message);
            rollbackProductStock();
            getContext().getSelf().tell(new OrderProcessingComplete(false, "Wallet check failed"));
        } else {
            if (msg.balance < totalPriceFromProducts) {
                getContext().getLog().error("Insufficient wallet balance: {} available, {} required", msg.balance, totalPriceFromProducts);
                rollbackProductStock();
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
                            System.out.println("Debit response: " + resp.body());
                            if (ex != null || resp.statusCode() != 200) {
                                System.out.println("Debit error: " + (ex != null ? ex.getMessage() : "Status " + resp.statusCode()));
                                getContext().getSelf().tell(new WalletDebitResult(false, "Wallet debit failed"));
                            } else {
                                System.out.println("Debit success: " + resp.body());
                                //getContext().getLog().info("Wallet debited successfully. Amount: {}", totalPriceFromProducts);
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
            if (!rolledBack) {
                rollbackProductStock();
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
    getContext().getLog().info("Discount update result: {}", msg.success);
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
        getContext().getLog().info("Initializing OrderActor with orderId: {}, userId: {}, items: {}, totalPrice: {}, status: PLACED",
                orderId, userId, items, totalPriceFromProducts);
        orderEntity.tell(new OrderActor.InitializeOrder(orderId, userId, items, totalPriceFromProducts, "PLACED", initAdapter));
    }
    return this;
}


    // Step 10: Handle the OrderInitialized response and then request order details.
    private Behavior<Command> onOrderInitialized(OrderInitialized msg) {
        System.out.println("Order initialized response: " + msg.response.success);
        if (!msg.response.success) {
            getContext().getLog().error("Order initialization failed: {}", msg.response.message);
            getContext().getSelf().tell(new OrderProcessingComplete(false, "Order initialization failed"));
            return this;
        } else {
            getContext().getLog().info("Order initialized successfully with ID: {}", orderId);
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
                orderResp.order_id, orderResp.user_id, orderResp.total_price, orderResp.status);
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

    // Helper method to perform product stock rollback.
    private void rollbackProductStock() {
        if (!rolledBack) {
            for (OrderActor.OrderItem item : items) {
                EntityRef<ProductActor.Command> productEntity =
                    sharding.entityRefFor(ProductActor.TypeKey, String.valueOf(item.product_id));
                productEntity.tell(new ProductActor.AddStock(item.quantity));
            }
            rolledBack = true;
            getContext().getLog().info("Performed product stock rollback for order {}", orderId);
        }
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