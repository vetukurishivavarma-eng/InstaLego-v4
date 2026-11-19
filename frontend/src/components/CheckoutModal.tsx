import { useEffect, useRef, useState } from "react";
import { CreditCard, Loader2, ShieldCheck, Smartphone, X } from "lucide-react";
import { api, type CaseView } from "../api";

interface RazorpayResponse { razorpay_payment_id: string; razorpay_order_id: string; razorpay_signature: string }
declare global {
  interface Window {
    Razorpay?: new (opts: Record<string, unknown>) => { open: () => void; on: (e: string, cb: () => void) => void };
  }
}

function loadRazorpay(): Promise<void> {
  if (window.Razorpay) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const s = document.createElement("script");
    s.src = "https://checkout.razorpay.com/v1/checkout.js";
    s.onload = () => resolve();
    s.onerror = () => reject(new Error("Could not load the payment window. Check your connection."));
    document.body.appendChild(s);
  });
}

export default function CheckoutModal({ view, onClose, onPaid }: {
  view: CaseView; onClose: () => void; onPaid: (v: CaseView) => void;
}) {
  const [method, setMethod] = useState<"upi" | "card">("upi");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const dialog = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    dialog.current?.showModal();
  }, []);

  async function pay() {
    setBusy(true);
    setError(null);
    try {
      const order = await api.checkout(view.case_id);
      if (order.mode === "mock") {
        onPaid(await api.confirm(view.case_id));
        return;
      }
      await loadRazorpay();
      const rzp = new window.Razorpay!({
        key: order.key_id,
        order_id: order.order_id,
        amount: order.amount_inr * 100,
        currency: "INR",
        name: "TitleProof",
        description: "Title verification opinion",
        method: { upi: method === "upi", card: method === "card", netbanking: true, wallet: false },
        handler: async (r: RazorpayResponse) => {
          try {
            onPaid(await api.confirm(view.case_id, { razorpay_payment_id: r.razorpay_payment_id, razorpay_signature: r.razorpay_signature }));
          } catch (e) {
            setError((e as Error).message);
            setBusy(false);
          }
        },
        modal: { ondismiss: () => setBusy(false) },
        theme: { color: "#1f3a5f" },
      });
      rzp.on("payment.failed", () => { setError("Payment failed. You have not been charged."); setBusy(false); });
      rzp.open();
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  }

  return (
    <dialog ref={dialog} onClose={onClose} aria-labelledby="checkout-title"
      className="m-auto w-[min(26rem,calc(100vw-2rem))] rounded-xl border border-rule bg-card p-0 text-ink backdrop:bg-ink/50">
      <div className="p-5">
        <div className="flex items-start justify-between">
          <div>
            <h2 id="checkout-title" className="text-lg font-semibold">Unlock full report</h2>
            <p className="text-sm text-muted">One-time payment for this case</p>
          </div>
          <button type="button" onClick={() => dialog.current?.close()} aria-label="Close" className="rounded p-1 text-muted hover:bg-rule/50">
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="mt-4 flex items-baseline justify-between rounded-lg bg-paper px-4 py-3">
          <span className="text-sm">Opinion + PDF + checklist</span>
          <span className="font-serif text-2xl font-semibold">₹{view.price_inr}</span>
        </div>

        <fieldset className="mt-4 grid grid-cols-2 gap-2">
          <legend className="sr-only">Payment method</legend>
          {([["upi", "UPI", Smartphone], ["card", "Card / Netbanking", CreditCard]] as const).map(([id, label, Icon]) => (
            <label key={id} className={`flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2.5 text-sm
              ${method === id ? "border-navy bg-navy/5 font-medium" : "border-rule"}`}>
              <input type="radio" name="method" value={id} checked={method === id} onChange={() => setMethod(id)} className="sr-only" />
              <Icon className="h-4 w-4 text-navy" aria-hidden />{label}
            </label>
          ))}
        </fieldset>

        {view.payment_mode === "mock" && (
          <p className="mt-3 rounded-md bg-cond/10 px-3 py-2 text-xs text-cond">Test mode: no money is charged, the report unlocks immediately.</p>
        )}
        {error && <p className="mt-3 text-sm text-defect" role="alert">{error}</p>}

        <button type="button" onClick={pay} disabled={busy}
          className="mt-4 inline-flex w-full items-center justify-center gap-2 rounded-lg bg-navy px-4 py-2.5 text-sm font-medium text-white disabled:opacity-50">
          {busy && <Loader2 className="h-4 w-4 animate-spin" aria-hidden />}
          Pay ₹{view.price_inr}
        </button>
        {view.payment_mode === "razorpay" && (
          <p className="mt-3 flex items-center justify-center gap-1.5 text-xs text-muted">
            <ShieldCheck className="h-3.5 w-3.5" aria-hidden /> Secured by Razorpay. We never see your card or UPI PIN.
          </p>
        )}
      </div>
    </dialog>
  );
}
