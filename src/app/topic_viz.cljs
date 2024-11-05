(ns app.topic-viz
  (:require
   [hashgraph.main :as hg]
   [hashgraph.topic :as hgt]
   [hashgraph.members :as hgm]
   [hashgraph.app.events :as hga-events]
   [hashgraph.app.playback :as hga-playback]
   [hashgraph.app.transitions :as hga-transitions]
   [hashgraph.app.state :as hga-state]
   [hashgraph.app.page :as hga-page]
   [hashgraph.app.view :as hga-view]
   [hashgraph.app.utils :as hga-utils]
   [hashgraph.utils.core :refer [hash= not-neg map-vals filter-map-vals] :refer-macros [defn* l letl letl2 when-let*] :as utils]
   [hashgraph.utils.lazy-derived-atom :refer [lazy-derived-atom] :refer-macros [defda deflda]]
   [hashgraph.utils.unique-cursor :refer [unique-cursor]]

   [app.styles :refer [reg-styles! shadow0 shadow1 shadow2 shadow3] :as styles]
   [app.creds :as ac]
   [app.control :as actrl]
   [app.topic :as at]
   [app.state :as as]
   [app.aid :as aa]

   [rum.core :refer [defc defcs] :as rum]
   [clojure.set :as set]
   [clojure.test :refer [deftest testing is are run-tests]]
   [garden.selectors :as gs]
   [garden.units :refer [px px- px+ px-div] :as gun]
   [malli.core :as m]
   [goog.object :as gobject]))

(defn* ^:memoizing tip-taped->tips-taped [tip-taped]
  (let [prev-tips-taped (or (some-> tip-taped hg/self-parent tip-taped->tips-taped)
                            [])]
    (conj prev-tips-taped tip-taped)))

