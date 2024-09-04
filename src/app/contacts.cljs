(ns app.contacts
  (:require [hashgraph.main :as hg]
            [hashgraph.topic :as hgt]
            [hashgraph.app.members :as hga-members]
            [hashgraph.utils.core :refer [hash= flatten-all] :refer-macros [defn* l letl] :as utils]
            [hashgraph.app.icons :as icons]

            [app.styles :refer [reg-styles! kind->css] :as styles]
            [app.state :as as]
            [app.io :refer [reg< send-message]]
            [app.topic :as at]
            [app.utils :refer [reg-on-clipboard-text!]]
            [app.creds :as ac]
            [app.topic-feed-unread :as atfu]

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
(defn >connect-intive-accepted [{:connect-invite/keys [target] :as connect-invite}]
  (let [message {:type "https://didcomm.org/connect-invite/1.0/accepted"
                 :body (hash connect-invite)}]
    (send-message target message)))

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

(defcs connect-invite-view < rum/reactive (rum/local false :*input-opened?)
  [{:keys [input-opened?]} target-topic]
  ;; TODO input for copying connect-invite if clipboard is not supported
  (when-let [invitor-aid (rum/react ac/*selected-my-aid)]
    (when-let [target-aid (rum/react (rum/cursor ac/*topic->aid target-topic))]
      (when-let [target-did-peers (some-> (rum/react (rum/cursor as/*topic->tip-taped target-topic))
                                          at/->projected-db
                                          :member-aid->did-peers vals
                                          (->> (reduce into #{}))) ;; projected member-aid->did-peers
                                  #_(rum/react (rum/cursor ac/*aid->did-peers target-aid))]
        (let [connect-invite* {:connect-invite/invitor-aid      invitor-aid
                               :connect-invite/target-aid       target-aid
                               :connect-invite/target-did-peers target-did-peers}]
          [:button.connect-invite {:on-click #(do (.stopPropagation %) (copy-to-clipboard! (pr-str (create-connect-invite connect-invite*))))
                                   :title    "Connect invite"}
           (icons/icon :solid :link)])))))

(defn accept-connect-invite! [connectee-creator connectee-aid connectee-aid-did-peers {:connect-invite/keys [target-aid target-did-peers] :as connect-invite}]
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

(defcs accept-connect-invite-view < rum/reactive
  {:will-mount (fn [state]
                 (let [*connect-invite   (atom nil)
                       on-clipboard-text (fn [clipboard-text]
                                           (when-let [edn (edn/read-string clipboard-text)]
                                             (when (:connect-invite/target-aid edn)
                                               (reset! *connect-invite edn))))]
                   (assoc state
                          ::*connect-invite          *connect-invite
                          ::unreg-on-clipboard-text! (reg-on-clipboard-text! on-clipboard-text))))
   :will-unmount (fn [state] ((::unreg-on-clipboard-text! state)))}
  [{::keys [*connect-invite]}]
  (let [?connect-invite          (rum/react *connect-invite)
        ?connectee-creator       (rum/react as/*my-did-peer)
        ?connectee-aid-topic     (rum/react as/*selected-my-aid-topic)
        ?connectee-aid           (rum/react ac/*selected-my-aid)
        ?connectee-aid-did-peers (some-> ?connectee-aid-topic
                                         (->> (rum/cursor as/*topic->tip-taped))
                                         (rum/react)
                                         at/->projected-db
                                         :member-aid->did-peers vals
                                         (->> (reduce into #{})))
        shown?                   (when-let [{:connect-invite/keys [target-aid target-did-peers]} ?connect-invite]
                                   (and target-aid target-did-peers
                                        ?connectee-creator ?connectee-aid-topic ?connectee-aid ?connectee-aid-did-peers
                                        (empty? (set/intersection target-did-peers ?connectee-aid-did-peers))))]
    [:div.contact.accept-connect-invite
     (when shown?
       {:class    "shown"
        :on-click #(accept-connect-invite! ?connectee-creator ?connectee-aid ?connectee-aid-did-peers ?connect-invite)})
     [:div.contact-name
      (icons/icon :solid :link)]]))
;; ----------------------


(defc contact-view < rum/reactive
  {:key-fn first}
  [did connectivity?]
  [:div.contact {:on-click #(reset! as/*browsing {:page :profile :did did})
                 :class    (when (= (rum/react as/*selected-did) did) "selected")}
   [:div.contact-name (hga-members/did->alias did)
    #_
    (when (not (nil? connectivity?))
      [:div.contact-connectivity {:class (if connectivity? "connected" "disconnected")}])
    (connect-invite-view did)]])

(def *topic->other-aid
  (rum/derived-atom [as/*topics ac/*topic->aid] ::derive-topic->other-aid
    (fn [topics topic->aid]
      (->> topics
           (remove (set (keys topic->aid)))
           (map (fn [topic]
                  [topic (-> topic :member-aid->did-peers
                             keys set
                             (set/difference (set (vals topic->aid)))
                             first)]))
           (into {})))))

(def *topic->navbar-aid
  (rum/derived-atom [*topic->other-aid ac/*topic->aid] ::derive-topic->navbar-aid
    (fn [topic->other-aid topic->aid]
      (merge topic->other-aid
             topic->aid))))

(defc aid-attributions-view < rum/reactive
  [topic]
  (let [navbar-aid                  (rum/react (rum/cursor *topic->navbar-aid topic))
        navbar-aid-attributed-acdcs (rum/react (rum/cursor ac/*aid->attributed-acdcs navbar-aid))
        relevant-acdcs              (->> navbar-aid-attributed-acdcs
                                         (filter (comp #{::ac/acdc-qvi ::ac/acdc-le} :acdc/schema))
                                         (sort-by :acdc/schema))]
    (when (not-empty relevant-acdcs)
      [:div.aid-certified
       (icons/icon :solid :verified2 :size :xl)])))


(defc topic-view < rum/reactive
  {:key-fn identity}
  [topic & [connectivity?]]
  [:div.contact {:on-click #(reset! as/*browsing {:page :topic :topic topic})
                 :class     [(when (hash= (rum/react as/*selected-my-aid-topic) topic) "selected-my-aid")
                             (when (hash= (rum/react as/*selected-topic) topic) "selected")]}
   [:div.contact-name (rum/react (rum/cursor as/*topic->topic-name topic))
    (atfu/topic-feed-unread-count-bubble-view topic)
    #_
    (when (not (nil? connectivity?))
      [:div.contact-connectivity {:class (if connectivity? "connected" "disconnected")}])
    (aid-attributions-view topic)
    ;; useful for invitation
    ;; may get revoked later
    ;; may require authZ to be sent out
    (connect-invite-view topic)
    #_
    [:button.connect-invite {:on-click #(do (.stopPropagation %) (copy-to-clipboard! did))
                             :title    (str "Copy DID: " did)}
     "C"]]])

(defc contacts-view < rum/reactive
  []
  [:div.contacts
   (let [{:keys [mutual-contacts pending-inbound pending-outbound]} (rum/react as/*contacts)]
     [:<>
      ;; [:div.contact-group.mutual
      ;;  (for [mutual-did mutual-contacts]
      ;;    ^{:key did}
      ;;    (contact-view mutual-did))]

      ;; [:div.contact-group.inbound
      ;;  (for [inbound-did pending-inbound]
      ;;    ^{:key did}
      ;;    (contact-view inbound-did))]

      ;; [:div.contact-group.outbound
      ;;  (for [outbound-did pending-outbound]
      ;;    ^{:key did}
      ;;    (contact-view outbound-did))]
      ])

   [:div.contact-group.topics
    (for [other-topic (rum/react as/*other-topics)]
      (topic-view other-topic))]])

(defc new-topic-view < rum/reactive []
  [:div.contact {:on-click #(reset! as/*browsing {:page :new-topic})}
   [:div.contact-name "+G"]])

(defc new-topic-controls-view < rum/reactive []
  [:div.new-topic-controls
   (accept-connect-invite-view)
   (new-topic-view)])

(def new-topic-styles
  [[:.new-topic styles/padded-column-style
    [:.topic-members {:display :flex
                      :flex-direction :row}]
    [:.contact-full (assoc styles/card-style
                           :cursor :pointer
                           :transition "0.4s")
     [:&:hover styles/accent-style]
     [:&.selected styles/accent2-style]]]])

(reg-styles! ::new-topic new-topic-styles)

(add-watch as/*topic->tip-taped ::navigate-to-the-newly-added-topic ;; sane only for demo purposes
           (fn [_ _ old-topic->tip-taped new-topic->tip-taped]
             (when-let [novel-topic (first (set/difference (set (keys new-topic->tip-taped))
                                                           (set (keys old-topic->tip-taped))))]
               (reset! as/*browsing {:page :topic :topic novel-topic}))))

(defcs new-topic-page-view < rum/reactive (rum/local "" :*topic-name) (rum/local #{} :*members)
  [{:keys [*topic-name *members]}]
  (l @*members)
  [:div.new-topic
   "Topic name"
   [:input {:type      "text"
            :on-change #(reset! *topic-name (-> % .-target .-value))
            :value     @*topic-name}]

   "Select members"
   [:div.topic-members
    [:div.contact-full {:class "selected"}
     [:div.contact-full-name "Me"]]

    (for [outbound-contact (l (rum/react as/*outbound-contacts))]
      [:div.contact-full {:key outbound-contact
                          :on-click #(swap! *members (fn [members] (if (contains? members outbound-contact)
                                                                     (disj members outbound-contact)
                                                                     (conj members outbound-contact))))
                          :class    (when (contains? @*members outbound-contact) "selected")}
       [:div.contact-full-name "Unknown"]])]

   [:button {:on-click #(at/create-topic! (l @as/*my-did-peer) (l (conj @*members @as/*my-did-peer)) (l {:topic-name @*topic-name}))}
    "Create topic"]])
