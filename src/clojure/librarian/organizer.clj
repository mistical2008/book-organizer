(ns librarian.organizer
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [librarian.core :as core]
            [clojure.java.shell :refer [sh]]))

;; =============================================================================
;; Clojure Babashka File Organizer Module
;; =============================================================================
;; High-privilege domain which manages real file manipulations & states

(defn write-log! [msg]
  (let [logs-file (io/file "data/logs.json")
        entry (str "[" (java.time.Instant/now) "] " msg)
        current-logs (try (json/parse-string (slurp logs-file) true)
                          (catch Exception _ []))
        truncated-logs (take 200 (conj current-logs entry))]
    (spit logs-file (json/generate-string truncated-logs {:pretty true}))
    (println "[Organizer]" msg)))

(defn organize-single-file! [file-path config state]
  (let [filename (.getName (io/file file-path))
        ocr-text (core/extract-book-text file-path)
        confidence-threshold (:confidenceThreshold config 70)
        output-dir (:outputDir config "/data/sorted_library")
        destination-template (:destinationTemplate config "{Author} - {Title} ({Year})")
        gemini-model (:geminiModel config "gemini-3.5-flash")
        auto-cleanup? (:autoCleanup config)
        isbn-only? (:isbnOnlyRequests config)
        
        ;; Use Core equations to find ISBN and metadata
        isbn-match (or (core/extract-valid-isbn ocr-text)
                       (core/extract-valid-isbn filename))
        metadata (let [api-meta (when (and isbn-match (not (str/blank? isbn-match)))
                                  (core/query-book-metadata isbn-match))]
                   (cond
                     api-meta
                     api-meta

                     isbn-only?
                     (do
                       (if (and isbn-match (not (str/blank? isbn-match)))
                         (println "⚠️ [Organizer] ISBN '" isbn-match "' detected for '" filename "' but API query produced no metadata. Skipping fallback as isbnOnlyRequests is active.")
                         (println "ℹ️ [Organizer] Skipping non-ISBN classification for '" filename "' (ISBN-only mode active)"))
                       nil)

                     :else
                     (try (core/extract-via-gemini ocr-text filename gemini-model)
                          (catch Exception e
                            (println "⚠️ Gemini extraction failure: " (.getMessage e))
                            nil))))]
                     
     (if (and metadata (>= (or (:confidence metadata) 0) confidence-threshold))
       (let [dest-name (core/compute-destination metadata destination-template)
             raw-segments (str/split dest-name #"[/\\\\]+")
             file-base-name (or (last raw-segments) "Untitled Book")
             template-subdirs (filter #(not (str/blank? %)) (map core/sanitize (butlast raw-segments)))
             
             resolved-out-dir (core/resolve-path output-dir)
             genre (or (:genre metadata) "Uncategorized")
             sub-dirs (if (seq template-subdirs)
                        template-subdirs
                        [(core/sanitize genre)])
             
             ;; Each catalog folder has a subfolder centered at the book name
             file-folder (core/sanitize file-base-name)
             category-folder (str/join "/" (concat [resolved-out-dir] sub-dirs [file-folder]))
             ext (or (re-find #"\.[a-zA-Z0-9]+$" file-path) ".pdf")
             final-dest (str category-folder "/" (core/sanitize file-base-name) ext)
             
             ;; Determine first new directory prior to actual creation for ownership adjustment
             all-path-levels (reductions (fn [acc segment] (.getAbsolutePath (io/file acc segment)))
                                         resolved-out-dir
                                         (concat sub-dirs [file-folder]))
             first-new-dir (first (filter #(not (.exists (io/file %))) all-path-levels))]
             
         (println "✨ [Organizer] Metadata is highly validated. confidence=" (:confidence metadata))
         (println "✨ [Organizer] Physical Relocations under construction ->" final-dest)
         
         ;; Perform actual physical filesystem mutations
         (io/make-parents final-dest)
         (io/copy (io/file file-path) (io/file final-dest))
         
         ;; Change ownership to the resolved logged-in user
         (when first-new-dir
           (core/chown-to-logged-user! first-new-dir))
         (core/chown-to-logged-user! final-dest)
         
         (when auto-cleanup?
           (println "🗑️ [Organizer] Active Clean-up enabled. Purging source raw book:" file-path)
           (io/delete-file (io/file file-path) true))
          
        {:status "completed" :meta metadata :destination final-dest :ocr ocr-text})
        
      (let [unknown-folder-name (:unknownFolderName config "Unknown")
            resolved-out-dir (core/resolve-path output-dir)
            unknown-dir (str resolved-out-dir "/" (core/sanitize unknown-folder-name))
            final-dest (str unknown-dir "/" filename)
            first-new-dir (when-not (.exists (io/file unknown-dir)) unknown-dir)]
        (println "❌ [Organizer] Validation failed or insufficient confidence threshold.")
        (println "📂 [Organizer] Relocating unorganized book to:" final-dest)
        
        (io/make-parents final-dest)
        (io/copy (io/file file-path) (io/file final-dest))
        (when first-new-dir
          (core/chown-to-logged-user! first-new-dir))
        (core/chown-to-logged-user! final-dest)
        (when auto-cleanup?
          (println "🗑️ [Organizer] Active Clean-up enabled. Purging source raw book:" file-path)
          (io/delete-file (io/file file-path) true))
        {:status "low_confidence" :reason "Low confidence or metadata query failed" :destination final-dest :ocr ocr-text}))))

(defn run-organizer-sync! []
  (let [config (core/load-config)
        state (core/load-state)
        input-dirs (or (:inputDirs config) ["/data/books_to_sort"])
        enable-caching? (not= (:enableCaching config) false)
        resolved-inputs (map core/resolve-path input-dirs)
        files (filter #(and (.isFile %) (re-find #"\.(pdf|epub|djvu|fb2|fb2\.zip|docx|html|htm|txt|md|markdown)$" (.getName %)))
                      (mapcat (fn [d]
                                (let [f (io/file d)]
                                  (if (and (.exists f) (.isDirectory f))
                                    (.listFiles f)
                                    [])))
                              resolved-inputs))
        files-count (count files)
        first-three-names (map #(.getName %) (take 3 files))]
    (write-log! (str "🚚 [Organizer] Initializing sorting executions on input files. Total files detected to sort: " files-count
                     ". First files queue: " (str/join ", " first-three-names)))
    (let [pre-batched-state (core/pre-process-and-batch-isbn-lookups! files config state)]
      (doseq [file files]
        (let [path (.getAbsolutePath file)]
          (let [cached-status (core/get-cached-status pre-batched-state path)]
            (if (and enable-caching? (and cached-status (or (= cached-status "completed") (= cached-status "failed") (= cached-status "low_confidence"))))
              (println "⏭️ Skipping cached file match:" path)
              (try
                (let [res (organize-single-file! path config pre-batched-state)
                      new-state (core/update-state-with-result pre-batched-state path res)]
                  (core/save-state! new-state)
                  ;; Pace delay of 2.5 seconds to cool down between files and prevent rate-limits
                  (when-not (= file (last files))
                    (write-log! "⏱️ [Organizer] Cooling down for 2.5 seconds to respect system API limits...")
                    (Thread/sleep 2500)))
                (catch Exception e
                  (write-log! (str "⚠️ [Organizer] Failed to organize '" (.getName file) "': " (.getMessage e)))))))))
      (write-log! "✅ [Organizer] Files successfully categorized, written and resolved."))))

(defn -main [& args]
  (println "================================================")
  (println "🚚 Librarian Organizer Daemon Sub-module booting...")
  (println "================================================")
  (run-organizer-sync!))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
