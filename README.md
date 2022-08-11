# electric-socket

A Clojure library designed to bring order to chaotic world of socket communication.

## Usage

The library does not communicate with sockets by itself. Instead it relies on the user to bring in fuunctions responsible for sending sockets.
The initial version is designed for Luminus / Jetty but can be easily modified to suit other web servers.

The library uses an abstracted broker (which can be anything that allows for message and data echange between instances). 
We used Redis with Carmine, but other brokers can be easily integrated.

The library provides one function only: add-jetty-integration

The function creates a map usable by luminus-jetty: https://github.com/luminus-framework/luminus-jetty
It includes the following functions:

:on-connect

:on-error

:on-text

:on-close

:on-bytes

The arguments to add-jetty-integration are as follows:

Sends a message to a given channel. the :close? and should-close are optional.
If present and set to true the channel will be closed after the message is sent:
```
(defn socket-send! [channel ^String message :close? ^Boolean should-close])  ==> electric-socket.spec/socket-send!
```
Immediately close the channel:
```
(defn socket-close! [channel]) ==> electric-socket.spec/socket-close!
```
Updates an entity in a given broker (redis?) using namespace and broker-key to locate it. 
The update function gets the old value (possibly nil) and boolean indicating if there was a retry on the way (conflicts?). 
Should return the new value as a map, or :swap/delete if the entry should be deleted or :swap/abort if no changes are needed:
```
(defn broker-update-entity [^String namespace ^String broker-key (fn [old-value retried?] -> either a map or one in #{:swap/abort, :swap/delete})]) ==>  electric-socket.spec/broker-update-entity
```
Subscribes to all the messages arriving from namespace / broker key combination. The handler receives just the message:
```
(defn broker-subscribe [^String namespace ^String broker-key (fn [^String message])]) ==> electric-socket.spec/broker-subscribe
```
Publishes a message to namespace and broker-key, which can be subscribed to by other instances of the backend:
```
(defn broker-publish-message [^String namespace ^String broker-key ^String message])  ==> electric-socket.spec/broker-publish-message
```
Processes side effects of certain actions as defined in the integration part of the documentation. 
Often used for DB transactions that should be performed in certain scenarios:
```
(defn process-side-effects [side-effects]) ==> electric-socket.spec/process-side-effects
```
Logs some diagnostic messages:
```
(defn logger [& strings]) ==> electric-socket.spec/logger
```
Allows for exception collection and possibly upload to some exception analysing service (Sentry?):
```
(defn error-handler [^Throwable error ^String description]) ==> electric-socket.spec/error-handler
```
*********************************************************************************************************************************************

The pure-integration argument is a map with the following keys (all function arguments must be pure!):

:name - The namespace used by our integration. Useful when communicating with the broker.
:path - The path associated with websocket service

:initial-authorisation - A function that receives channel and a map with initial message coming from websocket. 
                        Returns either a map to be associated with the connection - channel data (and later passed to all the other functions) or 
                        failjure/failed? value representing error to be sent to the user.

:broker-key-fn - A function that receives a channel and the channel data. Returns a key to be used on all the broker connections for this connection.

:broker-authorisation - A function that takes channel, channel data and broker data associated with this connection (through broker-key-fn) 
                       and returns a map with the required actions to be taken by electric socket after its execution is complete. 

                       :channel-data - a map containing updated channel data. ES is expected to replace it.
                       :broker-data - a map containing updated broker data. ES is expected to replace it. It can be also :swap/abort or :swap/delete
                                      to either not replace the broker data or completely purge it. 
                       :websocket-response - a map to be sent back over websocket or failjure/failed? indicating an error during authorisation.
                       :broker-messages - a list of maps containing at least two fields: target (String) and message (map). 
                                          Target is a unique identifier of the recipient, message is a mesage to be delivered.
                       :should-close? - If set to yes the connection will be terminated (useful for failed authorisation)
                       :side-effects - Any side effects to be processed by side effects processor (see above)
                       :error-namespace - (optional) Used for internationalisation, can be the language error should be presented in.

:process-websocket-message - A function that takes a channel, map with a message, channel data and broker data.
                            Assumes the connection is active and successfully authenticated.
                            The return value comprises of:

                       :channel-data - a map containing updated channel data. ES is expected to replace it.
                       :broker-data - a map containing updated broker data. ES is expected to replace it. It can be also :swap/abort or :swap/delete
                                      to either not replace the broker data or completely purge it. 
                       :websocket-response - a map to be sent back over websocket or failjure/failed? indicating an error. 
                                             We can still keep the connection going after error if needed.
                       :broker-messages - a list of maps containing at least two fields: target (String) and message (map). 
                                          Target is a unique identifier of the recipient, message is a mesage to be delivered.
                       :should-close? - If set to yes the connection will be terminated (useful for ending connection)
                       :side-effects - Any side effects to be processed by side effects processor (see above)
                       :error-namespace - (optional) Used for internationalisation, can be the language error should be presented in.

:process-broker-message - A function that takes a channel, map with a broker message, channel data and broker data.
                            Assumes the connection is active and successfully authenticated.
                            The return value comprises of:

                       :channel-data - a map containing updated channel data. ES is expected to replace it.
                       :broker-data - a map containing updated broker data. ES is expected to replace it. It can be also :swap/abort or :swap/delete
                                      to either not replace the broker data or completely purge it. 
                       :websocket-response - a map to be sent back over websocket or failjure/failed? indicating an error.
                                             We can still keep the connection going after error if needed.
                       :broker-messages - a list of maps containing at least two fields: target (String) and message (map). 
                                          Target is a unique identifier of the recipient, message is a mesage to be delivered.
                       :should-close? - If set to yes the connection will be terminated (useful for ending connection)
                       :side-effects - Any side effects to be processed by side effects processor (see above)
                       :error-namespace - (optional) Used for internationalisation, can be the language error should be presented in.

:on-close            - A function that takes channel, channel-data and broker-data and produces new broker data and broker messages 
                       analogically to functions above. It is being called only when connection is being closed.


## License

Copyright © 2022 Spinney

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
