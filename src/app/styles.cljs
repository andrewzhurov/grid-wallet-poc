(ns app.styles
  (:require [rum.core :as rum]
            [garden.core :refer [css]]))

(def window-height js/window.innerHeight)
(def window-width js/window.innerWidth)
(def view-mode-horizontal? (> window-width window-height))

(def *id->kind->styles (atom {}))
(defn reg-styles! [id bare & [horizontal vertical]]
  (swap! *id->kind->styles assoc id (cond-> {:bare bare}
                                      horizontal (assoc :horizontal horizontal)
                                      vertical   (assoc :vertical vertical))))

(defn kind->css [kind] (-> (rum/react *id->kind->styles)
                           vals
                           (->> (map #(get % kind))
                                (reduce into))
                           css))

(defn styles-hiccup []
  [:<>
   [:style (kind->css :bare)]
   (let [horizontal-css (kind->css :horizontal)
         vertical-css   (kind->css :vertical)]
     [:style (if view-mode-horizontal?
               horizontal-css
               vertical-css)])])
