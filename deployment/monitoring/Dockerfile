FROM node:24.14.0-bookworm-slim@sha256:d8e448a56fc63242f70026718378bd4b00f8c82e78d20eefb199224a4d8e33d8 AS build
WORKDIR /workspace
RUN npm install --global pnpm@11.16.0
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml tsconfig.base.json ./
COPY packages/monitor-hub ./packages/monitor-hub
COPY frontend/monitor-dashboard ./frontend/monitor-dashboard
RUN pnpm install --frozen-lockfile --ignore-scripts --filter @yumpoo/monitor-hub --filter @yumpoo/monitor-dashboard
RUN pnpm --filter @yumpoo/monitor-hub build && pnpm --filter @yumpoo/monitor-dashboard build
RUN pnpm --filter @yumpoo/monitor-hub deploy --prod --legacy /app

FROM node:24.14.0-bookworm-slim@sha256:d8e448a56fc63242f70026718378bd4b00f8c82e78d20eefb199224a4d8e33d8
WORKDIR /app
COPY --from=build /app /app
COPY --from=build /workspace/frontend/monitor-dashboard/dist /app/ui
USER 10001:10001
CMD ["node", "dist/main.js"]
