package pods.akka;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Scheduler;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.AskPattern;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.GroupRouter;
import akka.actor.typed.javadsl.Routers;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import akka.cluster.typed.Cluster;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.javadsl.EntityContext;
import akka.cluster.sharding.typed.javadsl.EntityRef;
// import akka.event.LoggingAdapter; // REMOVED Akka Logger Import
import org.slf4j.Logger;          // ADDED SLF4J Logger Import

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import pods.akka.actors.DeleteOrder;
import pods.akka.actors.OrderActor;
import pods.akka.actors.PostOrder;
import pods.akka.actors.ProductActor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class Main {

    // Service Keys for Routers
    public static final ServiceKey<PostOrder.Command> POST_ORDER_ROUTER_KEY =
            ServiceKey.create(PostOrder.Command.class, "postOrderRouter");
    public static final ServiceKey<DeleteOrder.Command> DELETE_ORDER_ROUTER_KEY =
            ServiceKey.create(DeleteOrder.Command.class, "deleteOrderRouter");

    // Configuration constants
    private static final int PRIMARY_HTTP_PORT = 8081;
    private static final int PRIMARY_NODE_AKKA_PORT = 8083;
    private static final int WORKER_POOL_SIZE = 50;
    private static final String PRODUCT_CSV_FILE = "products.csv";

    // --- Root Actor Behavior ---
    public static Behavior<Void> createRootBehavior(int akkaPort) {
        return Behaviors.setup(context -> {
            // Get the SLF4J Logger from context
            final Logger log = context.getLog(); 

            // log.info("Starting node with Akka port: {}", akkaPort); // LOG REMOVED
            Cluster cluster = Cluster.get(context.getSystem());
            ClusterSharding sharding = ClusterSharding.get(context.getSystem());
            // log.info("Node {} joining cluster with seed node {}", // LOG REMOVED
            //        cluster.selfMember().address(), ConfigFactory.load().getStringList("akka.cluster.seed-nodes"));

            boolean isPrimaryNode = (akkaPort == PRIMARY_NODE_AKKA_PORT);
            // log.info("Is Primary Node (port {} == {}): {}", akkaPort, PRIMARY_NODE_AKKA_PORT, isPrimaryNode); // LOG REMOVED

            // Initialize Cluster Sharding (All Nodes)
            sharding.init(
                Entity.of(OrderActor.TypeKey,
                    (EntityContext<OrderActor.Command> entityContext) ->
                        OrderActor.create(entityContext.getEntityId())
                )
            );
            sharding.init(
                Entity.of(ProductActor.TypeKey,
                    (EntityContext<ProductActor.Command> entityContext) ->
                        ProductActor.create(entityContext.getEntityId())
                 )
            );
            // log.info("Cluster Sharding initialized for OrderActor and ProductActor."); // LOG REMOVED

            // Spawn Worker Actors and Register (All Nodes)
            spawnAndRegisterWorkers(context, PostOrder::create, POST_ORDER_ROUTER_KEY, WORKER_POOL_SIZE);
            spawnAndRegisterWorkers(context, DeleteOrder::create, DELETE_ORDER_ROUTER_KEY, WORKER_POOL_SIZE);

            // Partitioned Product Initialization (All Nodes)
            initializePartitionedProducts(context, sharding, cluster);

            // Start HTTP Server and Gateway (Primary Node ONLY)
            if (isPrimaryNode) {
                 // log.info("Primary node starting HTTP server on port {} and Gateway actor.", PRIMARY_HTTP_PORT); // LOG REMOVED
                 GroupRouter<PostOrder.Command> postOrderRouter = Routers.group(POST_ORDER_ROUTER_KEY);
                 GroupRouter<DeleteOrder.Command> deleteOrderRouter = Routers.group(DELETE_ORDER_ROUTER_KEY);
                 ActorRef<Gateway.Command> gatewayActor = context.spawn(
                         Gateway.create(
                                 context.spawn(postOrderRouter, "postOrderRouter"),
                                 context.spawn(deleteOrderRouter, "deleteOrderRouter")
                         ), "gateway");

                 // Pass the SLF4J logger obtained above
                 startHttpServer(context, log, gatewayActor, PRIMARY_HTTP_PORT); 
            } else {
                 // log.info("Secondary node {} initialized workers, sharding, and joined cluster.", akkaPort); // LOG REMOVED
            }

            return Behaviors.empty();
        });
    }

    // --- Helper Methods ---

    private static <T extends CborSerializable> void spawnAndRegisterWorkers(
            ActorContext<?> context,
            Supplier<Behavior<T>> behaviorSupplier, 
            ServiceKey<T> serviceKey,
            int poolSize) {
        final Logger log = context.getLog(); // Get logger if needed inside
        // log.info("Spawning {} worker actors for service key {}", poolSize, serviceKey.id()); // LOG REMOVED
        for (int i = 0; i < poolSize; i++) {
            ActorRef<T> worker = context.spawn(behaviorSupplier.get(), serviceKey.id() + "-" + i); 
            context.getSystem().receptionist().tell(Receptionist.register(serviceKey, worker));
        }
        // log.info("Workers registered for service key {}", serviceKey.id()); // LOG REMOVED
    }

    private static void initializePartitionedProducts(ActorContext<?> context, ClusterSharding sharding, Cluster cluster) {
         final Logger log = context.getLog(); // Get logger if needed inside
        List<String[]> allProductDetails = LoadProduct.loadProducts(PRODUCT_CSV_FILE);
        if (allProductDetails.isEmpty()) {
            // log.warn("No products found in {}. Skipping product initialization.", PRODUCT_CSV_FILE); // LOG REMOVED
            return;
        }
        // log.info("Loaded {} products from CSV. Starting partitioned initialization.", allProductDetails.size()); // LOG REMOVED
        String selfNodeAddress = cluster.selfMember().address().toString();
        int selfNodeHashCode = Math.abs(selfNodeAddress.hashCode());
        int partitionCount = 10; 
        int assignedPartition = selfNodeHashCode % partitionCount;
        // log.info("Node {} responsible for partition {}", selfNodeAddress, assignedPartition); // LOG REMOVED
        int initializedCount = 0;
        for (String[] productData : allProductDetails) {
            if (productData.length == 5) {
                try {
                    String productId = productData[0];
                    int productPartition = Math.abs(productId.hashCode()) % partitionCount;
                    if (productPartition == assignedPartition) {
                        EntityRef<ProductActor.Command> productEntity =
                                sharding.entityRefFor(ProductActor.TypeKey, productId);
                        productEntity.tell(new ProductActor.InitializeProduct(
                                productId, productData[1], productData[2],
                                Integer.parseInt(productData[3]), Integer.parseInt(productData[4])));
                        initializedCount++;
                        // log.debug("Node {}: Initializing Product ID {}", selfNodeAddress, productId); // LOG REMOVED
                    }
                } catch (NumberFormatException e) {
                    // log.error("Skipping product line due to invalid number: {}", String.join(",", productData)); // LOG REMOVED
                }
            } else {
                // log.warn("Skipping invalid product line in CSV: {}", String.join(",", productData)); // LOG REMOVED
            }
        }
        // log.info("Node {} initialized {} products for partition {}.", selfNodeAddress, initializedCount, assignedPartition); // LOG REMOVED
    }

    // Modify to accept SLF4J Logger
    private static void startHttpServer(
            ActorContext<?> originalContext, 
            Logger log, // Expect SLF4J Logger
            ActorRef<Gateway.Command> gateway, 
            int httpPort) {
                
        Scheduler scheduler = originalContext.getSystem().scheduler(); 
        Duration askTimeout = Duration.ofSeconds(5);
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(httpPort), 0);
            // Pass the SLF4J Logger directly to the handler constructor
            server.createContext("/", new MarketplaceHttpHandler(gateway, scheduler, askTimeout, log)); // Pass SLF4J log
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            // log.info("HTTP server started on port {}", httpPort); // LOG REMOVED
        } catch (IOException e) {
            // log.error("Failed to start HTTP server on port " + httpPort, e); // LOG REMOVED
            e.printStackTrace(); 
        }
    }

    // --- Main Entry Point --- 
    public static void main(String[] args) {
        String portString = System.getProperty("exec.args");
        if (portString == null) {
            System.err.println("Missing Akka port argument. Use -Dexec.args=<port>");
            System.exit(1);
        }
        int akkaPort;
        try {
            akkaPort = Integer.parseInt(portString);
        } catch (NumberFormatException e) {
            System.err.println("Invalid Akka port provided: " + portString);
            System.exit(1);
            return;
        }
        Config baseConfig = ConfigFactory.load();
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("akka.remote.artery.canonical.port", akkaPort);
        Config finalConfig = ConfigFactory.parseMap(overrides).withFallback(baseConfig);

        System.out.println("Starting Akka node on port: " + akkaPort);
        // System.out.println("Effective Akka Config Port: " + finalConfig.getInt("akka.remote.artery.canonical.port")); // LOG REMOVED
        // System.out.println("Configured Seed Nodes: " + finalConfig.getStringList("akka.cluster.seed-nodes")); // LOG REMOVED

        final ActorSystem<Void> system = ActorSystem.create(createRootBehavior(akkaPort), "ClusterSystem", finalConfig);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
             System.out.println("Shutdown hook triggered. Terminating ActorSystem...");
             system.terminate();
        }));

        // System.out.println("ActorSystem " + system.name() + " started. Node address: " + Cluster.get(system).selfMember().address()); // LOG REMOVED
    }
} // End of Main class


