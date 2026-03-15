import { HttpClient } from '../http';
import { GatewayLog, Page } from '../types';

export interface ListLogsParams {
  type?: 'API_REQUEST' | 'PROVIDER_CALL' | 'WEBHOOK_DELIVERY' | 'ROUTING_DECISION';
  page?: number;
  size?: number;
}

export class LogsResource {
  constructor(
    private readonly http: HttpClient,
    private readonly merchantId: string,
  ) {}

  list(params?: ListLogsParams): Promise<Page<GatewayLog>> {
    const qs = new URLSearchParams();
    if (params?.type) qs.set('type', params.type);
    if (params?.page != null) qs.set('page', String(params.page));
    if (params?.size != null) qs.set('size', String(params.size));
    const query = qs.toString() ? `?${qs}` : '';
    return this.http.get<Page<GatewayLog>>(
      `/api/v1/merchants/${this.merchantId}/logs${query}`,
    );
  }
}
