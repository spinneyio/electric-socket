(ns electric-socket.core
  (:require [electric-socket.spec :refer :all]
            [electric-socket.internals :as internals]
            [clojure.data.json :refer [read-str write-str]]
            [clojure.spec.alpha :as spec]
            [failjure.core :as f]
            [clojure.test]))

(defn add-jetty-integration
  "The function creates a map usable by luminus-jetty https://github.com/luminus-framework/luminus-jetty
   It includes the following functions:

   :on-connect
   :on-error
   :on-text
   :on-close
   :on-bytes

  The arguments are as follows:

  * Sends a message to a given channel. the :close? and should-close are optional.
  * If present and set to true the channel will be closed after the message is sent.

  (defn socket-send! [channel ^String message :close? ^Boolean should-close])  ==> electric-socket.spec/socket-send!

  * Immediately close the channel

  (defn socket-close! [channel]) ==> electric-socket.spec/socket-close!

  * Updates an entity in a given broker (redis?) using namespace and broker-key to locate it. 
  * The update function gets the old value (possibly nil) and boolean indicating if there was a retry on the way (conflicts?). 
  * Should return the new value as a map, or :swap/delete if the entry should be deleted or :swap/abort if no changes are needed.

  (defn broker-update-entity [^String namespace ^String broker-key (fn [old-value retried?] -> either a map or one in #{:swap/abort, :swap/delete})]) ==>  electric-socket.spec/broker-update-entity

  * Subscribes to all the messages arriving from namespace / broker key combination. The handler receives just the message.

  (defn broker-subscribe [^String namespace ^String broker-key (fn [^String message])]) ==> electric-socket.spec/broker-subscribe

  * Publishes a message to namespace and broker-key, which can be subscribed to by other instances of the backend.

  (defn broker-publish-message [^String namespace ^String broker-key ^String message])  ==> electric-socket.spec/broker-publish-message

  * Processes side effects of certain actions as defined in the integration part of the documentation. 
  * Often used for DB transactions that should be performed in certain scenarios.

  (defn process-side-effects [side-effects]) ==> electric-socket.spec/process-side-effects

  * Logs some diagnostic messages

  (defn logger [& strings]) ==> electric-socket.spec/logger

  * Allows for exception collection and possibly upload to some exception analysing service (Sentry?)

  (defn error-handler [^Throwable error ^String description]) ==> electric-socket.spec/error-handler

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
                          analogically to functions above. It is being called only when connection is being closed."
  [pure-integration  
   socket-send! 
   socket-close! 
   broker-update-entity 
   broker-subscribe 
   broker-publish-message 
   process-side-effects 
   logger 
   error-handler] 
  (internals/add-integration 
   pure-integration  
   socket-send! 
   socket-close! 
   broker-update-entity
   broker-subscribe 
   broker-publish-message 
   process-side-effects 
   logger 
   error-handler))

(spec/fdef add-integration
  :args (spec/cat :pure-integration :electric-socket.spec/integration
                  :socket-send! :electric-socket.spec/socket-send!
                  :socket-close! :electric-socket.spec/socket-close!
                  :broker-update-entity :electric-socket.spec/broker-update-entity
                  :broker-subscribe :electric-socket.spec/broker-subscribe
                  :broker-publish-message :electric-socket.spec/broker-publish-message
                  :process-side-effects :electric-socket.spec/process-side-effects
                  :logger :electric-socket.spec/logger
                  :error-handler :electric-socket.spec/error-handler))