// --- HTTP Handler Class ---
class MarketplaceHttpHandler implements HttpHandler {

    private final ActorRef<Gateway.Command> gateway;
    private final Scheduler scheduler;
    private final Duration askTimeout;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Logger log; // Use SLF4J Logger

    // Constructor expects SLF4J Logger
    public MarketplaceHttpHandler(ActorRef<Gateway.Command> gateway, Scheduler scheduler, Duration askTimeout, Logger log) { 
        this.gateway = gateway;
        this.scheduler = scheduler;
        this.askTimeout = askTimeout;
        this.log = log; // Assign SLF4J logger
    }
    
    @Override
    public void handle(HttpExchange req) throws IOException {
        String path = req.getRequestURI().getPath();
        String method = req.getRequestMethod();
        // log.info("Received HTTP request: {} {}", method, path); // LOG REMOVED

        try {
            if ("GET".equals(method)) handleGet(req, path);
            else if ("POST".equals(method)) handlePost(req, path);
            else if ("PUT".equals(method)) handlePut(req, path);
            else if ("DELETE".equals(method)) handleDelete(req, path);
            else sendResponse(req, 405, "Method Not Allowed", null);
        } catch (Exception e) {
            // log.error("Error handling HTTP request {} {}: {}", method, path, e.getMessage(), e); // LOG REMOVED
            e.printStackTrace();
            try {
                 if(req.getResponseCode() == -1) {
                     sendResponse(req, 500, "Internal Server Error", null);
                 }
            } catch (IOException ioEx) {
                // log.error("Error sending 500 response: {}", ioEx.getMessage()); // LOG REMOVED
                ioEx.printStackTrace();
            }
        }
    }

