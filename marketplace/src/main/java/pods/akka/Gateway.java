package pods.akka;



import java.util.List;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.cluster.sharding.typed.javadsl.EntityContext;
import pods.akka.actors.ProductActor;
import pods.akka.actors.OrderActor;
import pods.akka.actors.PostOrder;



public class Gateway extends AbstractBehavior<Gateway.Command> {
	/* This class is also a skeleton class. 
	 * Actually, Command below should be an interface. It should have the necessary number of implementing classes and corresponding event handlers. The implementing classes should have fields to hold the required elements of the http request (request path and request body), in addition to the reply-to ActorRef. The event handlers could use some suitable json parser to parse any json in the original http request body. Similarly, the Response class should be populated with the actual response.
	 */
	int responseNum = 0;
    
    private final ClusterSharding sharding;
    
    // Create a few ProductActor entities for demonstration purposes

    

	// static class Command {
	// 	ActorRef<Response> replyTo;
	// 	public Command(ActorRef<Response> r) {replyTo = r;}
    // }
    public interface Command extends CborSerializable {}

    public static final class GetProductById implements Command {
        public final String product_id;
        public final ActorRef<ProductActor.ProductResponse> replyTo;

        public GetProductById(String product_id, ActorRef<ProductActor.ProductResponse> replyTo) {
            this.product_id = product_id;
            this.replyTo = replyTo;
        }
    }



    public static final class PostOrderReq implements Command {
        public final int user_id;
        public final List<OrderActor.OrderItem> items;
        public final ActorRef<PostOrder.PostOrderResponse> replyTo;
        public PostOrderReq() {
            this.user_id = 0;
            this.items = null;
            this.replyTo = null;
        }
        public PostOrderReq(int user_id, List<OrderActor.OrderItem> items, ActorRef<PostOrder.PostOrderResponse> replyTo) {
            this.user_id = user_id;
            this.items = items;
            this.replyTo = replyTo;
        }
    }

    public static class Response {
		String resp;
		public Response(String s) {resp = s;}
	}
	
	public static Behavior<Command> create() {
        return Behaviors.setup(context -> new Gateway(context));
    }

    private Gateway(ActorContext<Command> context) {
        super(context);
        
            // Assuming ProductActor.Command and ProductActor.TypeKey are defined elsewhere
            ClusterSharding sharding = ClusterSharding.get(context.getSystem());
            sharding.init(
                Entity.of(ProductActor.TypeKey,
                (EntityContext<ProductActor.Command> entityContext) ->
                        ProductActor.create(entityContext.getEntityId())
            ));


            // load product details form CSV
                        
            List<String[]> loadProductDetails = LoadProduct.loadProducts("products.csv");

            // Log all products in the loaded list
            for (String[] productDetails : loadProductDetails) {
                String product_id = productDetails[0];
                String productName = productDetails[1];
                String productDescription = productDetails[2];
                double productPrice = Double.parseDouble(productDetails[3]);
                int productQuantity = Integer.parseInt(productDetails[4]);

                getContext().getLog().info("Loaded Product - ID: {}, Name: {}, Description: {},Price: {}, Quantity: {}", 
                                           product_id, productName,
                                           productDescription, productPrice, productQuantity);

                // Create ProductActor entities for each product
                 sharding.entityRefFor(ProductActor.TypeKey, product_id)
                         .tell(new ProductActor.InitializeProduct(product_id, productName, productDescription, (int) productPrice, productQuantity));
            }
            // Create ProductActor entities with IDs 101 and 102
            // EntityRef<ProductActor.Command> product101 = sharding.entityRefFor(ProductActor.TypeKey, "101");
            // EntityRef<ProductActor.Command> product102 = sharding.entityRefFor(ProductActor.TypeKey, "102");

   
        
        this.sharding = ClusterSharding.get(context.getSystem());
    }
    
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder().
        onMessage(GetProductById.class, this::onGetProductById)
        .onMessage(PostOrderReq.class, this::onPostOrderReq)
        .build();
    }
    
    private Behavior<Command> onGetProductById(GetProductById req) {

        EntityRef<ProductActor.Command> productEntity =
            sharding.entityRefFor(ProductActor.TypeKey, req.product_id);
        
        productEntity.tell(new ProductActor.GetProduct(req.replyTo));
        return this;
      }
      private Behavior<Command> onPostOrderReq(PostOrderReq req) {
        
        ActorRef<PostOrder.Command> postOrderWorker = 
            getContext().spawn(PostOrder.create(), "PostOrderWorker-" + responseNum++);
        
        postOrderWorker.tell(new PostOrder.StartOrder(req.user_id,req.items,req.replyTo));
        // productEntity.tell(new ProductActor.GetProduct(req.replyTo));
        return this;
      }

}
