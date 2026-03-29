# MasonXPay Dashboard

Next.js 15 merchant portal for the MasonXPay payment gateway.

## What's in here

| Route | Purpose |
|-------|---------|
| `/overview` | Revenue charts, recent transactions |
| `/payments` | Paginated payment list — filter by status, provider, method, date, connector label |
| `/payments/[id]` | Payment detail — attempts, metadata, refund actions |
| `/refunds` | Refunds list with search and date filters |
| `/connectors` | Add/manage payment provider accounts (Stripe, Square, Braintree) |
| `/connectors/[id]/preview` | Live TEST checkout preview for a single connector |
| `/routing/rules` | Weighted routing rules per provider |
| `/payment-links` | Create and share hosted payment links |
| `/developers/api-keys` | Create/revoke API key pairs |
| `/developers/webhooks` | Webhook endpoints + delivery logs |
| `/developers/logs` | API request/response log viewer |
| `/team` | Invite and manage merchant users (OWNER / ADMIN / DEVELOPER / FINANCE / VIEWER) |
| `/settings/merchant` | Merchant profile |
| `/settings/security` | TOTP MFA setup / disable |
| `/pay/[token]` | Public hosted checkout page (served from this app, no auth required) |

## Local development

```bash
npm install
npm run dev    # http://localhost:3000
```

Requires the backend running on port 8012 (or set `NEXT_PUBLIC_API_URL`).

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` | Backend API base URL |

## Key architectural notes

- **Auth store** (`store/auth.ts`) — Zustand + `persist` middleware; tokens in `localStorage`, synced into `apiFetch` headers on rehydrate
- **Mode toggle** — TEST / LIVE toggled in the top bar; all API calls pass `mode` as a query param
- **MFA warning banner** — shown on every dashboard page when `user.mfaEnabled === false`; dismissible per session via `sessionStorage`
- **SDK integration** — the hosted pay page and connector preview use `@gateway/browser` (`GatewayEmbedded.mountCheckout()`); no provider-specific JSX anywhere in the dashboard
- **Sidebar** — collapsible groups (Developers, Settings); auto-expands the active group on load
