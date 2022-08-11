(ns electric-socket.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as ss]
            [electric-socket.core :as socket]
            [failjure.core :as f]
            [clojure.data.json :refer [read-str write-str]]))


(defn error-response
  ([code errors status]
   (let [update-values (fn [f m & args]
                         (reduce
                          (fn [r [k v]]
                            (assoc r k (apply f v args))) {} m))
         fixed-errors (update-values
                       #(if (or
                             (vector? %)
                             (list? %))
                          (first %) %) errors)]
     {:status status
      :body
      {"success" false
       "error-code" code
       "errors" fixed-errors}}))
  ([code errors] (error-response code errors 400)))

(defn api-validation-failure [code errors]
  (f/fail (pr-str (error-response code errors))))


; ------------------------- mock socket --------------------------------

(defn bind-mock-socket [socket socket-handler]
  (let [{:keys [on-connect on-close on-text]} socket-handler]
    (add-watch socket :handler 
               (fn [_ _ old-value new-value]
                 (doseq [k (ss/union (set (keys old-value)) (set (keys new-value)))]
                   (let [{old-id :id old-open :open old-message :message-to-send} (get old-value k)
                         {new-id :id new-open :open new-message :message-to-send} (get new-value k)]
                     (if (= old-open new-open)
                       (when (not= (:cnt old-message) (:cnt new-message))
                         (on-text new-id (:msg new-message)))
                       (if new-open
                         (on-connect new-id)
                         (on-close old-id 0 "closed")))))))))

