export class GatewayError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly detail?: string;

  constructor(status: number, title: string, detail?: string, code?: string) {
    super(title);
    this.name = 'GatewayError';
    this.status = status;
    this.detail = detail;
    this.code = code;
  }
}

export interface RequestOptions {
  idempotencyKey?: string;
}

export class HttpClient {
  private readonly baseUrl: string;
  private readonly secretKey: string;

  constructor(secretKey: string, baseUrl: string) {
    this.secretKey = secretKey;
    this.baseUrl = baseUrl.replace(/\/$/, '');
  }

  async get<T>(path: string): Promise<T> {
    return this.request<T>('GET', path);
  }

  async post<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T> {
    return this.request<T>('POST', path, body, options);
  }

  async put<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('PUT', path, body);
  }

  async patch<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('PATCH', path, body);
  }

  async delete(path: string): Promise<void> {
    await this.request<void>('DELETE', path);
  }

  private async request<T>(
    method: string,
    path: string,
    body?: unknown,
    options?: RequestOptions,
  ): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    const headers: Record<string, string> = {
      'Authorization': `Bearer ${this.secretKey}`,
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    };

    if (options?.idempotencyKey) {
      headers['Idempotency-Key'] = options.idempotencyKey;
    }

    const res = await fetch(url, {
      method,
      headers,
      body: body != null ? JSON.stringify(body) : undefined,
    });

    if (res.status === 204) return undefined as T;

    const json = await res.json().catch(() => null) as Record<string, string> | null;

    if (!res.ok) {
      const title = json?.['title'] ?? res.statusText;
      const detail = json?.['detail'];
      throw new GatewayError(res.status, title, detail);
    }

    return json as T;
  }
}
