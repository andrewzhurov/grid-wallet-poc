(ns app.io
  (:require [hashgraph.utils.core :refer-macros [defn* l letl] :as utils]
            [hashgraph.main :as hg]

            [app.state :as as]

            ["/lib/agent2" :refer [createAgent]]

            [cognitect.transit :as t]))

(def transit-type :json)
(def transit-writer (t/writer transit-type))
(defn message->message-raw-js [message]
  (let [message-raw (cond-> message
                      (:body message) (update :body #(t/write transit-writer %)))
        message-raw-js (clj->js message-raw)]
    message-raw-js))

(def transit-reader (t/reader transit-type))
(defn message-raw-js->message [message-raw-js]
  (let [message-raw (js->clj message-raw-js :keywordize-keys true)
        message (cond-> message-raw
                  (string? (:body message-raw)) (update :body #(t/read transit-reader %)))]
    message))

(defn to-me? [message] (some-> message :to set (contains? @as/*my-did-peer) boolean))
(defn handle-message [message-raw-js]
  (let [message      (message-raw-js->message message-raw-js)
        message-type (:type message)]
    (l message)
    (if (not (to-me? message))
      (js/console.warn "Received message that wasn't addressed to me:" message)
      (if-let [handler (get @as/*message-handlers message-type)]
        (handler message)
        (js/console.warn "No message registered for message type:" message-type)))))

(defonce agent (createAgent
                (clj->js
                 {:ondid          (fn [did]
                                    (set! hg/main-creator did)
                                    (reset! as/*my-did-peer did)
                                    (.connect agent))
                  :onconnected    (fn [] (reset! as/*connected? true))
                  :ondisconnected (fn [] (reset! as/*connected? false))
                  :onmessage      handle-message
                  :ondiddoc       (fn [[did did-doc]] (l [did did-doc]) (swap! as/*did->did-doc assoc did (js->clj did-doc :keywordize-keys true)))})))


(defn send-message [to-did message]
  (->> message
       (message->message-raw-js)
       (.sendMessage agent to-did)))
;; TODO derive supported features out of registered handlers
(defn reg< [type handler] (swap! as/*message-handlers assoc type handler))
