FROM node:24.15.0-alpine3.22 AS build

WORKDIR /workspace/frontend
RUN npm install --global pnpm@11.21.0

COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY frontend/ ./
RUN pnpm build

FROM nginxinc/nginx-unprivileged:1.29.3-alpine AS runtime

COPY infra/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/frontend/dist /usr/share/nginx/html

EXPOSE 8080
USER 101
