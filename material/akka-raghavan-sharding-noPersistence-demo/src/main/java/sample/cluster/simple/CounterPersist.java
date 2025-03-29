package sample.cluster.simple;

import sample.cluster.CborSerializable;
import sample.cluster.simple.SimpleCounter.CounterValue;
import akka.actor.typed.Behavior;
import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.ActorContext;

import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.EventSourcedBehavior;
import akka.persistence.typed.javadsl.Effect;
import akka.persistence.typed.javadsl.EventHandler;

import com.fasterxml.jackson.annotation.JsonCreator;


public class CounterPersist  extends 
  EventSourcedBehavior<CounterPersist.Command, CounterPersist.CounterEvent, CounterPersist.Count>{
	
	
	 public static final EntityTypeKey<Command> TypeKey =
			    EntityTypeKey.create(CounterPersist.Command.class, "CounterPersistEntity");
	
	// actor commands and responses
	 // extending CborSerializable is necessary for persistence
	interface Command extends CborSerializable {}
	  
	public static final class Increment implements Command {
		public int dummy=0; // this line is required for Jackson serialization to not 
							// throw an exception at run time! Probably this won't be required
							// if there are genuine public fields available here. There are other
							// ways to avoid this exception, but this is a dirty hack that works.
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
	
	// Event. 
	interface CounterEvent extends CborSerializable {}
	
	public static final class IncrEvent implements CounterEvent {
		int dummy=0;
	}
	
	// State
	static final class Count implements CborSerializable {
		public int count = 0;
	}
	
	@Override
	public Count emptyState() {
		return new Count();
	}

	public CounterPersist(ActorContext<Command> context,  PersistenceId persistenceId) {
		   super(persistenceId); // Note, if the persistent actor needs to spawn
		   						 // child actors, it should save the context in a field of "this",
	 }
	 
	 public static Behavior<Command> create(String entityId, PersistenceId persistenceId) {
		    return Behaviors.setup(context ->
		        new CounterPersist(context, persistenceId)
		    );
		  }

	@Override
	public CommandHandler<Command, CounterEvent, Count> commandHandler() {
	  return newCommandHandlerBuilder().forAnyState()
			  .onCommand(Increment.class, this::onIncrement)
			  .onCommand(GetValue.class, this::onGetValue)
			  .build();
	}
	 
	private Effect<CounterEvent,Count> onIncrement(Increment mesg) {
		return Effect().persist(new IncrEvent())
				.thenRun(newState -> 
						System.out.println("Incremented the counter " + this.persistenceId() +
								". Current value is " + newState.count + "."));
	}
	
	private Effect<CounterEvent,Count> onGetValue(GetValue mesg) {
		return Effect().none()
				.thenRun(newState -> 
							mesg.replyTo.tell(new CounterValue(newState.count)));
	}

	@Override
	public EventHandler<Count, CounterEvent> eventHandler() {
		return newEventHandlerBuilder()
				.forAnyState()
				.onEvent(IncrEvent.class, (state, evt) -> {state.count++; return state;})
				.build();
	}
}
