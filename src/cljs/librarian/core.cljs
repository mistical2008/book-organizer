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
                            :save-success false}))

;; =============================================================================
;; HTTP Actions & Effects
;; =============================================================================
(defn fetch-config! []
  (-> (js/fetch "/api/config")
      (.then (fn [resp] (.json resp)))
      (.then (fn [data]
               (swap! app-state assoc :config data)
               (swap! app-state assoc :edit-config data)))))

(defn fetch-state! []
  (-> (js/fetch "/api/state")
      (.then (fn [resp] (.json resp)))
      (.then (fn [data]
               (let [scanned (or (get data "scanned_books") (get data :scanned_books) [])
                     orgs (or (get data "file_organization") (get data :file_organization) [])
                     ;; Create a lookup map of org-items keyed by filepath
                     orgs-map (into {} (map (fn [o] [(or (get o "filepath") (get o :filepath)) o]) orgs))
                     ;; Reconcile scanned list and org list into map structure
                     books-map (into {}
                                     (map (fn [sb]
                                            (let [path (or (get sb "filepath") (get sb :filepath))
                                                  org (get orgs-map path)
                                                  entry {:status (or (get sb "status") (get sb :status))}]
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
                               ["logs" "📋 Production Logs"]]]
        [:button.w-full.flex.items-center.space-x-3.px-4.py-3.rounded-lg.text-xs.font-semibold.text-left.transition-all
         {:key tab
          :class (if (= active-tab tab)
                   "bg-brand text-black font-semibold shadow-md border-transparent"
                   "text-gray-400 hover:text-white hover:bg-brand-soft hover:border-brand-muted/10 border border-transparent")
          :on-click #(swap! app-state assoc :active-tab tab)}
         [:span label-icon]])]
     
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

(defn content-sandbox []
  (let [books (let [b (:scanned-books @app-state)]
                (if (map? b) b (into {} b)))
        logs (:logs @app-state)
        is-scanning (:is-scanning @app-state)]
    [:div.space-y-6
     ;; Header/Overview block
     [:div.border-b.border-white-5.pb-4
      [:h2.text-2xl.font-serif.text-white.font-semibold "⚡ Interactive Sandbox Hub"]
      [:p.text-xs.text-gray-400 "Queue file paths manually to simulate cataloguing runs, or run active polling daemon checks."]]

     [:div.grid.grid-cols-1.lg:grid-cols-3.gap-6
      ;; Main column (2/3 width) - Monitored Folder Index Stream
      [:div.lg:col-span-2.space-y-6
       [:div.bg-dark-12.p-6.rounded-xl.border.border-white-5.flex.flex-col
        [:h3.text-lg.font-serif.text-white.mb-4 "🗃️ Monitored Folder Index Stream"]
        (if (empty? books)
          [:div.p-8.text-center.border.border-dashed.border-white-10.rounded-lg
           [:p.text-xs.text-gray-500 "Intake stream queue is empty. Ready for scanning or simulator items."]]
          [:div.space-y-3.overflow-y-auto.pr-1 {:class "max-h-[600px]"}
           (for [[path entry] books]
             (let [status (:status entry)
                   status-class (case status
                                  "completed" "bg-emerald-950/80 text-emerald-400 border border-emerald-900"
                                  "pending" "bg-amber-950/80 text-amber-400 border border-amber-900"
                                  "queued" "bg-blue-950/80 text-blue-400 border border-blue-900"
                                  "low_confidence" "bg-rose-950/80 text-rose-400 border border-rose-900"
                                  "bg-white-5 text-brand border border-white-10")]
               ^{:key path}
               [:div.p-4.rounded-lg.border.border-white-5.bg-card-bg.space-y-3
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
                 [:span.uppercase.px-2.py-1.rounded.font-mono.font-semibold.h-fit
                  {:class (str "text-[10px] " status-class)}
                  (or status "unknown")]]
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
             :on-focus #(swap! app-state assoc :show-autocomplete? true)
             :on-blur #(js/setTimeout (fn [] (swap! app-state assoc :show-autocomplete? false)) 250)
             :on-change #(swap! app-state assoc :isbn-input (.. % -target -value))}]
           
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

(defn content-generator []
  (let [edit-config (:edit-config @app-state)
        save-status (:save-success @app-state)]
    [:div.space-y-6
     [:div.border-b.border-white-5.pb-4
      [:h2.text-2xl.font-serif.text-white.font-semibold "⚙️ Daemon Core Settings"]
      [:p.text-xs.text-gray-400 "Configure internal directory listeners, metadata confidence thresholds, caching, and API keys."]]

     [:div.bg-dark-12.p-6.rounded-xl.border.border-white-5.max-w-4xl.space-y-6
      (when save-status
        [:div.p-3.rounded.bg-emerald-950.border.border-emerald-800.text-emerald-400.text-xs.flex.items-center.justify-between
         [:span "✓ Configuration parameters written successfully to data/config.json!"]
         [:button.text-emerald-300.font-bold {:on-click #(swap! app-state assoc :save-success false)} "Dismiss"]])

      [:div.grid.grid-cols-1.md:grid-cols-2.gap-6
       ;; Left Column (File System Settings)
       [:div.space-y-4
        [:div.space-y-1.5
         [:label.block.text-xs.font-serif.text-gray-300 "Monitored Folders (Comma separated)"]
         [:input.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none
          {:type "text"
           :value (let [dirs (get edit-config :inputDirs)]
                    (if (vector? dirs) (str/join ", " dirs) dirs))
           :on-change #(swap! app-state assoc-in [:edit-config :inputDirs] (.. % -target -value))}]]

        [:div.space-y-1.5
         [:label.block.text-xs.font-serif.text-gray-300 "Output Category Destination Folder"]
         [:input.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none
          {:type "text"
           :value (get edit-config :outputDir)
           :on-change #(swap! app-state assoc-in [:edit-config :outputDir] (.. % -target -value))}]]

        [:div.space-y-1.5
         [:label.block.text-xs.font-serif.text-gray-300 "Dynamic Directory File Template"]
         [:input.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none
          {:type "text"
           :value (get edit-config :destinationTemplate)
           :on-change #(swap! app-state assoc-in [:edit-config :destinationTemplate] (.. % -target -value))}]]

        [:div.space-y-1.5
         [:label.block.text-xs.font-serif.text-gray-300 "Minimum Confidence Threshold (%)"]
         [:input.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none
          {:type "number"
           :min "0"
           :max "100"
           :value (str (get edit-config :confidenceThreshold))
           :on-change #(swap! app-state assoc-in [:edit-config :confidenceThreshold] (js/parseInt (.. % -target -value) 10))}]]]

       ;; Right Column (AI Models and Keys)
       [:div.space-y-4
        [:div.space-y-1.5
         [:label.block.text-xs.font-serif.text-gray-300 "Fallback Gemini Model Selector"]
         [:select.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none
          {:value (get edit-config :geminiModel "gemini-3.5-flash")
           :on-change #(swap! app-state assoc-in [:edit-config :geminiModel] (.. % -target -value))}
          [:option {:value "gemini-3.5-flash"} "gemini-3.5-flash (Fast & Accurate)"]
          [:option {:value "gemini-1.5-pro"} "gemini-1.5-pro (Aesthetic deep parsing)"]]]

        [:div.space-y-1.5
         [:label.block.text-xs.font-serif.text-brand.font-semibold "Gemini AI API Key"]
         [:input.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none.font-mono
          {:type "password"
           :placeholder "Enter Gemini API Key (saves to data/config.json)"
           :value (get edit-config :geminiApiKey "")
           :on-change #(swap! app-state assoc-in [:edit-config :geminiApiKey] (.. % -target -value))}]]

        [:div.space-y-1.5
         [:label.block.text-xs.font-serif.text-gray-300 "Google Books API Key"]
         [:input.w-full.bg-black.border.border-white-10.p-2.rounded.text-xs.text-white.focus:border-brand-muted.focus:outline-none.font-mono
          {:type "text"
           :placeholder "Enter Google Books API Key"
           :value (get edit-config :googleBooksApiKey "")
           :on-change #(swap! app-state assoc-in [:edit-config :googleBooksApiKey] (.. % -target -value))}]]

        ;; Toggles Block
        [:div.space-y-3.pt-3
         ;; ISBN Only toggle (Primary User Request!)
         [:label.flex.items-start.space-x-3.cursor-pointer.p-3.rounded.bg-black-30.border.border-white-5.hover:border-brand-muted.transition-colors
          [:input.mt-1
           {:type "checkbox"
            :checked (get edit-config :isbnOnlyRequests false)
            :on-change #(swap! app-state assoc-in [:edit-config :isbnOnlyRequests] (.. % -target -checked))}]
          [:div
           [:span.text-xs.font-serif.text-brand.font-semibold "ISBN Only Requests"]
           [:p.text-xs.text-gray-400 "Strictly query open books APIs for metadata. Disables fallback cataloguing via Gemini AI models."]]]

         ;; Enable Caching toggle
         [:label.flex.items-start.space-x-3.cursor-pointer.p-3.rounded.bg-black-30.border.border-white-5.hover:border-brand-muted.transition-colors
          [:input.mt-1
           {:type "checkbox"
            :checked (get edit-config :enableCaching false)
            :on-change #(swap! app-state assoc-in [:edit-config :enableCaching] (.. % -target -checked))}]
          [:div
           [:span.text-xs.font-serif.text-white.font-semibold "Intake Registry File Caching"]
           [:p.text-xs.text-gray-400 "Avoid duplicates by keeping track of successfully completed files, saving bandwidth."]]]

         ;; Daemon enabled toggle
         [:label.flex.items-start.space-x-3.cursor-pointer.p-3.rounded.bg-black-30.border.border-white-5.hover:border-brand-muted.transition-colors
          [:input.mt-1
           {:type "checkbox"
            :checked (get edit-config :daemonEnabled false)
            :on-change #(swap! app-state assoc-in [:edit-config :daemonEnabled] (.. % -target -checked))}]
          [:div
           [:span.text-xs.font-serif.text-white.font-semibold "Daemon Automated Scheduler"]
           [:p.text-xs.text-gray-400 "Permit background system loops to poll intake folders automatically."]]]]]]

      [:div.flex.justify-end.space-x-3.border-t.border-white-5.pt-4
       [:button.bg-brand.text-black.px-6.py-2.rounded.text-xs.font-bold.hover:bg-brand-hover.transition-colors
        {:on-click (fn []
                     (let [cur-config (:edit-config @app-state)
                           input-dirs (let [dirs (get cur-config :inputDirs)]
                                        (if (string? dirs)
                                          (vec (map str/trim (str/split dirs #",")))
                                          (vec dirs)))
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
        "Save Configuration"]]]]))

(defn content-logs []
  (let [logs (:logs @app-state)]
    [:div.space-y-6
     [:div.border-b.border-white-5.pb-4
      [:h2.text-2xl.font-serif.text-white.font-semibold "📋 Stateful Core Daemon Logs"]
      [:p.text-xs.text-gray-400 "Active streaming telemetry of file system watchers, OCR runs, API calls and catalog relocations."]]

     [:div.bg-dark-12.p-6.rounded-xl.border.border-white-5.space-y-4
      [:div.flex.items-center.justify-between
       [:div
        [:h3.text-md.font-serif.text-brand "Streaming Terminal Output"]
        [:p {:class "text-[11px] text-gray-400"} "Displays standard logs retrieved from data/logs.json in real time."]]
       [:button.border.border-white-10.text-gray-300.px-4.py-2.rounded-lg.text-xs.font-medium.hover:bg-brand-soft.transition-all
        {:on-click #(fetch-logs!)}
        "Refresh Logs"]]
      
      [:div.bg-black.p-5.rounded-lg.border.border-white-10.font-mono.text-xs.text-gray-300.overflow-y-auto.space-y-2 {:class "min-h-[500px] max-h-[650px]"}
       (if (empty? logs)
         [:p.text-xs.text-gray-500.italic.p-4 "No logged system events found in data/logs.json. Trigger a Scan or simulated book pipeline run."]
         (for [[idx log-line] (map-indexed vector logs)]
           (let [log-class (cond
                             (or (str/includes? log-line "failure") (str/includes? log-line "failed") (str/includes? log-line "⚠️")) "text-rose-400"
                             (or (str/includes? log-line "Success") (str/includes? log-line "successfully") (str/includes? log-line "✅")) "text-emerald-400"
                             (or (str/includes? log-line "Resolved") (str/includes? log-line "🔍") (str/includes? log-line "✨")) "text-brand"
                             :else "text-gray-400")]
             [:p.text-xs.leading-relaxed.whitespace-pre-wrap
              {:key idx :class log-class}
              log-line])))]]]))

(defn main-layout []
  [:div.min-h-screen.bg-black.text-gray-100.font-sans.flex
   [sidebar-component]
   [:main.flex-1.pl-80.p-8.min-w-0.min-h-screen
    (case (:active-tab @app-state)
      "sandbox" [content-sandbox]
      "generator" [content-generator]
      "logs" [content-logs]
      [content-sandbox])]])

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
