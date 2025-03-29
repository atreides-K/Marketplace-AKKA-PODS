This is a demo that demonstrates cluster sharding.

First issue "mvn compile".

In the first terminal window, start the first seed node with the following command:

    mvn exec:java -Dexec.mainClass="sample.cluster.simple.App" -Dexec.args=25251

The entity "Counter1" gets created in the first seed node, and gets
incremented once (note the message).

In the second terminal window, start the second seed node with the following command:

    mvn exec:java -Dexec.mainClass="sample.cluster.simple.App" -Dexec.args=25252

In the current window, Counter2 should show that it has value 1.
Switch over to the first window, and you will see that the first node has
again incremented the value of the counter Counter1 (which resides in the
first node). Its current value is hence 2.

In the third terminal window, start the third node with the following command:

    mvn exec:java -Dexec.mainClass="sample.cluster.simple.App" -Dexec.args=25253

In the current window, Counter3 should show that it has value 1.
Counter1 in window 1 should display value 3 and Counter 2 in window 2 
should display value 2.

