#!/usr/bin/env bb
;; =============================================================================
;; Librarian Container Orchestrator (Clojure Babashka)
;; Coordinates library folder sync cycles, cleans temp files, and manages process hooks.
;; =============================================================================

(ns librarian.container
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [cheshire.core :as json]))

(defn check-native-dependency [bin-name install-help]
  (let [res (sh "which" bin-name)]
    (if (zero? (:exit res))
      (println (str "✅ Native binary '" bin-name "' resolved at: " (str/trim (:out res))))
      (println (str "❌ Missing required command: '" bin-name "'. " install-help)))))

(defn run-checks []
  (println "=========================================================")
  (println "  🦉 LIBRARIAN DEPLOYMENT SYSTEM STATUS & NATIVE CHECKS    ")
  (println "=========================================================")
  (check-native-dependency "tesseract" "Please install tesseract-ocr (with ukr and eng engine packages).")
  (check-native-dependency "pdftotext" "Please install poppler-utils (retains PDF layout scanners).")
  (check-native-dependency "ddjvu" "Please install djvulibre-bin to parse OCR on djvu editions.")
  (println "========================================================="))

(defn read-config []
  (let [config-file (io/file "data/config.json")]
    (if (.exists config-file)
      (try (json/parse-string (slurp config-file) true)
           (catch Exception _ {}))
      {})))

(defn clean-garbage-files []
  (println "🗑️ Scanning for residual .tmp / -tmp-txt.txt temporary artifacts...")
  (let [temp-extensions #{".tmp" "-tmp-txt.txt"}
        state-dir (io/file "data")
        deleted-count (atom 0)]
    (when (.exists state-dir)
      (doseq [f (file-seq state-dir)]
        (when (and (.isFile f)
                   (some #(str/ends-with? (.getName f) %) temp-extensions))
          (do
            (io/delete-file f true)
            (swap! deleted-count inc)))))
    (println (str "✨ Temporary folder cleanup completed. Erased count: " @deleted-count))))

(defn run-service-loop []
  (println "🔄 Librarian Babashka daemon loop active. Pulling automated schedulers dynamically from data/config.json.")
  (try
    (while true
      (let [conf (read-config)
            ;; Read runIntervalMs or default to 60000ms (1 minute default for fallback)
            interval-ms (or (:runIntervalMs conf) 60000)
            sleep-sec (quot interval-ms 1000)]
        (println (str "\n--- 🕐 Checking scheduled folder sync cycle: " (java.time.Instant/now) " ---"))
        (println (str "ℹ️ Processing Mode: " (:processingMode conf "batch") " | Active Cycle Sleep: " sleep-sec " seconds"))
        ;; Trigger the librarian module to scan directories and apply Google Books/Gemini lookups
        (let [result (sh "bb" "daemon")]
          (print (:out result))
          (when-not (zero? (:exit result))
            (println "⚠️ Daemon cycle reported execution glitches: " (:err result))))
        (clean-garbage-files)
        ;; Sleep according to live configuration changes. Guard to prevent busy wait loops.
        (let [safe-sleep-ms (max 5000 interval-ms)]
          (Thread/sleep safe-sleep-ms))))
    (catch InterruptedException _
      (println "🛑 Scanning scheduler manually interrupted. Exiting gracefully."))))

(defn -main [& args]
  (run-checks)
  (let [mode (or (first args) "status")]
    (case mode
      "status" (println "Container engine is up. Pass 'run' to start daemon sync, or 'clean' to flush logs.")
      "clean"  (clean-garbage-files)
      "run"    (run-service-loop)
      (println (str "Unknown script argument directive: " mode ". Accepted: run, clean, status")))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
