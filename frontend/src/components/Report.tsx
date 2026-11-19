import type { ReactNode } from "react";
import { AlertTriangle, CheckSquare, Download, Lock, ScrollText } from "lucide-react";
import { api, type CaseView } from "../api";
import { fmt } from "./ChainOfTitle";

/** Locked: the server sent no paid content, so the blur covers decoy bars, not the real opinion. */
export default function Report({ view, onUnlock }: { view: CaseView; onUnlock: () => void }) {
  if (!view.paid)
    return (
      <section className="relative overflow-hidden rounded-xl border border-rule bg-card p-5">
        <h2 className="text-lg font-semibold">Legal opinion</h2>
        <div aria-hidden className="mt-4 space-y-5 blur-[3px] select-none">
          {[92, 78, 85, 60, 88, 70].map((w, i) => (
            <div key={i} className="space-y-2">
              <div className="h-3.5 w-40 rounded bg-ink/20" />
              <div className="h-2.5 rounded bg-ink/10" style={{ width: `${w}%` }} />
              <div className="h-2.5 rounded bg-ink/10" style={{ width: `${w - 12}%` }} />
            </div>
          ))}
        </div>
        <div className="absolute inset-0 flex items-center justify-center bg-gradient-to-b from-card/30 via-card/80 to-card p-6">
          <div className="max-w-sm text-center">
            <Lock className="mx-auto h-6 w-6 text-navy" aria-hidden />
            <p className="mt-2 font-serif text-xl font-semibold">Full opinion and PDF report</p>
            <p className="mt-1 text-sm text-muted">
              Clause-by-clause opinion, every red flag explained, the missing-documents checklist and requisitions to raise with the seller.
            </p>
            <button type="button" onClick={onUnlock}
              className="mt-4 rounded-lg bg-navy px-5 py-2.5 text-sm font-medium text-white hover:bg-navy/90">
              Unlock for ₹{view.price_inr}
            </button>
          </div>
        </div>
      </section>
    );

  return (
    <section className="space-y-5 rounded-xl border border-rule bg-card p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-semibold">Legal opinion</h2>
        <a href={api.reportUrl(view.case_id)}
          className="inline-flex items-center gap-2 rounded-lg border border-navy px-3 py-1.5 text-sm font-medium text-navy hover:bg-navy/5">
          <Download className="h-4 w-4" aria-hidden /> Download PDF
        </a>
      </div>

      {view.critical_red_flags && view.critical_red_flags.length > 0 && (
        <Block icon={<AlertTriangle className="h-4 w-4 text-defect" />} title="Critical red flags">
          <ul className="space-y-1.5">
            {view.critical_red_flags.map((f) => <li key={f} className="border-l-2 border-defect pl-3 text-sm">{f}</li>)}
          </ul>
        </Block>
      )}

      {view.missing_documents_checklist && view.missing_documents_checklist.length > 0 && (
        <Block icon={<CheckSquare className="h-4 w-4 text-cond" />} title="Missing documents checklist">
          <ul className="space-y-1.5">
            {view.missing_documents_checklist.map((m) => (
              <li key={m}><label className="flex items-start gap-2 text-sm"><input type="checkbox" className="mt-1 accent-navy" />{m}</label></li>
            ))}
          </ul>
        </Block>
      )}

      {view.chronological_summary && view.chronological_summary.length > 0 && (
        <Block icon={<ScrollText className="h-4 w-4 text-navy" />} title="Chronology">
          <ol className="relative space-y-3 border-l border-rule pl-4">
            {view.chronological_summary.map((s, i) => (
              <li key={i} className="text-sm">
                <span className="absolute -left-[5px] mt-1.5 h-2.5 w-2.5 rounded-full bg-navy" aria-hidden />
                <span className="font-medium">{s.date ? fmt(s.date) : "Undated"}</span>
                {s.doc_no && <span className="text-muted"> · Doc {s.doc_no}</span>}
                <p className="text-ink/80">{s.event}</p>
              </li>
            ))}
          </ol>
        </Block>
      )}

      {view.opinion && view.opinion.length > 0 ? (
        <div className="space-y-4 border-t border-rule pt-4">
          {view.opinion.map((s, i) => (
            <article key={i}>
              <h3 className="font-semibold">{i + 1}. {s.heading}</h3>
              <p className="mt-1 whitespace-pre-line text-sm leading-relaxed text-ink/85">{s.body}</p>
            </article>
          ))}
        </div>
      ) : (
        <p className="text-sm text-muted">The written opinion is not ready yet. Use “Re-run analysis” above.</p>
      )}
      <p className="text-xs text-muted">
        AI-assisted draft prepared from uploaded copies. Verify against the originals and have an enrolled advocate approve it before relying on it.
      </p>
    </section>
  );
}

function Block({ icon, title, children }: { icon: ReactNode; title: string; children: ReactNode }) {
  return (
    <div>
      <h3 className="mb-2 flex items-center gap-2 text-sm font-semibold uppercase tracking-wide text-ink/70">{icon}{title}</h3>
      {children}
    </div>
  );
}
