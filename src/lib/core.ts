/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import path from "path";

// =============================================================================
// Librarian TypeScript Core Module
// =============================================================================
// Holds pure logic: Path string calculation, ISBN validation, query mapping.
// STRICTLY NO filesystem write operations, state writes, or deletions.

export function isValidIsbn10(isbn: string): boolean {
  const clean = isbn.replace(/[^0-9X]/gi, "").toUpperCase();
  if (clean.length !== 10) return false;
  let sum = 0;
  for (let i = 0; i < 9; i++) {
    const digit = parseInt(clean[i], 10);
    if (isNaN(digit)) return false;
    sum += (10 - i) * digit;
  }
  const lastChar = clean[9];
  let lastVal = 0;
  if (lastChar === "X") {
    lastVal = 10;
  } else {
    lastVal = parseInt(lastChar, 10);
    if (isNaN(lastVal)) return false;
  }
  sum += lastVal;
  return sum % 11 === 0;
}

export function isValidIsbn13(isbn: string): boolean {
  const clean = isbn.replace(/[^0-9]/g, "");
  if (clean.length !== 13) return false;
  let sum = 0;
  for (let i = 0; i < 13; i++) {
    const digit = parseInt(clean[i], 10);
    if (isNaN(digit)) return false;
    sum += (i % 2 === 0 ? 1 : 3) * digit;
  }
  return sum % 10 === 0;
}

export function isValidIsbn(isbn: string): boolean {
  const clean = isbn.replace(/[^0-9X]/gi, "").toUpperCase();
  if (clean.length === 10) {
    return isValidIsbn10(clean);
  } else if (clean.length === 13) {
    return isValidIsbn13(clean);
  }
  return false;
}

export function extractValidIsbn(text: string): string | null {
  if (!text) return null;
  const isbnRegex = /(?:ISBN(?:[- ]*1[03])?:?\s*)?((?:97[89][- ]?)?(?:\d[- ]?){9}[\dXx])/gi;
  let match;
  while ((match = isbnRegex.exec(text)) !== null) {
    const raw = match[1];
    if (raw) {
      const clean = raw.replace(/[- ]/g, "");
      if (isValidIsbn(clean)) {
        return clean;
      }
    }
  }
  return null;
}

export function sanitizePathSegment(val: string): string {
  if (!val) return "";
  return val.replace(/[/\\?%*:|"<>\s]+/g, " ").trim();
}

export function sanitizeDirectoryName(val: string): string {
  if (!val) return "";
  return val.replace(/[/\\?%*:|"<>\s]+/g, "_").trim();
}

export interface ComputedFileInfo {
  fileBaseName: string;
  templateSubDirs: string[];
  parentFolderSegments: string[];
  fileFolder: string;
  categoryFolder: string;
  targetFilename: string;
  finalDestPath: string;
}

/**
 * Computes destination paths as PURE strings given metadata, directory variables, and patterns.
 */
export function computeDestinationFileInfo(
  metadata: { author?: string; title?: string; year?: string | number; genre?: string; isbn?: string },
  isbnResolved: string | null,
  filename: string,
  destTemplate: string,
  resolvedOutputDir: string
): ComputedFileInfo {
  const author = metadata.author || "Unknown Author";
  const title = metadata.title || "Unknown Title";
  const year = (metadata.year && Number(metadata.year) > 0) ? String(metadata.year) : "Unknown Year";
  const genre = metadata.genre || "Uncategorized";
  const isbn = metadata.isbn || isbnResolved || "No ISBN";

  const computedName = destTemplate
    .replace(/{Author}/g, author)
    .replace(/{Title}/g, title)
    .replace(/{Year}/g, year)
    .replace(/{Genre}/g, genre)
    .replace(/{ISBN}/g, isbn);

  // Split by slashes to find subdirectories defined inside the template
  const rawSegments = computedName.split(/[/\\]+/);
  const fileBaseSegment = rawSegments.pop() || "Untitled Book";

  // Sanitize parts separately using core rules
  const fileBaseName = sanitizePathSegment(fileBaseSegment);
  const templateSubDirs = rawSegments.map(seg => sanitizeDirectoryName(seg)).filter(Boolean);

  // Build target sequence
  const parentFolderSegments = [resolvedOutputDir];
  if (templateSubDirs.length > 0) {
    parentFolderSegments.push(...templateSubDirs);
  } else {
    parentFolderSegments.push(sanitizeDirectoryName(genre));
  }

  // Feature constraints: each cataloged book is housed inside a dedicated folder centered at the filename
  const fileFolder = fileBaseName;
  parentFolderSegments.push(fileFolder);

  const categoryFolder = path.join(...parentFolderSegments);
  const originalExt = path.extname(filename) || ".pdf";
  const targetFilename = `${fileBaseName}${originalExt}`;
  const finalDestPath = path.join(categoryFolder, targetFilename);

  return {
    fileBaseName,
    templateSubDirs,
    parentFolderSegments,
    fileFolder,
    categoryFolder,
    targetFilename,
    finalDestPath
  };
}
