(ns app.topic
  (:require
   [hashgraph.main :as hg]
   [hashgraph.topic :as hgt]
   [hashgraph.utils.lazy-derived-atom :refer [lazy-derived-atom]]
   [hashgraph.utils.core :refer [hash=] :refer-macros [defn* l letl letl2] :as utils]

   [app.state :as as]
   [app.control :as actrl]

   [rum.core :refer [defc defcs] :as rum]
   [clojure.set :as set]
   [clojure.test :refer [deftest testing is are run-tests]]
   [malli.core :as m]
   [hashgraph.app.state :as hga-state]))

(defn tape->tip [tape] (->> tape reverse first))
(defn tape->cr [tape] (->> tape tape->tip hg/->concluded-round))
(defn tape->stake-map [tape] (-> tape tape->cr hgt/cr->db hgt/db->stake-map keys))
(defn tape->creators [tape] (-> tape tape->stake-map keys))


(defn create-topic! [creator members & [init-db* partial-event]]
  (l [creator members init-db* partial-event])
  (let [topic-event       (merge (hg/create-topic-event creator members init-db*)
                                 partial-event)
        topic             (-> topic-event hg/topic)
        novel-events      [topic-event]
        topic-event-taped (hgt/tip+novel-events->tip-taped topic-event novel-events)]
    (swap! (rum/cursor as/*topic->tip-taped topic) (fn [?tip-taped] (or ?tip-taped topic-event-taped))) ;; mayb add it via watch
    topic))

(defn add-event!
  ([partial-evt] (add-event! @as/*selected-my-aid-path @as/*selected-topic partial-evt))
  ([topic partial-evt] (add-event! @as/*selected-my-aid-path topic partial-evt))
  ([my-aid-path topic partial-evt]
   (swap! as/*topic->tip-taped update topic
          (fn [tip-taped]
            (let [new-tip       (cond-> (merge partial-evt
                                               {hg/creator       (@actrl/*my-aid-path+topic->init-key [my-aid-path topic])
                                                hg/creation-time (.now js/Date)})
                                  tip-taped (assoc hg/self-parent tip-taped))
                  novel-events  [new-tip]
                  new-tip-taped (hgt/tip+novel-events->tip-taped new-tip novel-events)]
              new-tip-taped)))))

(hg/reg-tx-handler! :set-topic-name
    (fn [db _ [_ topic-name]]
      (assoc db :topic-name topic-name)))

(defn set-topic-name-event! [topic topic-name]
  (add-event! topic {:event/tx [:set-topic-name topic-name]}))


#_
(defn event->creator->needs-consensus? [event]
  (let [creator->tip-tx-event (hg/event->creator->tip-tx-event event)
         creator->tip          (hgt/event->creator->tip event)

         creator-tip->creator->tip-tx-events-received
         (->> creator->tip
              (map (fn [[_creator creator-tip]]
                     [creator-tip (hg/event->creator->tip-tx-event-received creator-tip)]))
              (into {}))

         creator->needs-consensus?
         (->> creator-tip->creator->tip-tx-events-received
              (map (fn [[creator-tip creator->tip-tx-event-received]]
                     [(hg/creator creator-tip) (not= creator->tip-tx-event creator->tip-tx-event-received)]))
              (into {}))]
    creator->needs-consensus?))


;; ---- subjective ----
(defonce *subjective-tx-handlers (atom {}))
(defn reg-subjective-tx-handler! [subjective-tx-handler-id subjective-tx-handler]
  (l [:regged-subjective-tx-handler subjective-tx-handler-id])
  (swap! *subjective-tx-handlers assoc subjective-tx-handler-id subjective-tx-handler))

(defn apply-subjective-tx-handler [db {:event/keys [tx] :as event}]
  (l [:apply-subjective-tx-handler db event])
  (let [txes (hg/?tx-or-txes->?txes tx)]
    (->> txes
         (reduce (fn [db-acc [tx-handler-id :as tx]]
                   (l tx)
                   (let [?subjective-tx-handler (get (l @*subjective-tx-handlers) tx-handler-id)]
                     (cond-> db-acc
                       ?subjective-tx-handler (?subjective-tx-handler event tx))))
                 db))))

(defonce *post-subjective-db-handlers (atom []))
(defn apply-post-subjective-db-handlers [subjective-db novel-tip-taped]
  (l [:apply-post-subjective-db-handlers subjective-db novel-tip-taped])
  (reduce (fn [subjective-db-acc post-subjective-db-handler]
            (post-subjective-db-handler subjective-db-acc novel-tip-taped))
          subjective-db
          @*post-subjective-db-handlers))

(defn prev-subjective-db+novel-event->subjective-db
  [prev-subjective-db novel-event]
  (cond-> prev-subjective-db
    (:event/tx novel-event) (apply-subjective-tx-handler novel-event)))

(defn* ^:memoizing tip-taped->subjective-db [tip-taped]
  (letl2 [prev-subjective-db (or (some-> (hg/self-parent tip-taped) tip-taped->subjective-db)
                                 (hg/event->topic tip-taped))
          novel-events       (-> tip-taped meta :tip/novel-events)]
    (-> (reduce prev-subjective-db+novel-event->subjective-db
                prev-subjective-db
                novel-events)
        (apply-post-subjective-db-handlers tip-taped))))

(def *topic->subjective-db
  (lazy-derived-atom [as/*topic->tip-taped]
    (fn [topic->tip-taped]
      (l [::derive-topic->subjective-db topic->tip-taped])
      (l (->> topic->tip-taped
              (map (fn [[topic tip-taped]]
                     [topic (tip-taped->subjective-db tip-taped)]))
              (into {}))))))

(defn tip-taped->consensus-enabled? [tip-taped]
  (-> tip-taped
      tip-taped->subjective-db
      :consensus-enabled?))


;; ---- projected ----
;; would be better to project cr after cr, not one big mock one
(defn* ^:memoizing ->projected-cr
  [event]
  (letl2 [cr (hg/->concluded-round event)]
    (if (-> cr :concluded-round/es-r set (contains? event))
      cr
      (letl2 [projected-cr*       {:concluded-round/r                    (max (inc (hg/->round-number event cr))
                                                                              (inc (:concluded-round/r cr))) ;; will allow to pull events not known to cr
                                   :concluded-round/ws                   #{event}
                                   :concluded-round/prev-concluded-round cr}
              projected-etr->les  (hg/concluded-round->event-to-receive->learned-events projected-cr*)
              projected-es-r      (keys projected-etr->les) ;; will not take into an account weight of learned events, is not a sophisticated projection
              projected-cr*       (-> projected-cr*
                                      (assoc :concluded-round/etr->les projected-etr->les)
                                      (assoc :concluded-round/es-r     projected-es-r))
              ?projected-last-re  (hg/concluded-round->?received-event projected-cr*)
              db                  (hg/cr-db cr)
              projected-db        (if (nil? ?projected-last-re)
                                    db
                                    (hg/prev-db+received-event->db db ?projected-last-re))
              projected-stake-map (-> projected-db :stake-map)
              projected-cr        (-> projected-cr*
                                      (assoc :concluded-round/db projected-db)
                                      (assoc :concluded-round/stake-map projected-stake-map)
                                      (cond->
                                          ?projected-last-re (assoc :concluded-round/last-received-event ?projected-last-re)))]
        projected-cr))))

(defn ->projected-db [event]
  (-> event ->projected-cr l hg/cr-db))

(def *topic->projected-db
  (lazy-derived-atom [as/*topic->tip-taped]
    (fn [topic->tip-taped]
      (l [::derive-*topic->projected-db topic->tip-taped])
      (l (->> topic->tip-taped
              (filter (fn [[_topic tip-taped]] (l (tip-taped->consensus-enabled? (l tip-taped)))))
              (map (fn [[topic tip-taped]]
                     [topic (l (-> tip-taped ->projected-db))]))
              (into {}))))))
