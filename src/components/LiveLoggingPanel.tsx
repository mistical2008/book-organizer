import { useState, useEffect } from "react";
import { 
  Terminal, Trash2, Download, Search, Filter, 
  Play, RefreshCw, Server, Activity 
} from "lucide-react";

interface LogCategory {
  id: "all" | "system" | "ai" | "isbn" | "file" | "alert";
  label: string;
  count: number;
}

export default function LiveLoggingPanel() {
  const [logs, setLogs] = useState<string[]>([]);
  const [isProcessing, setIsProcessing] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedCategory, setSelectedCategory] = useState<LogCategory["id"]>("all");
  const [configParams, setConfigParams] = useState<any>(null);
  const [stats, setStats] = useState({ inputFilesCount: 0, sortedFilesCount: 0 });

  // Poll status, config, and logs
  const fetchStatusAndLogs = async () => {
    try {
      // 1. Fetch live logs
      const resLogs = await fetch("/api/logs");
      if (resLogs.ok) {
        const payload = await resLogs.json();
        setLogs(payload.logs || []);
      }

      // 2. Fetch server status (e.g. isProcessing state)
      const resStatus = await fetch("/api/status");
      if (resStatus.ok) {
        const payload = await resStatus.json();
        setIsProcessing(payload.isProcessing);
        setStats(payload.stats || { inputFilesCount: 0, sortedFilesCount: 0 });
      }

      // 3. Fetch current system config
      const resConfig = await fetch("/api/config");
      if (resConfig.ok) {
        const payload = await resConfig.json();
        setConfigParams(payload);
      }
    } catch (e) {
      console.error("Error polling daemon logging systems:", e);
    }
  };

  useEffect(() => {
    fetchStatusAndLogs();

    // Fast polling in background for high-fidelity real-time logs
    const interval = setInterval(() => {
      fetchStatusAndLogs();
    }, 1500);

    return () => clearInterval(interval);
  }, []);

  // Handle pipeline trigger
  const handleTriggerSync = async () => {
    if (isProcessing) return;
    try {
      setIsProcessing(true);
      const res = await fetch("/api/trigger", { method: "POST" });
      if (res.ok) {
        fetchStatusAndLogs();
      }
    } catch (err) {
      console.error("Failed to trigger compilation sync run:", err);
    }
  };

  // Handle clearing log history in backend
  const handleClearLogs = async () => {
    try {
      const res = await fetch("/api/logs/clear", { method: "POST" });
      if (res.ok) {
        const payload = await res.json();
        setLogs(payload.logs || []);
      }
    } catch (err) {
      console.error("Failed to clear backend logging database:", err);
    }
  };

  // Export current logs to custom text file downloads
  const handleExportLogs = () => {
    const textBlob = new Blob([logs.join("\n")], { type: "text/plain" });
    const blobURL = URL.createObjectURL(textBlob);
    const mockAnchor = document.createElement("a");
    mockAnchor.href = blobURL;
    mockAnchor.download = `librarian-daemon-run_${new Date().toISOString().replace(/[:.]/g, "-")}.log`;
    document.body.appendChild(mockAnchor);
    mockAnchor.click();
    document.body.removeChild(mockAnchor);
    URL.revokeObjectURL(blobURL);
  };

  // Categorize log string internally
  const getLogCategory = (logStr: string): LogCategory["id"] => {
    const lowercase = logStr.toLowerCase();
    if (lowercase.includes("[warning]") || lowercase.includes("[failure]") || lowercase.includes("error") || lowercase.includes("crash") || lowercase.includes("failed")) {
      return "alert";
    }
    if (lowercase.includes("moving") || lowercase.includes("relocating") || lowercase.includes("purged") || lowercase.includes("cleanup") || lowercase.includes("destination") || lowercase.includes("target")) {
      return "file";
    }
    if (lowercase.includes("gemini") || lowercase.includes("context") || lowercase.includes("structured json") || lowercase.includes("llm")) {
      return "ai";
    }
    if (lowercase.includes("isbn") || lowercase.includes("google books api") || lowercase.includes("resolver")) {
      return "isbn";
    }
    return "system";
  };

  // Apply search query and category filters
  const filteredLogs = logs.filter(log => {
    const matchesSearch = log.toLowerCase().includes(searchQuery.toLowerCase());
    if (selectedCategory === "all") return matchesSearch;
    return matchesSearch && getLogCategory(log) === selectedCategory;
  });

  // Count distribution categories
  const categories: LogCategory[] = [
    { id: "all", label: "All Statements", count: logs.length },
    { id: "system", label: "Core Daemon", count: logs.filter(l => getLogCategory(l) === "system").length },
    { id: "ai", label: "Gemini AI Agent", count: logs.filter(l => getLogCategory(l) === "ai").length },
    { id: "isbn", label: "ISBN Lookup", count: logs.filter(l => getLogCategory(l) === "isbn").length },
    { id: "file", label: "File Relocation", count: logs.filter(l => getLogCategory(l) === "file").length },
    { id: "alert", label: "System Alerts", count: logs.filter(l => getLogCategory(l) === "alert").length }
  ];

  // Helper template renderer to highlight formatted segments inside raw logs
  const formatColoredLogSpan = (logStr: string) => {
    // Styling tags & log attributes dynamically
    if (logStr.startsWith("---") || logStr.includes("INITIATING AUTOMATED")) {
      return <span className="text-[#C4A47C]/80 font-bold tracking-wider">{logStr}</span>;
    }

    let parsedElement = <>{logStr}</>;
    
    // Check key patterns and apply visual tags
    if (logStr.includes("[Success]")) {
      const parts = logStr.split("[Success]");
      return (
        <span>
          {parts[0]}
          <span className="bg-emerald-500/15 border border-emerald-500/30 text-emerald-400 font-bold px-1.5 py-0.5 rounded text-[9px] uppercase font-mono mr-1">SUCCESS</span>
          <span className="text-emerald-400 font-medium">{parts[1]}</span>
        </span>
      );
    }

    if (logStr.includes("[Warning]")) {
      const parts = logStr.split("[Warning]");
      return (
        <span>
          {parts[0]}
          <span className="bg-amber-500/15 border border-amber-500/30 text-amber-500 font-bold px-1.5 py-0.5 rounded text-[9px] uppercase font-mono mr-1">WARN</span>
          <span className="text-amber-300 font-medium">{parts[1]}</span>
        </span>
      );
    }

    if (logStr.includes("[Failure]")) {
      const parts = logStr.split("[Failure]");
      return (
        <span>
          {parts[0]}
          <span className="bg-red-500/15 border border-red-500/30 text-red-500 font-bold px-1.5 py-0.5 rounded text-[9px] uppercase font-mono mr-1">CRITICAL</span>
          <span className="text-red-400 font-medium">{parts[1]}</span>
        </span>
      );
    }

    if (logStr.includes("[Gated AI Trigger]")) {
      const parts = logStr.split("[Gated AI Trigger]");
      return (
        <span>
          {parts[0]}
          <span className="bg-purple-500/15 border border-purple-500/30 text-purple-400 font-bold px-1.5 py-0.5 rounded text-[9px] uppercase font-mono mr-1">GEMINI AI</span>
          <span className="text-purple-300 italic">{parts[1]}</span>
        </span>
      );
    }

    if (logStr.includes("[Gated Rule Match]")) {
      const parts = logStr.split("[Gated Rule Match]");
      return (
        <span>
          {parts[0]}
          <span className="bg-[#C4A47C]/15 border border-[#C4A47C]/30 text-[#C4A47C] font-bold px-1.5 py-0.5 rounded text-[9px] uppercase font-mono mr-1">RULE MATCH</span>
          <span className="text-zinc-200">{parts[1]}</span>
        </span>
      );
    }

    if (logStr.includes("[Relocating System]")) {
      const parts = logStr.split("[Relocating System]");
      return (
        <span>
          {parts[0]}
          <span className="bg-blue-500/15 border border-blue-500/30 text-blue-400 font-bold px-1.5 py-0.5 rounded text-[9px] uppercase font-mono mr-1">RELOCATING</span>
          <span className="text-blue-300 font-semibold">{parts[1]}</span>
        </span>
      );
    }

    if (logStr.includes("[Purged original file]")) {
      const parts = logStr.split("[Purged original file]");
      return (
        <span>
          {parts[0]}
          <span className="bg-zinc-800 text-zinc-400 border border-zinc-700 px-1.5 py-0.5 rounded text-[9px] uppercase font-mono mr-1">PURGED</span>
          <span className="text-zinc-400 italic">{parts[1]}</span>
        </span>
      );
    }

    if (logStr.trim().startsWith("▷")) {
      return <span className="text-[#C4A47C] font-semibold">{logStr}</span>;
    }

    // Highlight generic info
    return parsedElement;
  };

  return (
    <div className="grid grid-cols-1 lg:grid-cols-4 gap-6 items-start text-left" id="logging-root-view">
      
      {/* Sidebar: Dashboard stats and operational variables */}
      <div className="lg:col-span-1 flex flex-col gap-6">
        
        {/* Active daemon parameters setup card */}
        <div className="bg-[#0B0B09] p-5 rounded-xl border border-white/5 flex flex-col gap-4">
          <h3 className="text-xs uppercase tracking-wider text-[#C4A47C] font-mono font-bold flex items-center gap-1.5 border-b border-white/5 pb-2.5">
            <Server className="w-3.5 h-3.5" />
            Daemon Variables
          </h3>

          <div className="flex flex-col gap-3">
            <div className="flex flex-col gap-1">
              <span className="text-[9px] font-mono uppercase text-white/30">Librarian Port</span>
              <span className="text-xs font-mono text-zinc-300 font-semibold py-1 px-2 rounded bg-black/40 border border-white/5">
                3000 (Proxy Router)
              </span>
            </div>

            <div className="flex flex-col gap-1">
              <span className="text-[9px] font-mono uppercase text-white/30">Background Scanning Rate</span>
              <span className="text-xs font-mono text-zinc-300 py-1 px-2 rounded bg-black/40 border border-white/5 font-semibold">
                {configParams?.enableCron ? `${(configParams?.cronIntervalMs || 60000) / 1000}s Polling Rate` : "Manual execution only"}
              </span>
            </div>

            <div className="flex flex-col gap-1">
              <span className="text-[9px] font-mono uppercase text-white/30">Target System Folder</span>
              <span className="text-xs font-mono text-[#C4A47C] truncate py-1 px-2 rounded bg-black/40 border border-[#C4A47C]/10 font-bold block" title={configParams?.outputDir}>
                {configParams?.outputDir || "Not set"}
              </span>
            </div>

            <div className="flex flex-col gap-1">
              <span className="text-[9px] font-mono uppercase text-white/30">Confidence Re-eval Gating</span>
              <span className="text-xs font-mono text-emerald-400 py-1 px-2 rounded bg-black/40 border border-white/5 font-semibold">
                &ge; {configParams?.confidenceThreshold || 70}%
              </span>
            </div>

            <div className="flex flex-col gap-1">
              <span className="text-[9px] font-mono uppercase text-white/30">Primary LLM Model</span>
              <span className="text-xs font-mono text-purple-400 py-1 px-2 rounded bg-black/40 border border-white/5 font-semibold">
                {configParams?.geminiModel || "gemini-3.5-flash"}
              </span>
            </div>

            <div className="flex flex-col gap-1">
              <span className="text-[9px] font-mono uppercase text-white/30">Max Batch Size</span>
              <span className="text-xs font-mono text-zinc-300 py-1 px-2 rounded bg-black/40 border border-white/5 font-semibold">
                {configParams?.batchSize || 5} publications per run
              </span>
            </div>
          </div>

          {/* Quick production operations actions */}
          <div className="border-t border-white/5 pt-4 flex flex-col gap-2">
            <button
              onClick={handleTriggerSync}
              disabled={isProcessing}
              className={`w-full py-2.5 px-3 rounded text-xs font-semibold font-mono tracking-wider transition-all flex items-center justify-center gap-1.5 cursor-pointer ${
                isProcessing
                  ? "bg-amber-500/10 border border-amber-500/20 text-amber-400 cursor-wait"
                  : "bg-[#C4A47C] text-[#0D0D0B] hover:bg-[#D5B58D] shadow-md"
              }`}
            >
              {isProcessing ? (
                <>
                  <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                  <span>SYNC PROCESSING...</span>
                </>
              ) : (
                <>
                  <Play className="w-3.5 h-3.5" />
                  <span>TRIGGER SYNC RUN</span>
                </>
              )}
            </button>
          </div>
        </div>

        {/* Categories filtration widget */}
        <div className="bg-[#0B0B09] p-5 rounded-xl border border-white/5 flex flex-col gap-3">
          <h3 className="text-xs uppercase tracking-wider text-white/40 font-mono font-bold flex items-center gap-1.5 border-b border-white/5 pb-2">
            <Filter className="w-3.5 h-3.5" />
            Filter Components
          </h3>
          <div className="flex flex-col gap-1.5 font-mono text-xs">
            {categories.map(cat => {
              const matches = selectedCategory === cat.id;
              return (
                <button
                  key={cat.id}
                  onClick={() => setSelectedCategory(cat.id)}
                  className={`flex items-center justify-between px-3 py-2 rounded text-left transition-all cursor-pointer ${
                    matches
                      ? "bg-[#C4A47C]/15 border border-[#C4A47C]/30 text-[#C4A47C]"
                      : "text-white/55 hover:bg-white/5 border border-transparent"
                  }`}
                >
                  <span className={matches ? "font-bold" : ""}>{cat.label}</span>
                  <span className={`text-[10px] px-1.5 py-0.5 rounded font-bold ${
                    matches ? "bg-[#C4A47C]/20 text-[#C4A47C]" : "bg-white/5 text-white/30"
                  }`}>
                    {cat.count}
                  </span>
                </button>
              );
            })}
          </div>
        </div>

        {/* Real-time sync diagnostic feedback panel */}
        <div className="bg-[#0B0B09] p-4 rounded-xl border border-white/5 flex flex-col gap-2 font-mono text-[11px] leading-relaxed relative overflow-hidden">
          <div className="absolute top-0 right-0 w-24 h-24 bg-emerald-500/5 rounded-full blur-2xl"></div>
          <div className="flex items-center gap-1.5 text-emerald-400 font-bold uppercase tracking-wider text-[10px]">
            <Activity className="w-3.5 h-3.5" />
            PRODUCTION ENVIRONMENT
          </div>
          <div className="text-zinc-500">
            Node Service Daemon is tied directly to port <span className="text-zinc-300">3000</span> running on host container under process tree supervisions. Memory pools are guarded safely from Out-Of-Memory exceptions.
          </div>
        </div>

      </div>

      {/* Main Column: Live Streaming Terminal Console */}
      <div className="lg:col-span-3 flex flex-col gap-4 h-full min-h-[500px]">
        {/* Terminal Header & Search bar */}
        <div className="bg-[#0B0B09] p-4 rounded-xl border border-white/5 flex flex-col sm:flex-row sm:items-center justify-between gap-3 shrink-0">
          
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded bg-[#121210] border border-white/10 flex items-center justify-center shrink-0">
              <Terminal className="w-4 h-4 text-[#C4A47C]" />
            </div>
            <div>
              <h2 className="text-sm font-semibold text-white tracking-wide font-sans">
                Librarian Daemon Pipeline Console
              </h2>
              <div className="flex items-center gap-1.5 mt-0.5 text-[10px] text-white/40 font-mono">
                <span className={`w-1.5 h-1.5 rounded-full ${isProcessing ? "bg-amber-500 animate-pulse" : "bg-emerald-500 animate-pulse"}`}></span>
                <span className="uppercase">{isProcessing ? "SYNCHRONIZATION ACTIVE" : "LOG ENGINE ATTACHED & AGENT STREAMING"}</span>
                <span>&bull;</span>
                <span>Showing {filteredLogs.length} statements</span>
              </div>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {/* Live Search input */}
            <div className="relative font-mono text-xs flex-1 sm:flex-initial">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-white/30" />
              <input
                type="text"
                placeholder="Search log records..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="bg-[#121210] border border-white/10 rounded pl-8 pr-3 py-1.5 text-xs text-white placeholder-white/30 focus:outline-none focus:border-[#C4A47C]/40 min-w-[200px]"
              />
            </div>

            {/* Clear button */}
            <button
              onClick={handleClearLogs}
              title="Clear terminal stream buffer on server"
              className="px-2.5 py-1.5 rounded bg-white/5 hover:bg-red-500/10 hover:text-red-400 border border-white/15 hover:border-red-500/30 text-white/70 transition-all text-xs font-mono flex items-center gap-1 cursor-pointer"
            >
              <Trash2 className="w-3.5 h-3.5" />
              <span className="hidden md:inline">Clear Server Buffer</span>
            </button>

            {/* Export downloads button */}
            <button
              onClick={handleExportLogs}
              title="Download compiled session log"
              className="px-2.5 py-1.5 rounded bg-[#C4A47C]/10 border border-[#C4A47C]/20 hover:bg-[#C4A47C]/20 text-[#C4A47C] transition-all text-xs font-mono flex items-center gap-1 cursor-pointer"
            >
              <Download className="w-3.5 h-3.5" />
              <span className="hidden md:inline">Export Log</span>
            </button>
          </div>

        </div>

        {/* Live Terminal screen */}
        <div className="bg-[#0B0B09] border border-white/10 rounded-xl p-4 flex flex-col flex-1 h-[600px] relative overflow-hidden">
          
          {/* Console Text Canvas Area */}
          <div className="flex-1 overflow-y-auto bg-[#121210] border border-black p-4 rounded-lg font-mono text-[10px] leading-relaxed text-zinc-300 flex flex-col gap-2 pr-2">
            
            {/* Empty log handler */}
            {filteredLogs.length === 0 ? (
              <div className="flex-grow flex flex-col items-center justify-center text-center gap-2 p-12 text-zinc-500">
                <Terminal className="w-8 h-8 text-white/10 animate-pulse" />
                <div>
                  <p className="text-xs font-bold text-white/60">Console log stream is empty</p>
                  <p className="text-[10px] text-white/35 mt-0.5">
                    {searchQuery 
                      ? "Try tweaking your keyword searches or filter parameters." 
                      : "Trigger a compilation synchronize run above to monitor server transactions."
                    }
                  </p>
                </div>
              </div>
            ) : (
              filteredLogs.map((logStr, index) => (
                <div 
                  key={index} 
                  className="hover:bg-white/5 px-2 py-0.5 rounded transition-all flex gap-2 border-l border-transparent hover:border-[#C4A47C]/40 break-words"
                >
                  {/* Console line counts */}
                  <span className="text-white/15 select-none text-right min-w-[32px] shrink-0 border-r border-white/5 pr-2">
                    {index + 1}
                  </span>
                  
                  {/* Styled log message body */}
                  <div className="text-left flex-1 whitespace-pre-wrap select-all font-mono leading-relaxed tracking-normal">
                    {formatColoredLogSpan(logStr)}
                  </div>
                </div>
              ))
            )}
          </div>

        </div>

      </div>

    </div>
  );
}
