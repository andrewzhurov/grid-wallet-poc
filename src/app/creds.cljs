(ns app.creds
  (:require
   [hashgraph.main :as hg]
   [hashgraph.topic :as hgt]
   [hashgraph.members :as hg-members]
   [hashgraph.schemas :as hgs]
   [hashgraph.utils.core :refer [hash= not-neg mean conjs conjv map-vals] :refer-macros [defn* l letl letl2 when-let*] :as utils]
   [hashgraph.utils.lazy-derived-atom :refer [lazy-derived-atom]]

   [hashgraph.app.avatars :as hga-avatars]
   [hashgraph.app.events :as hga-events]
   [hashgraph.app.playback :as hga-playback]
   [hashgraph.app.state :as hga-state]
   [hashgraph.app.page :as hga-page]
   [hashgraph.app.view :as hga-view]
   [hashgraph.app.utils :as hga-utils]

   [app.styles :refer [reg-styles!] :as styles]
   [app.state :as as]
   [app.io :refer [reg< send-message]]
   [app.topic :as at]
   [app.control :as actrl]

   [rum.core :refer [defc defcs] :as rum]
   [clojure.set :as set]
   [clojure.test :refer [deftest testing is are run-tests run-test]]
   [clojure.pprint :refer [pprint]]
   [garden.selectors :as gs]
   [malli.core :as m]
   [malli.util :as mu]
   [malli.error :as me]
   [goog.object :as gobject]))

#_
(def AID string?)

#_
(def KeyEventType
  [:enum
   :inception
   :rotation
   :interaction])

(hgs/register!
 :lei string?
 :fraction [:and
            [:tuple pos-int? pos-int?]
            [:fn {:error/message "Fraction denominator must not be nil"}
             (fn [[_numerator denominator]] (not (zero? denominator)))]
            [:fn {:error/message "Fraction's nominator must not be more than denominator"}
             (fn [[numerator denominator]] (<= numerator denominator))]])

;; this is brittle, as we're in the floats land
(defn sum-fractions [fractions]
  (->> fractions (reduce (fn [acc [numerator denominator]] (+ acc (/ numerator denominator))) 0)))

(deftest sum-fractions-test
  (is (= 1 (sum-fractions [[1 2] [1 4] [1 4]])))
  (is (= 0 (sum-fractions []))))

(hgs/register!
 :threshold
 [:and
  [:vector [:or :fraction [:= 0]]]
  [:fn {:error/message "Threshold must sum up to >= 1 or be 0"}
   (fn [threshold]
     (let [total (sum-fractions threshold)]
       (or (>= total 1)
           (zero? total))))]])

