/**
 * A suffix that keeps the names a real-API check creates apart from earlier runs' (codes, item names, titles).
 *
 * Digits only. A suffix in base 36 has letters, and once it spelled a word the page also shows: on 2026-11-08 the item
 * "CO-A-MW7TOMYW" put "TO" in the From select's options, so getByLabel('To') matched that select as well and the check
 * failed. A run of digits cannot spell a label, so it cannot make a locator match something else.
 */
export function uniqueSuffix(): string {
  return `${Date.now()}${Math.floor(Math.random() * 1000).toString().padStart(3, '0')}`
}
