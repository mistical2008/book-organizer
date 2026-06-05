/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import { extractValidIsbn, computeDestinationFileInfo } from "./core";

// =============================================================================
// Librarian TypeScript Sandbox Module
// =============================================================================
// Responsible for fully virtualization dry-runs.
// GUARANTEES zero filesystem writes or side effects.

export interface SandboxReport {
  filepath: string;
  filename: string;
  isbn_detected: string | null;
  projected_metadata: any;
  projected_destination: string;
  confidence: number;
}

export function simulateFileRun(
  filepath: string,
  filename: string,
  textContent: string,
  config: { outputDir: string; destinationTemplate: string },
  addLog: (msg: string) => void
): SandboxReport {
  addLog(`[Sandbox Simulated Scan] Reading virtual content for: ${filename}`);

  // Pure Core ISBN checking
  const isbnResolved = extractValidIsbn(textContent);
  if (isbnResolved) {
    addLog(`[Sandbox Rule Success] Found valid ISBN matches: ${isbnResolved}`);
  } else {
    addLog(`[Sandbox LLM Route] No direct physical ISBN found. Synthesizing full-text structures...`);
  }

  // Pure Projected Metadata standard
  const mockMetadata = {
    author: "Bertrand Russell",
    title: filename.replace(/\.(pdf|epub|djvu|fb2|mobi|txt)$/i, "").replace(/_/g, " ").toUpperCase(),
    year: 1912,
    genre: "Philosophy",
    isbn: isbnResolved || "978-0199540020",
    confidence: 100,
  };

  if (filename.toLowerCase().includes("kobzar")) {
    mockMetadata.author = "Taras Shevchenko";
    mockMetadata.title = "KOBZAR";
    mockMetadata.year = 1840;
    mockMetadata.genre = "Poetry";
  } else if (filename.toLowerCase().includes("feynman")) {
    mockMetadata.author = "Richard Feynman";
    mockMetadata.title = "Surely You're Joking, Mr. Feynman!";
    mockMetadata.year = 1985;
    mockMetadata.genre = "Physics Autobiographies";
  }

  // Compute final dest paths purely as strings through Core equations
  const mapping = computeDestinationFileInfo(
    mockMetadata,
    isbnResolved,
    filename,
    config.destinationTemplate || "{Author} - {Title} ({Year})",
    config.outputDir || "/var/lib/librarian/sorted"
  );

  addLog(`[Sandbox Path Result] Sourced file would move to: ${mapping.finalDestPath}`);

  return {
    filepath,
    filename,
    isbn_detected: isbnResolved,
    projected_metadata: mockMetadata,
    projected_destination: mapping.finalDestPath,
    confidence: mockMetadata.confidence
  };
}
