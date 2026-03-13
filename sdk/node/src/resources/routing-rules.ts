import { HttpClient } from '../http';
import { RoutingRule } from '../types';

export interface CreateRoutingRuleParams {
  priority: number;
  enabled?: boolean;
  targetProvider: string;
  fallbackProvider?: string;
  currencies?: string[];
  countryCodes?: string[];
  paymentMethodTypes?: string[];
  amountMin?: number;
  amountMax?: number;
}

export type UpdateRoutingRuleParams = CreateRoutingRuleParams;

export class RoutingRulesResource {
  private readonly base: string;

  constructor(private readonly http: HttpClient, merchantId: string) {
    this.base = `/api/v1/merchants/${merchantId}/routing-rules`;
  }

  list(): Promise<RoutingRule[]> {
    return this.http.get<RoutingRule[]>(this.base);
  }

  create(params: CreateRoutingRuleParams): Promise<RoutingRule> {
    return this.http.post<RoutingRule>(this.base, params);
  }

  update(ruleId: string, params: UpdateRoutingRuleParams): Promise<RoutingRule> {
    return this.http.put<RoutingRule>(`${this.base}/${ruleId}`, params);
  }

  delete(ruleId: string): Promise<void> {
    return this.http.delete(`${this.base}/${ruleId}`);
  }
}
