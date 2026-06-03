(ns librarian.server
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell :refer [sh]]
            [org.httpkit.server :refer [run-server]]))

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
                         :inputDirs ["/var/lib/librarian/input"]
                         :outputDir "/var/lib/librarian/sorted"
                         :destinationTemplate "{Author} - {Title} ({Year})"
                         :geminiModel "gemini-3.5-flash"
                         :confidenceThreshold 70
                         :daemonEnabled false
                         :processingMode "batch"
                         :batchSize 5
                         :enableCaching true}
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
;; Ring-Compatible Request Router
;; =============================================================================
(defn app [req]
  (let [uri (:uri req)
        method (:request-method req)]
    (cond
      (and (= method :get) (= uri "/api/config")) (handle-get-config req)
      (and (= method :post) (= uri "/api/config")) (handle-post-config req)
      (and (= method :get) (= uri "/api/state")) (handle-get-state req)
      (and (= method :get) (= uri "/api/logs")) (handle-get-logs req)
      :else (json-response 404 {:status "not-found" :message "Endpoint undefined in Librarian Clojure API"}))))

;; =============================================================================
;; Entrypoint Bootstrapping
;; =============================================================================
(defn -main [& args]
  (init-filesystem!)
  (write-log! "Librarian Clojure-Ring Web Daemon booting on address port 3000...")
  (run-server app {:port 3000 :ip "0.0.0.0"}))
