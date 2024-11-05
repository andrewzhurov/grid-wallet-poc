(ns app.chat
  (:require-macros [app.material :refer [speed-dial speed-dial-icon speed-dial-action
                                         icon icon-fingerprint icon-send
                                         dialog dialog-title dialog-content dialog-content-text dialog-actions
                                         text-field textarea-autosize

                                         button fab
                                         zoom fade
                                         click-away-listener]]
                   :reload-all)
  (:require [hashgraph.main :as hg]
            [hashgraph.topic :as hgt]
            [hashgraph.app.inspector :as hga-inspector]
            [hashgraph.app.tutorial :refer-macros [i]]
            [hashgraph.utils.core :refer-macros [defn* l letl letl2 when-let*] :refer [hash= merge-attr-maps conjv conjs] :as utils]
            [hashgraph.app.icons :as hga-icons]

            [app.styles :refer [reg-styles! shadow0 shadow1 shadow2 shadow3] :as styles]
            [app.state :as as]
            [app.topic :as at]
            [app.topic-viz :as atv]
            [app.creds :as ac]
            [app.creds-viz :as acv]
            [app.aid :as aa]

            [clojure.pprint]
            [rum.core :refer [defc defcs] :as rum]
            [garden.selectors :as gs]
            [garden.color :as gc]
            [garden.util :as gu]
            [garden.util]
            [garden.units :refer [px px- px+] :as gun]
            [garden.arithmetic :as ga]
            [garden.compiler]
            [app.control :as actrl]
            ))

