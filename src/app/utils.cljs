(ns app.utils)

(defn watch-clipboard! [*stop? on-clipboard-text & [prev-clipboard-text]]
  (when-not @*stop?
    (some-> js/navigator
            (.-clipboard)
            (.readText)
            (.then (fn [clipboard-text]
                     (when (not= clipboard-text prev-clipboard-text)
                       (on-clipboard-text clipboard-text))
                     (js/setTimeout #(watch-clipboard! *stop? on-clipboard-text clipboard-text) 250)))
            (.catch (fn [_]
                      (js/setTimeout #(watch-clipboard! *stop? on-clipboard-text prev-clipboard-text) 250))))))

(defn reg-on-clipboard-text!
  [on-clipboard-text]
  (let [*stop?                   (atom false)
        unreg-on-clipboard-text! (fn [] (reset! *stop? true))]
    (watch-clipboard! *stop? on-clipboard-text)
    unreg-on-clipboard-text!))
