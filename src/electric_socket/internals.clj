(ns electric-socket.internals
  (:require [electric-socket.spec :refer :all]
            [clojure.data.json :refer [read-str write-str]]
            [clojure.spec.alpha :as spec]
            [failjure.core :as f]
            [clojure.test]))

(defn- stacktrace [e]
  (let [sw (java.io.StringWriter.)
        pw (java.io.PrintWriter. sw)]
    (.printStackTrace e pw)
    (.toString sw)))

(defn- exception-description [e]
  (str (class e) " received in: " (stacktrace e) ": " (.getMessage e)))

(defn- process-websocket-response
  "Returns a tuple [response should-close?]"
  ([response] (process-websocket-response response "auth"))
  ([response id]
   (if (f/failed? response)
     [(-> response ; we convert api error into websocket error format (by taking body only and adding :id)
          :message
          read-string
          :body
          (assoc :id (or id "comm"))
          write-str) true]
     [(when response (write-str response)) false])))

(defn- spec-assert [spec var]
  (assert (spec/valid? spec var) (spec/explain spec var)))

(defn add-integration
  "Docstring"
  [integration  ; configuration
   socket-send!  ; implementation of socket send
   socket-close! ; implementation of socket close
   broker-update-entity ; implementation of broker-update
   broker-subscribe ; implementation of broker-subscribe
   broker-publish-message ; implementation of broker-publish
   process-side-effects ; implementation of side effects function
   logger ; implementation of logging function
   error-handler] ; error handler
  (do
    (spec-assert :electric-socket.spec/integration integration)
    
    (let [registered-subscriptions (ref #{})
          data-by-channel (ref {})
          channels-for-identifier (ref {})

          subscribe-internal (fn [name key handler]
                               (let [k (str name "." key)
                                     should-register (dosync
                                                      (let [subs @registered-subscriptions
                                                            ret-val (not (contains? subs k))]
                                                        (when ret-val
                                                          (alter registered-subscriptions conj k))
                                                        ret-val))]
                                 (when should-register (broker-subscribe name key handler))))

          send-broker-messages (fn [src-channel broker-key broker-messages]
                                 (doseq [m broker-messages]
                                   (let [message (merge m {:src-channel-hash (hash src-channel)
                                                           :broker-key broker-key})
                                         encoded-message (write-str message)]
                                     (logger "SENDING MESSAGE: " m src-channel broker-key)
                                     (broker-publish-message (:name integration) broker-key encoded-message))))
          process-broker-message (fn [content]
                                   (logger "Received broker message: " content)
                                        ; {:target "user-id or sth"
                                        ;  :message { ... } ; message
                                        ;  :src-channel-hash 
                                        ;  :broker-key}
                                   (let [processed-content (read-str content :key-fn keyword)
                                         {:keys [src-channel-hash broker-key target]} processed-content
                                         channel-datas (dosync
                                                        (let [channel-hashes (filter #(not= src-channel-hash %)
                                                                                     (get @channels-for-identifier target))
                                                              ret-vals (filter #(let [ch (:channel %)
                                                                                      dt (:data %)
                                                                                      ch-id ((:channel-identifier integration) ch dt)
                                                                                      rd-key ((:broker-key-fn integration) ch dt)]
                                                                                  (spec-assert :electric-socket.spec/channel-identifier-retval ch-id)
                                                                                  (spec-assert :electric-socket.spec/broker-key-retval rd-key)
                                                                                  (and (= ch-id target)
                                                                                       (= rd-key broker-key)))
                                                                               (map #(get @data-by-channel %) channel-hashes))]
                                                          ret-vals))]                                  
                                     (doseq [{:keys [channel data]} channel-datas]
                                       (let [broker-response (atom nil)]
                                         (if broker-key
                                           (broker-update-entity
                                            (:name integration)
                                            broker-key
                                            (fn [old-value _retried?]
                                              (let [{broker-data :broker-data :as response} ((:process-broker-message integration) channel processed-content  data (or old-value {}))]
                                                (reset! broker-response response)
                                                (or broker-data :swap/abort))))
                                           (reset! broker-response ((:process-broker-message integration) channel processed-content data {})))
                                         (spec-assert :electric-socket.spec/process-broker-message-retval @broker-response)
                                         (let [{:keys [channel-data websocket-response broker-messages should-close? error-namespace]} @broker-response
                                               [websocket-final-response _websocket-failed] (process-websocket-response websocket-response error-namespace)]
                                           (dosync
                                            (alter data-by-channel assoc (hash channel) {:data channel-data
                                                                                         :channel channel}))
                                           (send-broker-messages channel broker-key broker-messages)
                                           (when websocket-response (socket-send! channel websocket-final-response :close? should-close?)))))))]
      {:on-connect     (fn [channel]
                         (assert (= nil (get @data-by-channel (hash channel))) "The channel already exists")
                         (dosync
                          (alter data-by-channel assoc (hash channel) {:channel channel
                                                                       :data nil})))

       :on-close   (fn [channel _code _reason]
                     (let [[_identifier channel-data]
                           (dosync ; remove from our local records
                            (let [channel-info (get @data-by-channel (hash channel))
                                  identifier ((:channel-identifier integration) channel (:data channel-info))
                                  channel-data (:data channel-info)]
                              (spec-assert :electric-socket.spec/channel-identifier-retval identifier)
                              (if (not identifier) ; not yet authenticated    
                                (alter data-by-channel dissoc (hash channel))
                                (let [ids (get @channels-for-identifier identifier)]
                                  (assert ids "No id set for channel")
                                  (alter data-by-channel dissoc (hash channel))
                                  (alter channels-for-identifier update identifier #(disj % (hash channel)))))
                              [identifier channel-data]))

                           broker-response (atom nil)
                           broker-key ((:broker-key-fn integration) channel channel-data)]
                       (spec-assert :electric-socket.spec/broker-key-retval broker-key)
                       (if broker-key
                         (broker-update-entity (:name integration)
                                               broker-key
                                               (fn [old-value _retried?]
                                                 (let [{broker-data :broker-data :as response} ((:on-close integration) channel channel-data old-value)]
                                                   (reset! broker-response response)
                                                   (or broker-data :swap/abort))))
                         (reset! broker-response ((:on-close integration) channel channel-data {})))
                       (spec-assert :electric-socket.spec/on-close-retval @broker-response)
                       (send-broker-messages channel broker-key (:broker-messages @broker-response))))

       :on-text (fn [channel message]
                  (let [m-json (try
                                 (read-str message :key-fn keyword)
                                 (catch Throwable e
                                   (logger (str "Socket parsing exception: " (exception-description e)))
                                   {:bad-json-xx true
                                    :ex e}))]
                    (if (:bad-json-xx m-json)
                      (socket-send! channel (str "ERROR: Message has to be in JSON format: " (exception-description (:ex m-json)))  
                                    :close? true)
                      (let [channel-data (:data (get @data-by-channel (hash channel)))]
                        (if channel-data
                          (let ; subsequent message
                              [identifier ((:channel-identifier integration) channel channel-data)
                               broker-key ((:broker-key-fn integration) channel channel-data)
                               broker-response (atom nil)]
                            (spec-assert :electric-socket.spec/channel-identifier-retval identifier)
                            (spec-assert :electric-socket.spec/broker-key-retval broker-key)
                            (if broker-key
                              (broker-update-entity
                               (:name integration)
                               broker-key
                               (fn [old-value _retried?]
                                 (let [{broker-data :broker-data :as ret-val} ((:process-websocket-message integration) channel m-json channel-data (or old-value {}))]
                                   (reset! broker-response ret-val)
                                   (or broker-data :swap/abort))))
                              (reset! broker-response ((:process-websocket-message integration) channel m-json channel-data {})))
                            (spec-assert :electric-socket.spec/process-websocket-message-retval @broker-response)
                            (let [{:keys [channel-data websocket-response broker-messages should-close? error-namespace]} @broker-response
                                  [final-websocket-response _has-failed?] (process-websocket-response websocket-response error-namespace)]
                              (dosync
                               (alter data-by-channel assoc (hash channel) {:channel channel
                                                                            :data channel-data}))
                              (when final-websocket-response (socket-send! channel final-websocket-response :close? should-close?))
                              (send-broker-messages channel broker-key broker-messages)))
                          (let ; initial message
                              [initial-data ((:initial-authorisation integration) channel m-json)
                               _ (spec-assert :electric-socket.spec/initial-authorisation-retval initial-data)
                               [final-websocket-response is-failure] (process-websocket-response initial-data "auth")]
                            (if is-failure
                              (socket-send! channel
                                            final-websocket-response
                                            :close? true)
                              (let [identifier ((:channel-identifier integration) channel initial-data)]
                                (spec-assert :electric-socket.spec/channel-identifier-retval identifier)
                                (let [broker-auth-response (atom {})
                                      broker-key ((:broker-key-fn integration) channel initial-data)]
                                  (spec-assert :electric-socket.spec/broker-key-retval broker-key)
                                  (if broker-key
                                    (broker-update-entity
                                     (:name integration)
                                     broker-key
                                     (fn [old-value _retried?]
                                       (let [{broker-data :broker-data :as ret-val} ((:broker-authorisation integration) channel initial-data (or old-value {}))]
                                         (reset! broker-auth-response ret-val)
                                         (or broker-data :swap/abort))))
                                    (reset! broker-auth-response ((:broker-authorisation integration) channel initial-data {})))
                                  (spec-assert :electric-socket.spec/broker-authorisation-retval @broker-auth-response)
                                  (let [{:keys [websocket-response channel-data broker-messages broker-data side-effects error-namespace]} @broker-auth-response
                                        [websocket-final-response has-failed] (process-websocket-response websocket-response (or error-namespace "auth"))]
                                    (when (not has-failed)
                                      (dosync
                                       (alter data-by-channel assoc (hash channel) {:channel channel
                                                                                    :data channel-data})
                                       (alter channels-for-identifier update identifier (fn [id-set] (if id-set (conj id-set (hash channel)) #{(hash channel)}))))
                                      (process-side-effects (side-effects channel channel-data broker-data)))
                                    (when broker-key
                                      (subscribe-internal (:name integration) broker-key process-broker-message)
                                      (send-broker-messages channel broker-key broker-messages))
                                    (when websocket-response (socket-send! channel websocket-final-response :close? has-failed))))))))))))

       :on-bytes (fn [channel bytes _offset _len]
                   (logger (str "Channel error:  bytes sent: " bytes))
                   (socket-close! channel))

       :on-error (fn [channel error]
                   (error-handler error (exception-description error))
                   (logger (str "Channel error: " (exception-description error) " closing..."))
                   (socket-close! channel))

       :context-path (:path integration)
       :allow-null-path-info? true})))
