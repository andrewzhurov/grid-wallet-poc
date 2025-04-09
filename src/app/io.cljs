(ns app.io
  (:require [hashgraph.utils.core :refer-macros [defn* l letl] :as utils]
            [hashgraph.main :as hg]

            [app.state :as as]

            ["/lib/agent2" :refer [createAgent]]

            [cognitect.transit :as t]
            [clojure.data :as data]
            [rum.core :as rum]))

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

(defn to-me? [mailbox message] (boolean (some-> message :to set (contains? (:mailbox/did-peer mailbox)))))
(defn handle-message [aid-topic-path mailbox message-raw-js]
  (let [message      (message-raw-js->message message-raw-js)
        message-type (:type message)]
    (l message)
    (if (not (to-me? mailbox message))
      (js/console.warn "Received message that wasn't addressed to me:" message)
      (if-let [handler (get @as/*message-handlers message-type)]
        (handler aid-topic-path message)
        (js/console.warn "No message registered for message type:" message-type)))))

;; (defn with-addressed )

(defn abandon-mailbox! [{:mailbox/keys [agent]}]
  (-> agent (.disconnect))
  (-> agent (.-worker) (.terminate)))

(defn create-mailbox! [*aid-topic-path] ;; as atom, for device-mailbox to be set ahead of device topic creation, and later be swapped to the actual device topic
  (let [->*mailbox #(rum/cursor as/*topic-path->mailbox @*aid-topic-path)
        _          (when-let [mailbox @(->*mailbox)]
                     (abandon-mailbox! mailbox))
        agent      (createAgent (clj->js
                                 {:ondid          (fn [did]
                                                    (swap! (->*mailbox) assoc :mailbox/did-peer did)
                                                    (-> @(->*mailbox) :mailbox/agent (.connect))) ;; TODO handle by automatic reconnect logic
                                  :ondiddoc       (fn [[did did-doc]])
                                  :onconnected    (fn [] (swap! (->*mailbox) assoc :mailbox/connected? true))
                                  :ondisconnected (fn [] (swap! (->*mailbox) assoc :mailbox/connected? false))
                                  :onmessage      (fn [msg] (handle-message @*aid-topic-path @(->*mailbox) msg))}))]
    (reset! (->*mailbox) {:mailbox/agent      agent
                          :mailbox/connected? false})))

#_
(add-watch as/*topic-path->mailbox ::abandon-stale-mailboxes
           (fn [_ _ old-topic-path->mailbox new-topic-path->mailbox]
             (let [stale-mailboxes (->> old-topic-path->mailbox
                                        (filter (fn [[old-topic-path old-mailbox]]
                                                  (not= (:agent old-mailbox) (:agent (new-topic-path->mailbox old-topic-path)))))
                                        (map second))]
               (for [stale-mailbox stale-mailboxes]
                 (do (l [:abandoning-stale-mailbox stale-mailbox])
                     (-> stale-mailbox :agent (.disconnect))
                     (-> stale-mailbox :agent (.-worker) (.terminate)))))))

(defn send-message [topic-path to-did message]
  (let [member-aid-topic-path       (vec (butlast topic-path))
        member-aid-topic-path-agent (-> @as/*topic-path->mailbox (get member-aid-topic-path) :mailbox/agent)]
    (->> message
         (message->message-raw-js)
         (.sendMessage member-aid-topic-path-agent to-did))))

;; TODO derive supported features out of registered handlers
(defn reg< [type handler] (swap! as/*message-handlers assoc type handler))
