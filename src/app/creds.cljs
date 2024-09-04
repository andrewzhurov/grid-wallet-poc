(ns app.creds
  (:require
   [hashgraph.main :refer [Member Topic] :as hg]
   [hashgraph.topic :as hgt :refer [Tape TipTaped G$]]
   [hashgraph.members :as hgm]
   [hashgraph.app.events :as hga-events]
   [hashgraph.app.playback :as hga-playback]
   [hashgraph.app.state :as hga-state]
   [hashgraph.app.members :as hga-members]
   [hashgraph.app.page :as hga-page]
   [hashgraph.app.view :as hga-view]
   [hashgraph.app.utils :as hga-utils]
   [hashgraph.utils.core :refer [hash= conjs] :refer-macros [defn* l letl when-let*] :as utils]

   [app.styles :refer [reg-styles!] :as styles]
   [app.state :as as]
   [app.io :refer [reg< send-message]]
   [app.topic :as at]
   [app.topic-viz :as atv]

   [rum.core :refer [defc defcs] :as rum]
   [clojure.set :as set]
   [clojure.test :refer [deftest testing is are run-tests]]
   [clojure.pprint :refer [pprint]]
   [garden.selectors :as gs]
   [malli.core :as m]
   [malli.util :as mu]
   [malli.error :as me]
   [goog.object :as gobject]))

(def AID string?)
(def LEI string?)

(def KeyEventType
  [:enum
   :inception
   :rotation
   :interaction])

(def Key string?)
(def Hash int?)
(def KeyHash Hash)
(def Signature string?)

