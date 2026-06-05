(ns librarian.core
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell :refer [sh]]))

;; --- Config and State Files ---
(def config-path "data/config.json")
(def db-path "data/state.json")

;; --- Path resolver for home directories and ~ expansion ---
(defn resolve-path [dir-path]
  (if (str/blank? dir-path)
    ""
    (let [trimmed (str/trim dir-path)]
      (if (str/starts-with? trimmed "~")
        (let [home-base (cond
                          (and (.exists (io/file "/home/evgeniy")) (.isDirectory (io/file "/home/evgeniy")))
                          "/home/evgeniy"
                          
                          (.exists (io/file "/home"))
                          (let [homes (filter #(and (.isDirectory %) (not= (.getName %) "lost+found") (not= (.getName %) "guest"))
                                              (.listFiles (io/file "/home")))]
                            (if (seq homes)
                              (.getAbsolutePath (first homes))
                              (System/getProperty "user.home")))
                          
                          :else (System/getProperty "user.home"))]
          (if (= trimmed "~")
            home-base
            (str home-base (subs trimmed 1))))
        (.getAbsolutePath (io/file trimmed))))))

(defn load-config []
  (if (.exists (io/file config-path))
    (try (json/parse-string (slurp config-path) true)
         (catch Exception _ {}))
    {}))

(defn load-state []
  (if (.exists (io/file db-path))
    (try (json/parse-string (slurp db-path) true)
         (catch Exception _ {}))
    {}))

(defn save-state! [state]
  (spit db-path (json/generate-string state {:pretty true})))

