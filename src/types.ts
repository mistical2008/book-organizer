/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

export interface BookItem {
  id: string;
  originalName: string;
  currentName: string;
  fileType: "pdf" | "epub" | "fb2" | "mobi" | "djvu";
  sizeKb: number;
  status: "pending" | "processing" | "completed" | "failed";
  stage: "idle" | "preparing_images" | "ocr" | "isbn_regex" | "books_api" | "gemini_thin" | "gemini_expanded" | "finished";
  metadata?: BookMetadata;
  logs: string[];
}

export interface BookMetadata {
  author: string;
  title: string;
  year: number | null;
  genre: string;
  isbn: string | null;
  confidence: number;
  notes: string;
}

export interface SystemdOptions {
  serviceName: string;
  userName: string;
  workingDir: string;
  scriptPath: string;
  inputDir: string;
  inputDirs?: string[];
  outputDir: string;
  destinationTemplate?: string;
  geminiModel: string;
  confidenceThreshold: number;
  runInterval: string; // e.g. "hourly", "daily", "boot"
  timerType: "systemd" | "anacron";
  autoCleanup: boolean;
  processingMode?: "sequential" | "batch";
  batchSize?: number;
  enableCaching?: boolean;
}

export interface PresetBook {
  title: string;
  author: string;
  year: number;
  isbn: string;
  genre: string;
  fileType: "pdf" | "epub" | "djvu" | "fb2";
  ocrTextSample: string;
  directTextSample?: string;
}
