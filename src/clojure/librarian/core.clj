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
(defn parse-etc-passwd []
  (try
    (with-open [rdr (clojure.java.io/reader "/etc/passwd")]
      (into {} (keep (fn [line]
                       (let [parts (str/split line #":")]
                         (when (>= (count parts) 3)
                           [(nth parts 2) (first parts)])))
                     (line-seq rdr))))
    (catch Exception _ {})))

(defn active-run-user-names []
  (try
    (let [run-user (io/file "/run/user")]
      (if (and (.exists run-user) (.isDirectory run-user))
        (let [passwd (parse-etc-passwd)
              uids (->> (.listFiles run-user)
                        (filter #(.isDirectory %))
                        (map #(.getName %))
                        (filter #(re-matches #"\d+" %)))]
          (keep #(get passwd %) uids))
        []))
    (catch Exception _ [])))

(defn loginctl-names []
  (try
    (let [res (sh "loginctl" "list-users" "--no-legend")]
      (if (= 0 (:exit res))
        (->> (str/split-lines (:out res))
             (map str/trim)
             (keep (fn [line]
                     (let [parts (str/split line #"\s+")]
                       (when (>= (count parts) 2)
                         (second parts))))))
        []))
    (catch Exception _ [])))

(defn who-names []
  (try
    (let [res (sh "who")]
      (if (= 0 (:exit res))
        (->> (str/split-lines (:out res))
             (map str/trim)
             (keep (fn [line]
                     (let [parts (str/split line #"\s+")]
                       (first parts)))))
        []))
    (catch Exception _ [])))

(defn- filter-user-candidate [u]
  (let [banned-names #{"lost+found" "guest" "node" "ubuntu" "debian" "admin" "root" "http" "www" "nobody" "systemd-network"}
        home (io/file (str "/home/" u))]
    (and (not (str/blank? u))
         (not (contains? banned-names u))
         (.exists home)
         (.isDirectory home))))

(defn get-most-recently-modified-home-user []
  (try
    (if (.exists (io/file "/home"))
      (let [banned-names #{"lost+found" "guest" "node" "ubuntu" "debian" "admin" "root" "http" "www" "nobody" "systemd-network"}
            homes (filter #(and (.isDirectory %) 
                                (not (contains? banned-names (.getName %))))
                          (.listFiles (io/file "/home")))]
        (when (seq homes)
          (->> homes
               (sort-by #(.lastModified %) >)
               (first)
               (.getName))))
      nil)
    (catch Exception _ nil)))

(defn active-loginctl-user []
  (try
    (let [seat-res (sh "loginctl" "show-seat" "seat0" "-p" "ActiveSession")
          active-sess-id (when (= 0 (:exit seat-res))
                           (let [m (re-find #"ActiveSession=(\S+)" (:out seat-res))]
                             (second m)))]
      (if (and active-sess-id (not= active-sess-id "none") (not= active-sess-id ""))
        (let [sess-res (sh "loginctl" "show-session" active-sess-id "-p" "Name")
              uname (when (= 0 (:exit sess-res))
                      (let [m (re-find #"Name=(\S+)" (:out sess-res))]
                        (second m)))]
          (when-not (str/blank? uname) uname))
        ;; Fallback to listing all sessions and finding the one with Active=yes or State=active
        (let [list-res (sh "loginctl" "list-sessions" "--no-legend")]
          (if (= 0 (:exit list-res))
            (let [session-ids (keep (fn [line]
                                      (let [parts (str/split (str/trim line) #"\s+")]
                                        (when (seq parts) (first parts))))
                                    (str/split-lines (:out list-res)))]
              (first (keep (fn [sess-id]
                             (let [show-res (sh "loginctl" "show-session" sess-id)
                                   out (:out show-res)]
                               (when (and (= 0 (:exit show-res))
                                          (or (str/includes? out "Active=yes")
                                              (str/includes? out "State=active")))
                                 (let [m (re-find #"Name=(\S+)" out)]
                                   (second m)))))
                           session-ids)))
            nil))))
    (catch Exception _ nil)))

(defn active-who-user []
  (try
    (let [res (sh "who")]
      (if (= 0 (:exit res))
        (let [lines (->> (str/split-lines (:out res))
                         (map str/trim)
                         (filter #(not (str/blank? %))))]
          ;; Prefer lines containing graphical display signals like (:
          (let [graphical-users (keep (fn [line]
                                        (let [parts (str/split line #"\s+")]
                                          (when (and (>= (count parts) 2)
                                                     (str/includes? line "(:"))
                                            (first parts))))
                                      lines)]
            (if (seq graphical-users)
              (first graphical-users)
              ;; Otherwise, any user mentioned in who
              (let [all-users (keep (fn [line]
                                      (let [parts (str/split line #"\s+")]
                                        (when (seq parts) (first parts))))
                                    lines)]
                (first all-users)))))
        nil))
    (catch Exception _ nil)))

(defn get-current-os-user []
  (let [sudo-user (System/getenv "SUDO_USER")
        env-user (System/getenv "USER")
        logname-user (System/getenv "LOGNAME")
        ;; Determine order of preference
        direct-claims (filter filter-user-candidate [sudo-user env-user logname-user])
        active-loginctl (active-loginctl-user)
        active-who (active-who-user)
        who-candidates (filter filter-user-candidate (who-names))
        loginctl-candidates (filter filter-user-candidate (loginctl-names))
        runtime-candidates (filter filter-user-candidate (active-run-user-names))
        recent-home-candidate (get-most-recently-modified-home-user)]
    (cond
      ;; 1. Direct environment variable claims (non-root)
      (seq direct-claims) (first direct-claims)
      
      ;; 2. Active graphical or local session user from loginctl
      (and active-loginctl (filter-user-candidate active-loginctl)) active-loginctl
      
      ;; 3. Active graphical or terminal user from "who"
      (and active-who (filter-user-candidate active-who)) active-who
      
      ;; 4. Active logged-in users according to list in `who`
      (seq who-candidates) (first who-candidates)
      
      ;; 5. Active sessions registered with loginctl
      (seq loginctl-candidates) (first loginctl-candidates)
      
      ;; 6. Active systemd user-run environments (/run/user/<uid>)
      (seq runtime-candidates) (first runtime-candidates)
      
      ;; 7. Most recently active home directory under /home
      (not (str/blank? recent-home-candidate)) recent-home-candidate
      
      ;; Fallbacks
      :else (let [logname-res (try (sh "logname") (catch Exception _ nil))
                  logname-out (when (and logname-res (= 0 (:exit logname-res)))
                                (str/trim (:out logname-res)))]
              (if (filter-user-candidate logname-out)
                logname-out
                ;; Absolute last-resort default
                "root")))))

(declare load-config)

(defn resolve-path [dir-path]
  (if (str/blank? dir-path)
    ""
    (let [trimmed (str/trim dir-path)]
      (if (str/starts-with? trimmed "~")
        (let [config (load-config)
              config-user (:userName config)
              detected-user (get-current-os-user)
              user (if (and (not (str/blank? config-user)) (not= config-user "root"))
                     config-user
                     detected-user)
              user-home (str "/home/" user)
              home-base (cond
                          (and (not= user "root") (.exists (io/file user-home)) (.isDirectory (io/file user-home)))
                          user-home
                          
                          (and (.exists (io/file "/home/evgeniy")) (.isDirectory (io/file "/home/evgeniy")))
                          "/home/evgeniy"
                          
                          (and (.exists (io/file "/home/evg")) (.isDirectory (io/file "/home/evg")))
                          "/home/evg"
                          
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

(defn write-log! [msg]
  (let [logs-file (io/file "data/logs.json")
        entry (str "[" (java.time.Instant/now) "] " msg)
        current-logs (try (json/parse-string (slurp logs-file) true)
                          (catch Exception _ []))
        truncated-logs (take 200 (conj current-logs entry))]
    (spit logs-file (json/generate-string truncated-logs {:pretty true}))
    (println "[Librarian]" msg)))

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
    ;; 1. Accurate match with prefix
    (let [prefix-pattern #"(?i)(?:[I1l|іІ!\[\]][S58sЅѕ][B8bВв][NnНнМм])(?:[- ]*1[03])?:?\s*([0-9Xx](?:[- ]?[0-9Xx]){9,12})"
          prefix-matches (re-seq prefix-pattern text)
          prefix-candidates (map #(str/replace (second %) #"[^0-9Xx]" "") prefix-matches)
          valid-prefix (first (filter valid-isbn? prefix-candidates))]
      (if (seq valid-prefix)
        valid-prefix
        ;; 2. Fallback to raw numeric sequences
        (let [fallback-pattern #"\b[0-9Xx](?:[- ]?[0-9Xx]){9,12}\b"
              fallback-matches (re-seq fallback-pattern text)
              fallback-candidates (map #(str/replace % #"[^0-9Xx]" "") fallback-matches)]
          (first (filter valid-isbn? fallback-candidates)))))))

(defn query-google-books [isbn]
  (let [clean-isbn (str/replace isbn #"\D" "")
        config (load-config)
        api-key (or (:googleBooksApiKey config) "AIzaSyDYh87ATtVXKn9rF55Plh-1mGJhWFmigU0")
        url (if (str/blank? api-key)
              (str "https://www.googleapis.com/books/v1/volumes?q=isbn:" clean-isbn)
              (str "https://www.googleapis.com/books/v1/volumes?q=isbn:" clean-isbn "&key=" api-key))]
    (write-log! (str "📡 [Google Books API] Request started with payload/ISBN: " clean-isbn " (URL: " (if (str/blank? api-key) url (str/replace url api-key "MASKED")) ")"))
    (try
      (let [resp (http/get url {:headers {"User-Agent" "Librarian-Babashka/1.0"}})
            body (json/parse-string (:body resp) true)]
        (if (and (> (or (:totalItems body) 0) 0) (:items body))
          (let [volume-info (-> body :items first :volumeInfo)
                authors (:authors volume-info)
                author (if (seq authors) (str/join ", " authors) "Unknown Author")
                title (:title volume-info)
                pub-date (:publishedDate volume-info)
                year (and pub-date (re-find #"\d{4}" pub-date))]
            (write-log! (str "✅ [Google Books API] Request completed for ISBN: " clean-isbn " - Title: " title " - Author: " author))
            {:author (or author "Unknown Author")
             :title (or title "Unknown Title")
             :year (if year (Integer/parseInt year) nil)
             :genre (or (first (:categories volume-info)) "General Study")
             :isbn clean-isbn
             :confidence 100
             :notes "Matched from Google Books API using Clojure Babashka Client."})
          (do
            (write-log! (str "⚠️ [Google Books API] Request finished empty for ISBN: " clean-isbn))
            nil)))
      (catch Exception e
        (write-log! (str "❌ [Google Books API] Request failed for ISBN: " clean-isbn " - Error: " (.getMessage e)))
        nil))))

(defn query-open-library [isbn]
  (let [clean-isbn (str/replace isbn #"\D" "")
        url (str "https://openlibrary.org/api/books?bibkeys=ISBN:" clean-isbn "&format=json&jscmd=data")]
    (write-log! (str "📡 [Open Library API] Request started with payload/ISBN: " clean-isbn " (URL: " url ")"))
    (try
      (let [resp (http/get url {:headers {"User-Agent" "Librarian-Babashka/1.0"}})
            body (json/parse-string (:body resp))
            book-info (get body (str "ISBN:" clean-isbn))]
        (if book-info
          (let [authors (get book-info "authors")
                author-names (map #(get % "name") authors)
                author (if (seq author-names) (str/join ", " author-names) "Unknown Author")
                title (get book-info "title")
                pub-date (get book-info "publish_date")
                year (and pub-date (re-find #"\d{4}" pub-date))
                subjects (get book-info "subjects")
                genre (if (seq subjects)
                        (or (get (first subjects) "name") "General Study")
                        "General Study")]
            (write-log! (str "✅ [Open Library API] Request completed for ISBN: " clean-isbn " - Title: " title " - Author: " author))
            {:author (or (and (not (str/blank? author)) author) "Unknown Author")
             :title (or title "Unknown Title")
             :year (if year (Integer/parseInt year) nil)
             :genre genre
             :isbn clean-isbn
             :confidence 100
             :notes "Matched from Open Library Books API using Clojure Babashka Client."})
          (do
            (write-log! (str "⚠️ [Open Library API] Request finished empty for ISBN: " clean-isbn))
            nil)))
      (catch Exception e
        (write-log! (str "❌ [Open Library API] Request failed for ISBN: " clean-isbn " - Error: " (.getMessage e)))
        nil))))

(defn query-book-metadata [isbn]
  (let [clean-isbn (str/replace isbn #"\D" "")]
    (or (query-google-books clean-isbn)
        (query-open-library clean-isbn))))

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
  (let [config (load-config)
        api-key (or (:geminiApiKey config) (System/getenv "GEMINI_API_KEY"))]
    (if (str/blank? api-key)
      (throw (Exception. "GEMINI_API_KEY environment variable or settings configuration is required."))
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
        isbn-only? (:isbnOnlyRequests config)
        isbn-match (extract-valid-isbn ocr-text)
        metadata (cond
                   (and isbn-match (not (str/blank? isbn-match)))
                   (query-book-metadata isbn-match)

                   isbn-only?
                   nil

                   :else
                   (try (extract-via-gemini ocr-text gemini-model)
                        (catch Exception e
                          (println "⚠️ Gemini extraction failure: " (.getMessage e))
                          nil)))]
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
        {:status "completed" :meta metadata :destination final-dest :ocr ocr-text})
      (do
        (println "❌ Metadata extraction did not meet confidence threshold.")
        {:status "low_confidence" :reason "Low confidence" :ocr ocr-text}))))

(defn get-cached-status [state path]
  (let [scanned (or (:scanned_books state) [])]
    (:status (first (filter #(= (:filepath %) path) scanned)))))

(defn update-state-with-result [state path res]
  (let [filename (.getName (io/file path))
        timestamp (str (java.time.Instant/now))
        status (:status res)
        meta (:meta res)
        ocr-text (or (:ocr res) "")
        isbn (or (:isbn meta) (extract-valid-isbn ocr-text))
        
        ;; 1. Update scanned_books
        scanned (or (:scanned_books state) [])
        scanned-idx (first (keep-indexed (fn [idx item] (when (= (:filepath item) path) idx)) scanned))
        new-scanned-item {:filepath path
                          :filename filename
                          :isbn_detected (if (or (nil? isbn) (= isbn "null") (= isbn "None")) nil isbn)
                          :status status
                          :timestamp timestamp}
        new-scanned (if scanned-idx
                      (assoc scanned scanned-idx new-scanned-item)
                      (conj scanned new-scanned-item))
                      
        ;; 2. Update ai_categorization (if we have OCR text or meta)
        ai-cat (or (:ai_categorization state) [])
        ai-idx (first (keep-indexed (fn [idx item] (when (= (:filepath item) path) idx)) ai-cat))
        new-ai-item (when-not (str/blank? ocr-text)
                      {:filepath path
                       :text_preview (subs ocr-text 0 (min (count ocr-text) 500))
                       :status "completed"
                       :timestamp timestamp})
        new-ai-cat (if new-ai-item
                     (if ai-idx
                       (assoc ai-cat ai-idx new-ai-item)
                       (conj ai-cat new-ai-item))
                     ai-cat)
                     
        ;; 3. Update file_organization (if metadata is resolved successfully)
        file-org (or (:file_organization state) [])
        org-idx (first (keep-indexed (fn [idx item] (when (= (:filepath item) path) idx)) file-org))
        new-org-item (when (and (= status "completed") meta)
                       {:filepath path
                        :dest_path (:destination res)
                        :author (:author meta)
                        :title (:title meta)
                        :year (:year meta)
                        :genre (:genre meta)
                        :isbn (or (:isbn meta) "null")
                        :confidence (:confidence meta)
                        :status "completed"
                        :notes (:notes meta)
                        :timestamp timestamp})
        new-file-org (if new-org-item
                       (if org-idx
                         (assoc file-org org-idx new-org-item)
                         (conj file-org new-org-item))
                       file-org)

        ;; 4. Update isbn_requests (if an ISBN was detected and searched)
        isbn-reqs (or (:isbn_requests state) [])
        req-idx (first (keep-indexed (fn [idx item] (when (= (:filepath item) path) idx)) isbn-reqs))
        new-req-item (when (and isbn (not (str/blank? isbn)))
                       {:filepath path
                        :isbn isbn
                        :status (if (and meta (not= (:isbn meta) "No ISBN")) "completed" "failed")
                        :timestamp timestamp})
        new-isbn-reqs (if new-req-item
                        (if req-idx
                          (assoc isbn-reqs req-idx new-req-item)
                          (conj isbn-reqs new-req-item))
                        isbn-reqs)]
    {:scanned_books new-scanned
     :isbn_requests new-isbn-reqs
     :ai_categorization new-ai-cat
     :file_organization new-file-org}))

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
                      (mapcat #(.listFiles (io/file %)) resolved-inputs))
        files-count (count files)
        first-three-names (map #(.getName %) (take 3 files))]
    (write-log! (str "🔍 Library source scan started. Total files detected to sort: " files-count
                     ". First files queue: " (str/join ", " first-three-names)))
    (doseq [file files]
      (let [path (.getAbsolutePath file)]
        (let [cached-status (get-cached-status state path)]
          (if (and enable-caching? (and cached-status (or (= cached-status "completed") (= cached-status "failed") (= cached-status "low_confidence"))))
            (println "⏭️ Skipping cached file: " path)
            (let [res (process-book path config)
                  new-state (update-state-with-result state path res)]
              (save-state! new-state))))))
    (write-log! "✅ [Librarian] Library scanning successfully completed.")))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
