package sample.cluster.simple;


import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.javadsl.EntityContext;
import akka.cluster.sharding.typed.javadsl.EntityRef;


public class App {

  public static void main(String[] args) {
    if (args.length == 0) {
     /* startup(25251);
      startup(25252);
      startup(0);*/
    } else
      Arrays.stream(args).map(Integer::parseInt).forEach(App::startup);
  }

  private static Behavior<Void> rootBehavior(int port) {
    return Behaviors.setup(context -> {

      ActorRef<SimpleCounter.Command> ref0 =
      context.spawn(SimpleCounter.create("Counter4"), "Counter4");
      // this is not an entity, as we directly spawned it without using sharding.entityRefFor
            
      final ClusterSharding sharding = ClusterSharding.get(context.getSystem());
      sharding.init(
      	Entity.of(SimpleCounter.TypeKey,
  		            (EntityContext<SimpleCounter.Command> entityContext) ->
                    {return SimpleCounter.create(entityContext.getEntityId());}
  		  // we should pass any other parameters needed by the actor's constructor to function .create above
    	));

      /* sharding.init creates a sharding proxy in the
         local node for the given typekey. A typekey represents an entity type. */
    
      if (port == 25251 || port == 25252 || port == 25253) {
          System.out.println("Inside node1 case");
    	  EntityRef<SimpleCounter.Command> ref1 = sharding.entityRefFor(SimpleCounter.TypeKey, "Counter1");
          ref1.tell(new SimpleCounter.Increment());
      }
      if (port == 25252 || port == 25253) {
    	  EntityRef<SimpleCounter.Command> ref2 = sharding.entityRefFor(SimpleCounter.TypeKey, "Counter2");
    	  ref2.tell(new SimpleCounter.Increment());
      }
      if (port == 25253) {
    	  EntityRef<SimpleCounter.Command> ref3 = sharding.entityRefFor(SimpleCounter.TypeKey, "Counter3");
    	 ref3.tell(new SimpleCounter.Increment());
      }

      /* The method entityRefFor receives a typekey and an entity ID.
       Distinct entities with the same typekey should have distinct
       IDs.

       entityRefFor will first check with the sharding proxy in the
       current node and with the sharding proxies of the other nodes
       whether an entity with the given entity ID has already been
       spawned. If yes, it returns a ref to that entity.

       Otherwise, entityRefFor will package its two arguments into an
       entityContext object, and pass this object as argument to the
       user-defined function given as argument to sharding.init. This
       user-defined function should return the spawned entity. The
       entity can be spawned the same way as any actor (i.e., any
       actor can be an entity).  It is optional to pass the
       entityContext or entityId as arguments into the static .create method. The
       entity will be spawned in the current node, and the sharding
       proxy of the current node will now remember in a table that the
       actor just spawned is the entity for the given entity ID. Note, the 
       entityContext has methods such as .getEntityID (returns a String) 
       and .getEntityTypeKey (returns an EntityTypeKey object). 

       */
    

      return Behaviors.empty();
    });
  }

  private static void startup(int port) {
    // Override the configuration of the port
    // Override the configuration of the port
    Map<String, Object> overrides = new HashMap<>();
    overrides.put("akka.remote.artery.canonical.port", port);

    // See the cluster and cluster-sharding slides to understand
    // what all is required in application.conf.
    
    Config config = ConfigFactory.parseMap(overrides)
        .withFallback(ConfigFactory.load());

    // Create an Akka system
    ActorSystem<Void> system = ActorSystem.create(rootBehavior(port), "ClusterSystem", config);
    
    // Normally nothing should be done in main() after creating the ActorSytem.
    // We are breaking this rule only for demo purposes.
    
  }
}