;; ambiguous execution order with tips-playback
#_
(deflda *topic->tips-taped [as/*topic->tip-taped]
  (fn [topic->tip-taped]
    (l [::derive-*topic->tips-taped topic->tip-taped])
    (->> topic->tip-taped
         (map (fn [[topic tip-taped]]
                [topic (-> tip-taped tip-taped->tips-taped)]))
         (into {}))))

#_
(def *topic-path->main-creator
  (rum/derived-atom [as/*topic-path->member-init-keys-log] ::derive-*topic-path->main-creator
    (fn [topic-path->member-init-keys-log]
      (l [::derive-*topic-path->main-creator topic-path->member-init-keys-log])
      (l (->> topic-path->member-init-keys-log
              (into (hash-map) (map (fn [[topic-path member-init-keys-log]]
                                      [topic-path (-indexOf member-init-keys-log (actrl/topic-path->member-init-key topic-path))]))))))))
#_
(set! hga-view/*topic-path->main-creator *topic-path->main-creator)

(defonce *topic-path->playback (atom (hash-map)))
(defonce *topic-path->viz-scroll (atom (hash-map)))
(defonce *topic-path->tip-playback (atom (hash-map)))

(deflda *topic-path->viz-tip-taped [*topic-path->tip-playback]
  (filter-map-vals (fn [tip-playback] (or (-> tip-playback :tips-played< reverse first)
                                          (-> tip-playback :tips-behind> first)))))

(deflda *topic-path->viz-cr [*topic-path->viz-tip-taped] (map-vals hg/->concluded-round))
(deflda *topic-path->viz-cr-est [*topic-path->viz-cr] (map-vals ac/cr->cr-est))
(deflda *topic-path->viz-active-creators# [*topic-path->viz-cr-est] (map-vals (comp :active-creators :concluded-round/db)))

(deflda *topic-path->viz-projected-cr [*topic-path->viz-tip-taped] (map-vals at/event->projected-cr))
(deflda *topic-path->viz-nr-creators# [*topic-path->viz-projected-cr] (map-vals (fn [projected-cr] (-> projected-cr :concluded-round/es-r ;; note: this is a projected cr, es-r are those not received in the actual cr this one is atop
                                                                                                       (->> (into #{} (map hg/creator)))))))
(deflda *topic-path->viz-pending-creators# [*topic-path->viz-active-creators# *topic-path->viz-nr-creators#]
  (fn [topic-path->viz-active-creators# topic-path->viz-nr-creators#]
    (->> topic-path->viz-nr-creators#
         (into (hash-map) (map (fn [[topic-path viz-nr-creators#]]
                                 [topic-path (set/difference viz-nr-creators# (-> topic-path topic-path->viz-active-creators#))]))))))

(deflda *topic-path->viz-member-aid-info [*topic-path->viz-cr-est *topic-path->viz-pending-creators#]
  (fn [topic-path->viz-cr-est topic-path->viz-pending-creators#]
    (->> topic-path->viz-cr-est
         (into (hash-map) (map (fn [[topic-path viz-cr-est]]
                                 (let [member-init-keys-log     (-> viz-cr-est hg/cr->db :member-init-keys-log)
                                       pending-member-init-keys (->> topic-path topic-path->viz-pending-creators# (map (fn [viz-pending-creator] (nth member-init-keys-log viz-pending-creator))))]
                                   [topic-path (ac/cr-est+pending-member-init-keys#->member-aid-info viz-cr-est pending-member-init-keys)])))))))

#_
(def *topic-path->creator->viz-x
  (rum/derived-atom [*topic-path->viz-member-aid-info] ::derive-*topic-path->creator->viz-x
    (fn [topic-path->viz-member-aid-info]
      (l [::derive-*topic-path->creator->viz-x topic-path->viz-member-aid-info])
      (l (->> topic-path->viz-member-aid-info
              (map-vals (fn [viz-member-aid-info]
                          (->> viz-member-aid-info
                               :member-aid-info/creator->member-aid-info
                               (map-vals (fn [{:member-aid-info/keys [pos-x]}] (hga-view/idx->x pos-x)))))))))))

(defda *topic-path->creator->viz-x [*topic-path->viz-member-aid-info]
  (fn [topic-path->viz-member-aid-info]
    (l [::derive-*topic-path->creator->viz-x topic-path->viz-member-aid-info])
    (l (->> topic-path->viz-member-aid-info
            (map-vals (fn [viz-member-aid-info]
                        (->> viz-member-aid-info
                             :member-aid-info/creator->member-aid-info
                             (map-vals (fn [{:member-aid-info/keys [pos-x]}] (hga-view/idx->x pos-x))))))))))

#_
(set! hga-view/*topic-path->creator->viz-x *topic-path->creator->viz-x)

#_
(add-watch *topic-path->creator->viz-x ::sync-viz-with-member-init-key-positions
           (fn [_ _ old-topic-path->creator->viz-x new-topic-path->creator->viz-x]
             (let [changed-topic-paths
                   (->> new-topic-path->creator->viz-x
                        keys
                        (filter (fn [topic-path] (not (hash= (get old-topic-path->creator->viz-x topic-path)
                                                             (get new-topic-path->creator->viz-x topic-path))))))
                   topic-path->old-x->new-x
                   (->> changed-topic-paths
                        (reduce (fn [acc topic-path]
                                  (let [new-creator->viz-x (get new-topic-path->creator->viz-x topic-path)]
                                    (->> new-creator->viz-x
                                         (reduce (fn [acc2 [member-init-key new-x]]
                                                   (let [?old-x (get-in old-topic-path->creator->viz-x [topic-path member-init-key])]
                                                     (cond-> acc2
                                                       (and ?old-x (not= ?old-x new-x))
                                                       (assoc-in [topic-path ?old-x] new-x))))
                                                 acc))))
                                (hash-map)))]
               (doseq [[topic-path old-x->new-x] topic-path->old-x->new-x]
                 (hga-transitions/re-position! topic-path old-x->new-x)))))


(defn reg-topic-path-sync-tip-playback-with-viz-scroll! [topic-path]
  (add-watch (unique-cursor *topic-path->viz-scroll topic-path) [::sync-playback topic-path]
             (fn [_ _ old-viz-scroll new-viz-scroll]
               (l [::sync-playback old-viz-scroll new-viz-scroll])
               (let [tip-taped                (@as/*topic-path->tip-taped topic-path)
                     tips-taped               (-> tip-taped tip-taped->tips-taped)
                     delta                    (- new-viz-scroll old-viz-scroll)
                     play-forward?            (pos? delta)
                     tips-taped-last-idx      (or (not-neg (dec (count tips-taped)))
                                                  0)
                     {:keys [tips-behind>
                             tips-played<
                             tips-rewinded<]} (@*topic-path->tip-playback topic-path)]
                 (if play-forward?
                   ;; TODO switch to split-with* (?)
                   (let [[tips-unrewinded< new-tips-rewinded<] (->> tips-rewinded< (split-with #(not (hga-view/->after-viz-playback-viewbox? (hga-view/evt->y %) new-viz-scroll))))
                         last-tip-unrewinded-idx               (-indexOf tips-taped (->> tips-unrewinded< reverse first))
                         last-tip-played-idx                   (-indexOf tips-taped (->> tips-played< reverse first))
                         ahead-tip-idx                         (or (some-> last-tip-unrewinded-idx not-neg inc)
                                                                   (some-> last-tip-played-idx not-neg inc)
                                                                   0)
                         tips-just-played<                     (cond-> tips-unrewinded<
                                                                 (and (or (empty? tips-rewinded<)
                                                                          (empty? new-tips-rewinded<))
                                                                      (<= ahead-tip-idx tips-taped-last-idx))
                                                                 (concat (->> (subvec tips-taped ahead-tip-idx)
                                                                              l
                                                                              (take-while #(not (hga-view/->after-viz-playback-viewbox? (l (hga-view/evt->y %)) new-viz-scroll))))))

                         new-tips-played<*                  (concat tips-played< tips-just-played<)
                         [tips-to-behind< new-tips-played<] (->> new-tips-played<* (split-with #(hga-view/->before-viz-viewbox? (hga-view/evt->y %) new-viz-scroll)))
                         new-tips-behind>                   (into tips-behind> tips-to-behind<)

                         just-played< (->> tips-just-played<
                                           (mapcat #(-> % meta :tip/novel-events))
                                           (sort-by hg/event->depth))

                         old-viz-cr         (get @*topic-path->viz-cr topic-path)
                         old-creator->viz-x (get @*topic-path->creator->viz-x topic-path)]
                     ;; first runs transitions, then will derive viz-cr, member-aid-info, creator->viz-x, running cr-transitions and re-position transitions
                     (when (or (not-empty just-played<)
                               (not-empty tips-to-behind<))
                       (swap! *topic-path->tip-playback assoc topic-path {:tips-behind>   new-tips-behind>
                                                                          :tips-played<   new-tips-played<
                                                                          :tips-rewinded< new-tips-rewinded<}))

                     (let [new-viz-cr         (get @*topic-path->viz-cr topic-path)
                           new-creator->viz-x (get @*topic-path->creator->viz-x topic-path)]
                       (when (not (hash= old-creator->viz-x new-creator->viz-x))
                         (when-let [old-x->new-x (not-empty (reduce (fn [acc [creator new-x]]
                                                                      (let [?old-x (get old-creator->viz-x creator)]
                                                                        (cond-> acc
                                                                          (and ?old-x (not= ?old-x new-x))
                                                                          (assoc ?old-x new-x))))
                                                                    (hash-map)
                                                                    new-creator->viz-x))]
                           (hga-transitions/re-position! topic-path old-x->new-x)))

                       (when (not-empty just-played<)
                         (hga-transitions/play! topic-path new-creator->viz-x just-played<)
                         #_(reset! hga-state/*just-played< just-played<))

                       (when (not (hash= old-viz-cr new-viz-cr))
                         (let [initial-cr (get @as/*topic-path->initial-cr topic-path)]
                           (hga-transitions/transition-on-cr-change! topic-path new-creator->viz-x initial-cr old-viz-cr new-viz-cr)))))

                   (let [tips-played>                       (reverse tips-played<)
                         [tips-to-play> new-tips-behind>]   (->> tips-behind> (split-with #(not (hga-view/->before-viz-viewbox? (hga-view/evt->y %) new-viz-scroll))))
                         new-tips-played>*                  (concat tips-played> tips-to-play>)
                         [tips-to-rewind> new-tips-played>] (->> new-tips-played>* (split-with #(hga-view/->after-viz-playback-viewbox? (hga-view/evt->y %) new-viz-scroll)))
                         new-tips-rewinded<                 (into tips-rewinded< tips-to-rewind>)
                         new-tips-played<                   (reverse new-tips-played>)
                         just-rewinded>                     (->> tips-to-rewind>
                                                                 (mapcat #(-> % meta :tip/novel-events))
                                                                 (sort-by hg/event->depth))
                         old-viz-cr                         (get @*topic-path->viz-cr topic-path)
                         old-creator->viz-x                 (get @*topic-path->creator->viz-x topic-path)]
                     (when (or (not-empty tips-to-play>)
                               (not-empty tips-to-rewind>))
                       (swap! *topic-path->tip-playback assoc topic-path {:tips-behind>   new-tips-behind>
                                                                          :tips-played<   new-tips-played<
                                                                          :tips-rewinded< new-tips-rewinded<}))

                     (let [new-viz-cr         (get @*topic-path->viz-cr topic-path)
                           new-creator->viz-x (get @*topic-path->creator->viz-x topic-path)]
                       (when (not (hash= old-creator->viz-x new-creator->viz-x))
                         (when-let [old-x->new-x (not-empty (reduce (fn [acc [creator new-x]]
                                                                      (let [?old-x (get old-creator->viz-x creator)]
                                                                        (cond-> acc
                                                                          (and ?old-x (not= ?old-x new-x))
                                                                          (assoc ?old-x new-x))))
                                                                    (hash-map)
                                                                    new-creator->viz-x))]
                           (hga-transitions/re-position! topic-path old-x->new-x)))

                       (let [initial-cr (get @as/*topic-path->initial-cr topic-path)]
                         (when (not (hash= old-viz-cr new-viz-cr))
                           (hga-transitions/transition-on-cr-change! topic-path new-creator->viz-x initial-cr old-viz-cr new-viz-cr)))

                       (when-not (empty? just-rewinded>)
                         (hga-transitions/rewind! topic-path just-rewinded>)))))))))



(add-watch as/*topic-paths ::reg-sync-tips-playback-with-viz-scroll
           (fn [_ _ old-topic-paths new-topic-paths]
             (l [::reg-sync-tips-playback-with-viz-scroll old-topic-paths new-topic-paths])
             (let [novel-topic-paths (set/difference new-topic-paths old-topic-paths)]
               (doseq [novel-topic-path novel-topic-paths]
                 (reg-topic-path-sync-tip-playback-with-viz-scroll! novel-topic-path)))))

#_
(for [topic-path @as/*topic-paths]
  (reg-topic-path-sync-tip-playback-with-viz-scroll! topic-path))

(defn sync-tips-playback-with-playback! [{:keys [tips-behind> tips-played< tips-rewinded<] :as tips-playback}]
  (l [:sync-tips-playback-with-playback! tips-playback])
  (let [playback {:played<   (->> tips-played<
                                  (mapcat #(-> % meta :tip/novel-events))
                                  (sort-by hg/event->depth))
                  :rewinded< (->> tips-rewinded<
                                  (mapcat #(-> % meta :tip/novel-events))
                                  (sort-by hg/event->depth))
                  :behind>   (->> tips-behind>
                                  (take 10)
                                  (mapcat #(-> % meta :tip/novel-events))
                                  (sort-by hg/event->depth))}]
    (reset! hga-playback/*playback playback)))

(defn reg-topic-path-viz! [topic-path]
  (add-watch (unique-cursor *topic-path->tip-playback topic-path) [::topic-path-viz topic-path]
             (fn [_ _ _ new-tips-playback]
               (sync-tips-playback-with-playback! new-tips-playback))))

(defn unreg-topic-path-viz! [topic-path]
  (remove-watch (unique-cursor *topic-path->tip-playback topic-path) [::topic-path-viz topic-path]))

(add-watch as/*selected-topic-path ::syncing-tips-playback-with-playback
           (fn [_ _ old-selected-topic-path new-selected-topic-path]
             (l [::syncing-tips-playback-with-playback old-selected-topic-path new-selected-topic-path])
             (unreg-topic-path-viz! old-selected-topic-path)
             (reg-topic-path-viz! new-selected-topic-path)
             ;; (hgm/init-members! (-> new-selected-topic-path :topic-members))
             (sync-tips-playback-with-playback! (@*topic-path->tip-playback new-selected-topic-path))))

(defonce *desired-topic-path+viz-scroll (atom nil))

;; when adding event in a watch to *topic-path->tip-taped this handler gets called with new val before previous val
;;   topic-path->tip-taped
;;   V            ^    V (new)  V(prev)
;;   assoc-did-peer    this     this
;;
(add-watch as/*topic-path->tip-taped ::scroll-to-novel-tip-taped
           (fn [_ _ old-topic-path->tip-taped new-topic-path->tip-taped]
             (l [::scroll-to-novel-tip-taped old-topic-path->tip-taped new-topic-path->tip-taped])
             (letl2 [novel-topic-path->tip-taped  (->> new-topic-path->tip-taped
                                                       (reduce (fn [novel-tips-taped-acc [topic-path new-tip-taped]]
                                                                 (cond-> novel-tips-taped-acc
                                                                   (not (hash= (get old-topic-path->tip-taped topic-path) new-tip-taped))
                                                                   (assoc topic-path new-tip-taped)))
                                                               (hash-map)))
                     novel-topic-path->viz-scroll (->> novel-topic-path->tip-taped
                                                       (into (hash-map) (map (fn [[topic-path novel-tip-taped]]
                                                                               [topic-path (-> novel-tip-taped
                                                                                               hga-view/evt->y
                                                                                               (- hga-view/window-height)
                                                                                               (+ hga-view/load-area-size hga-view/members-y-end)
                                                                                               inc)]))))

                     selected-topic-path                   @as/*selected-topic-path
                     topic-path->jumping-viz-scroll        (dissoc novel-topic-path->viz-scroll selected-topic-path)]

                    (when-let [novel-selected-topic-path-viz-scroll (l (novel-topic-path->viz-scroll selected-topic-path))]
                      (l (reset! *desired-topic-path+viz-scroll [selected-topic-path novel-selected-topic-path-viz-scroll])))

                    (when-not (empty? topic-path->jumping-viz-scroll)
                      (swap! *topic-path->viz-scroll merge topic-path->jumping-viz-scroll)))))

#_
(defn novel-map [old-map new-map]
  (->> new-map
       (reduce (fn [novel-map-acc [new-k new-v]]
                 (let [?old-v (get old-map new-k)]
                   (cond-> novel-map-acc
                     (or (nil? ?old-v)
                         (not (hash= ?old-v new-v))
                         (assoc! new-k [?old-v new-v])))))
               (transient {}))
       (persistent!)))

#_
(add-watch *topic-path->viz-cr ::transition-cr-on-viz-cr-change
           (fn [_ _ old-topic-path->viz-cr new-topic-path->viz-cr]
             (let [novel-topic-path->?old+new (novel-map old-topic-path->viz-cr new-topic-path->viz-cr)]
               (doseq [[novel-topic-path [?old new]] novel-topic-path->?old+new]
                 (hga-transitions/transition-on-cr-change! novel-topic-path ?old new)))))

#_
(add-watch *topic-path->viz-cr ::run-transitions-on-cr-change
           (fn [_ _ old-topic-path->viz-cr new-topic-path->viz-cr]
             (l [::run-transitions-on-cr-change old-topic-path->viz-cr new-topic-path->viz-cr])
             (doseq [[topic-path new-viz-cr] new-topic-path->viz-cr]
               (let [?old-viz-cr (get old-topic-path->viz-cr topic-path)]
                 (when (or (nil? ?old-viz-cr)
                           (not (hash= ?old-viz-cr new-viz-cr)))
                   (hga-transitions/transition-on-cr-change! topic-path ?old-viz-cr new-viz-cr))))))

(deflda *topic-path->viz-subjective-db [*topic-path->viz-tip-taped]
  (map-vals at/tip-taped->subjective-db))


#_
(defn topic-path->hg-viz-watcher-id [topic-path] (keyword (str "hg-viz-watcher-" (hash topic-path))))
#_
(defn reg-viz-watcher! [topic-path]
  (let [*tip-taped      (rum/cursor as/*topic-path->tip-taped topic-path)
        tip-taped       (or @*tip-taped (throw (ex-info "no tip-taped is present for topic-path" {:topic-path topic-path})))
        my-did-peer (or @as/*my-did-peer (throw (ex-info "my-selected-did is nil" {})))]

    (hgm/init-members! (-> tip-taped hgt/?event->creators))
    (set! hg/main-creator my-did-peer)

    (let [tape<      (-> tip-taped meta :tip/tape)
          *left<     hga-playback/*left<]
      ;; better remember playbacks, and switch the selected one
      ;; (hga-playback/stop-sync-playback-with-viz-scroll!)
      ;; (hga-playback/rewind-all! :smooth false)
      ;; (reset! *left< tape<)
      ;; (reset! hga-playback/*playback hga-playback/playback-init)
      ;; (reset! hga-state/*main-tip nil)
      ;; (reset! hga-state/*just-played< '())
      ;; (reset! hga-state/*just-rewinded> '())
      ;; (hga-playback/start-sync-playback-with-viz-scroll!)
      ;; (l [:scrolling-to-tip-taped tip-taped])
      ;; (hga-playback/viz-scroll-to-event! (l tip-taped))

      (add-watch *tip-taped (-> topic-path topic-path->hg-viz-watcher-id)
                 (fn [_ _ prev-tip-taped new-tip-taped]
                   (l [:tip-taped-changed prev-tip-taped new-tip-taped])
                   (let [prev-tape        (-> prev-tip-taped meta :tip/tape)
                         new-tape         (-> new-tip-taped meta :tip/tape)
                         new-novel-events (-> new-tip-taped meta :tip/novel-events)]
                     (if (empty? prev-tape)
                       (reset! *left< new-tape)
                       (swap! *left< (fn [left<] (vec (concat left< new-novel-events)))))
                     (l [:scrolling-to-new-tip-taped new-tip-taped])
                     (hga-playback/viz-scroll-to-event! new-tip-taped)))))))

#_
(defn unreg-viz-watcher! [topic-path]
  (let [*tape (rum/cursor as/*topic-path->tip-taped topic-path)]
    (remove-watch *tape (-> topic-path topic-path->hg-viz-watcher-id))))


#_
(defn reset-scroll-utils! [dom-node]
  (reset! hga-state/*viz-scroll-by! (fn [viz-px & {:keys [smooth?]}]
                                      (let [px viz-px]
                                        (.scrollBy dom-node (if smooth?
                                                              (js-obj "top" px
                                                                      "behavior" "smooth")
                                                              (js-obj "top" px))))))
  (reset! hga-state/*viz-scroll! (fn [viz-px & {:keys [smooth?]}]
                                   (let [px (+ viz-px hga-view/window-y-span)]
                                     (.scroll dom-node (if smooth?
                                                         (js-obj "top" px
                                                                 "behavior" "smooth")
                                                         (js-obj "top" px)))) ;; doesn't scroll pixel-perfect on zoom
                                   ))
  (.addEventListener dom-node "scroll"
                     (hga-utils/once-per-render
                      (fn [e]
                        (reset! hga-state/*viz-scroll (- (-> e (.-target) (goog.object/get "scrollTop"))
                                                         hga-view/window-y-span)))))
  (.focus dom-node
          ;; focusVisible works only in Firefox atm https://developer.mozilla.org/en-US/docs/Web/API/HTMLElement/focus#browser_compatibility
          ;; overriding :outline manually in styles
          ^js {"focusVisible" false}))

(def topic-styles
  [[:.topic {:height :inherit
             :overflow :hidden
             :display :grid
             :grid-template-columns "1fr auto"
             :grid-template-rows "1fr"
             :gap "0px 0px"
             :grid-auto-flow :row
             :grid-template-areas "\"chat topic-path-viz\""}
    [:.chat {:grid-area "chat"}]
    [:.topic-path-viz {:grid-area        "topic-path-viz"
                  :position         :relative
                  :box-shadow       shadow0
                  :overflow-y       :scroll
                  :overflow-x       :hidden
                  :scrollbar-gutter "stable"
                  :scrollbar-width  hga-view/scrollbar-height ;; does not work in Safari https://developer.mozilla.org/en-US/docs/Web/CSS/scrollbar-width
                  }
     [:svg#viz]
     [:#members {:position :fixed
                 :bottom   "3px"
                 ;; :right    hga-view/scrollbar-height ;; automatically included
                 }
      [:.member
       [:.member-name {:max-width  "50px"
                       :overflow-x :hidden
                       ;; :text-overflow :ellipsis
                       }]]]]]])
(reg-styles! ::topic topic-styles)

(defn init-topic-path-viz-scroll! [topic-path-viz-dom-node topic-path]
  (l [:init-topic-path-viz-scroll! topic-path-viz-dom-node topic-path])
  (when-let [topic-path-viz-scroll (@*topic-path->viz-scroll topic-path)]
    (.scroll topic-path-viz-dom-node (js-obj "top" topic-path-viz-scroll))))


(defc topic-path-viz* < rum/static rum/reactive
  [topic-path]
  (let [tip-taped        (rum/react (rum/cursor as/*topic-path->tip-taped topic-path))
        viz-height       (-> tip-taped hga-view/evt->viz-height)
        ?member-aid-info (rum/react (rum/cursor *topic-path->viz-member-aid-info topic-path))
        viz-width        (-> (or (some-> ?member-aid-info :member-aid-info/creator->member-aid-info count) 1)
                             (* hga-view/hgs-size))]
    [:<>
     (hga-page/viz topic-path viz-width viz-height)
     (when ?member-aid-info
       (aa/topic-path-aids-view topic-path viz-width ?member-aid-info))]))

(defonce *last-processed-topic-path+viz-scroll (atom nil))
(defc topic-path-viz < rum/static rum/reactive
  {:did-mount  (fn [{[topic-path] :rum/args :as state}]
                 (l [:did-mount topic-path state])
                 (init-topic-path-viz-scroll! (rum/dom-node state) topic-path)
                 state)
   :did-update (fn [{[topic-path] :rum/args :as state}]
                 (l [:did-update topic-path state])
                 (init-topic-path-viz-scroll! (rum/dom-node state) topic-path)
                 state)
   :after-render (fn [{[topic-path] :rum/args :as state}]
                   (when-let [[desired-topic-path desired-viz-scroll :as desired-topic-path+viz-scroll] (l @*desired-topic-path+viz-scroll)]
                     (let [[last-processed-topic-path last-processed-viz-scroll :as last-processed-topic-path+viz-scroll] (l @*last-processed-topic-path+viz-scroll)]
                       (when (and (not (hash= desired-topic-path+viz-scroll last-processed-topic-path+viz-scroll))
                                  (hash= desired-topic-path topic-path))
                         (l [:processing-desired-viz-scroll])
                         (js/setTimeout (.scroll (rum/dom-node state) (js-obj "top"      desired-viz-scroll
                                                                              "behavior" "smooth"))
                                        0)
                         (reset! *last-processed-topic-path+viz-scroll desired-topic-path+viz-scroll))))
                   state)}
  [topic-path]
  (l [:render topic-path])
  (rum/react *desired-topic-path+viz-scroll)
  [:div.topic-path-viz {:on-scroll (hga-utils/once-per-render
                               (fn [e] (l [:on-scroll]) (l (swap! *topic-path->viz-scroll assoc topic-path (-> e (.-target) (goog.object/get "scrollTop"))))))}
   (topic-path-viz* topic-path)
   ])
