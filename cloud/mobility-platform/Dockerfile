# Cloud Mobility Platform (Streamlit). HTF_TMS_URL points to the TMS.
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY *.py ./
EXPOSE 8501
ENTRYPOINT ["streamlit", "run", "mobility_platform.py", "--server.address=0.0.0.0", "--server.port=8501", "--server.headless=true", "--browser.gatherUsageStats=false"]
