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
            [hashgraph.schemas :as hgs]
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
            [app.aid :as aa]

            [garden.units :refer [px px- px+ px-div] :as gun]
            [rum.core :refer [defc defcs] :as rum]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [app.control :as actrl]))

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

(hgs/register!
 :connect-invite
 [:map
  [:connect-invite/at int?]
  [:connect-invite/nonce uuid?]
  [:connect-invite/sig string?]
  [:connect-invite/target-aid# :hash]
  [:connect-invite/target-init-key->did-peer [:map-of string? string?]]
  [:connect-invite/target-init-key->signing-key [:map-of string? string?]]
  [:connect-invite/target-threshold :threshold]]

 :connect-invite-accepted
 [:map {:closed true}
  [:connect-invite-accepted/connect-invite :connect-invite]
  [:connect-invite-accepted/connectee-aid# :hash]])

(defn create-connect-invite [connect-invite*]
  ;; could be packed as DIDComm message for consistent io, this is a message that is transported via a kind of sneakernet / OOB
  (let [connect-invite (merge {:connect-invite/at    (.now js/Date)
                               :connect-invite/nonce (random-uuid)
                               :connect-invite/sig   ""}
                              connect-invite*)]
    ;; mayb also store sent invites locally, so you can revoke it
    (when (hgs/check :connect-invite connect-invite)
      connect-invite)))

(defcs connect-invite-view < rum/reactive (rum/local false :*input-opened?)
  [{:keys [input-opened?]} target-aid#]
  ;; TODO input for copying connect-invite if clipboard is not supported
  (when-let* [target-init-key->did-peer    (rum/react (rum/cursor ac/*aid#->latest-known-init-key->did-peer target-aid#))
              target-init-key->signing-key (rum/react (rum/cursor ac/*aid#->latest-known-init-key->signing-key target-aid#))
              target-ke                    (rum/react (rum/cursor ac/*aid#->latest-known-ke target-aid#))
              target-ke-est                (-> target-ke ac/->latest-establishment-key-event)
              target-threshold             (-> target-ke-est :key-event/threshold)]
    (let [connect-invite* {:connect-invite/target-aid#                  target-aid#
                           :connect-invite/target-init-key->did-peer    target-init-key->did-peer
                           :connect-invite/target-init-key->signing-key target-init-key->signing-key
                           :connect-invite/target-threshold             target-threshold} ;; could pass target-ke, but that would be a TON of info, not suitable for a link or QR code
          ]
      [:button.connect-invite {:on-click #(do (.stopPropagation %)
                                              (when-let [connect-invite (l (create-connect-invite connect-invite*))]
                                                (copy-to-clipboard! (pr-str connect-invite))))
                               :title    "Connect invite"}
       (icons/icon :solid :link)])))

;; we could contact target-aid and get the latest target-ke, and create-aided-topic! afterwards,
;; but that would require non-hashgraph communication (less robust) and whom of keys to contact?
;; doing it via hashgraph we have uniform communication & all folks gossip to each other
(defn accept-connect-invite! [my-aid-topic-path connectee-aid# connectee-ke {:connect-invite/keys [target-aid# target-init-key->did-peer target-init-key->signing-key target-threshold] :as connect-invite}]
  (l [:accept-connect-invite! connectee-aid# connectee-ke connect-invite])

  (letl2 [connect-invite-accepted (hgs/checks :connect-invite-accepted {:connect-invite-accepted/connect-invite connect-invite
                                                                        :connect-invite-accepted/connectee-aid# connectee-aid#})
          ;; only info necessary to derive the rest
          topic*                                 {:connect-invite-accepted       connect-invite-accepted
                                                  :aids#-log                     [connectee-aid# target-aid#]
                                                  :aid$->ke                      {0 connectee-ke} ;; makes topic bloated
                                                  :member-aids$                  [0 1]
                                                  :member-aid$->threshold        {1 target-threshold}
                                                  :member-aid$->member-init-keys {1 (vec (keys target-init-key->signing-key))}
                                                  :member-init-key->did-peer     target-init-key->did-peer}
          topic                   (ac/db-with-aid-control-propagated topic*)
          contact-topic-path  (conj my-aid-topic-path topic)]
    (reset! as/*selected-topic-path contact-topic-path)
    (reset! as/*browsing {:page :topic})
    (at/add-event! contact-topic-path {hg/topic topic}))
  #_(ac/create-aided-topic! my-aid-topic-path {connectee-aid connectee-ke}
                            {:connect-invite-accepted {:connect-invite-accepted/connect-invite connect-invite
                                                       :connect-invite-accepted/connectee-aid  connectee-aid}}))


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

(defcs accept-connect-invite-button-view < rum/reactive (rum/local false ::*input-open?)
  {:will-mount (fn [state]
                 (let [*connect-invite   (atom nil)
                       on-clipboard-text (fn [clipboard-text]
                                           (when-let [edn (edn/read-string clipboard-text)]
                                             (when (:connect-invite/target-aid# edn)
                                               (reset! *connect-invite edn))))]
                   (cond-> (assoc state
                                  ::*connect-invite          *connect-invite
                                  ::on-clipboard-text        on-clipboard-text)
                     clipboard-watchable?
                     (assoc ::unreg-on-clipboard-text! (reg-on-clipboard-text! on-clipboard-text)))))
   :will-unmount (fn [{::keys [unreg-on-clipboard-text!] :as state}]
                   (when unreg-on-clipboard-text!
                     (unreg-on-clipboard-text!))
                   state)}
  [{::keys [*connect-invite *input-open? on-clipboard-text]} selected-my-aid-topic-path]
  (letl2 [?connect-invite          (rum/react *connect-invite)
          connectee-aid-topic-path selected-my-aid-topic-path
          ?connectee-aid#          (rum/react (rum/cursor ac/*topic-path->my-aid# connectee-aid-topic-path))
          ?connectee-ke            (rum/react (rum/cursor ac/*topic-path->my-ke connectee-aid-topic-path))
          shown?                   (when-let [{:connect-invite/keys [target-aid#] :as connect-invite} (l ?connect-invite)]
                                     (and (hgs/check :connect-invite connect-invite)
                                          ?connectee-aid# ?connectee-ke
                                          (not (hash= target-aid# ?connectee-aid#))
                                          (not (contains? (rum/react (rum/cursor ac/*topic-path->contact-aids# selected-my-aid-topic-path))
                                                          target-aid#))))]
    (if clipboard-watchable?
      [:div.contact.accept-connect-invite
       (when shown?
         {:class    "shown"
          :on-click #(accept-connect-invite! selected-my-aid-topic-path ?connectee-aid# ?connectee-ke ?connect-invite)})
       [:div.contact-name
        (icons/icon :solid :link)]]

      [:<>
       (when shown?
         (reset! *connect-invite nil)
         (reset! *input-open? false)
         (accept-connect-invite! selected-my-aid-topic-path ?connectee-aid# ?connectee-ke ?connect-invite)
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


#_
(defc aid-attributions-view < rum/reactive
  [aid]
  (let [aid-attributed-acdcs (rum/react (rum/cursor acv/*aid->attributed-acdcs aid))
        relevant-acdcs       (->> aid-attributed-acdcs
                                  (filter (comp #{::ac/acdc-qvi ::ac/acdc-le} :acdc/schema))
                                  (sort-by :acdc/schema))]
    (when (not-empty relevant-acdcs)
      [:div.aid-certified
       (icons/icon :solid :verified2 :size :xl)])))

(defc my-aid-topic-path-view < rum/reactive
  {:key-fn hash}
  [my-aid-topic-path selected?]
  (let [connectivity? (rum/react as/*connected?)
        my-aid#       (rum/react (rum/cursor ac/*topic-path->my-aid# my-aid-topic-path))
        my-aid-name   (rum/react (rum/cursor ac/*aid#->aid-name my-aid#))
        group-aid?    (rum/react (rum/cursor ac/*aid#->group-aid? my-aid#))]
    [:div.contact {:on-click #(do (reset! as/*selected-topic-path my-aid-topic-path)
                                  (reset! as/*browsing {:page :topic}))
                   :class    [(when group-aid? "group-aid")
                              (when selected? #_(hash= (rum/react as/*selected-topic-path) my-aid-topic-path) "selected-my-aid")]}
     [:div.contact-name
      my-aid-name
      (atfu/topic-feed-unread-count-bubble-view my-aid-topic-path)
      #_
      (when (not (nil? connectivity?))
        [:div.contact-connectivity {:class (if connectivity? "connected" "disconnected")}])
      #_
      (aid-attributions-view my-aid)
      (connect-invite-view my-aid#)]]))

(defc contact-topic-path-view < rum/reactive
  {:key-fn hash}
  [my-aid-topic-path contact-topic-path]
  (letl [my-aid#          (rum/react (rum/cursor ac/*topic-path->my-aid# my-aid-topic-path))
         contact-aid#     (first (disj (rum/react (rum/cursor ac/*contact-topic-path->connected-aids# contact-topic-path)) my-aid#))
         contact-aid-name (rum/react (rum/cursor ac/*aid#->aid-name contact-aid#))
         group-aid?       (rum/react (rum/cursor ac/*aid#->group-aid? contact-aid#))]
    [:div.contact.contact-topic {:on-click #(do (reset! as/*selected-topic-path contact-topic-path)
                                                (reset! as/*browsing {:page :topic}))
                                 :class    [(when group-aid? "group-aid")
                                            (when (hash= (rum/react as/*selected-topic-path) contact-topic-path) "selected")]}
     [:div.contact-name
      contact-aid-name
      (atfu/topic-feed-unread-count-bubble-view contact-topic-path)
      #_
      (aid-attributions-view contact-aid)
      (connect-invite-view contact-aid#)]]))

(defc group-topic-path-view < rum/reactive
  {:key-fn hash}
  [my-aid-topic-path group-topic-path]
  (let [topic-name (rum/react (rum/cursor as/*topic-path->topic-name group-topic-path))]
    [:div.contact.group {:on-click #(do (reset! as/*selected-topic-path group-topic-path)
                                        (reset! as/*browsing {:page :topic}))
                         :class    [(when (hash= (rum/react as/*selected-topic-path) group-topic-path) "selected")]}
     [:div.contact-name
      topic-name
      (atfu/topic-feed-unread-count-bubble-view group-topic-path)]]))

(defc interactable-topic-paths-view < rum/reactive
  [my-aid-topic-path]
  [:div.contacts
   [:div.topics
    (for [contact-topic-path (rum/react (rum/cursor ac/*topic-path->contact-topic-paths my-aid-topic-path))]
      (contact-topic-path-view my-aid-topic-path contact-topic-path))]

   [:div.topics
    (for [group-topic-path (rum/react (rum/cursor ac/*topic-path->group-topic-paths my-aid-topic-path))]
      (group-topic-path-view my-aid-topic-path group-topic-path))]])

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
    [:.new-topic__topic-members {:width      "100%"
                                 :margin-top new-topic-spacing-y}
     [:.topic-members__label {:font-size "medium"}]
     [:.topic-members__label-sub {:font-size "small"}]
     [:.topic-members__members {:width          "100%"
                                :display        :flex
                                :flex-direction :row
                                :margin-top     (px-div new-topic-spacing-y 2)
                                :margin-bottom  (px-div new-topic-spacing-y 2)}
      ]]
    [:.new-topic__create-button {:align-self :end}]]

   ])

(reg-styles! ::new-topic new-topic-styles)

(defc new-topic-button-view < rum/reactive
  []
  (let [selected? (= :new-topic (rum/react (rum/cursor as/*browsing :page)))]
    [:div.contact.new-topic-button {:class    (when selected? "selected")
                                    :on-click #(do (reset! as/*browsing {:page :new-topic})
                                                   (reset! as/*selected-topic-path @ac/*selected-my-aid-topic-path))}
     [:div.contact-name "+G"]]))

(defc new-topic-controls-view < rum/reactive
  [selected-my-aid-topic-path]
  [:div.new-topic-controls
   (accept-connect-invite-button-view selected-my-aid-topic-path)
   (new-topic-button-view)])

#_
(add-watch as/*topic-paths ::navigate-to-the-newly-added-topic ;; sane only for demo purposes
           (fn [_ _ old-topic-paths new-topic-paths]
             (when-let [novel-topic-path (first (set/difference new-topic-paths
                                                                old-topic-paths))]
               (reset! as/*selected-topic-path novel-topic-path)
               (reset! as/*browsing {:page :topic}))))
;; (l @as/*my-aid-topics)
;; (l @as/*browsing)

(defcs new-topic-page-view < rum/reactive (rum/local "" :*topic-name) (rum/local #{} :*member-aids#)
  [{:keys [*topic-name *member-aids#]} selected-my-aid-topic-path]
  (let [selected-my-aid#     (rum/react (rum/cursor ac/*topic-path->my-aid# selected-my-aid-topic-path))
        topic-name           (rum/react *topic-name)
        member-aids#         (l (conj (rum/react *member-aids#) selected-my-aid#))
        aid#->toggle-member! (fn [aid#] #(swap! *member-aids# (fn [member-aids#]
                                                                (if (contains? member-aids# aid#)
                                                                  (disj member-aids# aid#)
                                                                  (conj member-aids# aid#)))))]
    [:div.new-topic
     (text-field {:class      "new-topic__topic-name"
                  :label      "Group name"
                  :auto-focus true
                  :value      @*topic-name
                  :on-change  #(reset! *topic-name (-> % .-target .-value))})

     (fade {:key 0
            :in  (not (empty? topic-name))}
           (letl2 [my-aids#                      (rum/react ac/*my-aids#)
                   selected-my-aid-name          (rum/react (rum/cursor ac/*aid#->aid-name selected-my-aid#))
                   selected-my-aid-contact-aids# (rum/react (rum/cursor ac/*topic-path->contact-aids# selected-my-aid-topic-path))
                   other-contact-aids#           (rum/react (rum/cursor ac/*topic-path->other-contact-aids# selected-my-aid-topic-path))]
             [:div.new-topic__topic-members
              [:div.topic-members__label "Group members"]
              [:div.topic-members__label-sub "My grIDs"]
              [:div.topic-members__members
               (for [my-aid# my-aids#]
                 (aa/aid#-view my-aid# {:class    [(when (contains? member-aids# my-aid#) "selected")]
                                        :on-click (when (not (= my-aid# selected-my-aid#))
                                                    (aid#->toggle-member! my-aid#))}))]

              (when (not-empty selected-my-aid-contact-aids#)
                [:<>
                 [:div.topic-members__label-sub (str selected-my-aid-name "'s contacts")]
                 [:div.topic-members__members
                  (for [contact-aid# selected-my-aid-contact-aids#]
                    (aa/aid#-view contact-aid# {:class    [(when (contains? member-aids# contact-aid#) "selected")]
                                                :on-click (aid#->toggle-member! contact-aid#)}))]])

              (when (not-empty other-contact-aids#)
                [:<>
                 [:div.topic-members__label-sub "Other contacts"]
                 [:div.topic-members__members
                  (for [other-contact-aid# other-contact-aids#]
                    (aa/aid#-view other-contact-aid# {:class    [(when (contains? member-aids# other-contact-aid#) "selected")]
                                                      :on-click (aid#->toggle-member! other-contact-aid#)}))]])]))

     (fade {:key 1
            :in  (not (empty? topic-name))}
           (button {:class    "new-topic__create-button"
                    :on-click #(ac/create-aided-topic! selected-my-aid-topic-path (let [aids#-log (vec member-aids#)]
                                                                                    {:topic-name   topic-name
                                                                                     :aids#-log    aids#-log
                                                                                     :aid$->ke     (->> member-aids# (into (hash-map) (map (fn [member-aid#] [(-indexOf aids#-log member-aid#) (-> member-aid# (@ac/*aid#->latest-known-ke))]))))
                                                                                     :member-aids$ (->> member-aids# (mapv (fn [member-aid#] (-indexOf aids#-log member-aid#))))}))}
                   "Create Group"))]))
