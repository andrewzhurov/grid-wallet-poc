(ns app.creds
  (:require
   [hashgraph.main :as hg]
   [hashgraph.topic :as hgt]
   [hashgraph.members :as hg-members]
   [hashgraph.schemas :as hgs]
   [hashgraph.utils.core
    :refer [xor hash= not-neg mean conjs disjs conjv vec-union map-keys map-vals filter-map-vals reverse-map map-prim indexed]
    :refer-macros [defn* l letl letl2 when-let*] :as utils]
   [hashgraph.utils.lazy-derived-atom :refer [lazy-derived-atom] :refer-macros [deflda defda]]

   [hashgraph.app.avatars :as hga-avatars]
   [hashgraph.app.playback :as hga-playback]
   [hashgraph.app.state :as hga-state]
   [hashgraph.app.page :as hga-page]
   [hashgraph.app.view :as hga-view]

   [app.state :as as]
   [app.topic :as at]
   [app.control :as actrl]
   [app.io :as io]

   [garden.color :as gc]
   [clojure.set :as set]
   [clojure.test :refer [deftest testing is are run-tests run-test]]
   [clojure.walk :as walk]
   [clojure.pprint :refer [pprint]]
   [malli.core :as m]
   [malli.util :as mu]
   [malli.error :as me]))

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
 ::threshold
 [:or :leaf-threshold
  [:and
   [:vector {:min 1} [:ref ::threshold-el]]
   [:fn {:error/message "Weighted threshold must be satisfiable (weights sum up to >= 1)"}
    (fn [weighted-thresholds]
      (let [total-weight (->> weighted-thresholds
                              (map (fn [weighted-threshold]
                                     (let [[nom denom] (-> weighted-threshold ffirst)]
                                       (/ nom denom))))
                              (reduce +))]
        (>= total-weight 1)))]]]
 :threshold ::threshold

 :leaf-threshold
 [:= [[1 1]]]

 ::threshold-el
 [:or
  :fraction
  [:ref ::weighted-threshold]]

 ::weighted-threshold
 [:and
  [:map-of {:min 1 :max 1} :fraction [:ref ::threshold]]]
 :weighted-threshold ::weighted-threshold)

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

(defn flat-vector? [maybe-flat-vec]
  (and (vector? maybe-flat-vec)
       (->> maybe-flat-vec (every? (comp not coll?)))))
#_
(defn simple-fraction? [maybe-simple-fraction]
  (and (flat-vector? maybe-simple-fraction)
       (= 2 (count maybe-simple-fraction))
       (let [[nom denom] maybe-simple-fraction]
         (not (zero? denom)))))
#_#_
(declare threshold?)
(defn weighted-thresholds? [maybe-weighted-thresholds]
  (and (map? maybe-weighted-thresholds)
       (-> maybe-weighted-thresholds first first simple-fraction?)
       (-> maybe-weighted-thresholds first second threshold?)))
#_#_
(declare threshold?)
(defn thresholds? [maybe-thresholds]
  (and (vector? maybe-thresholds)
       (->> maybe-thresholds (every? threshold?))))
#_#_
(declare thresholds?)
(defn threshold? [maybe-threshold]
  (or (simple-fraction? maybe-threshold)
      (weighted-threshold? maybe-threshold)
      (thresholds? maybe-threshold)))

#_
(defn is-fraction [maybe-fraction]
  (when (m/validate :fraction maybe-fraction)
    maybe-fraction))

#_
(defn is-leaf-threshold [maybe-leaf-threshold]
  (when (m/validate :leaf-threshold maybe-leaf-threshold)
    maybe-leaf-threshold))

#_
(defn is-threshold [maybe-threshold]
  (when (m/validate :threshold maybe-threshold)
    maybe-threshold))

#_
(defn is-weighted-threshold [maybe-weighted-threshold]
  (when (m/validate :weighted-thresholds maybe-weighted-threshold)
    maybe-weighted-threshold))

;; Assumption: flat threshold fractions are not supported
;; E.g., [[1 2] [1 2]]
;; Instead, they'll be
;; [{[1 2] [[1 1]]}
;;  {[1 2] [[1 1]]}]
;; Potentially they can be
;; [{[1 2] 1}
;;  {[1 2] 1}]
;; this is to make it easier to express aid-signing-weight->aid-threshold
;; as multisig AID is always meant to be a combination of other AIDs

;; fast/inprecise checking, suitable for distpatch
;; opting to use it instead of full check with malli, as no need for it, as it's meant to have been validated prior
(defn fraction? [maybe-fraction]
  (and (vector? maybe-fraction)
       (= 2 (count maybe-fraction))
       (int? (first maybe-fraction))
       (int? (second maybe-fraction))))

(defn weighted-threshold? [maybe-weighted-threshold]
  (map? maybe-weighted-threshold))

(defn threshold? [maybe-threshold]
  (and (vector? maybe-threshold)
       (not (fraction? maybe-threshold))))

(defn threshold->fractions-paths [threshold & [?child-fractions-path]]
  ;; (println threshold ?child-fractions-path)
  (cond (fraction? threshold)
        [(conjv ?child-fractions-path threshold)]

        (weighted-threshold? threshold)
        (let [[weight weighted-threshold] (-> threshold first)]
          (threshold->fractions-paths weighted-threshold (conjv ?child-fractions-path weight)))

        (vector? threshold)
        (->> threshold
             (map-indexed (fn [idx threshold-el]
                            (threshold->fractions-paths threshold-el ?child-fractions-path)))
             (apply concat)
             vec)))

(defn threshold->key-weights [threshold]
  (->> threshold
       threshold->fractions-paths
       (mapv (fn [fractions-path]
               (->> fractions-path (reduce (fn [key-weight-acc [nom denom]] (-> key-weight-acc (* (/ nom denom))))
                                           1))))))

(defn threshold->paths [threshold & [?path]]
  (cond (fraction? threshold)
        [?path]

        (weighted-threshold? threshold)
        (let [[weight weighted-threshold] (-> threshold first)]
          (threshold->paths weighted-threshold (conjv ?path weight)))

        (threshold? threshold)
        (->> threshold
             (map-indexed (fn [idx threshold-el]
                            (threshold->paths threshold-el (conjv ?path idx))))
             (apply concat)
             vec)))

#_(m/validate [:map-of vector? [:= true]] {[1 2] true})
(defn voted-threshold->voted-threshold-propagated [voted-threshold]
  (->> voted-threshold
       (clojure.walk/postwalk (fn [form]
                                #_(println "form:" form)
                                (let [out (cond (= [true] form)
                                                true

                                                (and (vector? form)
                                                     (->> form (every? map?))
                                                     (let [agreed-weight (->> form
                                                                              (reduce (fn [vote-acc weighted-voted-threshold]
                                                                                        (let [[[nom denom] maybe-vote] (first weighted-voted-threshold)]
                                                                                          (cond-> vote-acc
                                                                                            (true? maybe-vote) (+ (/ nom denom)))))
                                                                                      0))]
                                                       (>= agreed-weight 1)))
                                                true

                                                :else
                                                form)]
                                  #_(println "out:" out)
                                  out)))))

;; TODO incremental?
;; or pre-compute a set of all subsets of keys satisfying threshold (will increasingly grow in size proportional to keys count)
(defn threshold+signing-keys+agreed-keys->threshold-satisfied? [threshold signing-keys agreed-signing-keys]
  (let [paths                      (-> threshold threshold->paths)
        agreed-paths               (->> agreed-signing-keys
                                        (map (fn [agreed-signing-key]
                                               (when-let [idx (not-neg (-indexOf signing-keys agreed-signing-key))]
                                                 (nth paths idx))))
                                        (filter some?))
        voted-threshold            (->> agreed-paths
                                        (reduce (fn [voted-threshold-acc agreed-path]
                                                  (assoc-in voted-threshold-acc agreed-path true))
                                                threshold))
        ;; _                          (println "voted-threshold:" voted-threshold)
        voted-threshold-propagated (-> voted-threshold voted-threshold->voted-threshold-propagated)]
    ;; (println "voted-threshold-propagated:" voted-threshold-propagated)
    (true? voted-threshold-propagated)))

(let [signing-keys    [:a1 :a2 :a3 :b1 :c1 :c2 :c3]
      threshold       [{[1 2] [{[1 2] [[1 1]]}
                               {[1 2] [[1 1]]}
                               {[1 1] [[1 1]]}]}
                       {[1 2] [[1 1]]}
                       {[1 1] [{[1 2] [[1 1]]}
                               {[1 2] [[1 1]]}
                               {[1 2] [[1 1]]}]}]
      fractions-paths [[[1 2] [1 2] [1 1]]
                       [[1 2] [1 2] [1 1]]
                       [[1 2] [1 1] [1 1]]
                       [[1 2] [1 1]]
                       [[1 1] [1 2] [1 1]]
                       [[1 1] [1 2] [1 1]]
                       [[1 1] [1 2] [1 1]]]
      key-weights     [(-> 1 (/ 2) (/ 2))
                       (-> 1 (/ 2) (/ 2))
                       (-> 1 (/ 2))
                       (-> 1 (/ 2))
                       (-> 1 (/ 2))
                       (-> 1 (/ 2))
                       (-> 1 (/ 2))]
      paths           [[0 [1 2] 0 [1 2] 0]
                       [0 [1 2] 1 [1 2] 0]
                       [0 [1 2] 2 [1 1] 0]
                       [1 [1 2] 0]
                       [2 [1 1] 0 [1 2] 0]
                       [2 [1 1] 1 [1 2] 0]
                       [2 [1 1] 2 [1 2] 0]]]

  (deftest fraction?-test
    (is (fraction? [1 1]))
    (is (not (fraction? [[1 1]]))))

  (deftest weighted-threshold?-test
    (is (weighted-threshold? {[1 2] [[1 1]]}))
    (is (weighted-threshold? {[1 2] [{[1 2] [[1 1]]}
                                     {[1 2] [[1 1]]}]})))

  (deftest threshold?-test
    (is (threshold? threshold)))

  (deftest threshold->fractions-paths-test
    (is (= fractions-paths (-> threshold threshold->fractions-paths))))

  (deftest threshold->key-weights-test
    (is (= key-weights (-> threshold threshold->key-weights))))

  (deftest threshold->paths-test
    (is (= paths (-> threshold threshold->paths))))

  (deftest threshold+signing-keys+agreed-keys->threshold-satisfied?-test
    #_(is (= true (threshold+signing-keys+agreed-keys->threshold-satisfied? threshold signing-keys [:a1])))
    (are [agreed-signing-keys] (threshold+signing-keys+agreed-keys->threshold-satisfied? threshold signing-keys agreed-signing-keys)
      [:a1 :a2 :a3 :b1 :c1 :c2 :c3]
      [:a3 :b1 :c1 :c2]
      [:a1 :a2 :b1]
      [:a3 :b1]
      [:c1 :c2]
      [:c2 :c3]
      [:c1 :c3]
      [:c1 :c3 :a1]
      [:c1 :c3 :b1])
    (are [agreed-signing-keys] (not (threshold+signing-keys+agreed-keys->threshold-satisfied? threshold signing-keys agreed-signing-keys))
      []
      [:a1]
      [:a1 :a2]
      [:b1]
      [:a1 :b1]
      [:a3 :c1]
      [:a1 :b1 :c1]
      [:a1 :a2 :c1]))

  #_(clojure.walk/prewalk (fn [arg] (println arg) arg) threshold)
  #_(clojure.walk/postwalk (fn [arg] (println arg) arg) threshold))

#_(do (run-test fraction?-test)
    (run-test weighted-threshold?-test)
    (run-test threshold?-test)
    (run-test threshold->fractions-paths-test)
    (run-test threshold->key-weights-test)
    (run-test threshold->paths-test)
    (run-test threshold+signing-keys+agreed-keys->threshold-satisfied?-test))


