import express from "express";
import path from "path";
import fs from "fs";
import https from "https";
import { createServer as createViteServer } from "vite";
import { GoogleGenAI, Type } from "@google/genai";
import dotenv from "dotenv";

dotenv.config();

// Create application data directories on the host/container
const DATA_DIR = path.resolve(process.cwd(), "data");
const CONFIG_FILE = path.join(DATA_DIR, "config.json");
const STATE_FILE = path.join(DATA_DIR, "state.json");
const LOGS_FILE = path.join(DATA_DIR, "logs.json");

const logs: string[] = [];
let isProcessing = false;
let lastRunTime: string | null = null;
let daemonTimer: NodeJS.Timeout | null = null;

function addServerLog(msg: string) {
  const timestamp = new Date().toLocaleTimeString();
  const entry = `[${timestamp}] ${msg}`;
  logs.push(entry);
  console.log(`[Grimmory Logger] ${msg}`);
  if (logs.length > 200) {
    logs.shift();
  }
  try {
    fs.writeFileSync(LOGS_FILE, JSON.stringify(logs, null, 2));
  } catch (err) {}
}

const PRESETS = [
  {
    fileName: "kotlyarevsky_eneida_scanned_v3.pdf",
    content: `IВАН КOTЛЯPEBCЬКИЙ\nЕНЕЇДА\nПоема в шести частинах\nХарків\nМiнiстерство освіти України, 1993 р.\nБiблiотека укр. клясики.\nУДК 821.161.2\nББК 84(4УКР)\nISBN 978-966-03-8120-9 (Помилка друку: 1SВN 978-966-О3-8120-9)\nЕней був парубок моторний i хлопець хоть куди козак...\nЗшито у друкарні Г. П. Квітки, 1798 рік вид.`
  },
  {
    fileName: "kaydasheva_simya_rotated.djvu",
    content: `КИЇB - ДЕPЖABA BИД-BO «ДHІПPO» - 1983\nІВАН НЕЧУЙ-ЛЕВИЦЬКИЙ\nКАЙДAШEBA СІМ'Я\nПовiсть на дві часті.\nМал. О. Данченка.\n[ OCR ERROR: Heчуй-Jeвицbкuй: Kaйдашеbа сiм'я. Повістb, напuсана у 1878-1879 роках. ]\nКарпо i Лаврін стояли під повіткою і стругали...`
  },
  {
    fileName: "kobzar_shevchenko_2012_edition.pdf",
    content: `Тарас ШЕВЧЕНКО\nК О Б З А Р\nПовне видання з ілюстраціями.\nВидавництво Книжковий Клуб, 2012\nОригінальне видання датується 1840 роком у Санкт-Петербурзі.\nІSВN 978 - 966 - 2449 - 01 - 3\n[ OCR Noise: Т. Г. Шeвчeнкo, Ko6зapь. Думи мої, думи мої, лихо мені з вами! ]`
  },
  {
    fileName: "sicp_mit_press.epub",
    content: `Structure and Interpretation of Computer Programs\nSecond Edition\nHarold Abelson and Gerald Jay Sussman\nwith Julie Sussman\nThe MIT Press, Cambridge, Massachusetts\nISBN 0-262-51087-1 (paperback), 0-262-01153-0 (hardcover)\nCopyright 1996 by The Massachusetts Institute of Technology.`
  }
];

function seedDemoFiles(inputDir: string) {
  try {
    let filesSeeded = false;
    PRESETS.forEach(p => {
      const filePath = path.join(inputDir, p.fileName);
      if (!fs.existsSync(filePath)) {
        fs.writeFileSync(filePath, p.content, "utf8");
        filesSeeded = true;
      }
    });
    if (filesSeeded) {
      addServerLog("Successfully pre-populated books in input scan directory.");
    }
  } catch (e: any) {
    console.error("Failed to seed demo files:", e.message);
  }
}

