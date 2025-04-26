package pods.akka;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Scheduler;
import akka.actor.typed.javadsl.AskPattern;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Routers;
import akka.actor.typed.javadsl.GroupRouter;

// Import actor classes
import pods.akka.actors.ProductActor;
import pods.akka.actors.DeleteOrder;
import pods.akka.actors.OrderActor;
import pods.akka.actors.PostOrder;

// Sharding imports
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.cluster.sharding.typed.javadsl.EntityContext;


public class Main {

    static ActorRef<Gateway.Command> gateway = null; // Nullable, only set on primary
    static Duration askTimeout;
    static Scheduler scheduler;

    // ServiceKeys for worker discovery
    public static final ServiceKey<PostOrder.Command> POST_ORDER_SERVICE_KEY =
            ServiceKey.create(PostOrder.Command.class, "postOrderWorker");
    public static final ServiceKey<DeleteOrder.Command> DELETE_ORDER_SERVICE_KEY =
            ServiceKey.create(DeleteOrder.Command.class, "deleteOrderWorker");

    // --- Static Handler Class ---
    static class Handler implements HttpHandler {
        private static final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public void handle(HttpExchange req) throws IOException {
            // Ensure this node should handle HTTP requests (primary node check)
            if (gateway == null) {
                // Should only happen if configuration is wrong or during brief startup/shutdown race
                System.err.println("FATAL: HTTP request received but Gateway actor is null. Check node configuration.");
                // Avoid sending response if gateway isn't ready, client will likely timeout
                 req.close(); // Close the connection quickly
                return;
            }

            String path = req.getRequestURI().getPath();
            String method = req.getRequestMethod();
            System.out.println("Received request: " + method + " " + path); // Basic Logging

            try {
                switch (method) {
                    case "GET":
                        handleGet(req, path);
                        break;
                    case "POST":
                        handlePost(req, path);
                        break;
                    case "PUT":
                        handlePut(req, path);
                        break;
                    case "DELETE":
                        handleDelete(req, path);
                        break;
                    default:
                        System.err.println("Unsupported method: " + method);
                        sendErrorResponse(req, 405, "Method Not Allowed");
                        break;
                }
            } catch (Exception e) {
                System.err.println("Unexpected error handling request " + method + " " + path + ": " + e.getMessage());
                e.printStackTrace();
                if (req.getResponseCode() == -1) {
                     try { sendErrorResponse(req, 500, "Internal Server Error"); }
                     catch (IOException ioEx) { /* Ignore secondary error */ }
                }
            }
        }

