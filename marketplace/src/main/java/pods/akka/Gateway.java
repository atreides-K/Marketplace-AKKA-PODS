package pods.akka;

import java.util.List;
import java.util.Map; // Import Map

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.ServiceKey; // Needed if using Receptionist for routers
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import pods.akka.actors.ProductActor;
import pods.akka.actors.DeleteOrder;
import pods.akka.actors.OrderActor;
import pods.akka.actors.PostOrder;

// PHASE 2: Gateway runs only on primary node, interacts with sharded entities and ROUTERS
public class Gateway extends AbstractBehavior<Gateway.Command> {

    // Define ServiceKeys if using Receptionist for routers (alternative to constructor injection)
    // public static final ServiceKey<PostOrder.Command> POST_ORDER_ROUTER_KEY = ServiceKey.create(PostOrder.Command.class, "postOrderRouter");
    // public static final ServiceKey<DeleteOrder.Command> DELETE_ORDER_ROUTER_KEY = ServiceKey.create(DeleteOrder.Command.class, "deleteOrderRouter");

    // --- Command Protocol ---
    public interface Command extends CborSerializable {} // Mark commands as serializable

    // GET /products/{productId}
    public static final class GetProductById implements Command {
        public final String productId; // Use String to match EntityTypeKey
        public final ActorRef<ProductActor.ProductResponse> replyTo;

        public GetProductById(String productId, ActorRef<ProductActor.ProductResponse> replyTo) {
            this.productId = productId;
            this.replyTo = replyTo;
        }
    }

    // POST /orders
    // Note: Jackson needs help deserializing nested generic lists.
    // Consider using a wrapper class or ensuring OrderItem has @JsonCreator.
    public static final class PostOrderReq implements Command {
        public final int user_id;
        public final List<OrderActor.OrderItem> items; // Ensure OrderItem is deserializable
        public final ActorRef<PostOrder.PostOrderResponse> replyTo;

        // Constructor for Jackson/Manual creation
        public PostOrderReq(int user_id, List<OrderActor.OrderItem> items, ActorRef<PostOrder.PostOrderResponse> replyTo) {
            this.user_id = user_id;
            this.items = items;
            this.replyTo = replyTo;
        }
         // Default constructor might be needed by Jackson depending on setup
         public PostOrderReq() { this.user_id = 0; this.items = null; this.replyTo = null; }
    }

    // GET /orders/{orderId}
    public static final class GetOrderById implements Command {
        public final String orderId; // Use String to match EntityTypeKey
        public final ActorRef<OrderActor.OrderResponse> replyTo;

        public GetOrderById(String orderId, ActorRef<OrderActor.OrderResponse> replyTo) {
            this.orderId = orderId;
            this.replyTo = replyTo;
        }
    }

    // PUT /orders/{orderId} (Mark as DELIVERED)
    public static final class PutOrderReq implements Command {
        public final String orderId; // Use String to match EntityTypeKey
        public final ActorRef<OrderActor.OperationResponse> replyTo;

        public PutOrderReq(String orderId, ActorRef<OrderActor.OperationResponse> replyTo) {
            this.orderId = orderId;
            this.replyTo = replyTo;
        }
    }

    // DELETE /orders/{orderId}
    public static final class DeleteOrderReq implements Command {
        public final String orderId; // Use String to match EntityTypeKey
        public final ActorRef<DeleteOrder.DeleteOrderResponse> replyTo;

        public DeleteOrderReq(String orderId, ActorRef<DeleteOrder.DeleteOrderResponse> replyTo) {
            this.orderId = orderId;
            this.replyTo = replyTo;
        }
    }

    // --- State ---
    private final ClusterSharding sharding;
    // PHASE 2 CHANGE: Routers are injected, not created here
    private final ActorRef<PostOrder.Command> postOrderRouter;
    private final ActorRef<DeleteOrder.Command> deleteOrderRouter;

    // --- Factory and Constructor ---
    public static Behavior<Command> create(
            ActorRef<PostOrder.Command> postOrderRouter,
            ActorRef<DeleteOrder.Command> deleteOrderRouter) {
        return Behaviors.setup(context -> new Gateway(context, postOrderRouter, deleteOrderRouter));
    }

    private Gateway(ActorContext<Command> context,
                    ActorRef<PostOrder.Command> postOrderRouter,
                    ActorRef<DeleteOrder.Command> deleteOrderRouter) {
        super(context);
        this.sharding = ClusterSharding.get(context.getSystem());
        this.postOrderRouter = postOrderRouter;
        this.deleteOrderRouter = deleteOrderRouter;

        context.getLog().info("Gateway actor started on primary node.");

        // PHASE 2 CHANGE: Product initialization is REMOVED from Gateway.
        // It's now handled distributively by nodes in Main.java during startup.
    }

    // --- Receive Logic ---
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(GetProductById.class, this::onGetProductById)
            .onMessage(PostOrderReq.class, this::onPostOrderReq) // PHASE 2: Route this
            .onMessage(GetOrderById.class, this::onGetOrderById)
            .onMessage(PutOrderReq.class, this::onPutOrderReq)
            .onMessage(DeleteOrderReq.class, this::onDeleteOrderReq) // PHASE 2: Route this
            .build();
    }

    // --- Message Handlers ---

    // Handles GET /products/{productId}
    private Behavior<Command> onGetProductById(GetProductById req) {
        EntityRef<ProductActor.Command> productEntity =
            sharding.entityRefFor(ProductActor.TypeKey, req.productId); // Use String ID

        getContext().getLog().debug("Routing GetProduct request for ID {} to sharding", req.productId);
        // Ask the sharded entity
        productEntity.tell(new ProductActor.GetProduct(req.replyTo));
        return this;
    }

    // Handles POST /orders
    private Behavior<Command> onPostOrderReq(PostOrderReq req) {
        getContext().getLog().info("Routing PostOrder request for user {} to PostOrder router", req.user_id);

        // PHASE 2 CHANGE: Send message to the PostOrder ROUTER, not spawn locally
        postOrderRouter.tell(new PostOrder.StartOrder(req.user_id, req.items, req.replyTo));

        return this;
    }

    // Handles GET /orders/{orderId}
    private Behavior<Command> onGetOrderById(GetOrderById req) {
        EntityRef<OrderActor.Command> orderEntity =
            sharding.entityRefFor(OrderActor.TypeKey, req.orderId); // Use String ID

        getContext().getLog().debug("Routing GetOrder request for ID {} to sharding", req.orderId);
        orderEntity.tell(new OrderActor.GetOrder(req.replyTo));
        return this;
    }

    // Handles PUT /orders/{orderId}
    private Behavior<Command> onPutOrderReq(PutOrderReq req) {
        EntityRef<OrderActor.Command> orderEntity =
            sharding.entityRefFor(OrderActor.TypeKey, req.orderId); // Use String ID

        getContext().getLog().debug("Routing PutOrder (UpdateStatus) request for ID {} to sharding", req.orderId);
        // Send the UpdateStatus message to mark as DELIVERED
        orderEntity.tell(new OrderActor.UpdateStatus("DELIVERED", req.replyTo));
        return this;
    }

    // Handles DELETE /orders/{orderId}
    private Behavior<Command> onDeleteOrderReq(DeleteOrderReq req) {
        getContext().getLog().info("Routing DeleteOrder request for ID {} to DeleteOrder router", req.orderId);

        // PHASE 2 CHANGE: Send message to the DeleteOrder ROUTER, not spawn locally
        deleteOrderRouter.tell(new DeleteOrder.StartDelete(req.orderId, req.replyTo));

        return this;
    }
}