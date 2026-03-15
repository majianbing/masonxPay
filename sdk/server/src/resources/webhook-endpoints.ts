import { HttpClient } from '../http';
import {
  WebhookEndpoint,
  CreateWebhookEndpointParams,
  UpdateWebhookEndpointParams,
} from '../types';

export class WebhookEndpointsResource {
  constructor(
    private readonly http: HttpClient,
    private readonly merchantId: string,
  ) {}

  list(): Promise<WebhookEndpoint[]> {
    return this.http.get<WebhookEndpoint[]>(
      `/api/v1/merchants/${this.merchantId}/webhook-endpoints`,
    );
  }

  create(params: CreateWebhookEndpointParams): Promise<WebhookEndpoint> {
    return this.http.post<WebhookEndpoint>(
      `/api/v1/merchants/${this.merchantId}/webhook-endpoints`,
      params,
    );
  }

  update(id: string, params: UpdateWebhookEndpointParams): Promise<WebhookEndpoint> {
    return this.http.patch<WebhookEndpoint>(
      `/api/v1/merchants/${this.merchantId}/webhook-endpoints/${id}`,
      params,
    );
  }

  delete(id: string): Promise<void> {
    return this.http.delete(
      `/api/v1/merchants/${this.merchantId}/webhook-endpoints/${id}`,
    );
  }

  rotateSecret(id: string): Promise<WebhookEndpoint> {
    return this.http.post<WebhookEndpoint>(
      `/api/v1/merchants/${this.merchantId}/webhook-endpoints/${id}/rotate-secret`,
    );
  }
}
