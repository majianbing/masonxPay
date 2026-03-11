import { HttpClient } from '../http';
import { ApiKey, CreateApiKeyParams } from '../types';

export class ApiKeysResource {
  constructor(
    private readonly http: HttpClient,
    private readonly merchantId: string,
  ) {}

  list(): Promise<ApiKey[]> {
    return this.http.get<ApiKey[]>(
      `/api/v1/merchants/${this.merchantId}/api-keys`,
    );
  }

  create(params: CreateApiKeyParams): Promise<ApiKey> {
    return this.http.post<ApiKey>(
      `/api/v1/merchants/${this.merchantId}/api-keys`,
      params,
    );
  }

  revoke(id: string): Promise<void> {
    return this.http.delete(
      `/api/v1/merchants/${this.merchantId}/api-keys/${id}`,
    );
  }
}
