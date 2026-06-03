(ns grimmory.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [clojure.string :as str]))

;; =============================================================================
;; Frontend App State
;; =============================================================================
(defonce app-state (r/atom {:active-tab "sandbox"
                            :config {}
                            :scanned-books []
                            :logs []
                            :isbn-input ""
                            :is-scanning false}))

;; =============================================================================
;; HTTP Actions & Effects
;; =============================================================================
(defn fetch-config! []
  (-> (js/fetch "/api/config")
      (.then (fn [resp] (.json resp)))
      (.then (fn [data]
               (swap! app-state assoc :config (js->clj data :keywordize-keys true))))))

(defn fetch-state! []
  (-> (js/fetch "/api/state")
      (.then (fn [resp] (.json resp)))
      (.then (fn [data]
               (let [clj-data (js->clj data :keywordize-keys true)]
                 (swap! app-state assoc :scanned-books (:scanned_books clj-data)))))))

;; =============================================================================
;; Reagent View Components (ClojureScript React wrapper)
;; =============================================================================
(defn header-component []
  [:nav.flex.items-center.justify-between.px-8.py-4.border-b.border-white-5.bg-dark-0D
   [:div.flex.items-center.space-x-4
    [:div.w-10.h-10.bg-gold.rounded.flex.items-center.justify-center
     [:span.text-black.font-bold.text-xl "G"]]
    [:div
     [:h1.text-xl.font-serif.text-white "Grimmory (ClojureScript Edition)"]
     [:p.text-xs.font-mono.text-gold "STATEFUL BOOK CLASSIFICATION DAEMON"]]]
   [:div.flex.space-x-2
    (for [[tab label] [["sandbox" "Sandbox"]
                      ["generator" "Daemon Configuration"]]]
      [:button.px-4.py-2.rounded.text-xs.font-medium
       {:key tab
        :class (if (= (:active-tab @app-state) tab) "bg-[#C4A47C] text-black" "text-gray-400 hover:text-white")
        :on-click #(swap! app-state assoc :active-tab tab)}
       label])]])

(defn content-sandbox []
  [:div.grid.grid-cols-1.md:grid-cols-2.gap-6.mt-6
   ;; Input file scanning column
   [:div.bg-dark-12.p-6.rounded-xl.border.border-white-5
    [:h3.text-lg.font-serif.text-gold.mb-4 "📂 Target Intake Folders Schema"]
    [:p.text-xs.text-gray-400.mb-4 "Add a book file below to process metadata classification rules and verify ISBN lookups."]
    [:div.flex.space-x-2
     [:input.flex-1.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white
      {:type "text"
       :placeholder "e.g., kobzar_shevchenko.pdf"
       :value (:isbn-input @app-state)
       :on-change #(swap! app-state assoc :isbn-input (.. % -target -value))}]
     [:button.bg-gold.text-black.px-4.py-2.rounded.text-xs.font-bold
      {:on-click (fn []
                   (let [new-book {:name (:isbn-input @app-state) :status "pending"}]
                     (swap! app-state update :scanned-books conj new-book)
                     (swap! app-state assoc :isbn-input "")))}
      "Queue Book File"]]]

   ;; Active Registry Grid column
   [:div.bg-dark-12.p-6.rounded-xl.border.border-white-5
    [:h3.text-lg.font-serif.text-white.mb-4 "🗃️ Monitored Folder Index Stream"]
    (if (empty? (:scanned-books @app-state))
      [:p.text-xs.text-gray-500 "Intake stream queue is empty. Ready for scanning..."]
      [:ul.space-y-2
       (for [[idx book] (map-indexed vector (:scanned-books @app-state))]
         [:li.flex.items-center.justify-between.bg-black-30.p-2.rounded.border.border-white-5
          {:key idx}
          [:span.text-xs.font-mono (:name book)]
          [:span.text-[10px].uppercase.px-2.py-0.5.rounded.bg-white-5.text-gold (:status book)]])])]])

(defn main-layout []
  [:div.min-h-screen.bg-black.text-gray-100.font-sans
   [header-component]
   [:main.max-w-7xl.mx-auto.p-6
    (case (:active-tab @app-state)
      "sandbox" [content-sandbox]
      "generator" [:div.p-6.text-gray-400 "Clojure and Babashka Script generation modules fully configured."]
      [content-sandbox])]])

;; =============================================================================
;; ClojureScript Reagent Bootstrapper
;; =============================================================================
(defn ^:export init []
  (fetch-config!)
  (fetch-state!)
  (rdom/render [main-layout] (.getElementById js/document "root")))
