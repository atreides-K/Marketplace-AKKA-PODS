package pods.example;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

public class Gateway extends AbstractBehavior<Gateway.Command> {
	/* This class is also a skeleton class. 
	 * Actually, Command below should be an interface. It should have the necessary number of implementing classes and corresponding event handlers. The implementing classes should have fields to hold the required elements of the http request (request path and request body), in addition to the reply-to ActorRef. The event handlers could use some suitable json parser to parse any json in the original http request body. Similarly, the Response class should be populated with the actual response.
	 */
	int responseNum = 0;
	
	static class Command {
		ActorRef<Response> replyTo;
		public Command(ActorRef<Response> r) {replyTo = r;}
	}
	
	class Response {
		String resp;
		public Response(String s) {resp = s;}
	}
	
	public static Behavior<Command> create() {
        return Behaviors.setup(context -> new Gateway(context));
    }

    private Gateway(ActorContext<Command> context) {
        super(context);
    }
    
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder().onMessage(Command.class, this::onCommand).build();
    }
    
    private Behavior<Command> onCommand(Command command) {
        command.replyTo.tell(new Response("Response " + responseNum++ + " from Gateway actor. "));
        // Stub functionality. This is not the actual desired functionality.
        return this;
      }

}
