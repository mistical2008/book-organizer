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

(defn is-user-active? [uname]
  (try
    (if (or (str/blank? uname) (= uname "root"))
      false
      (let [actives (set (keep identity
                               (concat [(active-loginctl-user) (active-who-user)]
                                       (who-names)
                                       (loginctl-names)
                                       (active-run-user-names))))]
        (contains? actives uname)))
    (catch Exception _ false)))

(defn get-current-os-user []
  (let [proc-user (System/getProperty "user.name")
        sudo-user (System/getenv "SUDO_USER")
        env-user (System/getenv "USER")
        logname-user (System/getenv "LOGNAME")
        ;; Determine order of preference
        direct-claims (filter filter-user-candidate [proc-user sudo-user env-user logname-user])
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

(defn check-pause-and-wait! []
  (let [initial-paused? (:scanPaused (load-config))]
    (when initial-paused?
      (write-log! "⏸️ [Librarian] Scanning has been PAUSED. Waiting for resume..."))
    (loop [was-paused? initial-paused?]
      (let [paused? (:scanPaused (load-config))]
        (if paused?
          (do
            (when-not was-paused?
              (write-log! "⏸️ [Librarian] Scanning has been PAUSED. Waiting for resume..."))
            (Thread/sleep 1500)
            (recur true))
          (when was-paused?
            (write-log! "▶️ [Librarian] Scanning RESUMED.")))))))

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
    ;; 1. Accurate match with prefix (handles arbitrary spacing like ' - ' between digits)
    (let [prefix-pattern #"(?i)(?:[I1l|іІ!\[\]][S58sЅѕ][B8bВв][NnНнМм])(?:[- ]*1[03])?:?\s*([0-9Xx](?:\s*[- ]\s*[0-9Xx]|[0-9Xx]){9,18})"
          prefix-matches (re-seq prefix-pattern text)
          prefix-candidates (map #(str/replace (second %) #"[^0-9Xx]" "") prefix-matches)
          valid-prefix (first (filter valid-isbn? prefix-candidates))]
      (if (seq valid-prefix)
        valid-prefix
        ;; 2. Fallback to raw numeric sequences
        (let [fallback-pattern #"\b[0-9Xx](?:\s*[- ]\s*[0-9Xx]|[0-9Xx]){9,18}\b"
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

(def tesseract-paths
  ["tesseract"
   "/run/current-system/sw/bin/tesseract"
   "/nix/var/nix/profiles/default/bin/tesseract"
   "/usr/bin/tesseract"
   "/usr/local/bin/tesseract"])

(defn find-valid-tesseract []
  (first (filter (fn [p]
                   (try
                     (let [res (sh p "--version")]
                       (zero? (:exit res)))
                     (catch Exception _ false)))
                 tesseract-paths)))

(defn tesseract-available? []
  (boolean (find-valid-tesseract)))

(def pdftotext-paths
  ["pdftotext"
   "/run/current-system/sw/bin/pdftotext"
   "/nix/var/nix/profiles/default/bin/pdftotext"
   "/usr/bin/pdftotext"
   "/usr/local/bin/pdftotext"])

(defn find-valid-pdftotext []
  (first (filter (fn [p]
                   (try
                     (sh p "-v")
                     true
                     (catch Exception _ false)))
                 pdftotext-paths)))

(def pdftoppm-paths
  ["pdftoppm"
   "/run/current-system/sw/bin/pdftoppm"
   "/nix/var/nix/profiles/default/bin/pdftoppm"
   "/usr/bin/pdftoppm"
   "/usr/local/bin/pdftoppm"])

(defn find-valid-pdftoppm []
  (first (filter (fn [p]
                   (try
                     (sh p "-v")
                     true
                     (catch Exception _ false)))
                 pdftoppm-paths)))

(def djvutxt-paths
  ["djvutxt"
   "/run/current-system/sw/bin/djvutxt"
   "/nix/var/nix/profiles/default/bin/djvutxt"
   "/usr/bin/djvutxt"
   "/usr/local/bin/djvutxt"])

(defn find-valid-djvutxt []
  (first (filter (fn [p]
                   (try
                     (sh p "--help")
                     true
                     (catch Exception _ false)))
                 djvutxt-paths)))

(def ddjvu-paths
  ["ddjvu"
   "/run/current-system/sw/bin/ddjvu"
   "/nix/var/nix/profiles/default/bin/ddjvu"
   "/usr/bin/ddjvu"
   "/usr/local/bin/ddjvu"])

(defn find-valid-ddjvu []
  (first (filter (fn [p]
                   (try
                     (sh p "--help")
                     true
                     (catch Exception _ false)))
                 ddjvu-paths)))

(defn find-generated-page-files [prefix]
  (let [f (io/file prefix)
        parent (.getParentFile f)
        base-name (.getName f)
        all-files (if (and parent (.exists parent)) (.listFiles parent) [])]
    (->> all-files
         (filter (fn [file]
                   (let [name (.getName file)]
                     (and (str/starts-with? name base-name)
                          (or (str/ends-with? (str/lower-case name) ".png")
                              (str/ends-with? (str/lower-case name) ".tiff")
                              (str/ends-with? (str/lower-case name) ".tif")
                              (str/ends-with? (str/lower-case name) ".pnm")
                              (str/ends-with? (str/lower-case name) ".ppm")
                              (str/ends-with? (str/lower-case name) ".pgm")
                              (str/ends-with? (str/lower-case name) ".pbm"))))))
         (sort-by #(.getName %)))))

(defn get-supported-ocr-langs [bin]
  (try
    (let [res (sh bin "--list-langs")]
      (if (zero? (:exit res))
        (let [lines (str/split-lines (:out res))
              available (->> lines
                             (map str/trim)
                             (filter #(re-matches #"[a-zA-Z_]+" %))
                             set)
              target-langs ["eng" "ukr" "srp" "srp_latn"]
              supported (filter available target-langs)]
          (if (seq supported)
            (str/join "+" supported)
            "eng"))
        "eng"))
    (catch Exception _
      "eng")))

(defn ocr-single-image [img-path]
  (try
    (let [bin (or (find-valid-tesseract) "tesseract")]
      (println "📝 [Librarian] OCR page image (" bin "): " img-path)
      (let [output-base (str img-path "-tmp-txt")
            langs (get-supported-ocr-langs bin)
            _ (println "📝 [Librarian OCR] Running Tesseract with supported languages:" langs)
            result (sh bin img-path output-base "-l" langs)]
        (if (zero? (:exit result))
          (let [txt-file (io/file (str output-base ".txt"))
                txt-content (if (.exists txt-file) (slurp txt-file) "")]
            (when (.exists txt-file) (io/delete-file txt-file true))
            txt-content)
          (do
            (write-log! (str "⚠️ [Tesseract OCR] Execution failed on page image: " img-path " - Exit: " (:exit result) " - Err: " (:err result)))
            ""))))
    (catch Exception e
      (write-log! (str "⚠️ [Tesseract OCR] Process crashed on page image: " img-path " - Error: " (.getMessage e)))
      "")))

(defn ocr-pdf-pages [file-path]
  (try
    (let [pdftoppm (or (find-valid-pdftoppm) "pdftoppm")
          temp-prefix (str file-path "-page")
          _ (write-log! (str "📝 [Librarian] Rendering PDF pages to temporary images with: " pdftoppm))
          ;; Run pdftoppm to convert pages 1-10 to png
          res (sh pdftoppm "-png" "-f" "1" "-l" "10" file-path temp-prefix)]
      (if (zero? (:exit res))
        (let [files (find-generated-page-files temp-prefix)
              texts (doall (map (fn [f]
                                  (let [txt (ocr-single-image (.getAbsolutePath f))]
                                    (io/delete-file f true)
                                    txt))
                                files))]
          (let [all-text (str/join "\n" texts)]
            (if (str/blank? (str/trim all-text))
              (do
                (write-log! (str "⚠️ [Librarian OCR] PDf rendered but Tesseract returned zero text on " (count files) " pages."))
                "")
              all-text)))
        (do
          (write-log! (str "⚠️ [Librarian OCR] pdftoppm execution failed with exit code: " (:exit res) " Error: " (:err res)))
          "")))
    (catch Exception e
      (write-log! (str "⚠️ [Librarian OCR] PDF page rendering crashed: " (.getMessage e)))
      "")))

(defn extract-djvu-text [file-path]
  (try
    (let [bin (or (find-valid-djvutxt) "djvutxt")]
      (println "📖 [Librarian] Extracting text from DJVU (" bin "):" file-path)
      ;; Try page restricted text first, or fall back to full if that fails
      (let [result (sh bin "-page=1-10" file-path "-")]
        (if (zero? (:exit result))
          (:out result)
          (let [res2 (sh bin file-path "-")]
            (if (zero? (:exit res2))
              (:out res2)
              "")))))
    (catch Exception e
      (println "⚠️ DJVU text extraction failed:" (.getMessage e))
      "")))

(defn ocr-djvu-pages [file-path]
  (try
    (let [ddjvu (or (find-valid-ddjvu) "ddjvu")
          temp-prefix (str file-path "-page")
          _ (write-log! (str "📝 [Librarian] Rendering DjVu pages to temporary images with: " ddjvu))
          ;; Try TIFF first (Tesseract's native and highly preferred format)
          res-tiff (sh ddjvu "-format=tiff" "-page=1-10" file-path (str temp-prefix "-%d.tiff"))
          res (if (zero? (:exit res-tiff))
                {:exit 0 :ext "tiff"}
                (do
                  (write-log! "⚠️ [Librarian OCR] ddjvu TIFF rendering failed or not supported. Trying PPM format...")
                  (let [res-ppm (sh ddjvu "-format=ppm" "-page=1-10" file-path (str temp-prefix "-%d.ppm"))]
                    (if (zero? (:exit res-ppm))
                      {:exit 0 :ext "ppm"}
                      (do
                        (write-log! "⚠️ [Librarian OCR] ddjvu PPM rendering failed. Trying PBM format...")
                        (let [res-pbm (sh ddjvu "-format=pbm" "-page=1-10" file-path (str temp-prefix "-%d.pbm"))]
                          (if (zero? (:exit res-pbm))
                            {:exit 0 :ext "pbm"}
                            (do
                              (write-log! "⚠️ [Librarian OCR] ddjvu PBM rendering failed. Trying PGM format...")
                              (let [res-pgm (sh ddjvu "-format=pgm" "-page=1-10" file-path (str temp-prefix "-%d.pgm"))]
                                (if (zero? (:exit res-pgm))
                                  {:exit 0 :ext "pgm"}
                                  {:exit (:exit res-pgm) :err (:err res-pgm)}))))))))))]
      (if (zero? (:exit res))
        (let [files (find-generated-page-files temp-prefix)
              texts (doall (map (fn [f]
                                  (let [txt (ocr-single-image (.getAbsolutePath f))]
                                    (io/delete-file f true)
                                    txt))
                                files))]
          (let [all-text (str/join "\n" texts)]
            (if (str/blank? (str/trim all-text))
              (do
                (write-log! (str "⚠️ [Librarian OCR] DjVu rendered but Tesseract returned zero text on " (count files) " pages."))
                "")
              all-text)))
        (do
          (write-log! (str "⚠️ [Librarian OCR] ddjvu execution failed or no formats worked. Error: " (:err res)))
          "")))
    (catch Exception e
      (write-log! (str "⚠️ [Librarian OCR] DjVu page rendering crashed: " (.getMessage e)))
      "")))

(defn query-book-metadata
  ([isbn]
   (query-book-metadata isbn nil))
  ([isbn state]
   (let [clean-isbn (str/replace isbn #"\D" "")
         state-resolved (or state (load-state))
         cache (or (:batch_isbn_cache state-resolved) {})]
     (or (get cache (keyword clean-isbn))
         (get cache clean-isbn)
         (query-google-books clean-isbn)
         (query-open-library clean-isbn)))))

(defn run-ocr [file-path]
  (try
    (let [bin (or (find-valid-tesseract) "tesseract")]
      (println "📝 [Librarian] Translating layout using local tesseract CLI (" bin "): " file-path)
      (let [output-base (str file-path "-tmp-txt")
            result (sh bin file-path output-base "-l" "eng+ukr+srp+srp_latn")]
        (if (zero? (:exit result))
          (let [txt-file (io/file (str output-base ".txt"))
                txt-content (slurp txt-file)]
            (io/delete-file txt-file true)
            txt-content)
          "")))
    (catch Exception e
      (println "⚠️ OCR execution failed (tesseract probably missing):" (.getMessage e))
      "")))

(defn parse-safe-int [v default-val]
  (cond
    (nil? v) default-val
    (number? v) (let [d (double v)]
                  (if (and (<= d 1.0) (> d 0.0))
                    (int (* d 100))
                    (int d)))
    (string? v) (try
                  (if-let [dot-num (re-find #"\d+\.\d+" v)]
                    (let [d (Double/parseDouble dot-num)]
                      (if (and (<= d 1.0) (> d 0.0))
                        (int (* d 100))
                        (int d)))
                    (if-let [first-num (re-find #"\d+" v)]
                      (Integer/parseInt first-num)
                      default-val))
                  (catch Exception _ default-val))
    :else default-val))

(defn extract-json-string [s]
  (if (str/blank? s)
    ""
    (if-let [json-match (re-find #"(?s)\{.*\}" s)]
      json-match
      s)))

(defn normalize-metadata [meta]
  (when meta
    (let [raw-conf (:confidence meta)
          rec-conf (parse-safe-int raw-conf 0)
          raw-year (:year meta)
          rec-year (parse-safe-int raw-year nil)]
      (assoc meta
             :confidence rec-conf
             :year (if (and rec-year (> rec-year 0)) rec-year nil)))))

(defn extract-via-gemini [text filename gemini-model]
  (let [config (load-config)
        api-key (or (:geminiApiKey config) (System/getenv "GEMINI_API_KEY"))]
    (if (str/blank? api-key)
      (throw (Exception. "GEMINI_API_KEY environment variable or settings configuration is required."))
      (let [url (str "https://generativelanguage.googleapis.com/v1beta/models/" (or gemini-model "gemini-3.5-flash") ":generateContent?key=" api-key)
            system-prompt "Act as the Librarian Library Metadata Agent. Analyze the provided book filename and extracted raw text to catalog the book. Extract the following metadata fields:
- author (string: 'Lastname, Firstname' if possible or multiple authors separated by commas)
- title (string: capitalized, clean and descriptive title)
- year (integer: 4-digit publication year or 0 if unknown)
- genre (string: core subject classification or category)
- isbn (string: 10 or 13 digit number, or empty string if not found)
- confidence (integer: a percentage score between 0 and 100 representing your confidence in this classification)
- notes (string: short rationale for your classification)"
            text-safe (or text "")
            combined-text (str "Book Filename: " filename "\n\nExtracted content preview (OCR/Text):\n" (subs text-safe 0 (min (count text-safe) 4000)))
            payload {:contents [{:parts [{:text combined-text}]}]
                     :systemInstruction {:parts [{:text system-prompt}]}
                     :generationConfig {:responseMimeType "application/json"
                                        :responseSchema {:type "OBJECT"
                                                         :properties {:author {:type "STRING"}
                                                                      :title {:type "STRING"}
                                                                      :year {:type "INTEGER"}
                                                                      :genre {:type "STRING"}
                                                                      :isbn {:type "STRING"}
                                                                      :confidence {:type "INTEGER"}
                                                                      :notes {:type "STRING"}}
                                                         :required ["author" "title" "year" "genre" "isbn" "confidence" "notes"]}}}]
        (loop [attempt 1
               delay-ms 2000]
          (let [res (try
                      (let [resp (http/post url {:headers {"Content-Type" "application/json"}
                                                 :body (json/generate-string payload)})
                            body (json/parse-string (:body resp) true)]
                        (if-let [err (:error body)]
                          (throw (Exception. (str "Gemini API Error: " (:message err))))
                          (let [text-response (-> body :candidates first :content :parts first :text)
                                _ (when (str/blank? text-response)
                                    (throw (Exception. "Gemini API returned an empty response.")))
                                cleaned-json (extract-json-string text-response)
                                parsed (json/parse-string cleaned-json true)]
                            {:ok (normalize-metadata parsed)})))
                      (catch Exception e
                        (let [msg (.getMessage e)]
                          (if (and (< attempt 5)
                                   (or (nil? msg)
                                       (re-find #"429" msg)
                                       (re-find #"503" msg)
                                       (re-find #"504" msg)
                                       (re-find #"status code: 42" msg)
                                       (re-find #"timeout" (str/lower-case msg))))
                            (do
                              (write-log! (str "⚠️ [Librarian] Gemini API transient rate-limit/network hit (Attempt " attempt "/5: " (or msg "unknown exception") "). Retrying in " delay-ms " ms..."))
                              (Thread/sleep delay-ms)
                              nil)
                            (throw e)))))]
            (if res
              (:ok res)
              (recur (inc attempt) (* delay-ms 2)))))))))

(defn sanitize [s]
  (if (nil? s)
    ""
    (let [cleaned (str/replace (str/trim s) #"[/\\?%*:|\"<>\s\n\r]+" " ")]
      (if (> (count cleaned) 100)
        (str (subs cleaned 0 100) "...")
        cleaned))))

(defn compute-destination [meta template]
  (let [author (or (:author meta) "Unknown Author")
        title (or (:title meta) "Unknown Title")
        year (let [y (:year meta)
                   y-int (parse-safe-int y 0)]
               (if (pos? y-int) (str y-int) "Unknown Year"))
        genre (or (:genre meta) "Uncategorized")
        isbn (or (:isbn meta) "No ISBN")
        interpolated (-> template
                         (str/replace "{Author}" author)
                         (str/replace "{Title}" title)
                         (str/replace "{Year}" year)
                         (str/replace "{Genre}" genre)
                         (str/replace "{ISBN}" isbn))
        segments (str/split interpolated #"[/\\\\]+")
        sanitized-segments (map sanitize segments)]
    (str/join "/" sanitized-segments)))

(defn markup-file? [file-path]
  (let [filename (str/lower-case (.getName (io/file file-path)))]
    (or (str/ends-with? filename ".epub")
        (str/ends-with? filename ".fb2"))))

(defn extract-epub-text [file-path]
  (try
    (let [f (io/file file-path)]
      (if (.exists f)
        (with-open [zip (java.util.zip.ZipFile. f)]
          (let [entries (enumeration-seq (.entries zip))
                html-entries (->> entries
                                  (filter (fn [entry]
                                            (let [name (str/lower-case (.getName entry))]
                                              (and (not (.isDirectory entry))
                                                   (or (str/ends-with? name ".html")
                                                       (str/ends-with? name ".xhtml")
                                                       (str/ends-with? name ".xml")
                                                       (str/ends-with? name ".htm"))))))
                                  (sort-by #(.getName %))
                                  (take 30))]
            (loop [es html-entries
                   acc []
                   total-chars 0]
              (if (or (empty? es) (> total-chars 250000))
                (str/join "\n" acc)
                (let [entry (first es)
                      content (try
                                (with-open [is (.getInputStream zip entry)]
                                  (let [text (slurp is :encoding "UTF-8")]
                                    (str/replace text #"<[^>]+>" " ")))
                                (catch Exception _ ""))]
                  (recur (rest es)
                         (conj acc content)
                         (+ total-chars (count content))))))))
        ""))
    (catch Exception e
      (println "⚠️ Failed to extract EPUB text in Clojure: " (.getMessage e))
      "")))

(defn extract-fb2-text [file-path]
  (try
    (let [file (io/file file-path)
          lower-name (str/lower-case (.getName file))]
      (if (.exists file)
        (if (str/ends-with? lower-name ".zip")
          (with-open [zip (java.util.zip.ZipFile. file)]
            (let [entries (enumeration-seq (.entries zip))
                  fb2-entry (first (filter #(str/ends-with? (str/lower-case (.getName %)) ".fb2") entries))]
              (if fb2-entry
                (with-open [is (.getInputStream zip fb2-entry)]
                  (-> (slurp is :encoding "UTF-8")
                      (str/replace #"<[^>]+>" " ")
                      str/trim))
                "")))
          (let [content (slurp file-path :encoding "UTF-8")]
            (-> content
                (str/replace #"<[^>]+>" " ")
                str/trim)))
        ""))
    (catch Exception e
      (println "⚠️ Failed to extract FB2 text in Clojure: " (.getMessage e))
      "")))

(defn extract-pdf-text [file-path]
  (try
    (let [bin (or (find-valid-pdftotext) "pdftotext")]
      (println "📖 [Librarian] Extracting text from PDF (" bin "):" file-path)
      (let [result (sh bin file-path "-")]
        (if (zero? (:exit result))
          (:out result)
          "")))
    (catch Exception e
      (println "⚠️ PDF text extraction failed: " (.getMessage e))
      "")))

(defonce book-text-cache (atom {}))

(defn extract-book-text-with-status [file-path]
  (if-let [cached (get @book-text-cache file-path)]
    cached
    (let [res (let [filename (.getName (io/file file-path))
                    ext (str/lower-case (some-> (re-find #"\.([^.]+)$" filename) second))]
                (cond
                  (or (= ext "epub") (= ext "fb2"))
                  {:text (if (= ext "epub") (extract-epub-text file-path) (extract-fb2-text file-path))
                   :ocr-status "not_required"}

                  (= ext "pdf")
                  (let [extracted (extract-pdf-text file-path)]
                    (if (and (not (str/blank? extracted)) (> (count (str/trim extracted)) 50))
                      (do
                        (write-log! (str "ℹ️ Digital PDF detected: '" filename "'. Using raw text extraction (Native)."))
                        {:text extracted :ocr-status "not_required"})
                      (do
                        (write-log! (str "ℹ️ Scanned PDF detected or empty native text: '" filename "'. Falling back to local OCR scanning."))
                        (let [ocr-res (ocr-pdf-pages file-path)]
                          (if (and (not (str/blank? ocr-res)) (> (count (str/trim ocr-res)) 10))
                            (do
                              (write-log! (str "✅ Local OCR reading succeeded on PDF: '" filename "'."))
                              {:text ocr-res :ocr-status "success"})
                            (do
                              (write-log! (str "❌ Local OCR reading FAILED on PDF: '" filename "'."))
                              {:text ocr-res :ocr-status "failed"}))))))

                  (= ext "djvu")
                  (let [extracted (extract-djvu-text file-path)]
                    (if (and (not (str/blank? extracted)) (> (count (str/trim extracted)) 50))
                      (do
                        (write-log! (str "ℹ️ Digital DJVU detected: '" filename "'. Using raw text extraction (Native)."))
                        {:text extracted :ocr-status "not_required"})
                      (do
                        (write-log! (str "ℹ️ Scanned DJVU detected or empty native text: '" filename "'. Falling back to local ddjvu OCR scanning."))
                        (let [ocr-res (ocr-djvu-pages file-path)]
                          (if (and (not (str/blank? ocr-res)) (> (count (str/trim ocr-res)) 10))
                            (do
                              (write-log! (str "✅ Local OCR reading succeeded on DJVU: '" filename "'."))
                              {:text ocr-res :ocr-status "success"})
                            (do
                              (write-log! (str "❌ Local OCR reading FAILED on DJVU: '" filename "'."))
                              {:text ocr-res :ocr-status "failed"}))))))

                  :else
                  (let [ocr-res (run-ocr file-path)]
                    (if (and (not (str/blank? ocr-res)) (> (count (str/trim ocr-res)) 10))
                      (do
                        (write-log! (str "✅ Local OCR reading succeeded on default parser file: '" filename "'."))
                        {:text ocr-res :ocr-status "success"})
                      (do
                        (write-log! (str "❌ Local OCR reading FAILED on default parser file: '" filename "'."))
                        {:text ocr-res :ocr-status "failed"})))))]
      (swap! book-text-cache assoc file-path res)
      res)))

(defn extract-book-text [file-path]
  (:text (extract-book-text-with-status file-path)))

(defn chown-to-logged-user! [path]
  (let [config (load-config)
        config-user (:userName config)
        detected-user (get-current-os-user)
        user (if (and (not (str/blank? config-user)) (not= config-user "root"))
               config-user
               detected-user)]
    (when (and (not (str/blank? user)) (not= user "root"))
      (try
        (let [path-str (if (instance? java.io.File path) (.getAbsolutePath path) (str path))
              res (sh "chown" "-R" (str user ":" user) path-str)]
          (if (= 0 (:exit res))
            (write-log! (str "🔑 [Librarian Permission] Successfully changed ownership of " path-str " to " user ":" user))
            (let [res2 (sh "chown" "-R" user path-str)]
              (if (= 0 (:exit res2))
                (write-log! (str "🔑 [Librarian Permission] Successfully changed ownership of " path-str " to " user))
                (write-log! (str "⚠️ [Librarian Permission] Failed to chown " path-str " to " user ": " (:err res2)))))))
        (catch Exception e
          (write-log! (str "⚠️ [Librarian Permission] Error changing ownership on " path ": " (.getMessage e))))))))

(defn process-book [file-path config]
  (let [filename (.getName (io/file file-path))
        _ (write-log! (str "📖 [Librarian] Processing publication: " filename))
        extracted-res (extract-book-text-with-status file-path)
        ocr-text (:text extracted-res)
        ocr-status (:ocr-status extracted-res)
        confidence-threshold (:confidenceThreshold config 70)
        output-dir (:outputDir config "/data/sorted_library")
        destination-template (:destinationTemplate config "{Author} - {Title} ({Year})")
        gemini-model (:geminiModel config "gemini-3.5-flash")
        auto-cleanup? (:autoCleanup config)
        isbn-only? (:isbnOnlyRequests config)
        isbn-match (or (extract-valid-isbn ocr-text)
                       (extract-valid-isbn filename))
        _ (when (and isbn-match (not (str/blank? isbn-match)))
            (write-log! (str "🔍 [Librarian] Detected ISBN '" isbn-match "' for publication '" filename "'")))
        metadata-or-error (let [api-meta (when (and isbn-match (not (str/blank? isbn-match)))
                                  (query-book-metadata isbn-match))]
                           (cond
                             api-meta
                             {:status "success" :meta api-meta}

                             isbn-only?
                             (do
                               (if (and isbn-match (not (str/blank? isbn-match)))
                                 (write-log! (str "⚠️ [Librarian] ISBN '" isbn-match "' detected for '" filename "' but API query produced no metadata. Skipping fallback as isbnOnlyRequests is active."))
                                 (write-log! (str "ℹ️ [Librarian] Skipping non-ISBN classification for '" filename "' (ISBN-only mode active)")))
                               {:status "low_confidence" :reason "ISBN-only mode and no metadata found"})

                             :else
                             (try
                               (write-log! (str "🤖 [Librarian] Running Gemini AI classification fallback for: " filename))
                               {:status "success" :meta (extract-via-gemini ocr-text filename gemini-model)}
                               (catch Exception e
                                 (write-log! (str "⚠️ [Librarian] Gemini AI classification failed for '" filename "': " (.getMessage e)))
                                 {:status "failed" :reason (.getMessage e)}))))]
    (cond
      (= (:status metadata-or-error) "success")
      (let [metadata (:meta metadata-or-error)]
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
                final-dest (str category-folder "/" (sanitize file-base-name) ext)
                
                ;; Determine first new directory prior to actual creation for ownership adjustment
                all-path-levels (reductions (fn [acc segment] (.getAbsolutePath (io/file acc segment)))
                                            resolved-out-dir
                                            (concat sub-dirs [file-folder]))
                first-new-dir (first (filter #(not (.exists (io/file %))) all-path-levels))]
            (write-log! (str "✅ [Librarian] Successfully classified '" filename "' -> '" dest-name "' (Confidence: " (:confidence metadata) "%)"))
            (println "🚚 Relocating to: " final-dest)
            (io/make-parents final-dest)
            (io/copy (io/file file-path) (io/file final-dest))
            
            ;; Change ownership to the resolved logged-in user
            (when first-new-dir
              (chown-to-logged-user! first-new-dir))
            (chown-to-logged-user! final-dest)
            
            (when auto-cleanup?
              (io/delete-file (io/file file-path) true))
            {:status "completed" :meta metadata :destination final-dest :ocr ocr-text :ocr-status ocr-status})
          (do
            (let [conf (or (and metadata (:confidence metadata)) 0)]
              (write-log! (str "⚠️ [Librarian] Classification for '" filename "' fell below confidence threshold (Threshold: " confidence-threshold "% | Got: " conf "%)")))
            {:status "low_confidence" :reason "Low confidence" :ocr ocr-text :ocr-status ocr-status})))

      (= (:status metadata-or-error) "low_confidence")
      {:status "low_confidence" :reason (:reason metadata-or-error) :ocr ocr-text :ocr-status ocr-status}

      :else
      {:status "failed" :reason (:reason metadata-or-error) :ocr ocr-text :ocr-status ocr-status})))

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
                          :ocr_status (:ocr-status res)
                          :reason (if (= status "completed") nil (get res :reason "Low confidence classification or missing metadata"))
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

(defn- scan-chunk-files [chunk-files]
  (keep (fn [file]
          (check-pause-and-wait!)
          (let [path (.getAbsolutePath file)
                filename (.getName file)
                ;; Detect ISBN (tries filename first, then text/OCR extraction)
                isbn (or (extract-valid-isbn filename)
                         (let [res (extract-book-text-with-status path)]
                           (extract-valid-isbn (:text res))))]
            (when (and isbn (not (str/blank? isbn)))
              [path isbn])))
        chunk-files))

(defn pre-process-and-batch-isbn-lookups! [files config state]
  (let [enable-caching? (not= (:enableCaching config) false)
        ;; 1. Filter out files that are already completed/scanned
        unprocessed-files (filter (fn [file]
                                    (let [path (.getAbsolutePath file)
                                          status (get-cached-status state path)]
                                      (not (and enable-caching? 
                                                (or (= status "completed") 
                                                    (= status "low_confidence"))))))
                                  files)]
    (if (empty? unprocessed-files)
      state
      (let [chunk-size (or (:batchSize config) 12)
            file-chunks (partition-all chunk-size unprocessed-files)
            total-chunks (count file-chunks)]
        (write-log! (str "📦 [Batch Engine] Commencing ISBN scanning for " (count unprocessed-files) 
                         " unprocessed files in " total-chunks " chunks of " chunk-size " files..."))
        (let [first-scan-fut (future (scan-chunk-files (first file-chunks)))]
          (loop [remaining-chunks file-chunks
                 current-state state
                 chunk-idx 1
                 active-scan-fut first-scan-fut]
            (if-let [chunk-files (first remaining-chunks)]
              (do
                (check-pause-and-wait!)
                (let [path-isbn-pairs @active-scan-fut]
                  (write-log! (str "✅ [Batch Engine] Chunk scan success " chunk-idx "/" total-chunks))
                  (let [next-chunk (second remaining-chunks)
                        next-scan-fut (when next-chunk
                                        (write-log! (str "⚡ [Batch Engine] In parallel running next chunk scanning (" (inc chunk-idx) "/" total-chunks ")"))
                                        (future (scan-chunk-files next-chunk)))
                        unique-isbns (distinct (map second path-isbn-pairs))
                        existing-cache (or (:batch_isbn_cache current-state) {})
                        ;; Only fetch ISBNs not already resolved in cache
                        isbns-to-fetch (filter (fn [isbn]
                                                 (let [clean (str/replace isbn #"\D" "")]
                                                   (not (or (contains? existing-cache (keyword clean))
                                                            (contains? existing-cache clean)))))
                                               unique-isbns)]
                    (if (empty? isbns-to-fetch)
                      (do
                        (write-log! (str "📦 [Batch Engine] Make requests for the " chunk-idx "/" total-chunks " chunk"))
                        (write-log! (str "📦 [Batch Engine] Chunk " chunk-idx " of " total-chunks " - all detected ISBNs already in cache."))
                        (recur (rest remaining-chunks) current-state (inc chunk-idx) next-scan-fut))
                      (do
                        (write-log! (str "📡 [Batch Engine] Make requests for the " chunk-idx "/" total-chunks " chunk"))
                        (let [bibkeys (str/join "," (map #(str "ISBN:" (str/replace % #"\D" "")) isbns-to-fetch))
                              url (str "https://openlibrary.org/api/books?bibkeys=" bibkeys "&format=json&jscmd=data")
                              next-state 
                              (try
                                (let [resp (http/get url {:headers {"User-Agent" "Librarian-Babashka/1.0"}})
                                      body (json/parse-string (:body resp))
                                      parsed-chunk (into {} (keep (fn [isbn]
                                                                    (let [bibkey (str "ISBN:" (str/replace isbn #"\D" ""))]
                                                                      (when-let [book-info (get body bibkey)]
                                                                        (let [clean-isbn (str/replace bibkey #"ISBN:" "")
                                                                              authors (get book-info "authors")
                                                                              author-names (map #(get % "name") authors)
                                                                              author (if (seq author-names) (str/join ", " author-names) "Unknown Author")
                                                                              title (get book-info "title")
                                                                              pub-date (get book-info "publish_date")
                                                                              year (and pub-date (re-find #"\d{4}" pub-date))
                                                                              subjects (get book-info "subjects")
                                                                              genre (if (seq subjects)
                                                                                      (or (get (first subjects) "name") "General Study")
                                                                                      "General Study")
                                                                              meta {:author (or (and (not (str/blank? author)) author) "Unknown Author")
                                                                                    :title (or title "Unknown Title")
                                                                                    :year (if year (Integer/parseInt year) nil)
                                                                                    :genre genre
                                                                                    :isbn clean-isbn
                                                                                    :confidence 100
                                                                                    :notes "Matched from Batch Open Library Books API using Babashka."}]
                                                                          [clean-isbn meta]))))
                                                                  isbns-to-fetch))
                                      updated-cache (merge existing-cache parsed-chunk)
                                      new-s (assoc current-state :batch_isbn_cache updated-cache)]
                                  (write-log! (str "✅ [Batch Engine] Chunk " chunk-idx " successfully resolved " (count parsed-chunk) " books."))
                                  (save-state! new-s)
                                  new-s)
                                (catch Exception e
                                  (write-log! (str "❌ [Batch Engine] Chunk " chunk-idx " Open Library fetch failed: " (.getMessage e)))
                                  current-state))]
                          ;; Wait 1.5 seconds between batches to avoid Open Library rate limits
                          (Thread/sleep 1500)
                          (recur (rest remaining-chunks) next-state (inc chunk-idx) next-scan-fut)))))))
              current-state)))))))

(defn -main [& args]
  (println "================================================")
  (println "🤖 Librarian Clojure Babashka Daemon Live")
  (println "================================================")
  (let [config (load-config)
        state (load-state)
        input-dirs (or (:inputDirs config) ["/data/books_to_sort"])
        enable-caching? (not= (:enableCaching config) false)
        resolved-inputs (map resolve-path input-dirs)
        files (filter #(and (.isFile %) (re-find #"\.(pdf|epub|djvu|fb2)$" (.getName %)))
                      (mapcat (fn [d]
                                (let [f (io/file d)]
                                  (if (and (.exists f) (.isDirectory f))
                                    (.listFiles f)
                                    [])))
                              resolved-inputs))
        files-count (count files)
        first-three-names (map #(.getName %) (take 3 files))]
    (write-log! (str "🔍 Library source scan started. Resolved source path(s): " (str/join ", " resolved-inputs)
                     ". Total files detected: " files-count
                     ". First 3 files: " (if (empty? first-three-names) "None" (str/join ", " first-three-names))))
    (when-not (tesseract-available?)
      (write-log! "⚠️ [Librarian System] 'tesseract' CLI utility is not present. Local OCR text extraction from document scans is fallback-disabled. Filenames and Gemini-based mapping will be prioritized."))
    (let [pre-batched-state (pre-process-and-batch-isbn-lookups! files config state)
          final-state
          (loop [remaining-files files
                 current-state pre-batched-state
                 skipped-count 0]
            (if-let [file (first remaining-files)]
              (let [path (.getAbsolutePath file)
                    cached-status (get-cached-status current-state path)]
                (if (and enable-caching? (and cached-status (or (= cached-status "completed") (= cached-status "low_confidence"))))
                  (recur (rest remaining-files) current-state (inc skipped-count))
                  (do
                    (check-pause-and-wait!)
                    (let [_ (write-log! (str "📖 [Librarian] Processing single file: " (.getName file)))
                          res (try
                                (process-book path config)
                                (catch Exception e
                                  (write-log! (str "❌ Manual book scan failed: " (.getAbsolutePath file) " (" (.getMessage e) ")"))
                                  {:status "failed" :ocr-status "failed" :reason (.getMessage e)}))
                          new-state (update-state-with-result current-state path res)]
                       (save-state! new-state)
                       ;; Pace delay of 2.5 seconds to cool down between files and prevent rate-limits
                       (when (seq (rest remaining-files))
                         (write-log! "⏱️ [Librarian] Cooling down for 2.5 seconds to respect system API limits...")
                         (Thread/sleep 2500))
                       (recur (rest remaining-files) new-state skipped-count)))))
              (do
                (when (> skipped-count 0)
                  (write-log! (str "⏭️ [Librarian] Skipped " skipped-count " cached files (previously completed or low-confidence classified) to save API/system resources.")))
                current-state)))]
      (write-log! "✅ [Librarian] Library scanning successfully completed."))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