(def Fraction
  [:and
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

(def Threshold
  [:and
   [:vector [:or Fraction [:= 0]]]
   [:fn {:error/message "Threshold must sum up to >= 1 or be 0"}
    (fn [threshold]
      (let [total (sum-fractions threshold)]
        (or (>= total 1)
            (zero? total))))]])

(defn ->establishment-key-event? [ke]
  (#{:inception :rotation} (:key-event/type ke)))

(defn ->prior-establishment-key-event [ke]
  (->> ke
       (iterate :key-event/prior)
       (drop 1)
       (take-while some?)
       (some (fn [ke] (when (->establishment-key-event? ke) ke)))))

(defn ->latest-establishment-key-event [ke]
  (if (->establishment-key-event? ke)
    ke
    (->prior-establishment-key-event ke)))

(defn* ^:memoizing ->inception-key-event [ke]
  (if (= :inception (:key-event/type ke))
    ke
    (->inception-key-event (:key-event/prior ke))))

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

(def KeyEvent
  {::key-event
   [:multi {:dispatch :key-event/type}
    [:inception ::key-event-inception]
    [:rotation ::key-event-rotation]
    [:interaction ::key-event-interaction]]

   ::key-event-inception
   [:and
    [:map {:closed true}
     [:key-event/type [:= :inception]]
     [:key-event/signing-keys [:vector Key]]
     [:key-event/threshold Threshold]
     [:key-event/next-signing-keys [:vector KeyHash]]
     [:key-event/next-threshold Threshold]
     #_[:key-event/said Hash]]
    #_(said-check :key-event/said)
    signing-keys-threshold-check
    next-signing-keys-next-threshold-check]

   ::key-event-rotation
   [:and
    [:map {:closed true}
     [:key-event/type [:= :rotation]]
     [:key-event/signing-keys [:vector Key]]
     [:key-event/threshold Threshold]
     [:key-event/next-signing-keys [:vector KeyHash]]
     [:key-event/next-threshold Threshold]
     [:key-event/prior [:ref ::key-event]] ;; it's a SAID, actually
     #_[:key-event/said Hash]]
    #_(said-check :key-event/said)
    signing-keys-threshold-check
    next-signing-keys-next-threshold-check
    [:fn {:error/message "Rotation event's unblinded must be able to satisfy prev Establishment event's threshold"}
     (fn [ke] (>= (->unblinded-keys-weight ke) 1))]]

   ::key-event-interaction
   [:and
    [:map {:closed true}
     [:key-event/type [:= :interaction]]
     [:key-event/anchors [:vector {:min 1} [:ref ::tx-event]]]
     [:key-event/prior [:ref ::key-event]]
     #_[:key-event/said Hash]]
    #_(said-check :key-event/said)]})

(def TxEvent
  {::tx-event
   [:or
    [:ref ::tx-event-inception]
    [:ref ::tx-event-update]]

   ::tx-event-inception
   [:map {:closed true}
    [:tx-event/type [:= :inception]]
    [:tx-event/issuer [:ref ::key-event-inception]]]

   ::tx-event-update
   [:map {:closed true}
    [:tx-event/type [:= :update]]
    [:tx-event/attribute
     [:map {:closed true}
      [:ts [:enum :placeholder :issued :revoked]]]]
    [:tx-event/prior [:ref ::tx-event]]]})

(def ACDC
  {::acdc
   [:map {:closed true}
    [:acdc/issuer [:ref ::key-event-inception]]
    [:acdc/schema keyword?]
    [:acdc/attribute {:optional true} map?]
    [:acdc/uuid {:optional true} uuid?]
    [:acdc/registry {:optional true} [:ref ::tx-event-inception]]
    [:acdc/edge-group {:optional true} [:ref ::acdc-edge-group]]]

   ::acdc-edge-group
   [:map-of
    keyword? [:or
              [:ref ::acdc]]]

   ::acdc-qvi
   [:merge
    [:ref ::acdc]
    [:map {:closed true}
     [:acdc/schema [:= ::acdc-qvi]]
     [:acdc/registry [:ref ::tx-event-inception]]
     [:acdc/attribute
      [:map {:closed true}
       [:issuee [:ref ::key-event-inception]]
       [:lei LEI]]]]]

   ::acdc-le
   [:merge
    [:ref ::acdc]
    [:map {:closed true}
     [:acdc/schema [:= ::acdc-le]]
     [:acdc/registry [:ref ::tx-event-inception]]
     [:acdc/attribute
      [:map {:closed true}
       [:issuee [:ref ::key-event-inception]]
       [:lei LEI]]]
     [:acdc/edge-group
      [:map {:closed true}
       [:qvi [:ref ::acdc-qvi]]]]]]})

(def schema-registry
  (merge
   (m/default-schemas)
   (mu/schemas)
   KeyEvent
   TxEvent
   ACDC))

(defn check [schema-id value]
  (or (m/validate [:ref schema-id] value {:registry schema-registry})
      (do (pprint value)
          (pprint (me/humanize (m/explain [:ref schema-id] value {:registry schema-registry})))
          false)))


(defonce *topic->control (atom {}))
(defonce *selected-topic (atom nil))

(def alias-nonce (random-uuid))
(defn gen-key [topic idx] ;; passing in all kinds of stuff as `topic`
  (cond (keyword? topic)
        (str (name topic) idx)
        (int? topic)
        (str topic "-k" idx)
        :else
        (str (hash [topic alias-nonce]) "-k" idx)))

(defn init-control! [topic]
  (let [control {:control/idx                   0
                 :control/signing-key           (gen-key topic 0)
                 :control/next-signing-key      (gen-key topic 1)
                 :control/next-next-signing-key (gen-key topic 2)}]
    (swap! *topic->control assoc topic control)
    (reset! *selected-topic topic)
    control))

(defn latest-control! [topic rotation-ke]
  (let [topic->control @*topic->control]
    (if-let [{:control/keys [key-idx
                             signing-key
                             next-signing-key
                             next-next-signing-key]
              :as           control}
             (topic->control topic)]
      (if-not (unblinded? rotation-ke next-signing-key)
        control
        (let [new-key-idx (inc key-idx)
              new-control {:control/key-idx               new-key-idx
                           :control/signing-key           next-signing-key
                           :control/next-signing-key      next-next-signing-key
                           :control/next-next-signing-key (gen-key topic (inc (inc new-key-idx)))}]
          (swap! *topic->control assoc topic new-control)
          new-control))
      (js/console.error "no control is present for topic" topic topic->control))))

(defn topic->k [topic]
  (-> @*topic->control
      (get topic)
      (get :control/signing-key)))

(defn topic->nk [topic]
  (-> @*topic->control
      (get topic)
      (get :control/next-signing-key)))

(defn topic->nnk [topic]
  (-> @*topic->control
      (get topic)
      (get :control/next-next-signing-key)))


(hg/reg-tx-handler! :init-control
  (fn [{:keys [consensus-keys] :as db} {:event/keys [creator]} [_ signing-key next-signing-key-hash]]
    (cond-> db
      ((set consensus-keys) creator)
      ;; direct mode assumed, where consensus keys = controlling keys
      (-> (assoc-in [:consensus-keys (-indexOf consensus-keys creator)] signing-key)
          (assoc-in [:signing-key->next-signing-key-hash signing-key] next-signing-key-hash)
          (assoc-in [:init-key->known-control creator] {:known-control/keys          [creator signing-key]
                                                        :known-control/next-key-hash next-signing-key-hash})))))

(defn sc-accept-init-control [{:keys [consensus-keys signing-key->next-signing-key-hash] :as db}]
  (cond-> db
    (and (nil? (:ke db)) (= (count consensus-keys) (count signing-key->next-signing-key-hash)))
    (-> (assoc :ke (let [present-next-signing-key-hashes (-> signing-key->next-signing-key-hash
                                                             vals
                                                             (->> (filter some?))
                                                             vec)]
                     {:key-event/type              :inception
                      :key-event/signing-keys      (vec (keys signing-key->next-signing-key-hash))
                      :key-event/threshold         (hg/->equal-threshold (count signing-key->next-signing-key-hash))
                      :key-event/next-signing-keys present-next-signing-key-hashes
                      :key-event/next-threshold    (hg/->equal-threshold (count present-next-signing-key-hashes))})))))


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
                       {:key-event/type              :rotation
                        :key-event/signing-keys      next-signing-keys
                        :key-event/threshold         (:key-event/next-threshold latest-est-ke)
                        :key-event/next-signing-keys (vec (vals next-signing-key->next-next-signing-key-hash))
                        :key-event/next-threshold    (:key-event/next-threshold latest-est-ke)
                        :key-event/prior             ke}))
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
          issuer-aid (->inception-key-event ke)
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

(reset! hg/*smartcontracts [sc-accept-init-control sc-anchor-accepted-proposals sc-accept-rotate])


(defn sole-controller? [db]
  (= 1 (count (:signing-key->next-signing-key db))))



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
  {:acdc/schema    ::acdc-qvi
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
  {:acdc/schema     ::acdc-le
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
    (is (check ::key-event ke))))

(deftest tel-test
  (doseq [te [gleif-acdc-qvi-te0 gleif-acdc-qvi-te1
              qvi-acdc-le-te0 qvi-acdc-le-te1]]
    (is (check ::tx-event te))))

(deftest acdc-test
  (doseq [acdc [gleif-acdc-qvi
                qvi-acdc-le]]
    (is (check ::acdc acdc))
    (is (check (:acdc/schema acdc) acdc))))


(init-control! :gleif)
(def gleif-k0 (topic->k :gleif))
(def gleif-k1 (topic->nk :gleif))
(def gleif-k2 (topic->nnk :gleif))
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

(defn evt->?ke [evt] (-> evt hg/evt->db :ke))
(defn evt->?ke->k->signature [evt] (-> evt hg/evt->db :ke->k->signature))
(defn evt->?ke-icp [evt]
  (some-> evt evt->?ke ->inception-key-event))

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

(defn add-init-control-event!
  [topic]
  (init-control! topic)
  (let [k  (topic->k topic)
        nk (topic->nk topic)]
    (at/add-event! topic {:event/tx [:init-control k (hash nk)]})))

(defn incepted? [event]
  (-> event evt->?ke some?))

(defn* ^:memoizing init-control-initiated? [event]
  (boolean (or (some-> (:event/tx event) first (= :init-control))
               (some-> (hg/self-parent event) init-control-initiated?)
               (some-> (hg/other-parent event) init-control-initiated?))))

(defn* ^:memoizing init-control-participated? [event]
  (boolean (or (some-> (:event/tx event) first (= :init-control))
               (some-> (hg/self-parent event) init-control-participated?))))

(add-watch as/*my-did-peer ::create-topic-for-my-did-peer
           (fn [_ _ _ my-did-peer]
             (when my-did-peer
               (l [:creating-topic-for-my-did-peer my-did-peer])
               (let [topic (at/create-topic! my-did-peer #{my-did-peer})]
                 #_(add-init-control-event! topic)))))

(add-watch as/*topic->tip-taped ::participate-in-init-control
           (fn [_ _ _ new]
             (doseq [[topic tip-taped] new]
               (when (and (l (not (incepted? tip-taped)))
                          (l (init-control-initiated? tip-taped))
                          (l (not (init-control-participated? tip-taped))))
                 (add-init-control-event! topic)))))

;; redundant
(add-watch as/*topic->tip-taped ::derive-my-aids-and-reg-did-peers
           (fn [_ _ _ topic->tip-taped]
             (let [my-aid-topics @as/*my-aid-topics]
               (when-let [novel-incepted-topics (->> topic->tip-taped
                                                     (filter (fn [[topic tip-taped]]
                                                               (and (incepted? tip-taped)
                                                                    (= -1 (-indexOf my-aid-topics topic)))))
                                                     (map first)
                                                     not-empty)]
                 (reset! as/*my-aid-topics (vec (into my-aid-topics novel-incepted-topics)))
                 (reset! as/*selected-my-aid-topic (last novel-incepted-topics))
                 (reset! as/*selected-topic (last novel-incepted-topics))
                 (doseq [novel-incepted-topic novel-incepted-topics]
                   (at/add-event! novel-incepted-topic {:event/tx [:reg-did-peers]}))))))

(hg/reg-tx-handler! :reg-did-peers
  (fn [{:keys [init-key->known-control ke] :as db} _ _]
    (assoc-in db [:member-aid->did-peers (->inception-key-event ke)] (set (keys init-key->known-control)))))

(defn rotate-key! [topic]
  (let [nk  (topic->nk topic)
        nnk (topic->nnk topic)]
    (at/add-event! topic {:event/tx [:rotate nk (hash nnk)]})))

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
                          (fn [_ _ old-my-aid-topic-tip-taped new-my-aid-topic-tip-taped]
                            (let [old-acdcs (-> old-my-aid-topic-tip-taped
                                                hg/evt->db
                                                :acdcs)
                                  new-acdcs (-> new-my-aid-topic-tip-taped
                                                hg/evt->db
                                                :acdcs)]
                              (when-let [novel-acdcs (not-empty (set/difference new-acdcs old-acdcs))]
                                (doseq [novel-acdc novel-acdcs]
                                  ;; hacky way to select 1 device as discloser
                                  (when (-> new-my-aid-topic-tip-taped hg/evt->db :topic-members last
                                            l
                                            (= (l @as/*my-did-peer)))
                                    (disclose-acdc-to-issuee! novel-acdc))))))))))

(def *my-aids
  (rum/derived-atom [as/*my-aid-topics as/*topic->tip-taped] ::derive-my-aids
    (fn [my-aid-topic topic->tip-taped]
      (->> my-aid-topic
           (map topic->tip-taped)
           (map evt->?ke-icp)
           (filter some?)))))



#_
(when-let [new-ke (evt->?ke new-my-aid-topic-tip-taped)]
  (when (not= ?old-ke new-ke)
    (when-let [novel-ixn-kes< (->> new-ke
                                   (iterate :key-event/prior)
                                   (take-while #(not= % ?old-ke))
                                   (filter (fn [ke] (= :interaction (:key-event/type ke))))
                                   not-empty)]
      (let [acdcs            (-> new-my-aid-topic-tip-taped
                                 hg/evt->db
                                 :acdcs)
            acdc->latest-tel (->> acdcs
                                  (map (fn [acdc]
                                         [acdc (->> novel-ixn-kes>
                                                    (some (fn [novel-ixn-ke]
                                                            (->> novel-ixn-ke
                                                                 :key-event/anchors
                                                                 (some (fn [te]
                                                                         (when (= te (:acdc/registry acdc))
                                                                           te)))))))])))]
        (doseq [acdc acdcs]
          (disclose-issued-acdc-and-tel-to-issue! acdc ))))))

(def *topic->ke
  (rum/derived-atom [as/*topic->tip-taped] ::derive-topic->ke
    (fn [topic->tip-taped]
      (->> topic->tip-taped
           (map (fn [[topic tip-taped]]
                  [topic (-> (get topic->tip-taped topic)
                             (evt->?ke))]))
           (filter (comp some? second))
           (into {})))))

(def *topic->aid
  (rum/derived-atom [*topic->ke] ::derive-topic->aid
    (fn [topic->ke]
      (->> topic->ke
           (map (fn [[topic ke]]
                  [topic (->inception-key-event ke)]))
           (into {})))))

(def *aid->topic
  (rum/derived-atom [*topic->aid] ::derive-aid->topic
    (fn [topic->aid]
      (reduce (fn [aid->topic-acc [topic aid]]
                (assoc aid->topic-acc aid topic))
              {}
              topic->aid))))

(def *selected-my-ke
  (rum/derived-atom [*topic->ke as/*selected-my-aid-topic] ::derive-selected-my-ke
    (fn [topic->ke selected-my-aid-topic]
      (get topic->ke selected-my-aid-topic))))

(def *selected-my-aid
  (rum/derived-atom [*topic->aid as/*selected-my-aid-topic] ::derive-selected-my-aid
    (fn [topic->aid selected-my-aid-topic]
      (get topic->aid selected-my-aid-topic))))


;; ideally, should be just one did:peer of the AID
(def *aid->did-peers
  (rum/derived-atom [as/*topic->tip-taped] ::derive-aid->did-peers
    (fn [topic->tip-taped]
      (->> topic->tip-taped
           (map (fn [[topic tip-taped]]
                  (-> tip-taped
                      hg/evt->db
                      :member-aid->did-peers)))
           (reduce merge)))))

(def *disclosed-acdcs
  (rum/derived-atom [atv/*topic->viz-subjective-db] ::derive-disclosed-acdcs
    (fn [topic->viz-subjective-db]
      (->> topic->viz-subjective-db
           (map (fn [[_topic viz-subjective-db]] (some-> viz-subjective-db :feed :feed/disclosed-acdcs)))
           (filter some?)
           (apply set/union)))))

(def *aid->attributed-acdcs
  (rum/derived-atom [*disclosed-acdcs] ::derive-aid->attributed-acdcs
    (fn [disclosed-acdcs]
      (l (->> (l disclosed-acdcs)
              (reduce (fn [aid->attributed-acdcs-acc disclosed-acdc]
                        (let [?issuee-aid (-> disclosed-acdc :acdc/attribute :issuee)]
                          (cond-> aid->attributed-acdcs-acc
                            ?issuee-aid (update ?issuee-aid conjs disclosed-acdc))))
                      {}))))))

(add-watch at/*topic->projected-db ::participate-in-rotation
           (fn [_ _ _ topic->projected-db]
             (l [::participate-in-rotation topic->projected-db])
             (doseq [[topic projected-db] topic->projected-db]
               (when-let [next-signing-key->next-next-signing-key-hash (l (:next-signing-key->next-next-signing-key-hash projected-db))]
                 (letl [nk (topic->nk topic)]
                   (when-not (l (next-signing-key->next-next-signing-key-hash nk))
                     (rotate-key! topic)))))))


(add-watch *topic->ke ::update-control-on-rotation-ke
           (fn [_ _ old-topic->ke new-topic->ke]
             (let [novel-topic->ke (->> new-topic->ke
                                        (filter (fn [[topic ke]]
                                                  (not= ke (old-topic->ke topic)))))]
               (doseq [[topic novel-ke] novel-topic->ke]
                 (let [novel-kes< (->> novel-ke
                                       (iterate :key-event/prior)
                                       (take-while #(not= % (old-topic->ke topic)))
                                       reverse)
                       novel-rotation-kes< (->> novel-kes<
                                                (filter #(= :rotation (:key-event/type %))))]
                   (doseq [novel-rotation-ke novel-rotation-kes<]
                     (l (latest-control! topic novel-rotation-ke))))))))
