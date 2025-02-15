(ns app.control
  (:require
   [hashgraph.utils.core :refer [hash= not-neg mean conjs conjv map-vals reverse-map] :refer-macros [defn* l letl letl2 when-let*] :as utils]
   [hashgraph.utils.lazy-derived-atom :refer [lazy-derived-atom] :refer-macros [deflda]]
   [app.state :as as]
   [rum.core :as rum]))
rum/react
(defonce *topic-path->control (atom {[] {:control/keys [(str "root-" (hash (random-uuid)) "-k")]}}))

(deflda *topic-path->init-key [*topic-path->control as/*my-did-peer]
  (fn [topic-path->control my-did-peer]
    (->> topic-path->control
         (map-vals (fn [control] (-> control :control/keys first))))))

(deflda *init-key->topic-path [*topic-path->init-key] reverse-map)

#_(deflda *topic-path->member-init-key [as/*topic-paths *topic-path->init-key]
  (fn [topic-paths topic-path->init-key]
    (->> topic-paths
         (reduce (fn [acc topic-path]
                   (let [member-topic-path (vec (butlast topic-path))
                         member-init-key   (topic-path->init-key member-topic-path)]
                     (assoc acc member-topic-path member-init-key)))
                 (hash-map)))))

(defn topic-path->init-key [topic-path]
  (or (((if (some? rum/*reactions*) rum/react deref) *topic-path->init-key) topic-path)
      (throw (ex-info "can't find init-key for topic-path" {:topic-path topic-path :*topic-path->init-key @*topic-path->init-key}))))

(defn init-key->topic-path [init-key]
  (or (((if (some? rum/*reactions*) rum/react deref) *init-key->topic-path) init-key)
      (throw (ex-info "can't find topic-path for init-key" {:init-key init-key :*init-key->topic-path @*init-key->topic-path}))))

(defn topic-path->member-init-key [topic-path]
  (or (((if (some? rum/*reactions*) rum/react deref) *topic-path->init-key) (vec (butlast topic-path)))
      (throw (ex-info "can't find member-init-key for topic-path" {:topic-path topic-path :*topic-path->member-init-key @*topic-path->init-key}))))

(defn gen-key [topic-path idx] ;; passing in all kinds of stuff as `topic-path`
  (cond (keyword? topic-path)
        (str (name topic-path) idx)
        (int? topic-path)
        (str topic-path "-k" idx)
        :else
        (-> topic-path
            (->> (map :topic-name)
                 (interpose "-")
                 (apply str))
            (str "-k" idx))
        #_
        (str (hash topic-path) "-k" idx)))

(defn init-control! [topic-path]
  (let [control {:control/idx  0
                 :control/keys [(gen-key topic-path 0)
                                (gen-key topic-path 1)
                                (gen-key topic-path 2)]}]
    (-> (swap! *topic-path->control update topic-path  (fn [?current-control] (or ?current-control control)))
        (get topic-path))))

(defn topic-path->control [topic-path]
  (or (@*topic-path->control topic-path)
      (throw (ex-info "no control found for topic-path" {:topic-path topic-path :*topic-path->control @*topic-path->control}))))

(defn topic-path->k [topic-path]
  (let [control (topic-path->control topic-path)]
    (or (nth (:control/keys control) (:control/idx control) nil)
        (throw (ex-info "no k is found for topic-path" {:topic-path topic-path :*topic-path->control @*topic-path->control})))))

(defn topic-path->nk [topic-path]
  (let [control (topic-path->control topic-path)]
    (or (nth (:control/keys control) (inc (:control/idx control)) nil)
        (throw (ex-info "no nk is found for topic-path" {:topic-path topic-path :*topic-path->control @*topic-path->control})))))

(defn topic-path->nnk [topic-path]
  (let [control (topic-path->control topic-path)]
    (or (nth (:control/keys control) (inc (inc (:control/idx control))) nil)
        (throw (ex-info "no nnk is found for topic-path" {:topic-path topic-path :*topic-path->control @*topic-path->control})))))

(defn rotate-control! [topic-path]
  (-> (swap! *topic-path->control update topic-path
             (fn [control]
               (-> control
                   (update :control/idx inc)
                   (update :control/keys conj (gen-key topic-path (count (:control/keys control)))))))
      (get topic-path)))
