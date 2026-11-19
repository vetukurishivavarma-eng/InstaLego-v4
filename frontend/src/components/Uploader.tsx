import { useRef, useState } from "react";
import { FileText, Loader2, UploadCloud, X } from "lucide-react";
import { guessTag, TAG_LABELS, type Tag } from "../api";

const ACCEPT = ".pdf,.jpg,.jpeg,.png";
const MAX_MB = 25;

export interface Staged { file: File; tag: Tag }

export default function Uploader({ busy, hasDocuments, onSubmit }: {
  busy: boolean;
  hasDocuments: boolean;
  onSubmit: (files: Staged[]) => void;
}) {
  const [staged, setStaged] = useState<Staged[]>([]);
  const [over, setOver] = useState(false);
  const [rejected, setRejected] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  function add(list: FileList | null) {
    if (!list) return;
    const ok: Staged[] = [];
    const bad: string[] = [];
    for (const file of Array.from(list)) {
      if (!/\.(pdf|jpe?g|png)$/i.test(file.name)) bad.push(`${file.name} (not PDF/JPG/PNG)`);
      else if (file.size > MAX_MB * 1024 * 1024) bad.push(`${file.name} (over ${MAX_MB} MB)`);
      else if (!staged.some((s) => s.file.name === file.name && s.file.size === file.size)) ok.push({ file, tag: guessTag(file.name) });
    }
    setRejected(bad.length ? `Skipped: ${bad.join(", ")}` : null);
    setStaged((s) => [...s, ...ok]);
  }

  return (
    <section className="rounded-xl border border-rule bg-card p-5">
      <h2 className="text-lg font-semibold">{hasDocuments ? "Add a missing document" : "Upload the document bundle"}</h2>
      <p className="mt-1 text-sm text-muted">
        {hasDocuments
          ? "Documents already read are reused, only the new ones are processed."
          : "Sale and link deeds, EC, Patta/Pahani/7-12. Scanned copies and Telugu text are fine."}
      </p>

      <button
        type="button"
        onClick={() => input.current?.click()}
        onDragOver={(e) => { e.preventDefault(); setOver(true); }}
        onDragLeave={() => setOver(false)}
        onDrop={(e) => { e.preventDefault(); setOver(false); add(e.dataTransfer.files); }}
        className={`mt-4 flex w-full flex-col items-center gap-2 rounded-lg border-2 border-dashed px-4 py-8 text-sm transition
          ${over ? "border-navy bg-navy/5" : "border-rule hover:border-navy/50"}`}
      >
        <UploadCloud className="h-7 w-7 text-navy" aria-hidden />
        <span><span className="font-medium text-navy">Choose files</span> or drop them here</span>
        <span className="text-xs text-muted">PDF, JPG or PNG, up to {MAX_MB} MB each</span>
      </button>
      <input ref={input} type="file" accept={ACCEPT} multiple hidden onChange={(e) => { add(e.target.files); e.target.value = ""; }} />
      {rejected && <p className="mt-2 text-xs text-defect">{rejected}</p>}

      {staged.length > 0 && (
        <ul className="mt-4 divide-y divide-rule rounded-lg border border-rule">
          {staged.map((s, i) => (
            <li key={s.file.name + s.file.size} className="flex items-center gap-3 px-3 py-2">
              <FileText className="h-4 w-4 shrink-0 text-muted" aria-hidden />
              <span className="min-w-0 flex-1 truncate text-sm" title={s.file.name}>{s.file.name}</span>
              <label className="sr-only" htmlFor={`tag-${i}`}>Document type for {s.file.name}</label>
              <select
                id={`tag-${i}`}
                value={s.tag}
                onChange={(e) => setStaged((all) => all.map((x, j) => (j === i ? { ...x, tag: e.target.value as Tag } : x)))}
                className="rounded-md border border-rule bg-white px-2 py-1 text-xs"
              >
                {(Object.keys(TAG_LABELS) as Tag[]).map((t) => <option key={t} value={t}>{TAG_LABELS[t]}</option>)}
              </select>
              <button type="button" aria-label={`Remove ${s.file.name}`} onClick={() => setStaged((all) => all.filter((_, j) => j !== i))}
                className="rounded p-1 text-muted hover:bg-rule/50 hover:text-ink">
                <X className="h-4 w-4" />
              </button>
            </li>
          ))}
        </ul>
      )}

      <button
        type="button"
        disabled={busy || staged.length === 0}
        onClick={() => { onSubmit(staged); setStaged([]); }}
        className="mt-4 inline-flex w-full items-center justify-center gap-2 rounded-lg bg-navy px-4 py-2.5 text-sm font-medium text-white
          disabled:cursor-not-allowed disabled:opacity-40"
      >
        {busy && <Loader2 className="h-4 w-4 animate-spin" aria-hidden />}
        {busy ? "Reading documents…" : `Verify ${staged.length || ""} document${staged.length === 1 ? "" : "s"}`}
      </button>
    </section>
  );
}
