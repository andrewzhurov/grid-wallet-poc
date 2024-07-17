(ns utils)

(defmacro l [expr]
  `(let [res# ~expr]
     (js/console.log (quote ~expr) res#)
     res#))
