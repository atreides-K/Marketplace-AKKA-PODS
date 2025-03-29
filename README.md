# Marketplace-AKKA-PODS
## Resources
### TO RUN
mvn clean install
mvn exec:java
### HTTP Server
[Simple HTTP Server in Java](https://dzone.com/articles/simple-http-server-in-java)

### HttpExchange 
The typical life-cycle of a HttpExchange is shown in the sequence below:

getRequestMethod() to determine the command.
getRequestHeaders() to examine the request headers (if needed).
getRequestBody() returns an InputStream for reading the request body. After reading the request body, the stream should be closed.
getResponseHeaders() to set any response headers, except content-length.
sendResponseHeaders(int, long) to send the response headers. Must be called before next step.
getResponseBody() to get a OutputStream to send the response body. When the response body has been written, the stream must be closed to terminate the exchange.