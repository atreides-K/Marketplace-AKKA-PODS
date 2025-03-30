package pods.akka.actors;

import akka.actor.typed.ActorRef;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class ProductRegistry {

    private static final Logger logger = Logger.getLogger(ProductRegistry.class.getName());

    // Map productId -> ProductActor reference
    private static final Map<Integer, ActorRef<ProductActor.Command>> registry = new ConcurrentHashMap<>();

    // Registers a ProductActor in the registry.
    public static void registerProduct(int productId, ActorRef<ProductActor.Command> productActor) {
        logger.info("Registering ProductActor with productId: " + productId);
        registry.put(productId, productActor);
        logger.info("ProductActor registered successfully for productId: " + productId);
    }

    // Returns the ProductActor for the given productId.
    public static ActorRef<ProductActor.Command> getProductActor(int productId) {
        logger.info("Fetching ProductActor for productId: " + productId);
        ActorRef<ProductActor.Command> productActor = registry.get(productId);
        if (productActor != null) {
            logger.info("ProductActor found for productId: " + productId);
        } else {
            logger.warning("No ProductActor found for productId: " + productId);
        }
        return productActor;
    }
}
