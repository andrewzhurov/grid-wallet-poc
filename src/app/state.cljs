(ns app.state
  (:require [rum.core :as rum]
            [clojure.set :as set]))

(def features ["https://didcomm.org/discover-features/2.0"
               "https://didcomm.org/trust-ping/2.0"
               "https://didcomm.org/basicmessage/2.0"
               "https://didcomm.org/user-profile/1.0"])

(defonce *browsing (atom {}))
(defonce *connected? (atom false))
(defonce *my-did (atom nil))
(defonce *did->profile (atom {}))
(defonce *inbound-contacts (atom #{}))
(defonce *outbound-contacts (atom #{}))
(defonce *contacts (rum/derived-atom [*inbound-contacts *outbound-contacts] :all-contacts
                     (fn [inbound-contacts outbound-contacts]
                       (let [mutual-contacts  (set/intersection inbound-contacts outbound-contacts)
                             pending-inbound  (set/difference inbound-contacts mutual-contacts)
                             pending-outbound (set/difference outbound-contacts mutual-contacts)]
                         {:mutual-contacts  mutual-contacts
                          :pending-inbound  pending-inbound
                          :pending-outbound pending-outbound}))))

(defonce *did->messages (atom {}))
(defonce *selected-did (atom nil))

(def *message-handlers (atom {}))
