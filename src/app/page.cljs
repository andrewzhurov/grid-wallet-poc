(ns app.page
  (:require
   [hashgraph.app.styles :as hga-styles]

   [app.styles :refer [reg-styles! kind->css] :as styles]
   [app.state :as as]
   [app.navbar :as navbar]
   [app.contacts :as contacts]
   [app.profile :as profile]
   [app.topic :as topic]
   [app.chat :as chat]
   [utils :refer-macros [l]]

   [rum.core :refer [defc defcs] :as rum]
   [garden.selectors :as gs]
   [garden.stylesheet :refer [at-keyframes]]
   [garden.color :as gc]
   [garden.units :refer [px]]))

(def styles-horizontal
  [])

(def styles-vertical
  [])

(def styles
  ;; https://grid.layoutit.com/?id=D90XF8D
  [[:body {:overflow :hidden
           :margin "0px"}]
   [:#root-view {:height "100vh"
                 :overflow :hidden
                 :display :grid
                 :grid-template-columns "80px 1fr"
                 :grid-template-rows "1fr"
                 :gap "0px 0px"
                 :grid-auto-flow :row
                 :grid-template-areas "\"navbar page\""}
    [:#navbar {:grid-area "navbar"
               :min-width "0px"
               :height :inherit
               :width :inherit}]
    [:#page {:grid-area "page"
             :min-width "0px"
             :height :inherit
             :width :inherit}]]])

(reg-styles! ::page styles styles-horizontal styles-vertical)

(defc view < rum/reactive []
  [:<>
   (styles/styles-hiccup)
   [:style (hga-styles/kind->css :bare)]
   [:style (hga-styles/kind->css :vertical)]

   [:div#root-view
    (navbar/navbar-view)
    [:#page
     (case (rum/react as/*selected-page)
       :topic     (chat/topic-view)
       :new-topic (contacts/new-topic-page-view)
       :profile   (profile/profile-page-view)
       [:div "Home page"])]]])
