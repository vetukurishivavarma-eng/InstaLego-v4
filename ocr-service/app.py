"""OCR + sanitisation service: PDF/JPG/PNG in, cleaned masked text out."""
import hashlib
import hmac
import os
import subprocess
import threading

import pymupdf
from fastapi import FastAPI, Header, HTTPException, UploadFile

from clean import clean

MAX_BYTES = int(os.getenv("OCR_MAX_FILE_MB", "25")) * 1024 * 1024
MIN_TEXT_CHARS = int(os.getenv("OCR_MIN_TEXT_CHARS", "200"))  # below this a PDF page is treated as scanned
OCR_ENGINE = os.getenv("OCR_ENGINE", "tesseract")  # tesseract (fits 512 MB) | paddle (needs ~1.5 GB, build with OCR_ENGINE=paddle)
OCR_LANG = os.getenv("OCR_LANG", "te")  # PaddleOCR script model; the te/ta/kn/hi dicts also read Latin
TESSERACT_CMD = os.getenv("TESSERACT_CMD", "tesseract")
TESSERACT_LANGS = os.getenv("TESSERACT_LANGS", "tel+eng")
OCR_DPI = int(os.getenv("OCR_DPI", "300"))
OCR_TOKEN = os.getenv("OCR_TOKEN", "")  # set when the service is reachable from the internet
TYPES = {".pdf": "pdf", ".jpg": "jpg", ".jpeg": "jpg", ".png": "png"}

app = FastAPI(title="ocr-service")
_ocr = None
_ocr_lock = threading.Lock()  # ponytail: one OCR at a time (Paddle isn't thread-safe, 512 MB can't hold two); run more replicas to scale


def _ocr_page(page: pymupdf.Page) -> str:
    pix = page.get_pixmap(dpi=OCR_DPI, colorspace=pymupdf.csRGB)
    with _ocr_lock:
        return _paddle(pix) if OCR_ENGINE == "paddle" else _tesseract(pix)


def _tesseract(pix: pymupdf.Pixmap) -> str:
    try:
        r = subprocess.run([TESSERACT_CMD, "stdin", "stdout", "-l", TESSERACT_LANGS], input=pix.tobytes("png"),
                           capture_output=True, timeout=180)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise HTTPException(500, f"OCR engine failed: {e}")
    if r.returncode != 0:
        raise HTTPException(500, f"OCR engine failed: {r.stderr.decode(errors='replace')[:300]}")
    return r.stdout.decode("utf-8", errors="replace")


def _paddle(pix: pymupdf.Pixmap) -> str:
    global _ocr
    import numpy as np
    img = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width, 3)
    if _ocr is None:
        from paddleocr import PaddleOCR  # lazy: text-layer PDFs never pay the model load
        _ocr = PaddleOCR(lang=OCR_LANG, use_doc_orientation_classify=False,
                         use_doc_unwarping=False, use_textline_orientation=True)
    return "\n".join(t for r in _ocr.predict(img) for t in r["rec_texts"])


def extract(name: str, data: bytes) -> dict:
    ext = os.path.splitext(name.lower())[1]
    if ext not in TYPES:
        raise HTTPException(415, f"{name}: only PDF, JPG, PNG accepted")
    if len(data) > MAX_BYTES:
        raise HTTPException(413, f"{name}: larger than {MAX_BYTES // 1048576} MB")
    try:
        doc = pymupdf.open(stream=data, filetype=TYPES[ext])
    except Exception:
        raise HTTPException(422, f"{name}: unreadable or corrupt file")
    with doc:
        pages = []
        for page in doc:
            text = page.get_text() if TYPES[ext] == "pdf" else ""
            pages.append(text if len(text.strip()) >= MIN_TEXT_CHARS else _ocr_page(page))
        return {
            "file_name": name,
            "sha256_hash": hashlib.sha256(data).hexdigest(),
            "cleaned_text": clean(pages),
            "page_count": doc.page_count,
        }


@app.post("/api/ocr/extract")
def extract_files(files: list[UploadFile], x_ocr_token: str = Header(default="")) -> list[dict]:  # sync def -> worker thread
    if OCR_TOKEN and not hmac.compare_digest(x_ocr_token.encode(), OCR_TOKEN.encode()):
        raise HTTPException(401, "missing or wrong X-OCR-Token")
    return [extract(f.filename or "upload", f.file.read(MAX_BYTES + 1)) for f in files]


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}
