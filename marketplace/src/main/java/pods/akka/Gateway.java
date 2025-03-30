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
import pods.akka.actors.ProductActor;
import akka.cluster.sharding.typed.javadsl.EntityContext;

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
    public interface Command extends CborSerializable{}

    public static final record GetProductById(String productId, ActorRef<Response> replyTo) implements Command{

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
                        ProductActor.create(entityContext.getEntityId(),"unknown",0,0)
            ));


            // load product details form CSV
                        
            List<String[]> loadProductDetails = LoadProduct.loadProducts("products.csv");

            // Log all products in the loaded list
            for (String[] productDetails : loadProductDetails) {
                String productId = productDetails[0];
                String productName = productDetails[1];
                String productDescription = productDetails[2];
                double productPrice = Double.parseDouble(productDetails[3]);
                int productQuantity = Integer.parseInt(productDetails[4]);

                getContext().getLog().info("Loaded Product - ID: {}, Name: {}, Description: {},Price: {}, Quantity: {}", 
                                           productId, productName,
                                           productDescription, productPrice, productQuantity);

                // Create ProductActor entities for each product
            //     sharding.entityRefFor(ProductActor.TypeKey, productId)
            //             .tell(new ProductActor.InitializeProduct(productId, productName, productPrice, productQuantity));
            }
            // Create ProductActor entities with IDs 101 and 102
            // EntityRef<ProductActor.Command> product101 = sharding.entityRefFor(ProductActor.TypeKey, "101");
            // EntityRef<ProductActor.Command> product102 = sharding.entityRefFor(ProductActor.TypeKey, "102");

   
        
        this.sharding = ClusterSharding.get(context.getSystem());
    }
    
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder().onMessage(GetProductById.class, this::onGetProductById).build();
    }
    
    private Behavior<Command> onGetProductById(GetProductById req) {

        EntityRef<ProductActor.Command> productEntity =
            sharding.entityRefFor(ProductActor.TypeKey, req.productId);
        
        // productEntity.tell(new ProductActor.GetProduct(req.replyTo));
        return this;
      }

}
