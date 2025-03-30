package pods.akka;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Scheduler;
import akka.actor.typed.javadsl.AskPattern;
import akka.actor.typed.javadsl.Behaviors;

// Main class
public class Main {

	static ActorRef<Gateway.Command> gateway;
	static Duration askTimeout;
	static Scheduler scheduler;
	static class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange req) throws IOException {
            // only has GET request
            if("GET".equals(req.getRequestMethod())){
                	// parse the request path and body
                	// create a Product.Command object
                	// send it to the gateway actor
                	
                    // i think theis Gateway.Command is generic example will subs with an actual one here GetProductCommand
                    String[] pathParts = req.getRequestURI().getPath().split("/");
                    String productId;
                    if (pathParts.length > 2) {
                        // Extract the productId from the path
                        try {
                            // check if the id is indeed an integer
                            Integer.parseInt(pathParts[2]);
                            productId = pathParts[2];
                        } catch (NumberFormatException e) {
                            req.sendResponseHeaders(400, 0); // Bad Request
                            req.getResponseBody().close();
                            return;
                        }
                        // Use productId not there as needed
                    } else {
                        req.sendResponseHeaders(400, 0); // Bad Request
                        req.getResponseBody().close();
                        return;
                    }
                    CompletionStage<Gateway.Response> compl = 
                			AskPattern.ask(
                				  gateway,
                				  (ActorRef<Gateway.Response> ref) -> new Gateway.GetProductById(productId, ref), 
                				  askTimeout,
                				  scheduler);
                    compl.thenAccept((Gateway.Response r) -> {
                    String response = r.resp;
                    try {
                            req.sendResponseHeaders(200, response.length());
                            OutputStream os = req.getResponseBody();
                            os.write(response.getBytes());
                            os.close();
                        } catch (IOException e) {
                                e.printStackTrace();
                        }
                    }
                );
                	
            }
        }
    }
    // static class OrderHandler implements HttpHandler {
    //     @Override
    //     public void handle(HttpExchange req) throws IOException {
    //     	// map respective req by their path to the respective command class
    //         // fetch the req body here?
            



    //     	CompletionStage<Gateway.Response> compl = 
    //     			AskPattern.ask(
    //     				  gateway,
    //     				  (ActorRef<Gateway.Response> ref) -> new Gateway.Command(ref), 
    //     				  askTimeout,
    //     				  scheduler);
        	// The first param to ask() is the target actor to which we want to send the message
        	/* The second param to ask() is a function that takes a "reply to" ActorRef as parameter (the parameter is named "ref" in this case), and constructs and returns the message to be sent to the target actor. The Akka framework will first implicitly create a "reply to" actor  for use only in this call to ask(); the programmer need not even declare a class for this actor. The framework will then call the provided function (i.e., the second param), and give the replyt-to ActorRef as argument to this function. 
			
			Normally, the function should embed the reply-to ActorRef in the message, and should also populate the message's other fields with whatever they need to contain. 
			
			Once the function returns the constructed message, the framework  sends this message to the target actor. When the target actor eventually sends a response  to the ActorRef inside the message (i.e., the reply-to actor),  that response will be received by the reply-to actor.   
			  We should also explain what is meant by a CompletionStage<T> object. CompletionStage is a feature of plain Java, not akka. Please note that ask() is actually not a blocking call. It is non-blocking, like tell(). The difference is that tell() returns nothing, and does not expect that the target actor necessarily responds to the sending actor. Whereas, ask() expects that the target actor will respond to the reply-to actor embedded in the message sent to it. And the response message sent by the target actor (whenever it sends the message) will be received by the reply-to actor and  then placed into the CompletionStage returned by ask(). A CompletionStage in general is a placeholder for a value that is being computed by an asynchronous task, and that may not yet be ready to read. 
			  */
        	 // The third param to ask() is how long the CompletionStage should wait for its value to be obtained before declaring a timeout
        	
        	/* The following call to thenAccept() is blocking, and completes only when the CompletionStage receives its value (or times out). If it receives its value, the function provided below as argument to thenAccept gets executed, and the parameter r refers to the value of the CompletionStage (i.e., the message sent by the target actor to the reply-to actor in our setting). */
            // compl.thenAccept((Gateway.Response r) -> {
            // 							String response = r.resp;
            // 					        try {
			// 									req.sendResponseHeaders(200, response.length());
			// 									OutputStream os = req.getResponseBody();
			// 									os.write(response.getBytes());
			// 									os.close();
			// 								} catch (IOException e) {
			// 										e.printStackTrace();
			// 								}
            // 			 			   }
            // 				);
            
           /* Note, the entire code shown above in this method is a placeholder. Actually, Gateway.Command should be an interface, not a concrete class. This interface should have the necessary number of implementing classes, corresponding to the different types of requests that we can receive. The ask() should pass on the contents of the http request (including necessary fields in the request path and the request body) to the gateway actor, by constructing the message using the  implementing class of Gateway.Command that corresponds to the receipt request.  Also, the function passed to thenAccept must format the response received from the gateway actor into a valid http response as expected by the client. */
    //     }
    // }
    
    public static Behavior<Void> create() {
        return Behaviors.setup(context -> {
        	
        	 gateway = context.asJava().spawn(Gateway.create(), "gateway"); // spawn gateway actor
        	 
        	 askTimeout = Duration.ofSeconds(5);
        	 scheduler = context.getSystem().scheduler();
			// code which starts the http server inside the root actor?
        	 HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0); /* Creates a HTTP server that runs on localhost and listens to port 8080 */
             server.createContext("/", new Handler()); /* The "handle" method class OrderHandler will receive each http request and respond to it */
             server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool()); /* Create a thread pool and give it to the server. Server will submit each incoming request to the thread pool. Thread pool will pick a free thread (whenever it becomes available) and run the handle() method in this thread. The request is given as argument to the handle() method. */
             server.start(); /* Start the server */
             
            // so fr product initialization should we spawn the product actor here?
            // or should we spawn it in the gateway actor i think gateway?
            return Behaviors.empty(); // keep me (i.e., the root actor) alive, but  I don't want to receive messages
        });
    }

    public static void main(String[] args) {
        Config config = ConfigFactory.load();

        // Get the port from the config
        int port = config.getInt("akka.remote.artery.canonical.port");

        System.out.println("Loaded Configurations:");
        System.out.println("Port: " + port);

        // config = ConfigFactory.parseString(
        // "akka.remote.artery.canonical.port=" + port + "\n" +
        // "akka.cluster.seed-nodes=[\"akka://ClusterSystem@127.0.0.1:8080\"]"
        // ).withFallback(config);

        // System.out.println("Updated Configurations:");
        // System.out.println(config.root().render());

        // Create the ActorSystem
        
        //The actor system name should be the same for the Cluster
        final ActorSystem<Void> system = ActorSystem.create(Main.create(), "ClusterSystem", config);



        // Add a shutdown hook to stop the ActorSystem gracefully
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Stopping actor system");
            system.terminate();
        }));
    }
    
    // Normally nothing should be done in main() after creating the ActorSytem.
    // We are breaking this rule only for demo purposes.
        // A question to think about: Why can't we start the http server here? Why does it need to be started within the root actor?

		// actor system stops or crashes, the HTTP server would still be running, potentially leading to inconsistencies
    
}
