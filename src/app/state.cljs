(ns app.state
  (:require [rum.core :as rum]
            [utils :refer-macros [l]]
            [hashgraph.utils.core :refer [map-vals] :refer-macros [defn*]]
            [hashgraph.utils.lazy-derived-atom :refer [lazy-derived-atom]]
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
(defonce *selected-did-doc (lazy-derived-atom [*did->did-doc *selected-did]
                               (fn [did->did-doc selected-did] (get did->did-doc selected-did))))
(defonce *selected-page (rum/cursor *browsing :page))
(defonce *selected-topic (rum/cursor *browsing :topic))
(defonce *selected-my-aid-topic (atom nil))
(defonce *selected-my-aid-path (atom []))

#_#_#_
(defonce *inbound-contacts (atom #{}))
(defonce *outbound-contacts (atom #{}))
(def *contacts
  (lazy-derived-atom [*inbound-contacts *outbound-contacts]
      (fn [inbound-contacts outbound-contacts]
        (let [mutual-contacts  (set/intersection inbound-contacts outbound-contacts)
              pending-inbound  (set/difference inbound-contacts mutual-contacts)
              pending-outbound (set/difference outbound-contacts mutual-contacts)]
          {:mutual-contacts  mutual-contacts
           :pending-inbound  pending-inbound
           :pending-outbound pending-outbound}))))

;; *my-aid-path->topic->tip-taped
;; Pro: easier to resolve from nav data
;; *topic->init-key->tip-taped
;; Pro: generic, can support :init-key aside mine
;;  Con: you'll known only yours, other's init-key->tip-taped is derived from what you know
(defonce *my-aid-path->topic->tip-taped (atom {}))
(defonce *my-aid-topics (atom []))

(def *topics
  (lazy-derived-atom [*my-aid-path->topic->tip-taped]
      (fn [my-aid-path->topic->tip-taped]
        (l [::derive-*topics my-aid-path->topic->tip-taped])
        (->> my-aid-path->topic->tip-taped
             vals
             (mapcat keys)
             set))))

(def *my-aid-path->topic->db
  (lazy-derived-atom [*my-aid-path->topic->tip-taped]
      (map-vals (map-vals hg/evt->db))))

(def *my-aid-path->topic->topic-name
  (lazy-derived-atom [*my-aid-path->topic->db]
      (map-vals (map-vals :topic-name))))

(def *other-topics
  (lazy-derived-atom [*topics *my-aid-topics]
    (fn [topics my-aid-topics]
      (set/difference topics my-aid-topics))))

(def *topic-hash->topic
  (lazy-derived-atom [*topics]
    (fn [topics]
      (->> topics
           (into (hash-map) (map (fn [topic] [(hash topic) topic])))))))

(defonce *my-aid-path->topic->new-message (atom {}))

(defonce *message-handlers (atom {}))
