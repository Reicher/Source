ARG NODE_IMAGE=node:24-bookworm-slim
FROM ${NODE_IMAGE}

WORKDIR /app

COPY package.json ./
COPY src ./src
COPY test ./test

ENV NODE_ENV=production
EXPOSE 8080

CMD ["node", "src/server.mjs"]
