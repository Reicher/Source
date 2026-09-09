ARG NODE_IMAGE=node:24-bookworm-slim
FROM ${NODE_IMAGE}

WORKDIR /app

COPY package.json ./
COPY package-lock.json ./
RUN npm ci --omit=dev
COPY src ./src

ENV NODE_ENV=production
EXPOSE 8080 9090

CMD ["node", "src/server.mjs"]