        // --- GET Request Handler ---
        private void handleGet(HttpExchange req, String path) {
            String[] parts = path.split("/");
            // Path: /products/{productId}
            if (parts.length == 3 && "products".equals(parts[1])) {
                String productIdStr = parts[2];
                try {
                    // Basic validation: is it potentially an integer?
                    Integer.parseInt(productIdStr);
                    System.out.println("Handler asking Gateway for product: " + productIdStr);
                    CompletionStage<ProductActor.ProductResponse> stage = AskPattern.ask(
                            gateway,
                            (ActorRef<ProductActor.ProductResponse> ref) -> new Gateway.GetProductById(productIdStr, ref),
                            askTimeout,
                            scheduler);

                    handleActorResponse(req, stage, (httpExchange, response) -> {
                        try { // Handle IOException from send calls
                            if (response.price >= 0) { // Found
                                sendJsonResponse(httpExchange, 200, response);
                            } else { // Not found by actor logic
                                System.err.println("Product not found by actor: " + productIdStr);
                                sendErrorResponse(httpExchange, 404, "Product not found");
                            }
                        } catch (IOException e) {
                            System.err.println("IOException sending GET /products/{id} response: " + e.getMessage());
                        }
                    });
                } catch (NumberFormatException e) {
                     System.err.println("Invalid product ID format: " + productIdStr);
                     try { sendErrorResponse(req, 400, "Invalid product ID format"); }
                     catch (IOException ioEx) { System.err.println("IOException sending 400 error: " + ioEx.getMessage()); }
                }
            // Path: /orders/{orderId}
            } else if (parts.length == 3 && "orders".equals(parts[1])) {
                String orderIdStr = parts[2];
                 try {
                    // Basic validation: is it potentially an integer?
                    Integer.parseInt(orderIdStr);
                    System.out.println("Handler asking Gateway for order: " + orderIdStr);
                    CompletionStage<OrderActor.OrderResponse> stage = AskPattern.ask(
                            gateway,
                            (ActorRef<OrderActor.OrderResponse> ref) -> new Gateway.GetOrderById(orderIdStr, ref),
                            askTimeout,
                            scheduler);

                    handleActorResponse(req, stage, (httpExchange, response) -> {
                        try { // Handle IOException from send calls
                            if (response.order_id != 0 && !"NotInitialized".equals(response.status)) { // Found
                                sendJsonResponse(httpExchange, 200, response);
                            } else { // Not found or not initialized
                                System.err.println("Order not found by actor: " + orderIdStr);
                                sendErrorResponse(httpExchange, 404, "Order not found");
                            }
                        } catch (IOException e) {
                             System.err.println("IOException sending GET /orders/{id} response: " + e.getMessage());
                        }
                    });
                 } catch (NumberFormatException e) {
                     System.err.println("Invalid order ID format: " + orderIdStr);
                     try { sendErrorResponse(req, 400, "Invalid order ID format"); }
                     catch (IOException ioEx) { System.err.println("IOException sending 400 error: " + ioEx.getMessage()); }
                 }
            } else {
                System.err.println("Unsupported GET path: " + path);
                try { sendErrorResponse(req, 404, "Not Found"); }
                catch (IOException ioEx) { System.err.println("IOException sending 404 error: " + ioEx.getMessage()); }
            }
        }

        // --- POST Request Handler ---
        private void handlePost(HttpExchange req, String path) {
            String[] parts = path.split("/");
            // Path: /orders
            if (parts.length == 2 && "orders".equals(parts[1])) {
                //Gateway.PostOrderReq orderRequest = null;
                try (InputStream is = req.getRequestBody()) {
                    Gateway.PostOrderReq parsedOrderRequest = objectMapper.readValue(is, Gateway.PostOrderReq.class);
                    // Validation
                    if (parsedOrderRequest.items == null || parsedOrderRequest.items.isEmpty()) {
                        throw new IllegalArgumentException("Order items cannot be empty.");
                    }
                    if (parsedOrderRequest.user_id <= 0) {
                         throw new IllegalArgumentException("Invalid user ID.");
                    }
                    for(OrderActor.OrderItem item : parsedOrderRequest.items) {
                        if (item.product_id <= 0 || item.quantity <= 0) {
                             throw new IllegalArgumentException("Invalid product ID or quantity in items.");
                        }
                    }
                    final Gateway.PostOrderReq finalOrderRequest = parsedOrderRequest;
                    // If validation passed, proceed with Ask
                    System.out.println("Handler asking Gateway to post order for user: " + finalOrderRequest.user_id);
                    CompletionStage<PostOrder.PostOrderResponse> stage = AskPattern.ask(
                            gateway,
                            // Use the parsed and validated orderRequest object
                            (ActorRef<PostOrder.PostOrderResponse> ref) -> new Gateway.PostOrderReq(finalOrderRequest.user_id, finalOrderRequest.items, ref),
                            askTimeout,
                            scheduler);

                    handleActorResponse(req, stage, (httpExchange, response) -> {
                        try { // Handle IOException from send calls
                            if (response.success) {
                                sendJsonResponse(httpExchange, 201, response.orderResponse); // 201 Created
                            } else {
                                System.err.println("Failed to create order: " + response.message);
                                // Use 400 as per Project 1 spec for most creation failures
                                sendJsonResponse(httpExchange, 400, Map.of("success", false, "message", response.message));
                            }
                        } catch (IOException e) {
                            System.err.println("IOException sending POST /orders response: " + e.getMessage());
                        }
                    });

                } catch (JsonProcessingException | IllegalArgumentException e) {
                    System.err.println("Invalid POST /orders request body: " + e.getMessage());
                    try { sendErrorResponse(req, 400, "Bad Request: Invalid order data - " + e.getMessage()); }
                    catch (IOException ioEx) { System.err.println("IOException sending 400 error: " + ioEx.getMessage()); }
                } catch (IOException e) { // Catch IO error from reading body stream
                    System.err.println("IO Error reading POST request body: " + e.getMessage());
                    try { sendErrorResponse(req, 500, "Error reading request"); }
                    catch (IOException ioEx) { System.err.println("IOException sending 500 error: " + ioEx.getMessage()); }
                }
            } else {
                 System.err.println("Unsupported POST path: " + path);
                 try { sendErrorResponse(req, 404, "Not Found"); }
                 catch (IOException ioEx) { System.err.println("IOException sending 404 error: " + ioEx.getMessage()); }
            }
        }

