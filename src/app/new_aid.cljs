(ns app.new-aid
  (:require-macros [app.material :refer [speed-dial speed-dial-icon speed-dial-action
                                         icon icon-fingerprint icon-send
                                         dialog dialog-title dialog-content dialog-content-text dialog-actions
                                         text-field textarea-autosize

                                         button fab
                                         zoom fade
                                         click-away-listener
                                         box container
                                         tab-context tab-list tab tab-panel
                                         typography]]
                   :reload-all)
  (:require [hashgraph.main :as hg]
            [hashgraph.topic :as hgt]
            [hashgraph.utils.core :refer-macros [defn* l letl letl2 when-let*] :refer [conjv conjs] :as utils]
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
            [garden.units :refer [px px- px+] :as gun]
            [garden.arithmetic :as ga]
            [garden.compiler]))

(def new-aid-styles
  [[:.new-aid {:max-width (px 400)
               :width :fit-content
               :height "100vh"
               :margin-top "10vh"
               :margin-left :auto
               :margin-right :auto
               :display :flex
               :flex-direction :column
               :align-items :center}
    [:.create]
    [:.new-aid__device-name-input {:width "100%"}]
    [:.new-aid__login {:width          "100%"
                       :display        :flex
                       :flex-direction :column
                       :align-items :center}]]])

(reg-styles! ::new-aid new-aid-styles)

(defn accept-device-link! [device-link]
  (l device-link))

(defcs new-aid-view < rum/reactive (rum/local "" ::*device-name) (rum/local "" ::*aid-name) (rum/local false ::*tab)
  [{::keys [*device-name *aid-name *tab]}]
  [:div.new-aid
   (typography {:variant "h4"} "grID Wallet")
   (typography {:variant "subtitle1"} "A safe place for Grassroots Identifiers")
   (typography {:variant "subtitle2"} "ones that are truly yours")
   #_[:h1 {:style {:margin-top "0px"}} "grID Wallet"]
   #_[:h3 {:style {:margin-top "0px"}} "A safe place for Grassroots Identifiers"]
   #_[:h4 {:style {:margin-top "0px"}} "ones that are truly yours"]
   (text-field {:sx         {:margin-top "48px"}
                :class      "new-aid__device-name-input"
                :label      "Device Name"
                :value      @*device-name
                :auto-focus true
                :on-change  #(reset! *device-name (-> % .-target .-value))})
   (let [my-did-peer (rum/react as/*my-did-peer)]
     [:<>
      (fade {:key     1
             :in      (and (some? my-did-peer)
                           (not (empty? @*device-name)))
             :timeout {"enter" 400
                       "exit"  400}}
            (button {:style    {:margin-top    "24px"
                                :margin-bottom "24px"}
                     ;; :disabled (empty? @*device-name)
                     :on-click (fn []
                                 (let [init-key (actrl/topic-path->init-key [])
                                       topic    {:topic-name           @*device-name
                                                 :member-init-keys     [init-key]
                                                 :member-init-keys-log [init-key]
                                                 :active-creators      #{0}
                                                 :threshold            [[1 1]]
                                                 :total-stake          hg/total-stake ;; ideally this is not needed for one-member hg
                                                 :stake-map            {init-key hg/total-stake}}]
                                   (reset! as/*selected-topic-path [topic])
                                   (reset! as/*browsing {:page :topic})
                                   (at/add-event! [topic] {hg/topic topic})
                                   (ac/add-init-control-event! [topic])
                                   #_(ac/add-event! [topic] {:event/tx [:assoc-did-peer my-did-peer]})))}
                    "Create Device grID"))
      (fade {:key     :login
             :in      (and (some? my-did-peer)
                           (not (empty? @*device-name)))
             :timeout {"enter" 400
                       "exit"  400}}
            [:div.new-aid__login
             (typography {:variant "subtitle1"} "or")
             (tab-context {:value @*tab}
                          (box {:sx {:border-bottom 1
                                     :padding-top   "24px"
                                     :border-color  "divider"}}
                               (tab-list (tab {:value    "1"
                                               :on-click #(reset! *tab "1")
                                               :label    "Join Personal grID"})
                                         (tab {:value    "2"
                                               :on-click #(reset! *tab "2")
                                               :label    "Create Personal grID"})))
                          (tab-panel {:value "1"}
                                     [:div {:style {:display        :flex
                                                    :flex-direction :column
                                                    :align-items    :center}}
                                      (text-field {:label       "Join Invite"
                                                   :placeholder "Paste Join Invite here"
                                                   :helper-text "You can create Join Invite from one of your devices"
                                                   :auto-focus  true
                                                   :on-change   #(-> % .-target .-value (accept-device-link!))})
                                      [:div "<Scan Join Invite QR code>"]
                                      [:div "<Show Join Request QR code>"]])
                          (tab-panel {:sx    {:width                "100%"
                                              :box-sizing           :border-box
                                              #_#_#_#_:padding-left "0px"
                                              :padding-right        "0px"}
                                      :value "2"}
                                     [:div {:style {:display        :flex
                                                    :flex-direction :column
                                                    :align-items    :center}}
                                      (text-field {:sx         {:width "100%"}
                                                   :label      "Person Name"
                                                   :value      @*aid-name
                                                   :auto-focus true
                                                   :on-change  #(reset! *aid-name (-> % .-target .-value))})
                                      (let [my-did-peer (rum/react as/*my-did-peer)]
                                        (fade {:key     1
                                               :in      (and (some? my-did-peer)
                                                             (not (empty? @*aid-name)))
                                               :timeout {"enter" 400
                                                         "exit"  400}}
                                              (button {:style    {:margin-top    "24px"
                                                                  :margin-bottom "24px"}
                                                       ;; :disabled (empty? @*device-name)
                                                       :on-click (fn []
                                                                   (let [device-init-key (actrl/topic-path->init-key [])
                                                                         device-topic    {:topic-name           @*device-name
                                                                                          :member-init-keys     [device-init-key]
                                                                                          :member-init-keys-log [device-init-key]
                                                                                          :threshold            [[1 1]]
                                                                                          :total-stake          hg/total-stake ;; ideally this is not needed for one-member hg
                                                                                          :stake-map            {0 hg/total-stake}}]
                                                                     #_(reset! as/*selected-topic-path [device-topic])
                                                                     #_(reset! as/*browsing {:page :topic})
                                                                     (at/add-event! [device-topic] {hg/topic device-topic})
                                                                     (ac/add-init-control-event! [device-topic])
                                                                     #_(ac/add-event! [device-topic] {:event/tx [:assoc-did-peer my-did-peer]})

                                                                     (letl2 [device-aid#    (or (get @ac/*topic-path->my-aid# [device-topic]) (throw (ex-info "device aid is not present" {:device-topic-cr (get @as/*topic-path->cr [device-topic])})))
                                                                             personal-topic (ac/create-aided-topic! [device-topic] {:topic-name   @*aid-name
                                                                                                                                    :aids#-log    [device-aid#]
                                                                                                                                    :aid$->ke     (hash-map 0 (@ac/*aid#->latest-known-ke device-aid#))
                                                                                                                                    :member-aids$ [0]})]
                                                                       (ac/add-init-control-event! [device-topic personal-topic])
                                                                       (reset! as/*selected-topic-path [device-topic personal-topic])
                                                                       (reset! as/*browsing {:page :topic})))
                                                                   #_(let [topic (at/create-topic! my-did-peer #{my-did-peer} {:topic-name @*device-name})]
                                                                       (ac/add-init-control-event! topic)))}
                                                      "Create Personal grID")))]))])])])