(defn ke+agreed-keys->threshold-satisfied? [ke agreed-keys]
  (let [{:key-event/keys [signing-keys threshold]} (->latest-establishment-key-event ke)]
    (threshold+signing-keys+agreed-keys->threshold-satisfied? threshold signing-keys agreed-keys)))

(defn ke+agreed-keys->next-threshold-satisfied? [ke agreed-keys]
  (let [{:key-event/keys [next-signing-keys next-threshold]} (->latest-establishment-key-event ke)]
    (threshold+signing-keys+agreed-keys->threshold-satisfied? (l next-threshold) (l next-signing-keys) (l (map hash agreed-keys)))))

(def signing-keys-threshold-check
  [:fn {:error/message "threshold must be set for all signing keys"}
   (fn [{:key-event/keys [signing-keys threshold]}] (= (count signing-keys) (count (threshold->key-weights threshold))))])

(def next-signing-keys-next-threshold-check
  [:fn {:error/message "next threshold must be set for all next signing keys"}
   (fn [{:key-event/keys [next-signing-keys next-threshold]}] (= (count next-signing-keys) (count (threshold->key-weights next-threshold))))])

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

(def pub-db-keys
  [:topic-name

   :aids#-log
   :aid$->ke

   :member-aids$-log
   :member-aids$
   :member-aid$->signing-weight
   :member-aid$->member-init-keys
   :member-aid$->member-init-keys-log

   :member-init-key->init-key
   :init-key->known-control
   :init-key->did-peer])

(defn db->pub-db [{:keys [member-aid->member-init-keys member-init-key->init-key] :as db}]
  (select-keys db pub-db-keys))

(defn pub-db-anchor [db]
  {:aid/pub-db (-> db db->pub-db)})

(defn* ^:memoizing ke->pub-db [ke]
  (or (->> ke :key-event/anchors (some (fn [anchor] (:aid/pub-db anchor))))
      (some-> ke :key-event/prior ke->pub-db)))

(defn db->init-key->signing-key [{:keys [init-key->known-control]}]
  (->> init-key->known-control
       (map-vals (comp last :known-control/keys))))

(defn db->signing-key->init-key [db]
  (->> db
       db->init-key->signing-key
       reverse-map))

(defn db->init-key->next-signing-key-hash [{:keys [init-key->known-control]}]
  (->> init-key->known-control
       (map-vals :known-control/next-key-hash)))

(hg/reg-tx-handler! :init-control
    (fn [{:keys [member-init-keys member-init-keys-log member-aid->member-init-keys] :as db} {:event/keys [creator] :as evt} [_ signing-key next-signing-key-hash]]
      (let [member-init-key (nth member-init-keys-log creator)]
        (-> db
            ;; direct mode assumed / no consensus-only members, where consensus keys = controlling keys
            (assoc-in [:member-init-key->init-key member-init-key] signing-key)
            (assoc-in [:init-key->known-control signing-key] {:known-control/keys          [signing-key]
                                                              :known-control/next-key-hash next-signing-key-hash})))))

(hg/reg-tx-handler! :rotate-control
    (fn [{:keys [member-init-keys member-init-keys-log member-aid->member-init-keys member-init-key->init-key] :as db} {:event/keys [creator] :as evt} [_ signing-key next-signing-key-hash]]
      (let [member-init-key (nth member-init-keys-log creator)
            init-key        (-> member-init-key member-init-key->init-key (or (throw (ex-info "no init key found on :rotate-control" {:db db :evt evt}))))]
        (-> db
            ;; direct mode assumed / no consensus-only members, where consensus keys = controlling keys
            (update-in [:init-key->known-control init-key] (fn [known-control]
                                                             (-> known-control
                                                                 (update :known-control/keys conjv signing-key)
                                                                 (assoc :known-control/next-key-hash next-signing-key-hash))))))))

(hg/reg-tx-handler! :assoc-did-peer
    (fn [{:keys [member-init-keys-log member-init-key->init-key] :as db} {:event/keys [creator]} [_ did-peer]]
      (let [member-init-key (nth member-init-keys-log creator)
            init-key        (-> member-init-key member-init-key->init-key)]
        (assoc-in db [:init-key->did-peer init-key] did-peer))))


(defn db->ready-to-incept? [{:keys [ke member-init-keys member-init-key->init-key init-key->known-control init-key->did-peer]}]
  (and (nil? ke)
       member-init-key->init-key
       init-key->known-control
       init-key->did-peer
       (->> member-init-keys (every? member-init-key->init-key))
       (->> member-init-keys (every? (comp init-key->known-control member-init-key->init-key))) ;; control has been set
       (->> member-init-keys (every? (comp init-key->did-peer member-init-key->init-key))))) ;; did-peer has been set

(defn db->signing-keys [{:keys [member-init-keys member-init-key->init-key] :as db}]
  (let [init-key->signing-key (db->init-key->signing-key db)]
    (->> member-init-keys
         (map member-init-key->init-key)
         (mapv init-key->signing-key))))

(defn db->wanted-to-rotate? [{:keys [ke member-aids$ member-aid$->member-init-keys member-init-keys member-init-key->init-key init-key->known-control init-key->did-peer] :as db}]
  (and (some? ke)
       member-aids$
       member-aid$->member-init-keys
       (->> member-aids$ (every? member-aid$->member-init-keys))
       member-init-key->init-key
       init-key->known-control
       (->> member-init-keys (every? member-init-key->init-key))
       (->> member-init-keys (every? (comp init-key->known-control member-init-key->init-key)))
       (let [signing-keys (-> db db->signing-keys)]
         (not= signing-keys (-> ke ->latest-establishment-key-event :key-event/signing-keys)))
       init-key->did-peer
       (->> member-init-keys (every? (comp init-key->did-peer member-init-key->init-key)))))

(defn db->all-rotation-commitments-fulfilled? [{:keys [rotation-commitments fulfilled-rotation-commitments]}]
  (= rotation-commitments fulfilled-rotation-commitments))

(defn sc-bump-received-r [db {:received-event/keys [r]}]
  (update db :received-r max r))

;; TODO rotation commitment, so not every key needs to unblind, they may be offline, hey!
(defn db->ready-to-rotate? [{:keys [ke member-aids member-aid->member-init-keys member-init-keys member-init-key->init-key init-key->known-control init-key->did-peer received-r] :as db}]
  (and (-> db db->wanted-to-rotate?)
       (let [signing-keys (-> db db->signing-keys)]
         (ke+agreed-keys->next-threshold-satisfied? ke signing-keys))))

(defn db->next-signing-key-hashes [{:keys [member-init-keys member-init-key->init-key] :as db}]
  (let [init-key->next-signing-key-hash (-> db db->init-key->next-signing-key-hash)]
    (->> member-init-keys
         (map member-init-key->init-key)
         (mapv init-key->next-signing-key-hash))))

(defn db->threshold [{:keys [member-aids$ member-aid$->signing-weight member-aid$->threshold]}]
  (->> member-aids$
       (mapcat (fn [member-aid$]
                 [{(member-aid$->signing-weight member-aid$) (member-aid$->threshold member-aid$)}]))
       vec))

(defn sc-incept [{:keys [threshold] :as db} {:received-event/keys [received-time] :as re}]
  (cond-> db
    (-> db db->ready-to-incept?)
    (assoc :ke (let [signing-keys      (db->signing-keys db)
                     next-signing-keys (db->next-signing-key-hashes db)]
                 {:key-event/type              :inception
                  :key-event/signing-keys      signing-keys
                  :key-event/threshold         threshold
                  :key-event/next-signing-keys next-signing-keys
                  :key-event/next-threshold    threshold
                  :key-event/anchors           [#_{:aid/creation-time received-time}
                                                {:aid/creation-time (-> re :received-event/event hg/creation-time)}
                                                (pub-db-anchor db)]}))))

;; when do we accept rotate?
;; if we say that when we've got enough unblinded keys to sign - then we can accept before learning of other keys being unblidned
;; then we'd fall into the loop - we accept, learn others that unblinded to accept yet were not included -> unblind more -> accept subset -> unblind more ...
;; Solution: before unblinding members can consent on who's gonna unblind
;;  Problem: member who consented to unblind may refuse to do so
;;    Solution: based on timeout, initiate a new unblind request, waiting for consent from enough other peers
;; Problem: same may happen on rotation :ke, members consented to have it / unblinded, yet those unblinded may not participate (go offline)
;;  Solution: same logic, consent on adding more unblinded keys to it, so :ke can be signed
(defn sc-rotate [{:keys [threshold ke] :as db} {:received-event/keys [received-time] :as re}]
  (cond-> db
    (-> db db->ready-to-rotate?)
    (assoc :ke (let [signing-keys      (db->signing-keys db)
                     next-signing-keys (db->next-signing-key-hashes db)]
                 {:key-event/type              :rotation
                  :key-event/signing-keys      signing-keys
                  :key-event/threshold         threshold
                  :key-event/next-signing-keys next-signing-keys
                  :key-event/next-threshold    threshold
                  :key-event/anchors           [(pub-db-anchor db)]
                  :key-event/prior             ke}))))

(defn* ^:memoizing aid->creation-time [aid]
  (->> aid :key-event/anchors (some :aid/creation-time)))

(defn parent-depth+seed->avatar [parent-depth seed]
  (case parent-depth
    0 hga-avatars/computer
    1 (case (rem seed 2)
        0 hga-avatars/male-avatar
        1 hga-avatars/female-avatar)
    hga-avatars/group))

(defn creation-time->seed [creation-time]
  (-> creation-time str last int inc))

(defn* ^:memoizing aid->seed [aid]
  (-> aid aid->creation-time creation-time->seed))

(deftest aid->seed-test
  (let [aid-a1 {:key-event/anchors [{:aid/creation-time 1234}]}
        aid-a2 {:key-event/anchors [{:aid/creation-time 4321}]}]
    (is (= 5 (aid->seed aid-a1))
        (= 2 (aid->seed aid-a2)))))

(defn* ^:memoizing aid->color [aid]
  (case (some-> aid ke->pub-db :topic-name first str)
    "A" (hg-members/palette1 1)
    "B" (hg-members/palette1 2)
    "C" (hg-members/palette1 3)
    "D" (hg-members/palette1 4)
    "E" (hg-members/palette1 5)
    (->> aid aid->seed (get hg-members/palette1))))
(set! hga-page/aid->color aid->color)

