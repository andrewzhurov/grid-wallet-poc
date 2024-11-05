(ns app.aid
  (:require [hashgraph.main :as hg]
            [hashgraph.topic :as hgt]
            [hashgraph.members :as hg-members]
            [hashgraph.utils.core
             :refer [color-rgba-str]
             :refer-macros [defn* l letl letl2 when-let*]
             :as utils]
            [hashgraph.utils.lazy-derived-atom :refer [lazy-derived-atom]]

            [hashgraph.app.state :as hga-state]
            [hashgraph.app.view :refer [t] :as hga-view]
            [hashgraph.app.transitions :refer [tt] :as hga-transitions]
            [hashgraph.app.styles :refer [reg-styles!] :as hga-styles]
            [hashgraph.app.icons :as hga-icons]
            [hashgraph.app.avatars :as hga-avatars]
            [hashgraph.app.playback :as hga-playback]
            [hashgraph.app.utils :as hga-utils]

            [app.creds :as ac]
            [app.styles :refer [shadow0 shadow1] :as styles]

            [rum.core :refer [defc defcs] :as rum]
            [garden.units :refer [px]]
            [garden.selectors :as gs]
            [garden.color :as gc]
            [app.state :as as]))

(def avatar-margin (-> (+ hga-view/hgs-padding hga-view/evt-r)
                       (- (/ hga-view/avatar-size 2))))
(def avatar-margin2 (+ hga-view/hgs-padding hga-view/hgs-padding (- hga-view/avatar-size)))

(def hierarchy-toggle-size 20)

(def styles
  [[:#members {:background-color hga-view/members-background-color
               :transition       (hga-view/t :bottom hga-transitions/tt)}
    [:&.hierarchical
     [:.hierarchy-toggler {:transform "rotate(180deg)"}]]
    [:.hierarchy-toggler {:position         :fixed
                          :width            (px hierarchy-toggle-size)
                          :height           (px hierarchy-toggle-size)
                          :bottom           (px (+ hga-view/avatar-size 20))
                          :display          :flex
                          :align-items      :center
                          :justify-content  :center
                          :background-color :white
                          :box-shadow       shadow1
                          :border-radius    "50%"
                          :transform        "rotate(0deg)"
                          :transition       (t :transform (/ tt 2))
                          :cursor           :pointer}]
    [:.member {:position :absolute
               :transition (t :opacity (/ tt 2)
                              :left tt
                              :top tt)
               }
     [:&.me
      [:.member-name {:font-weight :bold}]]
     [:.avatar {:position :relative
                :width  (px hga-view/avatar-size)
                :height (px hga-view/avatar-size)}
      [:.member-name {:position   :absolute
                      :min-width  :max-content
                      :left       (px (/ hga-view/avatar-size 2))
                      :margin-top (px -6) ;; -33 is fun
                      :transform  "translateX(-50%)"
                      ;; :font-size (px hga-view/member-name-font-size)
                      :color      :black}]]
     #_
     [:.connectivity {:display       :none
                      :position      :absolute
                      :bottom        (px -2)
                      :right         (px -2)
                      #_#_:transform "translate(-100%, -50%)"}
      [:&.poor {:display :block}
       [:svg {:vertical-align :middle}]]]]]])

(reg-styles! ::members styles)

(defn did->alias [did]
  (apply str (take 3 (drop 16 did))))

#_(defn* ^:memoizing topic->aid->color [topic]
  )

(defn bake-alpha [rgba]
  (l rgba)
  (gc/rgba (-> (->> [(:red rgba) (:green rgba) (:blue rgba)]
                    (mapv (fn [c]
                            (let [rem-c   (- 255 c)
                                  added-c (- rem-c (* rem-c (:alpha rgba)))]
                              (+ c added-c)))))
               (conj 1))))

(defn rgb->css-str [{r :red g :green b :blue}]
  (str "rgb(" r "," g "," b ")"))

(defn aid-depth->y [aid-depth]
  (-> (/ hga-view/avatar-size 2)
      (+ (* aid-depth (+ hga-view/avatar-size 20)))))

