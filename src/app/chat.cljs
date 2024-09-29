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
            [hashgraph.utils.core :refer-macros [defn* l letl letl2 when-let*] :refer [conjv conjs] :as utils]
            [hashgraph.app.icons :as icons]

            [app.styles :refer [reg-styles! shadow0 shadow1 shadow2 shadow3] :as styles]
            [app.state :as as]
            [app.topic :as at]
            [app.topic-viz :as atv]
            [app.creds :as ac]
            [app.creds-viz :as acv]
            [app.utils :refer-macros [incm]]

            [clojure.pprint]
            [rum.core :refer [defc defcs] :as rum]
            [garden.selectors :as gs]
            [garden.color :as gc]
            [garden.util :as gu]
            [garden.util]
            [garden.units :refer [px px- px+] :as gun]
            [garden.arithmetic :as ga]
            [garden.compiler]))

(def message-padding (px 12))
(def color-primary (gc/rgb [51 144 236]))
(def color-primary-lighter (gc/lighten color-primary 20))
(def color-primary-lightest (gc/lighten color-primary 30))
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
      [:&.creator-info-bot {:align-self :center
                            :border-radius "12px"}]
      [:&.creator-aid-bot {:align-self :center
                           :border-radius "12px"}]
      [:.message-content {:padding     (px message-padding)
                          :white-space :break-spaces}]
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

