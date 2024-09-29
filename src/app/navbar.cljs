(ns app.navbar
  (:require-macros [app.material :refer [speed-dial speed-dial-icon speed-dial-action
                                         icon icon-fingerprint icon-send
                                         dialog dialog-title dialog-content dialog-content-text dialog-actions
                                         text-field textarea-autosize button fab

                                         mui-list list-item list-item-button list-item-text list-item-avatar avatar

                                         zoom fade
                                         click-away-listener]]
                   :reload-all)
  (:require [hashgraph.main :as hg]
            [hashgraph.utils.core :refer-macros [l letl2]]

            [app.styles :refer [reg-styles! kind->css shadow0 shadow1 shadow2 shadow3] :as styles]
            [app.state :as as]
            [app.io :refer [reg< send-message]]
            [app.contacts :as contacts]
            [app.creds :as ac]

            [rum.core :refer [defc defcs] :as rum]
            [utils :refer-macros [l]]
            [garden.units :refer [px]]))

(def styles-horizontal
  [])

(def styles-vertical
  [])

(def styles
  [[:#navbar {:max-width             "100%"
              :max-height            "100%"
              :width                 "100%"
              :height                "100%"
              :display               :grid
              :grid-template-columns "80px 80px"
              :grid-template-rows    "1fr"
              :grid-template-areas   "\"my-aids my-aid-contacts\""}
    [:.navbar__my-aids {:grid-area  "my-aids"
                        :box-shadow shadow1}]
    [:.navbar__my-aid-contacts {:grid-area      "my-aid-contacts"
                                :max-height     "100%"
                                :max-width      "100%"
                                :border-right   "solid 1px lightgray"
                                :position       :relative
                                :height         "100%"
                                :display        :flex
                                :flex-direction :column}
     [:.new-topic-controls {:position :absolute
                            :bottom   (px 0)
                            :left     (px 0)
                            :right    (px 0)}]]]])

(reg-styles! ::navbar styles styles-horizontal styles-vertical)

(defc navbar-view < rum/reactive
  []
  (let [my-did-peer           (rum/react as/*my-did-peer)
        selected-my-aid-topic (rum/react as/*selected-my-aid-topic)
        selected-my-aid       (rum/react (rum/cursor ac/*my-aid-topic->my-aid selected-my-aid-topic))]
    [:div#navbar
     [:div.navbar__my-aids
      (for [my-aid-topic (rum/react as/*my-aid-topics)]
        (contacts/my-aid-topic-view my-aid-topic))]
     [:div.navbar__my-aid-contacts
      (contacts/contacts-view selected-my-aid-topic selected-my-aid)
      (contacts/new-topic-controls-view selected-my-aid-topic selected-my-aid)]
     #_(if selected-my-aid-topic

         (contacts/contact-view my-did-peer (rum/react as/*connected?)))
     #_(contacts/contacts-view)
     ]))
