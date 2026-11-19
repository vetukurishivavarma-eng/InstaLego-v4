import type { ReactNode } from "react";
import { AlertTriangle, FileQuestion } from "lucide-react";
import type { Status } from "../api";

const LIGHT: Record<Status, { color: string; text: string; label: string; blurb: string }> = {
  CLEAR: { color: "var(--color-clear)", text: "text-clear", label: "Clear", blurb: "No material defect found in the documents supplied." },
  CONDITIONAL: { color: "var(--color-cond)", text: "text-cond", label: "Conditional", blurb: "Marketable only after the listed requisitions are met." },
  DEFECTIVE: { color: "var(--color-defect)", text: "text-defect", label: "Defective", blurb: "Serious defects. Do not proceed until they are cured." },
};

export default function RiskScore({ score, status, redFlags, missing }: {
  score: number; status: Status; redFlags: number; missing: number;
}) {
  const l = LIGHT[status];
  const r = 52;
  const arc = Math.PI * r; // semicircle length
  return (
    <section className="rounded-xl border border-rule bg-card p-5">
      <div className="flex flex-col items-center gap-5 sm:flex-row sm:items-center">
        <svg viewBox="0 0 128 76" className="w-44 shrink-0" role="img" aria-label={`Risk score ${score} out of 100, ${l.label}`}>
          <path d={`M12 68 A${r} ${r} 0 0 1 116 68`} fill="none" stroke="var(--color-rule)" strokeWidth="11" strokeLinecap="round" />
          <path d={`M12 68 A${r} ${r} 0 0 1 116 68`} fill="none" stroke={l.color} strokeWidth="11" strokeLinecap="round"
            strokeDasharray={`${(arc * score) / 100} ${arc}`} />
          <text x="64" y="62" textAnchor="middle" className="font-serif" fontSize="26" fontWeight="600" fill="var(--color-ink)">{score}</text>
          <text x="64" y="74" textAnchor="middle" fontSize="7" fill="var(--color-muted)">RISK / 100</text>
        </svg>
        <div className="flex-1 text-center sm:text-left">
          <div className="flex items-center justify-center gap-2 sm:justify-start">
            {(["CLEAR", "CONDITIONAL", "DEFECTIVE"] as Status[]).map((s) => (
              <span key={s} aria-hidden className="h-3 w-3 rounded-full"
                style={{ background: s === status ? LIGHT[s].color : "var(--color-rule)" }} />
            ))}
            <span className={`ml-1 font-serif text-2xl font-semibold ${l.text}`}>{l.label}</span>
          </div>
          <p className="mt-1 text-sm text-muted">{l.blurb}</p>
          <div className="mt-4 grid grid-cols-2 gap-3">
            <Stat icon={<AlertTriangle className="h-4 w-4 text-defect" aria-hidden />} n={redFlags} label="critical red flags" />
            <Stat icon={<FileQuestion className="h-4 w-4 text-cond" aria-hidden />} n={missing} label="documents missing" />
          </div>
        </div>
      </div>
    </section>
  );
}

function Stat({ icon, n, label }: { icon: ReactNode; n: number; label: string }) {
  return (
    <div className="rounded-lg border border-rule px-3 py-2">
      <div className="flex items-center gap-2 text-xl font-semibold">{icon}{n}</div>
      <div className="text-xs text-muted">{label}</div>
    </div>
  );
}
