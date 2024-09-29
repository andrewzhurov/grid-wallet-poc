(ns app.utils
  (:require [clojure.string]))

;; instead could use https://github.com/clj-commons/camel-snake-kebab
(defn pascal-case->kebab-case [pascal-case-str]
  (let [{:keys [words word]} (->> pascal-case-str
                                  (reduce (fn [{:keys [word words]} char]
                                            (if (= (str char) (clojure.string/upper-case (str char)))
                                              {:words (conj words word)
                                               :word  (clojure.string/lower-case (str char))}
                                              {:words words
                                               :word  (str word char)}))
                                          {:words []}))
        final-words          (->> (conj words word)
                                  (filter some?))]
    (clojure.string/join "-" final-words)))

(defmacro incm [val]
  (inc val))
