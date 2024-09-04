(ns app.state
  (:require [rum.core :as rum]
            [utils :refer-macros [l]]
            [hashgraph.utils.core :refer-macros [defn*]]
            [hashgraph.app.members :as hga-members]
            [hashgraph.main :as hg]
            [clojure.set :as set]))

;; reg supported features dynamically, based on actual code in use
(def features ["https://didcomm.org/discover-features/2.0"
               "https://didcomm.org/trust-ping/2.0"
               "https://didcomm.org/basicmessage/2.0"
               "https://didcomm.org/user-profile/1.0"])


(defonce *browsing (atom {}))
(defonce *connected? (atom false))

(defonce *my-did-peer (atom nil))
(defonce *did->did-doc (atom {}))

(defonce *selected-did (rum/cursor *browsing :did))
(defonce *selected-did-doc (rum/derived-atom [*did->did-doc *selected-did] ::derive-*selected-did-doc
                             (fn [did->did-doc selected-did] (get did->did-doc selected-did))))
(defonce *selected-topic (rum/cursor *browsing :topic))
(defonce *selected-page (rum/cursor *browsing :page))
(defonce *selected-my-aid-topic (atom nil))

(defonce *inbound-contacts (atom #{}))
(defonce *outbound-contacts (atom #{}))
(def *contacts
  (rum/derived-atom [*inbound-contacts *outbound-contacts] :all-contacts
    (fn [inbound-contacts outbound-contacts]
      (let [mutual-contacts  (set/intersection inbound-contacts outbound-contacts)
            pending-inbound  (set/difference inbound-contacts mutual-contacts)
            pending-outbound (set/difference outbound-contacts mutual-contacts)]
        {:mutual-contacts  mutual-contacts
         :pending-inbound  pending-inbound
         :pending-outbound pending-outbound}))))


(defonce *topics (atom #{}))
(defonce *topic->tip-taped (atom {}))
(defonce *my-aid-topics (atom []))


(def *other-topics
  (rum/derived-atom [*topics *my-aid-topics] ::derive-other-topics
    (fn [topics my-aid-topics]
      (set/difference topics my-aid-topics))))

(def *topic-hash->topic
  (rum/derived-atom [*topics] ::derive-topic-hash->topic
    (fn [topics]
      (->> topics
           (into (hash-map) (map (fn [topic] [(hash topic) topic])))))))

(def *topic->topic-name
  (rum/derived-atom [*topics *my-did-peer] ::derive-topic-name-out-of-topic
    (fn [topics select-my-did]
      (->> topics
           (into {} (map (fn [{:keys [topic-members topic-name] :as topic}]
                           [topic (or topic-name ;; this may change, take it from derived-db of a topic
                                      (and (= 2 (count topic-members))
                                           (let [other-member (first (set/difference (set topic-members) #{select-my-did}))]
                                             (l (hga-members/did->alias (l other-member)))))
                                      "???")])))))))

(defonce *topic->new-message (atom {}))

(defonce *message-handlers (atom {}))
