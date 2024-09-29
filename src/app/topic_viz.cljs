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
   [hashgraph.utils.core :refer [hash= not-neg] :refer-macros [defn* l letl letl2 when-let*] :as utils]
   [hashgraph.utils.lazy-derived-atom :refer [lazy-derived-atom]]
   [hashgraph.utils.unique-cursor :refer [unique-cursor]]

   [app.styles :refer [reg-styles! shadow0 shadow1 shadow2 shadow3] :as styles]
   [app.topic :as a-topic]
   [app.state :as a-state]
   [app.aid :as a-aid]

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
(def *topic->tips-taped
  (lazy-derived-atom [a-state/*topic->tip-taped]
    (fn [topic->tip-taped]
      (l [::derive-*topic->tips-taped topic->tip-taped])
      (->> topic->tip-taped
           (map (fn [[topic tip-taped]]
                  [topic (-> tip-taped tip-taped->tips-taped)]))
           (into {})))))

(defonce *topic->playback (atom {}))
(defonce *topic->viz-scroll (atom {}))
(defonce *topic->tip-playback (atom {}))
(defn reg-topic-sync-tip-playback-with-viz-scroll! [topic]
  (add-watch (unique-cursor *topic->viz-scroll topic) [::sync-playback topic]
             (fn [_ _ old-viz-scroll new-viz-scroll]
               (l [::sync-playback old-viz-scroll new-viz-scroll])
               (let [tip-taped                (@a-state/*topic->tip-taped topic)
                     tips-taped               (-> tip-taped tip-taped->tips-taped)
                     delta                    (- new-viz-scroll old-viz-scroll)
                     play-forward?            (pos? delta)
                     tips-taped-last-idx      (or (not-neg (dec (count tips-taped)))
                                                  0)
                     {:keys [tips-behind>
                             tips-played<
                             tips-rewinded<]} (@*topic->tip-playback topic)]
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
                                           (sort-by hgt/event->depth))]
                     (when (not-empty just-played<)
                       (reset! hga-state/*just-played< just-played<))
                     (swap! *topic->tip-playback assoc topic {:tips-behind>   new-tips-behind>
                                                              :tips-played<   new-tips-played<
                                                              :tips-rewinded< new-tips-rewinded<}))
                   (let [tips-played>                       (reverse tips-played<)
                         [tips-to-play> new-tips-behind>]   (->> tips-behind> (split-with #(not (hga-view/->before-viz-viewbox? (hga-view/evt->y %) new-viz-scroll))))
                         new-tips-played>*                  (concat tips-played> tips-to-play>)
                         [tips-to-rewind> new-tips-played>] (->> new-tips-played>* (split-with #(hga-view/->after-viz-playback-viewbox? (hga-view/evt->y %) new-viz-scroll)))
                         new-tips-rewinded<                 (into tips-rewinded< tips-to-rewind>)
                         new-tips-played<                   (reverse new-tips-played>)
                         just-rewinded>                     (->> tips-to-rewind>
                                                                 (mapcat #(-> % meta :tip/novel-events))
                                                                 (sort-by hgt/event->depth))]
                     (when-not (empty? just-rewinded>)
                       (reset! hga-state/*just-rewinded> just-rewinded>))
                     (swap! *topic->tip-playback assoc topic {:tips-behind>   new-tips-behind>
                                                              :tips-played<   new-tips-played<
                                                              :tips-rewinded< new-tips-rewinded<})))))))

(add-watch a-state/*topics ::reg-sync-tips-playback-with-viz-scroll
           (fn [_ _ old-topics new-topics]
             (l [::reg-sync-tips-playback-with-viz-scroll old-topics new-topics])
             (let [novel-topics (set/difference (set new-topics) (set old-topics))]
               (doseq [novel-topic novel-topics]
                 (reg-topic-sync-tip-playback-with-viz-scroll! novel-topic)))))


(defn sync-tips-playback-with-playback! [{:keys [tips-behind> tips-played< tips-rewinded<] :as tips-playback}]
  (l [:sync-tips-playback-with-playback! tips-playback])
  (let [playback {:played<   (->> tips-played<
                                  (mapcat #(-> % meta :tip/novel-events))
                                  (sort-by hgt/event->depth))
                  :rewinded< (->> tips-rewinded<
                                  (mapcat #(-> % meta :tip/novel-events))
                                  (sort-by hgt/event->depth))
                  :behind>   (->> tips-behind>
                                  (take 10)
                                  (mapcat #(-> % meta :tip/novel-events))
                                  (sort-by hgt/event->depth))}]
    (reset! hga-playback/*playback playback)))

(defn reg-topic-viz! [topic]
  (add-watch (unique-cursor *topic->tip-playback topic) [::topic-viz topic]
             (fn [_ _ _ new-tips-playback]
               (sync-tips-playback-with-playback! new-tips-playback))))

(defn unreg-topic-viz! [topic]
  (remove-watch (unique-cursor *topic->tip-playback topic) [::topic-viz topic]))

(add-watch a-state/*selected-topic ::syncing-tips-playback-with-playback
           (fn [_ _ old-selected-topic new-selected-topic]
             (l [::syncing-tips-playback-with-playback old-selected-topic new-selected-topic])
             (unreg-topic-viz! old-selected-topic)
             (reg-topic-viz! new-selected-topic)
             (hgm/init-members! (-> new-selected-topic :topic-members))
             (sync-tips-playback-with-playback! (@*topic->tip-playback new-selected-topic))))

(defonce *desired-topic+viz-scroll (atom nil))

(add-watch a-state/*topic->tip-taped ::scroll-to-novel-tip-taped
           (fn [_ _ old-topic->tip-taped new-topic->tip-taped]
             (l [::scroll-to-novel-tip-taped old-topic->tip-taped new-topic->tip-taped])
             (letl2 [novel-topic->tip-taped  (->> new-topic->tip-taped
                                                  (reduce (fn [novel-tips-taped-acc [topic new-tip-taped]]
                                                            (cond-> novel-tips-taped-acc
                                                              (not= (get old-topic->tip-taped topic) new-tip-taped)
                                                              (assoc topic new-tip-taped)))
                                                          {}))
                     novel-topic->viz-scroll (->> novel-topic->tip-taped
                                                  (map (fn [[topic novel-tip-taped]]
                                                         [topic (-> novel-tip-taped
                                                                    hga-view/evt->y
                                                                    (- hga-view/window-height)
                                                                    (+ hga-view/load-area-size hga-view/members-y-end)
                                                                    inc)]))
                                                  (into {}))

                     selected-topic                   @a-state/*selected-topic
                     topic->jumping-viz-scroll        (dissoc novel-topic->viz-scroll selected-topic)]

               (when-let [novel-selected-topic-viz-scroll (l (novel-topic->viz-scroll selected-topic))]
                 (l (reset! *desired-topic+viz-scroll [selected-topic novel-selected-topic-viz-scroll])))

               (when-not (empty? topic->jumping-viz-scroll)
                 (swap! *topic->viz-scroll merge topic->jumping-viz-scroll)))))


(def *topic->viz-tip-taped
  (lazy-derived-atom [*topic->tip-playback]
    (fn [topic->tip-playback]
      (->> topic->tip-playback
           (map (fn [[topic tip-playback]]
                  [topic (-> tip-playback :tips-played< reverse first)]))
           (filter second)
           (into {})))))

(def *topic->viz-cr
  (lazy-derived-atom [*topic->viz-tip-taped]
    (fn [topic->viz-tip-taped]
      (->> topic->viz-tip-taped
           (map (fn [[topic viz-tip-taped]]
                  [topic (hg/->concluded-round viz-tip-taped)]))
           (into {})))))

(add-watch *topic->viz-cr ::run-transitions-on-cr-change
           (fn [_ _ old-topic->viz-cr new-topic->viz-cr]
             (l [::run-transitions-on-cr-change old-topic->viz-cr new-topic->viz-cr])
             (doseq [[topic new-viz-cr] new-topic->viz-cr]
               (let [?old-viz-cr (get old-topic->viz-cr topic)]
                 (hga-transitions/transition-on-cr-change! ?old-viz-cr new-viz-cr)))))

(def *topic->viz-subjective-db
  (lazy-derived-atom [*topic->viz-tip-taped]
    (fn [topic->viz-tip-taped]
      (->> topic->viz-tip-taped
           (map (fn [[topic viz-tip-taped]]
                  [topic (a-topic/tip-taped->subjective-db viz-tip-taped)]))
           (into {})))))


#_
(defn topic->hg-viz-watcher-id [topic] (keyword (str "hg-viz-watcher-" (hash topic))))
#_
(defn reg-viz-watcher! [topic]
  (let [*tip-taped      (rum/cursor a-state/*topic->tip-taped topic)
        tip-taped       (or @*tip-taped (throw (ex-info "no tip-taped is present for topic" {:topic topic})))
        my-did-peer (or @a-state/*my-did-peer (throw (ex-info "my-selected-did is nil" {})))]

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

      (add-watch *tip-taped (-> topic topic->hg-viz-watcher-id)
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
(defn unreg-viz-watcher! [topic]
  (let [*tape (rum/cursor a-state/*topic->tip-taped topic)]
    (remove-watch *tape (-> topic topic->hg-viz-watcher-id))))


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
             :grid-template-areas "\"chat topic-viz\""}
    [:.chat {:grid-area "chat"}]
    [:.topic-viz {:grid-area        "topic-viz"
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

(defn init-topic-viz-scroll! [topic-viz-dom-node topic]
  (l [:init-topic-viz-scroll! topic-viz-dom-node topic])
  (when-let [topic-viz-scroll (@*topic->viz-scroll topic)]
    (.scroll topic-viz-dom-node (js-obj "top" topic-viz-scroll))))


(defc topic-viz* < rum/static rum/reactive
  [topic]
  (let [tip-taped (rum/react (rum/cursor a-state/*topic->tip-taped topic))
        viz-width  (-> tip-taped hg/evt->max-members-across-time
                       (* hga-view/hgs-size))
        viz-height (-> tip-taped hga-view/evt->viz-height)]
    [:<>
     (hga-page/viz viz-width viz-height)
     (a-aid/topic-aids-view viz-width)]))

(defonce *last-processed-topic+viz-scroll (atom nil))
(defc topic-viz < rum/static rum/reactive
  {#_#_:did-mount  (fn [{[topic] :rum/args :as state}]
                 (l [:did-mount topic state])
                 (init-topic-viz-scroll! (rum/dom-node state) topic)
                 state)
   :did-update (fn [{[topic] :rum/args :as state}]
                 (l [:did-update topic state])
                 (init-topic-viz-scroll! (rum/dom-node state) topic)
                 state)
   :after-render (fn [{[topic] :rum/args :as state}]
                   (when-let [[desired-topic desired-viz-scroll :as desired-topic+viz-scroll] (l @*desired-topic+viz-scroll)]
                     (let [[last-processed-topic last-processed-viz-scroll :as last-processed-topic+viz-scroll] (l @*last-processed-topic+viz-scroll)]
                       (when (and (not= desired-topic+viz-scroll last-processed-topic+viz-scroll)
                                  (= desired-topic topic))
                         (l [:processing-desired-viz-scroll])
                         (js/setTimeout (.scroll (rum/dom-node state) (js-obj "top"      desired-viz-scroll
                                                                              "behavior" "smooth"))
                                        0)
                         (reset! *last-processed-topic+viz-scroll desired-topic+viz-scroll))))
                   state)}
  [topic]
  (l [:render topic])
  (rum/react *desired-topic+viz-scroll)
  [:div.topic-viz {:on-scroll (hga-utils/once-per-render
                               (fn [e] (l [:on-scroll]) (l (swap! *topic->viz-scroll assoc topic (-> e (.-target) (goog.object/get "scrollTop"))))))}
   (topic-viz* topic)
   ])