(defn my? [creator]
  (= @as/*my-did-peer creator))

(at/reg-subjective-tx-handler!
 :text-message
 (fn [db {:event/keys [creator]} [_ {:text-message/keys [content]}]]
   (update-in db [:feed :feed/items] conjv {:feed-item/kind    :text-message
                                            :feed-item/text    content
                                            :feed-item/creator creator
                                            :feed-item/my?     (my? creator)})))

(at/reg-subjective-tx-handler!
 :init-control
 (fn [{:keys [feed] :as db} {:event/keys [creator]} _]
   (-> (if (-> feed :feed/init-control-feed-item-idx nil?)
         (-> db
             (update-in [:feed :feed/items] conjv {:feed-item/kind      :promote-to-id
                                                   :feed-item/text      "Promote to ID"
                                                   :feed-item/creator   creator
                                                   :feed-item/my?       (my? creator)
                                                   :feed-item/reactions {:agree #{creator}}})
             (assoc-in [:feed :feed/init-control-feed-item-idx] (count (:feed/items feed))))
         (-> db
             (update-in [:feed :feed/items (:feed/init-control-feed-item-idx feed) :feed-item/reactions :agree]
                        conjs creator)))
       (assoc :consensus-enabled? true))))

(at/reg-subjective-tx-handler!
 :propose
 (fn [{:keys [feed] :as db} {:event/keys [creator]} tx]
   (-> db
       (update-in [:feed :feed/items] conjv {:feed-item/kind      :proposal
                                             :feed-item/text      "Issue credential?"
                                             :feed-item/proposal  tx
                                             :feed-item/creator   creator
                                             :feed-item/my?       (my? creator)
                                             :feed-item/reactions {:agree #{creator}}})
       (update-in [:feed :feed/propose-tx->feed-item-idx] assoc tx (count (:feed/items feed))))))

(at/reg-subjective-tx-handler!
 :agree
 (fn [{:keys [feed] :as db} {:event/keys [creator]} [_ proposal]]
   (-> db
       (update-in [:feed :feed/items (get-in feed [:feed/propose-tx->feed-item-idx proposal]) :feed-item/reactions :agree]
                  conjs creator))))

(at/reg-subjective-tx-handler!
 :reg-did-peers
 (fn [{:keys [feed] :as db} {:event/keys [creator]} _]
   (-> db
       (update-in [:feed :feed/items] conj {:feed-item/kind    :text-message
                                            :feed-item/text    "Mailbox registered"
                                            :feed-item/creator :info-bot}))))

(at/reg-subjective-tx-handler!
 :connect-invite-accepted
 (fn [{:keys [feed] :as db} _ _]
   (-> db
       (update-in [:feed :feed/items] conjv {:feed-item/kind :text-message
                                             :feed-item/text "Accepted connect invite"
                                             :feed-item/creator :info-bot}))))

(at/reg-subjective-tx-handler!
 :disclose-acdc
 (fn [{:keys [feed] :as db} {:event/keys [creator]} [_ acdc]]
   (cond-> db
     (not (contains? (:feed/disclosed-acdcs feed) acdc))
     (-> (update-in [:feed :feed/items] conjv {:feed-item/kind    :text-message
                                               :feed-item/text    "<credential>"
                                               :feed-item/creator creator})
         (update-in [:feed :feed/disclosed-acdcs] conjs acdc)))))


(defn create-feed-item-of-received-ke [{:keys [received-ke informed-ke] :as db} novel-tip-taped]
  ;; (js/console.warn (with-out-str (clojure.pprint/pprint db)))
  ;; (js/console.warn (with-out-str (clojure.pprint/pprint (-> event hg/evt->db))))
  (l db)
  (l (-> novel-tip-taped hg/evt->db))
  ;; (js* "debugger;")
  (letl2 [?latest-received-ke (ac/evt->?ke novel-tip-taped)]
    (cond-> db
      (not= (l received-ke) ?latest-received-ke) ;; latest is nil, received is some
      (-> (assoc :received-ke ?latest-received-ke)
          (update-in [:feed :feed/items]
                     (fn [feed-items]
                       (letl2 [novel-kes< (->> ?latest-received-ke
                                             (iterate :key-event/prior)
                                             (take-while #(not= % received-ke))
                                             reverse)]
                         (->> novel-kes<
                              (reduce (fn [feed-items-acc novel-ke]
                                        (conjv feed-items-acc
                                               (case (:key-event/type novel-ke)
                                                 :inception   {:feed-item/kind    :ke/inception
                                                               :feed-item/text    "ID has been incepted"
                                                               :feed-item/creator :aid-bot
                                                               :feed-item/concluded-by-event novel-tip-taped
                                                               :feed-item/member-aid->signing-key+next-signing-key-hash+evt nil}
                                                 :interaction {:feed-item/kind    :ke/interaction
                                                               :feed-item/text    "Credential has been issued"
                                                               :feed-item/creator :aid-bot}
                                                 :rotation    {:feed-item/kind    :ke/rotation
                                                               :feed-item/text    "Keys been rotated"
                                                               :feed-item/creator :aid-bot})))
                                      feed-items)))))))))

(reset! at/*post-subjective-db-handlers [create-feed-item-of-received-ke])


;; (-> @as/*selected-topic (@as/*topic->tip-taped) hg/->concluded-round js/console.log)

(defc message-view < rum/reactive
  [topic {:feed-item/keys [kind proposal text creator my? idx reactions] :as feed-item}]
  (case kind
    :text-message
    [:div.message {:key   idx
                   :class [(when (keyword? creator)
                             (str "creator-" (name creator)))
                           (when my? "from-me")]}
     [:div.message-content
      text]
     (let [possible-reactions (case kind
                                :promote-to-id [[:agree #(ac/add-init-control-event! topic)]]
                                :proposal      [[:agree #(at/add-event! topic {:event/tx [:agree proposal]})]]
                                [])]
       (when (not-empty possible-reactions)
         [:div.message-reactions
          (for [[reaction-kind on-react] possible-reactions
                :let                     [reactors   (get reactions reaction-kind)
                                          i-reacted? (contains? reactors (rum/react as/*my-did-peer))]]
            [:div.message-reaction {:class    (when i-reacted? "i-reacted")
                                    :key      (str reaction-kind)
                                    :on-click #(on-react)}
             (icons/icon :regular :circle-check :color "white" :size "sm")
             (when (>= (count reactors) 1)
               [:span.message-reaction-count (count reactors)])])]))]

    :ke/inception
    (let [{:feed-item/keys [member-aid->signing-key+next-signing-key-hash+evt
                            concluded-by-event]} feed-item]
      [:div.message.creator-aid-bot
       [:div.message-content
        "ID has been incepted"
        "member | signing key | next signing key hash | set in event"
        (for [[member-aid [signing-key next-signing-key-hash evt]] member-aid->signing-key+next-signing-key-hash+evt]
          [:div member-aid signing-key next-signing-key-hash])]])
    [:div.message {:style {:white-space :pre
                           :font-family :monospace}}
     (with-out-str (clojure.pprint/pprint feed-item))]))

#_(l @at/*topic->subjective-db)
(defc messages-view < rum/reactive
  [my-aid-topic my-aid my-creator topic]
  (let [feed (:feed (l (rum/react (rum/cursor atv/*topic->viz-subjective-db (l topic))
                                  #_(rum/cursor at/*topic->subjective-db topic)
                                  #_at/*viz-subjective-db)))]
    (->> feed :feed/items
         (map-indexed (fn [idx feed-item]
                        (rum/with-key
                          (message-view topic feed-item)
                          (str idx)))))))


(defn send-key-combo? [dom-event]
  (and (= (.-key dom-event) "Enter")
       (.-ctrlKey dom-event)))

(defc my-aid-actions < rum/reactive
  [topic]
  [:<>
   [:button.action {:on-click #(ac/rotate-key! topic)}
    "Rotate pre-rotated key"]])

(defc issuee-actions < rum/reactive
  [topic]
  (when-let* [issuer-aid-topic               (l (rum/react as/*selected-my-aid-topic))
              issuer-aid-topic-tip-taped     (l ((rum/react as/*topic->tip-taped) issuer-aid-topic))
              issuer-aid                     (l (-> (l issuer-aid-topic-tip-taped) ac/evt->?ke-icp))
              issuee-aid                     (some-> topic
                                                     (get :member-aid->did-peers)
                                                     (->> (some (fn [[member-aid]] ;; works correctly only on 1 other-aid member
                                                                  (when (not= member-aid issuer-aid)
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

(defcs new-text-message-button < rum/reactive (rum/local false ::*actions-shown?) (rum/local false ::*promote-to-id-modal-open?)
  [{::keys [*actions-shown? *promote-to-id-modal-open?]} my-aid-topic my-aid topic add-text-message!]
  (let [*new-message (rum/cursor as/*topic->new-message topic)
        new-message  (or (rum/react *new-message) "")
        can-send?    (not (empty? new-message))
        add-text-message! (fn [text-message]
                            (add-text-message! {:text-message/content text-message}))

        ;; mixing promote-to-id and issue contexts,
        ;; in promote, there's selected-my-aid-topic, but we want to create a new one - need to check if inception's been instantiated
        init-control-initiated? (-> (rum/react (rum/cursor as/*topic->tip-taped topic)) ac/init-control-initiated?)
        group-topic?            (-> (rum/react (rum/cursor ac/*my-aid->group-topics my-aid)) (contains? topic))
        ]
    [:<>
     #_(promote-to-id-form-dialog topic @*promote-to-id-modal-open? #(reset! *promote-to-id-modal-open? false))
     [:div.new-message-area
      (textarea-autosize {:class        ["message" "from-me" "new"]
                          :key          "new-message"
                          :placeholder  "Message"
                          :value        new-message
                          :on-change    #(reset! *new-message (-> % .-target .-value))
                          :on-key-press #(when (and can-send? (send-key-combo? %))
                                               (add-text-message! new-message)
                                               (reset! *new-message ""))})
      (let [ts-duration         400
            transition-duration #js {"enter" ts-duration
                                     "exit"  ts-duration}]
        [(fade {:key     true
                :in      can-send?
                :timeout transition-duration
                :style   {:transition-delay (if can-send? 0 0)}}
               (fab {:class "new-message-button"
                     :color "primary"
                     :on-click #(when can-send?
                                  (add-text-message! new-message)
                                  (reset! *new-message ""))}
                    (icon-send)))
         (click-away-listener {:on-click-away #(reset! *actions-shown? false)}
                              (fade {:key      false
                                     :in       (not can-send?)
                                     :timeout  transition-duration
                                     :style    {:transition-delay (if-not can-send? 0 0)}
                                     :on-click #(swap! *actions-shown? not)}
                                    (speed-dial {:class      "new-message-button"
                                                 "ariaLabel" "SpeedDial basic example"
                                                 :icon       (speed-dial-icon)
                                                 :open       @*actions-shown?}
                                                (when init-control-initiated?
                                                  (speed-dial-action {:key           :rotate
                                                                      :icon          (icons/icon :solid :key)
                                                                      :tooltip-title "Rotate key"
                                                                      :tooltip-open  true
                                                                      :on-click      #(ac/rotate-key! topic)}))
                                                (when (and group-topic? (not init-control-initiated?))
                                                  (speed-dial-action {:key           "1"
                                                                      :icon          (icon-fingerprint)
                                                                      :tooltip-title "Promote to ID"
                                                                      :tooltip-open  true
                                                                      :on-click      #(ac/add-init-control-event! topic)}))
                                                #_
                                                (speed-dial-action {:key           "1"
                                                                    :icon          (icon-fingerprint)
                                                                    :tooltip-title "Promote to ID"
                                                                    :tooltip-open  true
                                                                    :on-click      #(ac/add-init-control-event! topic)}))))])]]

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
  [my-aid-topic my-aid my-creator topic add-text-message!]
  [:div.chat
   [:div.messages
    (messages-view my-aid-topic my-aid my-creator topic)
    (new-text-message-button my-aid-topic my-aid topic add-text-message!)]])

(defn add-text-message! [topic my-creator text-message]
  (swap! as/*topic->tip-taped update topic
         (fn [tip-taped]
           (let [new-tip       (cond-> {hg/creator       my-creator
                                        hg/creation-time (.now js/Date)
                                        hg/tx            [:text-message text-message]}
                                 tip-taped (assoc hg/self-parent tip-taped))
                 novel-events  [new-tip]
                 new-tip-taped (hgt/tip+novel-events->tip-taped new-tip novel-events)]
             new-tip-taped))))

(defc topic-view < rum/reactive
  []
  (when-let* [my-aid-topic (rum/react as/*selected-my-aid-topic)
              my-aid       (rum/react (rum/cursor ac/*my-aid-topic->my-aid my-aid-topic))
              my-creator   (rum/react as/*my-did-peer)
              topic        (rum/react as/*selected-topic)]
    [:div.topic
     (chat-view my-aid-topic my-aid my-creator topic (partial add-text-message! topic my-creator))
     (atv/topic-viz topic)]))