function initFilesystem() {
  if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
  }

  const defaultConfig = {
    serviceName: "grimmory-librarian",
    userName: "root",
    workingDir: process.cwd(),
    scriptPath: path.join(process.cwd(), "dist/server.cjs"),
    inputDirs: [path.join(DATA_DIR, "books_to_sort")],
    outputDir: path.join(DATA_DIR, "sorted_library"),
    destinationTemplate: "{Author} - {Title} ({Year})",
    geminiModel: "gemini-3.5-flash",
    confidenceThreshold: 70,
    runIntervalMs: 300000, // 5 minutes default daemon timer
    daemonEnabled: false,
    processingMode: "batch",
    batchSize: 5,
    enableCaching: true
  };

  if (!fs.existsSync(CONFIG_FILE)) {
    fs.writeFileSync(CONFIG_FILE, JSON.stringify(defaultConfig, null, 2));
  }

  const defaultState = {
    scanned_books: [],
    isbn_requests: [],
    ai_categorization: [],
    file_organization: []
  };

  if (!fs.existsSync(STATE_FILE)) {
    fs.writeFileSync(STATE_FILE, JSON.stringify(defaultState, null, 2));
  } else {
    try {
      const savedState = JSON.parse(fs.readFileSync(STATE_FILE, "utf-8"));
      if (savedState.file_organization && savedState.file_organization.length > 0) {
        const lastRec = savedState.file_organization[savedState.file_organization.length - 1];
        lastRunTime = lastRec.timestamp || null;
      }
    } catch (e) {}
  }

  if (fs.existsSync(LOGS_FILE)) {
    try {
      const savedLogs = JSON.parse(fs.readFileSync(LOGS_FILE, "utf-8"));
      if (Array.isArray(savedLogs)) {
        logs.push(...savedLogs);
      }
    } catch (e) {}
  }

  if (logs.length === 0) {
    addServerLog("Grimmory Database & Full-Stack Daemon active. File system monitoring initialized.");
  }

  // Ensure active paths
  try {
    const activeConf = JSON.parse(fs.readFileSync(CONFIG_FILE, "utf-8"));
    const mainInput = activeConf.inputDirs ? activeConf.inputDirs[0] : activeConf.inputDir;
    if (!fs.existsSync(mainInput)) {
      fs.mkdirSync(mainInput, { recursive: true });
    }
    if (!fs.existsSync(activeConf.outputDir)) {
      fs.mkdirSync(activeConf.outputDir, { recursive: true });
    }
    seedDemoFiles(mainInput);
  } catch (e) {}
}

function queryGoogleBooksByIsbn(isbn: string): Promise<any> {
  return new Promise((resolve, reject) => {
    const cleanIsbn = isbn.replace(/\D/g, "");
    const url = `https://www.googleapis.com/books/v1/volumes?q=isbn:${cleanIsbn}`;
    
    const req = https.get(url, { headers: { "User-Agent": "Grimmory-Web/1.0" } }, (res) => {
      let data = "";
      res.on("data", (chunk) => { data += chunk; });
      res.on("end", () => {
        try {
          const parsed = JSON.parse(data);
          if (parsed.totalItems > 0 && parsed.items && parsed.items[0]) {
            const info = parsed.items[0].volumeInfo;
            const author = info.authors ? info.authors.join(", ") : "Unknown Author";
            const title = info.title || "Unknown Title";
            const yearStr = info.publishedDate ? info.publishedDate.substring(0, 4) : null;
            const year = yearStr && !isNaN(Number(yearStr)) ? Number(yearStr) : null;
            const genre = info.categories ? info.categories[0] : "General Study";

            resolve({
              author,
              title,
              year,
              genre,
              isbn: cleanIsbn,
              confidence: 100,
              notes: "Direct match from official ISBN catalog registry."
            });
          } else {
            resolve(null);
          }
        } catch (e) {
          resolve(null);
        }
      });
    });

    req.on("error", (err) => {
      reject(err);
    });

    // Timeout to prevent hanging
    req.setTimeout(8000, () => {
      req.destroy();
      resolve(null);
    });
  });
}

