import { Fragment, useState, type ReactNode } from "react";
import { ArrowDown, ArrowRight, Crown, Unlink, UserRound } from "lucide-react";
import type { ChainEdge, ChainNode } from "../api";

type Pick = { kind: "node"; i: number } | { kind: "edge"; i: number } | null;

/** Nodes come back in chain order and each neighbouring pair is joined by exactly one edge (transfer or break). */
export default function ChainOfTitle({ nodes, edges }: { nodes: ChainNode[]; edges: ChainEdge[] }) {
  const [pick, setPick] = useState<Pick>(null);
  if (nodes.length === 0)
    return (
      <Shell>
        <p className="text-sm text-muted">No sale, gift or link deed was found, so there is no chain to draw. Upload the title deeds.</p>
      </Shell>
    );

  const between = nodes.slice(0, -1).map((n, i) => edges.find((e) => e.from === n.id && e.to === nodes[i + 1].id));
  const breaks = edges.filter((e) => e.kind === "break").length;

  return (
    <Shell subtitle={breaks ? `${breaks} gap${breaks > 1 ? "s" : ""} in the chain` : "Continuous from the earliest deed supplied"}>
      <ol className="flex flex-col items-stretch gap-1 overflow-x-auto pb-2 md:flex-row md:items-center">
        {nodes.map((n, i) => {
          const e = between[i];
          const current = i === nodes.length - 1;
          return (
            <Fragment key={n.id}>
              <li>
                <button type="button" onClick={() => setPick({ kind: "node", i })} aria-pressed={pick?.kind === "node" && pick.i === i}
                  className={`flex w-full min-w-40 items-start gap-2 rounded-lg border px-3 py-2.5 text-left transition md:w-44
                    ${current ? "border-navy bg-navy text-white" : "border-rule bg-white hover:border-navy/50"}
                    ${pick?.kind === "node" && pick.i === i ? "ring-2 ring-navy/40 ring-offset-1" : ""}`}>
                  {current ? <Crown className="mt-0.5 h-4 w-4 shrink-0" aria-hidden /> : <UserRound className="mt-0.5 h-4 w-4 shrink-0 text-muted" aria-hidden />}
                  <span className="min-w-0">
                    <span className="block text-sm font-medium leading-snug">{n.label}</span>
                    <span className={`block text-xs ${current ? "text-white/70" : "text-muted"}`}>
                      {current ? "Current owner" : n.since ? `Owner from ${fmt(n.since)}` : "Earliest owner on record"}
                    </span>
                  </span>
                </button>
              </li>
              {e && (
                <li>
                  <button type="button" onClick={() => setPick({ kind: "edge", i })} aria-pressed={pick?.kind === "edge" && pick.i === i}
                    className={`flex w-full items-center justify-center gap-2 rounded-md px-2 py-1.5 text-xs transition hover:bg-rule/40 md:w-28 md:flex-col md:gap-0.5
                      ${pick?.kind === "edge" && pick.i === i ? "bg-rule/50" : ""}`}>
                    {e.kind === "break" ? (
                      <>
                        <Unlink className="h-4 w-4 text-defect" aria-hidden />
                        <span className="font-medium text-defect">Gap in title</span>
                      </>
                    ) : (
                      <>
                        <ArrowDown className="h-4 w-4 text-navy md:hidden" aria-hidden />
                        <ArrowRight className="hidden h-4 w-4 text-navy md:block" aria-hidden />
                        <span className="font-medium">Doc {e.doc_no ?? "?"}</span>
                        <span className="text-muted">{e.date ? fmt(e.date) : "undated"}</span>
                        {e.note && <span className="rounded bg-cond/15 px-1.5 text-[10px] font-medium text-cond">name differs</span>}
                      </>
                    )}
                  </button>
                </li>
              )}
            </Fragment>
          );
        })}
      </ol>
      <Detail pick={pick} nodes={nodes} between={between} />
    </Shell>
  );
}

function Detail({ pick, nodes, between }: { pick: Pick; nodes: ChainNode[]; between: (ChainEdge | undefined)[] }) {
  let text = "Tap an owner or a deed for details.";
  if (pick?.kind === "node") {
    const n = nodes[pick.i];
    text = `${n.label}: ${pick.i === nodes.length - 1 ? "holds title now" : n.since ? `acquired on ${fmt(n.since)}` : "first owner shown in the documents supplied"}.`;
  } else if (pick?.kind === "edge") {
    const e = between[pick.i];
    if (e?.kind === "break") text = "No uploaded deed transfers title between these owners. The link document must be produced, or the seller's title is unproven.";
    else if (e) text = `${e.doc_type ?? "Deed"} No. ${e.doc_no ?? "unknown"}${e.date ? `, registered ${fmt(e.date)}` : ""}.${e.note ? " The owner's name is spelt differently from the previous deed; an identity affidavit is advisable." : ""}`;
  }
  return <p className="mt-3 rounded-lg bg-paper px-3 py-2 text-sm text-ink/80" aria-live="polite">{text}</p>;
}

function Shell({ subtitle, children }: { subtitle?: string; children: ReactNode }) {
  return (
    <section className="rounded-xl border border-rule bg-card p-5">
      <h2 className="text-lg font-semibold">Chain of title</h2>
      {subtitle && <p className="mb-4 text-sm text-muted">{subtitle}</p>}
      {!subtitle && <div className="mb-3" />}
      {children}
    </section>
  );
}

export function fmt(iso: string) {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}
