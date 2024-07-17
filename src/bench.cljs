(ns bench
  (:require [cognitect.transit :as t]))

(defn roundtrip [x]
  (let [w (t/writer :json)
        r (t/reader :json)]
    (t/read r (t/write w x))))

(defn test-roundtrip []
  (let [list1 [:red :green :blue]
        list2 [:apple :pear :grape]
        data  {(t/integer 1) list1
               (t/integer 2) list2}
        data' (roundtrip data)]
    (assert (= data data'))))

(roundtrip {:profile/did "did:..."
            :profile/alias "Alice"})

(def alice-pub-key "MIIBCgKCAQEA+xGZ/wcz9ugFpP07Nspo6U17l0YhFiFpxxU4pTk3Lifz9R3zsIsu
ERwta7+fWIfxOo208ett/jhskiVodSEt3QBGh4XBipyWopKwZ93HHaDVZAALi/2A
+xTBtWdEo7XGUujKDvC2/aZKukfjpOiUI8AhLAfjmlcD/UZ1QPh0mHsglRNCmpCw
mwSXA9VNmhz+PiB+Dml4WWnKW/VHo2ujTXxq7+efMU4H2fny3Se3KYOsFPFGZ1TN
QSYlFuShWrHPtiLmUdPoP6CV2mML1tk+l7DIIqXrQhLUKDACeM5roMx0kLhUWB8P
+0uj1CNlNN4JRZlC7xFfqiMbFRU9Z4N6YwIDAQAB")

(def bob-pub-key "bafy...Bob's pub key")

(defn inc-balance [balance] inc)

(def evtA1
  {:event/pubkey alice-pub-key
   :event/tx [inc-balance]
   })

(def evtB1
  {:event/pubkey bob-pub-key
   :event/tx [inc-balance]
   :event/self-parent evtA1})

(def evtA2
  {:event/pubkey alice-pub-key
   :event/tx [inc-balance]
   :event/self-parent evtA1
   :event/othe-parent evtB1})

(def simple-dag evtA2)
;; transit does not zip values? O.o
(t/write (t/writer :json) simple-dag)


;; (require '[clojure.data.fressian :as fress])

;; ;; read / write objects
;; (fress/write evtA2)
;; (fress/read)