(def message-padding (px 12))
(def color-primary (gc/rgb [51 144 236]))
(def color-primary-lighter (gc/lighten color-primary 20))
(def color-primary-lightest (gc/lighten color-primary 30))
(def color-primary-lightest2 (gc/lighten color-primary 40))
(def action-button-offset (px 10))
(def action-button-size   (px 56))
(def messages-margin-x    (ga/+ action-button-size (ga/* 2 action-button-offset)))


(def chat-styles
  [[:.new-message-button
    [:.MuiSpeedDialAction-staticTooltipLabel
     {:width :max-content}]]
   [:.chat {:position  :relative
            :height    :inherit
            :min-width (px 0)
            :width     :inherit}
    [:.messages {:display :flex
                 :flex-direction :column
                 :justify-content :end
                 :width :inherit
                 :height :inherit
                 :max-width (px 400)
                 :padding [[(px 0) messages-margin-x (px 0) messages-margin-x]]
                 :margin  [[(px 0) "auto" (px 0) "auto"]]
                 }
     [:.message {:position :relative
                 :max-width "80%"
                 :width :fit-content
                 :box-shadow shadow0
                 ;; :border "1px solid lightgray"
                 :border-radius "12px 12px 12px 0px"
                 :margin-bottom "10px"
                 :margin-top    "10px"}
      [:&.creator-info-bot {:align-self       :center
                            :border-radius    "12px"
                            }]
      [:&.creator-aid-bot {:align-self :center
                           :border-radius "12px"
                           :background-color color-primary-lightest2}]

      [:.message-content {:padding     (px message-padding)
                          :white-space :break-spaces}
       ]
      [(gs/& (gs/nth-last-child "2")) {:margin-bottom "20px"}]
      [:&.from-me {:align-self    :flex-end
                   :border-radius "12px 12px 0px 12px"}]

      [:.message-reactions {:position      :absolute
                            :bottom        (px 12)
                            :left          (px 0)
                            :right         (px 0)
                            :transform     "translate(0%, 100%)"
                            :display       :flex
                            :padding-left  (px message-padding)
                            :padding-right (px message-padding)}
       [:.message-reaction {:display          :flex
                            :align-items      :center
                            :background-color color-primary-lightest
                            :color            color-primary
                            :border-radius    "20px"
                            :box-shadow       shadow1
                            :padding          "4px 8px 4px 8px"
                            :transition       "background-color 0.2s"
                            :cursor           :pointer}
        [:svg {:vertical-align :text-bottom}]
        [:&:hover {:background-color color-primary-lighter}]
        [:&.i-reacted {:background-color color-primary}
         [:.message-reaction-count {:color "white"}]]
        [:.message-reaction-count {:margin-left "4px"
                                   :font-weight :bold
                                   :font-size   "0.875rem"}]]]

      ]]

    [:.inspectable {:transform-origin :center
                    :transition "scale 0.4s, box-shadow 0.4s !important"}
     [:&.inspected {:scale      1.05
                    :box-shadow shadow2}
      [:&.message-reaction {:scale 1.1}]]]


    [:.new-message-area {:width  "100%"
                         :display :flex
                         :justify-content :end
                         :position    :relative}
     [:.message [:&.new {:max-width "100%"
                         :width "100%"
                         :padding (ga/+ message-padding 6)
                         :border "none !important"
                         :outline "none !important"
                         :box-shadow shadow1
                         :resize :none}]]
     [:.new-message-button
      {:position         :absolute
       :bottom           (px 10)
       :right            (-> action-button-offset
                             (ga/-)
                             (ga/- 1) ;; 1 for border offset
                             (ga/- 56)) ;; size of fab / speed-dial
       :transform-origin "center calc(100% - 28px)"}]
     [:.send-message
      [:svg {}]]
     #_[:.actions-shower
      [:svg {:transition "transform 0.4s"}]
      [:.actions-hide {:position :absolute
                       :top      "0px" :right "0px" :bottom "0px" :left "0px"
                       :display  :none}]
      [:div.actions (merge (dissoc styles/card-style :padding)
                           {:position       :absolute
                            :top            "-10px"
                            :right          "0px"
                            :transform      "translate(0%, -100%)"
                            :pointer-events :none
                            :opacity        0
                            :box-shadow     shadow1
                            :transition     "opacity 0.4s"})
       [:.action {:min-width  :max-content
                  :width      "100%"
                  :padding    "12px"
                  :text-align :left
                  :cursor     :default}
        [:&:hover styles/accent-style]]]
      [:&.shown
       [:svg {:transform "rotate(45deg)"}]
       [:.actions {:opacity        1
                   :pointer-events :auto}
        [:.action {:cursor :pointer}]]]
      [:.send-message {}]]]]])
(reg-styles! ::chat chat-styles)

(at/reg-subjective-tx-handler!
 :text-message
 (fn [db {:event/keys [creator] :as event} [_ {:text-message/keys [content]}]]
   (update-in db [:feed :feed/items] conjv {:feed-item/kind       :text-message
                                            :feed-item/text       content
                                            :feed-item/creator    creator
                                            :feed-item/from-event event})))

(at/reg-subjective-tx-handler!
 :init-control
 (fn [{:keys [feed] :as db} {:event/keys [creator] :as event} _]
   (-> db (update-in [:feed :feed/items] conjv {:feed-item/kind       :text-message
                                                :feed-item/data       [[:creator creator] " set initial keys"]
                                                :feed-item/creator    :info-bot
                                                :feed-item/from-event event}))))

(at/reg-subjective-tx-handler!
 :rotate-control
 (fn [{:keys [feed] :as db} {:event/keys [creator] :as event} _]
   (-> db (update-in [:feed :feed/items] conjv {:feed-item/kind       :text-message
                                                :feed-item/data       [[:creator creator] " rotated key"]
                                                :feed-item/creator    :info-bot
                                                :feed-item/from-event event}))))

(at/reg-subjective-tx-handler!
 :propose
 (fn [{:keys [feed] :as db} {:event/keys [creator] :as event} proposal]
   (if-let [proposal-feed-item-idx (get-in feed [:feed/proposal->feed-item-idx proposal])]
     (-> db
         (update-in [:feed :feed/items proposal-feed-item-idx] (fn [feed-item] (-> feed-item
                                                                                   (update-in [:feed-item/reactions :agree] conjs creator)
                                                                                   (update-in [:feed-item/reaction->events :agree] conjs event)))))
     (-> db
         (update-in [:feed :feed/items] conjv {:feed-item/kind             :proposal
                                               :feed-item/proposal         proposal
                                               :feed-item/creator          :info-bot
                                               :feed-item/reactions        {:agree #{creator}}
                                               :feed-item/reaction->events {:agree #{event}}})
         (assoc-in [:feed :feed/proposal->feed-item-idx proposal] (count (:feed/items feed)))))))

#_
(at/reg-subjective-tx-handler!
 :agree
 (fn [{:keys [feed] :as db} {:event/keys [creator]} [_ proposal]]
   (-> db
       (update-in [:feed :feed/items (get-in feed [:feed/propose-tx->feed-item-idx proposal]) :feed-item/reactions :agree]
                  conjs creator))))

(at/reg-subjective-tx-handler!
 :assoc-did-peer
 (fn [{:keys [feed] :as db} {:event/keys [creator] :as event} _]
   (-> db
       (update-in [:feed :feed/items] conjv {:feed-item/kind       :text-message
                                             :feed-item/data       [[:creator creator] " set mailbox"]
                                             :feed-item/creator    :info-bot
                                             :feed-item/from-event event}))))

(at/reg-subjective-tx-handler!
 :connect-invite-accepted
 (fn [{:keys [feed] :as db} event [_ {:connect-invite-accepted/keys [connectee-aid]}]]
   (-> db
       (update-in [:feed :feed/items] conjv {:feed-item/kind       :text-message
                                             :feed-item/data       [[:aid connectee-aid] " accepted connect invite"]
                                             :feed-item/creator    :info-bot
                                             :feed-item/from-event event}))))

#_
(at/reg-subjective-tx-handler!
 :disclose-acdc
 (fn [{:keys [feed] :as db} {:event/keys [creator] :as event} [_ acdc]]
   (cond-> db
     (not (contains? (:feed/disclosed-acdcs feed) acdc))
     (-> (update-in [:feed :feed/items] conjv {:feed-item/kind       :text-message
                                               :feed-item/text       "<credential>"
                                               :feed-item/creator    creator
                                               :feed-item/from-event event})
         (update-in [:feed :feed/disclosed-acdcs] conjs acdc)))))


(defn create-feed-item-of-received-ke [{:keys [received-ke informed-ke] :as db} novel-tip-taped]
  (letl2 [?latest-received-ke (ac/evt->?ke novel-tip-taped)]
    (cond-> db
      (not (hash= received-ke ?latest-received-ke)) ;; latest is nil, received is some
      (-> (assoc :received-ke ?latest-received-ke)
          (update-in [:feed :feed/items]
                     (fn [feed-items]
                       (letl2 [novel-kes< (->> ?latest-received-ke
                                               (iterate :key-event/prior)
                                               (take-while #(not (hash= % received-ke)))
                                               reverse)]
                         (->> novel-kes<
                              (reduce (fn [feed-items-acc novel-ke]
                                        (conjv feed-items-acc
                                               {:feed-item/kind               :novel-ke
                                                :feed-item/ke                 novel-ke
                                                :feed-item/creator            :aid-bot
                                                :feed-item/concluded-by-event novel-tip-taped}))
                                      feed-items)))))))))

(reset! at/*post-subjective-db-handlers [create-feed-item-of-received-ke])


;; (-> @as/*selected-topic (@as/*topic->tip-taped) hg/->concluded-round js/console.log)

(defc message-view < rum/reactive
  [my-aid-topic-path topic-path {:feed-item/keys [kind data proposal text creator idx reactions from-event] :as feed-item}]
  (let [my-member-init-key (actrl/topic-path->member-init-key topic-path)
        my-creator         (-indexOf (rum/react (rum/cursor as/*topic-path->member-init-keys-log topic-path)) my-member-init-key)]
    (case kind
      :text-message
      [:div.message (merge-attr-maps (hga-inspector/inspectable from-event)
                                     {:key   idx
                                      :class [(when (keyword? creator) (str "creator-" (name creator)))
                                              (when (= creator my-creator) "from-me")]})
       [:div.message-content
        (when text
          text)
        (when data
          (for [dat data]
            (cond (string? dat) dat
                  (-> dat first (= :creator))
                  (or (some-> (rum/react (rum/cursor atv/*topic-path->viz-member-aid-info topic-path))
                              :member-aid-info/creator->member-aid-info (get (-> dat second)) :member-aid-info/alias)
                      "Device")
                  (-> dat first (= :aid))
                  (or (rum/react (rum/cursor ac/*aid->aid-name (-> dat first)))
                      "grID")
                  :else
                  "???")))]]

      :proposal
      (let [[_ kind {:acdc/keys [schema] :as acdc}] proposal
            issuer-aid#                             (-> acdc :acdc/issuer)
            issuee-aid#                             (-> acdc :acdc/attribute :issuee)
            issuer-name                             (rum/react (rum/cursor ac/*aid#->aid-name issuer-aid#))
            issuee-name                             (rum/react (rum/cursor ac/*aid#->aid-name issuee-aid#))]
        [:div.message {:key   idx
                       :class [(when (keyword? creator) (str "creator-" (name creator)))
                               (when (= creator my-creator) "from-me")]}
         [:div.message-content
          (case kind
            :incept
            "Incept ID?"
            :issue
            (case schema
              :acdc-join-invite
              (str "Invite " issuee-name " to join this ID?")
              :acdc-join-invite-accepted
              (str "Join " issuee-name "?")
              :acdc-qvi
              (str "Issue QVI credential to " issuee-name "?")
              :acdc-le
              (str "Issue LE credential to " issuee-name "?")))]
         (let [possible-reactions (if (= :incept kind)
                                    [[:agree #(do (at/add-event! topic-path {:event/tx proposal})
                                                  (ac/add-init-control-event! topic-path))]]
                                    [[:agree #(at/add-event! topic-path {:event/tx proposal})]])]
           [:div.message-reactions
            (for [[reaction-kind on-react] possible-reactions
                  :let                     [reactors   (get reactions reaction-kind)
                                            i-reacted? (contains? reactors my-creator)
                                            reacted-events (get-in feed-item [:feed-item/reaction->events reaction-kind])]]
              [:div.message-reaction (merge-attr-maps (hga-inspector/inspectable reacted-events)
                                                      (cond-> {:class    [(when i-reacted? "i-reacted")]
                                                               :key      (str reaction-kind)}
                                                        (not i-reacted?) (assoc :on-click #(on-react))))
               (hga-icons/icon :regular :circle-check :color "white" :size "sm")
               (when (>= (count reactors) 1)
                 [:span.message-reaction-count (count reactors)])])])])

      :novel-ke
      (let [{:feed-item/keys [ke concluded-by-event concluded-by-event]} feed-item]
        [:div.message.creator-aid-bot (hga-inspector/inspectable concluded-by-event)
         [:div.message-content
          (case (-> ke :key-event/type)
            :inception
            [:div.row (hga-icons/icon :solid :layer-group :color :black) "Key Event: Inception" #_"ID has been incepted"
             #_(ke-keys-view ke)
             #_(ke-anchors-view ke)]
            :rotation
            [:div.row (hga-icons/icon :solid :layer-group :color :black) "Key Event: Rotation" #_"Keys has been rotated"
             #_(ke-keys-view ke)
             #_(ke-anchors-view ke)]
            :interaction
            [:div.row (hga-icons/icon :solid :layer-group :color :black) "Key Event: Interaction" #_"Data has been anchored"
             #_(ke-anchors-view ke)])]])
      [:div.message {:style {:white-space :pre
                             :font-family :monospace}}
       (with-out-str (clojure.pprint/pprint feed-item))])))

#_(l @at/*topic->subjective-db)
(defc messages-view < rum/reactive
  [my-aid-topic-path topic-path]
  (let [feed (:feed (rum/react (rum/cursor atv/*topic-path->viz-subjective-db topic-path)))]
    (->> feed :feed/items
         (map-indexed (fn [idx feed-item]
                        (rum/with-key
                          (message-view my-aid-topic-path topic-path feed-item)
                          (str idx)))))))


(defn send-key-combo? [dom-event]
  (and (= (.-key dom-event) "Enter")
       (.-ctrlKey dom-event)))

#_
(defc issuee-actions < rum/reactive
  [topic]
  (when-let* [issuer-aid-topic               (l (rum/react as/*selected-my-aid-topic))
              issuer-aid-topic-tip-taped     (l ((rum/react as/*topic->tip-taped) issuer-aid-topic))
              issuer-aid                     (l (-> (l issuer-aid-topic-tip-taped) ac/evt->?ke-icp))
              issuee-aid                     (some-> topic
                                                     (get :member-aid->did-peers)
                                                     (->> (some (fn [[member-aid]] ;; works correctly only on 1 other-aid member
                                                                  (when (not (hash= member-aid issuer-aid))
                                                                    member-aid)))))]
    #_(let [?issuer-aid-attributed-acdc-le (->> (rum/react (rum/cursor ac/*aid->attributed-acdcs issuer-aid))
                                                (some (fn [acdc] (when (= ::ac/acdc-le (:acdc/schema acdc))
                                                                   acdc))))])
    [:<>
     [:button.action {:on-click #(ac/issue-acdc-qvi! issuer-aid-topic issuer-aid issuee-aid)}
      "Issue QVI"]
     (when-let [issuer-aid-attributed-acdc-qvi (l (->> (rum/react (rum/cursor acv/*aid->attributed-acdcs issuer-aid))
                                                       (some (fn [acdc] (when (= ::ac/acdc-qvi (:acdc/schema acdc))
                                                                          acdc)))))]
       [:button.action {:on-click #(ac/issue-acdc-le! issuer-aid-topic issuer-aid issuee-aid issuer-aid-attributed-acdc-qvi)}
        "Issue LE"])]))

#_
(defcs promote-to-id-form-dialog < (rum/local "" ::*id-name)
  [{::keys [*opened? *id-name]} topic open? on-close]
  (dialog {:open        open?
           :on-close    on-close
           "PaperProps" {"component" "form"
                         "onSubmit"  (fn [e]
                                       (.preventDefault e)
                                       (on-close)
                                       (at/set-topic-name-event! topic @*id-name)
                                       (ac/add-init-control-event! topic))}}
          (dialog-title "Promote to ID")
          (dialog-content
           (text-field {:auto-focus true
                        :margin     "dense"
                        :id         "id-name"
                        :name       "Name"
                        :label      "ID Name"
                        :full-width true
                        :variant    "standard"
                        :on-change  #(reset! *id-name (-> % .-target .-value))})
           (dialog-actions
            (button {:on-click on-close} "Nevermind")
            (button {:type     "submit"
                     :disabled (empty? @*id-name)}
                    "Promote")))))

(defcs propose-join-invite-dialog < rum/reactive
  [{::keys [*opened? *id-name]} topic-path open? on-close]
  (let [my-aid#     (rum/react (rum/cursor ac/*topic-path->my-aid# topic-path))
        my-aid-name (rum/react (rum/cursor ac/*aid#->aid-name my-aid#))]
    (dialog {:open     open?
             :on-close on-close}
            (dialog-title "Propose Join Invite")
            (dialog-content
              [:div {:style {:margin-bottom "10px"}} my-aid-name "'s contacts:"]
              [:div {:style {:display :flex}}
               (when-let* [contact-aids# (rum/react (rum/cursor ac/*topic-path->contact-aids# topic-path))]
                 (for [contact-aid# contact-aids#]
                   (aa/aid#-view contact-aid# {:on-click #(do (on-close)
                                                              (ac/propose-issue-acdc-join-invite! topic-path my-aid# contact-aid#))})))]))))

(defn add-text-message-event! [text-message]
  (at/add-event! {hg/tx [:text-message {:text-message/content text-message}]}))

(defcs new-text-message-button < rum/reactive (rum/local false ::*actions-shown?) (rum/local false ::*promote-to-id-modal-open?) (rum/local false ::*propose-join-invite-dialog-open?)
  [{::keys [*actions-shown? *promote-to-id-modal-open? *propose-join-invite-dialog-open?]} my-aid-topic-path topic-path]
  (let [*new-message (rum/cursor as/*topic-path->new-message topic-path)
        new-message  (or (rum/react *new-message) "")
        can-send?    (not (empty? new-message))

        ;; mixing promote-to-id and issue contexts,
        ;; in promote, there's selected-my-aid-topic, but we want to create a new one - need to check if inception's been instantiated
        init-control-initiated? (-> (rum/react (rum/cursor as/*topic-path->tip-taped topic-path)) ac/init-control-initiated?)
        group-topic-path?       (-> (rum/react ac/*group-topic-paths) (contains? topic-path))
        contact-topic-path?     (-> (rum/react ac/*contact-topic-paths) (contains? topic-path))
        ]
    [:<>
     #_(promote-to-id-form-dialog topic @*promote-to-id-modal-open? #(reset! *promote-to-id-modal-open? false))
     (propose-join-invite-dialog topic-path @*propose-join-invite-dialog-open? #(reset! *propose-join-invite-dialog-open? false))
     [:div.new-message-area
      (textarea-autosize {:class        ["message" "from-me" "new"]
                          :key          "new-message"
                          :placeholder  "Message"
                          :value        new-message
                          :on-change    #(reset! *new-message (-> % .-target .-value))
                          :on-key-press #(when (and can-send? (send-key-combo? %))
                                           (add-text-message-event! new-message)
                                           (reset! *new-message ""))})
      (let [ts-duration         400
            transition-duration #js {"enter" ts-duration
                                     "exit"  ts-duration}]
        [(fade {:key     true
                :in      can-send?
                :timeout transition-duration
                :style   {:transition-delay (if can-send? 0 0)}}
               (fab {:key      "new-message-button"
                     :class    "new-message-button"
                     :color    "primary"
                     :on-click #(when can-send?
                                  (add-text-message-event! new-message)
                                  (reset! *new-message ""))}
                    (icon-send)))
         (click-away-listener {:on-click-away #(reset! *actions-shown? false)}
                              (fade {:key      false
                                     :in       (not can-send?)
                                     :timeout  transition-duration
                                     :style    {:transition-delay (if-not can-send? 0 0)}
                                     :on-click #(swap! *actions-shown? not)}
                                    (speed-dial {:key        "button-actions"
                                                 :class      "new-message-button"
                                                 "ariaLabel" "SpeedDial basic example"
                                                 :icon       (speed-dial-icon)
                                                 :open       @*actions-shown?}
                                                (when init-control-initiated?
                                                  (speed-dial-action {:key           :rotate
                                                                      :icon          (hga-icons/icon :solid :key)
                                                                      :tooltip-title "Rotate key"
                                                                      :tooltip-open  true
                                                                      :on-click      #(ac/add-rotate-control-event! topic-path)}))
                                                (when (and (> (count my-aid-topic-path) 1)
                                                           (not-empty (rum/react (rum/cursor ac/*topic-path->contact-aids# topic-path))))
                                                  (speed-dial-action {:key           "join invite"
                                                                      :icon          (icon-fingerprint)
                                                                      :tooltip-title "Propose Join Invite"
                                                                      :tooltip-open  true
                                                                      :on-click      #(reset! *propose-join-invite-dialog-open? true)}))
                                                (when (and group-topic-path? (not init-control-initiated?))
                                                  (speed-dial-action {:key           "1"
                                                                      :icon          (icon-fingerprint)
                                                                      :tooltip-title "Propose to incept ID"
                                                                      :tooltip-open  true
                                                                      :on-click      #(do (at/add-event! topic-path {hg/tx [:propose :incept]})
                                                                                          (ac/add-init-control-event! topic-path))}))
                                                #_
                                                (when group-topic-path?
                                                  (speed-dial-action {:key           "1"
                                                                      :icon          (icon-fingerprint)
                                                                      :tooltip-title "Add member"
                                                                      :tooltip-open  true
                                                                      :on-click      #(reset! *add-member-dialog-open? true)}))
                                                #_
                                                (speed-dial-action {:key           "1"
                                                                    :icon          (icon-fingerprint)
                                                                    :tooltip-title "Promote to ID"
                                                                    :tooltip-open  true
                                                                    :on-click      #(ac/add-init-control-event! topic-path)}))))])]]

    #_(if can-send?
        [:div.new-message-button.send-message
         {:on-click #(do (add-text-message! new-message)
                         (reset! *new-message ""))}
         (icons/icon :regular :send :size :2xl)]

        [:div.new-message-button.actions-shower.clean {:class    (when (rum/react *actions-shown?) "shown")
                                                       :on-click #(swap! *actions-shown? not)}
         (icons/icon :solid :plus :color "white" :size :lg)
         [:div.actions
          (if (-> (rum/react (rum/cursor as/*topic->tip-taped topic))
                  ac/init-control-initiated?)
            (my-aid-actions topic)
            [:<>
             [:button.action {:on-click #(ac/add-init-control-event! topic)}
              "Promote to ID"]
             (issuee-actions topic)])]])))

(defc chat-view < rum/static rum/reactive
  [my-aid-topic-path topic-path]
  [:div.chat
   [:div.messages
    (messages-view my-aid-topic-path topic-path)
    (new-text-message-button my-aid-topic-path topic-path)]])

(defc topic-path-view < rum/reactive
  [my-aid-topic-path topic-path]
  [:div.topic
   (chat-view my-aid-topic-path topic-path)
   (atv/topic-path-viz topic-path)])
