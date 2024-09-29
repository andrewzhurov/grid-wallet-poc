(ns app.contacts
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
            [hashgraph.utils.core :refer [hash= flatten-all] :refer-macros [defn* l letl letl2 when-let*] :as utils]
            [hashgraph.app.icons :as icons]

            [app.styles :refer [reg-styles! kind->css] :as styles]
            [app.state :as as]
            [app.io :refer [reg< send-message]]
            [app.topic :as at]
            [app.utils :refer [clipboard-watchable? reg-on-clipboard-text!]]
            [app.creds :as ac]
            [app.creds-viz :as acv]
            [app.topic-feed-unread :as atfu]

            [garden.units :refer [px px- px+ px-div] :as gun]
            [rum.core :refer [defc defcs] :as rum]
            [clojure.edn :as edn]
            [clojure.set :as set]))

(def contacts-styles
  [[:.contact {:width           "80px"
               :height          "80px"
               :display         :flex
               :align-items     :center
               :justify-content :center
               :transition      "background-color 0.2s"
               :cursor          :pointer}
    [:&.group
     [:.contact-name {:border-radius "0%"}]]
    [:&.group-aid
     [:.contact-name {:border-radius "25%"}]]
    [:&.selected-my-aid styles/accent-style-light]
    [:&:hover styles/accent-style
     [:.contact-name
      [:.connect-invite {:opacity 1}]]]
    [:&.selected styles/accent2-style]
    [:.contact-name {:width           "65%"
                     :height          "65%"
                     :position        :relative
                     :display         :flex
                     :justify-content :center
                     :align-items     :center
                     :border          "1px solid gray"
                     :border-radius   "50%"
                     :background-color "white"}
     [:.contact-connectivity {:position      :absolute
                              :top           "0px"
                              :right         "0px"
                              :width         "12px"
                              :height        "12px"
                              :border-radius "50%"}
      [:&.connected {:background-color "green"}]
      [:&.disconnected {:background-color "gray"}]]

     [:.aid-certified {:position       :absolute
                       :right          "0px"
                       :top            "0px"
                       :transform      "translate(25%, -15%)"}]

     [:.connect-invite {:position :absolute
                          :bottom   "-6px"
                          :right    "-6px"
                          :width    "24px"
                          :height   "24px"
                          :background-color :white
                          :border "1px solid lightgray"
                          :cursor :pointer
                          :opacity 0
                        :transition "opacity 0.3s"}]]
    [:&.accept-connect-invite {:opacity 0
                               :transition "0.4s"
                               :cursor     :default}
     [:&.shown {:opacity 1
                :cursor :pointer}]]]
   [:.contacts {:grid-area      "contacts"
                :max-height     "100%"
                :max-width      "100%"
                :display        :flex
                :flex-direction :column}]

   [:.add
    [:button
     {:width            "65%"
      :height           "65%"
      :position         :relative
      :display          :flex
      :justify-content  :center
      :align-items      :center
      :border           "1px solid gray"
      :border-radius    "50%"
      :background-color "white"}]]])

(reg-styles! ::contacts contacts-styles)

