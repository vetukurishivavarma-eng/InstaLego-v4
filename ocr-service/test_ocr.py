"""Self-check: run `python test_ocr.py` (or pytest). Builds text-layer PDFs from mocks/sample-deeds, no OCR model needed."""
import pathlib

import pymupdf
from fastapi.testclient import TestClient

from app import app
from clean import mask_pii

MOCKS = pathlib.Path(__file__).parent.parent / "mocks" / "sample-deeds"


def to_pdf(text: str) -> bytes:
    doc = pymupdf.open()
    for chunk in text.split("\f"):
        page = doc.new_page()
        page.insert_textbox(pymupdf.Rect(40, 40, 555, 800), chunk, fontsize=8)
    return doc.tobytes()


def test_masking():
    assert mask_pii("Aadhaar 4821 7730 5519") == "Aadhaar XXXX-XXXX-5519"
    assert mask_pii("PAN ABCPK1234F.") == "PAN XXXXX1234F."
    assert mask_pii("Mobile +91 9701234567") == "Mobile XXXXXX4567"
    assert mask_pii("Mobile 98480 22113") == "Mobile XXXXXX2113"
    assert mask_pii("Rs. 9800000000 paid") == "Rs. 9800000000 paid"  # money kept
    assert mask_pii("Doc No. 2345/2012 Sy 123/4A") == "Doc No. 2345/2012 Sy 123/4A"
    assert mask_pii("IN-AP48211930551922X") == "IN-AP48211930551922X"  # embedded in an id, not Aadhaar


def test_extract_mocks():
    client = TestClient(app)
    files = [("files", (p.stem + ".pdf", to_pdf(p.read_text("utf8")), "application/pdf")) for p in sorted(MOCKS.glob("*.txt"))]
    res = client.post("/api/ocr/extract", files=files)
    assert res.status_code == 200, res.text
    sale, gift, ec = res.json()

    assert sale["page_count"] == 3 and len(sale["sha256_hash"]) == 64
    t = sale["cleaned_text"]
    for gone in ["4821 7730 5519", "ABCPK1234F", "98480 22113", "9701234567", "Page 1 of 3", "INDIA NON JUDICIAL", "Stamp Vendor"]:
        assert gone not in t, gone
    for kept in ["KONDAPALLI VENKATA RAO", "POTHULA LAKSHMI", "1187/2005", "Rs. 9,50,000", "Survey No. 123/4A", "14th day of March 2012"]:
        assert kept in t, kept
    assert t.count("SCHEDULE OF PROPERTY\nAll that piece") == 1, "duplicate schedule block not removed"
    assert t.count("DOCUMENT NO. 2345/2012") == 1, "repeated header should survive exactly once"

    assert "123/A4" in gift["cleaned_text"] and "Certificate No." not in gift["cleaned_text"]
    assert "801/2021" in ec["cleaned_text"] and "STATE BANK OF INDIA" in ec["cleaned_text"]

    bad = client.post("/api/ocr/extract", files=[("files", ("x.docx", b"hi", "application/octet-stream"))])
    assert bad.status_code == 415
    corrupt = client.post("/api/ocr/extract", files=[("files", ("x.pdf", b"not a pdf", "application/pdf"))])
    assert corrupt.status_code == 422


if __name__ == "__main__":
    test_masking()
    test_extract_mocks()
    print("ok")