#_
(defc aid-view < rum/static rum/reactive
  {:key-fn (fn [member-aid] (hash member-aid))}
  [member-aid member-color member-stake-pos active? aid->x hierarchy-size hierarchy-path hierarchical-view?]
  (when-let* [member-name   (l (rum/react (rum/cursor ac/*aid->aid-name member-aid)))
              #_#_member-idx    (l (rum/react (rum/cursor hga-playback/*root-aid->idx member-aid)))
              member-avatar (l (ac/aid->avatar member-aid))
              #_#_me?       (= member-name (l hg/main-creator))
              ]
    (let [adjusted-member-color (-> (update member-color :alpha * member-stake-pos)
                                    (bake-alpha))
          ?controlling-aids     (not-empty (hga-playback/aid->controlling-aids member-aid))
          new-hierarchy-path    (conj hierarchy-path member-aid)
          x                     (aid->x member-aid)
          y                     (if-not hierarchical-view?
                                  (-> member-aid ac/aid->control-depth aid-depth->y)
                                  (-> hierarchy-size
                                      (- (count new-hierarchy-path))
                                      aid-depth->y))]
      [:<>
       [:div.member {:class [(when active? "active")
                             (when (nil? ?controlling-aids) "root-aid")
                             #_(when me? "me")]
                     :style {:left (- x (/ hga-view/avatar-size 2))
                             :top  (- y (/ hga-view/avatar-size 2))}}

        [:div.avatar
         (member-avatar (rgb->css-str member-color)
                        (rgb->css-str adjusted-member-color))
         [:div.member-name
          member-name]]]

       (when ?controlling-aids
         [:<>
          (for [controlling-aid ?controlling-aids]
            (aid-view controlling-aid adjusted-member-color (/ 1 (count ?controlling-aids)) true aid->x hierarchy-size new-hierarchy-path hierarchical-view?))
          [:div.grouping-area]])])))

(defc member-aid-info-view <
  {:key-fn (fn [{:member-aid-info/keys [member-init-key aid]}] (or member-init-key (hash aid)))}
  [{:member-aid-info/keys [aid alias creation-time status stake pos-x color child-depth parent-depth member-aid-infos]} hierarchical-view? total-depth head? headless?]
  [:<>
   [:div.member {:class [(str "status-" (name (l status)))
                         (when (nil? member-aid-infos) "root-aid")]
                 :style {:left (-> pos-x hga-view/idx->x (- (/ hga-view/avatar-size 2)))
                         :top  (if-not hierarchical-view?
                                 (-> parent-depth aid-depth->y)
                                 (-> total-depth (- (cond-> child-depth headless? dec)) aid-depth->y))}}
    (when-not (and head? headless?)
      [:div.avatar
       (let [seed          (ac/creation-time->seed creation-time)
             member-avatar (cond (= alias "A") hga-avatars/female-avatar
                                 (= alias "B") hga-avatars/male-avatar
                                 :else         (ac/parent-depth+seed->avatar parent-depth seed))]
         (member-avatar (rgb->css-str color)
                        (rgb->css-str (bake-alpha (assoc color :alpha (/ stake hg/total-stake))))))
       [:div.member-name
        alias]])]
   (when member-aid-infos
     (for [member-aid-info member-aid-infos]
       (member-aid-info-view member-aid-info hierarchical-view? total-depth false headless?)))])

(defcs topic-path-aids-view < rum/static rum/reactive (rum/local false  ::*hierarchy-opened?)
  [{::keys [*hierarchy-opened?]} topic-path viz-width {:member-aid-info/keys [alias parent-depth] :as member-aid-info}]
  (let [headless? (= alias "???")]
    (when-let* [active-parent-depth (if headless?
                                      (dec parent-depth)
                                      parent-depth)
                members-height      (->> (cond-> parent-depth
                                           headless? dec)
                                         aid-depth->y
                                         (+ hga-view/avatar-size (/ hga-view/avatar-size 2)))

                bottom (if @*hierarchy-opened?
                         "0px"
                         (-> (+ hga-view/avatar-size hga-view/avatar-size)
                             (- members-height)
                             (str "px")))]
      [:div#members {:style {:width  (str viz-width "px")
                             :height members-height
                             :bottom bottom}
                     :class [(when @*hierarchy-opened? "hierarchical")]}
       (when (> active-parent-depth 0)
         [:div.hierarchy-toggler {:style    {:right (+ viz-width
                                                       (+ hga-view/scrollbar-height)
                                                       (- (/ hierarchy-toggle-size 2)))}
                                  :on-click #(swap! *hierarchy-opened? not)}
          (hga-icons/icon :solid :angle-up :color :black)])
       (member-aid-info-view (l member-aid-info) (rum/react *hierarchy-opened?) active-parent-depth true headless?)]))

  #_
  (when-let* [#_#_root-aids-sorted (l (rum/react hga-playback/*root-aids-sorted))  ;; does not preserve removed member-aids
              member-aids          (rum/react hga-playback/*member-aids)]
    (let [root-aids-sorted (ac/aids->root-aids-sorted member-aids)
          aid->x           (partial ac/root-aids-sorted+aid->x root-aids-sorted)
          members-height   (->> member-aids
                                (map ac/aid->control-depth)
                                (apply max)
                                aid-depth->y
                                (+ hga-view/avatar-size))
          bottom           (if @*hierarchy-opened?
                             "0px"
                             (-> (+ hga-view/avatar-size hga-view/avatar-size)
                                 (- members-height)
                                 (str "px")))
          hierarchy-size   (->> member-aids
                                (map ac/aid->control-depth)
                                (apply max)
                                inc)]
      [:div#members {:style {:width  (str viz-width "px")
                             :height members-height
                             :bottom bottom}
                     :class [(when @*hierarchy-opened? "hierarchical")]}
       (when (> hierarchy-size 1)
         [:div.hierarchy-toggler {:style    {:right (+ viz-width
                                                       (+ hga-view/scrollbar-height)
                                                       (- (/ hierarchy-toggle-size 2)))}
                                  :on-click #(swap! *hierarchy-opened? not)}
          (hga-icons/icon :solid :angle-up :color :black)])

       (for [member-aid (sort-by ac/aid->creation-time member-aids)
             :let       [member-color         (gc/rgba (conj (ac/aid->color member-aid) 1))
                         member-stake-pos     (/ 1 (count member-aids)) #_(/ creator-stake hg/total-stake)
                         active?              true #_(not= 0 creator-stake)
                         hierarchy-path       []]]
         (aid-view member-aid member-color member-stake-pos active? aid->x hierarchy-size hierarchy-path @*hierarchy-opened?))])

    #_(let [aid-topic?     (contains? (rum/react as/*my-aid-topics) topic)
          contact-topic? (contains? (rum/react ac/*contact-topics) topic)
          group-topic?   (contains? (rum/react ac/*group-topics) topic)]
      [:div#members {:style {:width (str viz-width "px")}}
       (cond aid-topic?
             (let [aid (rum/react (rum/cursor ac/*my-aid-topic->my-aid topic))]
               (aid-view aid true 1))
             contact-topic?
             (let [contact-aids (rum/react (rum/cursor ac/*contact-topic->contact-aids topic))]
               [:<>
                (aid-view (first contact-aids) true 0.5)
                (aid-view (second contact-aids) true 0.5)])
             group-topic?
             (let []))

       ])))


(def aid-styles
  [[:.aid (assoc styles/card-style
                 :width  (px 20)
                 :height (px 20)
                 :display :flex
                 :align-items :center
                 :justify-content :center
                 :box-shadow styles/shadow0
                 :cursor :pointer
                 :transition "0.4s")
    [:&:hover styles/accent-style]
    [:&.selected styles/accent2-style]]])

(reg-styles! :aid-styles aid-styles)

(defc aid#-view < rum/reactive
  {:key-fn (fn [aid#] aid#)}
  [aid# props]
  (let [aid-name (rum/react (rum/cursor ac/*aid#->aid-name aid#))]
    [:div.aid props
     [:div.aid-name
      aid-name]]))
