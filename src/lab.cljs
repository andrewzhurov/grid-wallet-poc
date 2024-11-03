(ns lab
  (:require [hashgraph.utils.lazy-derived-atom :refer [lazy-derived-atom]]))

(let [l (doall (range 100000))
      v (vec l)]
  (time (-indexOf l 5000))
  (time (nth v 5000)))

;; an efficient way to lazily iterate from the back of vec
(let [l (doall (range 100000))
      v (vec l)]
  (time (-> v reverse rest first)))

;; works
(-> (transient [])
    (conj! 0)
    (conj! 1)
    (-nth 1))

;; perhaps tape can be transient
;; it's more efficient to append
(let [t (transient [])
      v []
      times 1000000]
  (time (loop [out v
               time 0]
          (if (> time times)
            out
            (recur (conj out time) (inc time)))))
  (time (loop [out t
               time 0]
          (if (> time times)
            out
            (recur (conj! out time) (inc time))))))

;; nth' costs match _exactly_, same code runs, I guess
(let [times 100000
      v     (loop [out  []
                   time 0]
          (if (> time times)
            out
            (recur (conj out time) (inc time))))
      t     (loop [out  (transient [])
                   time 0]
              (if (> time times)
                out
                (recur (conj! out time) (inc time))))]
  (time [(count v) (count t)])
  (time
   (loop [time 0]
     (when (< time times)
       (nth v time)
       (recur (inc time)))))

  (time
   (loop [time 0]
     (when (< time times)
       (nth t time)
       (recur (inc time))))))


;; Problem: we need an indexed tape<
;; yet hg viz needs to know what's left<

;; Solution1: use *left< atom to track

;; Solution2: remember idx of what's been played, derive left<
(let [v (doall (vec (range 0 1000000)))]
  (time (drop 500000 v))
  :done) ;; it's damm fast, 0.1ms max

;; how to remember idx of played events?
;; Solution1: attach idx as meta to event on insert to tape
;; will be of use for cache lookup for memoizing fns


(let [v (doall (vec (range 0 1000000)))]
  (time (subvec v 500000 500100))
  :done)

;; is it fast to update depth index as vec of events?
(let [v (doall (vec (repeat 1000000 [])))]
  (time (update v 500000 conj 1))
  (time (update v 500000 conj 2))
  :done)
;; it is, ~0.1ms

;; of sets?
(let [v (doall (vec (repeat 1000000 #{})))]
  (time (update v 500000 conj 1))
  (time (update v 500000 conj 2))
  :done)
;; it is, 0ms - 0.2ms


;; arr[10] = 10
(let [arr #js []]
  (aget arr 0)
  (aset arr 10 10)
  (.map arr (fn [el] (println el) (inc el))))

(require 'goog.object)
;; how efficient is arr[n] = val?
(let [els (doall (shuffle (range 100000)))
      arr #js []]
  (println "---")
  (time
   (doseq [el els]
     (aset arr el el)))
  (time
   (doseq [el els]
     (aget arr el)))

  (let [mt (transient (hash-map))]
    (time
     (doseq [el els]
       (assoc! mt el el)))
    (time
     (doseq [el els]
       (get mt el))))

  (let [jm (new js/Map)]
    (time
     (doseq [el els]
       (.set jm el el)))
    (time
     (doseq [el els]
       (.get jm el))))

  (let [jo #js {}]
    (time
     (doseq [el els]
       (goog.object/set jo el el)))
    (time
     (doseq [el els]
       (goog.object/get jo el)))))
;; arrays are generally faster

;; what if we need to lookup array idx first?
(let [els (doall (->> (shuffle (repeat 100000 {}))
                      (map-indexed (fn [idx el]
                                     (with-meta {:event/creator       (rand-nth ["Alice" "Bob" "Charlie"])
                                                 :event/creation-time idx
                                                 :event/tx ['(+ 1 1)]}
                                       {:el/idx idx})))))
      arr #js []]
  (println "---")
  (time
   (doseq [el els]
     (aset arr (-> el meta :el/idx) el)))
  (time
   (doseq [el els]
     (aget arr (-> el meta :el/idx))))

  (let [mt (transient (hash-map))]
    (time
     (doseq [el els]
       (assoc! mt el el)))
    (time
     (doseq [el els]
       (get mt el))))

  (let [jm (new js/Map)]
    (time
     (doseq [el els]
       (.set jm el el)))
    (time
     (doseq [el els]
       (.get jm el))))

  (let [jo #js {}]
    (time
     (doseq [el els]
       (goog.object/set jo (hash el) el)))
    (time
     (doseq [el els]
       (goog.object/get jo (hash el))))))
;; js map is generally faster


;; pub-key (256 bit) as emoji, how much needed?
;; https://openmoji.org/
;; emojis count: 4177
(count "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBCE9Uli8bGnD4hOWdeo5KKQJ/P/vOazI4MgqJK54w37emP2JwOAOdMmXuwpxbKng3KZz27mz+nKWIlXJ3rzSGMo")
(/ 4177 64)
(require 'clojure.math)
(> (clojure.math/pow 2 256)
   (clojure.math/pow 4177 22))
;; 22 emojies - that's a lot to show on screen


(= (hash {1 {2 2}})
   (hash {1 (hash {2 2})}))
;; => true
;; neat, can blind/forget data without loosing hash integrity

(= (hash (:a {:a {1 1}})) (hash (:b {:b {1 1}})))
;; => true

(identical? (:a {:a {1 1}}) (:b {:b {1 1}}))
;; => false

(identical? (:a {:a :keyword}) (:b {:b :keyword}))
;; => false

(let [a :a]
  (identical? a a))
;; => true


;; what's faster, (into #{} (map ...)) or (->> ... (map) (into #{}))
(let [els (doall (shuffle (concat (range 10000) (range 10000) (range 10000))))]
  (time (->> els
             (map inc)
             (into #{})
             doall))
  (time (->> els
             (into #{} (map inc))
             doall)))
;; (into #{} (map)) generally up to 2x faster

;; is it so for (into {}) ?
(let [els (doall (shuffle (concat (range 10000) (range 10000) (range 10000))))]
  (time (->> els
             (map (fn [el] [el el]))
             (into {})
             doall))
  (time (->> els
             (into {} (map (fn [el] [el el])))
             doall)))
;; yes, (into {}) is generally faster, up to 2x
