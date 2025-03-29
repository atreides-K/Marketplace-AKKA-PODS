package pods.akka;



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

public class Gateway extends AbstractBehavior<Gateway.Command> {
	/* This class is also a skeleton class. 
	 * Actually, Command below should be an interface. It should have the necessary number of implementing classes and corresponding event handlers. The implementing classes should have fields to hold the required elements of the http request (request path and request body), in addition to the reply-to ActorRef. The event handlers could use some suitable json parser to parse any json in the original http request body. Similarly, the Response class should be populated with the actual response.
	 */
	int responseNum = 0;
	
    private final ClusterSharding sharding;
    
    // Create a few Product entities for demonstration purposes



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
        
            // Assuming Product.Command and Product.TypeKey are defined elsewhere
            ClusterSharding sharding = ClusterSharding.get(context.getSystem());
            sharding.init(
                Entity.of(Product.TypeKey,
                (EntityContext<Product.Command> entityContext) ->
                        Product.create(entityContext.getEntityId())
            ));

            // Create Product entities with IDs 101 and 102
            EntityRef<Product.Command> product101 = sharding.entityRefFor(Product.TypeKey, "101");
            EntityRef<Product.Command> product102 = sharding.entityRefFor(Product.TypeKey, "102");

   
        
        this.sharding = ClusterSharding.get(context.getSystem());
    }
    
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder().onMessage(GetProductById.class, this::onGetProductById).build();
    }
    
    private Behavior<Command> onGetProductById(GetProductById req) {

        EntityRef<Product.Command> productEntity =
            sharding.entityRefFor(Product.TypeKey, req.productId);
        
        productEntity.tell(new Product.GetProduct(req.productId, req.replyTo));
        return this;
      }

}
