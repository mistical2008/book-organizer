import React, { useState, useEffect } from "react";
import { Copy, Check, Download, Settings, RefreshCw, AlertCircle, Sparkles, FolderOpen, FolderPlus, Folder, ArrowUpLeft } from "lucide-react";
import { SystemdOptions } from "../types";

export default function CodeGenerator() {
  const [errorMess, setErrorMess] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);

  // Directory Browser states
  const [showBrowser, setShowBrowser] = useState(false);
  const [browserMode, setBrowserMode] = useState<"input" | "output">("input");
  const [browserInputIdx, setBrowserInputIdx] = useState<number | null>(null);
  const [browserCurrentPath, setBrowserCurrentPath] = useState("");
  const [browserParentPath, setBrowserParentPath] = useState<string | null>(null);
  const [browserDirs, setBrowserDirs] = useState<string[]>([]);
  const [browserLoading, setBrowserLoading] = useState(false);
  const [newFolderName, setNewFolderName] = useState("");
  const [newFolderError, setNewFolderError] = useState<string | null>(null);

  const loadDirectory = async (pathStr: string) => {
    setBrowserLoading(true);
    setNewFolderError(null);
    try {
      const res = await fetch(`/api/system/browse?path=${encodeURIComponent(pathStr)}`);
      if (res.ok) {
        const data = await res.json();
        setBrowserCurrentPath(data.currentPath);
        setBrowserParentPath(data.parentPath);
        setBrowserDirs(data.directories || []);
      } else {
        const errData = await res.json();
        setNewFolderError(errData.error || "Failed to load directory.");
      }
    } catch (err: any) {
      setNewFolderError("Failed to fetch host file system locations.");
    } finally {
      setBrowserLoading(false);
    }
  };

  const handleOpenBrowser = (mode: "input" | "output", idx: number | null = null) => {
    const input = document.createElement("input");
    input.type = "file";
    input.setAttribute("webkitdirectory", "true");
    input.setAttribute("directory", "true");
    input.multiple = true;
    
    input.onchange = (e: any) => {
      const files = e.target.files;
      if (!files || files.length === 0) return;

      const firstFile = files[0];
      const relativePath = firstFile.webkitRelativePath || "";
      let pathValue = "";

      if (relativePath) {
        const parts = relativePath.split("/");
        const rootFolder = parts[0];
        // Smart relative folder path detection for host system
        pathValue = `/home/evgeniy/${rootFolder}`;
      } else {
        pathValue = `/home/evgeniy/${firstFile.name}`;
      }

      if (pathValue) {
        if (mode === "input" && idx !== null) {
          const updated = [...(options.inputDirs || [])];
          updated[idx] = pathValue;
          setOptions({ ...options, inputDirs: updated });
        } else {
          setOptions({ ...options, outputDir: pathValue });
        }
      }
    };

    input.click();
  };

  const handleNavigate = (subDirName: string) => {
    let cleanBrowserPath = browserCurrentPath;
    if (!cleanBrowserPath.endsWith("/") && !cleanBrowserPath.endsWith("\\")) {
      const separator = cleanBrowserPath.includes("\\") ? "\\" : "/";
      cleanBrowserPath = cleanBrowserPath + separator + subDirName;
    } else {
      cleanBrowserPath = cleanBrowserPath + subDirName;
    }
    loadDirectory(cleanBrowserPath);
  };

  const handleNavigateUp = () => {
    if (browserParentPath) {
      loadDirectory(browserParentPath);
    }
  };

  const handlePathInputSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    loadDirectory(browserCurrentPath);
  };

  const handleSelectDirectory = () => {
    if (browserMode === "input" && browserInputIdx !== null) {
      const updated = [...options.inputDirs];
      updated[browserInputIdx] = browserCurrentPath;
      setOptions({ ...options, inputDirs: updated });
    } else {
      setOptions({ ...options, outputDir: browserCurrentPath });
    }
    setShowBrowser(false);
  };

  const handleCreateNewFolder = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newFolderName.trim()) return;
    try {
      const res = await fetch("/api/system/mkdir", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          parentPath: browserCurrentPath,
          folderName: newFolderName.trim()
        })
      });
      if (res.ok) {
        setNewFolderName("");
        loadDirectory(browserCurrentPath);
      } else {
        const data = await res.json();
        setNewFolderError(data.error || "Failed to create directory catalog on server node.");
      }
    } catch (err: any) {
      setNewFolderError("Failed to communicate directory generation with daemon node.");
    }
  };

  const [options, setOptions] = useState<any>({
    serviceName: "librarian",
    userName: "root",
    workingDir: "/root",
    scriptPath: "/root/server.cjs",
    inputDirs: ["/var/lib/librarian/input"],
    outputDir: "/var/lib/librarian/sorted",
    destinationTemplate: "{Author} - {Title} ({Year})",
    geminiModel: "gemini-3.5-flash",
    confidenceThreshold: 70,
    runInterval: "hourly", // hourly, daily, boot
    processingMode: "batch",
    batchSize: 5,
    enableCaching: true,
    daemonEnabled: false,
    geminiApiKey: ""
  });

  // Fetch config from server on mount
  useEffect(() => {
    const fetchConfig = async () => {
      try {
        const res = await fetch("/api/config");
        if (res.ok) {
          const data = await res.json();
          // Map backend speed parameters to options
          setOptions({
            ...options,
            ...data
          });
        }
      } catch (err: any) {
        setErrorMess("Failed to retrieve current server configurations.");
      }
    };
    fetchConfig();
  }, []);

  const handleSaveConfig = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsSaving(true);
    setSaveSuccess(false);
    setErrorMess(null);

    // Map run intervals to ms
    const intervalMapping: Record<string, number> = {
      hourly: 600000,   // every 10 mins in demo for reactivity
      daily: 86400000,  // daily
      boot: 60000       // post run post 1 min
    };
    
    const updatedOptions = {
      ...options,
      runIntervalMs: intervalMapping[options.runInterval] || 300000
    };

    try {
      const res = await fetch("/api/config", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(updatedOptions)
      });
      if (res.ok) {
        setSaveSuccess(true);
        setTimeout(() => setSaveSuccess(false), 4400);
      } else {
        setErrorMess("Server rejected configuration payload.");
      }
    } catch (err: any) {
      setErrorMess("Failed to publish updated daemon parameters.");
    } finally {
      setIsSaving(false);
    }
  };

  const [babashkaCode, setBabashkaCode] = useState("");
  const [activeOutputTab, setActiveOutputTab] = useState<"nixos" | "babashka">("nixos");
  const [copiedState, setCopiedState] = useState(false);

  useEffect(() => {
    const clj = `#!/usr/bin/env bb
;; =============================================================================
;; Librarian Library Supervisor Daemon - Clojure Babashka Edition
;; Matches parameters customized in your Librarian Admin Console.
;; =============================================================================

(ns librarian.core
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell :refer [sh]]))

;; --- Configured Parameters ---
(def input-dirs ${JSON.stringify(options.inputDirs || ["/var/lib/librarian/input"])})
(def output-dir "${options.outputDir}")
(def destination-template "${options.destinationTemplate || "{Author} - {Title} ({Year})"}")
(def confidence-threshold ${options.confidenceThreshold})
(def gemini-model "${options.geminiModel}")
(def enable-caching? ${options.enableCaching !== false ? "true" : "false"})
(def auto-cleanup? ${options.autoCleanup === true ? "true" : "false"})

;; --- Persistent State Cache ---
(def db-path "/var/lib/librarian-web/data/state.json")

(defn load-state []
  (if (.exists (io/file db-path))
    (try (json/parse-string (slurp db-path) true)
         (catch Exception _ {}))
    {}))

(defn save-state! [state]
  (spit db-path (json/generate-string state {:pretty true})))

(defn query-google-books [isbn]
  (println (str "🔍 [Librarian] Seeking ISBN match: " isbn))
  (let [clean-isbn (str/replace isbn #"\\D" "")
        url (str "https://www.googleapis.com/books/v1/volumes?q=isbn:" clean-isbn)]
    (try
      (let [resp (http/get url {:headers {"User-Agent" "Librarian-Babashka/1.0"}})
            body (json/parse-string (:body resp) true)]
        (if (and (> (:totalItems body) 0) (:items body))
          (let [volume-info (-> body :items first :volumeInfo)
                author (str/join ", " (:authors volume-info))
                title (:title volume-info)
                pub-date (:publishedDate volume-info)
                year (and pub-date (re-find #"\\d{4}" pub-date))]
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

(defn extract-via-gemini [text]
  (let [api-key (System/getenv "GEMINI_API_KEY")]
    (if (str/blank? api-key)
      (throw (Exception. "GEMINI_API_KEY env is required."))
      (let [url (str "https://generativelanguage.googleapis.com/v1beta/models/" gemini-model ":generateContent?key=" api-key)
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
  (str/replace (str/trim s) #"[/\\\\?%*:|\\\"<>\s]+" " "))

(defn compute-destination [meta]
  (let [author (or (:author meta) "Unknown Author")
        title (or (:title meta) "Unknown Title")
        year (if (:year meta) (str (:year meta)) "Unknown Year")
        genre (or (:genre meta) "Uncategorized")
        isbn (or (:isbn meta) "No ISBN")
        path-name (-> destination-template
                      (str/replace "{Author}" author)
                      (str/replace "{Title}" title)
                      (str/replace "{Year}" year)
                      (str/replace "{Genre}" genre)
                      (str/replace "{ISBN}" isbn))
        sanitized-path-name (sanitize path-name)]
    (str sanitized-path-name)))

(defn process-book [file-path]
  (println "📖 Processing publication: " file-path)
  (let [ocr-text (run-ocr file-path)
        isbn-match (re-find #"(?:ISBN[- ]?)?(?:97[89][- ]?)?\\\\d{1,5}[- ]?\\\\d{1,7}[- ]?\\\\d{1,7}[- ]?[\\\\dX]" ocr-text)
        metadata (or (and isbn-match (query-google-books isbn-match))
                     (extract-via-gemini ocr-text))]
    (if (and metadata (>= (or (:confidence metadata) 0) confidence-threshold))
      (let [dest-name (compute-destination metadata)
            ext (or (re-find #"\\.[a-zA-Z0-9]+$" file-path) ".pdf")
            final-dest (str output-dir "/" dest-name ext)]
        (println "✨ Metadata Resolved! confidence=" (:confidence metadata))
        (println "🚚 Relocating to: " final-dest)
        (io/make-parents final-dest)
        (io/copy (io/file file-path) (io/file final-dest))
        (when auto-cleanup?
          (io/delete-file (io/file file-path) true))
        {:status "completed" :meta metadata :destination final-dest})
      (do
        (println "❌ Metadata extraction did not meet confidence threshold.")
        {:status "failed" :reason "Low confidence"}))))

(defn -main []
  (println "================================================")
  (println "🤖 Librarian Clojure Babashka Daemon Live")
  (println "================================================")
  (let [state (load-state)
        files (filter #(and (.isFile %) (re-find #"\\.(pdf|epub|djvu)$" (.getName %)))
                      (mapcat #(.listFiles (io/file %)) input-dirs))]
    (doseq [file files]
      (let [path (.getAbsolutePath file)]
        (if (and enable-caching? (= (get-in state [path :status]) "completed"))
          (println "⏭️ Skipping cached file: " path)
          (let [res (process-book path)]
            (save-state! (assoc state path res)))))))
  (println "✅ [Librarian] Library scanning successfully completed."))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
`;
    setBabashkaCode(clj);
  }, [options]);

  const handleCopyCode = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopiedState(true);
    setTimeout(() => setCopiedState(false), 2000);
  };

  const nixConfigString = `# /etc/nixos/configuration.nix
{ config, pkgs, ... }: {
  # Import Librarian Flake module
  imports = [
    inputs.librarian.nixosModules.default
  ];

  # Enable the Librarian Portal & its integrated library monitoring daemon.
  # All configuration (input folders, destination structures, intervals, and fallback models)
  # is customized dynamically and persisted directly in the web administration UI.
  services.librarian = {
    enable = true;
    port = 3000;
    
    # (Optional) Provide a secure credentials environment file containing the GEMINI_API_KEY.
    # Alternatively, config.json is saved dynamically through the Web Settings GUI.
    apiKeyFile = "/etc/secrets/gemini-api.env";
  };
}`;

  return (
    <div className="flex flex-col gap-6" id="code-generator-root">
      
      {/* Settings Form */}
      <form onSubmit={handleSaveConfig} className="bg-[#0B0B09] text-[#E4E4E0] p-5 md:p-6 rounded-xl border border-white/5 flex flex-col gap-6 text-left" id="code-generator-card">
        <div className="flex flex-col gap-1">
          <h2 className="text-xl font-serif font-semibold text-white/95 tracking-tight flex items-center gap-2">
            <Settings className="w-5 h-5 text-[#C4A47C]" />
            Librarian Daemon Parameters Setup
          </h2>
          <p className="text-xs text-white/50">
            Dynamically adjust scanning conditions, directories, confidence thresholds, and Gemini fallback targets inside your full-stack background daemon.
          </p>
        </div>

        {/* Display Status indicators */}
        <div className="flex flex-wrap gap-4 items-center bg-black/40 border border-white/5 rounded-lg p-3.5 font-mono text-xs">
          <div className="flex items-center gap-2">
            <span className="text-white/40 uppercase text-[10px]">Background Daemon Scheduler:</span>
            <span className={`px-1.5 py-0.5 rounded text-[9px] uppercase font-bold ${
              options.daemonEnabled 
                ? "bg-emerald-500/15 text-emerald-400" 
                : "bg-amber-500/10 text-amber-500"
            }`}>
              {options.daemonEnabled ? "Active (OnCalendar)" : "Idle (Manual Trigger Only)"}
            </span>
          </div>

          <div className="h-4 w-px bg-white/10 hidden sm:block"></div>

          <div className="flex items-center gap-1">
            <span className="text-white/30 uppercase text-[10px]">Processing Pipeline:</span>
            <span className="text-[#C4A47C] font-semibold">{options.processingMode === "batch" ? `Batch Limit (${options.batchSize} files)` : "Sequential Paced"}</span>
          </div>
        </div>

        {errorMess && (
          <div className="bg-red-500/10 border border-red-500/25 p-3 rounded text-red-400 text-xs flex items-center gap-2 font-mono">
            <AlertCircle className="w-4 h-4 shrink-0" />
            <span>{errorMess}</span>
          </div>
        )}

        {saveSuccess && (
          <div className="bg-emerald-500/15 border border-emerald-500/25 p-3.5 rounded text-emerald-400 text-xs flex flex-col gap-0.5 font-mono transition-all">
            <span className="font-bold flex items-center gap-1.5 uppercase tracking-wider text-[11px]">
              <Check className="w-3.5 h-3.5" />
              Settings Updated & Schedulers Applied!
            </span>
            <span className="text-white/50 text-[10px] lowercase font-sans">Daemon system config.json flushed and interval timers reloaded successfully on host environment.</span>
          </div>
        )}

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5" id="generator-settings-grid">
          
          {/* Multiple directories settings */}
          <div className="flex flex-col gap-2 Text-left md:col-span-2">
            <div className="flex justify-between items-center">
              <label className="text-[10px] font-mono text-white/40 tracking-wider uppercase font-semibold">Daemon Input Directories (Multiple Source Paths)</label>
              <button
                type="button"
                onClick={() => {
                  const updated = [...(options.inputDirs || ["/var/lib/librarian/input"])];
                  updated.push(`/var/lib/librarian/input_channel_${updated.length + 1}`);
                  setOptions({ ...options, inputDirs: updated });
                }}
                className="text-[#C4A47C] text-[10px] font-mono hover:underline uppercase tracking-wider cursor-pointer"
              >
                + Add Source Path
              </button>
            </div>
            <div className="flex flex-col gap-2 max-h-36 overflow-y-auto pr-1">
              {(options.inputDirs || ["/var/lib/librarian/input"]).map((dir: string, idx: number) => (
                <div key={idx} className="flex gap-2 items-center">
                  <span className="text-[10px] font-mono text-white/30 w-4 font-bold">{idx + 1}.</span>
                  <div className="flex bg-[#121210] border border-white/15 rounded focus-within:border-[#C4A47C]/50 flex-1 overflow-hidden">
                    <input
                      type="text"
                      required
                      className="bg-transparent border-none text-xs px-3 py-2 focus:outline-none text-[#C4A47C] font-mono flex-1 min-w-0"
                      value={dir}
                      onChange={(e) => {
                        const updated = [...options.inputDirs];
                        updated[idx] = e.target.value;
                        setOptions({ ...options, inputDirs: updated });
                      }}
                      id={`input-dir-setting-${idx}`}
                    />
                    <button
                      type="button"
                      onClick={() => handleOpenBrowser("input", idx)}
                      className="bg-white/5 hover:bg-[#C4A47C]/15 border-l border-white/10 px-3 flex items-center justify-center text-white/50 hover:text-[#C4A47C] transition-all cursor-pointer animate-none"
                      title="Browse directory path"
                    >
                      <FolderOpen className="w-3.5 h-3.5" />
                    </button>
                  </div>
                  {(options.inputDirs || []).length > 1 && (
                    <button
                      type="button"
                      onClick={() => {
                        const updated = (options.inputDirs || []).filter((_, i) => i !== idx);
                        setOptions({ ...options, inputDirs: updated });
                      }}
                      className="text-white/30 hover:text-red-400 text-sm px-2 font-mono hover:bg-white/5 rounded transition-all cursor-pointer"
                      title="Remove source path"
                    >
                      &times;
                    </button>
                  )}
                </div>
              ))}
            </div>
          </div>

          <div className="flex flex-col gap-1 text-left">
            <label className="text-[10px] font-mono text-white/40 tracking-wider uppercase">Output Directory</label>
            <div className="flex bg-[#121210] border border-white/15 rounded focus-within:border-[#C4A47C]/50 overflow-hidden">
              <input
                type="text"
                required
                className="bg-transparent border-none text-xs px-3 py-2 focus:outline-none text-[#C4A47C] font-mono flex-1 min-w-0"
                value={options.outputDir}
                onChange={(e) => setOptions({ ...options, outputDir: e.target.value })}
                id="output-dir-setting"
              />
              <button
                type="button"
                onClick={() => handleOpenBrowser("output", null)}
                className="bg-white/5 hover:bg-[#C4A47C]/15 border-l border-white/10 px-3 flex items-center justify-center text-white/50 hover:text-[#C4A47C] transition-all cursor-pointer animate-none"
                title="Browse directory path"
              >
                <FolderOpen className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>

          <div className="flex flex-col gap-1 text-left md:col-span-2">
            <div className="flex justify-between items-center">
              <label className="text-[10px] font-mono text-white/40 tracking-wider uppercase font-semibold">Destination Path Custom Template</label>
              <button
                type="button"
                onClick={() => setOptions({ ...options, destinationTemplate: "{Author} - {Title} ({Year})" })}
                className="text-white/30 text-[9px] font-mono hover:underline uppercase cursor-pointer"
              >
                Reset Default
              </button>
            </div>
            <input
              type="text"
              required
              className="bg-[#121210] border border-white/10 text-xs px-3 py-2 rounded focus:outline-none focus:border-[#C4A47C]/50 text-[#C4A47C] font-mono"
              value={options.destinationTemplate}
              onChange={(e) => setOptions({ ...options, destinationTemplate: e.target.value })}
              placeholder="{Author} - {Title} ({Year})"
              id="destination-template-setting"
            />
            <div className="flex flex-wrap gap-1 mt-1">
              {[
                { tag: "{Author}", desc: "Author Name" },
                { tag: "{Title}", desc: "Book Title" },
                { tag: "{Year}", desc: "Publish Year" },
                { tag: "{ISBN}", desc: "ISBN Identifier" },
                { tag: "{Genre}", desc: "Genre Category (Study field)" }
              ].map(item => (
                <button
                  key={item.tag}
                  type="button"
                  onClick={() => {
                    const val = options.destinationTemplate || "";
                    setOptions({ ...options, destinationTemplate: val + item.tag });
                  }}
                  className="bg-white/5 hover:bg-[#C4A47C]/25 border border-white/10 hover:border-[#C4A47C]/30 text-white/80 hover:text-[#C4A47C] px-2 py-0.5 rounded text-[10px] font-mono transition-all cursor-pointer"
                  title={item.desc}
                >
                  {item.tag}
                </button>
              ))}
            </div>
          </div>

          <div className="flex flex-col gap-1 text-left">
            <label className="text-[10px] font-mono text-white/40 tracking-wider uppercase">Gemini fallback model</label>
            <select
              className="bg-[#121210] border border-white/10 text-xs px-3 py-2 rounded focus:outline-none focus:border-[#C4A47C]/50 text-[#C4A47C] font-mono"
              value={options.geminiModel}
              onChange={(e) => setOptions({ ...options, geminiModel: e.target.value })}
              id="gemini-model-setting"
            >
              <option value="gemini-3.5-flash">gemini-3.5-flash (Recommended)</option>
              <option value="gemini-3.1-pro-preview">gemini-3.1-pro-preview (Paid)</option>
            </select>
          </div>

          <div className="flex flex-col gap-1 text-left">
            <label className="text-[10px] font-mono text-white/40 tracking-wider uppercase">Gemini API Key (API Ключ)</label>
            <input
              type="password"
              placeholder="AIzaSy... (empty for environment default)"
              className="bg-[#121210] border border-white/10 text-xs px-3 py-2 rounded focus:outline-none focus:border-[#C4A47C]/50 text-[#C4A47C] font-mono"
              value={options.geminiApiKey || ""}
              onChange={(e) => setOptions({ ...options, geminiApiKey: e.target.value })}
              id="gemini-apikey-setting"
            />
          </div>

          <div className="flex flex-col gap-1 text-left">
            <label className="text-[10px] font-mono text-white/40 tracking-wider uppercase">Confidence Limit Threshold (%)</label>
            <input
              type="number"
              min="0"
              max="100"
              required
              className="bg-[#121210] border border-white/10 text-xs px-3 py-2 rounded focus:outline-none focus:border-[#C4A47C]/50 text-[#C4A47C] font-mono"
              value={options.confidenceThreshold}
              onChange={(e) => setOptions({ ...options, confidenceThreshold: Number(e.target.value) })}
              id="confidence-threshold-setting"
            />
          </div>

          <div className="flex flex-col gap-1 text-left">
            <label className="text-[10px] font-mono text-white/40 tracking-wider uppercase">Scanning Timer Interval</label>
            <select
              className="bg-[#121210] border border-white/10 text-xs px-3 py-2 rounded focus:outline-none focus:border-[#C4A47C]/50 text-[#C4A47C] font-mono"
              value={options.runInterval}
              onChange={(e) => setOptions({ ...options, runInterval: e.target.value })}
              id="scan-interval-setting"
            >
              <option value="hourly">Hourly polling (Щогодини)</option>
              <option value="daily">Daily polling (Щодня)</option>
            </select>
          </div>

          <div className="flex flex-col gap-1 text-left font-mono text-xs justify-center pt-2">
            <label className="flex items-center gap-2 cursor-pointer select-none">
              <input
                type="checkbox"
                className="accent-[#C4A47C] rounded w-4 h-4"
                checked={options.daemonEnabled}
                onChange={(e) => setOptions({ ...options, daemonEnabled: e.target.checked })}
              />
              <span className="text-white/90">Enable Background Daemon Sync</span>
            </label>
            <p className="text-[9px] text-white/35 ml-6">Starts automatic directory scanner interval handlers</p>
          </div>

          <div className="flex flex-col gap-1 text-left font-mono text-xs justify-center pt-2">
            <label className="flex items-center gap-2 cursor-pointer select-none">
              <input
                type="checkbox"
                className="accent-[#C4A47C] rounded w-4 h-4"
                checked={options.enableCaching !== false}
                onChange={(e) => setOptions({ ...options, enableCaching: e.target.checked })}
              />
              <span className="text-white/90">Avoid Rescanning (Cache)</span>
            </label>
            <p className="text-[9px] text-white/35 ml-6">Bypasses re-processing completed records</p>
          </div>

          <div className="flex flex-col gap-1 text-left font-mono text-xs justify-center pt-2">
            <label className="flex items-center gap-2 cursor-pointer select-none">
              <input
                type="checkbox"
                className="accent-[#C4A47C] rounded w-4 h-4"
                checked={options.autoCleanup === true}
                onChange={(e) => setOptions({ ...options, autoCleanup: e.target.checked })}
              />
              <span className="text-white/90">Auto-Delete Original</span>
            </label>
            <p className="text-[9px] text-white/35 ml-6">Deletes files from input folder on sorting success</p>
          </div>

        </div>

        <button
          type="submit"
          disabled={isSaving}
          className="bg-[#C4A47C] text-[#0D0D0B] py-2 px-5 rounded-lg font-mono text-xs font-semibold tracking-widest uppercase cursor-pointer hover:bg-[#D5B58D] transition-all self-start flex items-center gap-1.5"
        >
          {isSaving ? (
            <>
              <RefreshCw className="w-3.5 h-3.5 animate-spin" />
              <span>SAVING PARAMS...</span>
            </>
          ) : (
            <span>Save & Apply Daemon Settings</span>
          )}
        </button>
      </form>

      {/* Decorative Tab / Multiple System Deployments panel */}
      <div className="bg-[#0B0B09] border border-white/5 rounded-xl p-5 md:p-6 text-left flex flex-col gap-4 font-mono text-xs" id="daemon-deployments-box">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between border-b border-white/5 pb-3 gap-3">
          <div className="flex flex-col gap-1">
            <span className="text-sm uppercase tracking-widest text-[#C4A47C] flex items-center gap-2 font-bold select-none">
              <Sparkles className="w-4 h-4 text-[#C4A47C]" />
              Daemon Deployment Outputs & Guides
            </span>
            <p className="text-[10px] text-white/40 normal-case font-sans">
              Choose your target daemon environment to manage scheduled folder scanning.
            </p>
          </div>
          
          <div className="flex items-center gap-1 bg-white/5 p-1 rounded-lg border border-white/10 self-start">
            {[
              { id: "nixos", label: "NixOS Config" },
              { id: "babashka", label: "Clojure Babashka" }
            ].map((tab) => (
              <button
                key={tab.id}
                type="button"
                onClick={() => {
                  setActiveOutputTab(tab.id as any);
                  setCopiedState(false);
                }}
                className={`px-2 py-1 rounded text-[10px] font-semibold transition-all cursor-pointer ${
                  activeOutputTab === tab.id
                    ? "bg-[#C4A47C] text-[#0D0D0B]"
                    : "text-white/40 hover:text-white hover:bg-white/5"
                }`}
              >
                {tab.label}
              </button>
            ))}
          </div>
        </div>

        <div className="flex items-center justify-between text-[11px] text-white/50 bg-black/30 p-2.5 rounded border border-white/5 font-sans">
          <span>
            {activeOutputTab === "nixos" && "Declarative NixOS configuration module with automated systemd background services."}
            {activeOutputTab === "babashka" && "High-performance Clojure script running on the lightweight Babashka (bb) interpreter."}
          </span>
          <button
            onClick={() => {
              const code = 
                activeOutputTab === "nixos" ? nixConfigString : babashkaCode;
              handleCopyCode(code);
            }}
            className="text-white/40 hover:text-white transition-all text-[10px] flex items-center gap-1.5 cursor-pointer bg-white/5 px-2.5 py-1 rounded border border-white/10 shrink-0 select-none ml-4"
          >
            {copiedState ? (
              <>
                <Check className="w-3.5 h-3.5 text-emerald-400" />
                <span>COPIED!</span>
              </>
            ) : (
              <>
                <Copy className="w-3.5 h-3.5" />
                <span>COPY CODE</span>
              </>
            )}
          </button>
        </div>

        <pre className="bg-[#121210] p-4 rounded-lg overflow-x-auto text-[11px] leading-relaxed border border-white/5 text-[#C4A47C] max-h-[480px] overflow-y-auto font-mono">
          {activeOutputTab === "nixos" && nixConfigString}
          {activeOutputTab === "babashka" && babashkaCode}
        </pre>
      </div>

    </div>
  );
}