async function extractMetadataViaGemini(ocrText: string, modelName: string): Promise<any> {
  const apiKey = process.env.GEMINI_API_KEY;
  if (!apiKey) {
    throw new Error("GEMINI_API_KEY is not defined inside environmental configuration.");
  }

  const ai = new GoogleGenAI({
    apiKey,
    httpOptions: {
      headers: {
        'User-Agent': 'aistudio-build',
      }
    }
  });

  const thinOcr = (text: string) => {
    const cleaned = text.replace(/\s+/g, ' ');
    return cleaned.substring(0, 4000);
  };

  const systemInstruction = `Act as the Grimmory Library Metadata Agent. Your task is to extract book metadata from OCR-text of the first pages.
RULES:
1. CLEAN: Fix OCR errors in Author/Title based on context.
2. EXTRACT: Find [author, title, year, genre, isbn].
3. CLASSIFY: For 'genre', provide the general field of study (e.g., History, Fiction, Physics, Linguistics, Computer Science, Economics, Medicine).
4. CONFIDENCE: Rate accuracy from 0 to 100. If confidence is <70, explain exactly why in the 'notes' field.
5. OUTPUT: Return strictly valid JSON conforming to the requested schema.`;

  const responseSchema = {
    type: Type.OBJECT,
    properties: {
      author: { type: Type.STRING, description: "Normalized author name (e.g. Taras Shevchenko, Isaac Newton)" },
      title: { type: Type.STRING, description: "Normalized book title" },
      year: { type: Type.INTEGER, description: "Four-digit publication year, or null if unknown" },
      genre: { type: Type.STRING, description: "General field of study or category (e.g., Philosophy, Science fiction, History, Computer science, Poetry)" },
      isbn: { type: Type.STRING, description: "Extracted 10 or 13-digit ISBN without hyphens/spaces, or null if missing" },
      confidence: { type: Type.INTEGER, description: "Rating score of accuracy from 0 to 100 based on text completeness" },
      notes: { type: Type.STRING, description: "Explanation of why confidence is low or general extraction metadata comments" }
    },
    required: ["author", "title", "year", "genre", "isbn", "confidence", "notes"]
  };

  const initialText = thinOcr(ocrText);
  const response = await ai.models.generateContent({
    model: modelName,
    contents: initialText,
    config: {
      systemInstruction,
      responseMimeType: "application/json",
      responseSchema,
      temperature: 0.1,
    }
  });

  const responseText = response.text || "{}";
  let data = JSON.parse(responseText.trim());

  if (data.confidence < 70 && ocrText.length > 4000) {
    addServerLog(`Confidence returned low (${data.confidence}/100). Expanding analysis up to 20,000 characters...`);
    const expandedText = ocrText.replace(/\s+/g, ' ').substring(0, 20000);
    const secondResponse = await ai.models.generateContent({
      model: modelName,
      contents: expandedText,
      config: {
        systemInstruction,
        responseMimeType: "application/json",
        responseSchema,
        temperature: 0.1,
      }
    });
    const secondResponseText = secondResponse.text || "{}";
    data = JSON.parse(secondResponseText.trim());
  }

  return data;
}

