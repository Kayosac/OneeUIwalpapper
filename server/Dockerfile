FROM python:3.12-alpine
WORKDIR /app
COPY limits_server.py .
RUN adduser -D -H infowall && mkdir -p /data && chown infowall /data
USER infowall
ENV INFOWALL_DATA=/data INFOWALL_HOST=0.0.0.0 INFOWALL_PORT=8787 PYTHONUNBUFFERED=1
EXPOSE 8787
HEALTHCHECK --interval=60s --timeout=3s CMD wget -qO- http://127.0.0.1:8787/health || exit 1
CMD ["python", "limits_server.py"]
