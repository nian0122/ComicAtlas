FROM node:24-alpine AS build
WORKDIR /app
RUN corepack enable && corepack prepare pnpm@9.15.0 --activate
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build
FROM nginx:alpine AS runtime
ENV API_UPSTREAM=http://backend:8081
ENV MANAGEMENT_UPSTREAM=http://backend:8080
ENV MANAGEMENT_URL=http://127.0.0.1:18079/manage
COPY --from=build /app/dist /usr/share/nginx/html
COPY docker/default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 80 8080
