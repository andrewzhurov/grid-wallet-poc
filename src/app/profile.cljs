(ns app.profile
  (:require [rum.core :refer [defc defcs] :as rum]
            [app.styles :refer [reg-styles! kind->css] :as styles]
            [app.state :as as]
            [app.io :refer [reg< send-message]]
            [app.utils :refer [reg-on-clipboard-text!]]
            [hashgraph.main :as hg]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.pprint :refer [pprint]]
            ["react-qr-code" :refer [QRCode]]))

(def edn-view-styles
  [[:.edn-view (assoc styles/card-style
                      :font-family :monospace
                      :overflow-x :scroll
                      :overflow-y :scroll
                      :white-space :pre)]])
(reg-styles! ::edn-view edn-view-styles)

(defc edn-view [edn]
  [:div.edn-view
   (with-out-str (pprint edn))])

(def profile-page-view-styles
  [[:.profile {:height :inherit
               :overflow-y :scroll}
    [:my {:display :flex
          :flex-direction :column}
     [:.linking {:display :flex}]
     [:.create-id styles/card-style]
     [:.my-devices {:display        :flex
                    :flex-direction :column}
      [:.my-device (assoc styles/card-style
                          :width "200px"
                          :text-overflow :ellipsis
                          :overflow-x :hidden
                          :cursor :pointer)
       [:&:hover styles/accent-style]
       [:&.selected styles/accent2-style]]]
     [:.link-device styles/card-style]]]])
(reg-styles! ::profile-page-view profile-page-view-styles)

(defcs link-device-view < rum/reactive
  {:will-mount   (fn [state]
                   (let [*connect-invite   (atom nil)
                         on-clipboard-text (fn [clipboard-text]
                                             (when-let [edn (edn/read-string clipboard-text)]
                                               (when (:link-device/target edn)
                                                 (reset! *connect-invite edn))))]
                   (assoc state
                          ::*connect-invite          *connect-invite
                          ::unreg-on-clipboard-text! (reg-on-clipboard-text! on-clipboard-text))))
   :will-unmount (fn [state] ((::unreg-on-clipboard-text! state)))}
  [{::keys [*connect-invite]}])

(defc profile-page-view < rum/reactive
  []
  #_
  (let [my-did-peer (rum/react as/*my-did-peer)
        my-dids         (rum/react as/*my-dids)
        did->did-doc    (rum/react as/*did->did-doc)
        profile         (rum/react as/*selected-did-doc)]
    [:div.profile
     "Profile"
     [:my
      (if-not (rum/react as/*my-id)
        [:button.create-id
         "Create ID"])
      [:div.linking
       (link-device-invite-view)
       (link-device-view)]
      [:div.my-devices
       "My devices"
       (for [my-did my-dids]
         [:div.my-device {:on-click #(reset! as/*my-did-peer my-did)
                          :class    (when (= my-did my-did-peer) "selected")}
          my-did
          #_(when-let [my-did-doc (get did->did-doc my-did-peer)]
              (edn-view my-did-doc))])]
      ]]))
