FROM node:22-bookworm-slim

ENV NODE_ENV=production
WORKDIR /app

COPY --chown=node:node package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts \
    && npm cache clean --force

COPY --chown=node:node src/fargate ./src/fargate
COPY --chown=node:node src/domain.js src/storage.js ./src/
COPY --chown=node:node config/fargate.yml ./config/fargate.yml
COPY --chown=node:node fixtures/kbo ./fixtures/kbo

USER node

ENTRYPOINT ["node", "src/fargate/main.js"]
CMD ["--plan-day", "--profile", "fixture", "--dry-run"]
