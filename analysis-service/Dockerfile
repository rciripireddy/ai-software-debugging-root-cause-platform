FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_ROOT_USER_ACTION=ignore

RUN useradd --create-home --uid 10001 app
WORKDIR /app

COPY pyproject.toml README.md ./
COPY src ./src
RUN pip install .

USER app
EXPOSE 8000
# Starts uvicorn with the service's own logging configuration (see analysis_service/__main__.py).
CMD ["python", "-m", "analysis_service"]
