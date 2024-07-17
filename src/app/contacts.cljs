(ns app.contacts
  (:require [rum.core :refer [defc defcs] :as rum]
            [app.styles :refer [reg-styles! kind->css] :as styles]
            [app.state :as as]
            [app.io :refer [reg< send-message]]
            [utils :refer-macros [l]]))

(defn >profile [did]
  (let [message {:type "https://didcomm.org/user-profile/1.0/profile"
                 :body {:profile (-> @as/*did->profile (get @as/*my-did))}}]
    (send-message did message)))

(defn set-profile! [profile+]
  (let [profile (select-keys profile+ [:profile/did :profile/alias])]
    (swap! as/*did->profile assoc (:profile/did profile) profile)))

(reg< "https://didcomm.org/user-profile/1.0/profile"
      (fn [{{{:profile/keys [did] :as profile} :profile} :body
            :keys                                        [from to created_time]
            :as                                          message}]
        (if (not= did from)
          (js/console.warn "Received profile did not have matching 'from' and ':profile/did'" message)
          (do (set-profile! profile)
              (swap! as/*inbound-contacts conj did)))))

(defn oob-contact! [did]
  (swap! as/*did->profile assoc-in [did :profile/did] did)
  (swap! as/*outbound-contacts conj did)
  (>profile did))

(defn connect-contact! [did]
  (swap! as/*outbound-contacts conj did)
  (>profile did))

(defn disconnect-contact! [did]
  (swap! as/*outbound-contacts disj did))

(def styles-horizontal
  [])

(def styles-vertical
  [])

(def styles
  [[:.contact {:width           "80px"
               :height          "80px"
               :display         :flex
               :align-items     :center
               :justify-content :center
               :transition      "background-color 0.2s"
               :cursor          :pointer}
    [:&:hover {:background-color "rgba(0,0,0,0.025)"}
     [:.contact-name
      [:.contact-copy-did {:opacity 1}]]]
    [:&.selected {:background-color "rgba(0,0,0,0.05)"}]
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
                              :width "12px"
                              :height "12px"
                              :border-radius "50%"}
      [:&.connected {:background-color "green"}]
      [:&.disconnected {:background-color "gray"}]]

     [:.contact-copy-did {:position :absolute
                          :bottom   "-6px"
                          :right    "-6px"
                          :width    "24px"
                          :height   "24px"
                          :background-color :white
                          :border "1px solid lightgray"
                          :cursor :pointer
                          :opacity 0
                          :transition "opacity 0.3s"}]]]
   [:.contacts {:grid-area      "contacts"
                :max-height     "100%"
                :max-width      "100%"
                :display        :flex
                :flex-direction :column}]])

(reg-styles! ::contacts styles styles-horizontal styles-vertical)

(defn copy-to-clipboard! [text]
  (-> js/navigator
      (.-clipboard)
      (.writeText text)))

(defc contact-view < rum/reactive
  [did connectivity?]
  (let [{:profile/keys [alias]} (get (rum/react as/*did->profile) did)]
    [:div.contact {:on-click #(reset! as/*selected-did did)
                   :class (when (= (rum/react as/*selected-did) did) "selected")}
     [:div.contact-name (first (or alias "?"))
      (when (not (nil? connectivity?))
        [:div.contact-connectivity {:class (if connectivity? "connected" "disconnected")}])
      [:button.contact-copy-did {:on-click #(do (.stopPropagation %) (copy-to-clipboard! did))
                                 :title (str "Copy DID: " did)}
       "C"]]]))

(defcs add-contact-view < rum/reactive (rum/local "" :*new-contact-did)
  [{:keys [*new-contact-did]}]
  [:div.contact.new {:on-click #(try (some-> js/navigator
                                             (.-clipboard)
                                             (.readText)
                                             (.then (fn [maybe-did] ;; TODO ensure it's did
                                                      (oob-contact! maybe-did))))
                                     (catch js/Error e
                                       (js/console.error e)))}
   [:div.contact-name "+"]])

(defc contacts-view < rum/reactive
  []
  [:div.contacts
   (let [{:keys [mutual-contacts pending-inbound pending-outbound]} (rum/react as/*contacts)]
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

      (add-contact-view)])])
