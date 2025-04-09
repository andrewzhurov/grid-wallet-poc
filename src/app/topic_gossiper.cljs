(ns app.topic-gossiper
  (:require
   [hashgraph.main :as hg]
   [hashgraph.topic :as hgt]
   [hashgraph.schemas :as hgs]
   [hashgraph.utils.core :refer [hash= map-vals] :refer-macros [defn* l nl letl letl2 when-let* when-letl*] :as utils]
   [hashgraph.utils.lazy-derived-atom :refer [lazy-derived-atom]]

   [app.styles :refer [reg-styles!] :as styles]
   [app.state :as as]
   [app.io :refer [reg< send-message]]
   [app.topic :as at]
   [app.creds :as ac]

   [rum.core :refer [defc defcs] :as rum]
   [clojure.set :as set]
   [clojure.test :refer [deftest testing is are run-tests]]
   [clojure.data]
   [malli.core :as m]
   [app.control :as actrl]))


(defn topic-path->topic-gossiper-id [topic] (keyword (str "topic-gossiper-" (hash topic))))

(defonce *topic-path->creator->latest-acked-creator->sp-tip-tx-event (atom (hash-map)))
(defonce *topic-path->creator->latest-sent-tip (atom (hash-map)))
(defonce *topic-path->all-sent-tip (atom (hash-map)))

(defn topic-gossiper [topic-path new-tip-taped]
  ;; NOTE: processes only last event of an update to tape
  ;; ensure appends is atomic
  ;; last event is expected to be _yours_

  #_(l [:topic-gossiper topic new-tip-taped])
  (when (not (hash= new-tip-taped (@*topic-path->all-sent-tip topic-path)))
    (when-let* [my-creator     (hg/creator new-tip-taped)
                other-creators (not-empty (-> new-tip-taped hg/evt->db :active-creators (disj my-creator)))]
      (l [:topic-gossiper topic-path new-tip-taped my-creator other-creators])
      (letl2 [topic                    (last topic-path)
              cr                       (-> new-tip-taped hg/->concluded-round)
              db                       (-> cr hg/cr->db)
              creator->unique-tip      (-> new-tip-taped hg/event->creator->unique-tip)
              creator->sp-tip-tx-event (-> new-tip-taped hg/event->creator->sp-tip-tx-event)
              *sent?                   (atom false)]
        (doseq [other-creator (shuffle other-creators)
                :while        (not @*sent?)]
          (when (letl2 [last-sent-tip (get-in @*topic-path->creator->latest-sent-tip [topic-path other-creator])]
                  (or (nil? last-sent-tip)
                      (and (nl :havent-sent?
                               (not (hash= new-tip-taped last-sent-tip)))

                           (nl :not-sybil?
                               (-> new-tip-taped hg/event->creator->tips (get other-creator) count (<= 1)))

                           #_(or (nl :know-other-creator-learned-sent-tip?
                                   (some-> other-creator
                                           creator->unique-tip
                                           hg/event->creator->unique-tip
                                           (get my-creator)
                                           (hg/ancestor? last-sent-tip)))

                               ;; mayb derive reasons as f(event, creator)
                               (nl :he-would-not-need?
                                   (not (contains? (:send-reasons (meta last-sent-tip)) :send-reason/need-consensus)))))))

            (when-let [send-reasons
                       (not-empty
                        (cond-> #{}
                          (hg/root-event? new-tip-taped)
                          (conj :send-reason/root-event)

                          (nl :need-consensus?
                              (or (not (hash= (-> new-tip-taped at/event->projected-db :ke)
                                              (-> db :ke)))
                                  (not= (-> new-tip-taped at/event->projected-db :active-creators)
                                        (-> db :active-creators))))
                          (conj :send-reason/need-consensus)

                          #_#_
                          (when-letl* [other-creator-unique-tip           (-> other-creator creator->unique-tip)
                                       other-creator-not-known-crs        (->> cr
                                                                               (iterate :concluded-round/prev-concluded-round)
                                                                               (take-while some?)
                                                                               (take-while (fn [some-cr]
                                                                                             (when-letl* [prev-some-cr              (-> some-cr :concluded-round/prev-concluded-round)
                                                                                                          some-cr-witness-concluded (-> some-cr :concluded-round/witness-concluded)]
                                                                                               (and (when-letl* [some-cr-other-creator-unique-tip (-> some-cr-witness-concluded
                                                                                                                                                      hg/event->creator->unique-tip
                                                                                                                                                      (get other-creator))]
                                                                                                      (not= (l (hg/->round-number some-cr-witness-concluded prev-some-cr))
                                                                                                            (l (hg/->round-number some-cr-other-creator-unique-tip prev-some-cr))))
                                                                                                    (not (l (hg/ancestor? other-creator-unique-tip some-cr-witness-concluded))))))))
                                       other-creator-not-known-useful-cr? (->> other-creator-not-known-crs
                                                                               (some (fn [not-known-cr]
                                                                                       (->> not-known-cr
                                                                                            :concluded-round/es-r
                                                                                            (some hg/tx)))))]
                            other-creator-not-known-useful-cr?)
                          (conj :send-reason/needs-cr)

                          (when-letl* [other-creator-unique-tip (-> other-creator creator->unique-tip)
                                       other-creator-db         (-> other-creator-unique-tip hg/evt->db) ;; darn inefficient, but darn simple
                                       doesnt-know-novel-ke?    (not (hash= (-> other-creator-db :ke)
                                                                            (-> db :ke)))]
                            (l {:my-ke   (-> db :ke)
                                :oc-ke   (-> other-creator-db :ke)
                                :ke-diff (clojure.data/diff (-> db :ke) (-> other-creator-db :ke))})
                            doesnt-know-novel-ke?)
                          (conj :send-reason/needs-cr)

                          (nl :needs-ack?
                              (or (nl :sp-tips-differ?
                                      (not (hash= creator->sp-tip-tx-event (get-in @*topic-path->creator->latest-acked-creator->sp-tip-tx-event [topic-path other-creator]))))
                                  ;; afaik, he doesn't know I know of Topic
                                  (let [?oc-tip (-> creator->unique-tip (get other-creator))]
                                    (or (nil? ?oc-tip)
                                        (-> ?oc-tip hg/event->creator->unique-tip (get my-creator) nil?)))))
                          (conj :send-reason/needs-ack)))]
              (l send-reasons)
              (letl2 [g$ (hgt/topic-hash+grafter-tip+graftee->g$ (hash (last topic-path)) new-tip-taped other-creator)]
                (swap! *topic-path->creator->latest-acked-creator->sp-tip-tx-event assoc-in [topic-path other-creator] creator->sp-tip-tx-event)
                (swap! *topic-path->creator->latest-sent-tip assoc-in [topic-path other-creator] (vary-meta new-tip-taped assoc :send-reasons send-reasons))
                (reset! *sent? true)

                #_(if (-> new-tip-taped meta :tip/tape count (> 250))
                    (l [:aborting-sending-g$-after-250-events]))
                (letl2 [other-creator-member-init-key (nth (:member-init-keys-log db) other-creator)
                        other-creator-did-peer        (get-in db [:member-init-key->did-peer other-creator-member-init-key])]
                  (l [:sending-g$-to g$ other-creator other-creator-member-init-key other-creator-did-peer])
                  (send-message other-creator-did-peer {:type "https://didcomm.org/hashgraph/1.0/g$",
                                                        :body g$}))))))
        (when-not @*sent?
          (l [:all-sent topic-path new-tip-taped])
          (l (swap! *topic-path->all-sent-tip assoc topic-path new-tip-taped)))))))

