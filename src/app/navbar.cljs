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
            [hashgraph.utils.core :refer [subvecs] :refer-macros [l letl2]]

            [app.styles :refer [reg-styles! kind->css shadow0 shadow1 shadow2 shadow3] :as styles]
            [app.state :as as]
            [app.io :refer [reg< send-message]]
            [app.contacts :as contacts]
            [app.creds :as ac]

            [rum.core :refer [defc defcs] :as rum]
            [garden.units :refer [px]]))

(def styles
  [[:#navbar {:max-width             "100%"
              :max-height            "100%"
              :width                 "100%"
              :height                "100%"
              :display               :grid
              :grid-template-columns "80px 80px"
              :grid-template-rows    "1fr"
              :grid-template-areas   "\"my-aid-topic-paths interactable-topic-paths\""}
    [:.navbar__my-aid-topic-paths {:grid-area  "my-aid-topic-paths"
                                   :box-shadow shadow1}]
    [:.navbar__interactable-topic-paths {:grid-area      "interactable-topic-paths"
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

(reg-styles! ::navbar styles)


(defc navbar-view < rum/reactive
  [selected-my-aid-topic-path selected-topic-path]
  [:div#navbar
   [:div.navbar__my-aid-topic-paths
    [:div.navbar__my-aid-topic-paths__selected
     (for [selected-my-aid-topic-path (l (subvecs (l selected-my-aid-topic-path)))]
       (contacts/my-aid-topic-path-view selected-my-aid-topic-path true))]
    [:div.navbar__my-aid-topic-paths__selectable
     (for [selectable-my-aid-topic-path (rum/react (rum/cursor ac/*topic-path->my-aid-topic-paths selected-my-aid-topic-path))]
       (contacts/my-aid-topic-path-view selectable-my-aid-topic-path false))]]

   [:div.navbar__interactable-topic-paths
    (contacts/interactable-topic-paths-view selected-my-aid-topic-path)
    (contacts/new-topic-controls-view selected-my-aid-topic-path)]
   #_(if selected-my-aid-topic

       (contacts/contact-view my-did-peer (rum/react as/*connected?)))
   #_(contacts/contacts-view)
   ])
