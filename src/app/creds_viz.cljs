(ns app.creds-viz
  (:require
   [hashgraph.utils.core :refer [hash= conjs conjv map-vals] :refer-macros [defn* l letl letl2 when-let*] :as utils]
   [hashgraph.utils.lazy-derived-atom :refer [lazy-derived-atom] :refer-macros [deflda]]

   [app.topic-viz :as atv]

   [clojure.set :as set]))

(deflda *disclosed-acdcs [atv/*topic-path->viz-subjective-db]
  (fn [topic-path->viz-subjective-db]
    (->> topic-path->viz-subjective-db
         vals
         (map (fn [viz-subjective-db] (some-> viz-subjective-db :disclosed-acdcs)))
         (filter some?)
         (apply set/union))))

(deflda *aid#->attributed-acdcs [*disclosed-acdcs]
  (fn [disclosed-acdcs]
    (->> disclosed-acdcs
         (reduce (fn [aid#->attributed-acdcs-acc disclosed-acdc]
                   (let [?issuee-aid# (-> disclosed-acdc :acdc/attribute :issuee)]
                     (cond-> aid#->attributed-acdcs-acc
                       ?issuee-aid# (update ?issuee-aid# conjs disclosed-acdc))))
                 (hash-map)))))
