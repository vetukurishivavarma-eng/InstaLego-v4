FROM python:3.11-slim
# tesseract (default) fits a 512 MB instance; paddle needs ~1.5 GB RAM
ARG OCR_ENGINE=tesseract
ENV OCR_ENGINE=${OCR_ENGINE}
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng tesseract-ocr-tel \
    && if [ "$OCR_ENGINE" = "paddle" ]; then apt-get install -y --no-install-recommends libgl1 libglib2.0-0 libgomp1; fi \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements*.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
    && if [ "$OCR_ENGINE" = "paddle" ]; then pip install --no-cache-dir -r requirements-paddle.txt; fi
COPY app.py clean.py ./
EXPOSE 8000
CMD ["sh", "-c", "uvicorn app:app --host 0.0.0.0 --port ${PORT:-8000}"]
