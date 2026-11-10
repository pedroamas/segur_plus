# syntax=docker/dockerfile:1
# Servicio de seed (un solo uso, perfil `seed` del compose): resetea el dataset, genera los 32 documentos, los sube a MinIO y rellena
# `documents`. Contexto: RAÍZ del repo (necesita docs/data-model/permissions-spec.yaml, que es la fuente de los metadatos).
FROM python:3.12-slim
RUN useradd --system --uid 10001 --create-home segurplus
WORKDIR /app/generator
COPY generator/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY generator/segurplus_gen segurplus_gen
COPY generator/adversarial adversarial
COPY docs/data-model/permissions-spec.yaml /app/docs/data-model/permissions-spec.yaml
RUN mkdir -p out && chown -R segurplus /app
USER segurplus
ENV PYTHONUNBUFFERED=1
# Conexión (POSTGRES_*, DB_URL, MINIO_*) por variables de entorno: lo define el servicio `seed` del compose.
CMD ["python", "-m", "segurplus_gen.seed"]