    private void handleGet(HttpExchange req, String path) throws IOException {
        String[] parts = path.split("/");
        if (parts.length == 3) {
            String resourceType = parts[1];
            String resourceId = parts[2];
            if ("products".equals(resourceType)) handleGetProduct(req, resourceId);
            else if ("orders".equals(resourceType)) handleGetOrder(req, resourceId);
            else sendResponse(req, 404, "Not Found", null);
        } else {
             sendResponse(req, 400, "Bad Request: Invalid path format", null);
        }
    }

    // --- GET Handlers ---
    private void handleGetProduct(HttpExchange req, String productId) {
        CompletionStage<ProductActor.ProductResponse> compl = AskPattern.ask(
                gateway, (ActorRef<ProductActor.ProductResponse> ref) -> new Gateway.GetProductById(productId, ref),
                askTimeout, scheduler);

        compl.whenCompleteAsync((response, failure) -> {
            try {
                if (failure != null) {
                    // log.error(...) // LOG REMOVED
                    sendResponse(req, 500, "Internal Server Error (Ask Timeout/Failure)", null);
                } else {
                    if (response.price >= 0 && !"Not Initialized".equals(response.name)) {
                        sendResponse(req, 200, "application/json", response);
                    } else {
                        // log.info(...) // LOG REMOVED
                        sendResponse(req, 404, "Product Not Found", null);
                    }
                }
            } catch (IOException e) {
                 // log.error(...) // LOG REMOVED
                 e.printStackTrace(); 
            } finally {
                try { req.getResponseBody().close(); } catch (IOException e) { e.printStackTrace(); }
            }
        }, Executors.newSingleThreadExecutor());
    }

