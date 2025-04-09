(ns app.state
  (:require [rum.core :as rum]
            [hashgraph.utils.core :refer [map-vals filter-map-vals] :refer-macros [defn*]]
            [hashgraph.utils.lazy-derived-atom :refer-macros [deflda]]
            [hashgraph.main :as hg]
            [clojure.set :as set]))

;; reg supported features dynamically, based on actual code in use
(def features ["https://didcomm.org/discover-features/2.0"
               "https://didcomm.org/trust-ping/2.0"
               "https://didcomm.org/basicmessage/2.0"
               "https://didcomm.org/user-profile/1.0"])


(defonce *browsing (atom (hash-map)))

(defonce *selected-page (rum/cursor *browsing :page))
(defonce *selected-topic-path (atom []))

#_#_#_
(defonce *inbound-contacts (atom #{}))
(defonce *outbound-contacts (atom #{}))
(deflda *contacts [*inbound-contacts *outbound-contacts]
  (fn [inbound-contacts outbound-contacts]
    (let [mutual-contacts  (set/intersection inbound-contacts outbound-contacts)
          pending-inbound  (set/difference inbound-contacts mutual-contacts)
          pending-outbound (set/difference outbound-contacts mutual-contacts)]
      {:mutual-contacts  mutual-contacts
       :pending-inbound  pending-inbound
       :pending-outbound pending-outbound})))

(defonce *topic-path->mailbox (atom (hash-map)))
(deflda *topic-path->did-peer [*topic-path->mailbox]
  (filter-map-vals :mailbox/did-peer))

;; *my-aid-path->topic->tip-taped
;; Pro: easier to resolve from nav data
;; Con: well, it should becomes a part of my-aid-path on inception then
;; *topic->init-key->tip-taped
;; Pro: generic, can support :init-key aside mine
;;  Con: you'll known only yours, other's init-key->tip-taped is derived from what you know
(defonce *topic-path->tip-taped (atom (hash-map)))

(deflda *topic-paths [*topic-path->tip-taped] (comp set keys))
(deflda *topics [*topic-paths]
  (fn [topic-paths]
    (->> topic-paths
         (map last)
         set)))

(deflda *topic-path->cr [*topic-path->tip-taped]
  (filter-map-vals hg/->concluded-round))

(deflda *topic-path->db [*topic-path->cr]
  (map-vals hg/cr->db))

(deflda *topic-path->topic-name [*topic-path->db]
  (filter-map-vals :topic-name))

(deflda *topic-path->member-init-keys-log [*topic-path->db]
  (map-vals :member-init-keys-log))

(deflda *topic-hash->topic [*topics]
  (fn [topics]
    (->> topics
         (into (hash-map) (map (fn [topic] [(hash topic) topic]))))))

(deflda *topic-path->initial-tip-taped [*topic-path->tip-taped]
  (map-vals hg/first-self-parent))

(deflda *topic-path->initial-cr [*topic-path->initial-tip-taped]
  (map-vals hg/->concluded-round))

(defonce *topic-path->new-message (atom (hash-map)))

(defonce *message-handlers (atom {}))