(def gossip-ms 333)

(defn start-timely-dissemination!->stop!
  ([]
   (l :timely-dissemination!-started)
   (start-timely-dissemination!->stop! (atom false)))
  ([*stop?]
   (when-not @*stop?
     #_(l :timely-dissemination!)
     (doseq [[topic-path tip-taped] @as/*topic-path->tip-taped]
       (topic-gossiper topic-path tip-taped))
     (js/setTimeout #(start-timely-dissemination!->stop! *stop?) gossip-ms)
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


(m/=> ?tip-taped+g$->?grafted-tip-taped [:=> [:cat [:maybe :tip-taped] :g$] [:maybe :tip-taped]])
(defn ?tip-taped+g$->?grafted-tip-taped [?tip-taped g$]
  (if-let [novel-events (second (hgt/?<<tip+g$->?novel-events ?tip-taped g$))]
    (let [graftee           (get g$ hgt/g$-graftee)
          grafter-tip       (-> novel-events reverse first)
          ack-event         (cond-> {hg/creator       graftee
                                     ;; TODO select my creator that participatest in the topic (from current stake-map)
                                     hg/other-parent  grafter-tip
                                     hg/creation-time (.now js/Date)}
                              ?tip-taped (assoc hg/self-parent ?tip-taped))
          novel-tape-events (conj (vec novel-events) ack-event)
          ack-event-taped   (hgt/tip+novel-events->tip-taped ack-event novel-tape-events)]
      ack-event-taped)
    (do (l ["nothing to graft atop ?tip-taped in g$" ?tip-taped g$])
        ?tip-taped)))

(reg< "https://didcomm.org/hashgraph/1.0/g$"
      (fn [{g$ :body}]
        (l [:received-g$ g$])
        (let [?known-topic            (@as/*topic-hash->topic (g$ hgt/g$-topic-hash))
              topic                   (or ?known-topic
                                          (hgt/g$->?topic g$)
                                          (throw (ex-info "can't find topic of g$" {:g$ g$})))
              graftee-member-init-key (get g$ hgt/g$-graftee-member-init-key)
              member-topic-path       (actrl/init-key->topic-path graftee-member-init-key)
              topic-path              (conj member-topic-path topic)]
          (swap! (rum/cursor as/*topic-path->tip-taped topic-path)
                 (fn [?tip-taped]
                   (?tip-taped+g$->?grafted-tip-taped ?tip-taped g$))))))

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
