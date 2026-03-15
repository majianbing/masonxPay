import { HttpClient } from './http';
import { PaymentIntentsResource } from './resources/payment-intents';
import { WebhookEndpointsResource } from './resources/webhook-endpoints';
import { ApiKeysResource } from './resources/api-keys';
import { LogsResource } from './resources/logs';
import { RoutingRulesResource } from './resources/routing-rules';
import { WebhooksResource } from './webhooks';

export { GatewayError } from './http';
export * from './types';
export type { CreateRoutingRuleParams, UpdateRoutingRuleParams } from './resources/routing-rules';

export interface GatewayNodeOptions {
  /**
   * Base URL of the Gateway API.
   * @default 'http://localhost:8080'
   */
  baseUrl?: string;

  /**
   * Merchant ID — required for merchant-scoped resources
   * (api-keys, webhook-endpoints, routing-rules, logs).
   */
  merchantId?: string;
}

/**
 * Main entry point for the @gateway/node SDK.
 *
 * @example
 * ```ts
 * const gateway = new GatewayNode('sk_test_...', { merchantId: 'uuid' });
 *
 * const intent = await gateway.paymentIntents.create({
 *   amount: 1000,
 *   currency: 'usd',
 * });
 *
 * const event = gateway.webhooks.verify(rawBody, signatureHeader, signingSecret);
 * ```
 */
export class GatewayNode {
  readonly paymentIntents: PaymentIntentsResource;
  readonly webhookEndpoints: WebhookEndpointsResource;
  readonly apiKeys: ApiKeysResource;
  readonly logs: LogsResource;
  readonly routingRules: RoutingRulesResource;
  readonly webhooks: WebhooksResource;

  private readonly http: HttpClient;

  constructor(secretKey: string, options: GatewayNodeOptions = {}) {
    const baseUrl = options.baseUrl ?? 'http://localhost:8080';
    const merchantId = options.merchantId ?? '';

    this.http = new HttpClient(secretKey, baseUrl);

    this.paymentIntents = new PaymentIntentsResource(this.http);
    this.webhookEndpoints = new WebhookEndpointsResource(this.http, merchantId);
    this.apiKeys = new ApiKeysResource(this.http, merchantId);
    this.logs = new LogsResource(this.http, merchantId);
    this.routingRules = new RoutingRulesResource(this.http, merchantId);
    this.webhooks = new WebhooksResource();
  }
}