        // --- PUT Request Handler ---
        private void handlePut(HttpExchange req, String path) {
            String[] parts = path.split("/");
            // Path: /orders/{orderId} (Mark as Delivered)
            if (parts.length == 3 && "orders".equals(parts[1])) {
                 String orderIdStr = parts[2];
                 try {
                    // Validate orderId format
                     Integer.parseInt(orderIdStr);

                     Map<String, Object> requestBody;
                     try (InputStream is = req.getRequestBody()) {
                         requestBody = objectMapper.readValue(is, new TypeReference<Map<String, Object>>() {});
                     }

                     // Validate request body for DELIVERED status update
                     if (!requestBody.containsKey("order_id") || !requestBody.containsKey("status")) {
                         throw new IllegalArgumentException("Missing 'order_id' or 'status' in request body.");
                     }
                     String bodyOrderId = String.valueOf(requestBody.get("order_id")); // Convert safely
                     String status = String.valueOf(requestBody.get("status"));

                     if (!orderIdStr.equals(bodyOrderId)) {
                          throw new IllegalArgumentException("Order ID in path does not match Order ID in body.");
                     }
                     if (!"DELIVERED".equalsIgnoreCase(status)) {
                         throw new IllegalArgumentException("Invalid status for PUT request. Expected 'DELIVERED'.");
                     }

                     // If validation passed, proceed with Ask
                     System.out.println("Handler asking Gateway to mark order as delivered: " + orderIdStr);
                     CompletionStage<OrderActor.OperationResponse> stage = AskPattern.ask(
                            gateway,
                            (ActorRef<OrderActor.OperationResponse> ref) -> new Gateway.PutOrderReq(orderIdStr, ref),
                            askTimeout,
                            scheduler);

                     handleActorResponse(req, stage, (httpExchange, response) -> {
                         try { // Handle IOException from send calls
                             if (response.success) {
                                sendJsonResponse(httpExchange, 200, Map.of(
                                    "order_id", response.order_id,
                                    "status", response.status,
                                    "message", response.message
                                ));
                             } else {
                                 System.err.println("Failed to mark order " + orderIdStr + " as delivered: " + response.message);
                                 // 400 suggests order not found or not in PLACED state
                                 sendJsonResponse(httpExchange, 400, Map.of("success", false, "message", response.message));
                             }
                         } catch (IOException e) {
                             System.err.println("IOException sending PUT /orders/{id} response: " + e.getMessage());
                         }
                     });

                 } catch (NumberFormatException e) {
                     System.err.println("Invalid order ID format: " + orderIdStr);
                     try { sendErrorResponse(req, 400, "Invalid order ID format"); }
                     catch (IOException ioEx) { System.err.println("IOException sending 400 error: " + ioEx.getMessage()); }
                 } catch (JsonProcessingException | IllegalArgumentException e) {
                    System.err.println("Invalid PUT /orders/{id} request body: " + e.getMessage());
                    try { sendErrorResponse(req, 400, "Bad Request: " + e.getMessage()); }
                    catch (IOException ioEx) { System.err.println("IOException sending 400 error: " + ioEx.getMessage()); }
                 } catch (IOException e) { // Catch IO error from reading body stream
                    System.err.println("IO Error reading PUT request body: " + e.getMessage());
                    try { sendErrorResponse(req, 500, "Error reading request"); }
                    catch (IOException ioEx) { System.err.println("IOException sending 500 error: " + ioEx.getMessage()); }
                 }
            } else {
                 System.err.println("Unsupported PUT path: " + path);
                 try { sendErrorResponse(req, 404, "Not Found"); }
                 catch (IOException ioEx) { System.err.println("IOException sending 404 error: " + ioEx.getMessage()); }
            }
        }

