(ns app.topic-feed-unread
  (:require [hashgraph.main :as hg]
            [hashgraph.topic :as hgt]
            [hashgraph.utils.core :refer [hash= flatten-all map-vals] :refer-macros [defn* l letl letl2] :as utils]
            [hashgraph.utils.lazy-derived-atom :refer [lazy-derived-atom] :refer-macros [deflda]]
            [hashgraph.app.icons :as icons]

            [app.styles :refer [reg-styles! kind->css] :as styles]
            [app.state :as as]
            [app.io :refer [reg< send-message]]
            [app.topic :as at]
            [app.utils :refer [reg-on-clipboard-text!]]
            [app.creds :as ac]
            [app.chat :as ach]

            [rum.core :refer [defc defcs] :as rum]
            [clojure.edn :as edn]
            [clojure.set :as set]))

(def topic-unread-count-styles
  [[:.topic-unread-count {:position         :absolute
                          :top              "50%"
                          :right            "0px"
                          :min-width        "20px"
                          :min-height       "20px"
                          :display          :flex
                          :justify-content  :center
                          :align-items      :center
                          :transform        "translate(50%, -50%)"
                          :opacity          0
                          :transition       "opacity 0.4s"
                          :border-radius    "40px"
                          :font-size        "12px"
                          :font-weight      :bold
                          :color            "white"
                          :background-color ach/color-primary-lightest}
    [:&.some {:opacity 1}]]])
(reg-styles! ::topic-unread-count topic-unread-count-styles)

(deflda *topic-path->feed-items-count [at/*topic-path->subjective-db]
  (map-vals (fn [subjective-db] (-> subjective-db :feed :feed/items count))))

(defonce *topic-path->feed-items-count-read (atom (hash-map)))
(add-watch *topic-path->feed-items-count ::update-read-on-new-feed-items
           (fn [_ _ _ topic-path->feed-items-count]
             (l [::update-read-on-new-feed-items topic-path->feed-items-count])
             (swap! *topic-path->feed-items-count-read assoc @as/*selected-topic-path (topic-path->feed-items-count @as/*selected-topic-path))))
(add-watch as/*selected-topic-path ::update-read-on-selected-topic
           (fn [_ _ _ selected-topic-path]
             (l [::update-read-on-selected-topic selected-topic-path])
             (swap! *topic-path->feed-items-count-read assoc selected-topic-path (@*topic-path->feed-items-count selected-topic-path))))

(deflda *topic-path->feed-items-count-unread [*topic-path->feed-items-count *topic-path->feed-items-count-read]
  (fn [topic-path->feed-items-count topic-path->feed-items-count-read]
    (l [::derive-unread topic-path->feed-items-count topic-path->feed-items-count-read])
    (->> topic-path->feed-items-count
         (into (hash-map) (map (fn [[topic-path feed-items-count]]
                                 [topic-path (- feed-items-count (get topic-path->feed-items-count-read topic-path))]))))))

(defonce *topic-path->feed-items-count-unread-old+new (atom (hash-map)))
(add-watch *topic-path->feed-items-count-unread ::derive-prev->new-unread-count
           (fn [_ _ old-topic-path->feed-items-count-unread new-topic-path->feed-items-count-unread]
             (l [::derive-prev->new-unread-count old-topic-path->feed-items-count-unread new-topic-path->feed-items-count-unread])
             (->> new-topic-path->feed-items-count-unread
                  (into (hash-map) (map (fn [[new-topic new-unread-count]]
                                          [new-topic [(get old-topic-path->feed-items-count-unread new-topic 0) new-unread-count]])))
                  (reset! *topic-path->feed-items-count-unread-old+new))))

(defc topic-feed-unread-count-bubble-view < rum/reactive
  [topic-path]
  (let [[old-count-unread new-count-unread] (rum/react (rum/cursor *topic-path->feed-items-count-unread-old+new topic-path))]
    [:div.topic-unread-count {:class (when-not (zero? new-count-unread) "some")}
     (if (zero? new-count-unread)
       old-count-unread
       new-count-unread)]))
