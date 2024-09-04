(ns tape
  (:require [hashgraph.main :as hg]
            [hashgraph.utils.core :refer [log!] :refer-macros [l defn*] :as utils]))

;; a1
;; | \
;; a2 b1
;; => a2 b1
(def a1 {})
(def a2 {hg/self-parent a1})
(def b1 {hg/other-parent a1})
(def tape1 [a1 a2 b1])

;; a1
;; | \
;; a2 b1
;;  \ |
;;    b2
;; => b2
(def b2 {hg/self-parent b1
         hg/other-parent a2})
(def tape2 (conj tape1 b2))

;; a1
;; | \
;; a2 b1
;; |\ |
;; |  b2
;; | /|
;; a3 b3
;; => a3 b3
(def a3 {hg/self-parent a2
         hg/other-parent b2})
(def b3 {hg/self-parent b2})

;; (tape>->tips [])
