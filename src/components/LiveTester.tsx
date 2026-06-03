import { useState } from "react";
import { Sparkles, Play, Code, AlertCircle, CheckCircle2, RefreshCw } from "lucide-react";

export default function LiveTester() {
  const [inputText, setInputText] = useState(`IВАН КOTЛЯPEBCЬКИЙ
ЕНЕЇДА
Поема в шести частинах
Харків
Мiнiстерство освіти України, 1993 р.
Бiблiотека укр. клясики.
ISBN 978-966-03-8120-9 (Помилка друку: 1SВN 978-966-О3-8120-9)
Еней був парубок моторний i хлопець хоть куди козак...`);
  
  const [model, setModel] = useState("gemini-3.5-flash");
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<any>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const testPreset = (type: string) => {
    if (type === "noisy") {
      setInputText(`[SCAN SUMMARY: PAGE 1-2]
Heчуй-Jeвицbкuй: Kaйдашеbа сiм'я. Повістb, напuсана у 1878-1879 роках.
КИЇB - ДЕPЖABA BИД-BO «ДHІПPO» - 1983
ІВАН НЕЧУЙ-ЛЕВИЦЬКИЙ
КАЙДAШEBA СІМ'Я
КНИГА ДЛЯ ШКОЛЯРІВ ТА СТУДЕНТІВ.
Редактори: О. Данченко.
Карпо і Лаврін стояли під повіткою і стругали...
[OCR ERROR: Noisy footnote indicators, Page numbered 10]`);
    } else if (type === "english") {
      setInputText(`Structure and Interpretation of Computer Programs
Second Edition
Harold Abelson and Gerald Jay Sussman
with Julie Sussman
The MIT Press, Cambridge, Massachusetts
ISBN 0-262-51087-1 (paperback), 0-262-01153-0 (hardcover)
Copyright 1996 by The Massachusetts Institute of Technology.`);
    } else {
      setInputText(`IВАН КOTЛЯPEBCЬКИЙ
ЕНЕЇДА
Поема в шести частинах
Харків
Мiнiстерство освіти України, 1993 р.
Бiблiотека укр. клясики.
ISBN 978-966-03-8120-9 (Помилка друку: 1SВN 978-966-О3-8120-9)
Еней був парубок моторний i хлопець хоть куди козак...`);
    }
  };

  const executeExtraction = async () => {
    if (!inputText.trim() || loading) return;
    setLoading(true);
    setErrorMsg(null);
    setResult(null);

    try {
      const response = await fetch("/api/librarian/extract", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          ocrText: inputText,
          modelName: model
        })
      });

      const payload = await response.json();

      if (!response.ok) {
        throw new Error(payload.error || "Failed to parse metadata from Gemini API.");
      }

      setResult(payload);
    } catch (err: any) {
      console.error(err);
      setErrorMsg(err.message || "An unexpected error occurred during client invocation.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="bg-[#0B0B09] text-[#E4E4E0] p-5 md:p-6 rounded-xl border border-white/5 flex flex-col gap-5" id="live-tester-panel">
      <div className="flex flex-col gap-1 text-left">
        <h2 className="text-xl font-serif font-semibold text-white/95 tracking-tight flex items-center gap-2">
          <Sparkles className="w-5 h-5 text-[#C4A47C]" />
          Live Gemini Extraction Tester
        </h2>
        <p className="text-xs text-white/50">
          Paste raw library scans or multi-lingual book texts. Select a Gemini model to test gated thinning and confidence routing in real-time.
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5" id="tester-cols-grid">
        {/* Input Left column */}
        <div className="flex flex-col gap-4">
          <div className="flex items-center justify-between">
            <span className="text-[10px] font-mono text-white/40 uppercase tracking-widest">Input Raw OCR Text Buffer</span>
            <div className="flex items-center gap-2 text-[10px] font-mono">
              <span className="text-white/30 font-sans">Presets:</span>
              <button 
                onClick={() => testPreset("kotl")}
                className="text-[#C4A47C] hover:underline cursor-pointer font-semibold uppercase tracking-wider"
                id="test-preset-kotl"
              >
                Енеїда
              </button>
              <span className="text-white/10">|</span>
              <button 
                onClick={() => testPreset("noisy")}
                className="text-[#C4A47C] hover:underline cursor-pointer font-semibold uppercase tracking-wider"
                id="test-preset-noisy"
              >
                Кайдашева
              </button>
              <span className="text-white/10">|</span>
              <button 
                onClick={() => testPreset("english")}
                className="text-[#C4A47C] hover:underline cursor-pointer font-semibold uppercase tracking-wider"
                id="test-preset-english"
              >
                SICP
              </button>
            </div>
          </div>

          <textarea
            value={inputText}
            onChange={(e) => setInputText(e.target.value)}
            className="bg-[#121210] border border-white/10 rounded-lg p-3 text-xs font-mono text-emerald-400 h-64 focus:outline-none focus:border-[#C4A47C]/50 resize-none leading-relaxed text-left"
            placeholder="Paste raw scanner text or mock pages..."
            id="tester-text-area"
          />

          <div className="flex items-center justify-between gap-4" id="tester-options-row">
            <div className="flex items-center gap-4">
              <div className="flex flex-col gap-1 text-left">
                <span className="text-[9px] font-mono text-white/40 uppercase tracking-tight">Active Model Name</span>
                <select
                  value={model}
                  onChange={(e) => setModel(e.target.value)}
                  className="bg-[#121210] border border-white/10 text-xs px-3 py-1.5 rounded text-[#C4A47C] font-mono focus:outline-none focus:border-[#C4A47C]/50"
                  id="tester-model-select"
                >
                  <option value="gemini-3.5-flash">gemini-3.5-flash</option>
                  <option value="gemini-3.1-pro-preview">gemini-3.1-pro-preview</option>
                </select>
              </div>
            </div>

            <button
              onClick={executeExtraction}
              disabled={loading || !inputText.trim()}
              className={`flex items-center gap-1.5 px-4 py-2.5 border text-xs font-mono uppercase tracking-wider font-semibold rounded-lg transition-all cursor-pointer self-end ${
                loading 
                  ? "bg-[#C4A47C]/10 border-[#C4A47C]/25 text-[#C4A47C] cursor-not-allowed animate-pulse" 
                  : "bg-transparent text-[#C4A47C] hover:bg-[#C4A47C] hover:text-[#0D0D0B] border-[#C4A47C]/40"
              }`}
              id="tester-submit-btn"
            >
              {loading ? (
                <>
                  <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                  Running Agent...
                </>
              ) : (
                <>
                  <Play className="w-3.5 h-3.5" />
                  Classify Raw Text
                </>
              )}
            </button>
          </div>
        </div>

        {/* Output Right Column */}
        <div className="flex flex-col gap-4 text-left">
          <span className="text-[10px] font-mono text-white/40 uppercase tracking-widest flex items-center gap-1">
            <Code className="w-3.5 h-3.5 text-[#C4A47C]" />
            Classified API response
          </span>

          <div className="bg-[#121210] border border-white/5 rounded-lg p-4 h-64 overflow-y-auto flex flex-col justify-between font-mono relative">
            {loading && (
              <div className="absolute inset-0 bg-[#0D0D0B]/90 flex flex-col items-center justify-center gap-2" id="tester-loader-overlay">
                <RefreshCw className="w-8 h-8 text-[#C4A47C] animate-spin" />
                <span className="text-xs text-[#C4A47C] font-mono uppercase tracking-widest font-semibold">Inquiring LLM Agent...</span>
                <span className="text-[10px] text-white/40 font-mono text-center px-4 leading-normal max-w-xs">Preparing prompt schemas. Calculating output scores.</span>
              </div>
            )}

            {errorMsg ? (
              <div className="flex flex-col gap-2 p-3 text-red-400 bg-red-950/25 border border-red-900/30 rounded-lg text-xs leading-relaxed" id="tester-error-box">
                <div className="flex items-center gap-1.5 font-bold font-mono uppercase text-[10px] tracking-wider text-red-300">
                  <AlertCircle className="w-4 h-4 text-red-500" />
                  Request Failed
                </div>
                <p className="font-sans text-white/80">{errorMsg}</p>
                <div className="mt-2 text-[10px] font-sans text-white/40 leading-normal">
                  <strong className="text-white/60">Troubleshooting:</strong> Open the <strong className="text-[#C4A47C]">Secrets</strong> manager. Provide a valid <code className="bg-white/5 px-1 py-0.5 rounded text-[#C4A47C]">GEMINI_API_KEY</code> token.
                </div>
              </div>
            ) : result ? (
              <div className="flex flex-col gap-3 h-full justify-between">
                <div className="bg-[#C4A47C]/10 border border-[#C4A47C]/30 p-3 rounded-lg text-xs">
                  <div className="flex items-center gap-1.5 font-bold font-mono uppercase text-[9px] tracking-wider text-[#C4A47C] mb-1">
                    <CheckCircle2 className="w-3.5 h-3.5" />
                    Extraction metrics
                  </div>
                  <div className="grid grid-cols-2 gap-2 mt-2 font-mono text-[10px] text-white/60">
                    <div>
                      <span>Confidence score:</span>{" "}
                      <strong className={`font-mono ${result.data?.confidence >= 70 ? "text-emerald-400" : "text-[#C4A47C]"}`}>
                        {result.data?.confidence || 0}%
                      </strong>
                    </div>
                    <div>
                      <span>Expansions triggered:</span>{" "}
                      <strong className="text-white">{result.expandedContextCalled ? "YES (Dual Page)" : "NO (Single Page)"}</strong>
                    </div>
                    <div>
                      <span>Total characters analyzed:</span>{" "}
                      <strong className="text-white">{result.contextLengthUsed} chars</strong>
                    </div>
                  </div>
                </div>

                <div className="overflow-y-auto max-h-40 leading-relaxed text-[11px] text-emerald-400 p-2 rounded bg-[#0B0B09] border border-white/5 text-left select-all">
                  <pre>{JSON.stringify(result.data, null, 2)}</pre>
                </div>
              </div>
            ) : (
              <div className="h-full flex flex-col justify-center items-center text-center text-white/30 gap-1 font-sans">
                <Sparkles className="w-8 h-8 text-white/10 stroke-1 mb-1" />
                <span className="text-xs font-mono uppercase tracking-wider text-white/40">Tester Output Console</span>
                <p className="text-[10px] text-white/30 max-w-xs leading-normal font-mono">
                  Provide custom book text lines and click "Classify Raw Text" to verify active LLM parsing schemas.
                </p>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
