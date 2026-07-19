FROM python:3.14-slim

WORKDIR /app

# Install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy scraper script
COPY scraper.py .

ENV DB_PATH=/data/vpn.db
ENV LOOP_MODE=true

CMD ["python", "scraper.py", "--db", "/data/vpn.db", "--loop"]