;; A1               A2
;; E init           E init
;; E did  KE icp    E did  KE icp
;;
;;            A
;;            E connect               <- :member-aids :member-aid->member-init-keys :member-init-keys :member-aid->init-keys :member-init-key->did-peer
;;       E inform                     <- :member-aid->ke :member-init-key->init-key ...

(defn mix-colors
  "A safer version of gc/mix that also works with one color, returning it unchanged."
  [& colors]
  (if (= 1 (count colors))
    (first colors)
    (apply gc/mix colors)))

;; key may have been removed, yet if there are pending events from it - show as disabled member, preserving idx; when no more events left - remove member from contributing to idx
(defn ?ke+db->member-aid-info [?ke {:keys [topic-name member-aids$-log aid$->ke member-aids$ member-aid$->signing-weight member-aid$->member-init-keys member-aid$->member-init-keys-log member-init-key->init-key] :as db}
                               status stake stake-map child-depth
                               member-init-keys-log active-member-init-keys pending-member-init-keys eligible-member-init-keys ;; present? pending->carry
                               & [?parent-member-init-key->member-init-key ?prev-leaf-pos-x]]
  (let [alias                (or (some-> ?ke ke->pub-db :topic-name)
                                 topic-name
                                 "???")
        ?aid                 (-> ?ke ke->ke-icp)
        base-member-aid-info (cond-> {:member-aid-info/alias       alias
                                      :member-aid-info/status      status
                                      :member-aid-info/stake       stake
                                      :member-aid-info/child-depth child-depth}
                               ?aid (assoc :member-aid-info/aid#          (-> ?aid hash)
                                           :member-aid-info/creation-time (-> ?aid aid->creation-time)))]
    (if-let [member-init-key (when (and (empty? member-aids$) (= 1 (count eligible-member-init-keys)))
                               (first eligible-member-init-keys))]
      (let [creator              (-indexOf member-init-keys-log member-init-key)
            leaf-member-aid-info (-> base-member-aid-info
                                     (assoc :member-aid-info/member-init-key member-init-key
                                            :member-aid-info/creator         creator
                                            :member-aid-info/pos-x           (or (some-> ?prev-leaf-pos-x inc) 0)
                                            :member-aid-info/max-leaf-pos-x  (or (some-> ?prev-leaf-pos-x inc) 0)
                                            :member-aid-info/parent-depth    0
                                            :member-aid-info/color           (if-let [active-aid (and (= :active status)
                                                                                                      (-> ?ke ke->ke-icp))]
                                                                               (gc/rgba (conj (aid->color active-aid) 1))
                                                                               (gc/rgba [120 120 120 1])))
                                     ((fn [member-aid-info*] (assoc-in member-aid-info* [:member-aid-info/creator->member-aid-info creator] member-aid-info*))))]
        leaf-member-aid-info)
      (let [{member-aid-infos :member-aid-infos-acc
             ?max-leaf-pos-x  :max-leaf-pos-x-acc}
            (->> member-aids$-log
                 (filter aid$->ke)
                 (sort-by (comp aid->creation-time ke->ke-icp aid$->ke))
                 (reduce (fn [{:keys [member-aid-infos-acc] ?max-leaf-pos-x-acc :max-leaf-pos-x-acc :as acc} member-aid$]
                           (let [parent-member-init-key->member-init-key (if-not ?parent-member-init-key->member-init-key
                                                                           identity
                                                                           (comp ?parent-member-init-key->member-init-key (l member-init-key->init-key)))
                                 active-member-init-keys*                (->> member-aid$
                                                                 member-aid$->member-init-keys
                                                                 (map parent-member-init-key->member-init-key)
                                                                 doall
                                                                 set
                                                                 (set/intersection (set active-member-init-keys)))
                                 pending-member-init-keys*               (->> member-aid$
                                                                 member-aid$->member-init-keys-log
                                                                 (map parent-member-init-key->member-init-key)
                                                                 set
                                                                 (set/intersection (set pending-member-init-keys)))
                                 eligible-member-init-keys*              (set/union active-member-init-keys* pending-member-init-keys*)]
                             (if (empty? eligible-member-init-keys*)
                               acc
                               (let [member-aid-status (cond (not-empty active-member-init-keys*)  :active
                                                             (not-empty pending-member-init-keys*) :pending)
                                     ?member-pub-db    (when aid$->ke (some-> member-aid$ aid$->ke ke->pub-db))

                                     ?member-aid-info
                                     (?ke+db->member-aid-info (when aid$->ke (-> member-aid$ aid$->ke))
                                                              ?member-pub-db
                                                              member-aid-status
                                                              (if-not (= :active member-aid-status)
                                                                0
                                                                (-> stake
                                                                    (* (/ (first (member-aid$->signing-weight member-aid$))
                                                                          (second (member-aid$->signing-weight member-aid$))))))
                                                              stake-map
                                                              (inc child-depth)
                                                              member-init-keys-log
                                                              active-member-init-keys*
                                                              pending-member-init-keys*
                                                              eligible-member-init-keys*
                                                              parent-member-init-key->member-init-key
                                                              ?max-leaf-pos-x-acc)
                                     ?max-leaf-pos-x (or (:member-aid-info/max-leaf-pos-x ?member-aid-info) ?max-leaf-pos-x-acc)]
                                 (cond-> {:member-aid-infos-acc (conj member-aid-infos-acc ?member-aid-info)}
                                   ?max-leaf-pos-x (assoc :max-leaf-pos-x-acc ?max-leaf-pos-x))))))
                         (cond-> {:member-aid-infos-acc []}
                           ?prev-leaf-pos-x (assoc :max-leaf-pos-x-acc ?prev-leaf-pos-x))))

            ?creator->member-aid-info    (apply merge (map :member-aid-info/creator->member-aid-info member-aid-infos))
            undisclosed-member-init-keys (sort (set/difference eligible-member-init-keys
                                                                    (set (map :member-aid-info/member-init-key (vals ?creator->member-aid-info)))))
            undisclosed-member-aid-infos (->> undisclosed-member-init-keys
                                                   (map-indexed (fn [idx undisclosed-member-init-key]
                                                                  (let [undisclosed-member-init-key-status (cond (active-member-init-keys undisclosed-member-init-key)  :active
                                                                                                                 (pending-member-init-keys undisclosed-member-init-key) :pending)
                                                                        undisclosed-creator                (-indexOf member-init-keys-log undisclosed-member-init-key)
                                                                        undisclosed-member-aid-info*
                                                                        {:member-aid-info/member-init-key undisclosed-member-init-key
                                                                         :member-aid-info/creator         undisclosed-creator
                                                                         :member-aid-info/undisclosed?    true
                                                                         :member-aid-info/pos-x           (or (some-> ?max-leaf-pos-x (+ (inc idx))) idx)
                                                                         :member-aid-info/max-leaf-pos-x  (or (some-> ?max-leaf-pos-x (+ (inc idx))) idx)
                                                                         :member-aid-info/alias           "???"
                                                                         :member-aid-info/status          undisclosed-member-init-key-status
                                                                         :member-aid-info/stake           (if (= :pending undisclosed-member-init-key-status)
                                                                                                            0
                                                                                                            (-> undisclosed-creator stake-map))
                                                                         :member-aid-info/parent-depth    0
                                                                         :member-aid-info/child-depth     (inc child-depth)
                                                                         :member-aid-info/color           (gc/rgba [120 120 120 1])}]
                                                                    (assoc-in undisclosed-member-aid-info* [:member-aid-info/creator->member-aid-info undisclosed-creator] undisclosed-member-aid-info*)))))
            all-member-aid-infos         (vec (concat member-aid-infos undisclosed-member-aid-infos))]
        (if (and (empty? member-aid-infos) (= 1 (count undisclosed-member-aid-infos)))
          (let [undisclosed-parent-member-info (merge (first undisclosed-member-aid-infos) base-member-aid-info)] ;; bumps down :child-depth
            undisclosed-parent-member-info)
          (let [parent-member-aid-info (-> base-member-aid-info
                                           (assoc :member-aid-info/member-aid-infos all-member-aid-infos
                                                  :member-aid-info/creator->member-aid-info (->> all-member-aid-infos (map :member-aid-info/creator->member-aid-info) (filter some?) (apply merge))
                                                  :member-aid-info/pos-x          (-> (->> all-member-aid-infos (map :member-aid-info/pos-x) (reduce +))
                                                                                      (/ (count all-member-aid-infos)))
                                                  :member-aid-info/max-leaf-pos-x (-> all-member-aid-infos last :member-aid-info/max-leaf-pos-x)
                                                  :member-aid-info/parent-depth   (->> all-member-aid-infos (map :member-aid-info/parent-depth) (apply max) inc)
                                                  :member-aid-info/color          (or (some->> all-member-aid-infos
                                                                                               (remove :member-aid-info/undisclosed?)
                                                                                               (filter (comp #{:active} :member-aid-info/status))
                                                                                               not-empty
                                                                                               (map :member-aid-info/color)
                                                                                               (apply mix-colors))
                                                                                      (gc/rgba [120 120 120 1]))))]
            parent-member-aid-info))))))


(def db-establishment-keys [:topic-name :ke :aids#-log :aid$->ke :member-aids$ :member-aid$->member-init-keys :member-aid$->signing-weight])

(defn* ^:memoizing cr->cr-est [cr]
  (let [?prev-cr (:concluded-round/prev-concluded-round cr)]
    (cond (nil? ?prev-cr)
          cr
          (let [prev-db (-> ?prev-cr hg/cr->db)
                db      (-> cr hg/cr->db)]
            (->> db-establishment-keys
                 (some (fn [db-establishment-key]
                         (not (hash= (get prev-db db-establishment-key) (get db db-establishment-key)))))))
          cr
          :else
          (-> ?prev-cr cr->cr-est))))

(declare evt->?ke)
(defn* ^:memoizing cr-est+pending-member-init-keys#->member-aid-info [cr-est pending-member-init-keys#]
  (let [db-est                     (-> cr-est hg/cr-db)
        ?ke-est                    (-> db-est :ke)
        stake-map-est              (-> db-est :stake-map)
        active-member-init-keys#   (-> db-est :member-init-keys set)
        eligible-member-init-keys# (set/union active-member-init-keys# pending-member-init-keys#)
        member-init-keys-log       (-> db-est :member-init-keys-log)]
    (?ke+db->member-aid-info ?ke-est db-est :active hg/total-stake stake-map-est 0 member-init-keys-log active-member-init-keys# pending-member-init-keys# eligible-member-init-keys#)))


(defn db-with-aid-control-propagated [{:keys [aid$->ke member-aids$ member-aids$-log member-aid$->member-init-keys member-aid$->member-init-keys-log member-aid$->threshold
                                              member-init-keys-log
                                              member-init-key->did-peer]
                                       :or   {member-aids$-log                  []
                                              member-aid$->member-init-keys-log (hash-map)
                                              member-init-keys-log              []}
                                       :as   db}]
  (if (empty? member-aids$)
    db
    (let [new-member-aids$-log              (vec-union member-aids$-log member-aids$)
          new-member-aid$->member-init-keys (merge (select-keys member-aid$->member-init-keys member-aids$)
                                                   (->> (select-keys aid$->ke member-aids$)
                                                        (filter-map-vals (fn [member-ke]
                                                                           (when-let* [member-db                    (-> member-ke ke->pub-db)
                                                                                       member-init-key->signing-key (-> member-db db->init-key->signing-key)
                                                                                       signing-key->member-init-key (-> member-init-key->signing-key reverse-map)
                                                                                       signing-keys                 (-> member-ke ->latest-establishment-key-event :key-event/signing-keys)]
                                                                                      (->> signing-keys
                                                                                           (map signing-key->member-init-key)
                                                                                           (filter some?)
                                                                                           vec
                                                                                           not-empty))))))

          new-member-aid$->member-init-keys-log (merge-with vec-union
                                                            member-aid$->member-init-keys-log
                                                            new-member-aid$->member-init-keys)

          new-member-aid$->signing-weight (->> member-aids$
                                               (into (hash-map) (map (fn [member-aid] [member-aid (case (count member-aids$)
                                                                                                    1 [1 1]
                                                                                                    [1 2])]))))


          new-member-aid$->threshold (merge (select-keys member-aid$->threshold member-aids$)
                                            (->> (select-keys aid$->ke member-aids$)
                                                 (filter-map-vals (fn [ke] (-> ke ->latest-establishment-key-event :key-event/threshold)))))

          new-threshold   (db->threshold {:member-aids$                member-aids$
                                          :member-aid$->signing-weight new-member-aid$->signing-weight
                                          :member-aid$->threshold      new-member-aid$->threshold})
          #_#_total-stake hg/total-stake #_ (->> member-aid->signing-weight vals (reduce (fn [acc [nom denom]] (+ acc (/ nom denom))) 0))

          new-member-init-keys     (->> member-aids$ (mapcat new-member-aid$->member-init-keys) vec)
          new-member-init-keys-log (vec-union member-init-keys-log new-member-init-keys)

          fraction-paths                (-> new-threshold threshold->fractions-paths) ;; remove?
          key-weights                   (-> new-threshold threshold->key-weights)
          total-keys-weight             (->> key-weights (reduce +))
          rel-key-weights               (->> key-weights (mapv (fn [key-weight] (/ key-weight total-keys-weight))))
          new-active-creators           (->> new-member-init-keys (into #{} (map (fn [member-init-key] (-> (-indexOf new-member-init-keys-log member-init-key) not-neg (or (throw (ex-info "can't find member-init-key in member-init-keys-log on db-propagation" {:member-init-key member-init-key :new-member-init-keys-log new-member-init-keys-log :db db}))))))))
          new-active-creator->stake     (->> new-active-creators
                                             (into (hash-map) (map (fn [new-active-creator]
                                                                     (let [rel-key-weight (nth rel-key-weights (-indexOf new-member-init-keys (nth new-member-init-keys-log new-active-creator)))
                                                                           stake          (-> hg/total-stake (* rel-key-weight))]
                                                                       [new-active-creator stake])))))
          new-member-init-key->did-peer (or (merge member-init-key->did-peer
                                                   (->> (select-keys aid$->ke member-aids$)
                                                        (map (fn [[_ member-ke]] (-> member-ke ke->pub-db :init-key->did-peer)))
                                                        (apply merge)))
                                            (hash-map))
          db-propagation                {:member-aids$-log                  new-member-aids$-log
                                         :member-aid$->member-init-keys     new-member-aid$->member-init-keys
                                         :member-aid$->member-init-keys-log new-member-aid$->member-init-keys-log
                                         :member-aid$->signing-weight       new-member-aid$->signing-weight
                                         :member-aid$->threshold            new-member-aid$->threshold
                                         :threshold                         new-threshold
                                         :member-init-keys                  new-member-init-keys
                                         :member-init-keys-log              new-member-init-keys-log
                                         :member-init-key->did-peer         new-member-init-key->did-peer
                                         :active-creators                   new-active-creators
                                         :stake-map                         new-active-creator->stake}]
      (merge db db-propagation))))


(defn create-aided-topic! [my-aid-topic-path init-db*]
  (let [topic       (db-with-aid-control-propagated init-db*)
        aided-topic (-> (at/add-event! (vec (conj my-aid-topic-path topic)) {:event/topic topic})
                        (get hg/topic))]
    (reset! as/*selected-topic-path (conj my-aid-topic-path topic))
    (reset! as/*browsing {:page :topic})
    aided-topic))

(defn add-init-control-event!
  ([] (add-init-control-event! @as/*selected-topic-path))
  ([topic-path]
   (actrl/init-control! topic-path)
   (let [k  (actrl/topic-path->k topic-path)
         nk (actrl/topic-path->nk topic-path)]
     (at/add-event! topic-path {hg/tx [:init-control k (hash nk)]}))))

(deflda *topic-path->my-informed-did-peer [at/*topic-path->projected-db actrl/*topic-path->init-key]
  (fn [topic-path->projected-db topic-path->init-key]
    (->> topic-path->projected-db
         (into (hash-map)
               (comp (map (fn [[topic-path projected-db]]
                            (let [init-key (-> topic-path topic-path->init-key)]
                              [topic-path (get-in projected-db [:init-key->did-peer init-key])])))
                     (filter (comp some? second)))))))


(declare init-control-participated?)
(deflda *control-participated-topic-paths [as/*topic-path->tip-taped]
  (fn [topic-path->tip-taped]
    (->> topic-path->tip-taped
         (into #{}
               (comp (filter (comp init-control-participated? second))
                     (map first))))))

(deflda *topic-path->novel-did-peer [as/*topic-path->did-peer *topic-path->my-informed-did-peer *control-participated-topic-paths]
  (fn [topic-path->did-peer topic-path->my-informed-did-peer control-participated-topic-paths]
    (l [:*topic-path->novel-did-peer topic-path->did-peer topic-path->my-informed-did-peer control-participated-topic-paths])
    (->> topic-path->did-peer
         (into (hash-map)
               (filter (fn [[topic-path did-peer]]
                         (and (control-participated-topic-paths topic-path) ;; ugly, (for cases when [:device-topic] haven't been created yet, but we got mailbox ready)
                              (not= did-peer (-> topic-path topic-path->my-informed-did-peer)))))))))

(add-watch *topic-path->novel-did-peer ::inform-of-novel-did-peer
           (fn [_ _ _ topic-path->novel-did-peer]
             (l [::inform-of-novel-did-peer topic-path->novel-did-peer])
             (when (not-empty topic-path->novel-did-peer)
               (swap! as/*topic-path->tip-taped
                      (fn [topic-path->tip-taped]
                        ;; events are added atomically, to not trigger watches on partialy updated state
                        ;; E.g., were there 2 novel-did-peers:
                        ;; 1. (cb1) triggered
                        ;; 2. (cb1) 1st assoced
                        ;; 3. (cb2) triggered (as *topic-path->my-informed-did-peer changed)
                        ;; 4. (cb2) 2nd assoced
                        ;; 5. (cb1) 2nd assoced
                        (->> topic-path->novel-did-peer
                             (reduce (fn [topic-path->tip-taped-acc [topic-path novel-did-peer]]
                                       (at/add-event topic-path->tip-taped-acc topic-path {hg/tx [:assoc-did-peer novel-did-peer]}))
                                     topic-path->tip-taped)))))))

;; alternative
#_
(add-watch as/*topic-path->did-peer ::assoc-did-peer-once-created
           (fn [_ _ old-topic-path->did-peer new-topic-path->did-peer]
             (let [novel-topic-path->did-peer (->> new-topic-path->did-peer
                                                   (filter (fn [[topic-path did-peer]] (not= did-peer (get old-topic-path->did-peer topic-path)))))]
               (for [[topic-path did-peer] novel-topic-path->did-peer]
                 (at/add-event! topic-path {hg/tx [:assoc-did-peer did-peer]})))))

(defn add-rotate-control-event!
  ([] (add-init-control-event! @as/*selected-topic-path))
  ([topic-path]
   (actrl/rotate-control! topic-path)
   (let [k  (actrl/topic-path->k topic-path)
         nk (actrl/topic-path->nk topic-path)]
     (at/add-event! topic-path {:event/tx [:rotate-control k (hash nk)]}))))

(defn incepted? [event]
  (-> event evt->?ke some?))

(defn* ^:memoizing init-control-participated? [event]
  (or (some-> (hg/self-parent event) init-control-participated?)
      (some-> (:event/tx event) first (= :init-control))))

(defn* ^:memoizing init-control-initiated? [event]
  (or (some-> (hg/self-parent event) init-control-initiated?)
      (some-> (hg/other-parent event) init-control-initiated?)
      (some-> (:event/tx event) first (= :init-control))))

(defn* ^:memoizing assoced-did-peer? [event]
  (or (some-> (hg/self-parent event) assoced-did-peer?)
      (some-> (:event/tx event) first (= :assoc-did-peer))))



(let [A1-ke0 {:key-event/type              :inception
              :key-event/signing-keys      ["A1-key0"]
              :key-event/threshold         [[1 1]]
              :key-event/next-signing-keys [(hash "A1-key1")]
              :key-event/next-threshold    [[1 1]]
              :key-event/anchors           [{:aid/creation-time 0}
                                            {:aid/pub-db {:topic-name "A1"}} #_{:aid/name "A1"}]}
      A1-ke1 {:key-event/type    :interaction
              :key-event/anchors [{:aid/pub-db {:topic-name         "A1"
                                                :init-key->did-peer {"A1-key0" "A1-did-peer0"}}}
                                  #_{:aid/init-key->did-peer {"A1-key0" "A1-did-peer0"}}]}

      A2-ke0 {:key-event/type              :inception
              :key-event/signing-keys      ["A2-key0"]
              :key-event/threshold         [[1 1]]
              :key-event/next-signing-keys [(hash "A2-key1")]
              :key-event/next-threshold    [[1 1]]
              :key-event/anchors           [{:aid/creation-time 1}
                                            {:aid/pub-db {:topic-name "A2"}} #_{:aid/name "A2"}]}
      A2-ke1 {:key-event/type    :interaction
              :key-event/anchors [{:aid/pub-db {:topic-name         "A2"
                                                :init-key->did-peer {"A2-key0" "A2-did-peer0"}}}
                                  #_{:aid/init-key->did-peer {"A2-key0" "A2-did-peer0"}}]}

      A-db0 {:topic-name                     "A"
             ;; :topic-kind                     :group-aid
             :member-aids                    [A1-ke0 A2-ke0] ;; aligned with :key-event/threshold ?
             :member-aid->ke                 {A1-ke0 A1-ke1
                                              A2-ke0 A2-ke1}
             :member-aid->signing-weight     {A1-ke0 [1 2]
                                              A2-ke0 [1 2]}
             :member-init-key->known-control {"A1-key0" {:known-control/keys          ["A1-A-key0"]
                                                         :known-control/next-key-hash (hash "A1-A-key1")}
                                              "A2-key0" {:known-control/keys          ["A2-A-key0"]
                                                         :known-control/next-key-hash (hash "A2-A-key1")}}
             :member-init-key->did-peer      {"A0-key0" "A0-did-peer0"
                                              "A1-key0" "A1-did-peer0"} ;; to use internally ;; kinda known from member-aid->ke, if member-aid discloses (and otherwise we wouldn't know how to reach it) ;; but could be overwritteng
             :init-key->did-peer             {"A0-A-key0" "A0-A-did-peer0"
                                              "A1-A-key0" "A1-A-did-peer0"} ;; to disclose and use externally
             #_#_:total-stake                    1
             :stake-map                      {"A0-key0" (/ 1 2)
                                              "A1-key0" (/ 1 2)}}
      A-ke0 {:key-event/type              :inception
             :key-event/signing-keys      ["A1-A-key0" "A2-A-key0"]
             :key-event/threshold         [{[1 2] [[1 1]]}
                                           {[1 2] [[1 1]]}]
             :key-event/next-signing-keys [(hash "A1-A-key1") (hash "A2-A-key1")]
             :key-event/next-threshold    [{[1 2] [[1 1]]}
                                           {[1 2] [[1 1]]}]
             :key-event/anchors           [{:aid/creation-time 2}
                                           (pub-db-anchor A-db0)
                                           #_#_#_
                                           {:aid/name "A"}
                                           {:aid/member-aids [A1-ke0 A2-ke0]}
                                           {:aid/member-aid->ke {A1-ke0 A1-ke1
                                                                 A2-ke0 A2-ke1}}

                                           ;; I like this way more due to how it's up to member aid to disclose further mapping,
                                           ;; eventually it's up to device aid to disclose, confirming, the first mapping
                                           #_
                                           {:aid/init-key->member-init-key {"A1-A-key0" "A1-key0"
                                                                            "A2-A-key0" "A2-key0"}}
                                           #_
                                           {:aid/init-key->aid {"A1-A-key0" A1-ke0
                                                                "A2-A-key0" A2-ke0}}
                                           #_
                                           {:aid/init-key->did-peer {"A0-A-key0" "A0-did-peer0"
                                                                     "A1-A-key0" "A1-did-peer0"}}]}
      A-db0-post {assoc A-db0 :ke A-ke0}


      B1-ke0 {:key-event/type              :inception
              :key-event/signing-keys      ["B1-key0"]
              :key-event/threshold         [[1 1]]
              :key-event/next-signing-keys [(hash "B1-key1")]
              :key-event/next-threshold    [[1 1]]
              :key-event/anchors           [{:aid/creation-time 3}
                                            {:aid/pub-db {:topic-name "B1"}} #_{:aid/name "B1"}]}
      B1-ke1 {:key-event/type    :interaction
              :key-event/anchors [{:aid/pub-db {:topic-name         "B1"
                                                :init-key->did-peer {"B1-key0" "B1-did-peer0"}}}
                                  #_{:aid/init-key->did-peer {"B1-key0" "B1-did-peer0"}}]}

      AB-db0 {:topic-name                     "AB"
              :member-aids                    [A-ke0 B1-ke1]
              :member-aid->ke                 {A-ke0  A-ke0
                                               B1-ke0 B1-ke1}
              :member-aid->signing-weight     {A-ke0  [1 2]
                                               B1-ke0 [1 2]}
              ;; :member-init-keys               ["A1-A-key0" "A2-A-key0" "B1-key0"] ;; known from :stake-map ;; or have it ever-growing, stable idxes?
              :member-init-key->known-control {"A1-A-key0" {:known-control/keys          ["A1-A-AB-key0"]
                                                            :known-control/next-key-hash (hash "A1-A-AB-key1")}
                                               "A2-A-key0" {:known-control/keys          ["A2-A-AB-key0"]
                                                            :known-control/next-key-hash (hash "A2-A-key1")}
                                               "B1-key0"   {:known-control/keys          ["B1-AB-key0"]
                                                            :known-control/next-key-hash (hash "B1-AB-key1")}}
              :member-init-key->did-peer      {"A0-A-key0" "A0-did-peer0" ;; TODO did peer assoc somewhere in hierarchy
                                               "A1-A-key0" "A1-did-peer0"
                                               "B1-key0"   "B1-did-peer0"}
              #_#_:total-stake                    1
              :stake-map                      {"A0-A-key0" (-> 1 (/ 2) (/ 2))
                                               "A1-A-key0" (-> 1 (/ 2) (/ 2))
                                               "B1-key0"   (-> 1 (/ 2))}}
      AB-ke0 {:key-event/type              :inception
              :key-event/signing-keys      ["A1-A-AB-key0" "A2-A-AB-key0" "B1-AB-key0"]
              :key-event/threshold         [{[1 2] [{1 2} [[1 1]]
                                                    {1 2} [[1 1]]]}
                                            {[1 2] [[1 1]]}]
              :key-event/next-signing-keys [(hash "A1-A-AB-key1") (hash "A2-A-AB-key1") (hash "B1-AB-key1")]
              :key-event/next-threshold    [{[1 2] [{1 2} [[1 1]]
                                                    {1 2} [[1 1]]]}
                                            {[1 2] [[1 1]]}]
              :key-event/anchors           [{:aid/creation-time 4}
                                            (pub-db-anchor AB-db0)
                                            #_#_#_
                                            {:aid/name "AB"}
                                            {:aid/member-aids [A-ke0 B1-ke0]}
                                            {:aid/member-aid->ke {A-ke0  A-ke0
                                                                  B1-ke0 B1-ke1}}

                                            ;; I like this way more due to how it's up to member aid to disclose further mapping,
                                            ;; eventually it's up to device aid to disclose, confirming, the first mapping
                                            #_
                                            {:aid/init-key->member-init-key {"A1-A-AB-key0" "A1-A-key0"
                                                                             "A2-A-AB-key0" "A2-A-key0"
                                                                             "B1-AB-key0"   "B1-AB-key0"}}
                                            #_{:aid/init-key->aid {"A1-A-key0" A1-ke0
                                                                   "A2-A-key0" A2-ke0}}

                                            #_
                                            {:aid/init-key->did-peer {"A1-A-AB-key0" "A1-did-peer0"
                                                                      "A2-A-AB-key0" "A2-did-peer0"
                                                                      "B1-AB-key0"   "B1-did-peer0"}}]}
      AB-db0-post {assoc AB-db0 :ke AB-ke0}

      AB-db0-post-member-info {:member-aid-info/aid              AB-ke0
                               :member-aid-info/pos-x            (-> 0.5 (+ 2) (/ 2))
                               :member-aid-info/max-leaf-pos-x   2
                               :member-aid-info/pos-y            1
                               :member-aid-info/status           :active
                               :member-aid-info/stake            1
                               :member-aid-info/name             "AB"
                               :member-aid-info/member-aid-infos [{:member-aid-info/aid              A-ke0
                                                                   :member-aid-info/creation-time    2
                                                                   :member-aid-info/pos-x            (-> 0 (+ 1) (/ 2))
                                                                   :member-aid-info/max-leaf-pos-x   1
                                                                   :member-aid-info/pos-y            1
                                                                   :member-aid-info/status           :active
                                                                   :member-aid-info/stake            (-> 1 (/ 2))
                                                                   :member-aid-info/name             "A"
                                                                   :member-aid-info/member-aid-infos [{:member-aid-info/aid             A1-ke0
                                                                                                       :member-aid-info/creation-time   0
                                                                                                       :member-aid-info/pos-x           0
                                                                                                       :member-aid-info/max-leaf-pos-x  0
                                                                                                       :member-aid-info/pos-y           2
                                                                                                       :member-aid-info/status          :active
                                                                                                       :member-aid-info/stake           (-> 1 (/ 2) (/ 2))
                                                                                                       :member-aid-info/name            "A1"
                                                                                                       :member-aid-info/member-init-key "A1-A-key0"}
                                                                                                      {:member-aid-info/aid             A2-ke0
                                                                                                       :member-aid-info/creation-time   1
                                                                                                       :member-aid-info/pos-x           1
                                                                                                       :member-aid-info/max-leaf-pos-x  1
                                                                                                       :member-aid-info/pos-y           2
                                                                                                       :member-aid-info/status          :active
                                                                                                       :member-aid-info/stake           (-> 1 (/ 2) (/ 2))
                                                                                                       :member-aid-info/name            "A2"
                                                                                                       :member-aid-info/member-init-key "A2-A-ke0"}]}
                                                                  {:member-aid-info/aid             B1-ke0
                                                                   :member-aid-info/creation-time   3
                                                                   :member-aid-info/pos-x           2
                                                                   :member-aid-info/max-leaf-pos-x  2
                                                                   :member-aid-info/pos-y           1
                                                                   :member-aid-info/status          :active
                                                                   :member-aid-info/stake           (-> 1 (/ 2))
                                                                   :member-aid-info/name            "B1"
                                                                   :member-aid-info/member-init-key "B1-key0"}]}


      ke1  {:key-event/type              :inception
            :key-event/signing-keys      ["a0"]
            :key-event/next-signing-keys [(hash "a1")]
            :key-event/anchors           [{:aid/creation-time 10}
                                          {:aid/name "group"}
                                          {:aid/init-key->did-peer {"a0" "a0-did-peer"}}]}
      ke2  {:key-event/type              :rotation
            :key-event/signing-keys      ["a1" "b0"] ;; adding member
            :key-event/next-signing-keys [(hash "a2") (hash "b1")] ;; next keys would need to be in order, if we wish to derive key chains, however, that would leak correlation
            :key-event/anchors           [{:aid/init-key->did-peer {"b0" "b0-did-peer"}}]
            :key-event/prior             ke1}
      ke3  {:key-event/type              :rotation
            :key-event/signing-keys      ["c0" "b1" "a2"]  ;; adding member, out-of-order
            :key-event/next-signing-keys [(hash "b2") (hash "c1") (hash "a3")]
            :key-event/anchors           [{:aid/init-key->did-peer {"c0" "c0-did-peer"}}]
            :key-event/prior             ke2}
      ke4  {:key-event/type              :rotation
            :key-event/signing-keys      ["b2" "c1"] ;; removing member, out-of-order
            :key-event/next-signing-keys [(hash "b3") (hash "c2")]
            :key-event/anchors           []
            :key-event/prior             ke3}
      ke5  {:key-event/type              :rotation
            :key-event/signing-keys      ["d0" "b3"] ;; removing member & adding member, out-of-order
            :key-event/next-signing-keys [(hash "d1") (hash "b4")] ;; out-of-order
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

  #_
  (deftest ke->init-key->known-control-keys-test
    (are [ke res] (= res (-> ke ke->pub-db pub-db->init-key->known-control (map-vals :known-control/keys)))
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

  #_
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

  #_
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

  #_
  (deftest ke->reachable-active-init-keys-test
    (are [ke res] (= res (ke->reachable-active-init-keys ke))
      ke1  #{"a0"}
      ke2  #{"a0" "b0"}
      ke3  #{"a0" "b0" "c0"}
      ke4  #{"b0" "c0"}
      ke5  #{"b0" "d0"}
      ke6  #{"b0" "d0"}
      ke7  #{"b0" "d0"}
      ke8  #{"b0" "d0"} ;; no e0, as it does not have a mailbox
      ke9  #{"b0" "d0"}
      ke10 #{"a0" "b0" "d0"})))


(defn init-key->known-control+key->init-key [init-key->known-control key]
  (->> init-key->known-control
       (some (fn [[init-key {:known-control/keys [keys next-key-hash] :as known-control}]]
               (when (or (= (hash key) next-key-hash)
                         (contains? (set keys) key))
                 init-key)))))

(hg/reg-tx-handler! :propose
    (fn [db {:event/keys [creator]} [_ proposal]]
      (-> db
          (update-in [:proposal->agreed-creators proposal] conjs creator)
          (update-in [:proposal->disagreed-creators proposal] disjs creator))))

(hg/reg-tx-handler! :dispose
    (fn [db {:event/keys [creator]} [_ proposal]]
      (-> db
          (update-in [:proposal->agreed-creators proposal] disjs creator)
          (update-in [:proposal->disagreed-creators proposal] conjs creator))))

;; TODO clear accepted incept proposal
(defn sc-anchor-accepted-issue-proposals [{:keys [proposal->agreed-creators member-init-keys-log signing-key->next-signing-key-hash threshold ke member-init-key->init-key] :as db} _]
  (if (nil? ke)
    db
    (let [acceptable-proposals (->> proposal->agreed-creators
                                    (remove (comp (into (set (:accepted-proposals db)) (set (:rejected-proposals db))) first))
                                    (filter (fn [[[proposal-kind :as proposal] agreed-creators]]
                                              (and (= :issue proposal-kind)
                                                   (let [init-key->signing-key (db->init-key->signing-key db)
                                                         agreed-signing-keys
                                                         (->> agreed-creators
                                                              (set/intersection (-> db :active-creators))
                                                              (map member-init-keys-log)
                                                              (map member-init-key->init-key)
                                                              (map init-key->signing-key)
                                                              (set))]
                                                     (ke+agreed-keys->threshold-satisfied? ke agreed-signing-keys)))))
                                    (map first)
                                    doall)
          acceptable-acdcs*    (->> acceptable-proposals
                                    (map (fn [[_ & acdcs*]] acdcs*))
                                    (reduce into []))
          issuer-aid           (ke->ke-icp ke)
          ?acdc->te            (when (not-empty acceptable-acdcs*)
                                 (->> acceptable-acdcs*
                                      (into (hash-map) (map (fn [acceptable-acdc*]
                                                              (let [te1  {:tx-event/type   :inception
                                                                          :tx-event/issuer issuer-aid}
                                                                    acdc (assoc acceptable-acdc*
                                                                                :acdc/registry te1)
                                                                    te2  {:tx-event/type      :update
                                                                          :tx-event/attribute {:ts :issued}
                                                                          :tx-event/prior     te1}]
                                                                [acdc te2]))))))]
      ;; TODO revokation
      ;; TODO create ke in last sc, so it can be a ke-rot with anchors
      (cond-> db
        (not-empty acceptable-proposals)
        (update :accepted-proposals (fn [?accepted-proposals] (into (or ?accepted-proposals #{}) acceptable-proposals)))

        (some? ?acdc->te)
        (-> (assoc :ke {:key-event/type    :interaction
                        :key-event/anchors (vec (concat (keys ?acdc->te) (vals ?acdc->te)))
                        :key-event/prior   ke})
            (update :acdcs set/union (set (keys ?acdc->te))))))))

(defn sc-dispose-proposals [{:keys [proposal->disagreed-creators member-init-keys-log signing-key->next-signing-key-hash threshold ke member-init-key->init-key] :as db} _]
  (if (nil? ke)
    db
    (let [disposable-proposals (->> proposal->disagreed-creators
                                    (remove (comp (into (set (:accepted-proposals db)) (set (:rejected-proposals db))) first))
                                    (filter (fn [[[_ proposal-kind :as proposal] disagreed-creators]]
                                              (and (= :issue proposal-kind)
                                                   (let [init-key->signing-key (db->init-key->signing-key db)
                                                         disagreed-signing-keys
                                                         (->> disagreed-creators
                                                              (set/intersection (-> db :active-creators))
                                                              (map member-init-keys-log)
                                                              (map member-init-key->init-key)
                                                              (map init-key->signing-key)
                                                              (set))]
                                                     (ke+agreed-keys->threshold-satisfied? ke disagreed-signing-keys)))))
                                    (map first)
                                    doall)]
      (cond-> db
        (not-empty disposable-proposals)
        (update :rejected-proposals (fn [?rejected-proposals] (into (or ?rejected-proposals #{}) disposable-proposals)))))))

(reset! hg/*smartcontracts [sc-bump-received-r sc-incept sc-rotate sc-anchor-accepted-issue-proposals sc-dispose-proposals])


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


(actrl/init-control! [:gleif])
(def gleif-k0 (actrl/topic-path->k [:gleif]))
(def gleif-k1 (actrl/topic-path->nk [:gleif]))
(def gleif-e0 {hg/creator       "gleif"
               hg/creation-time 0
               hg/topic         {:stake-map {"gleif" hg/total-stake}}
               hg/tx            [:init-control gleif-k0 (hash gleif-k1)]})

(def gleif-e1
  {hg/creator       gleif-k0
   hg/creation-time (.now js/Date)
   hg/tx            [:propose [:issue gleif-acdc-qvi]]
   hg/self-parent   gleif-e0})

(actrl/rotate-control! [:gleif])
(def gleif-k2 (actrl/topic-path->nk [:gleif]))
(def gleif-e2
  {hg/creator       gleif-k0
   hg/creation-time (.now js/Date)
   hg/tx            [:rotate gleif-k1 (hash gleif-k2)]
   hg/self-parent   gleif-e1})

(defn evt->?ke [evt] (-> evt hg/evt->db :ke))
(defn evt->?ke->k->signature [evt] (-> evt hg/evt->db :ke->k->signature))
(defn evt->?ke-icp [evt] (some-> evt evt->?ke ke->ke-icp))
(defn* ke->?aid-name [ke]
  (-> ke ke->pub-db :topic-name))

#_
(defn evt->init-key [{:event/keys [creator] :as evt}]
  (or (some-> evt
              hg/evt->db
              :init-key->known-control
              (init-key->known-control+key->init-key creator))
      creator))

#_
(defn evt->active-init-keys [evt]
  (or (when-let [{:key-event/keys [signing-keys init-key->known-control]} (-> evt evt->?ke)]
        (->> signing-keys
             (map (fn [signing-key] (init-key->known-control+key->init-key
                                     init-key->known-control
                                     signing-key)))
             (filter some?)
             (not-empty)))
      (hgt/?event->creators evt)))

#_
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

#_
(deftest evt->init-key-test
  (is (= "gleif" (evt->init-key gleif-e0)))
  (is (= "gleif" (evt->init-key gleif-e1)))
  (is (= "gleif" (evt->init-key gleif-e2))))


;; (run-tests)


(declare *topic-path->attributed-acdcs)
(declare *my-aids)
(add-watch as/*topic-path->tip-taped ::participate-in-init-control-when-in-AID-group
           (fn [_ _ _ topic-path->tip-taped]
             (doseq [[topic-path tip-taped] topic-path->tip-taped]
               (when (and (init-control-initiated? tip-taped)
                          (-> tip-taped evt->?ke) ;; only when incepted, hacky, will also trigger when new id gets incepted and you did not consent
                          (not (init-control-participated? tip-taped))
                          #_(when-let* [attributed-acdcs (get @*topic-path->attributed-acdcs topic-path)
                                        my-aids          (set @*my-aids)]
                              (->> attributed-acdcs
                                   (some (fn [acdc] (and (= :acdc-join-invite-accepted (:acdc/schema acdc))
                                                         (contains? my-aids (:acdc/issuer acdc))))))))
                 (add-init-control-event! topic-path)
                 (io/create-mailbox! (atom topic-path))))))

(add-watch as/*topic-path->tip-taped ::participate-in-wanted-rotations
           (fn [_ _ _ topic-path->tip-taped]
             (doseq [[topic-path tip-taped] topic-path->tip-taped]
               (let [db           (-> tip-taped hg/evt->db)
                     projected-db (-> tip-taped at/event->projected-db)]
                 (and (-> projected-db db->wanted-to-rotate?)
                      (let [ke-est             (-> db :ke ->latest-establishment-key-event)
                            signing-keys       (-> ke-est :key-event/signing-keys)
                            next-signing-keys  (-> ke-est :key-event/next-signing-keys)
                            my-member-init-key (actrl/topic-path->member-init-key topic-path)
                            my-k               (actrl/topic-path->k topic-path)]
                        (and (contains? (set signing-keys) my-k)
                             (when-let* [projected-member-init-key->init-key (-> projected-db :member-init-key->init-key)
                                         projected-init-key->signing-key     (-> projected-db db->init-key->signing-key)
                                         projected-my-known-init-key         (-> my-member-init-key projected-member-init-key->init-key)
                                         projected-my-known-signing-key      (-> projected-my-known-init-key projected-init-key->signing-key)]
                                        (not (contains? (set next-signing-keys) (hash projected-my-known-signing-key))))))
                      (add-rotate-control-event! topic-path))))))


(deflda *topic-path->my-ke [as/*topic-path->db]
  (filter-map-vals :ke))

(deflda *topic-path->my-aid# [*topic-path->my-ke]
  (map-vals (comp hash ke->ke-icp)))

(deflda *my-aid-topic-paths [*topic-path->my-aid#]
  (comp set keys))

(deflda *my-aid-topics [*my-aid-topic-paths]
  (fn [my-aid-topic-paths]
    (->> my-aid-topic-paths (map last) (set))))

(deflda *selected-my-aid-topic-path [as/*selected-topic-path *my-aid-topics]
  (fn [selected-topic-path my-aid-topics]
    (->> selected-topic-path
         (take-while my-aid-topics)
         (vec))))

(deflda *?selected-interactable-topic-path [as/*selected-topic-path *my-aid-topic-paths]
  (fn [selected-topic-path my-aid-topic-paths]
    (->> selected-topic-path
         (drop-while my-aid-topic-paths)
         (not-empty))))

(deflda *selected-my-aid# [*topic-path->my-aid# *selected-my-aid-topic-path] get)
(deflda *selected-my-ke [*topic-path->my-ke *selected-my-aid-topic-path] get)

(deflda *topic-path->participating-topic-paths [as/*topic-paths]
  (fn [topic-paths]
        (->> topic-paths
             (reduce (fn [acc topic-path]
                       (update acc (vec (butlast topic-path)) conjs topic-path))
                     (hash-map)))))

(deflda *topic-path->interactable-topic-paths [*topic-path->participating-topic-paths *my-aid-topic-paths]
  (fn [topic-path->participating-topic-paths my-aid-topic-paths]
    (->> topic-path->participating-topic-paths
         (map-vals (fn [participating-topic-paths]
                     (->> participating-topic-paths
                          (filter (comp not my-aid-topic-paths))
                          (set)))))))

(deflda *my-aid#->my-ke [*topic-path->my-ke]
  (fn [topic-path->my-ke]
    (->> topic-path->my-ke
         vals
         (into (hash-map) (map (fn [my-ke] [(hash (ke->ke-icp my-ke)) my-ke]))))))

(deflda *my-aids# [*my-aid#->my-ke] (comp set keys))

(deflda *topic-path->my-aid-topic-paths [*topic-path->participating-topic-paths *my-aid-topic-paths]
  (fn [topic-path->participating-topic-paths my-aid-topic-paths]
    (->> topic-path->participating-topic-paths
         (filter-map-vals (fn [participating-topic-paths]
                            (->> participating-topic-paths
                                 (filter my-aid-topic-paths)
                                 set
                                 (not-empty)))))))


(defn* ^:memoizing evt->?connect-invite-accepted-payload [evt]
  (or (some-> evt hg/self-parent evt->?connect-invite-accepted-payload)
      (when-let [root-tx (some-> evt hg/evt->root-evt :event/tx)]
        (and (= :connect-invite-accepted (first root-tx))
             (second root-tx)))))

(deflda *topic-path->contact-topic-paths [*topic-path->interactable-topic-paths]
  (filter-map-vals (fn [interactable-topic-paths]
                     (->> interactable-topic-paths
                          (filter (comp :connect-invite-accepted last))
                          (set)
                          (not-empty)))))

(deflda *contact-topic-paths [*topic-path->contact-topic-paths]
  (fn [topic-path->contact-topic-paths]
    (->> topic-path->contact-topic-paths
         vals
         (apply set/union))))

(deflda *contact-topic-path->connected-aids# [*contact-topic-paths]
  (fn [contact-topic-paths]
    (->> contact-topic-paths
         (into (hash-map) (map (fn [contact-topic-path]
                                 (let [contact-topic  (last contact-topic-path)
                                       connectee-aid# (-> contact-topic :connect-invite-accepted :connect-invite-accepted/connectee-aid#)
                                       target-aid#    (-> contact-topic :connect-invite-accepted :connect-invite-accepted/connect-invite :connect-invite/target-aid#)]
                                   [contact-topic-path #{connectee-aid# target-aid#}])))))))

(deflda *connected-aids#->contact-topic-path [*contact-topic-path->connected-aids#] reverse-map)

(deflda *topic-path->contact-aids# [*contact-topic-path->connected-aids# *topic-path->my-aid#]
  (fn [contact-topic-path->connected-aids# topic-path->my-aid#]
    (->> contact-topic-path->connected-aids#
         (reduce (fn [acc [contact-topic-path connected-aids#]]
                   (let [my-aid-topic-path (vec (butlast contact-topic-path))
                         my-aid#           (topic-path->my-aid# my-aid-topic-path)
                         contact-aid#      (first (disj connected-aids# my-aid#))]
                     (assoc! acc my-aid-topic-path (conjs (get acc my-aid-topic-path) contact-aid#))))
                 (transient (hash-map)))
         persistent!)))

(deflda *contact-aids# [*topic-path->contact-aids#]
  (fn [topic-path->contact-aids#]
    (->> topic-path->contact-aids#
         vals
         (apply set/union))))

(deflda *topic-path->other-contact-aids# [*my-aid-topic-paths *topic-path->contact-aids# *contact-aids#]
  (fn [my-aid-topic-paths topic-path->contact-aids# contact-aids#]
    (->> my-aid-topic-paths
         (into (hash-map) (comp (map (fn [my-aid-topic-path]
                                       [my-aid-topic-path (set/difference contact-aids# (-> my-aid-topic-path topic-path->contact-aids#))]))
                                (filter (comp not-empty second)))))))


(deflda *topic-path->group-topic-paths [*topic-path->interactable-topic-paths *contact-topic-paths]
  (fn [topic-path->interactable-topic-paths contact-topic-paths]
    (->> topic-path->interactable-topic-paths
         (filter-map-vals (fn [interactable-topic-paths]
                            (->> interactable-topic-paths
                                 (filter (comp not contact-topic-paths))
                                 (set)
                                 (not-empty)))))))

(deflda *group-topic-paths [*topic-path->group-topic-paths]
  (fn [topic-path->group-topic-paths]
    (->> topic-path->group-topic-paths
         vals
         (apply set/union))))


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

(defn latest-control! [topic-path rotation-ke]
  (let [control (actrl/topic-path->control topic-path)]
    (if-not (unblinded? rotation-ke (actrl/topic-path->nk topic-path))
      control
      (actrl/rotate-control! topic-path))))

(add-watch *topic-path->my-ke ::update-control-on-rotation-ke
           (fn [_ _ old-topic-path->my-ke new-topic-path->my-ke]
             (let [novel-topic-path->my-ke (->> new-topic-path->my-ke
                                                (filter (fn [[topic-path my-ke]]
                                                          (not (hash= my-ke (get old-topic-path->my-ke topic-path))))))]
               (doseq [[novel-topic-path novel-my-ke] novel-topic-path->my-ke]
                 (let [novel-my-kes<     (->> novel-my-ke
                                                   (iterate :key-event/prior)
                                                   (take-while #(not (hash= % (get old-topic-path->my-ke novel-topic-path))))
                                                   reverse)
                       novel-my-kes-rot< (->> novel-my-kes<
                                              (filter #(= :rotation (:key-event/type %))))]
                   (doseq [novel-my-ke-rot novel-my-kes-rot<]
                     (latest-control! novel-topic-path novel-my-ke-rot)))))))


;; ------ graft-ke ------

;; (def ke-graft-aid# :ke-graft/aid#)
;; (def ke-graft-aid$ :ke-graft/aid$)
;; (def ke-graft-scions :ke-graft/scions)
;; (def ke-graft-stem-idx :ke-graft/stem-idx)

(def ke-graft-aid# 0)
(def ke-graft-aid$ 1)
(def ke-graft-scions 2)
(def ke-graft-stem-idx 3)

(declare ?ke->index)
(defn aids#-log+?ke-stem+ke-tip->ke-graft [aid#-log ?ke-stem ke-tip]
  (let [aid#     (-> ke-tip ke->ke-icp hash)
        aid$     (-indexOf aid#-log aid#)
        scions   (->> ke-tip
                      (iterate :key-event/prior)
                      (take-while some?)
                      (take-while (fn [ke] (not (hash= ke ?ke-stem))))
                      (map (fn [ke] (dissoc ke :key-event/prior)))
                      reverse
                      vec)
        stem-idx (?ke->index ?ke-stem)] ;; -1 if no ?ke-stem
    (cond-> {ke-graft-aid$     aid$
             ke-graft-scions   scions
             ke-graft-stem-idx (?ke->index ?ke-stem)}
      (= -1 stem-idx) (assoc ke-graft-aid# aid#))))

(defn db+ke-graft->grafted-db [{:keys [aids#-log aid$->ke] :as db} ke-graft]
  (let [[new-aids#-log aid$] (if-let [aid$ (not-neg (get ke-graft ke-graft-aid$))]
                               [aids#-log aid$]
                               (indexed aids#-log (get ke-graft ke-graft-aid#)))

        scions                (get ke-graft ke-graft-scions)
        stem-idx              (get ke-graft ke-graft-stem-idx)
        ?<<ke                 (-> aid$ aid$->ke)
        <<ke-idx              (?ke->index ?<<ke)
        known-count-atop-stem (- <<ke-idx stem-idx)]
    (if (>= known-count-atop-stem (count scions))
      db
      (let [to-graft-scions (subvec scions known-count-atop-stem)
            grafted-ke      (->> to-graft-scions
                                 (reduce (fn [?ke-acc scion]
                                           (cond-> scion
                                             ?ke-acc (assoc :key-event/prior ?ke-acc)))
                                         ?<<ke))]
        (-> db
            (assoc :aids#-log new-aids#-log
                   :aid$->ke  (assoc aid$->ke aid$ grafted-ke)))))))

(let [ke0 {:key-event/type :inception}
      ke1 {:key-event/type  :interaction
           :key-event/prior ke0}
      ke2 {:key-event/type  :rotation
           :key-event/prior ke1}
      ke3 {:key-event/type  :interaction
           :key-event/prior ke2}

      sending-db->ke-stem+ke-tip+ke-graft->receiving-db->grafted-db
      {{:aids#-log []}
       {[nil ke0 {ke-graft-aid$     -1
                  ke-graft-stem-idx -1
                  ke-graft-scions   [{:key-event/type :inception}]
                  ke-graft-aid#     (hash ke0)}]
        {{:aids#-log [] :aid$->ke {}}
         {:aids#-log [(hash ke0)] :aid$->ke {0 ke0}}

         {:aids#-log [(hash ke0)] :aid$->ke {0 ke0}} ;; learned already
         {:aids#-log [(hash ke0)] :aid$->ke {0 ke0}} ;; nothing changes

         {:aids#-log [(hash ke0)] :aid$->ke {0 ke1}} ;; learned next ke
         {:aids#-log [(hash ke0)] :aid$->ke {0 ke1}} ;; nothing changes

         {:aids#-log [(hash :some-aid)] :aid$->ke {0 {}}} ;; some aid managed to get learned before we grafted
         {:aids#-log [(hash :some-aid) (hash ke0)] :aid$->ke {0 {} 1 ke0}}}} ;; no fret, idx 1 is given


       {:aids#-log [(hash ke0)]}
       {[ke0 ke1 {ke-graft-aid$     0
                  ke-graft-stem-idx 0
                  ke-graft-scions   [{:key-event/type :interaction}]}]
        {{:aids#-log [(hash ke0)] :aid$->ke {0 ke0}}
         {:aids#-log [(hash ke0)] :aid$->ke {0 ke1}}

         {:aids#-log [(hash ke0)] :aid$->ke {0 ke1}} ;; learned already
         {:aids#-log [(hash ke0)] :aid$->ke {0 ke1}} ;; nothing changes

         {:aids#-log [(hash ke0)] :aid$->ke {0 ke2}} ;; learned next ke
         {:aids#-log [(hash ke0)] :aid$->ke {0 ke2}} ;; nothing changes


         {:aids#-log [(hash ke0) (hash :some-aid)] :aid$->ke {0 ke0 1 {}}}
         {:aids#-log [(hash ke0) (hash :some-aid)] :aid$->ke {0 ke1 1 {}}}}

        [ke1 ke3 {ke-graft-aid$     0
                  ke-graft-stem-idx 1
                  ke-graft-scions   [{:key-event/type :rotation} {:key-event/type :interaction}]}]
        {{:aids#-log [(hash ke0)] :aid$->ke {0 ke1}}
         {:aids#-log [(hash ke0)] :aid$->ke {0 ke3}}}}}]

  (deftest ke-graft-test
    (doseq [[sending-db ke-stem+ke-tip+ke-graft->receiving-db->grafted-db] sending-db->ke-stem+ke-tip+ke-graft->receiving-db->grafted-db
            [[ke-stem ke-tip ke-graft] receiving-db->grafted-db]           ke-stem+ke-tip+ke-graft->receiving-db->grafted-db]
      #_(l [sending-db ke-stem ke-tip ke-graft])
      (is (= ke-graft (aids#-log+?ke-stem+ke-tip->ke-graft (:aids#-log sending-db) ke-stem ke-tip)))
      (doseq [[receiving-db grafted-db] receiving-db->grafted-db]
        #_(l [sending-db ke-graft receiving-db grafted-db])
        (is (= grafted-db (db+ke-graft->grafted-db receiving-db ke-graft)))))))
#_
(run-test ke-graft-test)


(defda *inform-of-my-aid-novel-ke [*topic-path->my-ke *topic-path->participating-topic-paths]
  (fn [topic-path->my-ke topic-path->participating-topic-paths]
    (doseq [[topic-path my-ke]       topic-path->my-ke
            participating-topic-path (-> topic-path topic-path->participating-topic-paths)]
      (let [known-aid#->ke (get @at/*topic-path->projected-aid#->ke participating-topic-path)
            ?my-known-ke   (get known-aid#->ke (-> my-ke ke->ke-icp hash))]
        ;; perhaps only need to share on control propagation and acdc disclosure
        (when (and (> (?ke->index my-ke) (?ke->index ?my-known-ke))
                   ;; some novel establishment event?
                   #_(->> my-ke
                          (iterate :key-event/prior)
                          (take-while some?)
                          (take-while (fn [ke] (not (hash= ke ?my-known-ke))))
                          (some ->establishment-key-event?)))
          (let [{:keys [aids#-log]} (get @as/*topic-path->db participating-topic-path)
                ke-graft            (aids#-log+?ke-stem+ke-tip->ke-graft aids#-log ?my-known-ke my-ke)]
            (at/add-event! participating-topic-path {:event/tx [:graft-ke ke-graft]})))))))

(hg/reg-tx-handler! :graft-ke
    (fn [db evt [_ ke-graft]]
      (-> db
          (db+ke-graft->grafted-db ke-graft)
          (db-with-aid-control-propagated))))


(defn propose-issue-acdc-join-invite! [my-aid-topic-path issuer-aid# issuee-aid#]
  (at/add-event! my-aid-topic-path {:event/tx [:propose [:issue {:acdc/schema    :acdc-join-invite
                                                                 :acdc/issuer    issuer-aid#
                                                                 :acdc/attribute {:issuee  issuee-aid#
                                                                                  :purpose :join-invite}}]]}))

(defn propose-issue-acdc-qvi! [issuer-topic-path issuer-aid# issuee-aid# lei]
  (let [acdc-qvi* {:acdc/schema    :acdc-qvi
                   :acdc/issuer    issuer-aid#
                   :acdc/attribute {:issuee issuee-aid#
                                    :lei    lei}}]
    (at/add-event! issuer-topic-path {:event/tx [:propose [:issue acdc-qvi*]]})))

(defn propose-issue-acdc-le! [issuer-topic-path issuer-aid# issuee-aid# lei issuer-aid-attributed-acdc-qvi]
  (let [acdc-le* {:acdc/schema     :acdc-le
                  :acdc/issuer     issuer-aid#
                  :acdc/attribute  {:issuee issuee-aid#
                                    :lei    lei}
                  :acdc/edge-group {:qvi issuer-aid-attributed-acdc-qvi}}]
    (at/add-event! issuer-topic-path {:event/tx [:propose [:issue acdc-le*]]})))


(deflda *topic-path->acdcs [as/*topic-path->db] (filter-map-vals :acdcs))
(deflda *topic-path->disclosed-acdcs [at/*topic-path->subjective-db] (filter-map-vals :disclosed-acdcs))
(deflda *topic-path->attributed-acdcs [at/*topic-path->subjective-db] (filter-map-vals :attributed-acdcs))

(at/reg-subjective-tx-handler!
 :disclose-acdc
 (fn [{:keys [disclosed-acdcs] :as db} {:event/keys [creator] :as event} [_ acdc]]
   (cond-> db
     (not (contains? disclosed-acdcs acdc))
     (-> (update :disclosed-acdcs conjs acdc)
         (update-in [:feed :feed/items] conjv {:feed-item/kind       :acdc-presentation
                                               :feed-item/acdc       acdc
                                               :feed-item/creator    creator
                                               :feed-item/from-event event})))))

(declare *aid#->aid-name)
(at/reg-subjective-tx-handler!
 :attribute-acdc
 (fn [db event [_ acdc]]
   (let [schema      (-> acdc :acdc/schema)
         issuer-aid# (-> acdc :acdc/issuer)
         issuee-aid# (-> acdc :acdc/attribute :issuee)]
     (-> db
         (update :attributed-acdcs conjs acdc)
         ;; (update-in [:member-aid->ke (ke->ke-icp ke)] max-ke ke)
         (cond->
             (= :acdc-join-invite schema)
           (update :feed (fn [feed]
                           (let [proposal [:issue {:acdc/schema    :acdc-join-invite-accepted
                                                   :acdc/issuer    issuee-aid#
                                                   :acdc/attribute {:issuee      issuer-aid#
                                                                    :join-invite acdc
                                                                    :purpose     :join-invite-accepted}}]]
                             (cond-> feed
                               (not (contains? (-> feed :feed/proposal->feed-item-idx keys set) proposal))
                               (-> (update :feed/items conjv {:feed-item/kind       :proposal
                                                              :feed-item/proposal   proposal
                                                              :feed-item/creator    :info-bot
                                                              :feed-item/from-event event})
                                   (assoc-in [:feed/proposal->feed-item-idx proposal] (-> feed :feed/items count)))))))
           (= :acdc-join-invite-accepted schema)
           (update-in [:feed :feed/items] conjv {:feed-item/kind       :text-message
                                                 :feed-item/text       (str (get @*aid#->aid-name issuer-aid#) " accepted join invite")
                                                 :feed-item/creator    :info-bot
                                                 :feed-item/from-event event}))))))



(hg/reg-tx-handler! :attribute-acdc
    (fn [{:keys [aids#-log member-aids$] :as db} _ [_ {issuer-aid# :acdc/issuer :acdc/keys [schema] :as acdc}]]
      (let [[new-aids#-log issuer-aid$] (indexed aids#-log issuer-aid#)]
        (-> db
            (update :attributed-acdcs conjs acdc)
            (assoc :aids#-log new-aids#-log)
            (cond->
              (= :acdc-join-invite-accepted schema)
              (update :member-aids$ vec-union [issuer-aid$]))))))

(declare *connected-aids#->contact-topic-path)
(add-watch *topic-path->acdcs ::disclose-novel-acdc-to-issuee-aid
           (fn [_ _ old-topic-path->acdcs new-topic-path->acdcs]
             (let [topic-path+novel-acdcs (->> new-topic-path->acdcs
                                               (map (fn [[topic-path new-acdcs]]
                                                      [topic-path (set/difference new-acdcs (get old-topic-path->acdcs topic-path))]))
                                               (filter (comp not-empty second)))]
               (doseq [[topic-path novel-acdcs] topic-path+novel-acdcs
                       novel-acdc               novel-acdcs]
                 (when-let* [issuer-aid#              (-> novel-acdc :acdc/issuer)
                             issuee-aid#              (-> novel-acdc :acdc/attribute :issuee)
                             issuer+issuee-topic-path (get @*connected-aids#->contact-topic-path #{issuer-aid# issuee-aid#})]
                            (let [disclosed-acdcs (get @*topic-path->disclosed-acdcs issuer+issuee-topic-path)]
                              (when (not (contains? disclosed-acdcs novel-acdc))
                                (at/add-event! issuer+issuee-topic-path {hg/tx [:disclose-acdc novel-acdc]}))))))))


(declare *aid#->latest-known-ke)
(add-watch *topic-path->disclosed-acdcs ::attribute-novel-disclosed-acdcs-to-issuee-aid
           (fn [_ _ old-topic-path->disclosed-acdcs new-topic-path->disclosed-acdcs]
             (let [topic-path+novel-disclosed-acdcs (->> new-topic-path->disclosed-acdcs
                                                         (map (fn [[topic-path new-disclosed-acdcs]]
                                                                [topic-path (set/difference new-disclosed-acdcs (get old-topic-path->disclosed-acdcs topic-path))]))
                                                         (filter (comp not-empty second)))]
               (doseq [[topic-path novel-disclosed-acdcs] topic-path+novel-disclosed-acdcs
                       novel-disclosed-acdc               novel-disclosed-acdcs]
                 (let [participant-topic-path (vec (butlast topic-path))
                       participant-aid#       (get @*topic-path->my-aid# participant-topic-path)
                       issuer-aid#            (-> novel-disclosed-acdc :acdc/issuer)
                       issuee-aid#            (-> novel-disclosed-acdc :acdc/attribute :issuee)]
                   (when (= participant-aid# issuee-aid#)
                     (let [issuee-topic-path       participant-topic-path
                           issuee-attributed-acdcs (get @*topic-path->attributed-acdcs issuee-topic-path)]
                       (when (not (contains? issuee-attributed-acdcs novel-disclosed-acdc))
                         ;; member will be added
                         (at/add-event! issuee-topic-path {hg/tx [:attribute-acdc novel-disclosed-acdc]})

                         ;; control will be propagated
                         (when-let* [issuer-ke (get @*aid#->latest-known-ke issuer-aid#)]
                                    (let [{:keys [aids#-log aid$->ke]} (get @at/*topic-path->projected-db issuee-topic-path)
                                          ?issuee-known-issuer-ke      (some-> (not-neg (-indexOf aids#-log issuer-aid#))
                                                                               aid$->ke)]
                                      (when (> (?ke->index issuer-ke) (?ke->index ?issuee-known-issuer-ke))
                                        (let [{:keys [aids#-log]} (get @as/*topic-path->db issuee-topic-path)
                                              ke-graft            (aids#-log+?ke-stem+ke-tip->ke-graft aids#-log ?issuee-known-issuer-ke issuer-ke)]
                                          (at/add-event! issuee-topic-path {:event/tx [:graft-ke ke-graft]})))))))))))))


(defn* ^:memoizing ?ke->index [ke]
  (if (nil? ke)
    -1
    (-> ke :key-event/prior ?ke->index inc)))

(defn max-ke [?ke1 ?ke2]
  (if (>= (?ke->index ?ke1) (?ke->index ?ke2))
    ?ke1
    ?ke2))

;; that's gonna be slooow to recalc it from scratch every bloody time somebody informs or grafts
(deflda *projected-aid#->ke [at/*topic-path->projected-aid#->ke]
  (fn [topic-path->projected-aid#->ke]
    (->> topic-path->projected-aid#->ke
         vals
         (apply merge-with max-ke))))

(deflda *aid#->latest-known-ke [*projected-aid#->ke *my-aid#->my-ke] merge)

(deflda *aid#->latest-known-init-key->signing-key [*aid#->latest-known-ke]
  (map-vals (fn [ke] (-> ke ke->pub-db db->init-key->signing-key))))

(deflda *aid#->latest-known-init-key->did-peer [*aid#->latest-known-ke]
  (filter-map-vals (fn [ke] (-> ke ke->pub-db :init-key->did-peer))))


(deflda *aid#->aid-name [*aid#->latest-known-ke] (map-vals ke->?aid-name))


(defn ke->group-aid? [ke]
  (-> ke ->latest-establishment-key-event :key-event/signing-keys count (> 1)))

(deflda *aid#->group-aid? [*aid#->latest-known-ke] (map-vals ke->group-aid?))
;; -------------