    private void handleGetOrder(HttpExchange req, String orderId) {
        CompletionStage<OrderActor.OrderResponse> compl = AskPattern.ask(
                gateway, (ActorRef<OrderActor.OrderResponse> ref) -> new Gateway.GetOrderById(orderId, ref),
                askTimeout, scheduler);

        compl.whenCompleteAsync((response, failure) -> {
            try {
                if (failure != null) {
                    // log.error(...) // LOG REMOVED
                    sendResponse(req, 500, "Internal Server Error (Ask Timeout/Failure)", null);
                } else {
                    if (response.order_id != 0 && !"NotInitialized".equals(response.status)) {
                        sendResponse(req, 200, "application/json", response);
                    } else {
                        // log.info(...) // LOG REMOVED
                        sendResponse(req, 404, "Order Not Found", null);
                    }
                }
            } catch (IOException e) {
                 // log.error(...) // LOG REMOVED
                 e.printStackTrace();
            } finally {
                try { req.getResponseBody().close(); } catch (IOException e) { e.printStackTrace(); }
            }
        }, Executors.newSingleThreadExecutor());
    }

    // --- POST Handler ---
    private void handlePost(HttpExchange req, String path) throws IOException {
         if ("/orders".equals(path)) {
             Gateway.PostOrderReq orderRequest;
             try {
                 orderRequest = objectMapper.readValue(req.getRequestBody(), Gateway.PostOrderReq.class);
                 if (orderRequest.items == null || orderRequest.items.isEmpty()) {
                     sendResponse(req, 400, "Bad Request: Order must contain items", null);
                     return;
                 }
             } catch (Exception e) {
                 // log.error(...) // LOG REMOVED
                 e.printStackTrace();
                 sendResponse(req, 400, "Bad Request: Invalid JSON format", null);
                 return;
             }

             CompletionStage<PostOrder.PostOrderResponse> compl = AskPattern.ask(
                     gateway, (ActorRef<PostOrder.PostOrderResponse> ref) -> new Gateway.PostOrderReq(orderRequest.user_id, orderRequest.items, ref),
                     askTimeout, scheduler);

             compl.whenCompleteAsync((response, failure) -> {
                 try {
                     if (failure != null) {
                         // log.error(...) // LOG REMOVED
                         sendResponse(req, 500, "Internal Server Error (Order processing timeout/failure)", null);
                     } else {
                         if (response.success) {
                             // log.info(...) // LOG REMOVED
                             sendResponse(req, 201, "application/json", response.orderResponse);
                         } else {
                             int statusCode = response.message != null && response.message.contains("Insufficient") ? 400 : 500;
                             sendResponse(req, statusCode, "Order creation failed: " + response.message, null);
                         }
                     }
                 } catch (IOException e) {
                     // log.error(...) // LOG REMOVED
                     e.printStackTrace();
                 } finally {
                     try { req.getResponseBody().close(); } catch (IOException e) { e.printStackTrace(); }
                 }
             }, Executors.newSingleThreadExecutor());

         } else {
              sendResponse(req, 404, "Not Found", null);
         }
    }