(defn mock-socket [data-received]
  (let [sockets (atom {})
        close (fn [channel] 
                (swap! sockets (fn [socks]
                                 (let [socket (get socks channel)
                                       {:keys [open]} socket]
                                   (assert socket)
                                   (assert open)                       
                                   (update-in socks [channel :open] not)))))
        
        socket-send (fn [channel msg & args]                
                      (swap! sockets (fn [socks]
                                       (let [socket (get socks channel)
                                             {:keys [open]} socket]                                         
                                         (assert open)                                   
                                         (assert socket)
                                         (update-in socks [channel :received-messages] #(conj % msg)))))
                      (when data-received (data-received channel msg))
                      (when (and (= 2 (count args)) (last args)) (close channel)))
        
        connect (fn [] 
                  (let [new-sock (java.util.UUID/randomUUID)
                        state {:open true
                               :id new-sock
                               :received-messages []
                               :message-to-send {:cnt 0
                                                 :msg nil}}]
                    (swap! sockets #(assoc % new-sock state))
                    new-sock))        
        
        send (fn [channel msg]
               (swap! sockets (fn [socks]
                                (let [socket (get socks channel)
                                      {:keys [open message-to-send]} socket]                                         
                                  (assert socket)
                                  (assert open)
                                  (assoc-in socks [channel :message-to-send] {:cnt (inc (:cnt message-to-send))
                                                                              :msg msg})))))]
    
    {:connect! connect
     :send send
     :socket-close! close
     :socket-send! socket-send
     :socket sockets}))

; ------------------------- mock broker --------------------------------

(defn mock-redis []
  (let [state (atom {})
        subscribers (atom {})
        publish (fn [superkey key message]  
                  (let [subss (get @subscribers (str superkey "." key))]
                    (doseq [sub subss]
                      (sub message))))
        ; this does not need to be idempotent
        subscribe (fn [superkey key handler]                    
                    (swap! subscribers (fn [o]
                                         (update o (str superkey "." key) (fn [sp] (if sp (conj sp handler) [handler]))))))
        update-state (fn [superkey key handler]
                       (let [r-key (str superkey "." key)]
                         (swap! state (fn [o]
                                        (let [new-value (handler (get o r-key) false)
                                              red (case new-value
                                                :swap/abort o
                                                :swap/delete (dissoc o r-key)
                                                (assoc o r-key new-value))]
                                          red)
                                        ))))
        ]
    {:broker-subscribe subscribe
     :broker-update-entity update-state
     :broker-publish-message publish
     :broker [state subscribers]}))

; ------------------------- TESTS --------------------------------

(def no-side-effects (constantly []))

(deftest  simple-sockets 
  (let [broker (mock-redis)
        m-socket (mock-socket nil)
        integration {:name "simple-integration"
                     :path "/no/path"
                     :initial-authorisation (fn [_channel initial-message]
                                              (if (and 
                                                   (= (:auth initial-message) "secret")
                                                   (:user initial-message)
                                                   (:room initial-message)
                                                   (:role initial-message))
                                                {:user (:user initial-message)
                                                 :room (:room initial-message)
                                                 :role (:role initial-message)}
                                                (api-validation-failure
                                                 1
                                                 {"auth" "Bad password"})))
                     :channel-identifier (fn [_channel data] (:user data))
                     :broker-key-fn (fn [_channel data] (:room data))
                     :broker-authorisation (fn [channel c-data r-data] 
                                            (let [role (or (keyword (:role c-data)) :unknown)
                                                  success (or (not (role r-data)) (= (:user-id (role r-data)) (:user c-data)))
                                                  participant-changed {:id "participantChange" :callid (:room c-data) :status (if (role r-data) "reconnect" "connect")}
                                                  other-participant (if  (= role :dietitian) (:client r-data) (:dietitian r-data))
                                                  new-r-data (if success
                                                               (merge r-data
                                                                      {role {:user-id (:user c-data) 
                                                                             :channel (hash channel)}
                                                                       :call-id (:room c-data)})
                                                               r-data)
                                                  success-response {:id "auth"
                                                                    :success true
                                                                    :room-full (if (and (:dietitian new-r-data) (:client new-r-data)) true false)
                                                                    :state new-r-data}]
                                              {:should-close? (if success false true)
                                               :channel-data c-data
                                               :websocket-response (if success success-response (api-validation-failure
                                                                                          1
                                                                                          {"auth" "Too many users"}))
                                               :broker-data new-r-data
                                               :broker-messages (concat
                                                                (if success [{:target (or (:user c-data) "aa")
                                                                              :message {:disconnect true}}]
                                                                    [])
                                                                (if (and success other-participant)
                                                                  [{:target (:user-id other-participant)
                                                                    :message participant-changed}]
                                                                  []))
                                               :side-effects (if (and success (not other-participant))
                                                               (fn [_channel _channel-data _broker-data] [["aaaaaaaa"]])
                                                               no-side-effects)}))
                     
                     :process-websocket-message (fn [_channel message channel-data broker-data]
                                           (let [role (keyword (:role channel-data))
                                                 other-role (if (= :dietitian role) :client :dietitian)
                                                 target (:user-id (other-role broker-data))]                                             
                                             {:channel-data channel-data 
                                              :websocket-response (if target {:id (:id message)
                                                                       :success true}
                                                                        (api-validation-failure
                                                                         1
                                                                         {"auth" "The room is empty"}))
                                              :broker-messages (if target [{:target target
                                                                           :message message}] []) 
                                              :should-close? (if target false true)
                                              :error-namespace "transfer"
                                              :broker-data broker-data
                                              :side-effects no-side-effects})) 
                     :process-broker-message (fn [_channel broker-message channel-data broker-data] 
                                              (let [{:keys [broker-key message]} broker-message
                                                    disconnect (if (:disconnect message) true false)]
                                                (if (= (:call-id broker-data) broker-key)
                                                  {:channel-data channel-data
                                                   :broker-data broker-data
                                                   :websocket-response (if disconnect
                                                                  {:id "disconnectOnNewConnectionTrumpsOldConnection"
                                                                   :callid (str broker-key)}
                                                                  message) ; we pass all messages as-are except the disconnect one
                                                   :broker-messages []
                                                   :should-close? disconnect
                                                   :side-effects no-side-effects}
                                                  {:channel-data channel-data
                                                   :broker-data :swap/abort
                                                   :websocket-response nil
                                                   :broker-messages []
                                                   :should-close? false
                                                   :side-effects no-side-effects})))
                     :on-close (fn [channel channel-data broker-data] 
                                 (let [role (or (keyword (:role channel-data)) :unknown)
                                       new-broker-data (when (= (hash channel) (:channel (role broker-data))) ; we have disconnected
                                                        (dissoc broker-data role))
                                       remaining-inhabitant (or (:dietitian new-broker-data) (:client new-broker-data))]
                                   {:broker-messages (if remaining-inhabitant
                                                      [{:target (:user-id remaining-inhabitant)
                                                        :message {:id "participantChange" 
                                                                  :callid (str (:call-id new-broker-data)) 
                                                                  :status "disconnect"}}]
                                                      [])
                                    :broker-data (if (not new-broker-data) 
                                                  :swap/abort
                                                  (if remaining-inhabitant
                                                    new-broker-data
                                                    :swap/delete))}))}

        handler (socket/add-jetty-integration integration
                                        (:socket-send! m-socket)
                                        (:socket-close! m-socket)
                                        (:broker-update-entity broker)
                                        (:broker-subscribe broker)
                                        (:broker-publish-message broker)
                                        (fn [_] nil)
                                        (fn [& args] nil)
                                        (fn [e ed] nil))
        ]
    (bind-mock-socket (:socket m-socket) handler)
    (let [sock (:socket m-socket)
          connect! (:connect! m-socket)
          disconnect! (:socket-close! m-socket)
          send (:send m-socket)
          broker-content (first (:broker broker))]
     
      (testing "Bad login"
        (let [new-conn (connect!)]
          (is (uuid? new-conn))
          (is (get @sock new-conn))
          (send new-conn (write-str {:auth "socret"
                                     :user "marek"
                                     :room "myroom"
                                     :role :dietitian}))
          (let [s (get @sock new-conn)
                m (read-str (first (:received-messages s)))]
            (is (empty? @broker-content))
            (is (false? (:open s)))
            (is (count (:received-messages s)) 1)
            (is (= false (get m "success")))
            
            )))

      (testing "Good login; "
        (let [new-conn (connect!)]
          (is (uuid? new-conn))
          (is (get @sock new-conn))
          (send new-conn (write-str {:auth "secret"
                                     :user "marek"
                                     :room "myroom"
                                     :role :dietitian}))
          (let [s (get @sock new-conn)
                m (read-str (or (first (:received-messages s)) "{}"))]
            (is (seq @broker-content))
            (is (true? (:open s)))
            (is (= 1 (count (:received-messages s))))
            (is (= true (get m "success"))))
          (testing "Double login; "
            (let [new-conn2 (connect!)]
              (is (uuid? new-conn2))
              (is (get @sock new-conn2))
              (send new-conn2 (write-str {:auth "secret"
                                          :user "marek"
                                          :room "myroom"
                                          :role :dietitian}))
              (let [s1 (get @sock new-conn)
                    m1 (read-str (or (second (:received-messages s1)) "{}"))
                    s2 (get @sock new-conn2)
                    m2 (read-str (or (first (:received-messages s2)) "{}"))
                    ]
                (is (seq @broker-content))
                (is (true? (:open s2)))
                (is (false? (:open s1)))
                (is (= 2 (count (:received-messages s1))))
                (is (= "disconnectOnNewConnectionTrumpsOldConnection" (get m1 "id")))
                (is (= true (get m2 "success"))))

              (testing "Client connects; " 
                (let [new-conn3 (connect!)]
                  (is (uuid? new-conn3))
                  (is (get @sock new-conn3))

                  (send new-conn3 (write-str {:auth "secret"
                                              :user "jarek"
                                              :room "myroom"
                                              :role :client}))

                  (let [s1 (get @sock new-conn)
                        s2 (get @sock new-conn2)
                        s3 (get @sock new-conn3)
                        m3 (read-str (or (first (:received-messages s2)) "{}"))
                        ]
                    (is (seq @broker-content))
                    (is (true? (:open s2)))
                    (is (false? (:open s1)))
                    (is (= 2 (count (:received-messages s2))))
                    (is (= 1 (count (:received-messages s3))) )
                    (is (= true (get m3 "success"))))
                  (testing "Client disconnects; "  
                    (disconnect! new-conn3)

                    (let [s2 (get @sock new-conn2)
                          rm (read-str (last (:received-messages s2)) :key-fn keyword)]  
                      (is (= 3 (count (:received-messages s2))))
                      (is (= "participantChange" (:id rm)))
                      (is (= "disconnect" (:status rm)))
                      (is (not (nil? (get @broker-content "simple-integration.myroom"))))
                      (is (not (nil? (:dietitian (get @broker-content "simple-integration.myroom")))))
                      (is (nil? (:client (get @broker-content "simple-integration.myroom")))))

                    (testing "Two new connections as client"
                      (let [new-conn4 (connect!)
                            new-conn5 (connect!)]

                        (send new-conn5 (write-str {:auth "secret"
                                                    :user "jarek"
                                                    :room "myroom"
                                                    :role :client}))

                        (send new-conn4 (write-str {:auth "secret"
                                                    :user "jarek"
                                                    :room "myroom"
                                                    :role :client}))
                        (let [s5 (get @sock new-conn5)
                              rm (read-str (last (:received-messages s5)) :key-fn keyword)]
                          (is (not (:open s5)))
                          (is (= 2 (count (:received-messages s5))))
                          (is (= "disconnectOnNewConnectionTrumpsOldConnection" (:id rm))))


                        (let [s2 (get @sock new-conn2)
                              rm (read-str (last (:received-messages s2)) :key-fn keyword)
                              rm2 (read-str (second (reverse (:received-messages s2))) :key-fn keyword)]
                          (is (= 5 (count (:received-messages s2))))
                          (is (= "participantChange" (:id rm)))
                          (is (= "reconnect" (:status rm)))
                          (is (= "participantChange" (:id rm2)))
                          (is (= "connect" (:status rm2)))

                          (is (not (nil? (get @broker-content "simple-integration.myroom"))))
                          (is (not (nil? (:dietitian (get @broker-content "simple-integration.myroom")))))
                          (is (not (nil? (:client (get @broker-content "simple-integration.myroom"))))))
                        (testing "Pass a message d -> c and c -> d;"
                          ; diet new-conn2
                          ; client new-conn4
                          (send new-conn2 (write-str {:id "someMessage"
                                                      :data "someData"}))
                          
                          (let [s2 (get @sock new-conn2) ; dietitian was sending, nothing new came
                                rm (read-str (last (:received-messages s2)) :key-fn keyword)]  
                          (is (= 6 (count (:received-messages s2))))
                          (is (= "someMessage" (:id rm)))
                          (is (= true (:success rm)))
                          (is (nil? (:data rm)))

                          (is (not (nil? (get @broker-content "simple-integration.myroom"))))
                          (is (not (nil? (:dietitian (get @broker-content "simple-integration.myroom")))))
                          (is (not (nil? (:client (get @broker-content "simple-integration.myroom"))))))

                          (let [s4 (get @sock new-conn4) ; dietitian was sending, new data arrived
                                rm (read-str (last (:received-messages s4)) :key-fn keyword)]
                          (is (= 2(count (:received-messages s4))))
                          (is (= "someMessage" (:id rm)))
                          (is (= "someData" (:data rm)))

                          (is (not (nil? (get @broker-content "simple-integration.myroom"))))
                          (is (not (nil? (:dietitian (get @broker-content "simple-integration.myroom")))))
                          (is (not (nil? (:client (get @broker-content "simple-integration.myroom"))))))

                          

                          (send new-conn4 (write-str {:id "otherMessage"
                                                      :data "someOtherData"}))

                          (let [s4 (get @sock new-conn4) 
                                rm (read-str (last (:received-messages s4)) :key-fn keyword)]  
                          (is (= 3 (count (:received-messages s4))))
                          (is (= "otherMessage" (:id rm)))
                          (is (= true (:success rm)))
                          (is (nil? (:data rm)))

                          (is (not (nil? (get @broker-content "simple-integration.myroom"))))
                          (is (not (nil? (:dietitian (get @broker-content "simple-integration.myroom")))))
                          (is (not (nil? (:client (get @broker-content "simple-integration.myroom"))))))

                          (let [s2 (get @sock new-conn2) 
                                rm (read-str (last (:received-messages s2)) :key-fn keyword)]  
                          (is (= 7 (count (:received-messages s2))))
                          (is (= "otherMessage" (:id rm)))
                          (is (= "someOtherData" (:data rm)))

                          (is (not (nil? (get @broker-content "simple-integration.myroom"))))
                          (is (not (nil? (:dietitian (get @broker-content "simple-integration.myroom")))))
                          (is (not (nil? (:client (get @broker-content "simple-integration.myroom"))))))
                          

                          (testing "Disconnects and cleanup;"
                            (disconnect! new-conn4)
                            (disconnect! new-conn2)

                            (let [s4 (get @sock new-conn4) ; notching changed here, exept for close status
                                  rm (read-str (last (:received-messages s4)) :key-fn keyword)]
                              (is (not (:open s4)))
                              (is (= 3 (count (:received-messages s4))))
                              (is (= "otherMessage" (:id rm)))
                              (is (= true (:success rm)))
                              (is (nil? (:data rm)))
                              
                              (is (nil? (get @broker-content "simple-integration.myroom"))))
                            (let [s2 (get @sock new-conn2) ; one participant changed arrived before disconnection
                                  rm (read-str (last (:received-messages s2)) :key-fn keyword)]
                              (is (not (:open s2)))
                              (is (= 8 (count (:received-messages s2))))
                              (is (= "participantChange" (:id rm)))
                              (is (= "disconnect" (:status rm)))
                              
                              (is (nil? (get @broker-content "simple-integration.myroom"))))))))))))))))))
