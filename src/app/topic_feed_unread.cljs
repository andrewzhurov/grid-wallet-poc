(ns app.topic-feed-unread
  (:require [hashgraph.main :as hg]
            [hashgraph.topic :as hgt]
            [hashgraph.app.members :as hga-members]
            [hashgraph.utils.core :refer [hash= flatten-all] :refer-macros [defn* l letl] :as utils]
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

(def *topic->feed-items-count
  (rum/derived-atom [at/*topic->subjective-db] ::derive-topic->feed-items-count
    (fn [topic->subjective-db]
      (->> topic->subjective-db
           (map (fn [[topic subjective-db]]
                  [topic (-> subjective-db :feed :feed/items count)]))
           (into {})))))

(defonce *topic->feed-items-count-read (atom {}))
(add-watch *topic->feed-items-count ::update-read-on-new-feed-items
           (fn [_ _ _ topic->feed-items-count]
             (swap! *topic->feed-items-count-read assoc @as/*selected-topic (topic->feed-items-count @as/*selected-topic))))
(add-watch as/*selected-topic ::update-read-on-selected-topic
           (fn [_ _ _ selected-topic]
             (swap! *topic->feed-items-count-read assoc selected-topic (@*topic->feed-items-count selected-topic))))
(def *topic->feed-items-count-unread
  (rum/derived-atom [*topic->feed-items-count *topic->feed-items-count-read] ::derive-unread
    (fn [topic->feed-items-count topic->feed-items-count-read]
      (->> topic->feed-items-count
           (map (fn [[topic feed-items-count]]
                  [topic (- feed-items-count (get topic->feed-items-count-read topic))]))
           (into {})))))
(defonce *topic->feed-items-count-unread-old+new (atom {}))
(add-watch *topic->feed-items-count-unread ::derive-prev->new-unread-count
           (fn [_ _ old-topic->feed-items-count-unread new-topic->feed-items-count-unread]
             (->> new-topic->feed-items-count-unread
                  (map (fn [[new-topic new-unread-count]]
                         [new-topic [(or (old-topic->feed-items-count-unread new-topic) 0) new-unread-count]]))
                  (into {})
                  (reset! *topic->feed-items-count-unread-old+new))))

(defc topic-feed-unread-count-bubble-view < rum/reactive
  [topic]
  (let [[old-count-unread new-count-unread] (rum/react (rum/cursor *topic->feed-items-count-unread-old+new topic))]
    [:div.topic-unread-count {:class (when-not (zero? new-count-unread) "some")}
     (if (zero? new-count-unread)
       old-count-unread
       new-count-unread)]))
