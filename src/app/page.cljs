(ns app.page
  (:require
   [garden.selectors :as gs]
   [garden.stylesheet :refer [at-keyframes]]
   [garden.color :as gc]
   [garden.units :refer [px]]
   [rum.core :refer [defc defcs] :as rum]

   [app.styles :refer [reg-styles! kind->css] :as styles]
   [app.state :as as]
   [app.navbar :as navbar]
   [app.contacts :as contacts]
   [app.chat :as chat]
   [utils :refer-macros [l]]))

(def styles-horizontal
  [])

(def styles-vertical
  [])

(def styles
  ;; https://grid.layoutit.com/?id=D90XF8D
  [[:body {:overflow :hidden
           :margin "0px"}]
   [:#root-view {:width "100vw"
                 :height "100vh"
                 :overflow :hidden
                 :display :grid
                 :grid-template-columns "80px 1fr"
                 :grid-template-rows "1fr"
                 :gap "0px 0px"
                 :grid-auto-flow :row
                 :grid-template-areas "\"navbar page\""}
    [:#navbar {:grid-area "navbar"
               :min-width "0px"}]
    [:#page {:grid-area "page"
             :min-width "0px"}]]])

(reg-styles! ::page styles styles-horizontal styles-vertical)

(defc view < rum/reactive []
  [:<>
   (styles/styles-hiccup)

   [:div#root-view
    (navbar/navbar-view)
    [:#page
     (chat/chat-view)]]])