        // --- DELETE Request Handler ---
        private void handleDelete(HttpExchange req, String path) {
            String[] parts = path.split("/");
             // Path: /orders/{orderId} (Cancel Order)
            if (parts.length == 3 && "orders".equals(parts[1])) {
                String orderIdStr = parts[2];
                try {
                    // Validate orderId format
                    Integer.parseInt(orderIdStr);
                    // No request body needed for DELETE

                    System.out.println("Handler asking Gateway to delete/cancel order: " + orderIdStr);
                    CompletionStage<DeleteOrder.DeleteOrderResponse> stage = AskPattern.ask(
                            gateway,
                            (ActorRef<DeleteOrder.DeleteOrderResponse> ref) -> new Gateway.DeleteOrderReq(orderIdStr, ref),
                            askTimeout,
                            scheduler);

                    handleActorResponse(req, stage, (httpExchange, response) -> {
                        try { // Handle IOException from send calls
                            if (response.success) {
                                sendJsonResponse(httpExchange, 200, response); // Send success response with message
                            } else {
                                System.err.println("Failed to cancel order " + orderIdStr + ": " + response.message);
                                // 400 implies order not found or not cancellable
                                sendJsonResponse(httpExchange, 400, response);
                            }
                        } catch (IOException e) {
                            System.err.println("IOException sending DELETE /orders/{id} response: " + e.getMessage());
                        }
                    });
                 } catch (NumberFormatException e) {
                     System.err.println("Invalid order ID format: " + orderIdStr);
                     try { sendErrorResponse(req, 400, "Invalid order ID format"); }
                     catch (IOException ioEx) { System.err.println("IOException sending 400 error: " + ioEx.getMessage()); }
                 }
            } else {
                System.err.println("Unsupported DELETE path: " + path);
                try { sendErrorResponse(req, 404, "Not Found"); }
                catch (IOException ioEx) { System.err.println("IOException sending 404 error: " + ioEx.getMessage()); }
            }
        }

        // --- Helper to handle CompletionStage results ---
        private <T> void handleActorResponse(HttpExchange req, CompletionStage<T> stage, java.util.function.BiConsumer<HttpExchange, T> onSuccess) {
            stage.whenComplete((response, throwable) -> {
                if (throwable != null) {
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    try { // Try sending error response
                        if (req.getResponseCode() == -1) { // Only send if no response sent yet
                            if (cause instanceof TimeoutException) {
                                System.err.println("Actor request timed out: " + cause.getMessage());
                                sendErrorResponse(req, 504, "Gateway Timeout");
                            } else {
                                System.err.println("Actor request failed: " + cause.getMessage());
                                cause.printStackTrace();
                                sendErrorResponse(req, 500, "Internal Server Error");
                            }
                        } else {
                            System.err.println("Ask failed but response already sent (" + req.getResponseCode() + "). Ask Error: " + cause.getMessage());
                        }
                    } catch (IOException e) {
                         System.err.println("IOException sending error response after Ask failure: " + e.getMessage());
                    }
                } else {
                    // Actor returned a response, process it using the provided success handler
                    try {
                        if (req.getResponseCode() == -1) { // Only process if no response sent yet
                             onSuccess.accept(req, response); // Lambda MUST handle its own IOExceptions now
                        } else {
                             System.err.println("Ask succeeded but response already sent (" + req.getResponseCode() + "). Discarding actor response.");
                        }
                    } catch (Exception e) { // Catch ANY exception from the success handler logic
                         System.err.println("Unexpected error processing successful actor response: " + e.getMessage());
                         e.printStackTrace();
                         if (req.getResponseCode() == -1) { // Attempt final error response
                             try { sendErrorResponse(req, 500, "Internal Server Error processing response"); }
                             catch (IOException ioex) { /* ignore secondary error */ }
                         }
                    }
                }
            });
        }

