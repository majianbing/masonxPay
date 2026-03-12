import { HttpClient } from '../http';
import {
  PaymentIntent,
  CreatePaymentIntentParams,
  ConfirmPaymentIntentParams,
  Refund,
  CreateRefundParams,
} from '../types';

export class PaymentIntentsResource {
  constructor(private readonly http: HttpClient) {}

  create(params: CreatePaymentIntentParams): Promise<PaymentIntent> {
    return this.http.post<PaymentIntent>('/api/v1/payment-intents', params, {
      idempotencyKey: params.idempotencyKey,
    });
  }

  retrieve(id: string): Promise<PaymentIntent> {
    return this.http.get<PaymentIntent>(`/api/v1/payment-intents/${id}`);
  }

  confirm(id: string, params: ConfirmPaymentIntentParams): Promise<PaymentIntent> {
    return this.http.post<PaymentIntent>(`/api/v1/payment-intents/${id}/confirm`, params);
  }

  cancel(id: string): Promise<PaymentIntent> {
    return this.http.post<PaymentIntent>(`/api/v1/payment-intents/${id}/cancel`);
  }

  createRefund(id: string, params: CreateRefundParams = {}): Promise<Refund> {
    return this.http.post<Refund>(`/api/v1/payment-intents/${id}/refunds`, params);
  }

  listRefunds(id: string): Promise<Refund[]> {
    return this.http.get<Refund[]>(`/api/v1/payment-intents/${id}/refunds`);
  }
}
