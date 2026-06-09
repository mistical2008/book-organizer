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
  (let [ocr-text (core/run-ocr file-path)
        confidence-threshold (:confidenceThreshold config 70)
        output-dir (:outputDir config "/data/sorted_library")
        destination-template (:destinationTemplate config "{Author} - {Title} ({Year})")
        gemini-model (:geminiModel config "gemini-3.5-flash")
        auto-cleanup? (:autoCleanup config)
        isbn-only? (:isbnOnlyRequests config)
        
        ;; Use Core equations to find ISBN and metadata
        isbn-match (core/extract-valid-isbn ocr-text)
        metadata (cond
                   (and isbn-match (not (str/blank? isbn-match)))
                   (core/query-book-metadata isbn-match)

                   isbn-only?
                   nil

                   :else
                   (try (core/extract-via-gemini ocr-text gemini-model)
                        (catch Exception e
                          (println "⚠️ Gemini extraction failure: " (.getMessage e))
                          nil)))]
                     
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
            final-dest (str category-folder "/" (core/sanitize file-base-name) ext)]
            
        (println "✨ [Organizer] Metadata is highly validated. confidence=" (:confidence metadata))
        (println "✨ [Organizer] Physical Relocations under construction ->" final-dest)
        
        ;; Perform actual physical filesystem mutations
        (io/make-parents final-dest)
        (io/copy (io/file file-path) (io/file final-dest))
        
        (when auto-cleanup?
          (println "🗑️ [Organizer] Active Clean-up enabled. Purging source raw book:" file-path)
          (io/delete-file (io/file file-path) true))
          
        {:status "completed" :meta metadata :destination final-dest :ocr ocr-text})
        
      (do
        (println "❌ [Organizer] Validation failed or insufficient confidence threshold.")
        {:status "low_confidence" :reason "Low confidence" :ocr ocr-text}))))

(defn run-organizer-sync! []
  (let [config (core/load-config)
        state (core/load-state)
        input-dirs (or (:inputDirs config) ["/data/books_to_sort"])
        enable-caching? (not= (:enableCaching config) false)
        resolved-inputs (map core/resolve-path input-dirs)
        files (filter #(and (.isFile %) (re-find #"\.(pdf|epub|djvu)$" (.getName %)))
                      (mapcat #(.listFiles (io/file %)) resolved-inputs))
        files-count (count files)
        first-three-names (map #(.getName %) (take 3 files))]
    (write-log! (str "🚚 [Organizer] Initializing sorting executions on input files. Total files detected to sort: " files-count
                     ". First files queue: " (str/join ", " first-three-names)))
    (doseq [file files]
      (let [path (.getAbsolutePath file)]
        (let [cached-status (core/get-cached-status state path)]
          (if (and enable-caching? (and cached-status (or (= cached-status "completed") (= cached-status "failed") (= cached-status "low_confidence"))))
            (println "⏭️ Skipping cached file match:" path)
            (let [res (organize-single-file! path config state)
                  new-state (core/update-state-with-result state path res)]
              (core/save-state! new-state))))))
    (write-log! "✅ [Organizer] Files successfully categorized, written and resolved.")))

(defn -main [& args]
  (println "================================================")
  (println "🚚 Librarian Organizer Daemon Sub-module booting...")
  (println "================================================")
  (run-organizer-sync!))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