    // --- PUT Handler ---
    private void handlePut(HttpExchange req, String path) throws IOException {
        String[] parts = path.split("/");
        if (parts.length == 3 && "orders".equals(parts[1])) {
            String orderId = parts[2];
            Map<String, Object> requestBody;
            try {
                requestBody = objectMapper.readValue(req.getRequestBody(), new TypeReference<Map<String, Object>>(){});
                 if (!orderId.equals(requestBody.get("order_id")) || !"DELIVERED".equalsIgnoreCase((String)requestBody.get("status"))) {
                     sendResponse(req, 400, "Bad Request: order_id mismatch or status not DELIVERED", null);
                     return;
                 }
            } catch (Exception e) {
                 // log.error(...) // LOG REMOVED
                 e.printStackTrace();
                 sendResponse(req, 400, "Bad Request: Invalid JSON format or missing fields", null);
                 return;
            }

            CompletionStage<OrderActor.OperationResponse> compl = AskPattern.ask(
                    gateway, (ActorRef<OrderActor.OperationResponse> ref) -> new Gateway.PutOrderReq(orderId, ref),
                    askTimeout, scheduler);

            compl.whenCompleteAsync((response, failure) -> {
                 try {
                     if (failure != null) {
                         // log.error(...) // LOG REMOVED
                         sendResponse(req, 500, "Internal Server Error (Update timeout/failure)", null);
                     } else {
                         if (response.success) {
                             // log.info(...) // LOG REMOVED
                             Map<String, Object> minimalResponse = Map.of("order_id", response.order_id, "status", response.status);
                             sendResponse(req, 200, "application/json", minimalResponse);
                         } else {
                             int statusCode = response.message != null && response.message.contains("terminal state") ? 400 : 404;
                             sendResponse(req, statusCode, "Failed to update order status: " + response.message, null);
                         }
                     }
                 } catch (IOException e) {
                     // log.error(...) // LOG REMOVED
                     e.printStackTrace();
                 } finally {
                    try { req.getResponseBody().close(); } catch (IOException e) { e.printStackTrace(); }
                 }
             }, Executors.newSingleThreadExecutor());

        } else {
             sendResponse(req, 400, "Bad Request: Invalid path for PUT", null);
        }
    }

    // --- DELETE Handler ---
    private void handleDelete(HttpExchange req, String path) throws IOException {
         String[] parts = path.split("/");
        if (parts.length == 3 && "orders".equals(parts[1])) {
            String orderId = parts[2];
            CompletionStage<DeleteOrder.DeleteOrderResponse> compl = AskPattern.ask(
                    gateway, (ActorRef<DeleteOrder.DeleteOrderResponse> ref) -> new Gateway.DeleteOrderReq(orderId, ref),
                    askTimeout, scheduler);

            compl.whenCompleteAsync((response, failure) -> {
                 try {
                     if (failure != null) {
                         // log.error(...) // LOG REMOVED
                         sendResponse(req, 500, "Internal Server Error (Delete timeout/failure)", null);
                     } else {
                         if (response.success) {
                             // log.info(...) // LOG REMOVED
                             sendResponse(req, 200, "application/json", response);
                         } else {
                             int statusCode = response.message != null && response.message.contains("not found") ? 404 : 400;
                             sendResponse(req, statusCode, "Failed to delete order: " + response.message, null);
                         }
                     }
                 } catch (IOException e) {
                     // log.error(...) // LOG REMOVED
                     e.printStackTrace();
                 } finally {
                    try { req.getResponseBody().close(); } catch (IOException e) { e.printStackTrace(); }
                 }
             }, Executors.newSingleThreadExecutor());

        } else {
             sendResponse(req, 400, "Bad Request: Invalid path for DELETE", null);
        }
    }

    // --- Send Response Helper ---
     private void sendResponse(HttpExchange exchange, int statusCode, String contentType, Object responseBodyObject) throws IOException {
        byte[] responseBytes = new byte[0];
        long responseLength = 0;
        boolean hasBody = false;

        if (responseBodyObject != null) {
            if (contentType != null && contentType.equals("application/json")) {
                responseBytes = objectMapper.writeValueAsBytes(responseBodyObject);
            } else {
                responseBytes = responseBodyObject.toString().getBytes(StandardCharsets.UTF_8);
                if (contentType == null) contentType = "text/plain; charset=utf-8";
            }
            if(responseBytes.length > 0) {
                responseLength = responseBytes.length;
                hasBody = true;
            } else {
                responseLength = -1;
            }
        } else {
             responseLength = -1;
             if (statusCode == 204) contentType = null;
        }
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(statusCode, responseLength);
        if (hasBody) {
            try (OutputStream os = exchange.getResponseBody()) { os.write(responseBytes); }
        } else {
             try (OutputStream os = exchange.getResponseBody()) { /* Close empty body */ }
        }
        // log.debug("Sent HTTP Response: Status {}", statusCode); // LOG REMOVED
    }

} // End of MarketplaceHttpHandler class