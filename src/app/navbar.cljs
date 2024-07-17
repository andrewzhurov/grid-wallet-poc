(ns app.navbar
  (:require [rum.core :refer [defc defcs] :as rum]
            [app.styles :refer [reg-styles! kind->css] :as styles]
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
              :grid-template-rows "80px 1fr"
              :grid-template-areas "\"me\" \"contacts\""}
    [:.contact {:grid-area "me"}]
    [:.contacts {:grid-area  "contacts"
                 :max-height "100%"
                 :max-width  "100%"}]]])

(reg-styles! ::navbar styles styles-horizontal styles-vertical)

(defc navbar-view < rum/reactive
  []
  (let [my-did (rum/react as/*my-did)]
    [:div#navbar
     (contacts/contact-view my-did (rum/react as/*connected?))
     (contacts/contacts-view)]))
