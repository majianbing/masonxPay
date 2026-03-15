// ─── Payment Intents ────────────────────────────────────────────────────────

export type PaymentIntentStatus =
  | 'REQUIRES_PAYMENT_METHOD'
  | 'REQUIRES_CONFIRMATION'
  | 'REQUIRES_ACTION'
  | 'PROCESSING'
  | 'REQUIRES_CAPTURE'
  | 'SUCCEEDED'
  | 'CANCELED'
  | 'FAILED';

export interface PaymentIntent {
  id: string;
  merchantId: string;
  mode: 'TEST' | 'LIVE';
  amount: number;
  currency: string;
  status: PaymentIntentStatus;
  captureMethod: 'AUTOMATIC' | 'MANUAL';
  idempotencyKey?: string;
  resolvedProvider?: string;
  providerPaymentId?: string;
  metadata?: Record<string, string>;
  successUrl?: string;
  cancelUrl?: string;
  failureUrl?: string;
  attempts?: PaymentAttempt[];
  createdAt: string;
  updatedAt: string;
}

export interface PaymentAttempt {
  id: string;
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED';
  paymentMethodType: string;
  providerRequestId?: string;
  failureCode?: string;
  failureMessage?: string;
  createdAt: string;
}

export interface CreatePaymentIntentParams {
  amount: number;
  currency: string;
  captureMethod?: 'automatic' | 'manual';
  idempotencyKey?: string;
  metadata?: Record<string, string>;
  successUrl?: string;
  cancelUrl?: string;
  failureUrl?: string;
}

export interface ConfirmPaymentIntentParams {
  paymentMethodId: string;
  paymentMethodType?: string;
}

// ─── Webhook Endpoints ───────────────────────────────────────────────────────

export interface WebhookEndpoint {
  id: string;
  merchantId: string;
  url: string;
  description?: string;
  status: 'ACTIVE' | 'DISABLED';
  subscribedEvents: string[];
  signingSecret: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateWebhookEndpointParams {
  url: string;
  description?: string;
  subscribedEvents?: string[];
}

export interface UpdateWebhookEndpointParams {
  url?: string;
  description?: string;
  subscribedEvents?: string[];
  status?: 'ACTIVE' | 'DISABLED';
}

// ─── API Keys ────────────────────────────────────────────────────────────────

export interface ApiKey {
  id: string;
  merchantId: string;
  name: string;
  mode: 'TEST' | 'LIVE';
  type: 'PUBLISHABLE' | 'SECRET';
  prefix: string;
  status: 'ACTIVE' | 'REVOKED';
  lastUsedAt?: string;
  createdAt: string;
  revokedAt?: string;
  /** Only present on creation response */
  plaintext?: string;
}

export interface CreateApiKeyParams {
  name: string;
  mode: 'TEST' | 'LIVE';
}

// ─── Routing Rules ────────────────────────────────────────────────────────────

export interface RoutingRule {
  id: string;
  merchantId: string;
  priority: number;
  targetProvider: string;
  enabled: boolean;
  currencies?: string[];
  countryCodes?: string[];
  paymentMethodTypes?: string[];
  amountMin?: number;
  amountMax?: number;
  createdAt: string;
  updatedAt: string;
}

// ─── Refunds ─────────────────────────────────────────────────────────────────

export type RefundStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED';
export type RefundReason = 'CUSTOMER_REQUEST' | 'DUPLICATE' | 'FRAUDULENT';

export interface Refund {
  id: string;
  paymentIntentId: string;
  amount: number;
  currency: string;
  status: RefundStatus;
  reason?: RefundReason;
  providerRefundId?: string;
  failureReason?: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateRefundParams {
  amount?: number;
  reason?: RefundReason;
}

// ─── Logs ────────────────────────────────────────────────────────────────────

export interface GatewayLog {
  id: string;
  merchantId?: string;
  apiKeyId?: string;
  requestId?: string;
  type: 'API_REQUEST' | 'PROVIDER_CALL' | 'WEBHOOK_DELIVERY' | 'ROUTING_DECISION';
  method?: string;
  path?: string;
  requestHeaders?: string;
  requestBody?: string;
  responseStatus?: number;
  responseBody?: string;
  durationMs?: number;
  createdAt: string;
}

// ─── Pagination ───────────────────────────────────────────────────────────────

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

// ─── Webhook Event ────────────────────────────────────────────────────────────

export interface GatewayEvent<T = unknown> {
  id: string;
  merchantId: string;
  eventType: string;
  resourceId: string;
  payload: T;
  createdAt: string;
}
