(ns app.control
  (:require
   [hashgraph.utils.core :refer [hash= not-neg mean conjs conjv map-vals] :refer-macros [defn* l letl letl2 when-let*] :as utils]
   [hashgraph.utils.lazy-derived-atom :refer [lazy-derived-atom]]
   [app.state :as as]))

(defonce *my-aid-path+topic->control (atom {}))

(def *my-aid-path+topic->first-key
  (lazy-derived-atom [*my-aid-path+topic->control]
      (fn [my-aid-path+topic->control]
        (->> my-aid-path+topic->control
             (map-vals (fn [control] (-> control :control/keys first)))))))

(defn my-aid-path+topic->first-key [my-aid-path topic]
  (or (@*my-aid-path+topic->first-key [my-aid-path topic])
      (throw (ex-info "can't find init-key for my-aid-path+topic" {:my-aid-path+topic [my-aid-path topic] :*my-aid-path+topic->first-key @*my-aid-path+topic->first-key}))))

(def *topic->my-aid-paths
  (lazy-derived-atom [*my-aid-path+topic->control]
      (fn [my-aid-path+topic->control]
        (->> my-aid-path+topic->control
             keys
             (map (fn [[my-aid-path topic]]
                    [topic my-aid-path]))
             (into {})))))


(defn gen-key [my-aid-path topic idx] ;; passing in all kinds of stuff as `topic`
  (cond (keyword? topic)
        (str (name topic) idx)
        (int? topic)
        (str topic "-k" idx)
        :else
        (str (hash [my-aid-path topic]) "-k" idx)))

(defn init-control! [my-aid-path topic]
  (let [control {:control/idx  0
                 :control/keys [(gen-key my-aid-path topic 0)
                                (gen-key my-aid-path topic 1)
                                (gen-key my-aid-path topic 2)]}]
    (swap! *my-aid-path+topic->control assoc [my-aid-path topic] control)
    control))

(defn my-aid-path+topic->control [my-aid-path topic]
  (or (@*my-aid-path+topic->control [my-aid-path topic])
      (throw (ex-info "no control found for my-aid-path+topic" {:my-aid-path+topic [my-aid-path topic] :my-aid-path&topic->control @*my-aid-path+topic->control}))))

(defn my-aid-path+topic->k [my-aid-path topic]
  (let [control (my-aid-path+topic->control my-aid-path topic)]
    (or (nth (:control/keys control) (:control/idx control) nil)
        (throw (ex-info "no k is found for my-aid-path+topic" {:my-aid-path+topic [my-aid-path topic] :my-aid-path&topic->control @*my-aid-path+topic->control})))))

(defn my-aid-path+topic->nk [my-aid-path topic]
  (let [control (my-aid-path+topic->control my-aid-path topic)]
    (or (nth (:control/keys control) (inc (:control/idx control)) nil)
        (throw (ex-info "no nk is found for my-aid-path+topic" {:my-aid-path+topic [my-aid-path topic] :my-aid-path&topic->control @*my-aid-path+topic->control})))))

(defn my-aid-path+topic->nnk [my-aid-path topic]
  (let [control (my-aid-path+topic->control my-aid-path topic)]
    (or (nth (:control/keys control) (inc (inc (:control/idx control))) nil)
        (throw (ex-info "no nnk is found for my-aid-path+topic" {:my-aid-path+topic [my-aid-path topic] :my-aid-path&topic->control @*my-aid-path+topic->control})))))

(defn rotate-control! [my-aid-path topic]
  (-> (swap! *my-aid-path+topic->control update [my-aid-path topic]
             (fn [control]
               (-> control
                   (update :control/idx inc)
                   (update :control/keys conj (gen-key my-aid-path topic (count (:control/keys control)))))))
      (get [my-aid-path topic])))
