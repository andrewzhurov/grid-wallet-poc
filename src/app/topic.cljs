(ns app.topic
  (:require
   [hashgraph.main :as hg]
   [hashgraph.topic :as hgt]
   [hashgraph.utils.lazy-derived-atom :refer [lazy-derived-atom] :refer-macros [deflda]]
   [hashgraph.utils.core :refer [hash= map-vals map-keys median not-neg] :refer-macros [defn* l letl letl2] :as utils]

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


(defn add-event [topic-path->tip-taped topic-path partial-evt]
  (let [?tip-taped           (get topic-path->tip-taped topic-path)
        member-init-keys-log (or (-> ?tip-taped hg/evt->db :member-init-keys-log)
                                 (-> partial-evt hg/topic :member-init-keys-log)
                                 (throw (ex-info "no member-init-keys-log on add-event!" {:topic-path topic-path :?tip-taped ?tip-taped :partial-evt partial-evt})))
        my-member-init-key   (or (actrl/topic-path->member-init-key topic-path)
                                 (throw (ex-info "no member-init-key for topic-path" {:topic-path topic-path :partial-evt partial-evt :?tip-taped ?tip-taped})))
        my-creator           (or (not-neg (-indexOf member-init-keys-log my-member-init-key))
                                 (throw (ex-info "my-member-init-key is not in member-init-keys-log on add-event!" {:topic-path topic-path :?tip-taped ?tip-taped :partial-evt partial-evt :member-init-keys-log member-init-keys-log :my-member-init-key my-member-init-key})))
        new-tip              (cond-> (merge partial-evt
                                            {hg/creator       my-creator
                                             hg/creation-time (.now js/Date)})
                               ?tip-taped (assoc hg/self-parent ?tip-taped))
        _                    (hash new-tip)
        novel-events         [new-tip]
        new-tip-taped        (hgt/tip+novel-events->tip-taped new-tip novel-events)]
    (assoc topic-path->tip-taped topic-path new-tip-taped)))

(defn add-event!
  ([partial-evt] (add-event! @as/*selected-topic-path partial-evt))
  ([topic-path partial-evt]
   (-> (swap! as/*topic-path->tip-taped add-event topic-path partial-evt)
       (get topic-path))))

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
  (l (let [txes (hg/?tx-or-txes->?txes tx)]
       (->> txes
            (reduce (fn [db-acc [tx-handler-id :as tx]]
                      (l tx)
                      (let [?subjective-tx-handler (get (l @*subjective-tx-handlers) tx-handler-id)]
                        (cond-> db-acc
                          ?subjective-tx-handler (?subjective-tx-handler event tx))))
                    db)))))

(defonce *post-subjective-db-handlers (atom []))
(defn apply-post-subjective-db-handlers [subjective-db novel-tip-taped]
  (l [:apply-post-subjective-db-handlers subjective-db novel-tip-taped])
  (l (reduce (fn [subjective-db-acc post-subjective-db-handler]
               (post-subjective-db-handler subjective-db-acc novel-tip-taped))
             subjective-db
             @*post-subjective-db-handlers)))

(defn subjective-receive-event
  [prev-subjective-db novel-event concluding-events#]
  (cond-> prev-subjective-db
    (:event/tx novel-event) (apply-subjective-tx-handler novel-event)

    (concluding-events# novel-event)
    (apply-post-subjective-db-handlers novel-event)))

(defn* ^:memoizing evt->root-path [evt]
  (if (hg/root-event? evt)
    [evt]
    (let [prev-root-path (or (some-> (hg/self-parent evt) evt->root-path)
                             (some-> (hg/other-parent evt) evt->root-path))]
      (conj prev-root-path evt))))

(defn tip->concluding-events [tip]
  (if (hg/self-parent tip)
    #{tip}
    (-> tip evt->root-path set)))

(defn* ^:memoizing tip-taped->subjective-db [tip-taped]
  (l [:tip-taped->subjective-db tip-taped])
  (l (let [prev-subjective-db (or (some-> (hg/self-parent tip-taped) tip-taped->subjective-db)
                                  (-> (hg/event->topic tip-taped)
                                      (assoc :my-creator (:event/creator tip-taped))))
           novel-events       (-> tip-taped meta :tip/novel-events)
           concluding-events# (-> tip-taped tip->concluding-events)]
       (reduce (fn [db-acc novel-event] (subjective-receive-event db-acc novel-event concluding-events#))
               prev-subjective-db
               novel-events))))

(deflda *topic-path->subjective-db [as/*topic-path->tip-taped]
  (map-vals tip-taped->subjective-db))

(defn tip-taped->consensus-enabled? [tip-taped]
  (-> tip-taped
      tip-taped->subjective-db
      :consensus-enabled?))


;; ---- projected ----
;; would be better to project cr after cr, not one big mock one

;; sorting by lamport in hg cr and here would mean same caches used => no pollution
;; or, to have it fast, can do cr+subjective atop, then atop is incremental
(defn* ^:memoizing event->projected-cr
  [event]
  (l [::event->projected-cr event])
  (letl2 [cr (hg/->concluded-round event)]
    (if (-> cr :concluded-round/es-r (contains? event))
      cr
      (letl2 [projected-cr-r (max (inc (hg/->round-number event cr))
                                  (inc (:concluded-round/r cr))) ;; will allow to pull events not known to cr
              projected-ws   #{event}
              projected-ufws projected-ws

              ;; projected-etr->ufw-les  (hg/concluded-round->event-to-receive->learned-events projected-cr*)
              ;; projected-es-r      (->> projected-etr->ufw-les keys set)

              ;; does not order sp events properly
              ;; projected-etr->projected-received-time (->> projected-etr->les (map-vals (fn [les] (->> les (map hg/creation-time) median))))
              ;; projected-es-r      (->> projected-etr->les (keys) (sort-by projected-etr->projected-received-time)) ;; will not take into an account weight of learned events, is not a sophisticated projection ;; also, plenty of compute (just use subjective ordering?)

              projected-creator->unique-tip-seq      (->> projected-ufws (map hg/event->creator->unique-tip))
              projected-received-creators            (apply set/intersection (map (comp set keys) projected-creator->unique-tip-seq))
              projected-creator->received-unique-tip (->> projected-creator->unique-tip-seq
                                                          (map #(select-keys % projected-received-creators))
                                                          (apply merge-with hg/min-sp))
              prev-creator->received-unique-tip      (:concluded-round/creator->received-unique-tip cr)
              projected-es-r                         (->> projected-creator->received-unique-tip
                                                          (mapcat (fn [[c received-unique-tip]]
                                                                    (->> received-unique-tip
                                                                         (iterate hg/self-parent)
                                                                         (take-while some?)
                                                                         (take-while (fn [sp-evt] (not (hash= sp-evt (prev-creator->received-unique-tip c)))))))))

              ;; forks do not affect that we did receive some prior non-forked event of that creator
              projected-creator->received-unique-tip (merge-with hg/max-sp
                                                                 prev-creator->received-unique-tip
                                                                 projected-creator->received-unique-tip)


              ?projected-last-received-event (hg/->?received-event cr projected-cr-r projected-es-r)
              db                             (hg/cr-db cr)
              projected-db                   (if (nil? ?projected-last-received-event)
                                               db
                                               (hg/prev-db+received-event->db db ?projected-last-received-event))
              projected-stake-map            (-> projected-db :stake-map)
              projected-cr                   (cond-> (hash-map :concluded-round/r                            projected-cr-r
                                                               :concluded-round/ws                           #{event}
                                                               :concluded-round/ufws                         #{event}
                                                               :concluded-round/es-r                         projected-es-r
                                                               :concluded-round/creator->received-unique-tip projected-creator->received-unique-tip
                                                               :concluded-round/db                           projected-db
                                                               :concluded-round/stake-map                    projected-stake-map
                                                               :concluded-round/prev-concluded-round         cr)
                                                     ?projected-last-received-event (assoc :concluded-round/last-received-event ?projected-last-received-event))]
        projected-cr))))

(defn event->projected-db [event]
  (-> event event->projected-cr hg/cr-db))

(deflda *topic-path->projected-cr [as/*topic-path->tip-taped] (map-vals event->projected-cr))
(deflda *topic-path->projected-db [*topic-path->projected-cr] (map-vals hg/cr->db))
(deflda *topic-path->projected-aid#->ke [*topic-path->projected-db]
  (map-vals (fn [{:keys [aids#-log aid$->ke]}]
              (->> aid$->ke (map-keys aids#-log)))))
