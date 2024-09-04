(ns app.topic
  (:require
   [hashgraph.main :refer [Member Topic] :as hg]
   [hashgraph.topic :as hgt :refer [Tape TipTaped G$]]
   [hashgraph.utils.core :refer [hash=] :refer-macros [defn* l letl letl2] :as utils]

   [app.state :as as]

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
    (swap! as/*topics conj topic)
    (swap! (rum/cursor as/*topic->tip-taped topic) (fn [?tip-taped] (or ?tip-taped topic-event-taped))) ;; mayb add it via watch
    topic))

(defn add-event!
  ([partial-evt] (add-event! @as/*selected-topic partial-evt))
  ([topic partial-evt]
   (swap! as/*topic->tip-taped update topic
          (fn [tip-taped]
            (let [new-tip       (cond-> (merge partial-evt
                                               {hg/creator       @as/*my-did-peer
                                                hg/creation-time (.now js/Date)})
                                  tip-taped (assoc hg/self-parent tip-taped))
                  novel-events  [new-tip]
                  new-tip-taped (hgt/tip+novel-events->tip-taped new-tip novel-events)]
              new-tip-taped)))))


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
(def *subjective-tx-handlers (atom {}))
(defn reg-subjective-tx-handler! [subjective-tx-handler-id subjective-tx-handler]
  (swap! *subjective-tx-handlers assoc subjective-tx-handler-id subjective-tx-handler))

(defn apply-subjective-tx-handler [db {:event/keys [tx] :as evt}]
  (if-let [subjective-tx-handler (get @*subjective-tx-handlers (first tx))]
    (subjective-tx-handler db evt tx)
    db))

(def *subjective-smartcontracts (atom []))
(defn apply-subjective-smartcontracts [subjective-db novel-event]
  (reduce (fn [subjective-db-acc subjective-smartcontract]
            (subjective-smartcontract subjective-db-acc novel-event))
          subjective-db
          @*subjective-smartcontracts))

(defn prev-subjective-db+novel-event->subjective-db
  [prev-subjective-db novel-event]
  (cond-> prev-subjective-db
    (:event/tx novel-event) (apply-subjective-tx-handler novel-event)
    :always                 (apply-subjective-smartcontracts novel-event)))

(defn ^:memoizing tip-taped->subjective-db [tip-taped]
  (let [prev-subjective-db (or (some-> (hg/self-parent tip-taped) tip-taped->subjective-db)
                               (hg/event->topic tip-taped))
        novel-events       (-> tip-taped meta :tip/novel-events)]
    (reduce prev-subjective-db+novel-event->subjective-db
            prev-subjective-db
            novel-events)))

(def *topic->subjective-db
  (rum/derived-atom [as/*topic->tip-taped] ::derive-tip-taped->subjective-db
    (fn [topic->tip-taped]
      (->> topic->tip-taped
           (map (fn [[topic tip-taped]]
                  [topic (tip-taped->subjective-db tip-taped)]))
           (into {})))))


(reg-subjective-tx-handler!
 :init-control
 (fn [db _ _]
   (assoc db :consensus-enabled? true)))

(defn tip-taped->consensus-enabled? [tip-taped]
  (-> tip-taped
      tip-taped->subjective-db
      :consensus-enabled?))

(defn tip-taped->creator->needs-consensus? [tip-taped]
  (if-not (tip-taped->consensus-enabled? tip-taped)
    (fn [_] false)
    ))

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
  (rum/derived-atom [as/*topic->tip-taped] ::derive-*topic->projected-db
    (fn [topic->tip-taped]
      (->> topic->tip-taped
           (filter (fn [[_topic tip-taped]] (tip-taped->consensus-enabled? tip-taped)))
           (map (fn [[topic tip-taped]]
                  [topic (-> tip-taped ->projected-db)]))
           (into {})))))
