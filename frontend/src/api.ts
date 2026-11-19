export type Tag = "SALE_DEED" | "LINK_DEED" | "EC" | "PATTA" | "ID" | "OTHER";
export type Status = "CLEAR" | "CONDITIONAL" | "DEFECTIVE";

export const TAG_LABELS: Record<Tag, string> = {
  SALE_DEED: "Sale Deed",
  LINK_DEED: "Link Deed",
  EC: "Encumbrance Cert.",
  PATTA: "Patta / 7/12",
  ID: "ID proof",
  OTHER: "Other",
};

export interface ChainNode { id: string; label: string; since: string | null }
export interface ChainEdge {
  from: string; to: string; doc_no: string | null; date: string | null; doc_type: string | null;
  kind: "transfer" | "break"; note: string | null;
}
export interface CaseDoc { file_name: string; tag: Tag; doc_type: string | null; doc_no: string | null; date: string | null }
export interface RuleFlag { code: string; severity: "CRITICAL" | "WARNING"; message: string; docs: string[] }

export interface CaseView {
  case_id: string;
  paid: boolean;
  price_inr: number;
  payment_mode: "mock" | "razorpay";
  risk_score?: number;
  status?: Status;
  chain_of_title?: { nodes: ChainNode[]; edges: ChainEdge[] };
  documents?: CaseDoc[];
  reduce_error?: string;
  red_flag_count?: number;
  missing_document_count?: number;
  // paid only
  critical_red_flags?: string[];
  missing_documents_checklist?: string[];
  chronological_summary?: { date: string; doc_no: string; event: string }[];
  opinion?: { heading: string; body: string }[];
  rule_flags?: RuleFlag[];
}

export interface Checkout { mode: "mock" | "razorpay"; key_id: string; order_id: string; amount_inr: number }

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api/cases${path}`, init);
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new Error(body?.error ?? body?.message ?? `Request failed (${res.status})`);
  }
  return res.json();
}

export const api = {
  create: () => call<CaseView>("", { method: "POST" }),
  get: (id: string) => call<CaseView>(`/${id}`),
  upload(id: string, files: { file: File; tag: Tag }[]) {
    const form = new FormData();
    files.forEach(({ file, tag }) => { form.append("files", file); form.append("tags", tag); });
    return call<CaseView>(`/${id}/documents`, { method: "POST", body: form });
  },
  reanalyze: (id: string) => call<CaseView>(`/${id}/reanalyze`, { method: "POST" }),
  checkout: (id: string) => call<Checkout>(`/${id}/checkout`, { method: "POST" }),
  confirm: (id: string, payment?: { razorpay_payment_id: string; razorpay_signature: string }) =>
    call<CaseView>(`/${id}/payment`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payment ?? {}),
    }),
  reportUrl: (id: string) => `/api/cases/${id}/report.pdf`,
};

/** Best guess from the file name; the user can always change it. */
export function guessTag(name: string): Tag {
  const n = name.toLowerCase();
  if (/\bec\b|encumbrance|form.?15/.test(n)) return "EC";
  if (/patta|pahani|adangal|7.?12|khata|passbook|ror/.test(n)) return "PATTA";
  if (/link|parent|mother/.test(n)) return "LINK_DEED";
  if (/aadhaar|aadhar|pan|voter|id/.test(n)) return "ID";
  if (/sale|deed|gift|settlement|partition/.test(n)) return "SALE_DEED";
  return "OTHER";
}
