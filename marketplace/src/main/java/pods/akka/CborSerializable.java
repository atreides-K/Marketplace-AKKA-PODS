/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */
package pods.akka;

/**
 * Marker trait to tell Akka to serialize messages into CBOR using Jackson for sending over the network
 * See application.conf where it is bound to a serializer.
 * For more details see the docs https://doc.akka.io/docs/akka/2.6/serialization-jackson.html
 */
public interface CborSerializable {  }
