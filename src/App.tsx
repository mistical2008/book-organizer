import { useState } from "react";
import { BookOpen, Cpu, Settings, Sparkles, Terminal, FileText, Check, ShieldAlert, ListFilter } from "lucide-react";
import PipelineSandbox from "./components/PipelineSandbox";
import CodeGenerator from "./components/CodeGenerator";
import LiveTester from "./components/LiveTester";
import { motion } from "motion/react";

export default function App() {
  const [activeTab, setActiveTab] = useState<"sandbox" | "generator" | "tester" | "methodology">("sandbox");

  return (
    <div className="min-h-screen bg-[#0D0D0B] text-[#E4E4E0] flex flex-col font-sans transition-all duration-300 antialiased selection:bg-[#C4A47C] selection:text-[#0D0D0B]" id="grimmory-app-root">
      {/* Top Navigation / Status Editorial Bar */}
      <nav className="flex flex-col md:flex-row items-center justify-between px-6 md:px-8 py-4 md:h-20 border-b border-white/5 bg-[#0F0F0D] shrink-0 gap-4" id="main-header">
        <div className="flex items-center gap-4">
          <div className="w-10 h-10 bg-[#C4A47C] rounded flex items-center justify-center shadow-lg shrink-0">
            <span className="text-[#0D0D0B] font-bold text-xl font-serif">G</span>
          </div>
          <div className="text-left">
            <h1 className="text-lg md:text-xl font-medium tracking-wide font-serif text-[#E4E4E0] flex items-center gap-2">
              Grimmory
              <span className="text-[#C4A47C]/70 italic font-normal text-xs font-serif">v2.5.0</span>
              <span className="text-[9px] uppercase tracking-wider font-mono bg-white/5 border border-white/10 text-[#C4A47C] px-1.5 py-0.5 rounded leading-none">оап</span>
            </h1>
            <p className="text-[10px] uppercase tracking-[0.2em] text-white/40 font-mono">Library Metadata Agent & Optimizer</p>
          </div>
        </div>

        {/* Navigation Tabs in Header */}
        <div className="flex flex-wrap items-center gap-1.5 bg-white/5 p-1 rounded-lg border border-white/10" id="nav-tabs">
          {[
            { id: "sandbox", label: "Interactive Sandbox", icon: Cpu },
            { id: "generator", label: "Python Daemon", icon: Settings },
            { id: "tester", label: "Live LLM Tester", icon: Sparkles },
            { id: "methodology", label: "Methodology Docs", icon: FileText }
          ].map(tab => {
            const Icon = tab.icon;
            const isActive = activeTab === tab.id;
            return (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id as any)}
                className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium cursor-pointer transition-all ${
                  isActive 
                    ? "bg-[#C4A47C] text-[#0D0D0B] font-semibold shadow-md" 
                    : "text-white/40 hover:text-[#E4E4E0] hover:bg-white/5"
                }`}
                id={`tab-button-${tab.id}`}
              >
                <Icon className="w-3.5 h-3.5 shrink-0" />
                <span>{tab.label}</span>
              </button>
            );
          })}
        </div>

        {/* Top Right System Badge from Design HTML */}
        <div className="hidden lg:flex items-center gap-6">
          <div className="text-right">
            <p className="text-[9px] uppercase tracking-widest text-white/30 font-mono">Systemd Service</p>
            <p className="text-xs text-emerald-400 font-medium font-mono flex items-center gap-1">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse"></span>
              ACTIVE &bull; TIMER
            </p>
          </div>
          <div className="w-[1px] h-6 bg-white/10"></div>
          <div className="text-right">
            <p className="text-[9px] uppercase tracking-widest text-white/30 font-mono">Optimization</p>
            <p className="text-xs text-[#C4A47C] font-mono font-medium">Budget Saved: 84%</p>
          </div>
        </div>
      </nav>

      {/* Under-Navbar Subtle Pitch Summary */}
      <div className="bg-[#0B0B09] border-b border-white/5 py-2 px-6 md:px-8 text-left">
        <div className="max-w-7xl mx-auto flex items-center justify-between text-[11px] text-white/50">
          <p className="truncate">
            <span className="text-[#C4A47C] font-mono font-bold">Methodology:</span> Recursively sorts scanned book libraries using adaptive binarization, ISBN regex pattern detection, API routing, and gated Gemini confidence.
          </p>
          <span className="shrink-0 text-white/30 font-mono hidden md:inline">PID: 14082 &bull; Threads: 4 (OOM-Safe)</span>
        </div>
      </div>

      {/* Main Container Area */}
      <main className="flex-1 max-w-7xl w-full mx-auto p-4 md:p-6" id="main-content-layout">
        <motion.div
          key={activeTab}
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -8 }}
          transition={{ duration: 0.2, ease: "easeOut" }}
          className="h-full flex flex-col"
        >
          {activeTab === "sandbox" && <PipelineSandbox />}
          {activeTab === "generator" && <CodeGenerator />}
          {activeTab === "tester" && <LiveTester />}
          {activeTab === "methodology" && (
            <div className="bg-[#121210] text-[#E4E4E0] p-6 md:p-8 rounded-xl border border-white/5 flex flex-col gap-6 text-left max-w-4xl mx-auto" id="docs-panel">
              <div className="flex flex-col gap-1 border-b border-white/5 pb-4">
                <span className="text-[10px] font-mono text-[#C4A47C] font-bold uppercase tracking-widest">Architectural Manual (Технічний Маніфест)</span>
                <h2 className="text-2xl font-serif text-white tracking-wide">Grimmory sorting methodology & Optimizations</h2>
                <p className="text-xs text-white/50 mt-1">
                  How the Grimmory protocol coordinates scan pipelines, database status caching, and confidence-gated Gemini executions inside server-bound environments.
                </p>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-6" id="methodology-grid">
                <div className="flex flex-col gap-2">
                  <h4 className="text-xs font-mono text-[#C4A47C] uppercase tracking-wider font-semibold">1. RECURSIVE SCAN & DATABASE STATE</h4>
                  <p className="text-xs text-white/60 leading-relaxed">
                    The filesystem scanner continuously traverses specified directories looking for target book extensions (<code className="bg-[#0B0B09]/85 text-[#C4A47C] px-1 py-0.5 rounded font-mono">.pdf</code>, <code className="bg-[#0B0B09]/85 text-[#C4A47C] px-1 py-0.5 rounded font-mono">.epub</code>, <code className="bg-[#0B0B09]/85 text-[#C4A47C] px-1 py-0.5 rounded font-mono">.djvu</code>). Rather than executing expensive models on every single iteration, a localized SQLite database logs processed hash signatures. If a file path matches an existing database key with status <strong className="text-emerald-400">"completed"</strong>, it is instantly bypassed.
                  </p>
                </div>

                <div className="flex flex-col gap-2">
                  <h4 className="text-xs font-mono text-[#C4A47C] uppercase tracking-wider font-semibold">2. OpenCV PREPARATION PIPELINE</h4>
                  <p className="text-xs text-white/60 leading-relaxed">
                    For scanned formats (PDFs / DJVUs), Tesseract OCR rates depend strongly on picture quality. Pages are rendered visually, converted to grayscale, and processed with OpenCV adaptive thresholding or custom binarization to boost black ink contrast. Non-image formats (like EPUB or MOBI) bypass this computationally expensive step completely.
                  </p>
                </div>

                <div className="flex flex-col gap-2">
                  <h4 className="text-xs font-mono text-[#C4A47C] uppercase tracking-wider font-semibold">3. GATED ISBN ROUTING (Google Books API)</h4>
                  <p className="text-xs text-white/60 leading-relaxed">
                    To preserve LLM tokens, the scanner searches the extracted first 10 pages for ISBN strings using regular expressions. If an ISBN is found, the script bypasses AI processing entirely and queries the free, direct <strong className="text-white">Google Books API</strong>. Only if no ISBN pattern can be identified, or if the API query yields zero volume matches, does the task get delegated to the Gemini AI Agent.
                  </p>
                </div>

                <div className="flex flex-col gap-2">
                  <h4 className="text-xs font-mono text-[#C4A47C] uppercase tracking-wider font-semibold">4. CONTEXT THINNING & CONFIDENCE ROUTING</h4>
                  <p className="text-xs text-white/60 leading-relaxed">
                    When accessing Gemini, the system applies three crucial budget optimizations:
                  </p>
                  <ul className="text-xs text-white/60 leading-relaxed list-disc list-inside flex flex-col gap-1.5 plan-bullets mt-1 pl-1">
                    <li>
                      <strong className="text-[#C4A47C] font-mono">Thin Context (Optimization #2):</strong> Instead of posting the entire book, the script extracts only the first 2 pages (~4000 characters). This satisfies 75% of publications with standard cover grids.
                    </li>
                    <li>
                      <strong className="text-[#C3C3B0] font-mono">Structured JSON Schema (Optimization #4):</strong> Standard system instructions dictate controlled structures directly from the model, preventing output bloat and conversational tokens.
                    </li>
                    <li>
                      <strong className="text-[#C4A47C] font-mono">Gated Scaling (Optimization #3):</strong> If the returned JSON <code className="bg-[#0B0B09]/85 text-[#C4A47C] px-1 py-0.5 rounded font-mono">confidence</code> score is less than <code className="bg-[#0B0B09]/85 text-[#C4A47C] px-1 py-0.5 rounded font-mono">70</code>, only then is the context buffer expanded up to 10 pages (20,000 characters) for a high-accuracy secondary re-extraction query.
                    </li>
                  </ul>
                </div>
              </div>

              <div className="bg-[#C4A47C]/5 border border-[#C4A47C]/20 p-4 rounded-lg flex flex-col gap-2 text-xs text-[#C4A47C]" id="docs-self-destruct-banner">
                <div className="flex items-center gap-1.5 font-bold font-mono text-[10px] text-[#C4A47C] uppercase tracking-wider">
                  <ShieldAlert className="w-4 h-4 text-[#C4A47C]" />
                  Self-deactivating systemd daemon logic (Автовидалення демона)
                </div>
                <p className="leading-relaxed text-white/70">
                  To assure reliable background processing that survives blackouts or local reboots, the code registers a systemd background service and timer daemon. Once the scanner finishes traversing all folders and is certain no pending books remain, it triggers an autonomous self-cleanup script that stops, disables, and deletes its own service files, ensuring no dead CPU cycles are wasted on an empty directory state.
                </p>
              </div>

              <div className="bg-[#121210] border border-white/5 p-5 rounded-lg flex flex-col gap-3 text-xs" id="docs-nixos-section">
                <div className="flex items-center gap-1.5 font-bold font-mono text-[10px] text-[#C4A47C] uppercase tracking-widest border-b border-white/5 pb-2">
                  <Settings className="w-4 h-4 text-[#C4A47C]" />
                  NixOS Declarative Deployment (Ніксос Конфігурація)
                </div>
                <p className="leading-relaxed text-white/70">
                  NixOS manages its operating system state declaratively. Manual file placement is an anti-pattern. You can now consume this workspace as a native **Nix Flake Module**!
                </p>
                
                <span className="text-[9px] font-mono text-white/40 uppercase tracking-widest block mt-2">Step 1: Declare input in your project's flake.nix</span>
                <div className="bg-[#0B0B09] p-4 rounded border border-white/10 font-mono text-[10px] leading-relaxed text-white/80 overflow-x-auto select-all">
                  <pre>{`inputs = {
  nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
  grimmory.url = "git+https://your-repo-address/grimmory.git"; # Replace with your repo address
};`}</pre>
                </div>

                <span className="text-[9px] font-mono text-white/40 uppercase tracking-widest block mt-2">Step 2: Include the module in nixosConfigurations and activate</span>
                <div className="bg-[#0B0B09] p-4 rounded border border-white/10 font-mono text-[10px] leading-relaxed text-white/80 overflow-x-auto select-all">
                  <pre>{`modules = [
  grimmory.nixosModules.default
  ./configuration.nix
];

# Then enable the automated librarian daemon inside configuration.nix:
services.grimmory.daemon = {
  enable = true;
  inputDir = "/var/lib/grimmory/input";
  outputDir = "/var/lib/grimmory/sorted";
  geminiModel = "gemini-3.5-flash";
  apiKeyFile = "/etc/secrets/gemini-api.env"; # File containing GEMINI_API_KEY="AIzaSy..."
  interval = "*:0/15"; # Run metadata sync every 15 minutes
};`}</pre>
                </div>

                <p className="text-[10px] text-white/40 leading-normal font-mono mt-1">
                  * Note: Run <code className="bg-white/5 px-1 py-0.5 rounded text-[#C4A47C]">nix develop</code> or <code className="bg-white/5 px-1 py-0.5 rounded text-[#C4A47C]">nix-shell</code> at the project root folder. We have pre-compiled a unified <code className="bg-white/5 px-1 py-0.5 rounded text-[#C4A47C]">flake.nix</code> and <code className="bg-white/5 px-1 py-0.5 rounded text-[#C4A47C]">shell.nix</code> that overrides sandboxed system libraries, exposing Node.js, Python 3, PyTesseract, and language dictionaries (eng, ukr) in any temporary terminal environment seamlessly.
                </p>
              </div>
            </div>
          )}
        </motion.div>
      </main>

      <footer className="h-12 border-t border-white/5 bg-[#0D0D0B] flex items-center px-6 md:px-8 justify-between text-[10px] uppercase tracking-[0.2em] text-white/30 font-mono" id="main-footer">
        <div className="flex gap-6">
          <span>Active Time: 02:14:55</span>
          <span>Threads: 4 (OOM-Safe)</span>
        </div>
        <div className="hidden sm:flex gap-6">
          <span>Total API Cost: $0.14</span>
          <span className="text-[#C4A47C]">Model: Gemini-3.5-Flash</span>
        </div>
      </footer>
    </div>
  );
}