        // --- Helper to send JSON responses ---
        private void sendJsonResponse(HttpExchange req, int statusCode, Object body) throws IOException {
            if (req.getResponseCode() != -1) { // Check if response already started
                 System.err.println("Attempted to send JSON response, but response already sent ("+statusCode+")");
                 return;
            }
            byte[] jsonBytes = objectMapper.writeValueAsBytes(body);
            req.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            req.sendResponseHeaders(statusCode, jsonBytes.length);
            try (OutputStream os = req.getResponseBody()) {
                os.write(jsonBytes);
            }
             System.out.println("Sent response: " + statusCode + " " + new String(jsonBytes, StandardCharsets.UTF_8));
        }

        // --- Helper to send error responses (plain text) ---
        private void sendErrorResponse(HttpExchange req, int statusCode, String message) throws IOException {
             if (req.getResponseCode() != -1) { // Check if response already started
                 System.err.println("Attempted to send error response, but response already sent ("+statusCode+")");
                 return;
            }
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            req.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            long length = messageBytes.length > 0 ? messageBytes.length : -1; // Use -1 for no body
            req.sendResponseHeaders(statusCode, length);
            if (length > 0) {
                try (OutputStream os = req.getResponseBody()) {
                    os.write(messageBytes);
                }
            } else {
                 req.getResponseBody().close(); // Close body if no content
            }
             System.out.println("Sent error response: " + statusCode + " " + message);
        }
    } // End of Handler class


     // --- Rest of Main class (create, main methods) ---
     // Ensure these are the same as the corrected versions from previous steps,
     // including parsing the port, overriding config, spawning workers,
     // partitioned product loading, and conditional HTTP server/Gateway startup.
     // ... (create method) ...
     // ... (main method) ...

