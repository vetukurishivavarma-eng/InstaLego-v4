# legal-verify — progress

Multi-document Indian property-title verification + opinion. Spec = user's pasted prompt (2026-11-19).
Separate from D:\INSTALEGO and ~/landtitle-diligence-engine by user's choice (option B: full Java/Python/React stack).

## RESUME HERE
- DONE: repo layout, docker-compose.yml, .env.example, ocr-service (FastAPI + PyMuPDF + PaddleOCR + cleaning/PII mask),
  mocks/sample-deeds (2012 sale, 2019 gift w/ Sy 123/A4 typo + donor spelt LAXMI, 2024 EC w/ undischarged SBI mortgage;
  link deed 1187/2005 referenced but not uploaded). `cd ocr-service && python test_ocr.py` -> ok.
- NOT VERIFIED: docker build (no docker on this machine), PaddleOCR on real scans (not installed locally).
- DONE 2026-11-19: backend/ (Spring Boot 3.5, Java 21). `cd backend && ./mvnw test` -> 7 green (router failover, chain rules on mocks,
  full pipeline vs fake OCR/LLM servers incl. cache-skip, paywall, PDF). mocks/map-outputs/*.json = expected Map output per sample deed.
  API: POST /api/cases | GET /api/cases/{id} | POST /{id}/documents (files[], tags[]: SALE_DEED|LINK_DEED|EC|PATTA|ID|OTHER)
  | POST /{id}/reanalyze | POST /{id}/checkout | POST /{id}/payment {razorpay_payment_id, razorpay_signature} | GET /{id}/report.pdf
  Paid fields are stripped server-side. Chain graph + base risk score are computed in Java; LLM may raise score/status, never lower.
- NOT VERIFIED: Spring context boot against real Postgres+Redis (no Redis here, no docker), any live provider call.
- DONE 2026-11-19: frontend/ (Vite 6 + React 18 + TS + Tailwind 4 + lucide). `npm run build` clean (tsc strict). Screenshots checked vs a
  mock API: desktop locked/paid + 504px mobile. Case id lives in the URL hash (#/case/<uuid>). nginx proxies /api (600s timeout).
- LIVE 2026-11-19 (Groq key in .env, gitignored; key was pasted in chat -> rotate later):
  llama-3.3-70b-versatile is GONE from Groq -> map+reduce use openai/gpt-oss-120b (also added to reduce pool). Cerebras model
  set to gpt-oss-120b UNVERIFIED; OpenRouter llama :free UNVERIFIED. Groq free limit for gpt-oss-120b = 8000 tokens/min, 1000 req/day;
  one 3-doc case ~7k tokens -> router now waits out a <=60s 429 and retries once when no provider is left.
  Live results on mocks: Map 1.3-2.4s/doc, survey typo caught 3/3 runs, rules 70/DEFECTIVE, Reduce ~5s, opinion good.
  Fixed from live run: survey regex needed optional "No."; prompt now forces all property ids, null consideration for gifts,
  EC parties [], no markdown, no repeating rule flags; Java drops a red flag repeating an encumbrance doc no; merge drops non-object entries.
- LIVE GEMINI 2026-11-19 (GEMINI_API_KEY in .env, "AQ." format works; pasted in chat -> rotate): gemini-2.5-* closed to new keys (404);
  3.1-pro-preview / pro-latest have free quota 0 (router now benches "limit: 0" 1h, never waits on it). Map fallback = gemini-3.5-flash-lite
  (1.3-2.4s, same rule result 70/DEFECTIVE; once returned [{...}] -> router unwraps single-object arrays). Reduce primary = gemini-3.8-flash:
  best legal drafting but 209s under "high demand" (several 503s) -> 90s timeout hands Reduce to Groq (~5s). 11 JUnit green.
- NOT VERIFIED YET: `docker compose up` (no docker here), a real upload end-to-end, PaddleOCR on real scans, live provider keys, Razorpay test checkout.
- NEXT: on a machine with Docker: cp .env.example .env, add a Groq + Gemini key, `docker compose up --build`, upload mocks as PDFs
  (ocr-service/test_ocr.py's to_pdf) and a real scanned deed; then tune MAP prompt / PaddleOCR lang on real Telugu scans.
- Decisions: one key per provider (no multi-key quota rotation). (Earlier claim that gemini-3.8-flash is not real was WRONG; it exists and is the Reduce primary.)
