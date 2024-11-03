(ns app.patches
  (:require [rum.cursor]))

;; by default uses PersistentArrayMap, which seem to use equiv on key to find it (which is damm costly on hg events / deeply nested structs)
(set! (.-EMPTY PersistentHashSet) (PersistentHashSet. nil (.-EMPTY PersistentHashMap) empty-unordered-hash))
(extend-type PersistentHashSet
  IEquiv
  (-equiv [coll other]
    (if (= PersistentHashSet (type other))
      (= (hash coll) (hash other))
      ;; default impl
      (and
       (set? other)
       (== (count coll) (count other))
       ^boolean
       (try
         (reduce-kv
          #(or (contains? other %2) (reduced false))
          true hash-map)
         (catch js/Error ex
           false))))))

(extend-type PersistentHashMap
  IEquiv
  (-equiv [coll other]
    (if (= PersistentHashMap (type other))
      (= (hash coll) (hash other))
      ;; default impl
      (equiv-map coll other))))

(extend-type PersistentArrayMap
  IEquiv
  (-equiv [coll other]
    (if (= PersistentArrayMap (type other))
      (= (hash coll) (hash other))
      ;; default impl
      (if (and (map? other) (not (record? other)))
        (let [alen              (alength (.-arr coll))
              ^not-native other other]
          (if (== (.-cnt coll) (-count other))
            (loop [i 0]
              (if (< i alen)
                (let [v (-lookup other (aget (.-arr coll) i) lookup-sentinel)]
                  (if-not (identical? v lookup-sentinel)
                    (if (= (aget (.-arr coll) (inc i)) v)
                      (recur (+ i 2))
                      false)
                    false))
                true))
            false))
        false))))

(extend-type rum.cursor/Cursor
  IWatchable
  (-add-watch [this key callback]
    (add-watch (.-ref this) (list this key)
               (fn [_ _ oldv newv]
                 (let [path (.-path this)
                       old  (get-in oldv path)
                       new  (get-in newv path)]
                   (when (not (= (hash old) (hash new)))
                     (callback key this old new)))))
    this)
  #_(-remove-watch [this key]
    (let [ref (.-ref this)]
      (remove-watch ref (list this key)))
      this))
