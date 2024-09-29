(ns app.topic-gossiper
  (:require
   [hashgraph.main :as hg]
   [hashgraph.topic :as hgt]
   [hashgraph.schemas :as hgs]
   [hashgraph.utils.core :refer [hash=] :refer-macros [defn* l letl letl2 when-let*] :as utils]
   [hashgraph.utils.lazy-derived-atom :refer [lazy-derived-atom]]

   [app.styles :refer [reg-styles!] :as styles]
   [app.state :as as]
   [app.io :refer [reg< send-message]]
   [app.topic :as at]
   [app.creds :as ac]

   [rum.core :refer [defc defcs] :as rum]
   [clojure.set :as set]
   [clojure.test :refer [deftest testing is are run-tests]]
   [malli.core :as m]))

(defonce *topic->creator->latest-acked-creator->tx-event (atom {}))
(defn topic->topic-gossiper-id [topic] (keyword (str "topic-gossiper-" (hash topic))))

(def *topic->creator->latest-sent-tip (atom {}))
(def *topic->tip->all-sent? (atom {}))

(defn topic-gossiper [topic new-tip-taped]
  ;; NOTE: processes only last event of an update to tape
  ;; ensure appends is atomic
  ;; last event is expected to be _yours_

  #_(l [:topic-gossiper topic new-tip-taped])
  (when-not (get-in @*topic->tip->all-sent? [topic new-tip-taped])
    (when-let* [my-did-peer    @as/*my-did-peer
                other-creators (not-empty (-> new-tip-taped hg/evt->db :topic-members set (disj my-did-peer))) ;; check if they're in signing keys
                ]
      (l [:topic-gossiper topic new-tip-taped])
      (letl2 [?projected-ke  (-> new-tip-taped at/->projected-db :ke)
              creator->tip   (-> new-tip-taped hgt/event->creator->tip)
              creator->?ke   (->> creator->tip
                                  (map (fn [[creator tip]]
                                         [creator (ac/evt->?ke tip)]))
                                  (into {}))

              creator->tip-tx-event (hg/event->creator->tip-tx-event new-tip-taped)
              creator->tip          (hgt/event->creator->tip new-tip-taped)
              tip-tx-event          (get creator->tip-tx-event my-did-peer)
              *sent?                (atom false)]
        (doseq [other-creator (shuffle other-creators)
                :when         (not (hash= new-tip-taped (get-in @*topic->creator->latest-sent-tip [topic other-creator]))) ;; haven't sent this tip to other-creator yet
                :while        (not @*sent?)]
          (letl2 [g$ (hgt/topic-hash+grafter-tip+graftee->g$ (hash topic) new-tip-taped other-creator)
                  _  (l g$)
                  creator-needs?
                  (or (nil? (g$ hgt/g$-stem)) ;; we haven't heard creator ack topic
                      (-> g$
                          (get hgt/g$-c$->ss$)
                          (->> (some (fn [[_c$ ss$]]
                                       (->> ss$ (some (fn [s$]
                                                        (get s$ hgt/s$-tx)))))))
                          some?))

                  ?ke              (get creator->?ke other-creator)
                  needs-consensus? (not (hash= ?ke ?projected-ke))

                  other-creator-creator->tip-tx-event (some-> creator->tip
                                                              (get other-creator)
                                                              hg/event->creator->tip-tx-event)
                  latest-acked-creator->tip-tx-event  (get-in @*topic->creator->latest-acked-creator->tx-event [topic other-creator])
                  needs-ack?                          (not= other-creator-creator->tip-tx-event latest-acked-creator->tip-tx-event)]
            (when (or creator-needs? needs-consensus? needs-ack?)
              (swap! *topic->creator->latest-acked-creator->tx-event assoc-in [topic other-creator] creator->tip-tx-event)
              (reset! *sent? true)
              (swap! *topic->creator->latest-sent-tip assoc-in [topic other-creator] new-tip-taped)
              (if (> (-> new-tip-taped meta :tip/tape count) 250)
                (l [:aborting-sending-g$-after-250-events])
                (do (l [:sending-g$-to g$ other-creator])
                    (send-message other-creator {:type "https://didcomm.org/hashgraph/1.0/g$",
                                                 :body g$}))))))
        (when-not @*sent?
          (l [:all-sent topic new-tip-taped])
          (l (swap! *topic->tip->all-sent? assoc topic {new-tip-taped true})))))))

(defn start-timely-dissemination!->stop!
  ([]
   (l :timely-dissemination!-started)
   (start-timely-dissemination!->stop! (atom false)))
  ([*stop?]
   (when-not @*stop?
     #_(l :timely-dissemination!)
     (doseq [[topic tip-taped] @as/*topic->tip-taped]
       (topic-gossiper topic tip-taped))
     (js/setTimeout #(start-timely-dissemination!->stop! *stop?) 333)
     #(do (l :timely-dissemination!-stopped) (reset! *stop? true)))))

#_
(defn reg-topic-gossiper! [topic]
  (l [:reg-topic-gossiper! topic])
  (or topic (throw (js/Error. "Can't reg topic gossiper for nil topic")))
  (let [topic-gossiper-id (topic->topic-gossiper-id topic)
        *tip-taped        (rum/cursor as/*topic->tip-taped topic)
        ;; TODO associate topics with my-did that participates in it, my-did->topic-hash->tape
        my-did-peer       (or @as/*my-did-peer (throw (js/Error. "Tried registering gossiper for nil my-did-peer")))]
    (add-watch *tip-taped topic-gossiper-id
               (fn [_ _ _ new-tip-taped]
                 (topic-gossiper topic new-tip-taped)))
    (topic-gossiper topic @*tip-taped)))

#_#_#_
(defn unreg-topic-gossiper! [topic]
  (remove-watch (rum/cursor as/*topic->tip-taped topic) (topic->topic-gossiper-id topic)))

(defn reg-topic-gossipers! []
  (doseq [topic @as/*topics]
    (reg-topic-gossiper! topic)))

(defn unreg-topic-gossipers! []
  (doseq [topic @as/*topics]
    (unreg-topic-gossiper! topic)))


(m/=> ?tip-taped+grafter+g$->?grafted-tip-taped [:=> [:cat [:maybe :tip-taped] :public-key :g$] [:maybe :tip-taped]])
(defn ?tip-taped+grafter+g$->?grafted-tip-taped [?tip-taped grafter g$]
  (if-let [novel-events (second (hgt/?<<tip+grafter+g$->?novel-events ?tip-taped grafter g$))]
    (let [sender-tip        (-> novel-events reverse first)
          ack-event         (cond-> {hg/creator       (or @as/*my-did-peer (throw (js/Error "*my-did-peer is nil")))
                                     ;; TODO select my creator that participatest in the topic (from current stake-map)
                                     hg/other-parent  sender-tip
                                     hg/creation-time (.now js/Date)}
                              ?tip-taped (assoc hg/self-parent ?tip-taped))
          novel-tape-events (conj (vec novel-events) ack-event)
          ack-event-taped   (hgt/tip+novel-events->tip-taped ack-event novel-tape-events)]
      ack-event-taped)
    (do (l ["nothing to graft atop ?tip-taped in g$" ?tip-taped g$])
        ?tip-taped)))

(reg< "https://didcomm.org/hashgraph/1.0/g$"
      (fn [{g$      :body
            grafter :from}]
        (l [:received-g$ g$])
        (let [?known-topic (@as/*topic-hash->topic (g$ hgt/g$-topic-hash))
              topic (or ?known-topic
                        (hgt/g$->?topic g$)
                        (throw (ex-info "can't find topic of g$" {:g$ g$})))]
          (swap! (rum/cursor as/*topic->tip-taped topic)
                 (fn [?tip-taped]
                   (?tip-taped+grafter+g$->?grafted-tip-taped ?tip-taped (l grafter) g$))))))

#_
(defn broadcast-topic! [topic]
  (let [other-creators (-> topic :share-stake-log hg/share-stake-log->stake-map keys set (disj @as/*my-did-peer))]
    (l [:broadcasting-to-other-creators other-creators])
    (doseq [other-creator other-creators]
      (send-message other-creator {:type "https://didcomm.org/hashgraph/1.0/topic-invite",
                                   :body topic}))))

#_
(reg< "https://didcomm.org/hashgraph/1.0/topic-invite"
      (fn [{topic :body}]
        (l [:received-topic-invite topic])
        (when (nil? (get @as/*topic->tape topic))
          (swap! as/*topic->tape assoc topic []))))

#_
(add-watch as/*topics ::topic-gossiper-manager
           (fn [_ _ old-topics new-topics]
             (when-let [added-topics (not-empty (set/difference (set new-topics)
                                                                (set old-topics)))]
               (l added-topics)
               (doseq [added-topic added-topics]
                 (reg-topic-gossiper! added-topic)))

             ;; TODO on topic remove remove
             ))
