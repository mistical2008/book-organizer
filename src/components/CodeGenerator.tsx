import React, { useState, useEffect } from "react";
import { Copy, Check, Download, Settings, RefreshCw, AlertCircle, Sparkles } from "lucide-react";
import { SystemdOptions } from "../types";

export default function CodeGenerator() {
  const [copied, setCopied] = useState(false);
  const [copiedNix, setCopiedNix] = useState(false);
  const [errorMess, setErrorMess] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);

  const [options, setOptions] = useState<any>({
    serviceName: "grimmory-librarian",
    userName: "root",
    workingDir: "/root",
    scriptPath: "/root/server.cjs",
    inputDirs: ["/var/lib/grimmory/input"],
    outputDir: "/var/lib/grimmory/sorted",
    destinationTemplate: "{Author} - {Title} ({Year})",
    geminiModel: "gemini-3.5-flash",
    confidenceThreshold: 70,
    runInterval: "hourly", // hourly, daily, boot
    processingMode: "batch",
    batchSize: 5,
    enableCaching: true,
    daemonEnabled: false
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

  const [pythonCode, setPythonCode] = useState("");

  useEffect(() => {
    const code = `#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Grimmory Standalone Library Agent - Python Version
Automatically generated according to config preferences.

Prerequisites pip:
  pip install google-genai opencv-python pillow pytesseract ebooklib beautifulsoup4 pypdf
"""
import os, sys, re, json, time, sqlite3, shutil, urllib.request, urllib.parse
from PIL import Image

INPUT_DIRS = ${JSON.stringify(options.inputDirs || ["/var/lib/grimmory/input"])}
OUTPUT_DIR = ${JSON.stringify(options.outputDir)}
DESTINATION_TEMPLATE = ${JSON.stringify(options.destinationTemplate || "{Author} - {Title} ({Year})")}
CONFIDENCE_THRESHOLD = ${options.confidenceThreshold}
GEMINI_MODEL = ${JSON.stringify(options.geminiModel)}
`;
    setPythonCode(code);
  }, [options]);

  const handleCopyPython = () => {
    navigator.clipboard.writeText(pythonCode);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleCopyNix = () => {
    navigator.clipboard.writeText(nixConfigString);
    setCopiedNix(true);
    setTimeout(() => setCopiedNix(false), 2000);
  };

  const nixConfigString = `# /etc/nixos/configuration.nix
{ config, pkgs, ... }: {
  # Import Grimmory Flake module
  imports = [
    inputs.grimmory.nixosModules.default
  ];

  services.grimmory = {
    # 1. Daemon scheduled sorting parameters
    daemon = {
      enable = ${options.daemonEnabled ? "true" : "false"};
      inputDir = "${options.inputDirs ? options.inputDirs[0] : "/var/lib/grimmory/input"}";
      outputDir = "${options.outputDir}";
      geminiModel = "${options.geminiModel}";
      confidenceThreshold = ${options.confidenceThreshold};
      interval = "${options.runInterval === "hourly" ? "*:0/15" : "daily"}";
    };

    # 2. Interactive full-stack Node web portal
    web = {
      enable = true;
      port = 3000;
    };
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
                  const updated = [...(options.inputDirs || ["/var/lib/grimmory/input"])];
                  updated.push(`/var/lib/grimmory/input_channel_${updated.length + 1}`);
                  setOptions({ ...options, inputDirs: updated });
                }}
                className="text-[#C4A47C] text-[10px] font-mono hover:underline uppercase tracking-wider cursor-pointer"
              >
                + Add Source Path
              </button>
            </div>
            <div className="flex flex-col gap-2 max-h-36 overflow-y-auto pr-1">
              {(options.inputDirs || ["/var/lib/grimmory/input"]).map((dir: string, idx: number) => (
                <div key={idx} className="flex gap-2 items-center">
                  <span className="text-[10px] font-mono text-white/30 w-4 font-bold">{idx + 1}.</span>
                  <input
                    type="text"
                    required
                    className="bg-[#121210] border border-white/10 text-xs px-3 py-2 rounded focus:outline-none focus:border-[#C4A47C]/50 text-[#C4A47C] font-mono flex-1"
                    value={dir}
                    onChange={(e) => {
                      const updated = [...options.inputDirs];
                      updated[idx] = e.target.value;
                      setOptions({ ...options, inputDirs: updated });
                    }}
                    id={`input-dir-setting-${idx}`}
                  />
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
            <input
              type="text"
              required
              className="bg-[#121210] border border-white/10 text-xs px-3 py-2 rounded focus:outline-none focus:border-[#C4A47C]/50 text-[#C4A47C] font-mono"
              value={options.outputDir}
              onChange={(e) => setOptions({ ...options, outputDir: e.target.value })}
              id="output-dir-setting"
            />
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

      {/* Decorative Tab / NixOS instructions panel */}
      <div className="bg-[#0B0B09] border border-white/5 rounded-xl p-5 md:p-6 text-left flex flex-col gap-4 font-mono text-xs">
        <div className="flex items-center justify-between border-b border-white/5 pb-2">
          <span className="text-sm uppercase tracking-widest text-[#C4A47C] flex items-center gap-2 font-bold select-none">
            <Sparkles className="w-4 h-4 text-[#C4A47C]" />
            NixOS Declarative Service Integration
          </span>
          <button
            onClick={handleCopyNix}
            className="text-white/40 hover:text-white transition-all text-[10px] flex items-center gap-1 cursor-pointer bg-white/5 px-2 py-1 rounded border border-white/10"
          >
            {copiedNix ? (
              <>
                <Check className="w-3.5 h-3.5 text-emerald-400" />
                <span>COPIED MODULE!</span>
              </>
            ) : (
              <>
                <Copy className="w-3.5 h-3.5" />
                <span>COPY NIX</span>
              </>
            )}
          </button>
        </div>

        <p className="text-[11px] leading-relaxed text-white/50 font-sans">
          To manage deployment, simply bind the package parameters inside your declarative <code className="text-[#C4A47C] font-mono">/etc/nixos/configuration.nix</code>. This establishes standard systemd service channels and ports, leaving setup entirely automatic post-rebuild!
        </p>

        <pre className="bg-[#121210] p-4 rounded-lg overflow-x-auto text-[11px] leading-relaxed border border-white/5 text-[#C4A47C]">
          {nixConfigString}
        </pre>
      </div>

    </div>
  );
}
