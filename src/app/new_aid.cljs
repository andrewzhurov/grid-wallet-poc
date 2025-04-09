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
            [app.io :as io]

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

(defonce *temp-device-topic-path (atom [:device-topic]))
(defonce *temp-personal-topic-path (atom [:personal-topic]))

;; Create both ahead-of-time,
;; Device mailbox is needed to create personal AID (as it contains device aid).
;; Personal mailbox is needed to show Personal AID right away as an AID (left on the navbar),
;; otherwise we'd see that Personal AID Topic is listed as a group before :assoc-did-peer is executed.
(defonce _ (io/create-mailbox! *temp-device-topic-path))
(defonce __ (io/create-mailbox! *temp-personal-topic-path))
(defn mount-temp-mailbox! [*temp-topic-path topic-path]
  (let [temp-topic-path @*temp-topic-path]
    (swap! as/*topic-path->mailbox
           (fn [topic-path->mailbox]
             (-> topic-path->mailbox
                 (dissoc temp-topic-path)
                 (assoc topic-path (topic-path->mailbox temp-topic-path)))))
    (reset! *temp-topic-path topic-path)))

(defn abandon-temp-mailbox! [*temp-topic-path]
  (let [temp-topic-path @*temp-topic-path]
    (io/abandon-mailbox! @(rum/cursor as/*topic-path->mailbox temp-topic-path))
    (swap! as/*topic-path->mailbox dissoc temp-topic-path)))

(defcs new-aid-view < rum/reactive (rum/local "" ::*device-name) (rum/local "" ::*aid-name) (rum/local false ::*tab)
  [{::keys [*device-name *aid-name *tab]}]
  (let [create-device-aid! #(let [device-init-key   (actrl/topic-path->init-key [])
                                  device-topic      {:topic-name           @*device-name
                                                     :member-init-keys     [device-init-key]
                                                     :member-init-keys-log [device-init-key]
                                                     :threshold            [[1 1]]
                                                     :total-stake          hg/total-stake ;; ideally this is not needed for one-member hg
                                                     :active-creators      #{0}
                                                     :stake-map            {0 hg/total-stake}}
                                  device-topic-path [device-topic]]
                              ;; :device-topic is used to have a stable mailbox location where it stores state
                              (at/add-event! device-topic-path {hg/topic device-topic})
                              (ac/add-init-control-event! device-topic-path)
                              (mount-temp-mailbox! *temp-device-topic-path device-topic-path)
                              device-topic-path)]
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
     (let [?device-did-peer (rum/react (rum/cursor as/*topic-path->did-peer (rum/react *temp-device-topic-path)))]
       [:<>
        (fade {:key     1
               :in      (and (some? ?device-did-peer)
                             (not (empty? @*device-name)))
               :timeout {"enter" 400
                         "exit"  400}}

              (button {:style    {:margin-top    "24px"
                                  :margin-bottom "24px"}
                       ;; :disabled (empty? @*device-name)
                       :on-click #(let [device-topic-path (create-device-aid!)]
                                    (abandon-temp-mailbox! *temp-personal-topic-path)
                                    (reset! as/*selected-topic-path device-topic-path)
                                    (reset! as/*browsing {:page :topic}))}
                      "Create Device grID"))

        (fade {:key     :personal-aid
               :in      (and (some? ?device-did-peer)
                             (not (empty? @*device-name)))
               :timeout {"enter" 400
                         "exit"  400}}

              [:div.new-aid__login
               (typography {:variant "subtitle1"} "or")
               (tab-context {:value @*tab}
                            (box {:sx {:border-bottom 1
                                       :padding-top   "24px"
                                       :border-color  "divider"}}
                                 (tab-list (tab {:value    "login"
                                                 :on-click #(reset! *tab "login")
                                                 :label    "Join Personal grID"})
                                           (tab {:value    "signup"
                                                 :on-click #(reset! *tab "signup")
                                                 :label    "Create Personal grID"})))

                            (tab-panel {:value "login"}
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

                            (tab-panel {:value "signup"
                                        :sx    {:width      "100%"
                                                :box-sizing :border-box}}

                                       [:div {:style {:display        :flex
                                                      :flex-direction :column
                                                      :align-items    :center}}

                                        (text-field {:sx         {:width "100%"}
                                                     :label      "Person Name"
                                                     :value      @*aid-name
                                                     :auto-focus true
                                                     :on-change  #(reset! *aid-name (-> % .-target .-value))})

                                        (let [?personal-did-peer (rum/react (rum/cursor as/*topic-path->did-peer (rum/react *temp-personal-topic-path)))]
                                          (fade {:key     1
                                                 :in      (and (not (empty? @*aid-name))
                                                               ?personal-did-peer)
                                                 :timeout {"enter" 400
                                                           "exit"  400}}

                                                (button {:style    {:margin-top    "24px"
                                                                    :margin-bottom "24px"}
                                                         ;; :disabled (empty? @*device-name)
                                                         :on-click #(let [device-topic-path   (create-device-aid!)
                                                                          device-aid#         (or (get @ac/*topic-path->my-aid# device-topic-path) (throw (ex-info "device aid is not present" {:device-topic-cr (get @as/*topic-path->cr device-topic-path)})))
                                                                          personal-topic      (ac/create-aided-topic! device-topic-path {:topic-name   @*aid-name
                                                                                                                                         :aids#-log    [device-aid#]
                                                                                                                                         :aid$->ke     (hash-map 0 (@ac/*aid#->latest-known-ke device-aid#))
                                                                                                                                         :member-aids$ [0]})
                                                                          personal-topic-path (conj device-topic-path personal-topic)]
                                                                      (ac/add-init-control-event! personal-topic-path)
                                                                      (mount-temp-mailbox! *temp-personal-topic-path personal-topic-path))}
                                                        "Create Personal grID")))]))])])]))
