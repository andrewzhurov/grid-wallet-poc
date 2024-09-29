(ns app.creds-viz
  (:require
   [hashgraph.utils.core :refer [hash= conjs conjv map-vals] :refer-macros [defn* l letl letl2 when-let*] :as utils]
   [hashgraph.utils.lazy-derived-atom :refer [lazy-derived-atom]]

   [app.topic-viz :as atv]

   [clojure.set :as set]))

(def *disclosed-acdcs
  (lazy-derived-atom [atv/*topic->viz-subjective-db]
      (fn [topic->viz-subjective-db]
        (l [::derive-*disclosed-acdcs topic->viz-subjective-db])
        (l (->> topic->viz-subjective-db
                (map (fn [[_topic viz-subjective-db]] (some-> viz-subjective-db :feed :feed/disclosed-acdcs)))
                (filter some?)
                (apply set/union))))))

(def *aid->attributed-acdcs
  (lazy-derived-atom [*disclosed-acdcs]
      (fn [disclosed-acdcs]
        (l [::derive-*aid->attributed-acdcs disclosed-acdcs])
        (l (->> disclosed-acdcs
                (reduce (fn [aid->attributed-acdcs-acc disclosed-acdc]
                          (let [?issuee-aid (-> disclosed-acdc :acdc/attribute :issuee)]
                            (cond-> aid->attributed-acdcs-acc
                              ?issuee-aid (update ?issuee-aid conjs disclosed-acdc))))
                        {}))))))
