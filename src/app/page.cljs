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
   [app.new-aid :as new-aid]

   [rum.core :refer [defc defcs] :as rum]
   [garden.selectors :as gs]
   [garden.stylesheet :refer [at-keyframes]]
   [garden.color :as gc]
   [garden.units :refer [px]]
   [app.creds :as ac]))

(def styles-horizontal
  [])

(def styles-vertical
  [])

(def styles
  ;; https://grid.layoutit.com/?id=D90XF8D
  [[:body {:overflow :hidden
           :margin   "0px"}]
   [:#root-view {:height                "100vh"
                 :overflow              :hidden
                 :display               :grid
                 :grid-template-columns "160px 1fr"
                 :grid-template-rows    "1fr"
                 :gap                   "0px 0px"
                 :grid-auto-flow        :row
                 :grid-template-areas   "\"navbar page\""}
    [:#navbar {:grid-area "navbar"
               :min-width "0px"
               :height    :inherit
               :width     :inherit}]
    [:#page {:grid-area "page"
             :min-width "0px"
             :height    :inherit
             :width     :inherit}]]])

(reg-styles! ::page styles styles-horizontal styles-vertical)

(defc view < rum/reactive []
  [:<>
   (styles/styles-hiccup)
   [:style (hga-styles/kind->css :bare)]
   [:style (hga-styles/kind->css :vertical)]

   (if-let [selected-topic-path (not-empty (rum/react as/*selected-topic-path))]
     (let [selected-my-aid-topic-path (rum/react ac/*selected-my-aid-topic-path)]
       [:div#root-view
        (navbar/navbar-view selected-my-aid-topic-path selected-topic-path)
        [:#page
         (case (rum/react as/*selected-page)
           :topic     (chat/topic-path-view selected-my-aid-topic-path selected-topic-path)
           :new-topic (contacts/new-topic-page-view selected-topic-path)
           :profile   (profile/profile-page-view selected-topic-path)
           [:div "Home page"])]])
     (new-aid/new-aid-view))])
