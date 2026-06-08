(ns librarian.server
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell :refer [sh]]
            [org.httpkit.server :refer [run-server]]
            [librarian.core :as core]))

;; =============================================================================
;; Stateful Database & Configuration Registry
;; =============================================================================
(def data-dir (io/file "data"))
(def config-file (io/file "data/config.json"))
(def state-file (io/file "data/state.json"))
(def logs-file (io/file "data/logs.json"))

(defn init-filesystem! []
  (when-not (.exists data-dir) (.mkdirs data-dir))
  (when-not (.exists config-file)
    (spit config-file (json/generate-string
                        {:serviceName "librarian"
                         :userName "root"
                         :inputDirs ["/data/books_to_sort"]
                         :outputDir "/data/sorted_library"
                         :destinationTemplate "{Author} - {Title} ({Year})"
                         :geminiModel "gemini-3.5-flash"
                         :confidenceThreshold 70
                         :daemonEnabled false
                         :processingMode "batch"
                         :batchSize 5
                         :enableCaching true
                         :isbnOnlyRequests false
                         :googleBooksApiKey "AIzaSyDYh87ATtVXKn9rF55Plh-1mGJhWFmigU0"}
                        {:pretty true})))
  (when-not (.exists state-file)
    (spit state-file (json/generate-string
                       {:scanned_books []
                        :isbn_requests []
                        :ai_categorization []
                        :file_organization []}
                       {:pretty true}))))

;; =============================================================================
;; Utility & Log Managers
;; =============================================================================
(defn write-log! [msg]
  (let [entry (str "[" (java.time.Instant/now) "] " msg)
        current-logs (try (json/parse-string (slurp logs-file) true)
                          (catch Exception _ []))
        truncated-logs (take 200 (conj current-logs entry))]
    (spit logs-file (json/generate-string truncated-logs {:pretty true}))
    (println "[Librarian Clojure Server]" msg)))

;; =============================================================================
;; Core API Routing & Controller Handlers
;; =============================================================================
(defn json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"
             "Access-Control-Allow-Origin" "*"}
   :body (json/generate-string body)})

(defn handle-get-config [req]
  (init-filesystem!)
  (json-response 200 (json/parse-string (slurp config-file) true)))

(defn handle-post-config [req]
  (init-filesystem!)
  (try
    (let [new-config (json/parse-string (slurp (:body req)) true)]
      (write-log! (str "Received new config parameters count: " (count new-config) " Keys: " (keys new-config) " config: " (pr-str new-config)))
      (spit config-file (json/generate-string new-config {:pretty true}))
      (write-log! "Configuration successfully updated by Clojure Backend Server.")
      (json-response 200 {:status "ok" :config new-config}))
    (catch Exception e
      (json-response 400 {:status "error" :message (.getMessage e)}))))

(defn handle-get-state [req]
  (init-filesystem!)
  (json-response 200 (json/parse-string (slurp state-file) true)))

(defn handle-get-logs [req]
  (if (.exists logs-file)
    (json-response 200 (json/parse-string (slurp logs-file) true))
    (json-response 200 [])))

;; =============================================================================
;; Static Assets File Server
;; =============================================================================
(defn serve-static-file [uri]
  (let [clean-uri (if (= uri "/") "/index.html" uri)
        ;; Check in built 'dist' folder first, then raw root
        dist-file (io/file (str "dist" clean-uri))
        root-file (io/file (str "." clean-uri))
        f (cond
            (and (.exists dist-file) (.isFile dist-file)) dist-file
            (and (.exists root-file) (.isFile root-file)) root-file
            :else nil)]
    (if f
      (let [file-name (.getName f)
            ext (re-find #"\.[a-zA-Z0-9]+$" file-name)
            mime (cond
                   (= ext ".html") "text/html"
                   (= ext ".js") "application/javascript"
                   (= ext ".mjs") "application/javascript"
                   (= ext ".css") "text/css"
                   (= ext ".svg") "image/svg+xml"
                   (= ext ".png") "image/png"
                   :else "text/plain")]
        {:status 200
         :headers {"Content-Type" (str mime "; charset=utf-8")
                   "Access-Control-Allow-Origin" "*"}
         :body (slurp f)})
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body (str "Static asset '" uri "' not found in distribution profiles.")})))

(defn handle-post-scan [req]
  (try
    (write-log! "Manual book classification scan initiated from Sandbox interface...")
    (core/-main)
    (write-log! "Manual book classification scan successfully completed.")
    (json-response 200 {:status "ok" :message "Daemon sync cycle completed successfully!"})
    (catch Exception e
      (write-log! (str "Manual book scan failed: " (.getMessage e)))
      (json-response 500 {:status "error" :message (.getMessage e)}))))

(defn parse-query [query-str]
  (when query-str
    (into {}
          (for [part (str/split query-str #"&")
                :let [[k v] (str/split part #"=")]]
            [(keyword k) (java.net.URLDecoder/decode (or v "") "UTF-8")]))))

(defn handle-list-dirs [req]
  (try
    (let [query (parse-query (:query-string req))
          path (or (:path query) "/")
          dir (io/file path)]
      (if (and (.exists dir) (.isDirectory dir))
        (let [files (.listFiles dir)
              parent-dir (.getParent dir)
              dirs (->> files
                        (filter #(.isDirectory %))
                        (map (fn [f]
                               {:name (.getName f)
                                :path (.getCanonicalPath f)}))
                        (sort-by :name))]
          (json-response 200 {:status "ok"
                               :path (.getCanonicalPath dir)
                               :parent (when parent-dir (.getCanonicalPath (io/file parent-dir)))
                               :dirs dirs}))
        (json-response 400 {:status "error" :message "Not a directory or does not exist"})))
    (catch Exception e
      (json-response 500 {:status "error" :message (.getMessage e)}))))

;; =============================================================================
;; Ring-Compatible Request Router
;; =============================================================================
(defn app [req]
  (let [uri (:uri req)
        method (:request-method req)]
    (println (str "📥 Request: [" method "] " uri))
    (cond
      (and (= method :get) (= uri "/api/config")) (handle-get-config req)
      (and (= method :post) (= uri "/api/config")) (handle-post-config req)
      (and (= method :get) (= uri "/api/state")) (handle-get-state req)
      (and (= method :post) (= uri "/api/scan")) (handle-post-scan req)
      (and (= method :get) (= uri "/api/logs")) (handle-get-logs req)
      (and (= method :get) (= uri "/api/list-dirs")) (handle-list-dirs req)
      (= method :get) (serve-static-file uri)
      :else (json-response 404 {:status "not-found" :message "Endpoint undefined in Librarian Clojure API"}))))

;; =============================================================================
;; Entrypoint Bootstrapping
;; =============================================================================
(defn -main [& args]
  (init-filesystem!)
  (write-log! "Librarian Clojure-Ring Web Daemon booting on address port 3000...")
  (run-server app {:port 3000 :ip "0.0.0.0"})
  @(promise))