async function runLibrarianSync() {
  if (isProcessing) {
    addServerLog("A synchronization pipeline run is already operating. Skipping trigger.");
    return;
  }

  isProcessing = true;
  addServerLog("----------------------------------------------------------------------");
  addServerLog("🔍 INITIATING AUTOMATED LIBRARY SYNC PIPELINE RUN");
  addServerLog("----------------------------------------------------------------------");

  try {
    const config = JSON.parse(fs.readFileSync(CONFIG_FILE, "utf-8"));
    const state = JSON.parse(fs.readFileSync(STATE_FILE, "utf-8"));

    const inputDirs = config.inputDirs || [config.inputDir];
    const outputDir = config.outputDir;
    const destTemplate = config.destinationTemplate || "{Author} - {Title} ({Year})";
    const confidenceThreshold = config.confidenceThreshold || 70;
    const geminiModel = config.geminiModel || "gemini-3.5-flash";
    const batchSize = config.batchSize || 5;

    const filesToProcess: { filepath: string; filename: string }[] = [];

    inputDirs.forEach((dir: string) => {
      const resolvedDir = path.resolve(dir);
      if (fs.existsSync(resolvedDir)) {
        try {
          const items = fs.readdirSync(resolvedDir);
          items.forEach(item => {
            const itemPath = path.join(resolvedDir, item);
            const isDir = fs.statSync(itemPath).isDirectory();
            if (!isDir) {
              const ext = path.extname(item).toLowerCase();
              if ([".pdf", ".epub", ".djvu", ".txt", ".mobi", ".fb2"].includes(ext)) {
                filesToProcess.push({ filepath: itemPath, filename: item });
              }
            }
          });
        } catch (e: any) {
          addServerLog(`Error accessing scanned directories: ${resolvedDir} (${e.message})`);
        }
      } else {
        try {
          fs.mkdirSync(resolvedDir, { recursive: true });
          addServerLog(`Created input scanning directory path: ${resolvedDir}`);
        } catch (e) {}
      }
    });

    addServerLog(`Scan complete. Found ${filesToProcess.length} raw files in the target directories.`);

    let processedCount = 0;
    for (const file of filesToProcess) {
      if (config.processingMode === "batch" && processedCount >= batchSize) {
        addServerLog(`Batch size threshold (${batchSize}) reached. Postponing remaining entries for the next synchronized execution.`);
        break;
      }

      const { filepath, filename } = file;

      // Check if processed already
      const scanRecordIdx = state.scanned_books.findIndex((r: any) => r.filepath === filepath);
      const isAlreadyCompleted = scanRecordIdx >= 0 && state.scanned_books[scanRecordIdx].status === "completed";

      if (isAlreadyCompleted && config.enableCaching !== false) {
        continue;
      }

      addServerLog(`Syncing book: ${filename}`);

      // Register initial state
      const scanRecord = {
        filepath,
        filename,
        isbn_detected: null as string | null,
        status: "processing",
        timestamp: new Date().toISOString()
      };

      if (scanRecordIdx >= 0) state.scanned_books[scanRecordIdx] = scanRecord;
      else state.scanned_books.push(scanRecord);

      // Read content text
      let textContent = "";
      try {
        textContent = fs.readFileSync(filepath, "utf-8");
      } catch (err) {}

      // Fallback matching using preset names for flawless demo experience
      if (!textContent || textContent.length < 50) {
        const matchingPreset = PRESETS.find(p => filename.toLowerCase().includes(p.fileName.split(".")[0]));
        if (matchingPreset) {
          textContent = matchingPreset.content;
        } else {
          textContent = `File: ${filename}\nBinary document context. Mocked OCR layout parsing active.`;
        }
      }

      // Check ISBN inside text
      const isbnRegex = /(?:ISBN(?:[- ]*1[03])?:?\\s*)?((?:97[89][- ]?)?(?:\\d[- ]?){9}[\\dXx])/i;
      const match = textContent.match(isbnRegex);
      const isbnResolved = match ? match[1].replace(/[- ]/g, "") : null;

      if (isbnResolved) {
        scanRecord.isbn_detected = isbnResolved;
        if (scanRecordIdx >= 0) state.scanned_books[scanRecordIdx] = scanRecord;
      }

      let metadata: any = null;
      let syncSource = "";

      // 1. Try ISBN direct catalog fetch
      if (isbnResolved) {
        addServerLog(`[Gated Rule Match] Valid ISBN detected: ${isbnResolved}. Querying Google Books API...`);
        const isbnReqIdx = state.isbn_requests.findIndex((r: any) => r.filepath === filepath);
        const isbnRecord = {
          filepath,
          isbn: isbnResolved,
          status: "pending",
          timestamp: new Date().toISOString()
        };

        if (isbnReqIdx >= 0) state.isbn_requests[isbnReqIdx] = isbnRecord;
        else state.isbn_requests.push(isbnRecord);

        try {
          const apiMeta = await queryGoogleBooksByIsbn(isbnResolved);
          if (apiMeta) {
            metadata = apiMeta;
            syncSource = "Google Books API Catalog Lookup";
            addServerLog(`[Success] Direct ISBN catalog match! Title: "${metadata.title}" by ${metadata.author}. Saved LLM token bills.`);
            
            // Update ISBN request table
            const finalIsbnIdx = state.isbn_requests.findIndex((r: any) => r.filepath === filepath);
            if (finalIsbnIdx >= 0) {
              state.isbn_requests[finalIsbnIdx].status = "completed";
            }
          } else {
            addServerLog(`Null registry result from Google Books. Falling back to AI model schema matching.`);
            const finalIsbnIdx = state.isbn_requests.findIndex((r: any) => r.filepath === filepath);
            if (finalIsbnIdx >= 0) state.isbn_requests[finalIsbnIdx].status = "failed";
          }
        } catch (e: any) {
          addServerLog(`ISBN Resolver request error (${e.message}). Scheduling LLM fallback execution.`);
          const finalIsbnIdx = state.isbn_requests.findIndex((r: any) => r.filepath === filepath);
          if (finalIsbnIdx >= 0) state.isbn_requests[finalIsbnIdx].status = "failed";
        }
      }

      // 2. Try Gemini fallback trigger
      if (!metadata) {
        addServerLog(`[Gated AI Trigger] Running server-side Gemini Model (${geminiModel}) on text buffer...`);
        const aiIdx = state.ai_categorization.findIndex((r: any) => r.filepath === filepath);
        const aiRecord = {
          filepath,
          text_preview: textContent.substring(0, 300) + "...",
          status: "pending",
          timestamp: new Date().toISOString()
        };

        if (aiIdx >= 0) state.ai_categorization[aiIdx] = aiRecord;
        else state.ai_categorization.push(aiRecord);

        try {
          const aiMeta = await extractMetadataViaGemini(textContent, geminiModel);
          if (aiMeta) {
            metadata = aiMeta;
            syncSource = `Gemini AI Model [${geminiModel}]`;
            
            const finalAiIdx = state.ai_categorization.findIndex((r: any) => r.filepath === filepath);
            if (finalAiIdx >= 0) state.ai_categorization[finalAiIdx].status = "completed";
          }
        } catch (e: any) {
          addServerLog(`[Failure] Gemini model error: ${e.message || e}`);
          const finalAiIdx = state.ai_categorization.findIndex((r: any) => r.filepath === filepath);
          if (finalAiIdx >= 0) state.ai_categorization[finalAiIdx].status = "failed";

          // Parse name as title fallback to prevent process failure
          const parsedWords = filename.replace(/\.[^/.]+$/, "").split(/[_\s-]+/);
          const simpleTitle = parsedWords.map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
          metadata = {
            author: "Unknown Author",
            title: simpleTitle,
            year: new Date().getFullYear(),
            genre: "General Study",
            isbn: isbnResolved || "",
            confidence: 30,
            notes: `Auto-generated fallback. Server encountered error: ${e.message || "Network Timeout"}`
          };
          syncSource = "Fallback Local Header Scanner";
        }
      }

      // 3. Move and Organize File
      if (metadata) {
        const author = metadata.author || "Unknown Author";
        const title = metadata.title || "Unknown Title";
        const year = metadata.year ? String(metadata.year) : "Unknown Year";
        const genre = metadata.genre || "Uncategorized";
        const isbn = metadata.isbn || isbnResolved || "No ISBN";

        let computedName = destTemplate
          .replace(/{Author}/g, author)
          .replace(/{Title}/g, title)
          .replace(/{Year}/g, year)
          .replace(/{Genre}/g, genre)
          .replace(/{ISBN}/g, isbn);

        const sanitizedName = computedName.replace(/[/\\?%*:|"<>\s]+/g, " ").trim();
        const originalExt = path.extname(filename) || ".pdf";
        const targetFilename = `${sanitizedName}${originalExt}`;

        const categoryFolder = path.join(outputDir, genre.replace(/[/\\?%*:|"<>\s]+/g, "_"));
        const finalDestPath = path.join(categoryFolder, targetFilename);

        addServerLog(`[Relocating System] Moving ${filename} to: ${finalDestPath}`);

        try {
          if (!fs.existsSync(categoryFolder)) {
            fs.mkdirSync(categoryFolder, { recursive: true });
          }

          // Write file copies to destination folder
          fs.copyFileSync(filepath, finalDestPath);

          // If cleanup is enabled, purge original
          if (config.autoCleanup) {
            try {
              fs.unlinkSync(filepath);
              addServerLog(`[Purged original file] ${filename}`);
            } catch (unlinkErr: any) {
              addServerLog(`Cleanup error, file busy: ${unlinkErr.message}`);
            }
          }

          // Register in file_organization state tables
          const orgIdx = state.file_organization.findIndex((r: any) => r.filepath === filepath);
          const orgRecord = {
            filepath,
            dest_path: finalDestPath,
            author,
            title,
            year: metadata.year ? Number(metadata.year) : null,
            genre,
            isbn: metadata.isbn || null,
            confidence: metadata.confidence || 100,
            status: "completed",
            notes: `Organized into catalog. Sourced from: ${syncSource}. Comments: ${metadata.notes || "None"}`,
            timestamp: new Date().toISOString()
          };

          if (orgIdx >= 0) state.file_organization[orgIdx] = orgRecord;
          else state.file_organization.push(orgRecord);

          // Update scan register to completed
          const finalScanIdx = state.scanned_books.findIndex((r: any) => r.filepath === filepath);
          if (finalScanIdx >= 0) {
            state.scanned_books[finalScanIdx].status = "completed";
          }

          processedCount++;
        } catch (orgErr: any) {
          addServerLog(`[Movement Task Error] ${orgErr.message}`);
          const finalScanIdx = state.scanned_books.findIndex((r: any) => r.filepath === filepath);
          if (finalScanIdx >= 0) {
            state.scanned_books[finalScanIdx].status = "failed";
          }
        }
      }
    }

    lastRunTime = new Date().toISOString();
    fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2));
    addServerLog(`📚 Sync iteration completed successfully. Reorganized ${processedCount} books.`);
  } catch (err: any) {
    addServerLog(`Fatal Pipeline Crash: ${err.message}`);
  } finally {
    isProcessing = false;
  }
}

function startDaemon() {
  try {
    const config = JSON.parse(fs.readFileSync(CONFIG_FILE, "utf-8"));
    if (daemonTimer) {
      clearInterval(daemonTimer);
      daemonTimer = null;
    }
    if (config.daemonEnabled) {
      const intervalMs = config.runIntervalMs || 300000;
      addServerLog(`Background scheduler DAEMON IS ACTIVE. Directory polling rate: ${intervalMs / 1000}s.`);
      daemonTimer = setInterval(() => {
        addServerLog("Scheduled daemon interval matched folder state. Running scan sync...");
        runLibrarianSync();
      }, intervalMs);
    } else {
      addServerLog("Background scheduler daemon is IDLE (Manual sync only).");
    }
  } catch (e: any) {
    console.error("Failed to start background interval timer:", e.message);
  }
}

async function startServer() {
  initFilesystem();
  startDaemon();

  const app = express();
  const PORT = 3000;

  app.use(express.json());

  // Google Gemini Extract API Proxy endpoint
  app.post("/api/grimmory/extract", async (req, res) => {
    try {
      const { ocrText, modelName } = req.body;
      if (!ocrText) {
        return res.status(400).json({ error: "Missing OCR text to extract metadata from." });
      }
      const model = modelName || "gemini-3.5-flash";
      const data = await extractMetadataViaGemini(ocrText, model);
      return res.json({
        success: true,
        data,
        contextLengthUsed: ocrText.length
      });
    } catch (err: any) {
      console.error("[Grimmory API Error]:", err);
      return res.status(500).json({ error: err?.message || "Internal server error occurred during extraction." });
    }
  });

  // REST API: Get current options config from server
  app.get("/api/config", (req, res) => {
    try {
      const config = JSON.parse(fs.readFileSync(CONFIG_FILE, "utf-8"));
      res.json(config);
    } catch (e: any) {
      res.status(500).json({ error: e.message });
    }
  });

  // REST API: Save options config on server and apply daemon scheduling changes
  app.post("/api/config", (req, res) => {
    try {
      const savedConfig = JSON.parse(fs.readFileSync(CONFIG_FILE, "utf-8"));
      const updatedConfig = {
        ...savedConfig,
        ...req.body
      };
      
      // Keep working settings
      updatedConfig.workingDir = process.cwd();
      updatedConfig.scriptPath = path.join(process.cwd(), "dist/server.cjs");

      fs.writeFileSync(CONFIG_FILE, JSON.stringify(updatedConfig, null, 2));
      addServerLog("Successfully updated and stored daemon parameters.");

      // Restart background schedulers
      startDaemon();

      res.json({ success: true, config: updatedConfig });
    } catch (e: any) {
      res.status(500).json({ error: e.message });
    }
  });

  // REST API: Get current scanner, thread and folder statistics
  app.get("/api/status", (req, res) => {
    try {
      const config = JSON.parse(fs.readFileSync(CONFIG_FILE, "utf-8"));
      
      let inputFilesCount = 0;
      const inputDirs = config.inputDirs || [config.inputDir];
      inputDirs.forEach((dir: string) => {
        const resolved = path.resolve(dir);
        if (fs.existsSync(resolved)) {
          try {
            const files = fs.readdirSync(resolved);
            files.forEach(f => {
              const ext = path.extname(f).toLowerCase();
              if ([".pdf", ".epub", ".djvu", ".txt", ".mobi", ".fb2"].includes(ext)) {
                inputFilesCount++;
              }
            });
          } catch (e) {}
        }
      });

      let sortedFilesCount = 0;
      const outputDir = path.resolve(config.outputDir);
      if (fs.existsSync(outputDir)) {
        try {
          const recurseCount = (dirPath: string) => {
            const items = fs.readdirSync(dirPath);
            items.forEach(it => {
              const fullItem = path.join(dirPath, it);
              if (fs.statSync(fullItem).isDirectory()) {
                recurseCount(fullItem);
              } else {
                const ext = path.extname(it).toLowerCase();
                if ([".pdf", ".epub", ".djvu", ".txt", ".mobi", ".fb2"].includes(ext)) {
                  sortedFilesCount++;
                }
              }
            });
          };
          recurseCount(outputDir);
        } catch (e) {}
      }

      res.json({
        isProcessing,
        lastRunTime,
        daemonEnabled: config.daemonEnabled,
        runIntervalMs: config.runIntervalMs,
        stats: {
          inputFilesCount,
          sortedFilesCount
        }
      });
    } catch (e: any) {
      res.status(500).json({ error: e.message });
    }
  });

  // REST API: Trigger immediate manual directory processing
  app.post("/api/trigger", async (req, res) => {
    if (isProcessing) {
      return res.status(409).json({ error: "Librarian sync execution already active." });
    }
    // Launch as promise so it doesn't block the UI thread response
    runLibrarianSync();
    res.json({ success: true, message: "Manual trigger received. Pipeline initialized inside backend worker thread." });
  });

  // REST API: Fetch all structured database logs
  app.get("/api/database", (req, res) => {
    try {
      if (!fs.existsSync(STATE_FILE)) {
        res.json({ scanned_books: [], isbn_requests: [], ai_categorization: [], file_organization: [] });
        return;
      }
      const state = JSON.parse(fs.readFileSync(STATE_FILE, "utf-8"));
      res.json(state);
    } catch (e: any) {
      res.status(500).json({ error: e.message });
    }
  });

  // REST API: Fetch live system logging blocks for client console feeds
  app.get("/api/logs", (req, res) => {
    res.json({ logs });
  });

  // REST API: Erase sorted target dirs and reseed raw books for demo purposes
  app.post("/api/reset-demo", (req, res) => {
    try {
      const config = JSON.parse(fs.readFileSync(CONFIG_FILE, "utf-8"));
      const mainInput = config.inputDirs ? config.inputDirs[0] : config.inputDir;

      if (!fs.existsSync(mainInput)) {
        fs.mkdirSync(mainInput, { recursive: true });
      }
      seedDemoFiles(mainInput);

      const clearedState = {
        scanned_books: [],
        isbn_requests: [],
        ai_categorization: [],
        file_organization: []
      };
      fs.writeFileSync(STATE_FILE, JSON.stringify(clearedState, null, 2));

      const outputFolder = path.resolve(config.outputDir);
      if (fs.existsSync(outputFolder)) {
        try {
          const deleteRecursive = (dirPath: string) => {
            if (fs.existsSync(dirPath)) {
              fs.readdirSync(dirPath).forEach(file => {
                const currentFile = path.join(dirPath, file);
                if (fs.lstatSync(currentFile).isDirectory()) {
                  deleteRecursive(currentFile);
                } else {
                  fs.unlinkSync(currentFile);
                }
              });
              if (dirPath !== outputFolder) {
                fs.rmdirSync(dirPath);
              }
            }
          };
          deleteRecursive(outputFolder);
        } catch (cleanErr) {}
      }

      addServerLog("Reset pipeline state completed. Purged sorted records and re-seeded input library.");
      res.json({ success: true });
    } catch (e: any) {
      res.status(500).json({ error: e.message });
    }
  });

  // REST API: Get list of active files in the input directory
  app.get("/api/system/files", (req, res) => {
    try {
      const config = JSON.parse(fs.readFileSync(CONFIG_FILE, "utf-8"));
      const inputDirs = config.inputDirs || [config.inputDir];
      const results: string[] = [];

      inputDirs.forEach((dir: string) => {
        const resolved = path.resolve(dir);
        if (fs.existsSync(resolved)) {
          try {
            const files = fs.readdirSync(resolved);
            files.forEach(f => {
              const ext = path.extname(f).toLowerCase();
              if ([".pdf", ".epub", ".djvu", ".txt", ".mobi", ".fb2"].includes(ext)) {
                results.push(f);
              }
            });
          } catch (e) {}
        }
      });
      res.json({ files: results });
    } catch (e: any) {
      res.status(500).json({ error: e.message });
    }
  });

  // REST API: Create custom mock book file inside input directory
  app.post("/api/system/create-file", (req, res) => {
    try {
      const { filename, content } = req.body;
      if (!filename) {
        return res.status(400).json({ error: "Filename is required" });
      }

      const config = JSON.parse(fs.readFileSync(CONFIG_FILE, "utf-8"));
      const mainInput = config.inputDirs ? config.inputDirs[0] : config.inputDir;

      if (!fs.existsSync(mainInput)) {
        fs.mkdirSync(mainInput, { recursive: true });
      }

      const cleanFilename = filename.replace(/[/\\?%*:|"<>\s]+/g, "_");
      const normalizedFilename = cleanFilename.match(/\.[a-zA-Z0-9]+$/) ? cleanFilename : `${cleanFilename}.txt`;
      const targetPath = path.join(mainInput, normalizedFilename);

      fs.writeFileSync(targetPath, content || "Custom book scanning body context.", "utf-8");
      addServerLog(`Created raw document file on server: ${normalizedFilename}`);

      res.json({ success: true, filename: normalizedFilename });
    } catch (e: any) {
      res.status(500).json({ error: e.message });
    }
  });

  // Serve compiled static files or start development server
  if (process.env.NODE_ENV !== "production") {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: "spa",
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.join(process.cwd(), 'dist');
    app.use(express.static(distPath));
    app.get('*', (req, res) => {
      res.sendFile(path.join(distPath, 'index.html'));
    });
  }

  app.listen(PORT, "0.0.0.0", () => {
    console.log(`[Grimmory Full-Stack Server] Active on port ${PORT}`);
  });
}

startServer();
