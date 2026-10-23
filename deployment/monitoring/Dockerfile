FROM node:24.14.0-bookworm-slim@sha256:d8e448a56fc63242f70026718378bd4b00f8c82e78d20eefb199224a4d8e33d8 AS build
WORKDIR /workspace
RUN npm install --global pnpm@11.16.0
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml tsconfig.base.json ./
COPY packages/monitor-hub ./packages/monitor-hub
COPY frontend/monitor-dashboard ./frontend/monitor-dashboard
RUN pnpm install --frozen-lockfile --ignore-scripts --filter @yumpoo/monitor-hub --filter @yumpoo/monitor-dashboard
RUN pnpm --filter @yumpoo/monitor-hub build && pnpm --filter @yumpoo/monitor-dashboard build
FROM build AS runtime-dependencies
RUN rm -rf node_modules packages/monitor-hub/node_modules frontend/monitor-dashboard/node_modules
RUN pnpm install --offline --prod --frozen-lockfile --ignore-scripts --filter @yumpoo/monitor-hub

FROM node:24.14.0-bookworm-slim@sha256:d8e448a56fc63242f70026718378bd4b00f8c82e78d20eefb199224a4d8e33d8
WORKDIR /app
COPY --from=runtime-dependencies /workspace/node_modules /app/node_modules
COPY --from=runtime-dependencies /workspace/packages/monitor-hub/node_modules /app/packages/monitor-hub/node_modules
COPY --from=build /workspace/packages/monitor-hub/package.json /app/packages/monitor-hub/package.json
COPY --from=build /workspace/packages/monitor-hub/dist /app/packages/monitor-hub/dist
COPY --from=build /workspace/frontend/monitor-dashboard/dist /app/ui
USER 10001:10001
CMD ["node", "packages/monitor-hub/dist/main.js"]
