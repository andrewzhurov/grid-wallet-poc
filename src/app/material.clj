(ns app.material
  (:require [hashgraph.utils.core :refer [l macroexpand-names conjs]]
            [app.utils :refer [pascal-case->kebab-case]]
            [rum.core :as rum]
            [clojure.java.io :as io]
            [daiquiri.compiler :as compiler]
            [daiquiri.util :as d-util]))



(defmacro adapt-class
  "Adapts JavaScript React component for usage in Rum components.

  [:div
    (rum/adapt-class js/Button {:on-click f} \"press me\")]

  When using in Clojure JVM calls *render-js-component* to render a fallback.
  See example in rum.examples.js-components ns"
  [type attrs & children]
  (let [[attrs children] (if (map? attrs)
                           [attrs children]
                           [nil (cons attrs children)])]
    `(rum/adapt-class-helper ~type (daiquiri.interpreter/element-attributes ~attrs) (cljs.core/array ~@(map #(compiler/compile-html % &env) children)))))

"@mui/material/SpeedDial$default"
'js/shadow.js.shim.module$$mui$material$SpeedDial$default
(defn required-js-ns-str->shim-sym [required-js-ns-str]
  (let [shim-sym (-> required-js-ns-str
                     (clojure.string/replace "@" "$")
                     (clojure.string/replace "/" "$")
                     (->> (str "js/shadow.js.shim.module$"))
                     (symbol))]
    shim-sym))

(defmacro def-util [[required-ns _as required-as]]
  #_(l [required-ns required-as])
  (let [required-shim (required-js-ns-str->shim-sym required-ns)]
    #_(l required-shim)
    `(defmacro ~required-as [& args#]
       (concat `(adapt-class) '(~required-shim) (or args# [{}])))))

(defmacro def-utils []
  (let [material-cljs-str (slurp (io/resource "app/material.cljs"))
        material-cljs-ns  (-> (str "[" material-cljs-str "]")
                              read-string
                              first)
        material-cljs-ns-requires (->> material-cljs-ns
                                       (some (fn [form] (when (and (list? form)
                                                                   (-> form first (= :require)))
                                                          (rest form)))))
        material-cljs-ns-requires-mui (->> material-cljs-ns-requires
                                           (filter (comp string? first)))]
    #_(l material-cljs-ns-requires-mui)
    (concat `(do)
            (for [require material-cljs-ns-requires-mui]
              `(def-util ~require)))))

(def-utils)
(l :loaded-material.clj)

;; (l (require-js-str>import-name+shim "@mui/material/SpeedDial$default"))
;; (l (macroexpand-names ["def-js-comps" "app.material/def-js-comp"]
;;                       (def-js-comps [`SpeedDial `SpeedDialAction `SpeedDialIcon])))
;; (def-js-comps [`SpeedDial `SpeedDialAction `SpeedDialIcon])

;; (def-js-comp app.material/SpeedDial)
;; (def-js-comp app.material/SpeedDialAction)
;; (def-js-comp app.material/SpeedDialIcon)

