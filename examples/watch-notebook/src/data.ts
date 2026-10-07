export interface Note { id: number; title: string }
export function noteRows(notes: readonly Note[]) {
  return notes.map((note, index) => ({ ...note, key: note.id, top: index * 48, height: 44 }));
}
export const initialNotes: Note[] = Array.from({ length: 100 }, (_, index) => ({ id: index + 1, title: `Note ${index + 1}` }));
export function validNotes(value: unknown): value is Note[] {
  return Array.isArray(value) && value.length <= 100 && value.every(note =>
    note && Number.isSafeInteger(note.id) && note.id > 0 && typeof note.title === "string" && note.title.length <= 40) &&
    new Set(value.map(note => note.id)).size === value.length;
}
