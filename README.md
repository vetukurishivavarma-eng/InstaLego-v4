# TitleProof

Upload the deeds, EC and Patta for one Indian property and get a risk score, a chain-of-title graph and a drafted legal opinion.
The system is built to stay inside the free tiers of Groq, Cerebras, Gemini, Mistral and OpenRouter.

```
cp .env.example .env      # add at least one API key
docker compose up --build # UI on http://localhost:5173
```

| Service | What it does |
|---|---|
| `ocr-service/` (FastAPI) | Reads the PDF text layer, or runs PaddleOCR on scanned pages. Strips stamp-paper and page boilerplate and masks Aadhaar, PAN and phone numbers. |
| `backend/` (Spring Boot) | Caches each file's extracted facts by SHA-256 in Redis, runs the provider failover, checks the chain of title in Java, makes one Reduce call and enforces the paywall. |
| `frontend/` (React) | Upload with document tags, traffic-light score, interactive chain of title, locked opinion and checkout. |

Without Docker: run `uvicorn app:app` in `ocr-service/`, `./mvnw spring-boot:run` in `backend/` (needs Postgres and Redis on localhost), and `npm run dev` in `frontend/`.

Tests: `python ocr-service/test_ocr.py` and `cd backend && ./mvnw test`. Neither needs an API key. Sample inputs are in `mocks/`.

`PAYMENT_MODE=mock` unlocks reports without charging. Set it to `razorpay` and add the keys to take real payments.
