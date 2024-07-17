(ns app.chat
  (:require [rum.core :refer [defc defcs] :as rum]
            [app.styles :refer [reg-styles! kind->css] :as styles]
            [app.state :as as]
            [app.io :refer [reg< send-message]]
            [utils :refer-macros [l]]))

(defn send-text-message [to-did text-message-content]
  (let [text-message {:text-message/content text-message-content
                      :text-message/from    @as/*my-did
                      :text-message/to      to-did
                      :text-message/at      (.now js/Date)}]
    (store-text-message! to-did text-message)
    (send-message to-did {:type "https://didcomm.org/basicmessage/2.0",
                          :body {:text-message text-message}})))

(defn store-text-message! [contact-did text-message+]
  (let [text-message (select-keys text-message+ [:text-message/content
                                                 :text-message/from
                                                 :text-message/to
                                                 :text-message/at])]
    (swap! as/*did->messages update contact-did (fn [?messages] (conj (or ?messages #{}) text-message)))))

(reg< "https://didcomm.org/basicmessage/2.0"
      (fn [{:keys [from body created_time]}]
        (store-text-message! from (:text-message body))))



(def styles-horizontal
  [])

(def styles-vertical
  [])

(def styles
  [[:.chat {:position :relative
            :display :flex
            :flex-direction :column
            :align-items :center
            :width "100%"
            :height "100%"}
    [:.messages {:height "100%"
                 :width "100%"
                 :display :flex
                 :flex-direction :column
                 :justify-content :end
                 :max-width "600px"
                 :margin-bottom "80px"}
     [:.message {:margin-top "5px"
                 :max-width :fit-content
                 :border "1px solid lightgray"
                 :padding"10px"
                 :border-radius "4px"}
      [:&.from-me {:align-self :flex-end}]]]
    [:.new-message-footer-layer {:position        :absolute
                                 :width           "100%"
                                 :height          "100%"
                                 :display         :flex
                                 :flex-direction  :row
                                 :justify-content :center
                                 :align-items     :end}
     [:.new-message-footer-bar {:width          "600px"
                                :margin-bottom  "30px"
                                :display        :flex
                                :flex-direction :row}
      [:.new-message {:width "100%"}]]]]])

(reg-styles! ::contacts styles styles-horizontal styles-vertical)

(defn send-key-combo? [event]
  (and (= (.-key event) "Enter")
       (.-ctrlKey event)))

(defcs chat-view < rum/reactive (rum/local "" :*new-message)
  [{:keys [*new-message]}]
  (let [selected-did (rum/react as/*selected-did)
        messages (get (rum/react as/*did->messages) selected-did)]
    (when selected-did
      [:div.chat
       [:div.new-message-footer-layer
        [:div.new-message-footer-bar
         [:input.new-message
          {:type         "text"
           :value        @*new-message
           :on-change    #(reset! *new-message (-> % .-target .-value))
           :on-key-press #(when (send-key-combo? %)
                            (send-text-message selected-did @*new-message)
                            (reset! *new-message ""))}]
         [:button.send-message
          {:on-click #(do (send-text-message selected-did @*new-message)
                          (reset! *new-message ""))}
          "Send"]]]

       [:div.messages
        (for [{:text-message/keys [content from to at]} (sort-by :text-message/at messages)
              :let [from-me? (= from (rum/react as/*my-did))]]
          [:div.message {:class (when from-me? "from-me")}
           content])]])))
