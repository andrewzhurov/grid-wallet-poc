(ns app.navbar
  (:require [rum.core :refer [defc defcs] :as rum]
            [app.styles :refer [reg-styles! kind->css shadow0 shadow1 shadow2 shadow3] :as styles]
            [app.state :as as]
            [app.io :refer [reg< send-message]]
            [app.contacts :as contacts]
            [utils :refer-macros [l]]))

(def styles-horizontal
  [])

(def styles-vertical
  [])

(def styles
  [[:#navbar {:width "100%"
              :height "100%"
              :max-width "100%"
              :max-height "100%"
              :display :grid
              :grid-template-columns "80px"
              :grid-template-rows "auto 1fr 160px"
              :grid-template-areas "\"my-aid-topics\" \"contacts\" \"new\""
              :box-shadow shadow0}
    [:.navbar-my-aids {:grid-area "my-aid-topics"
                       :display        :flex
                       :flex-direction :column
                       :border-bottom  "1px solid lightgray"}]
    [:.contacts {:grid-area  "contacts"
                 :max-height "100%"
                 :max-width  "100%"}]
    [:.new-topic-controls {:grid-area "new"
                           :max-width "100%"}]]])

(reg-styles! ::navbar styles styles-horizontal styles-vertical)

(defc navbar-view < rum/reactive
  []
  (let [my-did-peer           (rum/react as/*my-did-peer)
        selected-my-aid-topic (rum/react as/*selected-my-aid-topic)]
    [:div#navbar
     [:div.navbar-my-aids
      (for [my-aid-topic (rum/react as/*my-aid-topics)]
        (contacts/topic-view my-aid-topic (rum/react as/*connected?)))]
     #_(if selected-my-aid-topic

       (contacts/contact-view my-did-peer (rum/react as/*connected?)))
     (contacts/contacts-view)
     (contacts/new-topic-controls-view)]))