;; (defn >profile [did]
;;   (let [message {:type "https://didcomm.org/user-profile/1.0/profile"
;;                  :body {:profile (-> @as/*did->profile (get @as/*my-did-peer))}}]
;;     (send-message did message)))

;; (defn set-profile! [profile+]
;;   (let [profile (select-keys profile+ [:profile/did :profile/alias])]
;;     (swap! as/*did->profile assoc (:profile/did profile) profile)))

;; (reg< "https://didcomm.org/user-profile/1.0/profile"
;;       (fn [{{{:profile/keys [did] :as profile} :profile} :body
;;             :keys                                        [from to created_time]
;;             :as                                          message}]
;;         (if (not= did from)
;;           (js/console.warn "Received profile did not have matching 'from' and ':profile/did'" message)
;;           (do (set-profile! profile)
;;               (swap! as/*inbound-contacts conj did)))))

;; ---- connect-invite ----
#_
(defn >connect-intive-accepted [{:connect-invite/keys [target] :as connect-invite}]
  (let [message {:type "https://didcomm.org/connect-invite/1.0/accepted"
                 :body (hash connect-invite)}]
    (send-message target message)))

#_
(reg< "https://didcomm.org/connect-invite/1.0/accepted"
      (fn [{connect-invite-hash :body
            :keys               [from]}]
        ;; TODO remove connect-invite from pending
        (swap! as/*outbound-contacts conj from)
        (swap! as/*inbound-contacts conj from)))

(defn copy-to-clipboard! [text]
  (-> js/navigator
      (.-clipboard)
      (.writeText (l text))))

(defn create-connect-invite [connect-invite*]
  ;; could be packed as DIDComm message for consistent io, this is a message that is transported via a kind of sneakernet / OOB
  (let [connect-invite (merge {:connect-invite/at    (.now js/Date)
                               :connect-invite/nonce (random-uuid)
                               :connect-invite/sig   ""}
                              connect-invite*)]
    ;; mayb also store sent invites locally, so you can revoke it
    connect-invite))
(reduce into [] '([1 2] [3 4]))
(defcs connect-invite-view < rum/reactive (rum/local false :*input-opened?)
  [{:keys [input-opened?]} target-aid]
  ;; TODO input for copying connect-invite if clipboard is not supported
  ;; (js* "debugger;")
  (l :connect-invite)
  (when-let* [invitor-aid          (l (rum/react ac/*selected-my-aid))
              target-aid-did-peers (l (rum/react (rum/cursor ac/*aid->did-peers target-aid)))]
    (let [connect-invite* {:connect-invite/invitor-aid      invitor-aid
                           :connect-invite/target-aid       target-aid
                           :connect-invite/target-did-peers target-aid-did-peers}]
      [:button.connect-invite {:on-click #(do (.stopPropagation %) (copy-to-clipboard! (pr-str (create-connect-invite connect-invite*))))
                               :title    "Connect invite"}
       (icons/icon :solid :link)])))

(defn accept-connect-invite! [connectee-creator connectee-topic connectee-aid connectee-aid-did-peers {:connect-invite/keys [target-aid target-did-peers] :as connect-invite}]
  (l [connectee-aid connectee-aid-did-peers connect-invite])
  (let [topic-members (set/union (set connectee-aid-did-peers) (set target-did-peers))]
    (at/create-topic! connectee-creator topic-members
                      {:member-aid->did-peers {target-aid    target-did-peers
                                               connectee-aid connectee-aid-did-peers}}
                      {:event/tx [:connect-invite-accepted {:connect-invite-accepted/connect-invite connect-invite
                                                            :connect-invite-accepted/connectee-aid  connectee-aid}]})))



#_
(defn accept-connect-invite! [target connect-invite]
  (swap! as/*outbound-contacts conj target)
  (swap! as/*inbound-contacts conj target)
  (>connect-intive-accepted connect-invite))

#_
(add-watch as/*outbound-contacts ::share-profile-with-outbound-contacts
           (fn [_ _ old-outbound-contacts new-outbound-contacts]
             (l [old-outbound-contacts new-outbound-contacts])
             (let [novel-outbound-contacts (set/difference new-outbound-contacts old-outbound-contacts)]
               (l novel-outbound-contacts)
               (doseq [novel-outbound-contact novel-outbound-contacts]
                 (at/create-topic! @as/*my-did-peer #{(l novel-outbound-contact) (l @as/*my-did-peer)})
                 #_(profile> (l novel-outbound-contact))))))

(defcs accept-connect-invite-view < rum/reactive (rum/local false ::*input-open?)
  {:will-mount (fn [state]
                 (let [*connect-invite   (atom nil)
                       on-clipboard-text (fn [clipboard-text]
                                           (when-let [edn (edn/read-string clipboard-text)]
                                             (when (:connect-invite/target-aid edn)
                                               (reset! *connect-invite edn))))]
                   (cond-> (assoc state
                                  ::*connect-invite          *connect-invite
                                  ::on-clipboard-text        on-clipboard-text)
                     (l clipboard-watchable?)
                     (assoc ::unreg-on-clipboard-text! (reg-on-clipboard-text! on-clipboard-text)))))
   :will-unmount (fn [{::keys [unreg-on-clipboard-text!] :as state}]
                   (when unreg-on-clipboard-text!
                     (unreg-on-clipboard-text!))
                   state)}
  [{::keys [*connect-invite *input-open? on-clipboard-text]} selected-my-aid-topic selected-my-aid]
  (let [?connect-invite          (rum/react *connect-invite)
        ?connectee-creator       (rum/react as/*my-did-peer)
        connectee-aid-topic      selected-my-aid-topic
        ?connectee-aid           selected-my-aid
        ?connectee-aid-did-peers (some-> connectee-aid-topic
                                         (->> (rum/cursor as/*topic->tip-taped))
                                         (rum/react)
                                         at/->projected-db
                                         :member-aid->did-peers vals
                                         (->> (reduce into #{})))
        shown?                   (when-let [{:connect-invite/keys [target-aid target-did-peers]} ?connect-invite]
                                   (and target-aid target-did-peers
                                        ?connectee-creator connectee-aid-topic ?connectee-aid ?connectee-aid-did-peers
                                        (not= target-aid ?connectee-aid)
                                        (not (contains? (rum/react (rum/cursor ac/*my-aid->contact-aids selected-my-aid))
                                                        target-aid))))]
    (if clipboard-watchable?
      [:div.contact.accept-connect-invite
       (when shown?
         {:class    "shown"
          :on-click #(accept-connect-invite! ?connectee-creator connectee-aid-topic ?connectee-aid ?connectee-aid-did-peers ?connect-invite)})
       [:div.contact-name
        (icons/icon :solid :link)]]

      [:<>
       (when shown?
         (reset! *connect-invite nil)
         (reset! *input-open? false)
         (accept-connect-invite! ?connectee-creator connectee-aid-topic ?connectee-aid ?connectee-aid-did-peers ?connect-invite)
         nil)
       [:div.contact.accept-connect-invite
        {:class    "shown"
         :on-click #(swap! *input-open? not)}
        [:div.contact-name
         (icons/icon :solid :link)]
        (when @*input-open?
          (text-field {:class      "manual-input"
                       :auto-focus true
                       :label      "Connect Invite"
                       :on-change  #(->> % .-target .-value on-clipboard-text)}))]])))

(defc aid-attributions-view < rum/reactive
  [aid]
  (let [aid-attributed-acdcs (rum/react (rum/cursor acv/*aid->attributed-acdcs aid))
        relevant-acdcs       (->> aid-attributed-acdcs
                                  (filter (comp #{::ac/acdc-qvi ::ac/acdc-le} :acdc/schema))
                                  (sort-by :acdc/schema))]
    (when (not-empty relevant-acdcs)
      [:div.aid-certified
       (icons/icon :solid :verified2 :size :xl)])))

(defc my-aid-topic-view < rum/reactive
  {:key-fn hash}
  [my-aid-topic]
  (letl2 [connectivity? (rum/react as/*connected?)
          my-aid        (rum/react (rum/cursor ac/*my-aid-topic->my-aid my-aid-topic))
          my-aid-name   (rum/react (rum/cursor ac/*aid->aid-name my-aid))
          group-aid?    (rum/react (rum/cursor ac/*aid->group-aid? my-aid))]
    [:div.contact {:on-click #(do (reset! as/*selected-my-aid-topic my-aid-topic)
                                  (reset! as/*browsing {:page :topic :topic my-aid-topic}))
                   :class    [(when group-aid? "group-aid")
                              (when (hash= (rum/react as/*selected-my-aid-topic) my-aid-topic) "selected-my-aid")]}
     [:div.contact-name
      my-aid-name
      (atfu/topic-feed-unread-count-bubble-view my-aid-topic)
      #_
      (when (not (nil? connectivity?))
        [:div.contact-connectivity {:class (if connectivity? "connected" "disconnected")}])
      (aid-attributions-view my-aid)
      (connect-invite-view my-aid)]]))

(defc contact-topic-view < rum/reactive
  {:key-fn hash}
  [my-aid contact-aid]
  (let [contact-aid-name (rum/react (rum/cursor ac/*aid->aid-name contact-aid))
        group-aid?       (rum/react (rum/cursor ac/*aid->group-aid? contact-aid))
        contact-topic    (rum/react (rum/cursor ac/*contact-aids->contact-topic #{my-aid contact-aid}))]
    [:div.contact.contact-topic {:on-click #(reset! as/*browsing {:page :topic :topic contact-topic})
                                 :class    [(when group-aid? "group-aid")
                                            (when (hash= (rum/react as/*selected-topic) contact-topic) "selected")]}
     [:div.contact-name
      contact-aid-name
      (atfu/topic-feed-unread-count-bubble-view contact-topic)
      (aid-attributions-view contact-aid)
      (connect-invite-view contact-aid)]]))

(defc group-topic-view < rum/reactive
  {:key-fn hash}
  [my-aid-topic group-topic]
  (let [topic-name (rum/react (rum/cursor as/*topic->topic-name group-topic))]
    [:div.contact.group {:on-click #(reset! as/*browsing {:page :topic :topic group-topic})
                         :class    [(when (hash= (rum/react as/*selected-topic) group-topic) "selected")]}
     [:div.contact-name
      topic-name
      (atfu/topic-feed-unread-count-bubble-view group-topic)]]))

(defc contacts-view < rum/reactive
  [my-aid-topic my-aid]
  [:div.contacts
   #_(let [{:keys [mutual-contacts pending-inbound pending-outbound]} (rum/react as/*contacts)]
       [:<>
        [:div.contact-group.mutual
         (for [mutual-did mutual-contacts]
           ^{:key did}
           (contact-view mutual-did))]

        [:div.contact-group.inbound
         (for [inbound-did pending-inbound]
           ^{:key did}
           (contact-view inbound-did))]

        [:div.contact-group.outbound
         (for [outbound-did pending-outbound]
           ^{:key did}
           (contact-view outbound-did))]
        ])

   [:div.topics
    (for [contact-aid (rum/react (l (rum/cursor ac/*my-aid->contact-aids (l my-aid))))]
      (contact-topic-view my-aid contact-aid))]

   [:div.topics
    (do (l :groups) nil)
    (for [group-topic (l (rum/react (rum/cursor ac/*my-aid->group-topics my-aid)))]
      (group-topic-view my-aid-topic group-topic))]])


(def new-topic-spacing-y (px 20))
(def new-topic-styles
  [[:.new-topic-button
    [:&.selected {:background-color styles/accent2-style}]]
   [:.new-topic {:height          "100%"
                 :max-width       (px 300)
                 :width           (px 300)
                 :display         :flex
                 :flex-direction  :column
                 :justify-content :center
                 :align-items     :center
                 :margin          :auto}
    [:.new-topic__topic-name {:width "100%"}]
    [:.new-topic__topic-members {:width "100%"
                           :margin-top new-topic-spacing-y}
     [:.topic-members__label {:font-size "medium"}]
     [:.topic-members__label-sub {:font-size "small"}]
     [:.topic-members__members {:width          "100%"
                                :display        :flex
                                :flex-direction :row
                                :margin-top     (px-div new-topic-spacing-y 2)
                                :margin-bottom  (px-div new-topic-spacing-y 2)}
      [:.topic-members__member (assoc styles/card-style
                                      :box-shadow styles/shadow0
                                      :cursor :pointer
                                      :transition "0.4s")
       [:&:hover styles/accent-style]
       [:&.selected styles/accent2-style]]]]
    [:.new-topic__create-button {:align-self :end}]]])

(reg-styles! ::new-topic new-topic-styles)

(defc new-topic-view < rum/reactive
  []
  (let [selected? (= :new-topic (rum/react (rum/cursor as/*browsing :page)))]
    [:div.contact.new-topic-button {:class    (when selected? "selected")
                                    :on-click #(reset! as/*browsing {:page :new-topic})}
     [:div.contact-name "+G"]]))

(defc new-topic-controls-view < rum/reactive
  [selected-my-aid-topic selected-my-aid]
  [:div.new-topic-controls
   (accept-connect-invite-view selected-my-aid-topic selected-my-aid)
   (new-topic-view)])


(add-watch as/*topic->tip-taped ::navigate-to-the-newly-added-topic ;; sane only for demo purposes
           (fn [_ _ old-topic->tip-taped new-topic->tip-taped]
             (when-let [novel-topic (first (set/difference (set (keys new-topic->tip-taped))
                                                           (set (keys old-topic->tip-taped))))]
               (reset! as/*browsing {:page :topic :topic novel-topic}))))
;; (l @as/*my-aid-topics)
;; (l @as/*browsing)
(defcs new-topic-page-view < rum/reactive (rum/local "" :*topic-name)
  {:will-mount (fn [{[_ selected-my-aid] :rum/args :as state}]
                 (assoc state :*member-aids (atom #{selected-my-aid})))}
  [{:keys [*topic-name *member-aids]} selected-my-aid-topic selected-my-aid]
  (let [member-aids         (l (rum/react *member-aids))
        aid->toggle-member! (fn [aid] #(swap! *member-aids (fn [member-aids]
                                                             (if (contains? member-aids aid)
                                                               (disj member-aids aid)
                                                               (conj member-aids aid)))))
        ready?              (not (empty? @*topic-name))]
    [:div.new-topic
     (text-field {:class      "new-topic__topic-name"
                  :label      "Group name"
                  :auto-focus true
                  :value      @*topic-name
                  :on-change  #(reset! *topic-name (-> % .-target .-value))})

     (fade {:key 0
            :in  ready?}
           (letl2 [my-aids                      (rum/react ac/*my-aids)
                 selected-my-aid-name         (rum/react (rum/cursor ac/*aid->aid-name selected-my-aid))
                 selected-my-aid-contact-aids (rum/react (rum/cursor ac/*my-aid->contact-aids selected-my-aid))
                 other-contact-aids           (rum/react (rum/cursor ac/*my-aid->other-contact-aids selected-my-aid))]
             [:div.new-topic__topic-members
              [:div.topic-members__label "Group members"]
              [:div.topic-members__label-sub "My AIDs"]
              [:div.topic-members__members
               (for [my-aid my-aids]
                 (let [my-aid-name (rum/react (rum/cursor ac/*aid->aid-name my-aid))
                       selected?   (contains? member-aids my-aid)]
                   [:div.topic-members__member {:key      (hash my-aid)
                                                :class    (when selected? "selected")
                                                :on-click (when (not= my-aid selected-my-aid)
                                                            (aid->toggle-member! my-aid))}
                    my-aid-name]))]

              (when (not-empty selected-my-aid-contact-aids)
                [:<>
                 [:div.topic-members__label-sub (str selected-my-aid-name "'s contacts")]
                 [:div.topic-members__members
                  (for [contact-aid selected-my-aid-contact-aids]
                    (let [contact-aid-name (rum/react (rum/cursor ac/*aid->aid-name contact-aid))
                          selected?        (contains? member-aids contact-aid)]
                      [:div.topic-members__member {:key      (hash contact-aid)
                                                   :class    (when selected? "selected")
                                                   :on-click (aid->toggle-member! contact-aid)}
                       contact-aid-name]))]])

              (when (not-empty other-contact-aids)
                [:<>
                 [:div.topic-members__label-sub "Other contacts"]
                 [:div.topic-members__members
                  (for [other-contact-aid other-contact-aids]
                    (let [other-contact-aid-name (rum/react (rum/cursor ac/*aid->aid-name other-contact-aid))
                          selected?              (contains? member-aids other-contact-aid)]
                      [:div.topic-members__member {:key      (hash other-contact-aid)
                                                   :class    (when selected? "selected")
                                                   :on-click (aid->toggle-member! other-contact-aid)}
                       other-contact-aid-name]))]])]))

     (fade {:key 1
            :in  ready?}
           (button {:class    "new-topic__create-button"
                    :on-click #(letl2 [aid->did-peers        @ac/*aid->did-peers
                                       member-aid->did-peers (->> member-aids
                                                                  (map (fn [member-aid]
                                                                         [member-aid (aid->did-peers member-aid)]))
                                                                  (into {}))
                                       member-aid-did-peers  (apply set/union (vals member-aid->did-peers))]
                                 (at/create-topic! (l @as/*my-did-peer)
                                                   member-aid-did-peers
                                                   (l {:topic-name            @*topic-name
                                                       :member-aid->did-peers member-aid->did-peers})))}
                   "Create Group"))]))
