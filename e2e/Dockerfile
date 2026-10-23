FROM mcr.microsoft.com/playwright:v1.64.0-noble

ENV CI=true \
    PLAYWRIGHT_OUTPUT_DIR=/artifacts

WORKDIR /workspace/e2e
COPY e2e/package.json e2e/package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund

COPY e2e/playwright.config.ts e2e/safe-json-reporter.ts ./
COPY e2e/tests ./tests

CMD ["npm", "test"]
