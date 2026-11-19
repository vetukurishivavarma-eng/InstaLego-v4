"""Deterministic text reduction + PII masking. Pure functions, no I/O."""
import re
from collections import Counter

PAGE_NO = re.compile(r"^\s*(?:page\s*\d+\s*(?:of|/)\s*\d+|page\s*(?:no\.?)?\s*\d+|[-–]\s*\d{1,3}\s*[-–])\s*$", re.I)
STAMP = re.compile(
    r"^\s*(?:india\s+non[\s-]*judicial|non[\s-]*judicial\s+stamp|e[\s-]*stamp(?:\s+paper)?|"
    r"(?:certificate|stamp)\s+(?:no|issued\s+date|duty\s+paid\s+by)\b.*|unique\s+doc\.?\s+reference.*|"
    r"stamp\s+vendor.*|(?:sl|serial)\.?\s*no\.?\s*[:.]?\s*[A-Z]{0,3}\s*\d{3,}\s*|"
    r"(?:government\s+of\s+)?(?:andhra\s+pradesh|telangana|karnataka|tamil\s+nadu|maharashtra)\s*$)",
    re.I,
)
DIGITS = re.compile(r"\d+")

AADHAAR = re.compile(r"(?<![\dA-Za-z])[2-9]\d{3}[ -]?\d{4}[ -]?(\d{4})(?![\dA-Za-z])")
MOBILE = re.compile(r"(?<![\dA-Za-z])(?:\+91[ -]?|0)?[6-9]\d{4}[ -]?\d{1}(\d{4})(?![\dA-Za-z])")
PAN = re.compile(r"\b[A-Z]{5}(\d{4}[A-Z])\b")
MONEY_BEFORE = re.compile(r"(?:rs\.?|inr|₹|rupees)\s*$", re.I)


def _lines(page: str) -> list[str]:
    return [re.sub(r"[ \t ]+", " ", l).strip() for l in page.splitlines()]


def strip_boilerplate(pages: list[str]) -> list[str]:
    """Drop page numbers, stamp-paper boilerplate, and short lines repeated on >=half the pages."""
    split = [_lines(p) for p in pages]
    repeated: set[str] = set()
    if len(split) >= 3:
        # ponytail: digit-normalised exact match; misses headers that OCR garbles differently per page
        counts = Counter(k for ls in split for k in {DIGITS.sub("#", l.lower()) for l in ls if 0 < len(l) < 100})
        repeated = {k for k, c in counts.items() if c >= max(3, len(split) / 2)}
    out, emitted = [], set()
    for ls in split:
        keep = []
        for l in ls:
            if PAGE_NO.match(l) or STAMP.match(l):
                continue
            if DIGITS.sub("#", l.lower()) in repeated:
                if l in emitted:  # keep the first copy so a repeated survey-no line is never lost entirely
                    continue
                emitted.add(l)
            keep.append(l)
        out.append("\n".join(keep))
    return out


def dedupe_blocks(text: str) -> str:
    """Drop long lines (>=40 chars) seen before, e.g. a schedule repeated per page. PDF/OCR text has no
    reliable paragraph breaks, so lines are the unit; an exact repeat of a long line carries no new fact."""
    seen, out = set(), []
    for line in text.split("\n"):
        key = re.sub(r"\W+", "", line.lower())
        if len(key) >= 40:
            if key in seen:
                continue
            seen.add(key)
        out.append(line)
    return "\n".join(out)


def mask_pii(text: str) -> str:
    text = AADHAAR.sub(lambda m: f"XXXX-XXXX-{m.group(1)}", text)

    def mobile(m: re.Match) -> str:
        # ponytail: lookbehind heuristic; an unprefixed 10-digit amount starting 6-9 still gets masked
        if MONEY_BEFORE.search(text[max(0, m.start() - 8):m.start()]):
            return m.group(0)
        return f"XXXXXX{m.group(1)}"

    text = MOBILE.sub(mobile, text)
    return PAN.sub(lambda m: f"XXXXX{m.group(1)}", text)


def clean(pages: list[str]) -> str:
    text = "\n\n".join(strip_boilerplate(pages))
    text = re.sub(r"\n{3,}", "\n\n", text)
    return mask_pii(dedupe_blocks(text))
