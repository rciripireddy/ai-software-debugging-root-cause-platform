# syntax=docker/dockerfile:1

# Build stage: installs the locked dependencies, then the service itself, into a virtualenv.
FROM python:3.12-slim AS build
ENV PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_ROOT_USER_ACTION=ignore
RUN python -m venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH
WORKDIR /build
# Dependencies first, in their own layer: rebuilt only when requirements.lock changes.
COPY requirements.lock ./
RUN pip install --require-virtualenv -r requirements.lock
COPY pyproject.toml README.md ./
COPY src ./src
RUN pip install --require-virtualenv --no-deps .

# Runtime stage: the virtualenv only, no build files, running as an unprivileged user.
FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH=/opt/venv/bin:$PATH
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin app
COPY --from=build /opt/venv /opt/venv
WORKDIR /app
USER 10001:10001
EXPOSE 8000
# Exec form: Python is PID 1 and receives SIGTERM, so uvicorn shuts down gracefully.
# Starts uvicorn with the service's own logging configuration (see analysis_service/__main__.py).
CMD ["python", "-m", "analysis_service"]
