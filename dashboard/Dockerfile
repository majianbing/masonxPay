# ── Stage 1: Install dependencies ────────────────────────────────────────────
FROM node:20-alpine AS deps
WORKDIR /app
# The SDK is referenced as file:../sdk/browser in package.json.
# npm resolves that relative to /app, so it expects the SDK at /sdk/browser.
COPY sdk/browser /sdk/browser
COPY dashboard/package.json dashboard/package-lock.json* ./
RUN npm ci

# ── Stage 2: Build ────────────────────────────────────────────────────────────
FROM node:20-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
# Re-copy SDK so the symlink node_modules/@gateway/browser -> /sdk/browser resolves
COPY sdk/browser /sdk/browser
COPY dashboard/ .

# NEXT_PUBLIC_ vars are inlined at build time
ARG NEXT_PUBLIC_API_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL

# Enable standalone output for Docker (omit on Vercel)
ENV NEXT_STANDALONE=true

RUN npx next build

# ── Stage 3: Runner ───────────────────────────────────────────────────────────
FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production

# Standalone output bundles only what's needed
COPY --from=builder /app/public ./public
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static

EXPOSE 3000
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

CMD ["node", "server.js"]
