# Backend Cloud Run image. Build with repository root as the Docker context:
# docker build -f backend/Dockerfile .
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PORT=8080

WORKDIR /app

COPY backend/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY backend/main.py backend/prompt.py backend/app_check.py module_versions.json ./
COPY backend/prompts ./prompts

EXPOSE 8080

# Cloud Run sets PORT; default to 8080 locally.
CMD ["sh", "-c", "uvicorn main:app --host 0.0.0.0 --port ${PORT:-8080}"]
