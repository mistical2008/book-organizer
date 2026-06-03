import React, { useState, useEffect, useRef } from "react";
import { PRESET_BOOKS } from "../presets";
import { BookItem, BookMetadata, PresetBook } from "../types";
import { 
  Play, Folder, Database, RefreshCw, Cpu, Layers, CheckCircle2, 
  Terminal, FileText, ChevronRight, AlertTriangle, Eye, Loader2, Sparkles, BookOpen, Settings
} from "lucide-react";

interface ScannedBookRecord {
  filepath: string;
  filename: string;
  isbn_detected: string | null;
  status: string;
  timestamp: string;
}

interface IsbnRequestRecord {
  filepath: string;
  isbn: string;
  status: "pending" | "completed" | "failed";
  timestamp: string;
}

interface AiCategorizationRecord {
  filepath: string;
  status: "pending" | "completed" | "failed";
  timestamp: string;
}

interface FileOrganizationRecord {
  filepath: string;
  dest_path: string;
  author: string;
  title: string;
  year: number | null;
  genre: string;
  isbn: string | null;
  confidence: number;
  status: "pending" | "completed" | "failed";
  notes: string;
  timestamp: string;
}

export default function PipelineSandbox() {
  // Config state
  const [config, setConfig] = useState<any>({
    inputDirs: ["/var/lib/grimmory/input"],
    outputDir: "/var/lib/grimmory/sorted",
    destinationTemplate: "{Author} - {Title} ({Year})"
  });

  // Server state
  const [isProcessing, setIsProcessing] = useState(false);
  const [stats, setStats] = useState({ inputFilesCount: 0, sortedFilesCount: 0 });
  const [lastRunTime, setLastRunTime] = useState<string | null>(null);

  // Files in input directory
  const [inputFiles, setInputFiles] = useState<string[]>([]);

  // Tables from real database
  const [dbState, setDbState] = useState<{
    scanned_books: ScannedBookRecord[];
    isbn_requests: IsbnRequestRecord[];
    ai_categorization: AiCategorizationRecord[];
    file_organization: FileOrganizationRecord[];
  }>({
    scanned_books: [],
    isbn_requests: [],
    ai_categorization: [],
    file_organization: []
  });

  const [pipelineLogs, setPipelineLogs] = useState<string[]>([]);
  const [activeDbTable, setActiveDbTable] = useState<"scanned_books" | "isbn_requests" | "ai_categorization" | "file_organization">("scanned_books");

  // Create custom book state
  const [customTitle, setCustomTitle] = useState("my_custom_philosophy_book.pdf");
  const [customOcr, setCustomOcr] = useState(
    `THE PROBLEMS OF PHILOSOPHY\nBy Bertrand Russell\nLondon, Williams & Norgate\nISBN 978-0199540020\nPreface: In the following pages I have confined myself...`
  );
  const [activePreset, setActivePreset] = useState<PresetBook>(PRESET_BOOKS[0]);
  const [writingCustom, setWritingCustom] = useState(false);

  // Terminal autoscroll ref
  const logEndRef = useRef<HTMLDivElement>(null);

  // Polling helper
  const pollServerState = async () => {
    try {
      // 1. Fetch status
      const resStatus = await fetch("/api/status");
      if (resStatus.ok) {
        const payload = await resStatus.json();
        setIsProcessing(payload.isProcessing);
        setStats(payload.stats);
        setLastRunTime(payload.lastRunTime);
      }

      // 2. Fetch config
      const resConfig = await fetch("/api/config");
      if (resConfig.ok) {
        const payload = await resConfig.json();
        setConfig(payload);
      }

      // 3. Fetch database tables
      const resDb = await fetch("/api/database");
      if (resDb.ok) {
        const payload = await resDb.json();
        setDbState({
          scanned_books: payload.scanned_books || [],
          isbn_requests: payload.isbn_requests || [],
          ai_categorization: payload.ai_categorization || [],
          file_organization: payload.file_organization || []
        });
      }

      // 4. Fetch logs
      const resLogs = await fetch("/api/logs");
      if (resLogs.ok) {
        const payload = await resLogs.json();
        setPipelineLogs(payload.logs || []);
      }

      // 5. Fetch files
      const resFiles = await fetch("/api/system/files");
      if (resFiles.ok) {
        const payload = await resFiles.json();
        setInputFiles(payload.files || []);
      }
    } catch (e) {
      console.error("Error polling server:", e);
    }
  };

  // Run on mount and periodically
  useEffect(() => {
    pollServerState();
    
    // Quick polling when processing, otherwise slower
    const interval = setInterval(() => {
      pollServerState();
    }, isProcessing ? 1200 : 4000);

    return () => clearInterval(interval);
  }, [isProcessing]);

  // Scroll to bottom of terminal when logs expand
  useEffect(() => {
    if (logEndRef.current) {
      logEndRef.current.scrollIntoView({ behavior: "smooth" });
    }
  }, [pipelineLogs]);

  const handleTriggerSync = async () => {
    if (isProcessing) return;
    try {
      setIsProcessing(true);
      const res = await fetch("/api/trigger", { method: "POST" });
      if (res.ok) {
        pollServerState();
      }
    } catch (err) {
      console.error("Trigger fail:", err);
    }
  };

  const handleResetDemoFiles = async () => {
    if (isProcessing) return;
    try {
      const res = await fetch("/api/reset-demo", { method: "POST" });
      if (res.ok) {
        pollServerState();
      }
    } catch (err) {
      console.error("Reset fail:", err);
    }
  };

  const handleCreateCustomFile = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!customTitle.trim()) return;
    setWritingCustom(true);
    try {
      const res = await fetch("/api/system/create-file", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          filename: customTitle,
          content: customOcr
        })
      });
      if (res.ok) {
        setCustomTitle("another_scanned_book.pdf");
        setCustomOcr("ISBN 978-0123456789\nCustom publication body context.");
        pollServerState();
      }
    } catch (err) {
      console.error("Create custom error:", err);
    } finally {
      setWritingCustom(false);
    }
  };

  const applyPresetToCustom = (p: PresetBook) => {
    setActivePreset(p);
    setCustomTitle((p as any).fileName || `${p.title.toLowerCase().replace(/[^a-z0-9]+/g, "_")}.${p.fileType}`);
    setCustomOcr(p.ocrTextSample);
  };

  return (
    <div className="grid grid-cols-1 xl:grid-cols-3 gap-6" id="sandbox-root-container">
      {/* Left pane - Scanner Input View */}
      <div className="xl:col-span-1 bg-[#0B0B09] p-5 rounded-xl border border-white/5 flex flex-col gap-5 text-left">
        <h3 className="text-xs uppercase tracking-widest text-[#C4A47C] flex items-center gap-2 border-b border-white/5 pb-3 font-mono font-bold">
          <Folder className="w-3.5 h-3.5" />
          NixOS Directory Scanner (Вхідні теки)
        </h3>

        {/* Directory configuration summary */}
        <div className="bg-[#121210] p-4 rounded-lg border border-white/5 flex flex-col gap-2.5">
          <span className="text-[10px] font-mono text-[#C4A47C] uppercase tracking-wider block font-bold">Monitor Directories</span>
          
          <div className="flex flex-col gap-1.5 font-mono text-xs">
            {config.inputDirs?.map((dir: string, idx: number) => (
              <div key={idx} className="flex gap-2 items-center bg-black/30 p-2 rounded border border-white/5">
                <span className="text-[10px] text-white/35">INPUT {idx + 1}:</span>
                <span className="text-[#C4A47C] truncate flex-1 block" title={dir}>{dir}</span>
              </div>
            ))}
            <div className="flex gap-2 items-center bg-black/30 p-2 rounded border border-white/5 mt-1">
              <span className="text-[10px] text-white/35">SORTED TO:</span>
              <span className="text-[#E4E4E0] truncate flex-1 block" title={config.outputDir}>{config.outputDir}</span>
            </div>
          </div>
          
          <div className="flex font-mono text-[10px] text-white/35 justify-between">
            <span>Template: <code className="text-[#C4A47C] bg-[#0B0B09] px-1 rounded">{config.destinationTemplate}</code></span>
            <span>Batch: {config.batchSize} files</span>
          </div>
        </div>

        {/* Real physical files list inside scanning folder */}
        <div className="flex flex-col gap-2.5">
          <div className="flex justify-between items-center">
            <span className="text-[10px] font-mono text-white/40 tracking-wider uppercase font-semibold">Active Unorganized Files ({inputFiles.length})</span>
            <button
              onClick={handleResetDemoFiles}
              disabled={isProcessing}
              className="text-[#C4A47C] hover:text-[#E4E4E0] disabled:opacity-40 text-[9px] font-mono uppercase tracking-wider bg-white/5 hover:bg-white/10 px-2 py-1 rounded transition-all cursor-pointer"
              id="reset-demo-button"
            >
              Reset & Seed Presets
            </button>
          </div>

          <div className="flex flex-col gap-1.5 max-h-48 overflow-y-auto pr-1" id="files-list">
            {inputFiles.length === 0 ? (
              <div className="bg-white/5 border border-dashed border-white/10 p-5 rounded text-center">
                <p className="text-xs text-white/40 font-mono">No target files in input folder.</p>
                <p className="text-[10px] text-white/30 font-sans mt-1">Click &quot;Reset & Seed Presets&quot; or upload below to test.</p>
              </div>
            ) : (
              inputFiles.map((file, fIdx) => (
                <div key={fIdx} className="flex items-center justify-between p-2.5 rounded bg-[#121210] border border-white/5 hover:border-white/10 transition-all font-mono text-xs">
                  <div className="flex items-center gap-2 truncate">
                    <span className="text-white/20 text-[10px]">{fIdx + 1}.</span>
                    <span className="text-emerald-400 truncate font-semibold">{file}</span>
                  </div>
                  <span className="text-[9px] uppercase tracking-wider font-bold text-[#C4A47C] bg-[#C4A47C]/5 border border-[#C4A47C]/20 px-1.5 py-0.5 rounded ml-2">
                    {file.substring(file.lastIndexOf(".") + 1).toUpperCase()}
                  </span>
                </div>
              ))
            )}
          </div>
        </div>

        {/* Active execution controllers */}
        <div className="flex gap-3 border-t border-white/5 pt-4">
          <button
            onClick={handleTriggerSync}
            disabled={isProcessing}
            className={`flex-1 py-3 px-4 rounded-lg flex items-center justify-center gap-2 font-mono text-xs font-semibold tracking-wider cursor-pointer shadow-lg transition-all ${
              isProcessing 
                ? "bg-amber-500/10 border border-amber-500/30 text-amber-500 cursor-wait" 
                : "bg-[#C4A47C] text-[#0D0D0B] hover:bg-[#D5B58D] hover:scale-[1.01]"
            }`}
          >
            {isProcessing ? (
              <>
                <Loader2 className="w-4 h-4 animate-spin" />
                <span>SYNCING FILES...</span>
              </>
            ) : (
              <>
                <Play className="w-4 h-4" />
                <span>ORGANIZER RUN NOW</span>
              </>
            )}
          </button>
        </div>

        {/* Create custom scan file form */}
        <div className="bg-[#121210]/50 border border-white/5 rounded-lg p-3.5 flex flex-col gap-2 mt-2">
          <span className="text-[10px] font-mono text-white/45 uppercase tracking-widest block font-bold">Write Custom Scan File</span>
          
          <form onSubmit={handleCreateCustomFile} className="flex flex-col gap-2">
            <div className="flex flex-col gap-1">
              <span className="text-[8px] font-mono text-white/30 uppercase">File Name</span>
              <input
                type="text"
                value={customTitle}
                onChange={(e) => setCustomTitle(e.target.value)}
                className="bg-black/40 border border-white/10 rounded px-2 py-1 text-xs font-mono text-[#C4A47C] focus:outline-none"
                placeholder="e.g. russell_history_v1.pdf"
                required
              />
            </div>
            
            <div className="flex flex-col gap-1">
              <span className="text-[8px] font-mono text-white/30 uppercase">OCR Text Buffer</span>
              <textarea
                value={customOcr}
                onChange={(e) => setCustomOcr(e.target.value)}
                rows={3}
                className="bg-black/40 border border-white/10 rounded px-2 py-1 text-[11px] font-mono text-white/70 focus:outline-none resize-none leading-relaxed"
                placeholder="Book text content containing title/ISBN keys..."
              />
            </div>

            <div className="flex items-center justify-between mt-1">
              {/* Preset chips */}
              <div className="flex gap-1">
                {PRESET_BOOKS.slice(0, 3).map((p, pIdx) => (
                  <button
                    key={pIdx}
                    type="button"
                    onClick={() => applyPresetToCustom(p)}
                    className="text-[8px] uppercase tracking-wider bg-white/5 border border-white/10 hover:bg-white/10 text-white/60 hover:text-white px-1.5 py-0.5 rounded cursor-pointer transition-all"
                  >
                    {p.title.slice(0, 5)}
                  </button>
                ))}
              </div>

              <button
                type="submit"
                disabled={writingCustom || isProcessing}
                className="bg-emerald-600/20 hover:bg-emerald-600 border border-emerald-500/30 text-emerald-400 hover:text-white px-3 py-1 rounded text-[10px] uppercase font-mono font-bold transition-all cursor-pointer flex gap-1 items-center"
              >
                {writingCustom ? "Writing..." : "Write File"}
              </button>
            </div>
          </form>
        </div>
      </div>

      {/* Middle pane - SQLite Staged Database Tables Explorer */}
      <div className="xl:col-span-2 flex flex-col gap-4">
        {/* Statistics Bar */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 bg-[#0B0B09] p-4 rounded-xl border border-white/5 text-left">
          <div className="flex flex-col gap-0.5">
            <span className="text-[9px] uppercase tracking-widest text-[#C4A47C] font-mono">FILES PENDING</span>
            <span className="text-xl font-mono text-white font-bold">{stats.inputFilesCount}</span>
          </div>
          <div className="flex flex-col gap-0.5">
            <span className="text-[9px] uppercase tracking-widest text-[#C4A47C] font-mono">ORGANIZED</span>
            <span className="text-xl font-mono text-white font-bold">{stats.sortedFilesCount}</span>
          </div>
          <div className="flex flex-col gap-0.5 col-span-2">
            <span className="text-[9px] uppercase tracking-widest text-white/30 font-mono">LAST RUNTIME</span>
            <span className="text-xs font-mono text-white/70 font-semibold truncate block">
              {lastRunTime ? new Date(lastRunTime).toLocaleString() : "Never (Pipeline Idle)"}
            </span>
          </div>
        </div>

        {/* Tables Explorer */}
        <div className="bg-[#0B0B09] rounded-xl border border-white/5 flex-1 p-5 flex flex-col gap-4 text-left">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-white/5 pb-3">
            <div className="flex flex-col gap-0.5">
              <h3 className="text-xs uppercase tracking-widest text-[#C4A47C] flex items-center gap-2 font-mono font-bold">
                <Database className="w-3.5 h-3.5" />
                Grimmory State Database (Транзакційні Таблиці)
              </h3>
              <p className="text-[10px] text-white/40">Real-time state records persisted in metadata cache.</p>
            </div>

            {/* Table Selector */}
            <div className="flex bg-black/40 p-1 rounded-lg border border-white/10 font-mono text-[10px] self-start sm:self-auto overflow-x-auto max-w-full">
              {[
                { id: "scanned_books", label: "scanned_books" },
                { id: "isbn_requests", label: "isbn_requests" },
                { id: "ai_categorization", label: "ai_categorization" },
                { id: "file_organization", label: "file_organization" }
              ].map(t => (
                <button
                  key={t.id}
                  onClick={() => setActiveDbTable(t.id as any)}
                  className={`px-2.5 py-1 rounded-md transition-all cursor-pointer font-bold ${
                    activeDbTable === t.id 
                      ? "bg-[#C4A47C] text-[#0D0D0B] shadow-md" 
                      : "text-white/40 hover:text-white"
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>
          </div>

          {/* Database Content Renderer */}
          <div className="flex-1 overflow-x-auto min-h-[300px]">
            {activeDbTable === "scanned_books" && (
              <table className="w-full font-mono text-[11px] leading-relaxed text-left border-collapse">
                <thead>
                  <tr className="border-b border-white/5 text-white/40 font-bold">
                    <th className="py-2.5 pr-4 pl-2">filepath</th>
                    <th className="py-2.5 pr-4">filename</th>
                    <th className="py-2.5 pr-4 text-center">isbn_detected</th>
                    <th className="py-2.5 pr-4 text-right">status</th>
                  </tr>
                </thead>
                <tbody>
                  {dbState.scanned_books.length === 0 ? (
                    <tr>
                      <td colSpan={4} className="py-8 text-center text-white/30 italic">scanned_books is empty. Trigger sync to scan the filesystem.</td>
                    </tr>
                  ) : (
                    dbState.scanned_books.map((r, rIdx) => (
                      <tr key={rIdx} className="border-b border-white/5 hover:bg-white/5 transition-all text-white/80">
                        <td className="py-2.5 pr-4 pl-2 font-semibold text-[#C4A47C] truncate max-w-xs">{r.filepath}</td>
                        <td className="py-2.5 pr-4 truncate max-w-xs">{r.filename}</td>
                        <td className="py-2.5 pr-4 text-center font-bold text-emerald-400">{r.isbn_detected || "NULL"}</td>
                        <td className="py-2.5 pr-4 text-right">
                          <span className={`px-1.5 py-0.5 rounded text-[9px] uppercase font-bold ${
                            r.status === "completed" 
                              ? "bg-emerald-500/10 text-emerald-400" 
                              : r.status === "processing" 
                                ? "bg-amber-500/10 text-amber-500 animate-pulse" 
                                : "bg-red-500/10 text-red-400"
                          }`}>{r.status}</span>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            )}

            {activeDbTable === "isbn_requests" && (
              <table className="w-full font-mono text-[11px] leading-relaxed text-left border-collapse">
                <thead>
                  <tr className="border-b border-white/5 text-white/40 font-bold">
                    <th className="py-2.5 pr-4 pl-2">filepath</th>
                    <th className="py-2.5 pr-4">isbn</th>
                    <th className="py-2.5 pr-4 text-right">status</th>
                    <th className="py-2.5 pr-2 text-right">timestamp</th>
                  </tr>
                </thead>
                <tbody>
                  {dbState.isbn_requests.length === 0 ? (
                    <tr>
                      <td colSpan={4} className="py-8 text-center text-white/30 italic">isbn_requests is empty. (Bypassed if scan text has no valid standard ISBN).</td>
                    </tr>
                  ) : (
                    dbState.isbn_requests.map((r, rIdx) => (
                      <tr key={rIdx} className="border-b border-white/5 hover:bg-white/5 transition-all text-white/80">
                        <td className="py-2.5 pr-4 pl-2 font-semibold text-[#C4A47C] truncate max-w-xs">{r.filepath}</td>
                        <td className="py-2.5 pr-4 font-bold text-emerald-400">{r.isbn}</td>
                        <td className="py-2.5 pr-4 text-right">
                          <span className={`px-1.5 py-0.5 rounded text-[9px] uppercase font-bold ${
                            r.status === "completed" 
                              ? "bg-emerald-500/10 text-emerald-400" 
                              : r.status === "pending" 
                                ? "bg-amber-500/10 text-amber-500 animate-pulse" 
                                : "bg-red-500/10 text-red-500"
                          }`}>{r.status}</span>
                        </td>
                        <td className="py-2.5 pr-2 text-right text-white/30">{new Date(r.timestamp).toLocaleTimeString()}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            )}

            {activeDbTable === "ai_categorization" && (
              <table className="w-full font-mono text-[11px] leading-relaxed text-left border-collapse">
                <thead>
                  <tr className="border-b border-white/5 text-white/40 font-bold">
                    <th className="py-2.5 pr-4 pl-2">filepath</th>
                    <th className="py-2.5 pr-4">prompt_preview</th>
                    <th className="py-2.5 pr-4 text-right">status</th>
                    <th className="py-2.5 pr-2 text-right">timestamp</th>
                  </tr>
                </thead>
                <tbody>
                  {dbState.ai_categorization.length === 0 ? (
                    <tr>
                      <td colSpan={4} className="py-8 text-center text-white/30 italic">ai_categorization is empty. (Only filled as fallback when standard ISBN catalog lookup fails).</td>
                    </tr>
                  ) : (
                    dbState.ai_categorization.map((r, rIdx) => (
                      <tr key={rIdx} className="border-b border-white/5 hover:bg-white/5 transition-all text-white/80">
                        <td className="py-2.5 pr-4 pl-2 font-semibold text-[#C4A47C] truncate max-w-xs">{r.filepath}</td>
                        <td className="py-2.5 pr-4 text-white/50 italic truncate max-w-sm">{r.text_preview}</td>
                        <td className="py-2.5 pr-4 text-right font-bold text-emerald-400">
                          <span className={`px-1.5 py-0.5 rounded text-[9px] uppercase font-bold ${
                            r.status === "completed" 
                              ? "bg-emerald-500/10 text-emerald-400" 
                              : r.status === "pending" 
                                ? "bg-amber-500/10 text-amber-500 animate-pulse" 
                                : "bg-red-500/10 text-red-500"
                          }`}>{r.status}</span>
                        </td>
                        <td className="py-2.5 pr-2 text-right text-white/30">{new Date(r.timestamp).toLocaleTimeString()}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            )}

            {activeDbTable === "file_organization" && (
              <table className="w-full font-mono text-[11px] leading-relaxed text-left border-collapse">
                <thead>
                  <tr className="border-b border-white/5 text-white/40 font-bold">
                    <th className="py-2.5 pr-4 pl-2">destination_path</th>
                    <th className="py-2.5 pr-4">compiled_metadata</th>
                    <th className="py-2.5 pr-4 text-center">conf</th>
                    <th className="py-2.5 pr-2 text-right">notes</th>
                  </tr>
                </thead>
                <tbody>
                  {dbState.file_organization.length === 0 ? (
                    <tr>
                      <td colSpan={4} className="py-8 text-center text-white/30 italic">file_organization has no final records. Trigger sync above.</td>
                    </tr>
                  ) : (
                    dbState.file_organization.map((r, rIdx) => (
                      <tr key={rIdx} className="border-b border-white/5 hover:bg-white/5 transition-all text-white/80">
                        <td className="py-2.5 pr-4 pl-2 font-bold text-emerald-400 truncate max-w-xs" title={r.dest_path}>{r.dest_path.substring(r.dest_path.lastIndexOf("/") + 1)}</td>
                        <td className="py-2.5 pr-4">
                          <p className="font-semibold text-white/90">{r.title}</p>
                          <p className="text-[10px] text-white/40">{r.author} &bull; {r.year || "Unknown"} &bull; {r.genre}</p>
                        </td>
                        <td className="py-2.5 pr-4 text-center">
                          <span className={`px-1.5 py-0.5 rounded text-[10px] font-bold ${
                            r.confidence >= 85 
                              ? "bg-emerald-500/10 text-emerald-400" 
                              : r.confidence >= 70 
                                ? "bg-amber-500/10 text-amber-500" 
                                : "bg-red-500/10 text-red-400"
                          }`}>{r.confidence}%</span>
                        </td>
                        <td className="py-2.5 pr-2 text-right text-white/40 text-[10px] truncate max-w-xs" title={r.notes}>{r.notes}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            )}
          </div>
        </div>

        {/* Live system logs panel under tables */}
        <div className="bg-[#0B0B09] rounded-xl border border-white/5 p-5 flex flex-col gap-3 h-[250px] text-left">
          <div className="flex items-center justify-between border-b border-white/5 pb-2">
            <span className="text-xs uppercase tracking-widest text-[#C4A47C] flex items-center gap-1.5 font-mono font-bold">
              <Terminal className="w-3.5 h-3.5" />
              Librarian Daemon System Console
            </span>
            <div className="flex items-center gap-2">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse"></span>
              <span className="text-[9px] text-[#C4A47C] font-mono uppercase font-bold">DAEMON ATTACHED</span>
            </div>
          </div>
          
          <div className="flex-1 bg-[#121210] p-4 rounded-lg font-mono text-[10px] text-zinc-400 overflow-y-auto flex flex-col gap-1.5 border border-white/5">
            {pipelineLogs.length === 0 ? (
              <span className="text-white/20 italic">No activity logs recorded. Launch the organizer sync to generate logs.</span>
            ) : (
              pipelineLogs.map((log, lIdx) => (
                <div key={lIdx} className="leading-relaxed hover:text-[#E4E4E0] transition-colors break-words">
                  <span className="text-white/25 select-none">{`>`}</span> {log}
                </div>
              ))
            )}
            <div ref={logEndRef} />
          </div>
        </div>
      </div>
    </div>
  );
}
