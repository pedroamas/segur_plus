# Dify SIMULADO (solo pruebas/demo): servidor stdlib de Python. Ver server.py.
FROM python:3.12-slim
RUN useradd --system --uid 10001 --no-create-home mock && mkdir /state && chown mock /state
COPY server.py /app/server.py
USER mock
ENV PYTHONUNBUFFERED=1
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --retries=10 CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8080/health',timeout=2)"
CMD ["python", "/app/server.py"]
