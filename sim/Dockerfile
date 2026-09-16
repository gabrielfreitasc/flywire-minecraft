FROM python:3.11-slim

WORKDIR /app

# Dependências primeiro, para aproveitar cache de camada
COPY pyproject.toml ./
RUN pip install --no-cache-dir -e . 2>/dev/null || pip install --no-cache-dir \
      numpy scipy pandas pyarrow duckdb

COPY src/ ./src/
COPY tests/ ./tests/
RUN pip install --no-cache-dir -e .

ENV PYTHONPATH=/app/src
EXPOSE 8765

CMD ["python", "-m", "flywire_sim.server"]