(defn isbn-10? [isbn]
  (let [clean (str/upper-case (str/replace isbn #"[^0-9X]" ""))]
    (if (= (count clean) 10)
      (try
        (let [digits (map #(if (= % \X) 10 (Character/digit % 10)) (subs clean 0 9))
              last-char (last clean)
              last-val (if (= last-char \X) 10 (Character/digit last-char 10))
              sum (reduce + (map * (range 10 1 -1) digits))]
          (and (every? #(>= % 0) digits)
               (>= last-val 0)
               (zero? (mod (+ sum last-val) 11))))
        (catch Exception _ false))
      false)))

(defn isbn-13? [isbn]
  (let [clean (str/replace isbn #"[^0-9]" "")]
    (if (= (count clean) 13)
      (try
        (let [digits (map #(Character/digit % 10) clean)
              weights (cycle [1 3])
              sum (reduce + (map * weights digits))]
          (and (every? #(>= % 0) digits)
               (zero? (mod sum 10))))
        (catch Exception _ false))
      false)))

(defn valid-isbn? [isbn]
  (or (isbn-10? isbn) (isbn-13? isbn)))

(defn extract-valid-isbn [text]
  (if (str/blank? text)
    nil
    (let [pattern #"(?i)(?:ISBN(?:[- ]*1[03])?:?\s*)?((?:97[89][- ]?)?(?:\d[- ]?){9}[\dXx])"
          matches (re-seq pattern text)]
      (first (filter valid-isbn? (map #(str/replace (second %) #"[- ]" "") matches))))))

(defn query-google-books [isbn]
  (println (str "🔍 [Librarian] Seeking ISBN match: " isbn))
  (let [clean-isbn (str/replace isbn #"\D" "")
        url (str "https://www.googleapis.com/books/v1/volumes?q=isbn:" clean-isbn)]
    (try
      (let [resp (http/get url {:headers {"User-Agent" "Librarian-Babashka/1.0"}})
            body (json/parse-string (:body resp) true)]
        (if (and (> (:totalItems body) 0) (:items body))
          (let [volume-info (-> body :items first :volumeInfo)
                author (str/join ", " (:authors volume-info))
                title (:title volume-info)
                pub-date (:publishedDate volume-info)
                year (and pub-date (re-find #"\d{4}" pub-date))]
            {:author (or author "Unknown Author")
             :title (or title "Unknown Title")
             :year (if year (Integer/parseInt year) nil)
             :genre (or (first (:categories volume-info)) "General Study")
             :isbn clean-isbn
             :confidence 100
             :notes "Matched from Google Books API using Clojure Babashka Client."})
          nil))
      (catch Exception e
        (println "⚠️ Google Books lookup failure: " (.getMessage e))
        nil))))

(defn run-ocr [file-path]
  (println "📝 [Librarian] Translating layout using local tesseract CLI: " file-path)
  (let [output-base (str file-path "-tmp-txt")
        result (sh "tesseract" file-path output-base "-l" "eng+ukr")]
    (if (zero? (:exit result))
      (let [txt-file (io/file (str output-base ".txt"))
            txt-content (slurp txt-file)]
        (io/delete-file txt-file true)
        txt-content)
      "")))

(defn extract-via-gemini [text gemini-model]
  (let [api-key (System/getenv "GEMINI_API_KEY")]
    (if (str/blank? api-key)
      (throw (Exception. "GEMINI_API_KEY env is required."))
      (let [url (str "https://generativelanguage.googleapis.com/v1beta/models/" (or gemini-model "gemini-3.5-flash") ":generateContent?key=" api-key)
            system-prompt "Act as the Librarian Library Metadata Agent. Extract: author, title, year, genre, isbn. Return JSON: {author, title, year, genre, isbn, confidence, notes}."
            payload {:contents [{:parts [{:text (subs text 0 (min (count text) 4000))}]}]
                     :systemInstruction {:parts [{:text system-prompt}]}
                     :generationConfig {:responseMimeType "application/json"}}
            resp (http/post url {:headers {"Content-Type" "application/json"}
                                 :body (json/generate-string payload)})
            body (json/parse-string (:body resp) true)
            text-response (-> body :candidates first :content :parts first :text)]
        (json/parse-string text-response true)))))

(defn sanitize [s]
  (str/replace (str/trim s) #"[/\\?%*:|\"<>\s]+" " "))

(defn compute-destination [meta template]
  (let [author (or (:author meta) "Unknown Author")
        title (or (:title meta) "Unknown Title")
        year (if (and (:year meta) (> (int (:year meta)) 0)) (str (:year meta)) "Unknown Year")
        genre (or (:genre meta) "Uncategorized")
        isbn (or (:isbn meta) "No ISBN")
        path-name (-> template
                      (str/replace "{Author}" author)
                      (str/replace "{Title}" title)
                      (str/replace "{Year}" year)
                      (str/replace "{Genre}" genre)
                      (str/replace "{ISBN}" isbn))
        sanitized-path-name (sanitize path-name)]
    (str sanitized-path-name)))

(defn process-book [file-path config]
  (println "📖 Processing publication: " file-path)
  (let [ocr-text (run-ocr file-path)
        confidence-threshold (:confidenceThreshold config 70)
        output-dir (:outputDir config "/data/sorted_library")
        destination-template (:destinationTemplate config "{Author} - {Title} ({Year})")
        gemini-model (:geminiModel config "gemini-3.5-flash")
        auto-cleanup? (:autoCleanup config)
        isbn-match (extract-valid-isbn ocr-text)
        metadata (or (and isbn-match (query-google-books isbn-match))
                     (extract-via-gemini ocr-text gemini-model))]
    (if (and metadata (>= (or (:confidence metadata) 0) confidence-threshold))
      (let [dest-name (compute-destination metadata destination-template)
            ;; Split by slashes to find subdirectories defined inside the template
            raw-segments (str/split dest-name #"[/\\\\]+")
            file-base-name (or (last raw-segments) "Untitled Book")
            template-subdirs (filter #(not (str/blank? %)) (map sanitize (butlast raw-segments)))
            
            ;; Determine base directories via path resolver
            resolved-out-dir (resolve-path output-dir)
            
            ;; Resolve category grouping subdirectories
            genre (or (:genre metadata) "Uncategorized")
            sub-dirs (if (seq template-subdirs)
                       template-subdirs
                       [(sanitize genre)])
            
            ;; Feature: Each book should be stored under a folder with the same name as the file (excluding extension)
            file-folder (sanitize file-base-name)
            
            ;; Build category folder path
            category-folder (str/join "/" (concat [resolved-out-dir] sub-dirs [file-folder]))
            ext (or (re-find #"\.[a-zA-Z0-9]+$" file-path) ".pdf")
            final-dest (str category-folder "/" (sanitize file-base-name) ext)]
        (println "✨ Metadata Resolved! confidence=" (:confidence metadata))
        (println "🚚 Relocating to: " final-dest)
        (io/make-parents final-dest)
        (io/copy (io/file file-path) (io/file final-dest))
        (when auto-cleanup?
          (io/delete-file (io/file file-path) true))
        {:status "completed" :meta metadata :destination final-dest})
      (do
        (println "❌ Metadata extraction did not meet confidence threshold.")
        {:status "low_confidence" :reason "Low confidence"}))))

(defn -main [& args]
  (println "================================================")
  (println "🤖 Librarian Clojure Babashka Daemon Live")
  (println "================================================")
  (let [config (load-config)
        state (load-state)
        input-dirs (or (:inputDirs config) ["/data/books_to_sort"])
        enable-caching? (not= (:enableCaching config) false)
        resolved-inputs (map resolve-path input-dirs)
        files (filter #(and (.isFile %) (re-find #"\.(pdf|epub|djvu)$" (.getName %)))
                      (mapcat #(.listFiles (io/file %)) resolved-inputs))]
    (doseq [file files]
      (let [path (.getAbsolutePath file)]
        (let [cached-status (get-in state [:scanned_books path :status])]
          (if (and enable-caching? (and cached-status (or (= cached-status "completed") (= cached-status "failed") (= cached-status "low_confidence"))))
            (println "⏭️ Skipping cached file: " path)
          (let [res (process-book path config)
                ;; Update state
                new-state (assoc-in state [:scanned_books path] res)]
            (save-state! new-state))))))
  (println "✅ [Librarian] Library scanning successfully completed."))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