     public static Behavior<Void> create(int port) {
         return Behaviors.setup(context -> {

            ClusterSharding sharding = ClusterSharding.get(context.getSystem());
            // Use a slightly longer timeout for clustered environment potentially
            askTimeout = Duration.ofSeconds(100); // Increased timeout
            scheduler = context.getSystem().scheduler();

            // --- Worker Actor Spawning (All Nodes) ---
            int numberOfWorkers = 50; // As per spec suggestion
            context.getLog().info("Spawning {} PostOrder workers and registering with Receptionist", numberOfWorkers);
            for (int i = 0; i < numberOfWorkers; i++) {
                ActorRef<PostOrder.Command> worker = context.spawn(PostOrder.create(), "post-order-worker-" + i);
                context.getSystem().receptionist().tell(Receptionist.register(POST_ORDER_SERVICE_KEY, worker));
            }

            context.getLog().info("Spawning {} DeleteOrder workers and registering with Receptionist", numberOfWorkers);
            for (int i = 0; i < numberOfWorkers; i++) {
                ActorRef<DeleteOrder.Command> worker = context.spawn(DeleteOrder.create(), "delete-order-worker-" + i);
                context.getSystem().receptionist().tell(Receptionist.register(DELETE_ORDER_SERVICE_KEY, worker));
            }

            // --- Sharding Initialization (All Nodes) ---
             context.getLog().info("Initializing Cluster Sharding for OrderActor and ProductActor");
             sharding.init(
                Entity.of(OrderActor.TypeKey,
                (EntityContext<OrderActor.Command> entityContext) ->
                        OrderActor.create(entityContext.getEntityId())
            ));
            sharding.init(
                Entity.of(ProductActor.TypeKey,
                (EntityContext<ProductActor.Command> entityContext) ->
                        ProductActor.create(entityContext.getEntityId())
            ));


            // --- Partitioned Product Loading (All Nodes) ---
            context.getLog().info("Loading and initializing product partitions...");
            List<String[]> allProductDetails = LoadProduct.loadProducts("products.csv"); // Ensure CSV is in classpath
            int nodeIndex = port - 8083; // 0 for 8083, 1 for 8084, etc.
            int totalNodesForProducts = 2; // Distribute products across first two nodes as per spec
            context.getLog().info("Node index {} (Port {}) initializing its product partition.", nodeIndex, port);

            for (String[] productDetails : allProductDetails) {
                // Add defensive trimming and checking
                if (productDetails.length != 5) {
                     context.getLog().warn("Skipping malformed product line in CSV: {}", String.join(",", productDetails));
                     continue;
                }
                String productIdStr = productDetails[0].trim();
                try {
                    int productId = Integer.parseInt(productIdStr);
                    // Initialize product only if it belongs to this node's partition (first 2 nodes)
                    if (nodeIndex >= 0 && nodeIndex < totalNodesForProducts && (productId % totalNodesForProducts == nodeIndex)) {
                        String productName = productDetails[1].trim();
                        String productDescription = productDetails[2].trim();
                        // Trim potential whitespace before parsing numbers
                        int productPrice = Integer.parseInt(productDetails[3].trim());
                        int productQuantity = Integer.parseInt(productDetails[4].trim());

                        context.getLog().info("Node {} initializing Product {} - Name: {}, Price: {}, Qty: {}",
                                 nodeIndex, productId, productName, productPrice, productQuantity);
                        EntityRef<ProductActor.Command> productEntity =
                            sharding.entityRefFor(ProductActor.TypeKey, productIdStr);
                        // Use InitializeProduct message
                        productEntity.tell(new ProductActor.InitializeProduct(productIdStr, productName, productDescription, productPrice, productQuantity));
                    }
                 } catch (NumberFormatException e) {
                     // Log error if parsing fails
                      context.getLog().error("Skipping product due to invalid number format: {} - Error: {}", String.join(",", productDetails), e.getMessage());
                 }
            }


            // --- Conditional Startup Logic (Primary vs. Secondary) ---
            if (port == 8083) { // Primary node specific startup
                context.getLog().info("Primary node (port {}) starting HTTP server on port 8081 and Gateway actor.", port);
                gateway = context.spawn(Gateway.create(), "gateway"); // Assign static gateway ref

                HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0); // Listen on 8081
                server.createContext("/", new Handler()); // Use the static Handler class instance
                server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
                server.start();
                context.getLog().info("HTTP Server started on port 8081.");

            } else { // Secondary node specific logic (if any)
                context.getLog().info("Secondary node (port {}) joining cluster. Not starting HTTP server or Gateway.", port);
                // Secondary nodes simply join the cluster and run workers/shards
            }

            return Behaviors.empty(); // Keep root actor alive
        });
     }

     public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: Main <port>");
            System.exit(1);
        }

        final int port; // Declare final
        try {
            port = Integer.parseInt(args[0]); // Assign
        } catch (NumberFormatException e) {
            System.err.println("Invalid port number: " + args[0]);
            System.exit(1);
            return; // Needed because 'port' might not be initialized
        }
         if (port <= 0) { // Port must be positive
            System.err.println("Port must be a positive integer.");
            System.exit(1);
         }

        Config baseConfig = ConfigFactory.load(); // Loads application.conf
        Config configWithPort = ConfigFactory.parseMap(Collections.singletonMap(
                "akka.remote.artery.canonical.port", Integer.toString(port)
            )).withFallback(baseConfig);

        System.out.println("Starting Akka node on port: " + port);
        if(port == 8083) System.out.println("This is the primary node (seed node).");

        final ActorSystem<Void> system = ActorSystem.create(Main.create(port), "ClusterSystem", configWithPort);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Stopping actor system on port " + port); // Use final port
            system.terminate();
        }));
    } // End of main


} // End of Main class