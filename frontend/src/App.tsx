import { useEffect, useState } from "react";
import { FileCheck2, Link2, Loader2, RefreshCw, Scale, ScanText } from "lucide-react";
import { api, TAG_LABELS, type CaseView } from "./api";
import Uploader, { type Staged } from "./components/Uploader";
import RiskScore from "./components/RiskScore";
import ChainOfTitle, { fmt } from "./components/ChainOfTitle";
import Report from "./components/Report";
import CheckoutModal from "./components/CheckoutModal";

const caseFromHash = () => /^#\/case\/([0-9a-f-]{36})$/i.exec(location.hash)?.[1] ?? null;

export default function App() {
  const [caseId, setCaseId] = useState<string | null>(caseFromHash);
  const [view, setView] = useState<CaseView | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [checkout, setCheckout] = useState(false);
  const [seconds, setSeconds] = useState(0);

  useEffect(() => {
    if (!caseId) return;
    api.get(caseId).then(setView).catch(() => { location.hash = ""; setCaseId(null); });
  }, [caseId]);

  useEffect(() => {
    if (!busy) return;
    setSeconds(0);
    const t = setInterval(() => setSeconds((s) => s + 1), 1000);
    return () => clearInterval(t);
  }, [busy]);

  async function run(label: string, task: () => Promise<CaseView>) {
    setBusy(label);
    setError(null);
    try {
      setView(await task());
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  function upload(files: Staged[]) {
    run("Reading documents", async () => {
      let id = caseId;
      if (!id) {
        id = (await api.create()).case_id;
        location.hash = `/case/${id}`;
        setCaseId(id);
      }
      return api.upload(id, files);
    });
  }

  const docs = view?.documents ?? [];
  const analysed = view?.status !== undefined;

  return (
    <div className="min-h-screen">
      <header className="border-b border-rule bg-card">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3">
          <a href="#" onClick={() => { setCaseId(null); setView(null); }} className="flex items-center gap-2">
            <Scale className="h-5 w-5 text-navy" aria-hidden />
            <span className="font-serif text-lg font-semibold">TitleProof</span>
          </a>
          {caseId && <span className="hidden text-xs text-muted sm:block">Case {caseId.slice(0, 8)} · bookmark this page to return</span>}
        </div>
      </header>

      <main className="mx-auto grid max-w-6xl grid-cols-1 gap-5 px-4 py-6 lg:grid-cols-[22rem_1fr]">
        <div className="min-w-0 space-y-5">
          <Uploader busy={busy !== null} hasDocuments={docs.length > 0} onSubmit={upload} />
          {docs.length > 0 && (
            <section className="rounded-xl border border-rule bg-card p-5">
              <h2 className="text-lg font-semibold">Documents in this case</h2>
              <ul className="mt-3 space-y-2">
                {docs.map((d) => (
                  <li key={d.file_name + d.doc_no} className="rounded-lg border border-rule px-3 py-2 text-sm">
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-medium">{d.doc_type ?? TAG_LABELS[d.tag]}</span>
                      <span className="shrink-0 text-xs text-muted">{d.date ? fmt(d.date) : "undated"}</span>
                    </div>
                    <div className="truncate text-xs text-muted" title={d.file_name}>
                      {d.doc_no ? `No. ${d.doc_no} · ` : ""}{d.file_name}
                    </div>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>

        <div className="min-w-0 space-y-5">
          {busy && (
            <div className="flex items-center gap-3 rounded-xl border border-navy/30 bg-navy/5 px-4 py-3 text-sm" role="status">
              <Loader2 className="h-4 w-4 animate-spin text-navy" aria-hidden />
              <span>
                {busy}: {seconds < 15 ? "reading pages and masking Aadhaar/PAN" : seconds < 45 ? "extracting parties, dates and survey numbers" : "checking the chain of title and drafting the opinion"}
                … <span className="text-muted">{seconds}s</span>
              </span>
            </div>
          )}
          {error && <p className="rounded-xl border border-defect/30 bg-defect/5 px-4 py-3 text-sm text-defect" role="alert">{error}</p>}
          {view?.reduce_error && !busy && (
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-cond/30 bg-cond/5 px-4 py-3 text-sm">
              <span>{view.reduce_error}</span>
              <button type="button" onClick={() => run("Re-running analysis", () => api.reanalyze(view.case_id))}
                className="inline-flex items-center gap-1.5 rounded-md border border-cond px-2.5 py-1 font-medium text-cond hover:bg-cond/10">
                <RefreshCw className="h-3.5 w-3.5" aria-hidden /> Re-run analysis
              </button>
            </div>
          )}

          {view && analysed ? (
            <>
              <RiskScore score={view.risk_score ?? 0} status={view.status!} redFlags={view.red_flag_count ?? 0} missing={view.missing_document_count ?? 0} />
              <ChainOfTitle nodes={view.chain_of_title?.nodes ?? []} edges={view.chain_of_title?.edges ?? []} />
              <Report view={view} onUnlock={() => setCheckout(true)} />
            </>
          ) : (
            !busy && <HowItWorks />
          )}
        </div>
      </main>

      {checkout && view && (
        <CheckoutModal view={view} onClose={() => setCheckout(false)} onPaid={(v) => { setView(v); setCheckout(false); }} />
      )}
    </div>
  );
}

function HowItWorks() {
  const steps = [
    { icon: ScanText, title: "Read every page", body: "Typed and scanned deeds, English and Telugu. Aadhaar, PAN and phone numbers are masked before anything leaves the server." },
    { icon: Link2, title: "Trace the chain", body: "Each buyer must be the next seller. Survey and plot numbers must match in every document. Referenced deeds you did not upload are listed." },
    { icon: FileCheck2, title: "Get an opinion", body: "A free risk score and title graph straight away; the full clause-by-clause opinion and PDF when you need them." },
  ];
  return (
    <section className="rounded-xl border border-rule bg-card p-6">
      <h1 className="text-2xl font-semibold leading-tight">Check a property's title before you pay for it.</h1>
      <p className="mt-2 max-w-prose text-sm text-muted">Upload the sale deeds, link documents and EC for one property. You get a traffic-light verdict in a minute or two.</p>
      <ol className="mt-6 grid gap-4 sm:grid-cols-3">
        {steps.map(({ icon: Icon, title, body }, i) => (
          <li key={title} className="rounded-lg bg-paper p-4">
            <div className="flex items-center gap-2">
              <span className="flex h-6 w-6 items-center justify-center rounded-full bg-navy text-xs font-semibold text-white">{i + 1}</span>
              <Icon className="h-4 w-4 text-navy" aria-hidden />
            </div>
            <h3 className="mt-3 font-semibold">{title}</h3>
            <p className="mt-1 text-sm text-muted">{body}</p>
          </li>
        ))}
      </ol>
    </section>
  );
}
