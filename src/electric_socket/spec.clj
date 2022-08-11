(ns electric-socket.spec
  (:require [clojure.spec.alpha :as spec]
            [failjure.core :as f]))

(spec/def ::channel-data (spec/nilable map?))
(spec/def ::websocket-response (spec/nilable (spec/or :success map? :failure f/failed?)))
(spec/def ::broker-data (spec/or :map map? :cas-result #{:swap/abort :swap/delete}))

(spec/def ::target string?)
(spec/def ::error-namespace string?)
(spec/def ::message map?)

(spec/def ::broker-message (spec/keys :req-un [::target ::message]))
(spec/def ::broker-messages (spec/coll-of ::broker-message))
(spec/def ::should-close boolean?)

(spec/def ::initial-authorisation-retval (spec/or :success map? :failure f/failed?))
(spec/def ::channel-identifier-retval (spec/nilable string?))
(spec/def ::broker-key string?)
(spec/def ::broker-key-retval (spec/nilable ::broker-key))


(spec/def ::side-effects-message (spec/coll-of (spec/or :vec vector? :map map?)))

(spec/def ::side-effects (spec/fspec :args (spec/cat :channel some? :channel-data map? :broker-data map?)
                                     :ret ::side-effects-message))

; We signal that the auth was unsuccessful by returning f/failed? in websocket-response. If that happens, all the other fields are used anyway
(spec/def ::broker-authorisation-retval (spec/keys :req-un [::channel-data
                                                            ::websocket-response
                                                            ::broker-messages
                                                            ::broker-data
                                                            ::should-close?
                                                            ::side-effects]
                                                  :opt-un [::error-namespace]))

; In processing websocket messages (after auth) we allow for a situation that we emit an error from websocket-response but still keep the connection running
(spec/def ::process-websocket-message-retval (spec/keys :req-un [::channel-data
                                                                 ::websocket-response
                                                                 ::broker-messages
                                                                 ::broker-data
                                                                 ::should-close?
                                                                 ::side-effects]
                                                        :opt-un [::error-namespace]))

(spec/def ::process-broker-message-retval (spec/keys :req-un [::channel-data
                                                              ::websocket-response
                                                              ::broker-messages
                                                              ::broker-data
                                                              ::should-close?
                                                              ::side-effects]
                                                    :opt-un [::error-namespace]))

(spec/def ::on-close-retval (spec/keys :req-un [::broker-messages ::broker-data]))
(spec/def ::initial-authorisation (spec/fspec :args (spec/cat :channel some? :initial-message map?)
                                              :ret ::initial-authorisation-retval))
(spec/def ::broker-key-fn (spec/fspec :args (spec/cat :channel some? :channel-data map?)
                                     :ret ::broker-key-retval))
(spec/def ::broker-authorisation (spec/fspec :args (spec/cat :channel some? :channel-data map? :broker-data map?)
                                            :ret ::broker-authorisation-retval))
(spec/def ::process-websocket-message (spec/fspec :args (spec/cat :channel some? :message map? :channel-data map? :broker-data map?)
                                           :ret ::process-websocket-message-retval))
(spec/def ::process-broker-message (spec/fspec :args (spec/cat :channel some? :message map? :channel-data map? :broker-data map?)
                                              :ret ::process-broker-message-retval))
(spec/def ::on-close (spec/fspec :args (spec/cat :channel some? :channel-data map? :broker-data map?)
                                 :ret ::on-close-retval))
(spec/def ::name string?)
(spec/def ::error-code int?)
(spec/def ::path string?)

(spec/def ::close-label (spec/with-gen (spec/and keyword? #(= % :close?))
                          #(spec/gen #{:close? :i-am-a-fool})))

(spec/def ::socket-send! (spec/fspec :args (spec/or :simple (spec/cat :channel some? :message string?)
                                                    :closing (spec/cat :channel some? :message string? :close-label? ::close-label :close? ::should-close))))
(spec/def ::socket-close! (spec/fspec :args (spec/cat :channel some?)))

(spec/def ::broker-update-fn (spec/fspec :args (spec/cat :old-value (spec/nilable some?) :retried? boolean?)
                                        :ret ::broker-data))

(spec/def ::broker-update-entity (spec/fspec :args (spec/cat :integration-name ::name :broker-key ::broker-key :broker-update-fn ::broker-update-fn)))

(spec/def ::broker-encoded-message string?)

(spec/def ::broker-subscribe-handler (spec/fspec :args (spec/cat :message ::broker-encoded-message)))
(spec/def ::broker-subscribe (spec/fspec :args (spec/cat :integration-name ::name 
                                                         :broker-key ::broker-key 
                                                         :broker-subscribe-handler ::broker-subscribe-handler)))

(spec/def ::broker-publish-message (spec/fspec :args (spec/cat :integration-name ::name 
                                                              :broker-key ::broker-key 
                                                              :message ::broker-encoded-message)))

(spec/def ::process-side-effects (spec/fspec :args (spec/cat :side-effects ::side-effects-message)))

(spec/def ::integration (spec/keys :req-un [::name
                                            ::path
                                            ::initial-authorisation
                                            ::broker-key-fn
                                            ::broker-authorisation
                                            ::process-websocket-message
                                            ::process-broker-message
                                            ::on-close]))

(spec/def ::logger (spec/fspec :args (spec/coll-of string?)))
(spec/def ::exception (spec/with-gen  #(instance? Throwable %)
                          #(spec/gen #{(Exception.)})))
(spec/def ::error-handler (spec/fspec :args (spec/cat :error ::exception :description string?)))
