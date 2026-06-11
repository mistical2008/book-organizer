(ns librarian.core
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [clojure.string :as str]))

;; =============================================================================
;; Frontend App State
;; =============================================================================
(defonce app-state (r/atom {:active-tab "sandbox"
                            :config {}
                            :edit-config {}
                            :scanned-books {}
                            :logs []
                            :isbn-input ""
                            :is-scanning false
                            :save-success false
                            :show-dir-picker? false
                            :dir-picker-target nil
                            :dir-picker-curr-path "/"
                            :dir-picker-parent nil
                            :dir-picker-subdirs []
                            :log-search ""
                            :log-type "all"
                            :logs-copied? false
                            :active-db "scanned_books"
                            :db-search ""
                            :selected-record nil}))

;; =============================================================================
;; HTTP Actions & Effects
;; =============================================================================
(defn fetch-config! []
  (-> (js/fetch "/api/config")
      (.then (fn [resp] (.json resp)))
      (.then (fn [data]
               (let [config data
                     input-dirs (get config :inputDirs)
                     dir-vec (cond
                               (vector? input-dirs) input-dirs
                               (js/Array.isArray input-dirs) (vec input-dirs)
                               (string? input-dirs) (vec (map str/trim (str/split input-dirs #",")))
                               :else ["/data/books_to_sort"])
                     normalized-config (assoc config :inputDirs dir-vec)]
                 (swap! app-state assoc :config normalized-config)
                 (swap! app-state assoc :edit-config normalized-config))))))

(defn fetch-state! []
  (-> (js/fetch "/api/state")
      (.then (fn [resp] (.json resp)))
      (.then (fn [data]
               (swap! app-state assoc :raw-state data)
               (let [scanned (or (get data "scanned_books") (get data :scanned_books) [])
                     orgs (or (get data "file_organization") (get data :file_organization) [])
                     ;; Create a lookup map of org-items keyed by filepath
                     orgs-map (into {} (map (fn [o] [(or (get o "filepath") (get o :filepath)) o]) orgs))
                     ;; Reconcile scanned list and org list into map structure
                     books-map (into {}
                                     (map (fn [sb]
                                            (let [path (or (get sb "filepath") (get sb :filepath))
                                                  org (get orgs-map path)
                                                  entry {:status (or (get sb "status") (get sb :status))
                                                         :ocr-status (or (get sb "ocr_status") (get sb :ocr_status))}]
                                              [path (if org
                                                      (assoc entry
                                                             :destination (or (get org "dest_path") (get org :dest_path))
                                                             :meta {:title (or (get org "title") (get org :title))
                                                                    :author (or (get org "author") (get org :author))
                                                                    :year (or (get org "year") (get org :year))
                                                                    :isbn (or (get org "isbn") (get org :isbn))
                                                                    :genre (or (get org "genre") (get org :genre))})
                                                      entry)]))
                                          scanned))]
                 (swap! app-state assoc :scanned-books books-map))))))

(defn fetch-logs! []
  (-> (js/fetch "/api/logs")
      (.then (fn [resp] (.json resp)))
      (.then (fn [data]
               (swap! app-state assoc :logs data)))))

(defn clean-logs! [mode]
  (-> (js/fetch "/api/clean-logs"
                #js {:method "POST"
                     :headers #js {"Content-Type" "application/json"}
                     :body (js/JSON.stringify (clj->js {:mode mode}))})
      (.then (fn [resp] (.json resp)))
      (.then (fn [data]
               (fetch-logs!)))))

(defn clean-state! [mode]
  (-> (js/fetch "/api/clean-state"
                #js {:method "POST"
                     :headers #js {"Content-Type" "application/json"}
                     :body (js/JSON.stringify (clj->js {:mode mode}))})
      (.then (fn [resp] (.json resp)))
      (.then (fn [data]
               (fetch-state!)))))

(defn load-dirs! [path]
  (let [enc-path (js/encodeURIComponent (or path "/"))]
    (-> (js/fetch (str "/api/list-dirs?path=" enc-path))
        (.then (fn [resp] (.json resp)))
        (.then (fn [data]
                 (when (= (get data "status") "ok")
                   (swap! app-state assoc
                          :dir-picker-curr-path (get data "path")
                          :dir-picker-parent (get data "parent")
                          :dir-picker-subdirs (vec (get data "dirs")))))))))

(defn open-dir-picker! [target initial-path]
  (swap! app-state assoc
         :show-dir-picker? true
         :dir-picker-target target)
  (load-dirs! (or initial-path "/")))

(defn close-dir-picker! []
  (swap! app-state assoc
         :show-dir-picker? false
         :dir-picker-target nil))

(defn select-dir-picker-dir! [path]
  (let [target (:dir-picker-target @app-state)]
    (when target
      (swap! app-state assoc-in target path)
      (close-dir-picker!))))

;; =============================================================================
;; Reagent View Components
;; =============================================================================
(def autocomplete-samples
  [{:path "/data/books_to_sort/kaydasheva_simya_rotated.djvu"
    :filename "kaydasheva_simya_rotated.djvu"
    :type "DJVU"
    :desc "Fiction | Ukrainian classic (no ISBN, requires Gemini OCR parsing)"}
   {:path "/data/books_to_sort/kobzar_shevchenko_2012_edition.pdf"
    :filename "kobzar_shevchenko_2012_edition.pdf"
    :type "PDF"
    :desc "Poetry | Taras Shevchenko anthology (contains ISBN 978-966-2449-01-3 for API lookup)"}
   {:path "/data/books_to_sort/kotlyarevsky_eneida_scanned_v3.pdf"
    :filename "kotlyarevsky_eneida_scanned_v3.pdf"
    :type "PDF"
    :desc "Poetry | Scanned lyric poem (retains OCR noise & ISBN 978-966-03-8120-9)"}
   {:path "/data/books_to_sort/sicp_mit_press.epub"
    :filename "sicp_mit_press.epub"
    :type "EPUB"
    :desc "Computer Science | MIT Press classic textbook (displays multiple ISBN candidates)"}])

(defn sidebar-component []
  (let [books (:scanned-books @app-state)
        logs (:logs @app-state)
        active-tab (:active-tab @app-state)]
    [:aside.w-72.bg-sidebar-bg.border-r.border-white-5.p-6.flex.flex-col.h-screen.fixed.top-0.bottom-0.left-0.overflow-y-auto
     [:div.flex.items-center.space-x-4.mb-8.pb-6.border-b.border-white-5
      [:div.w-10.h-10.bg-brand.rounded.flex.items-center.justify-center.shrink-0
       [:span.text-black.font-mono.font-bold.text-xl "L"]]
      [:div.min-w-0
       [:h1.text-md.font-serif.text-white.font-semibold.truncate "Librarian Organiser"]
       [:p {:class "text-[10px] font-mono text-brand uppercase tracking-wider truncate"} "Classification Daemon"]]]
     
     ;; Sidebar Navigation Layout Links
     [:div.flex-1.space-y-1.5
      (for [[tab label-icon] [["sandbox" "⚡ Sandbox Hub"]
                               ["generator" "⚙️ Daemon Config"]
                              ["databases" "🗄️ Database View"]
                               ["logs" "📋 Production Logs"]]]
        [:button.w-full.flex.items-center.space-x-3.px-4.py-3.rounded-lg.text-xs.font-semibold.text-left.transition-all
         {:key tab
          :class (if (= active-tab tab)
                   "bg-brand text-black font-semibold shadow-md border-transparent"
                   "text-gray-400 hover:text-white hover:bg-brand-soft hover:border-brand-muted/10 border border-transparent")
          :on-click #(swap! app-state assoc :active-tab tab)}
         [:span label-icon]])
      [:div.pt-4.border-t.border-white-5.mt-4.space-y-2
       (let [is-scanning (:is-scanning @app-state)]
         [:button.w-full.flex.items-center.justify-center.space-x-2.px-4.py-3.rounded-lg.text-xs.font-bold.transition-all
          {:disabled is-scanning
           :class (if is-scanning
                    "bg-gray-800 text-gray-500 cursor-not-allowed border border-white-5"
                    "bg-emerald-600 hover:bg-emerald-500 text-white font-semibold shadow-md cursor-pointer border border-transparent")
           :on-click (fn []
                       (swap! app-state assoc :is-scanning true)
                       (-> (js/fetch "/api/scan" #js {:method "POST"})
                           (.then (fn [resp] (.json resp)))
                           (.then (fn [res]
                                    (swap! app-state assoc :is-scanning false)
                                    (fetch-state!)
                                    (fetch-logs!)))
                           (.catch (fn [err]
                                     (swap! app-state assoc :is-scanning false)
                                     (js/console.error err)))))}
          [:span (if is-scanning "⏳ Scanning..." "🔍 Start Manual Scan") ] ] ) ] ]
     
     ;; Sidebar system stats (Footer)
     [:div.mt-auto.pt-6.border-t.border-white-5.space-y-3
      [:div.flex.items-center.justify-between {:class "text-[11px]"}
       [:span.text-gray-500 "Database Queue:"]
       [:span.text-brand.font-mono.font-semibold (str (count books) (if (= (count books) 1) " publication" " publications"))]]
      [:div.flex.items-center.justify-between {:class "text-[11px]"}
       [:span.text-gray-500 "Telemetry Stack:"]
       [:span.text-gray-400.font-mono (str (count logs) " entries")]]
      [:div.flex.items-center.justify-between {:class "text-[11px]"}
       [:span.text-gray-500 "Daemon status:"]
       [:div.flex.items-center.space-x-1.5
        [:span.w-2.h-2.rounded-full.bg-emerald-500.animate-pulse]
        [:span.text-emerald-400.font-mono.font-semibold "ONLINE"]]]]]))

(defn ocr-monitor-component []
  (let [books (let [b (:scanned-books @app-state)]
                (if (map? b) b (into {} b)))
        is-scanning (:is-scanning @app-state)
        filter-ocr (or (:filter-ocr @app-state) "all")
        
        ;; Compute Statistics
        total-scanned (count books)
        success-ocr (count (filter #(= (:ocr-status (second %)) "success") books))
        failed-ocr (count (filter #(= (:ocr-status (second %)) "failed") books))
        native-ocr (count (filter #(= (:ocr-status (second %)) "not_required") books))
        pending-scans (count (filter #(or (= (:status (second %)) "pending") (= (:status (second %)) "queued")) books))
        
        processed-scans (- total-scanned pending-scans)
        progress-percent (if (pos? total-scanned) (int (* 100 (/ processed-scans total-scanned))) 0)]
    [:div.bg-dark-12.p-6.rounded-xl.border.border-white-5.space-y-6
     [:div.flex.items-center.justify-between
      [:div
       [:h3.text-md.font-serif.text-brand "📟 Scanning & OCR Progress Monitor"]
       [:p {:class "text-[11px] text-gray-400 leading-snug"}
        (if is-scanning 
          "⏳ Core engine active. Rendering local pages & running Tesseract OCR..."
          "✓ Intake check complete. All pipelines idle.")]]
      [:div.flex.items-center.space-x-2
       [:span.text-xs.font-mono.text-gray-400 (str processed-scans " / " total-scanned " Completed")]
       [:span {:class (str "w-2 h-2 rounded-full " (if is-scanning "bg-emerald-400 animate-pulse" "bg-gray-600"))}]]]
     
     ;; Progress Bar
     [:div.w-full.bg-black.rounded-full.h-2.5.overflow-hidden.border.border-white-5
      [:div.bg-brand.h-full.transition-all.duration-500
       {:style {:width (str progress-percent "%")}}]]
     
     ;; Stats Widget Selector Row
     [:div.grid.grid-cols-2.gap-3
      {:class "sm:grid-cols-5"}
      ;; Selector 1: All Files
      [:button.p-4.rounded-xl.border.text-left.transition-all.flex.flex-col.justify-between.space-y-2.cursor-pointer
       {:class (if (= filter-ocr "all")
                 "bg-brand-soft border-brand text-white shadow-lg"
                 "bg-black-30 border-white-5 text-gray-400 hover:text-white hover:border-white-15")
        :on-click #(swap! app-state assoc :filter-ocr "all")}
       [:div.flex.items-center.justify-between
        [:span {:class "text-[10px] font-mono uppercase tracking-wider"} "Total Files"]
        [:span.text-xs "🗂️"]]
       [:span.text-2xl.font-mono.font-bold total-scanned]]
      
      ;; Selector 2: OCR Succeeded
      [:button.p-4.rounded-xl.border.text-left.transition-all.flex.flex-col.justify-between.space-y-2.cursor-pointer
       {:class (if (= filter-ocr "success")
                 "bg-emerald-950/40 border-emerald-500 text-emerald-400 shadow-lg"
                 "bg-black-30 border-white-5 text-emerald-400/75 hover:text-emerald-300 hover:border-emerald-500/50")
        :on-click #(swap! app-state assoc :filter-ocr "success")}
       [:div.flex.items-center.justify-between
        [:span {:class "text-[10px] font-mono uppercase tracking-wider"} "OCR Success"]
        [:span.text-xs "✅"]]
       [:span.text-2xl.font-mono.font-bold success-ocr]]

      ;; Selector 3: OCR Failed
      [:button.p-4.rounded-xl.border.text-left.transition-all.flex.flex-col.justify-between.space-y-2.cursor-pointer
       {:class (if (= filter-ocr "failed")
                 "bg-rose-950/40 border-rose-500 text-rose-400 shadow-lg"
                 "bg-black-30 border-white-5 text-rose-400/75 hover:text-rose-300 hover:border-rose-500/50")
        :on-click #(swap! app-state assoc :filter-ocr "failed")}
       [:div.flex.items-center.justify-between
        [:span {:class "text-[10px] font-mono uppercase tracking-wider"} "OCR Failed"]
        [:span.text-xs "❌"]]
       [:span.text-2xl.font-mono.font-bold failed-ocr]]

      ;; Selector 4: Native Digital (No OCR Required)
      [:button.p-4.rounded-xl.border.text-left.transition-all.flex.flex-col.justify-between.space-y-2.cursor-pointer
       {:class (if (= filter-ocr "not_required")
                 "bg-blue-950/40 border-blue-500 text-blue-400 shadow-lg"
                 "bg-black-30 border-white-5 text-blue-400/75 hover:text-blue-300 hover:border-blue-500/50")
        :on-click #(swap! app-state assoc :filter-ocr "not_required")}
       [:div.flex.items-center.justify-between
        [:span {:class "text-[10px] font-mono uppercase tracking-wider"} "Native Text"]
        [:span.text-xs "⚡"]]
       [:span.text-2xl.font-mono.font-bold native-ocr]]

      ;; Selector 5: Queued & Pending
      [:button.p-4.rounded-xl.border.text-left.transition-all.flex.flex-col.justify-between.space-y-2.cursor-pointer
       {:class (if (= filter-ocr "queued")
                 "bg-amber-950/45 border-amber-500 text-amber-500 shadow-lg"
                 "bg-black-30 border-white-5 text-amber-500/75 hover:text-amber-400 hover:border-amber-500/50")
        :on-click #(swap! app-state assoc :filter-ocr "queued")}
       [:div.flex.items-center.justify-between
        [:span {:class "text-[10px] font-mono uppercase tracking-wider"} "Queued"]
        [:span.text-xs "⏳"]]
       [:span.text-2xl.font-mono.font-bold pending-scans]]]

     ;; Active Filter Reset Row
     (when-not (= filter-ocr "all")
       [:div.flex.items-center.justify-between.bg-black-30.p-3.rounded-lg.border.border-white-5.text-xs
        [:span.text-gray-400 (str "Showing: " 
                                  (case filter-ocr
                                    "success" "Successful OCR scans only"
                                    "failed" "Failed OCR scans only (highlighted)"
                                    "not_required" "Digital Native records (No OCR required)"
                                    "queued" "Pending / queued simulation records"
                                    "All files"))]
        [:button.text-brand.font-mono.font-bold.hover:underline.cursor-pointer {:on-click #(swap! app-state assoc :filter-ocr "all")} "✕ Clear Filter"]])]))

(defn content-sandbox []
  (let [books (let [b (:scanned-books @app-state)]
                (if (map? b) b (into {} b)))
        logs (:logs @app-state)
        is-scanning (:is-scanning @app-state)
        filter-ocr (or (:filter-ocr @app-state) "all")
        
        ;; Compute Statistics
        total-scanned (count books)
        success-ocr (count (filter #(= (:ocr-status (second %)) "success") books))
        failed-ocr (count (filter #(= (:ocr-status (second %)) "failed") books))
        native-ocr (count (filter #(= (:ocr-status (second %)) "not_required") books))
        pending-scans (count (filter #(or (= (:status (second %)) "pending") (= (:status (second %)) "queued")) books))
        
        processed-scans (- total-scanned pending-scans)
        progress-percent (if (pos? total-scanned) (int (* 100 (/ processed-scans total-scanned))) 0)
        
        filtered-books (filter (fn [[path entry]]
                                 (cond
                                   (= filter-ocr "all") true
                                   (= filter-ocr "success") (= (:ocr-status entry) "success")
                                   (= filter-ocr "failed") (= (:ocr-status entry) "failed")
                                   (= filter-ocr "not_required") (= (:ocr-status entry) "not_required")
                                   (= filter-ocr "queued") (or (= (:status entry) "pending") (= (:status entry) "queued"))
                                   :else true))
                               books)]
    [:div.space-y-6
     ;; Header/Overview block
     [:div.border-b.border-white-5.pb-4.flex.flex-col.gap-4
      {:class "sm:flex-row sm:items-center sm:justify-between"}
      [:div
       [:h2.text-2xl.font-serif.text-white.font-semibold "⚡ Interactive Sandbox Hub"]
       [:p.text-xs.text-gray-400 "Queue file paths manually to simulate cataloguing runs, or run active polling daemon checks."]]
      [:button.px-3.py-2.border.rounded-lg.transition-all
       {:class "border-rose-500/25 bg-rose-950/20 hover:bg-rose-900/40 text-rose-400 text-xs font-mono"
        :on-click (fn []
                    (clean-state! "all")
                    (swap! app-state assoc :filter-ocr "all"))}
       "🗑️ Clear Database"]]

     ;; Shared Scanning Progress Dashboard Component
     [ocr-monitor-component]

     [:div.grid.grid-cols-1.gap-6
      {:class "lg:grid-cols-3"}
      ;; Main column (2/3 width) - Monitored Folder Index Stream
      [:div.space-y-6
       {:class "lg:col-span-2"}
       [:div.bg-dark-12.p-6.rounded-xl.border.border-white-5.flex.flex-col
        [:div.flex.items-center.justify-between.mb-4
         [:h3.text-lg.font-serif.text-white "🗃️ Monitored Folder Index Stream"]
         [:span.text-xs.font-mono.text-gray-400 (str "Showing " (count filtered-books) " records")]]
        (if (empty? filtered-books)
          [:div.p-8.text-center.border.border-dashed.border-white-10.rounded-lg
           [:p.text-xs.text-gray-500 "No publication files match the active filter criteria."]]
          [:div.space-y-3.overflow-y-auto.pr-1 {:class "max-h-[600px]"}
           (for [[path entry] filtered-books]
             (let [status (:status entry)
                   status-class (case status
                                  "completed" "bg-emerald-950/80 text-emerald-400 border border-emerald-900"
                                  "pending" "bg-amber-950/80 text-amber-400 border border-amber-900"
                                  "queued" "bg-blue-950/80 text-blue-400 border border-blue-900"
                                  "low_confidence" "bg-rose-950/80 text-rose-400 border border-rose-900"
                                  "bg-white-5 text-brand border border-white-10")
                   ocr-status (:ocr-status entry)
                   ocr-badge (case ocr-status
                               "success" [:span {:class "text-[9px] uppercase px-1.5 py-0.5 rounded font-mono font-bold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20"} "✅ OCR Succeeded"]
                               "failed" [:span {:class "text-[9px] uppercase px-1.5 py-0.5 rounded font-mono font-bold bg-rose-500/10 text-rose-400 border border-rose-500/20 animate-pulse"} "⚠️ OCR Failed"]
                               "not_required" [:span {:class "text-[9px] uppercase px-1.5 py-0.5 rounded font-mono font-bold bg-blue-500/10 text-blue-400 border border-blue-500/20"} "⚡ Native Text"]
                               nil)]
               ^{:key path}
               [:div.p-4.rounded-lg.border.bg-card-bg.space-y-3
                {:class (if (= ocr-status "failed") "border-rose-500/30 shadow-md bg-rose-950/5" "border-white-5")}
                [:div.flex.items-start.justify-between.gap-4
                 [:div.flex-1.min-w-0
                  [:p.text-xs.font-mono.text-gray-400.truncate path]
                  (let [meta (:meta entry)]
                    (if meta
                      [:div.mt-1.space-y-1
                       [:p.text-sm.font-serif.text-brand.font-medium (:title meta)]
                       [:p.text-xs.text-gray-300 (str "by " (:author meta) " (" (or (:year meta) "N/A") ")")]
                       [:p.text-xs.text-gray-400.font-mono (str "ISBN: " (or (:isbn meta) "None") " | Genre: " (or (:genre meta) "N/A"))]]
                      [:p.text-xs.text-gray-500 "Pending classification scan..."]))]
                 [:div.flex.flex-col.items-end.gap-2.shrink-0
                  [:span.uppercase.px-2.py-1.rounded.font-mono.font-semibold.h-fit
                   {:class (str "text-[10px] " status-class)}
                   (or status "unknown")]
                  (when ocr-badge ocr-badge)]]
                (when (:destination entry)
                  [:p {:class "text-xs font-mono text-emerald-400 p-2 rounded bg-emerald-950/30 border border-emerald-900/40"}
                   "🚚 Destination: " (:destination entry)])]))])]]
      
      ;; Side column (1/3 width) - Sandbox Controls & Simulated File Intake
      [:div.space-y-6
       [:div.bg-dark-12.p-6.rounded-xl.border.border-white-5.space-y-4
        [:h3.text-md.font-serif.text-brand "⚡ Pipeline Controllers"]
        [:p {:class "text-[11px] text-gray-400 leading-relaxed"} "Manage simulation runs, poll filesystem folders, and manually queue intake records."]
        
        [:div.space-y-2
         [:button.w-full.px-4.py-2.5.rounded-lg.text-xs.font-bold.transition-colors
          {:class (if is-scanning "bg-gray-700 text-gray-400 cursor-not-allowed" "bg-brand text-black hover:bg-brand-hover")
           :disabled is-scanning
           :on-click (fn []
                       (swap! app-state assoc :is-scanning true)
                       (-> (js/fetch "/api/scan" #js {:method "POST"})
                           (.then (fn [resp] (.json resp)))
                           (.then (fn [res]
                                    (swap! app-state assoc :is-scanning false)
                                    (fetch-state!)
                                    (fetch-logs!)))
                           (.catch (fn [err]
                                     (swap! app-state assoc :is-scanning false)
                                     (js/console.error err)))))}
          (if is-scanning "Running Classification Engine..." "Run Polling Check")]
         [:button.w-full.border.border-white-10.text-gray-300.px-4.py-2.5.rounded-lg.text-xs.font-medium.hover:bg-brand-soft.transition-colors
          {:on-click #(do (fetch-state!) (fetch-logs!) (fetch-config!))}
          "Sync State"]]
          
        [:div.border-t.border-white-5.pt-4.space-y-3
         [:span.text-xs.font-serif.text-white.font-semibold "Simulated File Intake Queue"]
         [:p {:class "text-[11px] text-gray-400"} "Type or select a sample book path below to view details and queue into raw intake stream."]
         [:div.space-y-2
          [:div.relative.w-full
           [:input.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none.font-mono
            {:type "text"
             :placeholder "e.g. /data/books_to_sort/book_ocr_sample.pdf"
             :value (:isbn-input @app-state)
             :onFocus #(swap! app-state assoc :show-autocomplete? true)
             :onBlur #(js/setTimeout (fn [] (swap! app-state assoc :show-autocomplete? false)) 250)
             :onChange #(swap! app-state assoc :isbn-input (.. % -target -value))}]
           
           ;; Autocomplete Suggestions Dropdown with Parts Descriptions
           (when (and (:show-autocomplete? @app-state) 
                      (or (not-empty (:isbn-input @app-state)) true))
             (let [input-val (:isbn-input @app-state)
                   filtered-samples (filter (fn [sample]
                                              (or (str/blank? input-val)
                                                  (str/includes? (str/lower-case (:path sample)) (str/lower-case input-val))
                                                  (str/includes? (str/lower-case (:desc sample)) (str/lower-case input-val))))
                                            autocomplete-samples)]
               (when (seq filtered-samples)
                 [:div.absolute.z-50.left-0.right-0.mt-1.bg-card-bg.border.border-white-10.rounded-lg.shadow-2xl.max-h-60.overflow-y-auto.divide-y.divide-white-5
                  (for [sample filtered-samples]
                    ^{:key (:path sample)}
                    [:button.w-full.text-left.p-2.5.hover:bg-brand-soft.transition-colors.flex.flex-col.space-y-1
                     {:type "button"
                      :on-click (fn []
                                  (swap! app-state assoc :isbn-input (:path sample))
                                  (swap! app-state assoc :show-autocomplete? false))}
                     [:div.flex.items-center.justify-between.gap-2
                      [:span.text-xs.font-mono.text-brand.font-semibold.truncate (:filename sample)]
                      [:span {:class "text-[9px] uppercase px-1.5 py-0.5 rounded bg-black border border-white-10 text-gray-400 font-mono shrink-0"} (:type sample)]]
                     [:p {:class "text-[10px] text-gray-400 leading-snug"} (:desc sample)]])])))]
                     
          [:button.w-full.bg-brand.text-black.p-2.rounded.text-xs.font-bold.hover:bg-brand-hover.transition-colors
           {:on-click (fn []
                        (let [inp (str/trim (:isbn-input @app-state))]
                          (when-not (str/blank? inp)
                            (swap! app-state assoc-in [:scanned-books inp] {:status "queued" :meta {:title "Simulated File" :author "Loaded. Ready for sync cycle."}})
                            (swap! app-state assoc :isbn-input ""))))}
           "Queue Book File"]]]]]]]))

(def template-placeholders
  [{:token "{Author}" :label "Author" :desc "Author name (e.g. Taras Shevchenko)"}
   {:token "{Title}"  :label "Title"  :desc "Book title (e.g. Kobzar)"}
   {:token "{Year}"   :label "Year"   :desc "Publication/release year (e.g. 1840)"}
   {:token "{Genre}"  :label "Genre"  :desc "Detected genre category (e.g. Poetry)"}
   {:token "{ISBN}"   :label "ISBN"   :desc "Unique ISBN identifier (e.g. 9789662449013)"}])

(defn insert-template-text! [token]
  (let [input (js/document.getElementById "destination-template-input")]
    (when input
      (let [start (.-selectionStart input)
            end (.-selectionEnd input)
            val (or (.-value input) "")
            before (.substring val 0 start)
            after (.substring val end (.-length val))
            new-val (str before token after)
            new-cursor-pos (+ start (count token))]
        (swap! app-state assoc-in [:edit-config :destinationTemplate] new-val)
        (js/setTimeout
          #(when-let [inp (js/document.getElementById "destination-template-input")]
             (set! (.-selectionStart inp) new-cursor-pos)
             (set! (.-selectionEnd inp) new-cursor-pos)
             (.focus inp))
          1)))))

(defn insert-autocomplete-token! [token]
  (let [input (js/document.getElementById "destination-template-input")]
    (when input
      (let [cursor (.-selectionStart input)
            val (or (.-value input) "")
            before-cursor (.substring val 0 cursor)
            last-brace (.lastIndexOf before-cursor "{")]
        (if (>= last-brace 0)
          (let [before (.substring val 0 last-brace)
                after (.substring val cursor (.-length val))
                new-val (str before token after)
                new-cursor-pos (+ last-brace (count token))]
            (swap! app-state assoc-in [:edit-config :destinationTemplate] new-val)
            (swap! app-state assoc :show-template-autocomplete? false)
            (js/setTimeout
              #(when-let [inp (js/document.getElementById "destination-template-input")]
                 (set! (.-selectionStart inp) new-cursor-pos)
                 (set! (.-selectionEnd inp) new-cursor-pos)
                 (.focus inp))
              1))
          (insert-template-text! token))))))

(defn check-template-autocomplete! []
  (let [input (js/document.getElementById "destination-template-input")]
    (if input
      (let [cursor (.-selectionStart input)
            val (or (.-value input) "")
            before-cursor (.substring val 0 cursor)
            last-brace (.lastIndexOf before-cursor "{")
            last-close-brace (.lastIndexOf before-cursor "}")]
        (if (and (>= last-brace 0)
                 (> last-brace last-close-brace))
          (let [search-term (str/lower-case (.substring before-cursor (inc last-brace)))
                curr-show (:show-template-autocomplete? @app-state)
                curr-filter (:template-autocomplete-filter @app-state)]
            (when (or (not curr-show) (not= curr-filter search-term))
              (swap! app-state assoc
                     :show-template-autocomplete? true
                     :template-autocomplete-filter search-term)))
          (when (:show-template-autocomplete? @app-state)
            (swap! app-state assoc :show-template-autocomplete? false))))
      (when (:show-template-autocomplete? @app-state)
        (swap! app-state assoc :show-template-autocomplete? false)))))

(defn update-input-dir! [idx val]
  (let [dirs (get-in @app-state [:edit-config :inputDirs])
        dir-vec (if (vector? dirs) dirs (if (string? dirs) [dirs] []))
        updated (assoc dir-vec idx val)]
    (swap! app-state assoc-in [:edit-config :inputDirs] updated)))

(defn remove-input-dir! [idx]
  (let [dirs (get-in @app-state [:edit-config :inputDirs])
        dir-vec (if (vector? dirs) dirs (if (string? dirs) [dirs] []))]
    (when (> (count dir-vec) 1)
      (let [updated (vec (concat (subvec dir-vec 0 idx) (subvec dir-vec (inc idx))))]
        (swap! app-state assoc-in [:edit-config :inputDirs] updated)))))

(defn add-input-dir! []
  (let [dirs (get-in @app-state [:edit-config :inputDirs])
        dir-vec (if (vector? dirs) dirs (if (string? dirs) [dirs] []))
        updated (conj dir-vec "")]
    (swap! app-state assoc-in [:edit-config :inputDirs] updated)))

(defn content-generator []
  (let [edit-config (:edit-config @app-state)
        save-status (:save-success @app-state)]
    [:div.space-y-6
     [:div.border-b.border-white-5.pb-4
      [:h2.text-2xl.font-serif.text-white.font-semibold "⚙️ Librarian Daemon Parameters Setup"]
      [:p.text-xs.text-gray-400 "Dynamically adjust scanning conditions, directories, confidence thresholds, and Gemini fallback targets inside your full-stack background daemon."]]

     [:div.bg-dark-12.p-6.rounded-xl.border.border-white-5.max-w-4xl.space-y-6
      ;; Status Ribbon matching previous design mockup layout
      [:div.bg-black-30.border.border-white-5.rounded-lg.p-3.flex.flex-wrap.items-center.gap-6.justify-between.text-xs.font-mono.text-gray-500
       [:div.flex.items-center.space-x-2
        [:span "BACKGROUND DAEMON SCHEDULER:"]
        [:span {:class (str "px-2 py-0.5 rounded border text-[10px] font-bold "
                            (if (get edit-config :daemonEnabled)
                              "bg-emerald-500/10 text-emerald-400 border-emerald-500/20"
                              "bg-amber-500/10 text-amber-500 border-amber-500/20"))}
         (if (get edit-config :daemonEnabled) "ACTIVE (AUTOMATED BATCH SYNC)" "IDLE (MANUAL TRIGGER ONLY)")]
        (when (get edit-config :daemonEnabled)
          [:span.text-gray-400 (str " [" (get edit-config :runInterval "hourly") "]")])]
       [:div.h-4.w-px.bg-white-10.hidden.md:block]
       [:div
        [:span "PROCESSING PIPELINE: "]
        [:span.text-gray-300.font-semibold.uppercase (str "Batch Limit (" (get edit-config :batchSize 5) " files)")]]]

      (when save-status
        [:div.p-3.rounded.bg-emerald-950.border.border-emerald-800.text-emerald-400.text-xs.flex.items-center.justify-between
         [:span "✓ Configuration parameters written successfully to data/config.json!"]
         [:button.text-emerald-300.font-bold {:on-click #(swap! app-state assoc :save-success false)} "Dismiss"]])

      [:div.grid.grid-cols-1.md:grid-cols-2.gap-8
       ;; Left Column (File System & Path Templates Settings)
       [:div.space-y-6
        [:div.space-y-2
         [:div.flex.items-center.justify-between
          [:label.block.text-xs.font-mono.text-gray-400.font-bold.uppercase.tracking-wider "Daemon Input Directories (Multiple Source Paths)"]
          [:button {:type "button"
                    :class "text-[11px] font-mono text-brand hover:text-brand-hover transition-colors"
                    :onClick #(add-input-dir!)}
           "+ ADD SOURCE PATH"]]
         [:div.space-y-2
          (let [dirs (get edit-config :inputDirs)
                dir-list (if (vector? dirs) dirs (if (string? dirs) [dirs] ["/data/books_to_sort"]))]
            (for [[idx dir] (map-indexed vector dir-list)]
              ^{:key idx}
              [:div.flex.items-center.space-x-2
               [:span.text-xs.font-mono.text-gray-500 (str (inc idx) ".")]
               [:div.flex.flex-1.bg-black.border.border-white-10.rounded.overflow-hidden.focus-within:border-brand-muted
                [:input.flex-1.bg-transparent.p-2.text-xs.text-white.focus:outline-none.font-mono
                 {:type "text"
                  :value dir
                  :onChange (fn [e] (update-input-dir! idx (.. e -target -value)))}]
                [:button.px-3.py-2.bg-white-5.border-l.border-white-15.text-gray-400.hover:text-amber-500.transition-colors
                 {:type "button"
                  :title "Browse folder"
                  :onClick (fn [] (open-dir-picker! [:edit-config :inputDirs idx] dir))}
                 "📁"]
                (when (> (count dir-list) 1)
                  [:button.px-3.py-2.bg-white-5.border-l.border-white-15.text-gray-400.hover:text-rose-500.transition-colors
                   {:type "button"
                    :title "Remove path"
                    :onClick (fn [] (remove-input-dir! idx))}
                   "✕"])]]))]]

        [:div.space-y-2
         [:div.flex.items-center.justify-between
          [:label.block.text-xs.font-mono.text-gray-400.font-bold.uppercase.tracking-wider "Destination Path Custom Template"]
          [:button {:type "button"
                    :class "text-[11px] font-mono text-gray-500 hover:text-gray-300 transition-colors"
                    :onClick #(swap! app-state assoc-in [:edit-config :destinationTemplate] "{Genre}/{Author} - {Title} ({Year})")}
           "RESET DEFAULT"]]
         [:div.relative
          [:input.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none.font-mono
           {:id "destination-template-input"
            :type "text"
            :placeholder "e.g. {Genre}/{Author} - {Title}"
            :value (get edit-config :destinationTemplate)
            :onFocus (fn [] (check-template-autocomplete!))
            :onChange (fn [e]
                         (let [new-val (.. e -target -value)]
                           (swap! app-state assoc-in [:edit-config :destinationTemplate] new-val)
                           (check-template-autocomplete!)))
            :onKeyUp (fn [] (check-template-autocomplete!))
            :onBlur (fn [] (js/setTimeout (fn [] (swap! app-state assoc :show-template-autocomplete? false)) 250))}]
          
          ;; Autocomplete suggestions dropdown
          (when (and (:show-template-autocomplete? @app-state)
                     (some? (:template-autocomplete-filter @app-state)))
            (let [filter-str (:template-autocomplete-filter @app-state)
                  filtered-tokens (filter (fn [tp]
                                            (or (str/blank? filter-str)
                                                (str/includes? (str/lower-case (:token tp)) filter-str)
                                                (str/includes? (str/lower-case (:label tp)) filter-str)))
                                          template-placeholders)]
              (when (seq filtered-tokens)
                [:div.absolute.z-50.left-0.right-0.mt-1.bg-card-bg.border.border-white-10.rounded-lg.shadow-2xl.max-h-48.overflow-y-auto.divide-y.divide-white-5
                 (for [tp filtered-tokens]
                   ^{:key (:token tp)}
                   [:button.w-full.text-left.px-3.py-2.hover:bg-brand-soft.transition-colors.flex.justify-between.items-center
                    {:type "button"
                     :onClick (fn [] (insert-autocomplete-token! (:token tp)))}
                    [:span.text-xs.font-mono.text-brand.font-bold (:token tp)]
                    [:span {:class "text-[10px] text-gray-400"} (:desc tp)]])])))]
          


         
         ;; Clickable mini tag badges directly below the custom template input field
         [:div.flex.flex-wrap.gap-1.5.pt-1
          (for [tp template-placeholders]
            ^{:key (:token tp)}
            [:button {:type "button"
                      :class "text-[10px] font-mono text-brand border border-brand-muted hover:border-brand bg-black-30 hover:bg-brand-soft/20 transition-all px-2.5 py-0.5 rounded cursor-pointer"
                      :onClick (fn [] (insert-template-text! (:token tp)))}
             (:token tp)])]
         
         ;; Restored Interactive descriptive panel for template placeholders
         [:div.space-y-3.pt-3.border-t.border-white-5
          [:p {:class "text-[11px] text-gray-400 leading-relaxed"}
           "Define subdirectories dynamically. Use slashes to create nested folders automatically at destination (e.g., "
           [:code.text-gray-300.font-mono "{Genre}/{Author} - {Title}"] ")." ]]]

        [:div.space-y-2
         [:label.block.text-xs.font-mono.text-gray-400.font-bold.uppercase.tracking-wider "Gemini API Key (API КЛЮЧ)"]
         [:input.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-brand.focus:border-brand-muted.focus:outline-none.font-mono
          {:type "password"
           :placeholder "••••••••••••••••••••••••••••••••••••"
           :value (get edit-config :geminiApiKey "")
           :onChange #(swap! app-state assoc-in [:edit-config :geminiApiKey] (.. % -target -value))}]]

        [:div.space-y-2
         [:label.block.text-xs.font-mono.text-gray-400.font-bold.uppercase.tracking-wider "Google Books API Key (Optional)"]
         [:input.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none.font-mono
          {:type "text"
           :placeholder "Enter Google Books API Key"
           :value (get edit-config :googleBooksApiKey "")
           :onChange #(swap! app-state assoc-in [:edit-config :googleBooksApiKey] (.. % -target -value))}]]]

       ;; Right Column (Destination settings & parameters)
       [:div.space-y-6
        [:div.space-y-2
         [:label.block.text-xs.font-mono.text-gray-400.font-bold.uppercase.tracking-wider "Output Directory"]
         [:div.flex.bg-black.border.border-white-10.rounded.overflow-hidden.focus-within:border-brand-muted
          [:input.flex-1.bg-transparent.p-2.text-xs.text-white.focus:outline-none.font-mono
           {:type "text"
            :value (get edit-config :outputDir)
            :onChange #(swap! app-state assoc-in [:edit-config :outputDir] (.. % -target -value))}]
          [:button.px-3.py-2.bg-white-5.border-l.border-white-15.text-gray-400.hover:text-brand.transition-colors
           {:type "button"
            :title "Browse folder"
            :onClick #(open-dir-picker! [:edit-config :outputDir] (get edit-config :outputDir))}
           "📁"]]]

        [:div.space-y-2
         [:label.block.text-xs.font-mono.text-gray-400.font-bold.uppercase.tracking-wider "Gemini Fallback Model"]
         [:select.w-full.bg-black.border.border-white-10.p-2.5.rounded.text-xs.text-gray-300.focus:border-brand-muted.focus:outline-none.font-mono
          {:value (get edit-config :geminiModel "gemini-3.5-flash")
           :onChange #(swap! app-state assoc-in [:edit-config :geminiModel] (.. % -target -value))}
          [:option {:value "gemini-3.5-flash"} "gemini-3.5-flash (Recommended)"]
          [:option {:value "gemini-1.5-pro"} "gemini-1.5-pro (Aesthetic deep parsing)"]]]

        [:div.space-y-2
         [:label.block.text-xs.font-mono.text-gray-400.font-bold.uppercase.tracking-wider "Confidence Limit Threshold (%)"]
         [:input.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none.font-mono
          {:type "number"
           :min "0"
           :max "100"
           :value (str (get edit-config :confidenceThreshold))
           :onChange #(swap! app-state assoc-in [:edit-config :confidenceThreshold] (js/parseInt (.. % -target -value) 10))}]]

        [:div.space-y-2
         [:label.block.text-xs.font-mono.text-gray-400.font-bold.uppercase.tracking-wider "Scanning Timer Interval"]
         [:select.w-full.bg-black.border.border-white-10.p-2.5.rounded.text-xs.text-gray-300.focus:border-brand-muted.focus:outline-none.font-mono
          {:value (get edit-config :runInterval "hourly")
           :onChange #(swap! app-state assoc-in [:edit-config :runInterval] (.. % -target -value))}
          [:option {:value "hourly"} "Hourly polling (Щогодини)"]
          [:option {:value "daily"} "Daily check (Щодня)"]
          [:option {:value "realtime"} "Real-time watch (У реальному часі)"]
          [:option {:value "manual"} "Manual only (Вручну)"]]]]]

      ;; Horizontal Toggles Block
      [:div.space-y-4.pt-4.border-t.border-white-5
       [:span.block.text-xs.font-mono.text-gray-400.font-bold.uppercase.tracking-wider "Daemon System Flags"]
       [:div.grid.grid-cols-1.md:grid-cols-2.gap-4
        ;; Toggle 1: Background Scheduler
        [:label.flex.items-start.space-x-3.cursor-pointer.p-3.rounded.bg-black-30.border.border-white-5.hover:border-brand-muted.transition-all
         [:input.mt-1.rounded.border-white-20.bg-black.text-brand.focus:ring-brand.focus:ring-offset-black
          {:type "checkbox"
           :checked (get edit-config :daemonEnabled false)
           :onChange #(swap! app-state assoc-in [:edit-config :daemonEnabled] (.. % -target -checked))}]
         [:div
          [:span.text-xs.font-mono.text-gray-200.font-semibold.uppercase "Enable Background Daemon Sync"]
          [:p {:class "text-[11px] text-gray-500 leading-snug mt-0.5"} "Starts automatic directory scanner interval handlers"]]]

        ;; Toggle 2: Cache Skip
        [:label.flex.items-start.space-x-3.cursor-pointer.p-3.rounded.bg-black-30.border.border-white-5.hover:border-brand-muted.transition-all
         [:input.mt-1.rounded.border-white-20.bg-black.text-brand.focus:ring-brand.focus:ring-offset-black
          {:type "checkbox"
           :checked (get edit-config :enableCaching false)
           :onChange #(swap! app-state assoc-in [:edit-config :enableCaching] (.. % -target -checked))}]
         [:div
          [:span.text-xs.font-mono.text-gray-200.font-semibold.uppercase "Avoid Rescanning (Cache)"]
          [:p {:class "text-[11px] text-gray-500 leading-snug mt-0.5"} "Bypasses re-processing completed records"]]]

        ;; Toggle 3: Auto-Delete
        [:label.flex.items-start.space-x-3.cursor-pointer.p-3.rounded.bg-black-30.border.border-white-5.hover:border-brand-muted.transition-all
         [:input.mt-1.rounded.border-white-20.bg-black.text-brand.focus:ring-brand.focus:ring-offset-black
          {:type "checkbox"
           :checked (get edit-config :autoCleanup false)
           :onChange #(swap! app-state assoc-in [:edit-config :autoCleanup] (.. % -target -checked))}]
         [:div
          [:span.text-xs.font-mono.text-gray-200.font-semibold.uppercase "Auto-Delete Original"]
          [:p {:class "text-[11px] text-gray-500 leading-snug mt-0.5"} "Deletes files from input folder on sorting success"]]]

        ;; Toggle 4: ISBN Only
        [:label.flex.items-start.space-x-3.cursor-pointer.p-3.rounded.bg-black-30.border.border-white-5.hover:border-brand-muted.transition-all
         [:input.mt-1.rounded.border-white-20.bg-black.text-brand.focus:ring-brand.focus:ring-offset-black
          {:type "checkbox"
           :checked (get edit-config :isbnOnlyRequests false)
           :onChange #(swap! app-state assoc-in [:edit-config :isbnOnlyRequests] (.. % -target -checked))}]
         [:div
          [:span.text-xs.font-mono.text-gray-200.font-semibold.uppercase "ISBN Only Requests"]
          [:p {:class "text-[11px] text-gray-500 leading-snug mt-0.5"} "Strictly query open books APIs. Disables fallback cataloguing via Gemini AI models."]]]]]

      [:div.flex.justify-start.space-x-3.border-t.border-white-5.pt-4
       [:button.bg-brand.text-black.px-6.py-3.rounded-lg.text-xs.font-bold.hover:bg-brand-hover.transition-all.uppercase.font-mono.tracking-wider
        {:on-click (fn []
                     (let [cur-config (:edit-config @app-state)
                           dirs (get cur-config :inputDirs)
                           input-dirs (cond
                                        (vector? dirs) dirs
                                        (string? dirs) (vec (map str/trim (str/split dirs #",")))
                                        :else (vec dirs))
                           sanitized-config (assoc cur-config :inputDirs input-dirs)]
                       (-> (js/fetch "/api/config"
                                     #js {:method "POST"
                                          :headers #js {"Content-Type" "application/json"}
                                          :body (js/JSON.stringify (clj->js sanitized-config))})
                           (.then (fn [resp] (.json resp)))
                           (.then (fn [res]
                                    (swap! app-state assoc :save-success true)
                                     (fetch-config!)))
                            (.catch (fn [err] (js/console.error err))))))}
         "Save & Apply Daemon Settings"]]]]))

(defn content-logs []
  (let [logs (:logs @app-state)
        log-search (or (:log-search @app-state) "")
        log-type (or (:log-type @app-state) "all")
        
        ;; Predicates to categorize log lines
        failure? (fn [line] (or (str/includes? line "failure") (str/includes? line "failed") (str/includes? line "⚠️")))
        success? (fn [line] (or (str/includes? line "Success") (str/includes? line "successfully") (str/includes? line "✅")))
        resolved? (fn [line] (or (str/includes? line "Resolved") (str/includes? line "🔍") (str/includes? line "✨")))
        info? (fn [line] (not (or (failure? line) (success? line) (resolved? line))))
        
        ;; Compute counts for each category from the full logs list
        all-count (count logs)
        failure-count (count (filter failure? logs))
        success-count (count (filter success? logs))
        resolved-count (count (filter resolved? logs))
        info-count (count (filter info? logs))
        
        ;; Filter by selected category type
        type-filtered (cond
                        (= log-type "failures") (filter failure? logs)
                        (= log-type "success") (filter success? logs)
                        (= log-type "resolved") (filter resolved? logs)
                        (= log-type "info") (filter info? logs)
                        :else logs)
                        
        ;; Filter by text search (case-insensitive)
        search-term (str/lower-case (str/trim log-search))
        final-filtered-logs (if (str/blank? search-term)
                              type-filtered
                              (filter (fn [line]
                                        (str/includes? (str/lower-case line) search-term))
                                      type-filtered))]
    [:div.space-y-6
     [:div.border-b.border-white-5.pb-4
      [:h2.text-2xl.font-serif.text-white.font-semibold "📋 Stateful Core Daemon Logs"]
      [:p.text-xs.text-gray-400 "Active streaming telemetry of file system watchers, OCR runs, API calls and catalog relocations."]]

     [:div.bg-dark-12.p-6.rounded-xl.border.border-white-5.space-y-4
      [:div.flex.flex-col.sm:flex-row.sm:items-center.justify-between.gap-4
       [:div
        [:h3.text-md.font-serif.text-brand "Streaming Terminal Output"]
        [:p {:class "text-[11px] text-gray-400"} "Displays standard logs retrieved from data/logs.json in real time."]]
       [:div.flex.flex-wrap.items-center.gap-2.self-start.sm:self-auto
        [:button.bg-brand.text-black.hover:bg-brand-hover.px-3.py-2.rounded-lg.text-xs.font-semibold.transition-all
         {:on-click (fn []
                      (-> (js/navigator.clipboard.writeText (clojure.string/join "\n" final-filtered-logs))
                          (.then (fn []
                                   (swap! app-state assoc :logs-copied? true)
                                   (js/setTimeout #(swap! app-state assoc :logs-copied? false) 2000)))))}
         (if (:logs-copied? @app-state) "✓ Copied Filtered Logs!" "📋 Copy Filtered Logs")]
        [:button.border.border-white-10.text-gray-300.px-3.py-2.rounded-lg.text-xs.font-medium.hover:bg-brand-soft.transition-all
         {:on-click #(fetch-logs!)}
         "Refresh Logs"]
        [:div.h-5.w-px.bg-white-10.hidden.md:block]
        [:span {:class "text-[10px] text-gray-500 font-bold uppercase tracking-wider"} "Clear:"]
        (let [confirm-act (:confirm-action @app-state)]
          [:<>
           [:button.border.px-2.5.py-2.rounded-lg.text-xs.font-medium.transition-all
            {:class (if (= confirm-act "clean-logs-all")
                      "border-rose-600 bg-rose-950/40 text-rose-400 hover:bg-rose-900/50"
                      "border-rose-500/25 text-rose-400 hover:bg-rose-500/10")
             :on-click (fn []
                         (if (= confirm-act "clean-logs-all")
                           (do
                             (swap! app-state assoc :confirm-action nil)
                             (clean-logs! "all"))
                           (swap! app-state assoc :confirm-action "clean-logs-all")))}
            (if (= confirm-act "clean-logs-all") "⚠️ Confirm?" "All")]
           [:button.border.px-2.5.py-2.rounded-lg.text-xs.font-medium.transition-all
            {:class (if (= confirm-act "clean-logs-beforeToday")
                      "border-amber-600 bg-amber-950/40 text-amber-400 hover:bg-amber-900/50"
                      "border-amber-500/25 text-amber-400 hover:bg-amber-500/10")
             :on-click (fn []
                         (if (= confirm-act "clean-logs-beforeToday")
                           (do
                             (swap! app-state assoc :confirm-action nil)
                             (clean-logs! "beforeToday"))
                           (swap! app-state assoc :confirm-action "clean-logs-beforeToday")))}
            (if (= confirm-act "clean-logs-beforeToday") "⚠️ Confirm?" "Before Today")]
           (when (and confirm-act (clojure.string/starts-with? confirm-act "clean-logs-"))
             [:button.text-gray-500.hover:text-gray-300.px-1.cursor-pointer
              {:class "text-[11px]"
               :on-click #(swap! app-state assoc :confirm-action nil)}
              "Cancel"])])]]
      
      ;; Search and Category Filters panel
      [:div.flex.flex-col.lg:flex-row.lg:items-center.justify-between.gap-4.bg-black-30.p-4.rounded-lg.border.border-white-5
       ;; Search Box
       [:div.relative.flex-1.w-full.max-w-md
        [:input {:type "text"
                 :placeholder "Search log lines..."
                 :value log-search
                 :class "w-full bg-black border border-white-10 rounded-lg pl-8 pr-8 py-1.5 text-xs text-white placeholder-gray-500 focus:outline-none focus:border-brand"
                 :onChange (fn [e]
                             (swap! app-state assoc :log-search (.. e -target -value)))}]
        ;; Search Icon
        [:span.absolute.left-2.5.top-1.5.text-xs.opacity-40
         "🔍"]
        ;; Clear button
        (when-not (str/blank? log-search)
          [:button {:class "absolute right-2.5 top-1.5 text-gray-400 hover:text-white text-xs cursor-pointer px-1"
                    :on-click (fn [] (swap! app-state assoc :log-search ""))}
           "✕"])]
       
       ;; Category Buttons
       [:div.flex.flex-wrap.items-center.gap-1.5
        [:button {:class (str "px-3 py-1.5 rounded-lg text-xs font-mono transition-all border flex items-center gap-1.5 cursor-pointer "
                              (if (= log-type "all")
                                "bg-brand-soft text-brand border-brand/50 font-semibold"
                                "bg-black border-white-10 text-gray-400 hover:text-white hover:border-gray-500"))
                  :on-click #(swap! app-state assoc :log-type "all")}
         "All" [:span {:class "text-[10px] opacity-70"} (str "(" all-count ")")]]
         
        [:button {:class (str "px-3 py-1.5 rounded-lg text-xs font-mono transition-all border flex items-center gap-1.5 cursor-pointer "
                              (if (= log-type "resolved")
                                "bg-brand-soft text-brand border-brand/50 font-semibold"
                                "bg-black border-white-10 text-brand/80 hover:text-brand hover:border-brand/40"))
                  :on-click #(swap! app-state assoc :log-type "resolved")}
         "🔍 Resolved" [:span {:class "text-[10px] opacity-70"} (str "(" resolved-count ")")]]

        [:button {:class (str "px-3 py-1.5 rounded-lg text-xs font-mono transition-all border flex items-center gap-1.5 cursor-pointer "
                              (if (= log-type "success")
                                "bg-emerald-500/10 text-emerald-400 border-emerald-500/40 font-semibold"
                                "bg-black border-white-10 text-emerald-400/80 hover:text-emerald-400 hover:border-emerald-500/40"))
                  :on-click #(swap! app-state assoc :log-type "success")}
         "✅ Success" [:span {:class "text-[10px] opacity-70"} (str "(" success-count ")")]]

        [:button {:class (str "px-3 py-1.5 rounded-lg text-xs font-mono transition-all border flex items-center gap-1.5 cursor-pointer "
                              (if (= log-type "failures")
                                "bg-rose-500/10 text-rose-400 border-rose-500/40 font-semibold"
                                "bg-black border-white-10 text-rose-400/80 hover:text-rose-400 hover:border-rose-500/40"))
                  :on-click #(swap! app-state assoc :log-type "failures")}
         "⚠️ Failures" [:span {:class "text-[10px] opacity-70"} (str "(" failure-count ")")]]

        [:button {:class (str "px-3 py-1.5 rounded-lg text-xs font-mono transition-all border flex items-center gap-1.5 cursor-pointer "
                              (if (= log-type "info")
                                "bg-white/5 text-gray-300 border-white-10 font-semibold"
                                "bg-black border-white-10 text-gray-500 hover:text-gray-300 hover:border-white-20"))
                  :on-click #(swap! app-state assoc :log-type "info")}
         "ℹ️ Info" [:span {:class "text-[10px] opacity-70"} (str "(" info-count ")")]]]]
      
      [:div.bg-black.p-5.rounded-lg.border.border-white-10.font-mono.text-xs.text-gray-300.overflow-y-auto.space-y-2 {:class "min-h-[500px] max-h-[650px]"}
       (if (empty? final-filtered-logs)
         [:p.text-xs.text-gray-500.italic.p-4 "No log lines matched the active filters & search query."]
         (for [[idx log-line] (map-indexed vector final-filtered-logs)]
           (let [log-class (cond
                             (failure? log-line) "text-rose-400"
                             (success? log-line) "text-emerald-400"
                             (resolved? log-line) "text-brand"
                             :else "text-gray-400")]
             [:p.text-xs.leading-relaxed.whitespace-pre-wrap
              {:key idx :class log-class}
              log-line])))]]]))

(defn content-databases []
  (let [active-db (or (:active-db @app-state) "scanned_books")
        db-search (or (:db-search @app-state) "")
        selected-record (:selected-record @app-state)
        raw-state (or (:raw-state @app-state) {})
        records (or (get raw-state active-db) #js [])
        get-reconciled-val (fn [rec field-key]
                             (let [raw (get rec field-key)]
                               (if (and (= active-db "scanned_books")
                                        (= field-key "isbn_detected")
                                        (or (nil? raw) (= raw "null") (= raw "None")))
                                 (let [filepath (or (get rec "filepath") (get rec :filepath))
                                       orgs (or (get raw-state "file_organization") (get raw-state :file_organization) #js [])
                                       matched-org (.find orgs (fn [o] (= (or (get o "filepath") (get o :filepath)) filepath)))]
                                   (if matched-org
                                     (let [isbn (or (get matched-org "isbn") (get matched-org :isbn))]
                                       (if (or (nil? isbn) (= isbn "null") (= isbn "None"))
                                         nil
                                         isbn))
                                     nil))
                                 raw)))
        search-term (str/lower-case (str/trim db-search))
        filtered-records (if (str/blank? search-term)
                           records
                           (.filter records (fn [rec]
                                              (let [str-val (str/lower-case (js/JSON.stringify rec))]
                                                (str/includes? str-val search-term)))))
        headers (get {"scanned_books" [["filepath" "File Path"]
                                       ["filename" "Filename"]
                                       ["isbn_detected" "Detected ISBN"]
                                       ["status" "Status"]
                                       ["timestamp" "Timestamp"]]
                      "isbn_requests" [["filepath" "File Path"]
                                       ["isbn" "ISBN Query"]
                                       ["status" "Status"]
                                       ["timestamp" "Timestamp"]]
                      "ai_categorization" [["filepath" "File Path"]
                                           ["text_preview" "Text Preview"]
                                           ["status" "Status"]
                                           ["timestamp" "Timestamp"]]
                      "file_organization" [["filepath" "File Path"]
                                           ["dest_path" "Destination Path"]
                                           ["author" "Author"]
                                           ["title" "Title"]
                                           ["year" "Year"]
                                           ["genre" "Genre"]
                                           ["isbn" "ISBN"]
                                           ["confidence" "Confidence"]
                                           ["status" "Status"]
                                           ["notes" "Notes"]
                                           ["timestamp" "Timestamp"]]}
                     active-db)]
    [:div.space-y-6
     [:div.border-b.border-white-5.pb-4
      [:h2.text-2xl.font-serif.text-white.font-semibold "🗄️ System Database Registries"]
      [:p.text-xs.text-gray-400 "Inspect state.json table records, cataloging logs, OCR results, and physical filesystems metadata in real-time."]]

     ;; Shared Scanning Progress Dashboard Component
     [ocr-monitor-component]

     ;; Sub-tab selection menu
     [:div.grid.grid-cols-2.md:grid-cols-4.gap-3
      (for [[db-key label badge-color icon]
            [["scanned_books" "Scanned Publications" "bg-blue-500/10 text-blue-400" "📖"]
             ["isbn_requests" "ISBN Request Logs" "bg-amber-500/10 text-amber-400" "🔍"]
             ["ai_categorization" "AI Categorization" "bg-purple-500/10 text-purple-400" "🧠"]
             ["file_organization" "File Relocations" "bg-emerald-500/10 text-emerald-400" "📂"]]]
        (let [count-val (or (and (get raw-state db-key) (.-length (get raw-state db-key))) 0)]
          [:button.p-4.rounded-xl.border.text-left.transition-all.cursor-pointer.flex.flex-col.space-y-2
           {:key db-key
            :class (if (= active-db db-key)
                     "bg-brand-soft border-brand text-white shadow-lg"
                     "bg-dark-12 border-white-5 text-gray-400 hover:text-white hover:border-white-10")
            :on-click (fn []
                        (swap! app-state assoc :active-db db-key)
                        (swap! app-state assoc :selected-record nil))}
           [:div.flex.items-center.justify-between
            [:span.text-lg icon]
            [:span.px-2.py-0.5.rounded.font-mono.font-bold {:class (str "text-[10px] " badge-color)} (str count-val " recs")]]
           [:span.text-xs.font-mono.uppercase.tracking-wider.font-bold label]]))]

     ;; Records Table Section
     [:div.bg-dark-12.p-6.rounded-xl.border.border-white-5.space-y-4
      [:div.flex.flex-col.md:flex-row.md:items-center.justify-between.gap-4
       [:div
        [:h3.text-md.font-serif.text-brand (str "Active Registry: " (str/replace active-db "_" " ") " (" (or (and filtered-records (.-length filtered-records)) 0) " matches)")]
        [:p {:class "text-[11px] text-gray-400"} "Click on any row to view full-fidelity record details & raw metadata fields."]]
       
       ;; Clean Controls & Search combo
       [:div.flex.flex-wrap.items-center.gap-3.w-full.md:w-auto
        ;; Clean button group
        [:div.flex.items-center.gap-1.5
         [:span {:class "text-[10px] text-gray-500 font-bold uppercase tracking-wider"} "Clean db:"]
         (let [confirm-act (:confirm-action @app-state)]
           [:<>
            [:button.border.rounded.px-2.py-1.transition-all.cursor-pointer
             {:class (if (= confirm-act "clean-db-all")
                       "border-rose-600 bg-rose-950/40 text-rose-400 hover:bg-rose-900/50 text-[11px]"
                       "border-rose-500/20 text-rose-400 hover:bg-rose-500/10 text-[11px]")
              :on-click (fn []
                          (if (= confirm-act "clean-db-all")
                            (do
                              (swap! app-state assoc :confirm-action nil)
                              (clean-state! "all"))
                            (swap! app-state assoc :confirm-action "clean-db-all")))}
             (if (= confirm-act "clean-db-all") "⚠️ Confirm?" "All")]
            [:button.border.rounded.px-2.py-1.transition-all.cursor-pointer
             {:class (if (= confirm-act "clean-db-today")
                       "border-amber-600 bg-amber-950/40 text-amber-400 hover:bg-amber-900/50 text-[11px]"
                       "border-amber-500/20 text-amber-400 hover:bg-amber-500/10 text-[11px]")
              :on-click (fn []
                          (if (= confirm-act "clean-db-today")
                            (do
                              (swap! app-state assoc :confirm-action nil)
                              (clean-state! "today"))
                            (swap! app-state assoc :confirm-action "clean-db-today")))}
             (if (= confirm-act "clean-db-today") "⚠️ Confirm?" "Today")]
            [:button.border.rounded.px-2.py-1.transition-all.cursor-pointer
             {:class (if (= confirm-act "clean-db-before")
                       "border-blue-600 bg-blue-950/40 text-blue-400 hover:bg-blue-900/50 text-[11px]"
                       "border-blue-500/20 text-blue-400 hover:bg-blue-500/10 text-[11px]")
              :on-click (fn []
                          (if (= confirm-act "clean-db-before")
                            (do
                              (swap! app-state assoc :confirm-action nil)
                              (clean-state! "beforeToday"))
                            (swap! app-state assoc :confirm-action "clean-db-before")))}
             (if (= confirm-act "clean-db-before") "⚠️ Confirm?" "Before Today")]
            (when (and confirm-act (clojure.string/starts-with? confirm-act "clean-db-"))
              [:button.text-gray-500.hover:text-gray-300.px-1.cursor-pointer
               {:class "text-[11px]"
                :on-click #(swap! app-state assoc :confirm-action nil)}
               "Cancel"])])]
        
        [:div.h-5.w-px.bg-white-10.hidden.sm:block]
       
        ;; Search box for table
       [:div.relative.w-full.max-w-xs
        [:input {:type "text"
                 :placeholder "Filter records..."
                 :value db-search
                 :class "w-full bg-black border border-white-10 rounded-lg pl-8 pr-8 py-1.5 text-xs text-white placeholder-gray-500 focus:outline-none focus:border-brand"
                 :on-change (fn [e] (swap! app-state assoc :db-search (.. e -target -value)))}]
        [:span.absolute.left-2.5.top-1.5.text-xs.opacity-40 "🔍"]
        (when-not (str/blank? db-search)
          [:button {:class "absolute right-2.5 top-1.5 text-gray-400 hover:text-white text-xs cursor-pointer px-1"
                    :on-click (fn [] (swap! app-state assoc :db-search ""))} "✕"])]]]

      ;; Responsive Table
      (if (or (nil? filtered-records) (zero? (.-length filtered-records)))
        [:div.p-12.text-center.border.border-white-5.rounded-lg.bg-black-30
         [:p.text-xs.text-gray-500.italic "No database records found matching active filters in this registry."]]
        
        [:div.space-y-4
         [:div.overflow-x-auto.border.border-white-5.rounded-lg.bg-black
          [:table.w-full.text-left.border-collapse.text-xs
           [:thead.bg-white-5.border-b.border-white-10.font-mono.uppercase.text-gray-400.tracking-wider {:class "text-[10px]"}
            [:tr
             (for [[field-key display-name] headers]
               ^{:key field-key}
               [:th.p-3.font-semibold display-name])]]
           [:tbody.divide-y.divide-white-5
            (for [[idx rec] (map-indexed vector filtered-records)]
              (let [is-selected? (= selected-record rec)]
                ^{:key idx}
                [:tr.transition-all.cursor-pointer.group
                 {:class (str (if is-selected? "bg-brand/10 font-medium" "hover:bg-brand-soft/10"))
                  :on-click #(swap! app-state assoc :selected-record rec)}
                 (for [[field-key _] headers]
                   (let [raw-val (get-reconciled-val rec field-key)
                         val-str (if (or (nil? raw-val) (= raw-val "null") (= raw-val "None")) "nil" (str raw-val))]
                     ^{:key field-key}
                     [:td.p-3.font-mono.max-w-xs.truncate.text-gray-300.group-hover:text-white.transition-colors
                      (cond
                        (= field-key "status")
                        [:span.px-1.5.py-0.5.rounded.font-bold
                         {:class (str "text-[9px] "
                                      (if (= raw-val "completed")
                                        "bg-emerald-500/10 text-emerald-400"
                                        "bg-amber-500/10 text-amber-400"))}
                         val-str]
                        
                        (= field-key "confidence")
                        [:span.text-brand.font-bold (str val-str "%")]

                        :else val-str)]))]))]]]
         
         ;; Detailed view card of the selected record
         (when-let [rec selected-record]
           [:div.bg-black.p-5.rounded-lg.border.space-y-4.animate-fadeIn {:class "border-brand-muted/20"}
            [:div.flex.items-center.justify-between.border-b.border-white-5.pb-2
             [:span.text-xs.font-mono.text-brand.font-bold.uppercase "🔍 Detailed Record Field Inspector"]
             [:button.text-gray-500.hover:text-white.text-xs.font-mono
              {:on-click #(swap! app-state assoc :selected-record nil)} "Close ✕"]]
            [:div.grid.grid-cols-1.md:grid-cols-2.gap-4.text-xs
             (for [[field-key display-name] headers]
               (let [raw-val (get-reconciled-val rec field-key)
                     val-str (if (or (nil? raw-val) (= raw-val "null") (= raw-val "None")) "-" (str raw-val))]
                 ^{:key field-key}
                 [:div.space-y-1.p-2.rounded.bg-dark-12.border.border-white-5
                  [:span.block.text-gray-500.font-mono.uppercase.font-bold {:class "text-[10px]"} display-name]
                  [:div.font-mono.text-white.whitespace-pre-wrap.break-words
                   (if (and (= field-key "text_preview") (> (count val-str) 200))
                      [:div.space-y-2
                       [:p.leading-relaxed (str (subs val-str 0 200) "...")]
                       [:div {:class "max-h-48 overflow-y-auto bg-black p-1.5 rounded border border-white-5 text-[11px] text-gray-400"}
                        val-str]]
                     val-str)]]))]
            ;; Also show the full raw JSON
            [:div.pt-2.space-y-1
             [:span.block.text-gray-500.font-mono.uppercase.font-bold {:class "text-[10px]"} "Raw Record JSON Source"]
             [:pre.bg-dark-12.p-3.rounded.border.border-white-5.text-gray-400.font-mono.overflow-x-auto {:class "text-[10px] max-h-40"}
              (js/JSON.stringify (clj->js rec) nil 2)]]])])]]))

(defn dir-picker-modal []
  (when (:show-dir-picker? @app-state)
    (let [curr-path (:dir-picker-curr-path @app-state)
          parent-path (:dir-picker-parent @app-state)
          dirs (:dir-picker-subdirs @app-state)]
      [:div.fixed.inset-0.z-50.flex.items-center.justify-center.p-4
       ;; Backdrop
       [:div.fixed.inset-0.backdrop-blur-sm
        {:class "bg-black/80"
         :onClick close-dir-picker!}]
       ;; Dialog Panel
       [:div.bg-dark-12.border.border-white-10.rounded-xl.w-full.max-w-xl.p-6.relative.z-10.shadow-2xl.space-y-4
        [:div.flex.items-center.justify-between.pb-3.border-b.border-white-5
         [:div.flex.items-center.space-x-2
          [:span.text-brand "📁"]
          [:h3.text-lg.font-serif.text-white.font-semibold "Select Directory Path"]]
         [:button.text-gray-400.hover:text-white.text-xs.font-mono
          {:onClick close-dir-picker!}
          "[x] CLOSE"]]

        ;; Path banner
        [:div.bg-black.border.border-white-5.p-3.rounded.flex.items-center.justify-between.font-mono.text-xs
         [:span.text-gray-400.truncate (str "CURRENT PATH: " (or curr-path "/"))]
         (when parent-path
           [:button.text-brand.hover:underline.shrink-0.ml-2
            {:onClick #(load-dirs! parent-path)}
            "↑ Up One Level"])]

        ;; Scrollable directory list
        [:div.border.border-white-10.rounded-lg.bg-black.overflow-y-auto.divide-y.divide-white-5 {:class "max-h-64 h-64"}
         (if (empty? dirs)
           [:div.p-8.text-center.text-gray-500.text-xs.italic "No subdirectories found inside this folder."]
           (for [d dirs]
             (let [name (get d "name")
                   path (get d "path")]
               ^{:key path}
               [:div.flex.items-center.justify-between.p-3.transition-colors.group
                {:class "hover:bg-brand-soft/20"
                 :onClick nil}
                [:button.flex.items-center.space-x-3.flex-1.text-left
                 {:onClick #(load-dirs! path)}
                 [:span "📁"]
                 [:span.text-xs.text-gray-200.font-mono.font-medium.group-hover:text-brand.transition-colors name]]
                [:button.bg-brand.text-black.px-3.py-1.rounded.font-bold.opacity-0.group-hover:opacity-100.hover:bg-brand-hover.transition-all
                 {:class "text-[10px]"
                  :onClick #(select-dir-picker-dir! path)}
                 "SELECT"]])))]

        ;; Action buttons at bottom
        [:div.flex.justify-end.space-x-3.pt-2.border-t.border-white-5
         [:button.text-gray-400.hover:text-white.px-4.py-2.rounded.text-xs.font-mono
          {:onClick close-dir-picker!}
          "CANCEL"]
         [:button.bg-brand.text-black.px-5.py-2.rounded-lg.text-xs.font-bold.hover:bg-brand-hover.transition-all
          {:onClick #(select-dir-picker-dir! curr-path)}
          "CHOOSE CURRENT DIRECTORY"]]]])))

(defn main-layout []
  [:div.min-h-screen.bg-black.text-gray-100.font-sans.flex
   [sidebar-component]
   [:main.flex-1.pl-80.p-8.min-w-0.min-h-screen
    (case (:active-tab @app-state)
      "sandbox" [content-sandbox]
      "generator" [content-generator]
      "databases" [content-databases]
      "logs" [content-logs]
      [content-sandbox])]
   [dir-picker-modal]])

;; =============================================================================
;; ClojureScript Reagent Bootstrapper
;; =============================================================================
(defn ^:export init []
  (fetch-config!)
  (fetch-state!)
  (fetch-logs!)
  ;; Background polling loops for active live system syncs
  (js/setInterval (fn []
                    (fetch-state!)
                    (fetch-logs!))
                  5000)
  (rdom/render [main-layout] (.getElementById js/document "root")))