(defn ->establishment-key-event? [ke]
  (#{:inception :rotation} (:key-event/type ke)))

(defn* ^:memoizing ->prior-establishment-key-event [ke]
  (->> ke
       (iterate :key-event/prior)
       (drop 1)
       (take-while some?)
       (some (fn [ke] (when (->establishment-key-event? ke) ke)))))

(defn* ^:memoizing ->latest-establishment-key-event [ke]
  (if (->establishment-key-event? ke)
    ke
    (->prior-establishment-key-event ke)))

(defn* ^:memoizing ke->ke-icp [ke]
  (or (some-> ke :key-event/prior ke->ke-icp)
      (when (= :inception (:key-event/type ke))
        ke)))

(defn ->unblinded-keys [prior-es-ke next-signing-keys]
  (->> next-signing-keys
       (filter (fn [next-signing-key] (not= -1 (-indexOf (:key-event/next-signing-keys prior-es-ke) (hash next-signing-key)))))))

(defn ->unblinded-keys-weight [{:key-event/keys [signing-keys] :as ke}]
  (let [prior-est-ke             (->prior-establishment-key-event ke)
        unblinded-keys           (->unblinded-keys prior-est-ke signing-keys)
        unblinded-keys-fractions (->> unblinded-keys
                                      (map (fn [unblinded-key]
                                             (let [prior-idx (-indexOf (:key-event/next-signing-keys prior-est-ke) (hash unblinded-key))]
                                               (nth (:key-event/next-threshold prior-est-ke) prior-idx)))))]
    (sum-fractions unblinded-keys-fractions)))

(defn unblinded? [ke signing-key]
  (and (contains? (set (:key-event/signing-keys ke)) signing-key)
       (let [prior-est-ke (->prior-establishment-key-event ke)]
         (not (empty? (->unblinded-keys prior-est-ke [signing-key]))))))

(defn is-next-of? [ke next-signing-key signing-key]
  (l [:is-next-of? ke next-signing-key signing-key])
  (let [latest-est-ke             (->latest-establishment-key-event ke)
        signing-key-idx           (-indexOf (:key-event/signing-keys latest-est-ke) signing-key)
        next-signing-key-hash-idx (-indexOf (:key-event/next-signing-keys latest-est-ke) (hash next-signing-key))]
    (and (not= signing-key-idx -1)
         (= signing-key-idx next-signing-key-hash-idx))))

(defn threshold-satisfied? [ke agreed-keys]
  (let [{:key-event/keys [signing-keys threshold]} (->latest-establishment-key-event ke)

        agreed-keys-fractions (->> agreed-keys
                                   (reduce (fn [fractions-acc agreed-key]
                                             (let [agreed-key-idx (-indexOf signing-keys agreed-key)]
                                               (cond-> fractions-acc
                                                 (not= -1 agreed-key-idx)
                                                 (conj (nth threshold agreed-key-idx)))))
                                           []))]
    (>= (sum-fractions agreed-keys-fractions) 1)))

(defn next-threshold-satisfied? [ke agreed-keys]
  (let [{:key-event/keys [next-signing-keys next-threshold]} (->latest-establishment-key-event ke)

        agreed-keys-fractions (->> agreed-keys
                                   (reduce (fn [fractions-acc agreed-key]
                                             (let [agreed-key-idx (-indexOf next-signing-keys (hash agreed-key))]
                                               (cond-> fractions-acc
                                                 (not= -1 agreed-key-idx)
                                                 (conj (nth next-threshold agreed-key-idx)))))
                                           []))]
    (>= (sum-fractions agreed-keys-fractions) 1)))

(def signing-keys-threshold-check
  [:fn {:error/message "signing-keys count must equal to threshold count"}
   (fn [{:key-event/keys [signing-keys threshold]}] (= (count signing-keys) (count threshold)))])

(def next-signing-keys-next-threshold-check
  [:fn {:error/message "next-signing-keys count must equal to next-threshold count"}
   (fn [{:key-event/keys [next-signing-keys next-threshold]}] (= (count next-signing-keys) (count next-threshold)))])

(defn said-check [field]
  [:fn {:error/message "SAID is incorrect"}
   (fn [{:key-event/keys [said] :as ke}]
     (= said (hash (dissoc ke field))))])

(hgs/register!
 :anchors [:vector any?]

 ::key-event
 [:multi {:dispatch :key-event/type}
  [:inception [:ref ::key-event-inception]]
  [:rotation [:ref ::key-event-rotation]]
  [:interaction [:ref ::key-event-interaction]]]
 :key-event ::key-event
 :ke :key-event

 ::key-event-inception
 [:and
  [:map {:closed true}
   [:key-event/type [:= :inception]]
   [:key-event/signing-keys [:vector :public-key]]
   [:key-event/threshold :threshold]
   [:key-event/next-signing-keys [:vector :public-key-hash]]
   [:key-event/next-threshold :threshold]
   [:key-event/anchors :anchors];; add anchors
   #_[:key-event/said Hash]]
  #_(said-check :key-event/said)
  signing-keys-threshold-check
  next-signing-keys-next-threshold-check]
 :key-event-inception ::key-event-inception
 :ke-icp :key-event-inception
 :aid :key-event-inception

 ::key-event-rotation
 [:and
  [:map {:closed true}
   [:key-event/type [:= :rotation]]
   [:key-event/signing-keys [:vector :public-key]]
   [:key-event/threshold :threshold]
   [:key-event/next-signing-keys [:vector :public-key-hash]]
   [:key-event/next-threshold :threshold]
   [:key-event/anchors :anchors]
   [:key-event/prior [:ref ::key-event]] ;; it's a SAID, actually
   #_[:key-event/said Hash]]
  #_(said-check :key-event/said)
  signing-keys-threshold-check
  next-signing-keys-next-threshold-check
  [:fn {:error/message "Rotation event's unblinded must be able to satisfy prev Establishment event's threshold"}
   (fn [ke] (>= (->unblinded-keys-weight ke) 1))]]
 :key-event-rotation ::key-event-rotation
 :ke-rot ::key-event-rotation

 ::key-event-interaction
 [:and
  [:map {:closed true}
   [:key-event/type [:= :interaction]]
   [:key-event/anchors [:and :anchors
                        [:fn "interaction anchors must be non-zero"
                         (fn [{:key-event/keys [anchors]}] (not (zero? (count anchors))))]]]
   [:key-event/prior [:ref ::key-event]]
   #_[:key-event/said Hash]]
  #_(said-check :key-event/said)]
 :key-event-interaction ::key-event-interaction
 :ke-ixn ::key-event-interaction)

(hgs/register!
 ::tx-event
 [:multi {:dispatch :key-event/type}
  [:inception [:ref ::tx-event-inception]]
  [:update    [:ref ::tx-event-update]]]
 :tx-event ::tx-event

 ::tx-event-inception
 [:map {:closed true}
  [:tx-event/type [:= :inception]]
  [:tx-event/issuer [:ref ::key-event-inception]]]
 :tx-event-inception ::tx-event-inception

 ::tx-event-update
 [:map {:closed true}
  [:tx-event/type [:= :update]]
  [:tx-event/attribute
   [:map {:closed true}
    [:ts [:enum :placeholder :issued :revoked]]]]
  [:tx-event/prior [:ref ::tx-event]]])
:tx-event-update ::tx-event-update

(hgs/register!
 ::acdc-edge-group
 [:map-of
  keyword? [:or
            [:ref ::acdc]]]

 ::acdc
 [:map {:closed true}
  [:acdc/issuer [:ref ::key-event-inception]]
  [:acdc/schema keyword?]
  [:acdc/attribute {:optional true} map?]
  [:acdc/uuid {:optional true} uuid?]
  [:acdc/registry {:optional true} [:ref ::tx-event-inception]]
  [:acdc/edge-group {:optional true} [:ref ::acdc-edge-group]]]
 :acdc ::acdc

 ::acdc-qvi
 [:merge
  [:ref :acdc]
  [:map {:closed true}
   [:acdc/schema [:= :acdc-qvi]]
   [:acdc/registry [:ref ::tx-event-inception]]
   [:acdc/attribute
    [:map {:closed true}
     [:issuee [:ref ::key-event-inception]]
     [:lei :lei]]]]] ;; is it really needed for a QVI cred?
 :acdc-qvi ::acdc-qvi

 ::acdc-le
 [:merge
  [:ref ::acdc]
  [:map {:closed true}
   [:acdc/schema [:= :acdc-le]]
   [:acdc/registry [:ref ::tx-event-inception]]
   [:acdc/attribute
    [:map {:closed true}
     [:issuee [:ref ::key-event-inception]]
     [:lei :lei]]]
   [:acdc/edge-group
    [:map {:closed true}
     [:qvi [:ref ::acdc-qvi]]]]]])




(hg/reg-tx-handler! :init-control
    (fn [{:keys [stake-map original-key->aid] :as db} {:event/keys [creator] :as evt} [_ signing-key next-signing-key-hash]]
      (cond-> db
        ((set (keys stake-map)) creator)
        ;; direct mode assumed / no consensus-only members, where consensus keys = controlling keys
        (-> (assoc-in [:signing-key->next-signing-key-hash signing-key] next-signing-key-hash)
            (assoc-in [:init-key->known-control creator] {:known-control/keys          [signing-key]
                                                          :known-control/next-key-hash next-signing-key-hash})))))

(defn db+signing-key->init-key [db signing-key]
  (or (->> db :init-key->known-control
           (some (fn [[init-key known-control]]
                   (when (not-neg (-indexOf known-control signing-key))
                     init-key))))
      (throw (ex-info "did not find init key for signing key" {:signing-key signing-key :db db}))))

(defn db+signing-key->aid [db signing-key]
  (let [init-key (db+signing-key->init-key db signing-key)]
    (or (get-in db [:init-key->aid init-key]) (throw (ex-info "did not find aid for init-key" {:init-key init-key :db db})))))

(defn db+signing-key->did-peer [db signing-key]
  (let [init-key (db+signing-key->init-key db signing-key)]
    (or (get-in db [:init-key->did-peer init-key]) (throw (ex-info "did not find did-peer for init-key" {:init-key init-key :db db})))))

(defn ->controlling-aid-hierarchy [db signing-keys]
  (->> signing-keys
       (mapv (fn [signing-key-or-vec]
               (if (vector? signing-key-or-vec)
                 (->controlling-aid-hierarchy db signing-key-or-vec)
                 (db+signing-key->aid db signing-key-or-vec))))))

(defn ->signing-key->did-peer [db signing-keys]
  (->> signing-keys
       (map (fn [signing-key]
              [signing-key (db+signing-key->did-peer db signing-key)]))
       (into {})))

(defn with-controlling-aid-hierarchy [db ke]
  (let [controlling-aid-hierarchy (->controlling-aid-hierarchy db (:key-event/signing-keys ke))]
    (update ke :key-event/anchors (fn [?anchors] (conj (or ?anchors []) {:aid/controlling-aid-hierarchy controlling-aid-hierarchy})))))

(defn with-signing-key->did-peer [db ke]
  (let [signing-key->did-peer (->signing-key->did-peer db (:key-event/signing-keys ke))]
    (update ke :key-event/anchors (fn [?anchors] (conj (or ?anchors []) {:aid/signing-key->did-peer signing-key->did-peer})))))

(defn sc-accept-init-control [{:keys [stake-map signing-key->next-signing-key-hash topic-name member-aid->did-peers] :as db} {:received-event/keys [received-time] :as re}]
  (l re)
  (l [:sc-accept-init-control db])
  (cond-> db
    (and (nil? (:ke db)) (= (count stake-map) (count signing-key->next-signing-key-hash)))
    (-> (assoc :ke (let [present-next-signing-key-hashes (-> signing-key->next-signing-key-hash
                                                             vals
                                                             (->> (filter some?))
                                                             vec)]
                     (with-signing-key->did-peer db
                       (with-controlling-aid-hierarchy db
                         {:key-event/type              :inception
                          :key-event/signing-keys      (vec (keys signing-key->next-signing-key-hash))
                          :key-event/threshold         (hg/->equal-threshold (count signing-key->next-signing-key-hash)) ;; TODO order threshold by aids' :aid/creation-time
                          :key-event/next-signing-keys present-next-signing-key-hashes
                          :key-event/next-threshold    (hg/->equal-threshold (count present-next-signing-key-hashes))
                          :key-event/anchors           [{:aid/name                  (or topic-name (throw (ex-info "cannot sc-accept-init-control on a topic without topic-name" {:db db :re re})))
                                                         :aid/creation-time         received-time
                                                         :aid/signing-key->did-peer {}}]})))))))


(hg/reg-tx-handler! :rotate
  (fn [{:keys [ke] :as db}
       {:event/keys [creator] :as evt}
       [_ next-signing-key next-next-signing-key-hash :as rotate-tx]]
    (cond-> db
      (and (some? ke)
           (or (is-next-of? ke next-signing-key (-> db :init-key->known-control l (get creator) l :known-control/keys last)) ;; this will be just `creator` were we use actual keys as it
               (throw (ex-info "invalid :rotate tx atop ke:" {:event evt :ke ke}))))
      (assoc-in [:next-signing-key->next-next-signing-key-hash next-signing-key] next-next-signing-key-hash))))

(defn init-key->known-control+key->init-key [init-key->known-control key]
  (->> init-key->known-control
       (some (fn [[init-key {:known-control/keys [keys next-key-hash] :as known-control}]]
               (when (or (= (hash key) next-key-hash)
                         (contains? (set keys) key))
                 init-key)))))

(defn sc-accept-rotate [{:keys [next-signing-key->next-next-signing-key-hash ke] :as db}]
  (let [next-signing-keys (vec (keys next-signing-key->next-next-signing-key-hash))]
    (cond-> db
      (next-threshold-satisfied? ke next-signing-keys)
      (-> (assoc :ke (let [latest-est-ke (->latest-establishment-key-event ke)]
                       (with-signing-key->did-peer db
                         (with-controlling-aid-hierarchy db
                           {:key-event/type              :rotation
                            :key-event/signing-keys      next-signing-keys
                            :key-event/threshold         (:key-event/next-threshold latest-est-ke)
                            :key-event/next-signing-keys (vec (vals next-signing-key->next-next-signing-key-hash))
                            :key-event/next-threshold    (:key-event/next-threshold latest-est-ke)
                            :key-event/anchors           []
                            :key-event/prior             ke}))))
          (update :init-key->known-control
                  (fn [init-key->known-control]
                    (->> next-signing-key->next-next-signing-key-hash
                         (reduce (fn [init-key->known-control-acc [next-signing-key next-next-signing-key-hash]]
                                   (let [init-key (or (init-key->known-control+key->init-key init-key->known-control-acc next-signing-key)
                                                      (throw (ex-info "did not find init-key for next-signing-key" {:init-key->known-control-acc init-key->known-control-acc :key next-signing-key})))]
                                     (update init-key->known-control-acc init-key
                                             (fn [known-control]
                                               (-> known-control
                                                   (update :known-control/keys conj next-signing-key)
                                                   (assoc :known-control/next-key-hash next-next-signing-key-hash))))))
                                 init-key->known-control))))
          (dissoc :next-signing-key->next-next-signing-key-hash)))))


(hg/reg-tx-handler! :propose
  (fn [db {:event/keys [creator]} tx]
    (assoc-in db [:proposal->agreed-keys tx] #{creator})))

(hg/reg-tx-handler! :agree
  (fn [db {:event/keys [creator]} [_ proposal]]
    (update-in db [:proposal->agreed-keys proposal] conj creator)))

(defn sc-anchor-accepted-proposals [{:keys [proposal->agreed-keys signing-key->next-signing-key-hash threshold ke] :as db}]
  (if (nil? ke)
    db
    (let [acceptable-proposals (->> proposal->agreed-keys
                                    (remove (fn [[proposal]] (contains? (:accepted-proposals db) proposal)))
                                    (filter (fn [[[_ proposal-kind] agreed-keys]]
                                              (let [mock-agreed-keys
                                                    (->> agreed-keys
                                                         (map (fn [mock-agreed-key]
                                                                (-> db
                                                                    (get-in [:init-key->known-control mock-agreed-key :known-control/keys])
                                                                    last)))
                                                         (into #{}))]
                                                (and (= :issuee proposal-kind)
                                                     (threshold-satisfied? ke mock-agreed-keys)))))
                                    (map first))
          acceptable-acdcs* (->> acceptable-proposals
                                 (map (fn [[_ _ & acdcs*]] acdcs*))
                                 (reduce into []))
          issuer-aid (ke->ke-icp ke)
          ?acdc->te (when (not-empty acceptable-acdcs*)
                      (->> acceptable-acdcs*
                           (map (fn [acceptable-acdc*]
                                  (let [te1  {:tx-event/type   :inception
                                              :tx-event/issuer issuer-aid}
                                        acdc (assoc acceptable-acdc*
                                                    :acdc/registry te1)
                                        te2  {:tx-event/type      :update
                                              :tx-event/attribute {:ts :issued}
                                              :tx-event/prior     te1}]
                                    [acdc te2])))
                           (into {})))]
      (cond-> db
        (not-empty acceptable-proposals)
        (update :accepted-proposals (fn [?accepted-proposals] (into (or ?accepted-proposals #{}) acceptable-proposals)))

        (some? ?acdc->te)
        (-> (assoc :ke {:key-event/type    :interaction
                        :key-event/anchors (vals ?acdc->te)
                        :key-event/prior   ke})
            (update :acdcs (fn [?prior-acdcs] (set/union (or ?prior-acdcs #{}) (set (keys ?acdc->te))))))))))

(l :regging-smartcontracts)
(reset! hg/*smartcontracts [sc-accept-init-control sc-anchor-accepted-proposals sc-accept-rotate])


(defn sole-controller? [db]
  (= 1 (count (:signing-key->next-signing-key db))))

(defn ke->init-key->known-control-keys [ke]
  )

(defn ke->active-init-keys [ke]
  )

(defn ke->init-key->did-peer [ke]
  )

(defn ke->interactable-init-keys [ke]
  )

(defn member-kes->equal-stake-map [member-kes]
  )

(let [ke1  {:key-event/type              :inception
            :key-event/signing-keys      ["a0"]
            :key-event/next-signing-keys [(hash "a1")]
            :key-event/anchors           [{:aid/init-key->did-peer {"a0" "a0-did-peer"}}]}
      ke2  {:key-event/type              :rotation
            :key-event/signing-keys      ["a1" "b0"] ;; adding member
            :key-event/next-signing-keys [(hash "a2") (hash "b1")]
            :key-event/anchors           [{:aid/init-key->did-peer {"b0" "b0-did-peer"}}]
            :key-event/prior             ke1}
      ke3  {:key-event/type              :rotation
            :key-event/signing-keys      ["c0" "b1" "a2"]  ;; adding member, out-of-order
            :key-event/next-signing-keys [(hash "b2") (hash "a3") (hash "c1")] ;; next keys out-of-order
            :key-event/anchors           [{:aid/init-key->did-peer {"c0" "c0-did-peer"}}]
            :key-event/prior             ke2}
      ke4  {:key-event/type              :rotation
            :key-event/signing-keys      ["b2" "c1"] ;; removing member, out-of-order
            :key-event/next-signing-keys [(hash "c2") (hash "b3")] ;; out-of-order
            :key-event/anchors           []
            :key-event/prior             ke3}
      ke5  {:key-event/type              :rotation
            :key-event/signing-keys      ["d0" "b3"] ;; removing member & adding member, out-of-order
            :key-event/next-signing-keys [(hash "b4") (hash "d1")] ;; out-of-order
            :key-event/anchors           [{:aid/init-key->did-peer {"d0" "d0-did-peer"}}]
            :key-event/prior             ke4}
      ke6  {:key-event/type              :rotation
            :key-event/signing-keys      ["b4" "d1"]
            :key-event/next-signing-keys [(hash "d2") (hash "b5")] ;; out-of-order
            :key-event/prior             ke5}
      ke7  {:key-event/type    :interaction
            :key-event/anchors [{:aid/init-key->did-peer {"b0" "b0-did-peer2"}}]
            :key-event/prior   ke6}
      ke8  {:key-event/type              :rotation
            :key-event/signing-keys      ["b5" "e0" "d2"] ;; say e is a reserved key without did-peer
            :key-event/next-signing-keys [(hash "e1") (hash "b6") (hash "d3")] ;; out-of-order
            :key-event/prior             ke7}
      ke9  {:key-event/type              :rotation
            :key-event/signing-keys      ["b5" "e0" "d3"] ;; carrying over some keys without unblinding
            :key-event/next-signing-keys [(hash "b6") (hash "e1") (hash "d4")]
            :key-event/prior             ke8}
      ke10 {:key-event/type              :rotation
            :key-event/signing-keys      ["a3" "b6" "d4" "e0"] ;; adding back a
            :key-event/next-signing-keys [(hash "b7") (hash "a4") (hash "d5") (hash "e1")]
            :key-event/prior             ke9}
      ]
  ;; TODO mailbox removal

  (deftest ke->init-key->known-control-keys-test
    (are [ke res] (= res (ke->init-key->known-control-keys ke))
      ke1  {"a0" ["a0"]}
      ke2  {"a0" ["a0" "a1"]
            "b0" ["b0"]}
      ke3  {"a0" ["a0" "a1" "a2"]
            "b0" ["b0" "b1"]
            "c0" ["c0"]}
      ke4  {"a0" ["a0" "a1" "a2"]
            "b0" ["b0" "b1" "b2"]
            "c0" ["c0" "c1"]}
      ke5  {"a0" ["a0" "a1" "a2"]
            "b0" ["b0" "b1" "b2" "b3"]
            "c0" ["c0" "c1"]
            "d0" ["d0"]}
      ke6  {"a0" ["a0" "a1" "a2"]
            "b0" ["b0" "b1" "b2" "b3" "b4"]
            "c0" ["c0" "c1"]
            "d0" ["d0" "d1"]}
      ke7  {"a0" ["a0" "a1" "a2"]
            "b0" ["b0" "b1" "b2" "b3" "b4"]
            "c0" ["c0" "c1"]
            "d0" ["d0" "d1"]}
      ke8  {"a0" ["a0" "a1" "a2"]
            "b0" ["b0" "b1" "b2" "b3" "b4" "b5"]
            "c0" ["c0" "c1"]
            "d0" ["d0" "d1" "d2"]
            "e0" ["e0"]}
      ke9  {"a0" ["a0" "a1" "a2" "a3"]
            "b0" ["b0" "b1" "b2" "b3" "b4" "b5"]
            "c0" ["c0" "c1"]
            "d0" ["d0" "d1" "d2" "d3"]
            "e0" ["e0"]}
      ke10 {"a0" ["a0" "a1" "a2" "a3"]
            "b0" ["b0" "b1" "b2" "b3" "b4" "b5" "b6"]
            "c0" ["c0" "c1"]
            "d0" ["d0" "d1" "d2" "d3" "d4"]
            "e0" ["e0"]}))

  (deftest ke->active-init-keys-test
    (are [ke res] (= res (ke->active-init-keys ke))
      ke1  #{"a0"}
      ke2  #{"a0" "b0"}
      ke3  #{"a0" "b0" "c0"}
      ke4  #{"b0" "c0"}
      ke5  #{"b0" "d0"}
      ke6  #{"b0" "d0"}
      ke7  #{"b0" "d0"}
      ke8  #{"b0" "d0" "e0"}
      ke9  #{"b0" "d0" "e0"}
      ke10 #{"a0" "b0" "d0" "e0"}))

  (deftest ke->init-key->did-peer-test
    (are [ke res] (= res (ke->init-key->did-peer ke))
      ke1  {"a0" "a0-did-peer"}
      ke2  {"a0" "a0-did-peer"
            "b0" "b0-did-peer"} ;; <-
      ke3  {"a0" "a0-did-peer"
            "b0" "b0-did-peer"
            "c0" "c0-did-peer"} ;; <-
      ke4  {"a0" "a0-did-peer"
            "b0" "b0-did-peer"
            "c0" "c0-did-peer"}
      ke5  {"a0" "a0-did-peer"
            "b0" "b0-did-peer"
            "c0" "c0-did-peer"
            "d0" "d0-did-peer"} ;; <-
      ke6  {"a0" "a0-did-peer"
            "b0" "b0-did-peer"
            "c0" "c0-did-peer"
            "d0" "d0-did-peer"}
      ke7  {"a0" "a0-did-peer"
            "b0" "b0-did-peer2" ;; <-
            "c0" "c0-did-peer"
            "d0" "d0-did-peer"}
      ke8  {"a0" "a0-did-peer"
            "b0" "b0-did-peer2"
            "c0" "c0-did-peer"
            "d0" "d0-did-peer"} ;; no e0-did-peer, not set, as its a reserved key
      ke9  {"a0" "a0-did-peer"
            "b0" "b0-did-peer2"
            "c0" "c0-did-peer"
            "d0" "d0-did-peer"}
      ke10 {"a0" "a0-did-peer"
            "b0" "b0-did-peer2"
            "c0" "c0-did-peer"
            "d0" "d0-did-peer"}))

  (deftest ke->interactable-init-keys-test
    (are [ke res] (= res (ke->interactable-init-keys ke))
      ke1  #{"a0"}
      ke2  #{"a0" "b0"}
      ke3  #{"a0" "b0" "c0"}
      ke4  #{"b0" "c0"}
      ke5  #{"b0" "d0"}
      ke6  #{"b0" "d0"}
      ke7  #{"b0" "d0"}
      ke8  #{"b0" "d0"} ;; no e0, as it does not have a mailbox
      ke9  #{"b0" "d0"}
      ke10 #{"a0" "b0" "d0"}))

  (deftest member-kes->equal-stake-map-test
    (are [kes res] (= res (member-kes->equal-stake-map kes))
      ke6 {})))


(defn ->topic-config [member-aid->ke]
  (let [member-aid->member-interactable-init-keys (->> member-aid->ke
                                                       (map-vals ke->interactable-init-keys))
        _                                         (->> member-aid->member-interactable-init-keys (some (fn [[member-aid member-interactable-init-key]]
                                                                                                         (when (empty? member-interactable-init-key)
                                                                                                           ;; though may be ok if that AID is a reserved/recovery aid?
                                                                                                           (throw (ex-info "no interactable-init-keys are found for member-aid, you should have not been able to reach here" {:member-aid member-aid :member-aid->ke member-aid->ke}))))))
        member-interactable-init-key->did-peer    (->> member-aid->member-interactable-init-keys
                                                       (map (fn [[member-aid member-init-keys]]
                                                              (-> member-aid
                                                                  (member-aid->ke)
                                                                  (ke->init-key->did-peer)
                                                                  (select-keys member-init-keys))))
                                                       (reduce into {}))

        ;; TODO give stake only to those member-aids that have active-init-keys with mailboxes
        member-aid->stake (->> member-aid->ke
                               (map-vals (fn [_]
                                           (/ hg/total-stake (count member-aid->ke)))))

        ;; TODO make deeply hierarchical stake
        member-interactable-init-key->stake (->> member-aid->member-interactable-init-keys
                                                 (mapcat (fn [[member-aid member-interactable-init-keys]]
                                                           (->> member-interactable-init-keys
                                                                (map (fn [member-init-key]
                                                                       [member-init-key (/ (member-aid->stake member-aid) (count member-interactable-init-keys))])))))
                                                 (into {}))]
    {:member-aid->ke                         member-aid->ke ;; needed to know who's automatically allowed in, will be updated
     :member-aid->stake                      member-aid->stake
     :member-interactable-init-key->did-peer member-interactable-init-key->did-peer
     :stake-map                              member-interactable-init-key->stake
     }))

(defn create-aided-topic-event [creator-interactable-init-key member-aid->ke & [init-db*]]
  (let [topic (->topic-config member-aid->ke)]
    {hg/creator       creator-interactable-init-key
     hg/creation-time (.now js/Date)
     hg/topic         (merge topic init-db*)}))

(defn create-aided-topic-event! [creator-interactable-init-key member-aid->ke & [init-db*]]
  (let [root-event (create-aided-topic-event creator-interactable-init-key member-aid->ke init-db*)]
    (swap! as/*topic->tip-taped assoc (hg/topic root-event) root-event)))


(declare max-ke)
(hg/reg-tx-handler! :inform-novel-ke
    (fn [db _ [_ ke]]
      (let [aid                (ke->ke-icp ke)
            old-member-aid->ke (:member-aid->ke db)
            new-member-aid->ke (update old-member-aid->ke aid max-ke ke)]
        (cond-> db
          (not= old-member-aid->ke new-member-aid->ke)
          ;; will override :member-aid->stake, tweak when stake can be altered by members
          (merge db (->topic-config new-member-aid->ke))))))



;;  G     Q     L
;; Icp   Icp   Icp
;;    \ /     |
;; Inx Q____  |
;;          \ /
;;       Inx L
;;
;; Rot


(defn ke->sad [ke]
  (assoc ke :key-event/said (hash ke)))

(def gleif-ke0
  {:key-event/type              :inception
   :key-event/signing-keys      ["gleif0"]
   :key-event/threshold         [[1 1]]
   :key-event/next-signing-keys [(hash "gleif1")]
   :key-event/next-threshold    [[1 1]]})

(def qvi-ke0
  {:key-event/type              :inception
   :key-event/signing-keys      ["qvi0"]
   :key-event/threshold         [[1 1]]
   :key-event/next-signing-keys [(hash "qvi1")]
   :key-event/next-threshold    [[1 1]]})

(def gleif-acdc-qvi-te0
  {:tx-event/type :inception
   :tx-event/issuer gleif-ke0})

(def gleif-acdc-qvi
  {:acdc/schema    :acdc-qvi
   :acdc/issuer    gleif-ke0
   :acdc/registry  gleif-acdc-qvi-te0
   :acdc/attribute {:issuee qvi-ke0
                    :lei   "<LEI of QVI>"}})

(def gleif-acdc-qvi-te1
  {:tx-event/type      :update
   :tx-event/attribute {:ts :issued}
   :tx-event/prior     gleif-acdc-qvi-te0})

(def gleif-ke1
  {:key-event/type    :interaction
   :key-event/anchors [gleif-acdc-qvi-te1]
   :key-event/prior   gleif-ke0})

(def gleif-ke2
  {:key-event/type              :rotation
   :key-event/signing-keys      ["gleif1"]
   :key-event/threshold         [[1 1]]
   :key-event/next-signing-keys [(hash "gleif2")]
   :key-event/next-threshold    [[1 1]]
   :key-event/prior             gleif-ke1})


(def le-ke0
  {:key-event/type              :inception
   :key-event/signing-keys      ["le0"]
   :key-event/threshold         [[1 1]]
   :key-event/next-signing-keys [(hash "le1")]
   :key-event/next-threshold    [[1 1]]})

(def qvi-acdc-le-te0
  {:tx-event/type   :inception
   :tx-event/issuer qvi-ke0})

(def qvi-acdc-le
  {:acdc/schema     :acdc-le
   :acdc/issuer     qvi-ke0
   :acdc/registry   qvi-acdc-le-te0
   :acdc/attribute  {:issuee le-ke0
                     :lei   "<LEI of LE>"}
   :acdc/edge-group {:qvi gleif-acdc-qvi}})

(def qvi-acdc-le-te1
  {:tx-event/type      :update
   :tx-event/attribute {:ts :issued}
   :tx-event/prior     qvi-acdc-le-te0})

(def qvi-ke1
  {:key-event/type    :interaction
   :key-event/anchors [qvi-acdc-le-te1]
   :key-event/prior   qvi-ke0})

(deftest unblinded?-test
  (is (not (unblinded? gleif-ke0 "gleif0")))
  (is (not (unblinded? gleif-ke1 "gleif0")))
  (is (not (unblinded? gleif-ke2 "gleif0")))
  (is (unblinded? gleif-ke2 "gleif1")))

(deftest is-next-of?-test
  (is (is-next-of? gleif-ke0 "gleif1" "gleif0"))
  (is (is-next-of? gleif-ke2 "gleif2" "gleif1"))
  (is (not (is-next-of? gleif-ke0 "gleif1" "gleif")))
  (is (not (is-next-of? gleif-ke0 "gleif2" "gleif0"))))

(deftest kel-test
  (doseq [ke [gleif-ke0 gleif-ke1 gleif-ke2
              qvi-ke0 qvi-ke1
              le-ke0]]
    (is (hgs/check :key-event ke))))

(deftest tel-test
  (doseq [te [gleif-acdc-qvi-te0 gleif-acdc-qvi-te1
              qvi-acdc-le-te0 qvi-acdc-le-te1]]
    (is (hgs/check :tx-event te))))

(deftest acdc-test
  (doseq [acdc [gleif-acdc-qvi
                qvi-acdc-le]]
    (is (hgs/check :acdc acdc))
    (is (hgs/check (:acdc/schema acdc) acdc))))


(actrl/init-control! [] :gleif)
(def gleif-k0 (actrl/my-aid-path+topic->k [] :gleif))
(def gleif-k1 (actrl/my-aid-path+topic->nk [] :gleif))
(def gleif-k2 (actrl/my-aid-path+topic->nnk [] :gleif))
(def gleif-e0
  (-> (hg/create-topic-event "gleif" #{"gleif"})
      (assoc :event/tx [:init-control gleif-k0 (hash gleif-k1)])))

(def gleif-e1
  {hg/creator       gleif-k0
   hg/creation-time (.now js/Date)
   hg/tx            [:propose :issuee gleif-acdc-qvi]
   hg/self-parent   gleif-e0})

(def gleif-e2
  {hg/creator       gleif-k0
   hg/creation-time (.now js/Date)
   hg/tx            [:rotate gleif-k1 (hash gleif-k2)]
   hg/self-parent   gleif-e1})

(defn evt->?ke [evt] (-> evt hg/evt->db l :ke))
(defn evt->?ke->k->signature [evt] (-> evt hg/evt->db :ke->k->signature))
(defn evt->?ke-icp [evt] (some-> evt evt->?ke ke->ke-icp))
(defn* ^:memoizing ke->?aid-name [ke]
  (or (some-> ke :key-event/prior ke->?aid-name)
      (some-> ke :key-event/anchors (->> (some (fn [anchor] (:aid/name anchor)))))))

(defn evt->init-key [{:event/keys [creator] :as evt}]
  (or (some-> evt
              hg/evt->db
              :init-key->known-control
              (init-key->known-control+key->init-key creator))
      creator))

(defn evt->active-init-keys [evt]
  (or (when-let [{:key-event/keys [signing-keys init-key->known-control]} (-> evt evt->?ke)]
        (->> signing-keys
             (map (fn [signing-key] (init-key->known-control+key->init-key
                                     init-key->known-control
                                     signing-key)))
             (filter some?)
             (not-empty)))
      (hgt/?event->creators evt)))

(defn evt->?active-my-did-peer [evt]
  (let [active-init-keys (-> evt evt->active-init-keys)]
    ((set (l active-init-keys)) (l @as/*my-did-peer))))

(deftest evt->?ke-test
  (is (= gleif-ke0 (evt->?ke gleif-e0)))
  #_(is (= {gleif-ke0 {"gleif0" "gleif0"}}
         (evt->ke->k->signature gleif-e0)))

  (is (= gleif-ke1 (evt->?ke gleif-e1)))
  (is (= #{gleif-acdc-qvi} (:acdcs (hg/evt->db gleif-e1))))
  #_(is (= {gleif-ke0 {"gleif0" "gleif0"}
          gleif-ke1 {"gleif0" "gleif0"}}
         (evt->ke->k->signature gleif-e1)))
  (is (= gleif-ke2 (evt->?ke gleif-e2)))

  #_(is (= {gleif-ke0 {"gleif0" "gleif0"}
          gleif-ke1 {"gleif0" "gleif0"}
          gleif-ke2 {"gleif1" "gleif1"}}
         (evt->ke->k->signature gleif-e2)))
  #_(is (= {:control/idx                   1
          :control/signing-key           "gleif1"
          :control/next-signing-key      "gleif2"
            :control/next-next-signing-key "gleif3"})))

(deftest evt->init-key-test
  (is (= "gleif" (evt->init-key gleif-e0)))
  (is (= "gleif" (evt->init-key gleif-e1)))
  (is (= "gleif" (evt->init-key gleif-e2))))


;; (run-tests)

(declare *my-aid->my-aid-topic)
(declare *my-aid-path->init-key)
(defn add-event!
  ([partial-evt] (add-event! @as/*selected-topic partial-evt))
  ([topic partial-evt] (add-event! @as/*selected-my-aid-path topic partial-evt))
  ([my-aid-path ?topic partial-evt] ;; we may not have participating-topic selected, as in just select my-aid
   (let [topic    (or ?topic (-> my-aid-path last (@*my-aid->my-aid-topic)))
         init-key (@*my-aid-path->init-key my-aid-path)]
     (swap! as/*topic->tip-taped update topic
            (fn [tip-taped]
              (let [new-tip       (cond-> (merge partial-evt
                                                 {hg/creator       init-key
                                                  hg/creation-time (.now js/Date)})
                                    tip-taped (assoc hg/self-parent tip-taped))
                    novel-events  [new-tip]
                    new-tip-taped (hgt/tip+novel-events->tip-taped new-tip novel-events)]
                new-tip-taped))))))

(defn add-init-control-event!
  ([topic] (add-init-control-event! @as/*selected-my-aid-path topic))
  ([my-aid-path topic]
   (actrl/init-control! my-aid-path topic)
   (let [k  (actrl/my-aid-path+topic->k my-aid-path topic)
         nk (actrl/my-aid-path+topic->nk my-aid-path topic)]
     (at/add-event! my-aid-path topic {:event/tx [:init-control k (hash nk)]}))))

(defn incepted? [event]
  (-> event evt->?ke some?))

(defn* ^:memoizing init-control-initiated? [event]
  (boolean (or (some-> (:event/tx event) first (= :init-control))
               (some-> (hg/self-parent event) init-control-initiated?)
               (some-> (hg/other-parent event) init-control-initiated?))))

(defn* ^:memoizing init-control-participated? [event]
  (boolean (or (some-> (:event/tx event) first (= :init-control))
               (some-> (hg/self-parent event) init-control-participated?))))

#_
(add-watch as/*my-did-peer ::create-topic-for-my-did-peer
           (fn [_ _ _ my-did-peer]
             (when my-did-peer
               (l [:creating-topic-for-my-did-peer my-did-peer])
               (let [topic (at/create-topic! my-did-peer #{my-did-peer})]
                 #_(add-init-control-event! topic)))))

(add-watch as/*topic->tip-taped ::participate-in-init-control
           (fn [_ _ _ new]
             (l [::participate-in-init-control new])
             (doseq [[topic tip-taped] new]
               (when (and (l (not (incepted? tip-taped)))
                          (l (init-control-initiated? tip-taped))
                          (l (not (init-control-participated? tip-taped))))
                 (add-init-control-event! topic)))))

;; redundant (?)
(add-watch as/*topic->tip-taped ::derive-my-aids
           (fn [_ _ _ topic->tip-taped]
             (l [::derive-my-aids topic->tip-taped])
             (let [my-aid-topics @as/*my-aid-topics]
               (when-let [novel-incepted-topics (->> topic->tip-taped
                                                     (filter (fn [[topic tip-taped]]
                                                               (and (l (incepted? tip-taped))
                                                                    (= -1 (-indexOf my-aid-topics topic)))))
                                                     (map first)
                                                     not-empty)]
                 (reset! as/*my-aid-topics (vec (into my-aid-topics novel-incepted-topics)))
                 (reset! as/*selected-my-aid-topic (last novel-incepted-topics))
                 (reset! as/*selected-topic (last novel-incepted-topics))
                 #_
                 (doseq [novel-incepted-topic novel-incepted-topics]
                   (when (= @as/*my-did-peer (first (:topic-members novel-incepted-topic)))
                     (at/add-event! novel-incepted-topic {:event/tx [:reg-did-peers]})))))))

#_
(hg/reg-tx-handler! :reg-did-peers
    (fn [{:keys [init-key->known-control ke] :as db} _ _]
      (assoc-in db [:member-aid->did-peers (ke->ke-icp ke)] (set (keys init-key->known-control)))))

(hg/reg-tx-handler! :assoc-did-peer
    (fn [db {:event/keys [creator]} [_ did-peer]]
      (assoc-in db [:init-key->did-peer creator] did-peer)))

(defn rotate-key!
  ([] (rotate-key! @as/*selected-my-aid-path))
  ([my-aid-path]
   (let [my-aid-topic (@*my-aid->my-aid-topic (last my-aid-path))
         my-aid-path* (vec (butlast my-aid-path))
         nk           (actrl/my-aid-path+topic->nk my-aid-path* my-aid-topic)
         nnk          (actrl/my-aid-path+topic->nnk my-aid-path* my-aid-topic)]
     (at/add-event! my-aid-path* my-aid-topic {:event/creator (actrl/my-aid-path+topic->init-key my-aid-path* my-aid-topic)
                                               :event/tx      [:rotate nk (hash nnk)]}))))

(defn issue-acdc-qvi! [issuer-aid-topic issuer-aid issuee-aid]
  (let [acdc-qvi* {:acdc/schema    ::acdc-qvi
                   :acdc/issuer    issuer-aid
                   :acdc/attribute {:issuee issuee-aid
                                    :lei    "<LEI of QVI>"}}]
    (at/add-event! issuer-aid-topic {:event/tx [:propose :issuee acdc-qvi*]})))

(defn issue-acdc-le! [issuer-aid-topic issuer-aid issuee-aid issuer-aid-attributed-acdc-qvi]
  (let [acdc-le* {:acdc/schema     ::acdc-le
                  :acdc/issuer     issuer-aid
                  :acdc/attribute  {:issuee issuee-aid
                                    :lei    "<LEI of LE>"}
                  :acdc/edge-group {:qvi issuer-aid-attributed-acdc-qvi}}]
    (at/add-event! issuer-aid-topic {:event/tx [:propose :issuee acdc-le*]})))


(defn disclose-acdc-to-issuee! [acdc]
  (when-let* [issuer (l (-> (l acdc) :acdc/issuer))
              issuee (l (-> acdc :acdc/attribute :issuee))
              issuer+issuee-topic (l (->> (l @as/*other-topics)
                                          (some (fn [{:keys [member-aid->did-peers] :as topic}]
                                                  (l topic)
                                                  (let [member-aids (set (keys member-aid->did-peers))]
                                                    (when (l (and (contains? (l member-aids) issuee)
                                                                  (contains? member-aids issuer)))
                                                      topic))))))]
    (at/add-event! issuer+issuee-topic {:event/tx [:disclose-acdc acdc]})))

(defn topic+acdc->i-proposed? [topic acdc]
  (-> topic
      (@as/*topic->tip-taped)
      hg/evt->db))

(add-watch as/*my-aid-topics ::reg-acdc-disclosers
           (fn [_ _ _ my-aid-topics]
             (doseq [my-aid-topic my-aid-topics]
               (add-watch (rum/cursor as/*topic->tip-taped my-aid-topic) ::acdc-discloser
                          (fn [_ _ ?old-my-aid-topic-tip-taped new-my-aid-topic-tip-taped]
                            (l [::acdc-discloser ?old-my-aid-topic-tip-taped new-my-aid-topic-tip-taped])
                            (let [?old-acdcs (some-> ?old-my-aid-topic-tip-taped
                                                     hg/evt->db
                                                     :acdcs)
                                  new-acdcs (-> new-my-aid-topic-tip-taped
                                                hg/evt->db
                                                :acdcs)]
                              (when-let [novel-acdcs (not-empty (set/difference new-acdcs ?old-acdcs))]
                                (doseq [novel-acdc novel-acdcs]
                                  ;; hacky way to select 1 device as discloser
                                  (when (-> new-my-aid-topic-tip-taped hg/evt->db :topic-members last
                                            l
                                            (= (l @as/*my-did-peer)))
                                    (disclose-acdc-to-issuee! novel-acdc))))))))))

(def *my-aid-topic->tip-taped
  (lazy-derived-atom [as/*my-aid-topics as/*topic->tip-taped]
      (fn [my-aid-topics topic->tip-taped]
        (l [::derive-*my-aid-topic->tip-taped my-aid-topics])
        (l (->> my-aid-topics
                (map (fn [my-aid-topic]
                       [my-aid-topic (topic->tip-taped my-aid-topic)]))
                (into {}))))))

(def *my-aid-topic->ke
  (lazy-derived-atom [*my-aid-topic->tip-taped]
    (fn [my-aid-topic->tip-taped]
      (l [::derive-*my-aid-topic->ke my-aid-topic->tip-taped])
      (l (->> my-aid-topic->tip-taped
              (map-vals evt->?ke))))))

(def *my-aid-topic->my-aid
  (lazy-derived-atom [*my-aid-topic->ke]
    (fn [my-aid-topic->ke]
      (l [::derive-*my-aid-topic->my-aid my-aid-topic->ke])
      (l (->> my-aid-topic->ke (map-vals ke->ke-icp))))))

(def *my-aid->my-aid-topic
  (lazy-derived-atom [*my-aid-topic->my-aid]
      (fn [my-aid-topic->my-aid]
        (l [::derive-*my-aid->my-aid-topic my-aid-topic->my-aid])
        (l (reduce (fn [my-aid->my-aid-topic-acc [my-aid-topic my-aid]]
                     (assoc my-aid->my-aid-topic-acc my-aid my-aid-topic))
                   {}
                   my-aid-topic->my-aid)))))

(def *my-aid-path->init-key
  (lazy-derived-atom [actrl/*my-aid-path+topic->first-key *my-aid-topic->my-aid as/*my-did-peer]
      (fn [my-aid-path+topic->first-key my-aid-topic->my-aid my-did-peer]
        (->> my-aid-path+topic->first-key
             (map (fn [[[my-aid-path topic] first-key]]
                    (when-let [topic-aid (my-aid-topic->my-aid topic)]
                      [(conj my-aid-path topic-aid) first-key])))
             (remove nil?)
             (into {[] my-did-peer})))))

(def *my-aids
  (lazy-derived-atom [*my-aid-topic->my-aid]
    (fn [my-aid-topic->my-aid]
      (l [::derive-*my-aids my-aid-topic->my-aid])
      (l (vals my-aid-topic->my-aid)))))

(def *my-aid->ke
  (lazy-derived-atom [*my-aid-topic->ke]
      (fn [my-aid-topic->ke]
        (l [::derive-*my-aid->ke my-aid-topic->ke])
        (l (->> my-aid-topic->ke
                vals
                (map (fn [ke] [(ke->ke-icp ke) ke]))
                (into {}))))))


(def *selected-my-ke
  (lazy-derived-atom [*my-aid-topic->ke as/*selected-my-aid-topic]
      (fn [my-aid-topic->ke selected-my-aid-topic]
        (l [::derive-*selected-my-ke my-aid-topic->ke selected-my-aid-topic])
        (l (my-aid-topic->ke selected-my-aid-topic)))))

(def *selected-my-aid
  (lazy-derived-atom [*my-aid-topic->my-aid as/*selected-my-aid-topic]
    (fn [my-aid-topic->my-aid selected-my-aid-topic]
      (l [::derive-*selected-my-aid my-aid-topic->my-aid selected-my-aid-topic])
      (l (my-aid-topic->my-aid selected-my-aid-topic)))))


(defn* ^:memoizing evt->?connect-invite-accepted-payload [evt]
  (or (some-> evt hg/self-parent evt->?connect-invite-accepted-payload)
      (when-let [root-tx (some-> evt hg/evt->root-evt :event/tx)]
        (and (= :connect-invite-accepted (first root-tx))
             (second root-tx)))))

(def *contact-topic->connect-invite-accepted-payload
  (lazy-derived-atom [as/*topic->tip-taped]
    (fn [topic->tip-taped]
      (l [::derive-*contact-topics topic->tip-taped])
      (l (->> topic->tip-taped
              (map-vals (fn [tip-taped] (evt->?connect-invite-accepted-payload tip-taped))) ;; or check absence of :topic-name
              (filter (comp second))
              (into {}))))))

(def *contact-topics
  (lazy-derived-atom [*contact-topic->connect-invite-accepted-payload]
    (fn [contact-topic->connect-invite-accepted-payload]
      (l [::derive-*contact-topics contact-topic->connect-invite-accepted-payload])
      (l (set (keys contact-topic->connect-invite-accepted-payload))))))

(def *contact-topic->contact-aids
  (lazy-derived-atom [*contact-topic->connect-invite-accepted-payload]
    (fn [contact-topic->connect-invite-accepted-payload]
      (l [::derive-*contact-topic->contact-aid contact-topic->connect-invite-accepted-payload])
      (l (->> contact-topic->connect-invite-accepted-payload
              (reduce (fn [contact-topic->contact-aids-acc [contact-topic {:connect-invite-accepted/keys [connectee-aid connect-invite]}]]
                        (-> contact-topic->contact-aids-acc
                            (assoc contact-topic #{connectee-aid (:connect-invite/target-aid connect-invite)})))
                      {}))))))

(def *contact-aids->contact-topic
  (lazy-derived-atom [*contact-topic->contact-aids]
    (fn [contact-topic->contact-aids]
      (l [::derive-*contact-aids->contact-topic contact-topic->contact-aids])
      (l (->> contact-topic->contact-aids
              (reduce (fn [contact-aids->contact-topic-acc [contact-topic contact-aids]]
                        (assoc contact-aids->contact-topic-acc contact-aids contact-topic))
                      {}))))))

(def *my-aid->contact-topics
  (lazy-derived-atom [*my-aids *contact-topic->contact-aids]
    (fn [my-aids contact-topic->contact-aids]
      (l [::derive-*my-aid->contact-topics my-aids contact-topic->contact-aids])
      (l (->> my-aids
              (map (fn [my-aid]
                     [my-aid (->> contact-topic->contact-aids
                                  (filter (fn [[contact-topic contact-aids]] (contains? contact-aids my-aid)))
                                  (map first)
                                  set)]))
              (into {}))))))

(def *my-aid->contact-aids
  (lazy-derived-atom [*my-aids *contact-topic->contact-aids]
    (fn [my-aids contact-topic->contact-aids]
      (l [::derive-*my-aid->contact-aid my-aids contact-topic->contact-aids])
      (l (->> my-aids
              (map (fn [my-aid]
                     [my-aid (->> contact-topic->contact-aids
                                  vals
                                  (map (fn [contact-aids] (disj (set contact-aids) my-aid)))
                                  (filter (fn [contact-aids] (= 1 (count contact-aids))))
                                  (map first)
                                  set)]))
              (filter (comp not-empty second))
              (into {}))))))

(def *contact-aids
  (lazy-derived-atom [*contact-aids->contact-topic]
    (fn [contact-aids->contact-topic]
      (l [::derive-*contact-aids contact-aids->contact-topic])
      (l (->> contact-aids->contact-topic
              keys
              (apply set/union))))))

(def *my-aid->other-contact-aids
  (lazy-derived-atom [*my-aid->contact-aids *contact-aids]
    (fn [my-aid->contact-aids contact-aids]
      (l [::derive-*my-aid->other-contact-aids my-aid->contact-aids contact-aids])
      (l (->> my-aid->contact-aids
              (map (fn [[my-aid my-contact-aids]]
                     [my-aid (set/difference contact-aids my-contact-aids (set (keys my-aid->contact-aids)))]))
              (filter (comp not-empty second))
              (into {}))))))

(def *group-topics
  (lazy-derived-atom [as/*topic->tip-taped]
    (fn [topic->tip-taped]
      (l [::derive-*group-aid-topics topic->tip-taped])
      (l (->> topic->tip-taped
              (filter (fn [[_topic tip-taped]] (and (-> tip-taped hg/evt->db :member-aid->did-peers count (> 1))
                                                    (-> tip-taped hg/evt->db :topic-name some?))))
              keys
              set)))))

(def *topic->member-aid->did-peers
  (lazy-derived-atom [as/*topic->db]
    (fn [topic->db]
      (l [::derive-*topic->member->member-aid->did-peers topic->db])
      (l (->> topic->db
              (map-vals :member-aid->did-peers)
              (filter (comp some? second))
              (into {}))))))

(def *aid->did-peers
  (lazy-derived-atom [*topic->member-aid->did-peers]
    (fn [topic->member-aid->did-peers]
      (l [::derive-*aid->did-peers topic->member-aid->did-peers])
      (l (->> topic->member-aid->did-peers
              vals
              (apply merge-with set/union))))))

#_
(def *did-peer->aid
  (lazy-derived-atom [*aid->did-peers]
    (fn [aid->did-peers]
      (->> aid->did-peers
           (reduce (fn [did-peer->aid-acc [aid did-peers]])
                   {})))))

(def *aids
  (lazy-derived-atom [*aid->did-peers] (comp set keys)))

(defn* ^:memoizing aid->creation-time [aid]
  (-> aid l :key-event/anchors first :aid/creation-time l))

(defn* ^:memoizing aid->seed [aid]
  (-> aid aid->creation-time
      str last int inc))

(deftest aid->seed-test
  (let [aid-a1 {:key-event/anchors [{:aid/creation-time 1234}]}
        aid-a2 {:key-event/anchors [{:aid/creation-time 4321}]}]
    (is (= 5 (aid->seed aid-a1))
        (= 2 (aid->seed aid-a2)))))

#_(run-test aid->control-depth-test)

(defn* ^:memoizing aid->control-depth [aid]
  (or (some->> aid
               hga-playback/aid->controlling-aids
               not-empty
               (map aid->control-depth)
               (apply max)
               inc)
      0))

(defn* ^:memoising aid+inner-aid->hierarchy-depth [aid inner-aid]
  )

;; A1  A2 B1  C1
;; |  /  /   /
;; A    /   /
;; |   /   /
;; AB1    /
;; |     /
;; AB1C1
(deftest aid->depths-test
  (let [aid-a1    {:key-event/anchors []}
        aid-a2    {:key-event/anchors []}
        aid-b1    {:key-event/anchors []}
        aid-c1    {:key-event/anchors []}
        aid-a     {:key-event/anchors [{:aid/controlling-aids #{aid-a1}}]}
        aid-ab1   {:key-event/anchors [{:aid/controlling-aids #{aid-a aid-b1}}]}
        aid-ab1c1 {:key-event/anchors [{:aid/controlling-aids #{aid-ab1 aid-c1}}]}]

    (is (= 3 (aid->control-depth aid-ab1c1)))
    (is (= 2 (aid->control-depth aid-ab1)))
    (is (= 1 (aid->control-depth aid-a)))
    (is (= 0 (aid->control-depth aid-a1)))
    (is (= 0 (aid->control-depth aid-a2)))
    (is (= 0 (aid->control-depth aid-b1)))
    (is (= 0 (aid->control-depth aid-c1)))


    (is (= 3 (aid+inner-aid->hierarchy-depth aid-ab1c1 aid-ab1c1)))
    (is (= 2 (aid+inner-aid->hierarchy-depth aid-ab1c1 aid-ab1)))
    (is (= 1 (aid+inner-aid->hierarchy-depth aid-ab1c1 aid-a)))
    (is (= 0 (aid+inner-aid->hierarchy-depth aid-ab1c1 aid-a1)))
    (is (= 0 (aid+inner-aid->hierarchy-depth aid-ab1c1 aid-a2)))
    (is (= 1 (aid+inner-aid->hierarchy-depth aid-ab1c1 aid-b1)))
    (is (= 2 (aid+inner-aid->hierarchy-depth aid-ab1c1 aid-c1)))))

#_(run-test aid->control-depth-test)

(defn* ^:memoizing aid->controlling-aids [aid]
  (-> aid :key-event/anchors first :aid/controlling-aids))

(defn* ^:memoizing aids->root-aids-sorted [aids]
  (->> aids
       (sort-by aid->creation-time)
       (map (fn [aid] (if-let [controlling-aids (not-empty (aid->controlling-aids aid))]
                        (aids->root-aids-sorted controlling-aids)
                        [aid])))
       (reduce into [])))

(deftest aids->root-aids-sorted-test
  (let [aid-a1   {:key-event/anchors [{:aid/creation-time 1}]}
        aid-a    {:key-event/anchors [{:aid/creation-time    2
                                       :aid/controlling-aids #{aid-a1}}]}
        aid-b1   {:key-event/anchors [{:aid/creation-time 2}]}
        aid-b    {:key-event/anchors [{:aid/creation-time    3
                                       :aid/controlling-aids #{aid-b1}}]}
        aid-ab   {:key-event/anchors [{:aid/creation-time    4
                                       :aid/controlling-aids #{aid-a aid-b}}]}
        aid-c1   {:key-event/anchors [{:aid/creation-time 0}]}
        aid-abc1 {:key-event/anchors [{:aid/creation-time    5
                                       :aid/controlling-aids #{aid-ab aid-c1}}]}]
    (is (= [aid-a1 aid-b1] (aids->root-aids-sorted [aid-a aid-b])))
    (is (= [aid-c1 aid-a1 aid-b1] (aids->root-aids-sorted [aid-ab aid-c1])))))

#_(run-test aids->root-aids-sorted-test)

(defn* ^:memoizing root-aids-sorted+aid->x [root-aids-sorted aid]
  (if-let [root-idx (not-neg (-indexOf root-aids-sorted aid))]
    (hga-view/idx->x root-idx)
    (let [controlling-aids (aid->controlling-aids aid)]
      (->> controlling-aids
           (map (fn [controlling-aid] (root-aids-sorted+aid->x root-aids-sorted controlling-aid)))
           mean))))

#_
(defn* ^:memoizing evt->root-aids-sorted [evt]
  (->> evt
       hg/evt->db
       :member-aid->did-peers
       keys
       member-aids->controlling-aid-hierarchy-sorted
       flatten
       vec))

#_
(defn* ^:memoizing evt+aid->x [evt aid]
  (let [root-aids-sorted (evt->root-aids-sorted evt)]
    (root-aids-sorted+aid->x root-aids-sorted aid)))


#_
(def *aid->avatar
  (lazy-derived-atom [*aid->seed]
      (fn [aid->seed]
        (l [:derive-*aid->avatar aid->seed])
        (l (->> aid->seed
                (map-vals (fn [seed]
                            (case (rem seed 2)
                              0 hga-avatars/male-avatar
                              1 hga-avatars/female-avatar))))))))

(defn* ^:memoizing aid->avatar [aid]
  (case (aid->control-depth aid)
    0 hga-avatars/computer
    1 (case (rem (aid->seed aid) 2)
        0 hga-avatars/male-avatar
        1 hga-avatars/female-avatar)
    hga-avatars/group))

(defn* ^:memoizing aid->color [aid]
  (->> aid aid->seed (get hg-members/palette1)))
(set! hga-page/aid->color aid->color)

(def *topic->member-aids
  (lazy-derived-atom [*topic->member-aid->did-peers]
    (fn [topic->member-aid->did-peers]
      (l [::derive-*topic->member-aids topic->member-aid->did-peers])
      (l (->> topic->member-aid->did-peers
              (map-vals (fn [member-aid->did-peers]
                          (set (keys member-aid->did-peers)))))))))

(def *my-aid->group-topics
  (lazy-derived-atom [*my-aid-topic->my-aid *group-topics *topic->member-aids]
    (fn [my-aid-topic->my-aid group-topics topic->member-aids]
      (l [::derive-*my-aid-topic->group-topics my-aid-topic->my-aid group-topics topic->member-aids])
      (l (->> my-aid-topic->my-aid
              (map (fn [[my-aid-topic my-aid]]
                     [my-aid (->> group-topics
                                  (filter (fn [group-topic]
                                            (and (not (contains? (set (keys my-aid-topic->my-aid)) group-topic))
                                                 (-> group-topic
                                                     topic->member-aids
                                                     (contains? my-aid)))))
                                  (set))]))
              (into {}))))))

(def *my-aid->participating-topics
  (lazy-derived-atom [*my-aid->contact-topics *my-aid->group-topics]
      (fn [my-aid->contact-topics my-aid->group-topics]
        (l [:derive-*my-aid->participating-topics my-aid->contact-topics my-aid->group-topics])
        (merge-with into
                    my-aid->contact-topics
                    my-aid->group-topics))))

#_
(def *my-aid-topic->contact-topics
  (lazy-derived-atom [as/*topic->tip-taped *my-aid-topic->my-aid]
    (fn [topic->tip-taped my-aid-topic->my-aid]
      (l [::derive-*my-aid-topic->contact-aid-topics topic->tip-taped my-aid-topic->my-aid])
      (->> topic->tip-taped
           (reduce (fn [my-aid-topic->contact-aid-topics-acc [topic tip-taped]]
                     (if-let [{:connect-invite-accepted/keys [connectee-aid connect-invite]} (evt->?connect-invite-accepted-payload tip-taped)]
                       (let [?my-connectee-aid-topic (->> my-aid-topic->my-aid (some (fn [[my-aid-topic my-aid]] (when (= my-aid connectee-aid) my-aid-topic))))
                             ?my-target-aid-topic    (->> my-aid-topic->my-aid (some (fn [[my-aid-topic my-aid]] (when (= my-aid (:connect-invite/target-aid connect-invite)) my-aid-topic))))]
                         (cond-> my-aid-topic->contact-aid-topics-acc
                           ?my-connectee-aid-topic (update ?my-connectee-aid-topic conjv topic)
                           ?my-target-aid-topic    (update ?my-target-aid-topic conjv topic)))
                       my-aid-topic->contact-aid-topics-acc))
                   {})))))

#_
(def *my-aid-topic->group-topics
  (lazy-derived-atom [as/*my-aid-topics as/*topic->db]
    (fn [my-aid-topics topic->db]
      (->> my-aid-topics
           (map (fn [my-aid-topic]
                  [my-aid-topic (->> topic->db
                                     (filter (fn [[topic db]] (and (:topic-name db)
                                                                   (not (contains? (set my-aid-topics) topic)))))
                                     (map first)
                                     (into #{}))]))
           (into {})))))

#_
(def *my-aid-topic->contact-topic->contact-aid
  (lazy-derived-atom [*my-aid-topic->contact-topics *my-aid-topic->my-aid *topic->member-aids]
    (fn [my-aid-topic->contact-aid-topics my-aid-topic->my-aid topic->member-aids]
      (->> my-aid-topic->contact-aid-topics
           (map (fn [[my-aid-topic contact-aid-topics]]
                  [my-aid-topic (->> contact-aid-topics
                                     (map (fn [contact-aid-topic]
                                            [contact-aid-topic (-> contact-aid-topic
                                                                   topic->member-aids
                                                                   (disj (my-aid-topic->my-aid my-aid-topic))
                                                                   first)]))
                                     (filter second)
                                     (into {}))]))
           (filter (comp not-empty second))
           (into {})))))

#_
(def *my-aid-topic->other-contact-topic->other-contact-aid
  (lazy-derived-atom [*contact-topics *my-aid-topic->contact-topic->contact-aid]
    (fn [my-aid-topic->contact-topic->contact-aid]
      (->> my-aid-topic->contact-topic->contact-aid
           (map-vals (fn [contact-topic->contact-aid]
                       ))))))


;; ideally, should be just one did:peer of the AID
#_
(def *aid->did-peers
  (lazy-derived-atom [as/*topic->tip-taped]
    (fn [topic->tip-taped]
      (l [::derive-aid->did-peers topic->tip-taped])
      (->> topic->tip-taped
           (map (fn [[topic tip-taped]]
                  (-> tip-taped
                      hg/evt->db
                      :member-aid->did-peers)))
           (reduce merge)))))

(add-watch at/*topic->projected-db ::participate-in-rotation
           (fn [_ _ _ topic->projected-db]
             (l [::participate-in-rotation topic->projected-db])
             (doseq [[topic projected-db] topic->projected-db]
               (when-let [next-signing-key->next-next-signing-key-hash (l (:next-signing-key->next-next-signing-key-hash projected-db))]
                 (let [my-aid-paths (@actrl/*topic->my-aid-paths topic)]
                   (doseq [my-aid-path my-aid-paths]
                     (let [nk (actrl/my-aid-path+topic->nk my-aid-path topic)]
                       (when-not (next-signing-key->next-next-signing-key-hash nk)
                         (rotate-key! my-aid-path topic)))))))))
(l :regged-::participate-in-rotation)

(defn latest-control! [my-aid-path topic rotation-ke]
  (let [control (actrl/my-aid-path+topic->control my-aid-path topic)]
    (if-not (unblinded? rotation-ke (actrl/my-aid-path+topic->nk my-aid-path topic))
      control
      (actrl/rotate-control! my-aid-path topic))))

(add-watch *my-aid-topic->ke ::update-control-on-rotation-ke
           (fn [_ _ old-topic->ke new-topic->ke]
             (l [::update-control-on-rotation-ke old-topic->ke new-topic->ke])
             (let [novel-topic->ke (->> new-topic->ke
                                        (filter (fn [[topic ke]]
                                                  (not= ke (get old-topic->ke topic)))))]
               (doseq [[topic novel-ke] novel-topic->ke]
                 (let [novel-kes<          (->> novel-ke
                                                (iterate :key-event/prior)
                                                (take-while #(not= % (get old-topic->ke topic)))
                                                reverse)
                       novel-rotation-kes< (->> novel-kes<
                                                (filter #(= :rotation (:key-event/type %))))]
                   (doseq [novel-rotation-ke novel-rotation-kes<]
                     (let [my-aid-paths (@actrl/*topic->my-aid-paths topic)]
                       (doseq [my-aid-path my-aid-paths]
                         (latest-control! my-aid-paths topic novel-rotation-ke)))))))))

;; ------ inform-novel-ke ------
(defn evt->?informed-novel-ke [evt]
  (when (some-> evt :event/tx first (= :inform-novel-ke))
    (-> evt :event/tx second)))

(defn* ^:memoizing evt->?last-informed-ke [evt]
  (let [?prev-informed-ke    (some-> evt hg/self-parent evt->?last-informed-ke)
        ?current-informed-ke (evt->?informed-novel-ke evt)]
    (or ?current-informed-ke
        ?prev-informed-ke)))

(defn* ^:memoizing ke->index [ke]
  (or (some-> ke :key-event/prior ke->index inc)
      0))

(defn max-ke [ke1 ke2]
  (if (> (ke->index ke1) (ke->index ke2))
    ke1
    ke2))

(defn* ^:memoizing evt->aid->last-informed-ke [evt]
  (merge-with max-ke
              (some-> evt hg/self-parent evt->aid->last-informed-ke)
              (some-> evt hg/other-parent evt->aid->last-informed-ke)
              (if-let [ke (evt->?last-informed-ke evt)]
                {(ke->ke-icp ke) ke}
                {})))

(def *aid->latest-informed-ke
  (lazy-derived-atom [as/*topic->tip-taped]
    (fn [topic->tip-taped]
      (l [::derive-*aid->latest-informed-ke topic->tip-taped])
      (l (->> topic->tip-taped
              vals
              (map evt->aid->last-informed-ke)
              (apply merge-with max-ke))))))

(def *aid->latest-known-ke
  (lazy-derived-atom [*aid->latest-informed-ke *my-aid->ke]
    (fn [aid->latest-informed-ke my-aid->ke]
      (l [::derive-*aid->latest-known-ke aid->latest-informed-ke my-aid->ke])
      (l (merge aid->latest-informed-ke
                my-aid->ke)))))

(def *aid->aid-name
  (lazy-derived-atom [*aid->latest-known-ke]
    (fn [aid->latest-known-ke]
      (l [::derive*aid->aid-name aid->latest-known-ke])
      (l (->> aid->latest-known-ke
              (map-vals ke->?aid-name))))))
(set! hga-state/*aid->aid-name *aid->aid-name)

(defn ke->group-aid? [ke]
  (-> ke ->latest-establishment-key-event :key-event/signing-keys count (> 1)))

(def *aid->group-aid?
  (lazy-derived-atom [*aid->latest-known-ke]
    (fn [aid->latest-known-ke]
      (l [::derive*aid->group-aid? aid->latest-known-ke])
      (l (->> aid->latest-known-ke
              (map-vals ke->group-aid?))))))


(defn add-inform-novel-ke-event! [my-aid-path topic ke]
  (at/add-event! my-aid-path topic {:event/tx [:inform-novel-ke ke]}))

(rum/derived-atom [*my-aid->ke *my-aid->participating-topics] :inform-of-my-aid-novel-ke
  (fn [my-aid->ke my-aid->participating-topics]
    (l [::inform-of-my-aid-novel-ke my-aid->ke my-aid->participating-topics])
    (doseq [[my-aid ke]         my-aid->ke
            participating-topic (my-aid->participating-topics my-aid)]
      (letl [participating-topic-tip-taped (@as/*topic->tip-taped (l participating-topic))
             ?last-informed-ke             (evt->?last-informed-ke participating-topic-tip-taped)]
        (when (not= ke ?last-informed-ke)
          (let [my-aid-paths (@actrl/*topic->my-aid-paths participating-topic) ;; many aids may participate in the topic
                my-aid-path  (->> my-aid-paths
                                  (filter (fn [my-aid-path] (= my-aid (last my-aid-path))))
                                  (first))] ;; device many participate in the topic via many aid-paths, taking one to notify from it
            (add-inform-novel-ke-event! my-aid-path participating-topic ke)))))))
;; -------------
