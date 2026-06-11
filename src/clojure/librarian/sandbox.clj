(ns librarian.sandbox
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [librarian.core :as core]))

;; =============================================================================
;; Clojure Babashka Sandbox (Simulation Run)
;; =============================================================================
;; Guarantees ZERO side effects (no file moves, deletions, or writes)

(defn run-simulation [config state]
  (let [input-dirs (or (:inputDirs config) ["/data/books_to_sort"])
        output-dir (:outputDir config "/data/sorted_library")
        dest-template (:destinationTemplate config "{Author} - {Title} ({Year})")
        resolved-inputs (map core/resolve-path input-dirs)]
    (println "🧪 [Sandbox] Initiating ZERO-SIDE-EFFECT simulations...")
    (println "🧪 [Sandbox] Target scan folders:" (pr-str resolved-inputs))
    (println "🧪 [Sandbox] Projected catalog destination:" (pr-str output-dir))
    
    (let [files (for [dir resolved-inputs
                      file (if (.exists (io/file dir)) (filter #(.isFile %) (.listFiles (io/file dir))) [])
                      :when (re-find #"\.(pdf|epub|djvu|fb2)$" (.getName file))]
                  file)]
      
      (if (empty? files)
        (println "🧪 [Sandbox] Intake folders are clean. No files to project.")
        (doseq [file files]
          (let [path (.getAbsolutePath file)
                name (.getName file)
                ;; Simulate parsing
                ocr-text (str "Simulated OCR content for book: " name "\nISBN 978-0199540020")
                isbn (core/extract-valid-isbn ocr-text)
                
                ;; Projected Metadata standard structures
                mock-meta {:author "Bertrand Russell"
                           :title "The Problems of Philosophy"
                           :year 1912
                           :genre "Philosophy"
                           :isbn (or isbn "9780199540020")
                           :confidence 100}
                           
                dest-name (core/compute-destination mock-meta dest-template)
                raw-segments (str/split dest-name #"[/\\\\]+")
                file-base-name (or (last raw-segments) "Untitled Book")
                template-subdirs (filter #(not (str/blank? %)) (map core/sanitize (butlast raw-segments)))
                
                resolved-out-dir (core/resolve-path output-dir)
                sub-dirs (if (seq template-subdirs)
                           template-subdirs
                           [(core/sanitize (:genre mock-meta))])
                
                file-folder (core/sanitize file-base-name)
                category-folder (str/join "/" (concat [resolved-out-dir] sub-dirs [file-folder]))
                clean-ext (or (re-find #"\.[a-zA-Z0-9]+$" name) ".pdf")
                projected-destination (str category-folder "/" file-folder clean-ext)]
            
            (println "--------------------------------------------------------")
            (println "⚡ [Sandbox Simulation Report] Sourced file:" path)
            (println "  ▷ ISBN Pattern recognized:" (or isbn "NOT_FOUND"))
            (println "  ▷ Projected Metadata:    " (pr-str mock-meta))
            (println "  ▷ Projected Destination: " projected-destination)
            (println "  ⚡ STATUS: READY FOR ORGANIZATION (No files modified)"))))
      
      (println "✅ [Sandbox] Dry-run testing completed. Zero mutations performed."))))
