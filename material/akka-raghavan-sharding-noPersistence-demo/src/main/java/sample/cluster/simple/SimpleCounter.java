package sample.cluster.simple;

import sample.cluster.CborSerializable;
import akka.actor.typed.Behavior;
import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.Receive;

import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.ActorContext;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;

import com.fasterxml.jackson.annotation.JsonCreator;


public class SimpleCounter  extends AbstractBehavior<SimpleCounter.Command> {
	//private final String id; // the unique ID of this counter
	private int count = 0; // current count value
	String entityId;
	
	 public static final EntityTypeKey<Command> TypeKey =
			    EntityTypeKey.create(SimpleCounter.Command.class, "SimpleCounterEntity");
	
	// actor commands and responses
	 // extending CborSerializable is necessary for persistence
	interface Command extends CborSerializable {}
	  
	public static final class Increment implements Command {
		public int dummy=0; // this line is required for Jackson serialization to not 
							// throw an exception at run time! Probably this won't be required
							// if there are genuine public fields available here. There are other
							// to avoid this exception, but this is a dirty hack that works.
		public Increment() {}
	}
	
	public static final class GetValue implements Command {
		ActorRef<CounterValue> replyTo;
		public GetValue(ActorRef<CounterValue> replyTo) {
			this.replyTo = replyTo;
		}
	}
	
	public static final class CounterValue implements CborSerializable {
		final int value;
		public CounterValue(int value) {
			this.value = value;
		}
	}

	public SimpleCounter(ActorContext<Command> context, String id) {
		   super(context);
		   this.entityId = id;
	 }
	 
	 public static Behavior<Command> create(String entityId) {
		    return Behaviors.setup(context ->
		        new SimpleCounter(context, entityId)
		        // Note, we need not necessarily pass the entityId to the
                // constructor and save it in a field of the entity. The sharding proxy
		        // separately maintains the entityId -> entity mapping. 
		    );
		  }
	 
	 @Override
	  public Receive<Command> createReceive() {
	    return newReceiveBuilder()
	        .onMessage(Increment.class, this::onIncrement)
	        .onMessage(GetValue.class, this::onGetValue)
	        .build();
	  }

	private Behavior<Command> onIncrement(Increment mesg) {
		this.count++;
		getContext().getLog().info("Incremented the counter {}. Current value is {}.", entityId, count);
		return this;
	}
	
	private Behavior<Command> onGetValue(GetValue mesg) {
		mesg.replyTo.tell(new CounterValue(count));
		return this;
	}
}
