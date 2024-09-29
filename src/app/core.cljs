(ns app.core
  "Entry ns for the browser app"
  {:dev/always true}
  (:require [hashgraph.main :as hg]
            [hashgraph.schemas :as hgs]
            [hashgraph.utils.core :refer [hash=] :refer-macros [defn* l letl] :as utils]
            [hashgraph.utils.core-test]

            [app.material]
            [app.page :as page]
            [app.chat :as chat]
            [app.topic :as topic]
            [app.creds :as creds]
            [app.contacts :as contacts]
            [app.topic-gossiper :as topic-gossiper]


            [rum.cursor]
            [rum.core :as rum]
            [pjstadig.humane-test-output]
            [clojure.test :refer [run-tests run-all-tests empty-env]]
            [malli.instrument :as mi]
            [malli.clj-kondo :as mc]
            [malli.dev.cljs :as dev]
            [malli.dev.pretty :as pretty]))

(defn watches-behaviour []
  (let [*a   (atom {})
        *c1  (rum/cursor *a 1)
        *c2  (rum/cursor *a 1)
        *log (atom [])]
    (add-watch *c1 ::c (fn [_ _ c1-old c1-new] (l [c1-old c1-new]) (l ((l swap!) (l *log) conj [c1-old c1-new]))))
    (add-watch *c2 ::c (fn [_ _ c2-old c2-new] (swap! *log conj [c2-old c2-new])))
    (swap! *a assoc 1 1)
    @*log
    ))
#_(l [:watches-before-patch (watches-behaviour)])
;; (deftest cursor-test
;;   (is (= [[nil 1] [nil 1]] (watches-behaviour))))
;; both watches are active, yet they use the same key..
;; I'd expect the second watch to override the first one

;; patching Cursor
#_
(extend-type rum.cursor/Cursor
  IWatchable
  (-add-watch [this key callback]
    (let [ref  (.-ref this)
          path (.-path this)]
      (add-watch ref key
                 (fn [_ _ oldv newv]
                   (let [old (get-in oldv path)
                         new (get-in newv path)]
                     (when-not (hash= old new)
                       (callback key this old new))))))
    this)

  (-remove-watch [this key]
    (let [ref (.-ref this)]
      (remove-watch ref key))
    this))

;; (deftest patched-cursor-test
;;   (is (= [[nil 1]] (watches-behaviour))))

#_(l [:watches-after-patch (watches-behaviour)])


;; (def registry*
;;   (atom {}))

;; (defn register! [type ?schema]
;;   (swap! registry* assoc type ?schema))

;; (mr/set-default-registry!
;;   (mr/mutable-registry registry*))

;; (register! :non-empty-string [:string {:min 1}])

;; (m/validate :non-empty-string "malli")

#_(js/console.log @hgs/*registry)

(defonce *stop-timely-dissemination! (atom nil))

(defn start []
  ;; start is called after code's been reloaded
  ;; this is configured in :after-load in the shadow-cljs.edn
  (dev/start! {:report (pretty/reporter)})
  (run-tests (empty-env)
             ;; 'app.core
             'hashgraph.utils.core
             'hashgraph.utils.core-test
             'hashgraph.main
             'hashgraph.topic
             'app.topic
             'app.creds
             'app.contacts
             'app.chat)

  (reset! *stop-timely-dissemination! (topic-gossiper/start-timely-dissemination!->stop!))

  ;; (run-all-tests #"hashgraph.*")
  ;; (run-all-tests #"app.*")

  ;; (mc/get-kondo-config)

  ;; (mc/print-cljs!)

  ;; (mi/collect!)
  ;; (mi/instrument!)

  ;; (mi/check)
  ;; (js/console.log chat)
  ;; (js/console.log (mc/collect chat))
  ;; (mc/linter-config)
  (when-let [node (.getElementById js/document "root")]
    (rum/mount (page/view) node))
  (js/console.log "started"))

(defn stop []
  ;; stop is called before any code is reloaded
  ;; this is configured in :before load in the shadow-cljs.edn
  (when-let [stop-timely-dissemination! @*stop-timely-dissemination!]
    (stop-timely-dissemination!))
  (dev/stop!)
  #_(mi/unstrument!)
  (js/console.log "stopped"))

(defn ^:export init []
  ;; init is called ONCE when the page loads
  ;; this is called in the index.html and must be exported
  ;; so it is available even in :advanced release builds
  (start))


;; (defn from [{?schema :schema :keys [ns name]}]
;;   (let [ns-name (-> ns str symbol)
;;         schema (m/function-schema ?schema)]
;;     (reduce
;;      (fn [acc schema]
;;        (let [{:keys [input output arity min]} (m/-function-info schema)
;;              _    (l input)
;;              args (mc/transform input {:arity arity})
;;              ret (mc/transform output)]
;;          (conj acc (cond-> {:ns ns-name
;;                             :name name
;;                             :arity arity
;;                             :args args
;;                             :ret ret}
;;                      (= arity :varargs) (assoc :min-arity min)))))
;;      [] (or (seq (m/-function-schema-arities schema))
;;             (m/-fail! ::from-requires-function-schema {:schema schema})))))

;; (defn collect-cljs
;;   ([] (collect-cljs nil))
;;   ([ns]
;;    (let [-collect (fn [k] (or (nil? ns) (= k (symbol (str ns)))))]
;;      (for [[k vs] (m/function-schemas :cljs) :when (-collect k) [_ v] vs v (from (l v))] v))))
;; ;; (js* "debugger;")
;; (l (collect-cljs))
;; (l (mc/linter-config (collect-cljs)))
;; (l m/-function-schemas*)
;; (l [:fs (m/function-schemas :cljs)])
;; (l (mc/linter-config (m/function-schemas :cljs)))
