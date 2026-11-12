FROM python:3.14-slim@sha256:82abc9f53600280b0757126f83c5a7d47088592d2acb6b4c0b9e97f76865508c AS base
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PYTHONPATH=/app/src
WORKDIR /app
COPY requirements.lock ./
RUN python -m pip install --no-cache-dir --require-hashes -r requirements.lock
COPY src ./src
USER 10001:10001

FROM base AS test
COPY tests ./tests
ENTRYPOINT ["python", "-m", "unittest", "discover", "-s", "tests", "-v"]

FROM base AS runtime
EXPOSE 8080
ENTRYPOINT ["python", "-m", "comicatlas_ai"]
