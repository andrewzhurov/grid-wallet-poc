(ns app.styles
  (:require [rum.core :as rum]
            [garden.core :refer [css]]
            [garden.compiler :as gcomp]
            [malli.core :as m]))

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
   [:style (gcomp/render-css (kind->css :bare))]
   (let [horizontal-css (kind->css :horizontal)
         vertical-css   (kind->css :vertical)]
     [:style (if view-mode-horizontal?
               (gcomp/render-css horizontal-css)
               (gcomp/render-css vertical-css))])])

(def padded-column-style
  {:height         "100%"
   :width          "600px"
   :max-width      "70%"
   :display        :flex
   :flex-direction :column
   :margin-left    :auto
   :margin-right   :auto})

(def card-style
  {:padding          "10px"
   :border-radius    "4px"
   :background-color "white"})

(def accent-style-light
  {:background-color "rgba(0,0,0,0.015)"})

(def accent-style
  {:background-color "rgba(0,0,0,0.025)"})

(def accent2-style
  {:background-color "rgba(0,0,0,0.05)"})

(def shadow0 "rgba(0, 0, 0, 0.16) 0px 0px 0px 1px;")
(def shadow1 "rgba(0, 0, 0, 0.16) 0px 1px 4px 0px;")
(def shadow2 "rgba(0, 0, 0, 0.16) 0px 2px 6px 0px;")
(def shadow3 "rgba(0, 0, 0, 0.16) 0px 3px 8px 0px;")
