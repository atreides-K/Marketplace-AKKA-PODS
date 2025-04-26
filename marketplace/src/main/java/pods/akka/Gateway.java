package pods.akka;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.Routers; // Import Routers
import akka.actor.typed.javadsl.GroupRouter; // Import GroupRouter

import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.EntityRef;
// No need for Entity/EntityContext imports here if not initializing shards

// Import actor classes and messages
import pods.akka.actors.ProductActor;
import pods.akka.actors.DeleteOrder;
import pods.akka.actors.OrderActor;
import pods.akka.actors.PostOrder;

public class Gateway extends AbstractBehavior<Gateway.Command> {

    // --- Command Interface and Concrete Commands (Keep as is) ---
    public interface Command extends CborSerializable {}
    public static final class GetProductById implements Command { /* ... as before ... */
        public final String product_id;
        public final ActorRef<ProductActor.ProductResponse> replyTo;
        @JsonCreator // Even if not sent across nodes, good practice
        public GetProductById(
             @JsonProperty("product_id") String product_id,
             @JsonProperty("replyTo") ActorRef<ProductActor.ProductResponse> replyTo) {
            this.product_id = product_id;
            this.replyTo = replyTo;
        }
    }
    public static final class PostOrderReq implements Command { /* ... as before ... */
        public final int user_id;
        public final List<OrderActor.OrderItem> items;
        public final ActorRef<PostOrder.PostOrderResponse> replyTo;
        // Ensure constructors are correctly defined
        public PostOrderReq() { this.user_id = 0; this.items = null; this.replyTo = null; }
        @JsonCreator
        public PostOrderReq(
             @JsonProperty("user_id") int user_id,
             @JsonProperty("items") List<OrderActor.OrderItem> items,
             @JsonProperty("replyTo") ActorRef<PostOrder.PostOrderResponse> replyTo) {
            this.user_id = user_id;
            this.items = items;
            this.replyTo = replyTo;
        }
    }
    public static final class GetOrderById implements Command { /* ... as before ... */
        public final String order_id;
        public final ActorRef<OrderActor.OrderResponse> replyTo;
        @JsonCreator
        public GetOrderById(
             @JsonProperty("order_id") String order_id,
             @JsonProperty("replyTo") ActorRef<OrderActor.OrderResponse> replyTo) {
            this.order_id = order_id;
            this.replyTo = replyTo;
        }
    }
    public static final class PutOrderReq implements Command { /* ... as before ... */
        public final String order_id;
        public final ActorRef<OrderActor.OperationResponse> replyTo;
        @JsonCreator
        public PutOrderReq(
             @JsonProperty("order_id") String order_id,
             @JsonProperty("replyTo") ActorRef<OrderActor.OperationResponse> replyTo) {
            this.order_id = order_id;
            this.replyTo = replyTo;
        }
    }
    public static final class DeleteOrderReq implements Command { /* ... as before ... */
        public final String order_id;
        public final ActorRef<DeleteOrder.DeleteOrderResponse> replyTo;
        @JsonCreator
        public DeleteOrderReq(
             @JsonProperty("order_id") String order_id,
             @JsonProperty("replyTo") ActorRef<DeleteOrder.DeleteOrderResponse> replyTo) {
            this.order_id = order_id;
            this.replyTo = replyTo;
        }
    }
    // Response class might not be needed if individual commands have specific reply types
    // public static class Response { ... }

    // --- State Variables ---
    private final ClusterSharding sharding;
    // Routers for worker pools
    private final ActorRef<PostOrder.Command> postOrderRouter;
    private final ActorRef<DeleteOrder.Command> deleteOrderRouter;

    // --- Factory and Constructor ---
    public static Behavior<Command> create() {
        return Behaviors.setup(context -> new Gateway(context));
    }

    private Gateway(ActorContext<Command> context) {
        super(context);
        this.sharding = ClusterSharding.get(context.getSystem());

        // REMOVE Product Loading/Initialization from here
        // It's now done partition-wise in Main.create()

        // Create Group Routers
        // These routers will discover workers registered with the Receptionist
        this.postOrderRouter = context.spawn(Routers.group(Main.POST_ORDER_SERVICE_KEY), "post-order-router");
        this.deleteOrderRouter = context.spawn(Routers.group(Main.DELETE_ORDER_SERVICE_KEY), "delete-order-router");

        context.getLog().info("Gateway actor started and created worker routers.");
    }

    // --- Receive Builder ---
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(GetProductById.class, this::onGetProductById) // Stays the same
                .onMessage(PostOrderReq.class, this::onPostOrderReq)     // MODIFIED
                .onMessage(GetOrderById.class, this::onGetOrderById)     // Stays the same
                .onMessage(PutOrderReq.class, this::onPutOrderReq)       // Stays the same
                .onMessage(DeleteOrderReq.class, this::onDeleteOrderReq) // MODIFIED
                .build();
    }

    // --- Message Handlers ---

    // Handlers for Sharded Entities (GetProduct, GetOrder, PutOrder) remain the same
    private Behavior<Command> onGetProductById(GetProductById req) {
        EntityRef<ProductActor.Command> productEntity =
            sharding.entityRefFor(ProductActor.TypeKey, req.product_id);
        // Forward the request including the original replyTo
        productEntity.tell(new ProductActor.GetProduct(req.replyTo));
        return this;
    }

     private Behavior<Command> onGetOrderById(GetOrderById req) {
        EntityRef<OrderActor.Command> orderEntity =
            sharding.entityRefFor(OrderActor.TypeKey, req.order_id);
        // Forward the request including the original replyTo
        orderEntity.tell(new OrderActor.GetOrder(req.replyTo));
        return this;
    }

    private Behavior<Command> onPutOrderReq(PutOrderReq req) {
        EntityRef<OrderActor.Command> orderEntity =
            sharding.entityRefFor(OrderActor.TypeKey, req.order_id);
        // Forward the request including the original replyTo
        orderEntity.tell(new OrderActor.UpdateStatus("DELIVERED", req.replyTo));
        return this;
    }


    // MODIFIED: Use Router for PostOrder
    private Behavior<Command> onPostOrderReq(PostOrderReq req) {
        getContext().getLog().info("Gateway forwarding PostOrderReq for user {} to router", req.user_id);
        // Send the request to the router. The router distributes it to a worker.
        // The worker will use req.replyTo to respond back to the original asker (Handler).
        postOrderRouter.tell(new PostOrder.StartOrder(req.user_id, req.items, req.replyTo));
        return this;
    }

    // MODIFIED: Use Router for DeleteOrder
    private Behavior<Command> onDeleteOrderReq(DeleteOrderReq req) {
         getContext().getLog().info("Gateway forwarding DeleteOrderReq for order {} to router", req.order_id);
         // Send the request to the router.
        deleteOrderRouter.tell(new DeleteOrder.StartDelete(req.order_id, req.replyTo));
        return this;
    }
}