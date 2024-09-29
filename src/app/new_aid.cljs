(ns app.new-aid
  (:require-macros [app.material :refer [speed-dial speed-dial-icon speed-dial-action
                                         icon icon-fingerprint icon-send
                                         dialog dialog-title dialog-content dialog-content-text dialog-actions
                                         text-field textarea-autosize

                                         button fab
                                         zoom fade
                                         click-away-listener
                                         box container
                                         tab-context tab-list tab tab-panel]]
                   :reload-all)
  (:require [hashgraph.main :as hg]
            [hashgraph.topic :as hgt]
            [hashgraph.utils.core :refer-macros [defn* l letl when-let*] :refer [conjv conjs] :as utils]
            [hashgraph.app.icons :as icons]

            [app.styles :refer [reg-styles! shadow0 shadow1 shadow2 shadow3] :as styles]
            [app.state :as as]
            [app.topic :as at]
            [app.topic-viz :as atv]
            [app.creds :as ac]
            [app.control :as actrl]
            [app.utils :refer-macros [incm]]

            [rum.core :refer [defc defcs] :as rum]
            [garden.selectors :as gs]
            [garden.color :as gc]
            [garden.util :as gu]
            [garden.util]
            [garden.units :refer [px px- px+] :as gun]
            [garden.arithmetic :as ga]
            [garden.compiler]
            ["@mui/lab/TabPanel$default" :as tab-panel]))

(def new-aid-styles
  [[:.new-aid {:max-width (px 400)
               :height "100vh"
               :margin-top "20%"
               :margin-left :auto
               :margin-right :auto
               :display :flex
               :flex-direction :column
               :align-items :center}
    [:.new-aid__device-name-input {:width "100%"}]
    [:.new-aid__login {:width          "100%"
                       :display        :flex
                       :flex-direction :column
                       :align-items :center}]]])

(reg-styles! ::new-aid new-aid-styles)

(defn accept-device-link! [device-link]
  (l device-link))

(defcs new-aid-view < rum/reactive (rum/local "" ::*device-name) (rum/local "" ::*aid-name) (rum/local nil ::*tab)
  [{::keys [*device-name *aid-name *tab]}]
  [:div.new-aid
   (text-field {:class      "new-aid__device-name-input"
                :label      "Device Name"
                :value      @*device-name
                :auto-focus true
                :on-change  #(reset! *device-name (-> % .-target .-value))})
   (let [my-did-peer (rum/react as/*my-did-peer)]
     (fade {:key     1
            :in      (and (some? my-did-peer)
                          (not (empty? @*device-name)))
            :timeout {"enter" 400
                      "exit"  400}}
           (button {:style    {:align-self :end
                               :margin-top "10px"}
                    ;; :disabled (empty? @*device-name)
                    :on-click (fn []
                                (let [topic (at/create-topic! my-did-peer #{my-did-peer} {:topic-name @*device-name})]
                                  (ac/add-init-control-event! [] topic)
                                  (ac/add-event! [] topic {:event/tx [:reg-did-peers ]})))}
                   "Create Device AID")))
   #_(fade {:key     :login
          :in      (not (empty? @*device-name))
          :timeout {"enter" 400
                    "exit"  400}}
         [:div.new-aid__login
          (tab-context {:value @*tab}
                       (box {:sx {:border-bottom 1
                                  :border-color  "divider"}}
                            (tab-list (tab {:value    "1"
                                            :on-click #(reset! *tab "1")
                                            :label    "Link"})
                                      (tab {:value    "2"
                                            :on-click #(reset! *tab "2")
                                            :label    "Create"})))
                       (tab-panel {:value "1"}
                                  [:div {:style {:display        :flex
                                                 :flex-direction :column}}
                                   (text-field {:label       "Device Link"
                                                :placeholder "Paste Device Link here"
                                                :helper-text "You can create Device Link from one of your devices"
                                                :auto-focus  true
                                                :on-change   #(-> % .-target .-value (accept-device-link!))})
                                   [:div "<QR CODE>"]])
                       (tab-panel {:value "2"}
                                  [:div {:style {:display        :flex
                                                 :flex-direction :column}}
                                   (text-field {:label     "AID Name"
                                                :value     @*aid-name
                                                :auto-focus  true
                                                :on-change #(reset! *aid-name (-> % .-target .-value))})
                                   (let [my-did-peer (rum/react as/*my-did-peer)]
                                     (fade {:key     1
                                            :in      (and (some? my-did-peer)
                                                          (not (empty? @*aid-name)))
                                            :timeout {"enter" 400
                                                      "exit"  400}}
                                           (button {:style    {:align-self :end
                                                               :margin-top "10px"}
                                                    ;; :disabled (empty? @*device-name)
                                                    :on-click (fn [] (let [topic (at/create-topic! my-did-peer #{my-did-peer} {:topic-name @*device-name})]
                                                                       (ac/add-init-control-event! topic)))}
                                                   "Create AID")))]))])]
  #_[:div {:style {:height          "100vh"
                   :width           "100vw"
                   :display         :flex
                   :flex-direction  :column
                   :justify-content :center
                   :align-items     :center}}
     [:div {:style {:display         :flex
                    :flex-direction  :column
                    :justify-content :center
                    :align-items     :center
                    :max-width       "400px"}}
    ]])
