/**
 * @gateway/js — Browser SDK for the MasonXPay gateway (Pattern B)
 *
 * Usage:
 *   const gw = new GatewayEmbedded('pk_test_xxx', { baseUrl: 'http://localhost:8080' });
 *   await gw.mount('#payment-form');
 *   gw.on('token', ({ gatewayToken, provider }) => {
 *     // send gatewayToken to YOUR server, which calls:
 *     // POST /api/v1/payment-intents  (create)
 *     // POST /api/v1/payment-intents/{id}/confirm  { paymentMethodId: gatewayToken }
 *   });
 */

export interface GatewayEmbeddedOptions {
  baseUrl?: string;
}

export interface TokenEvent {
  gatewayToken: string;
  provider: string;
}

type EventMap = {
  token: TokenEvent;
  error: { message: string };
  ready: void;
};

type Listener<T> = (data: T) => void;

interface CheckoutSession {
  merchantName: string;
  mode: string;
  providers: ProviderOption[];
  amount: number | null;
  currency: string | null;
  title: string | null;
}

interface ProviderOption {
  provider: string;
  clientKey: string;
  clientConfig: Record<string, string>;
}

declare global {
  interface Window {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    Stripe?: any;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    Square?: any;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    braintree?: any;
  }
}

export class GatewayEmbedded {
  private readonly pk: string;
  private readonly baseUrl: string;

  private session: CheckoutSession | null = null;
  private container: HTMLElement | null = null;
  private area: HTMLElement | null = null;
  private errEl: HTMLElement | null = null;
  private submitBtn: HTMLButtonElement | null = null;

  private selectedProvider: string | null = null;

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private stripe: any = null;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private stripeCard: any = null;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private squareCard: any = null;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private squareMethods: any[] = [];
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private braintreeDropin: any = null;

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private listeners: Partial<{ [K in keyof EventMap]: Listener<any> }> = {};

  constructor(publishableKey: string, options: GatewayEmbeddedOptions = {}) {
    this.pk = publishableKey;
    this.baseUrl = (options.baseUrl ?? 'http://localhost:8080').replace(/\/$/, '');
  }

  on<K extends keyof EventMap>(event: K, handler: Listener<EventMap[K]>): this {
    this.listeners[event] = handler;
    return this;
  }

  private fire<K extends keyof EventMap>(event: K, data: EventMap[K]): void {
    (this.listeners[event] as Listener<EventMap[K]> | undefined)?.(data);
  }

  async mount(selector: string | HTMLElement): Promise<this> {
    const el = typeof selector === 'string'
      ? document.querySelector<HTMLElement>(selector)
      : selector;
    if (!el) throw new Error(`GatewayEmbedded: element not found: ${selector}`);
    this.container = el;

    const res = await fetch(`${this.baseUrl}/pub/checkout-session`, {
      headers: { 'Authorization': `Bearer ${this.pk}`, 'Accept': 'application/json' },
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({})) as { detail?: string };
      throw new Error(err.detail ?? `Failed to load checkout session (${res.status})`);
    }
    this.session = await res.json() as CheckoutSession;

    this.render();
    this.fire('ready', undefined as unknown as void);
    return this;
  }

  private render(): void {
    if (!this.container || !this.session) return;
    const providers = this.session.providers ?? [];
    this.container.innerHTML = '';

    // Provider picker
    if (providers.length > 1) {
      const picker = document.createElement('div');
      picker.className = 'gw-picker';
      providers.forEach(p => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'gw-pill';
        btn.dataset['provider'] = p.provider;
        btn.textContent = this.brandName(p.provider);
        btn.addEventListener('click', () => this.selectProvider(p.provider));
        picker.appendChild(btn);
      });
      this.container.appendChild(picker);
    }

    const area = document.createElement('div');
    area.className = 'gw-payment-area';
    this.container.appendChild(area);
    this.area = area;

    const errEl = document.createElement('div');
    errEl.className = 'gw-error';
    errEl.style.display = 'none';
    this.container.appendChild(errEl);
    this.errEl = errEl;

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'gw-submit';
    btn.textContent = this.session.amount
      ? `Pay ${this.fmt(this.session.amount, this.session.currency ?? 'USD')}`
      : 'Pay';
    btn.addEventListener('click', () => this.submit());
    this.container.appendChild(btn);
    this.submitBtn = btn;

    if (providers.length > 0) this.selectProvider(providers[0].provider);
  }

  private async selectProvider(provider: string): Promise<void> {
    if (!this.area || !this.session) return;
    this.selectedProvider = provider;
    this.container?.querySelectorAll('.gw-pill').forEach(b => {
      (b as HTMLElement).classList.toggle('gw-pill--active', (b as HTMLElement).dataset['provider'] === provider);
    });

    await this.destroyProviderForms();
    this.area.innerHTML = '';
    this.showError(null);

    const opt = this.session.providers.find(p => p.provider === provider);
    if (!opt) return;

    if (provider === 'STRIPE') await this.mountStripe(opt);
    else if (provider === 'SQUARE') await this.mountSquare(opt);
    else if (provider === 'BRAINTREE') await this.mountBraintree();
  }

  // ── Stripe ──────────────────────────────────────────────────────────────────

  private async mountStripe(opt: ProviderOption): Promise<void> {
    if (!this.area || !this.submitBtn) return;
    this.submitBtn.disabled = true;

    if (!window.Stripe) await this.loadScript('https://js.stripe.com/v3/');
    if (!window.Stripe) { this.showError('Failed to load Stripe.js'); return; }

    this.stripe = window.Stripe(opt.clientKey);
    const elements = this.stripe.elements();
    const card = elements.create('card', {
      style: {
        base: { fontSize: '15px', fontFamily: 'inherit', color: '#0f172a', '::placeholder': { color: '#94a3b8' } },
        invalid: { color: '#ef4444' },
      },
    });

    const mountDiv = document.createElement('div');
    mountDiv.className = 'gw-card-input';
    this.area.appendChild(mountDiv);
    card.mount(mountDiv);

    card.on('ready', () => { if (this.submitBtn) this.submitBtn.disabled = false; });
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    card.on('change', (e: any) => this.showError(e.error?.message ?? null));
    this.stripeCard = card;
  }

  // ── Square ───────────────────────────────────────────────────────────────────

  private async mountSquare(opt: ProviderOption): Promise<void> {
    if (!this.area || !this.submitBtn || !this.session) return;
    this.submitBtn.disabled = true;

    const scriptUrl = this.session.mode === 'LIVE'
      ? 'https://web.squarecdn.com/v1/square.js'
      : 'https://sandbox.web.squarecdn.com/v1/square.js';
    if (!window.Square) await this.loadScript(scriptUrl);
    if (!window.Square) { this.showError('Failed to load Square SDK'); return; }

    const payments = window.Square.payments(opt.clientKey, opt.clientConfig?.['locationId'] ?? '');

    // Payment request used for wallet buttons
    const paymentRequest = payments.paymentRequest({
      countryCode: 'US',
      currencyCode: (this.session.currency ?? 'USD').toUpperCase(),
      total: {
        amount: ((this.session.amount ?? 0) / 100).toFixed(2),
        label: this.session.title ?? 'Total',
      },
    });

    // Wallet buttons (best-effort, above card)
    const walletsContainer = document.createElement('div');
    walletsContainer.className = 'gw-wallets';
    this.area.appendChild(walletsContainer);

    const walletFactories = [
      () => payments.googlePay(paymentRequest),
      () => payments.applePay(paymentRequest),
      () => payments.cashAppPay(paymentRequest),
    ];

    for (const factory of walletFactories) {
      try {
        const method = await factory();
        const div = document.createElement('div');
        walletsContainer.appendChild(div);
        div.innerHTML = '';
        await method.attach(div);
        this.squareMethods.push(method);
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        method.addEventListener('ontokenization', async (evt: any) => {
          const { tokenResult } = evt.detail;
          if (tokenResult.status === 'OK') await this.tokenizeGateway('SQUARE', tokenResult.token);
        });
      } catch {
        // wallet not available in this browser/env — skip
      }
    }

    // Card form
    const card = await payments.card();
    const cardDiv = document.createElement('div');
    cardDiv.className = 'gw-card-input';
    this.area.appendChild(cardDiv);
    cardDiv.innerHTML = '';
    await card.attach(cardDiv);
    this.squareCard = card;

    if (this.submitBtn) this.submitBtn.disabled = false;
  }

  // ── Braintree ─────────────────────────────────────────────────────────────────

  private async mountBraintree(): Promise<void> {
    if (!this.area || !this.submitBtn) return;
    this.submitBtn.disabled = true;

    // Fetch dynamic client token from gateway (Braintree requires server-generated token)
    const res = await fetch(`${this.baseUrl}/pub/braintree-client-token`, {
      headers: { 'Authorization': `Bearer ${this.pk}`, 'Accept': 'application/json' },
    });
    if (!res.ok) { this.showError('Failed to get Braintree client token'); return; }
    const { clientToken } = await res.json() as { clientToken: string };

    if (!window.braintree?.dropin) {
      await this.loadScript('https://js.braintreegateway.com/web/dropin/1.43.0/js/dropin.min.js');
    }
    if (!window.braintree?.dropin) { this.showError('Failed to load Braintree SDK'); return; }

    const mountDiv = document.createElement('div');
    mountDiv.className = 'gw-card-input';
    this.area.appendChild(mountDiv);

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    this.braintreeDropin = await (window.braintree as any).dropin.create({
      authorization: clientToken,
      container: mountDiv,
    });

    if (this.submitBtn) this.submitBtn.disabled = false;
  }

  private async destroyProviderForms(): Promise<void> {
    if (this.stripeCard) { this.stripeCard.destroy(); this.stripeCard = null; }
    if (this.squareCard) { const c = this.squareCard; this.squareCard = null; c.destroy().catch(() => {}); }
    const methods = this.squareMethods.splice(0);
    methods.forEach(m => m.destroy().catch(() => {}));
    if (this.braintreeDropin) { const d = this.braintreeDropin; this.braintreeDropin = null; d.teardown().catch(() => {}); }
    this.stripe = null;
  }

  // ── Submit ───────────────────────────────────────────────────────────────────

  private async submit(): Promise<void> {
    if (!this.submitBtn) return;
    this.showError(null);
    this.submitBtn.disabled = true;
    try {
      if (this.selectedProvider === 'STRIPE') await this.submitStripe();
      else if (this.selectedProvider === 'SQUARE') await this.submitSquare();
      else if (this.selectedProvider === 'BRAINTREE') await this.submitBraintree();
    } catch (e) {
      const msg = (e as Error).message ?? 'Payment error';
      this.showError(msg);
      this.fire('error', { message: msg });
    } finally {
      if (this.submitBtn) this.submitBtn.disabled = false;
    }
  }

  private async submitStripe(): Promise<void> {
    if (!this.stripe || !this.stripeCard) throw new Error('Stripe not ready');
    const { paymentMethod, error } = await this.stripe.createPaymentMethod({ type: 'card', card: this.stripeCard });
    if (error || !paymentMethod) throw new Error(error?.message ?? 'Card error');
    await this.tokenizeGateway('STRIPE', paymentMethod.id);
  }

  private async submitSquare(): Promise<void> {
    if (!this.squareCard) throw new Error('Square not ready');
    const result = await this.squareCard.tokenize();
    if (result.status !== 'OK') {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      throw new Error(result.errors?.map((e: any) => e.message).join(', ') ?? 'Card error');
    }
    await this.tokenizeGateway('SQUARE', result.token);
  }

  private async submitBraintree(): Promise<void> {
    if (!this.braintreeDropin) throw new Error('Braintree not ready');
    const { nonce } = await this.braintreeDropin.requestPaymentMethod();
    await this.tokenizeGateway('BRAINTREE', nonce);
  }

  private async tokenizeGateway(provider: string, providerPmId: string): Promise<void> {
    const res = await fetch(`${this.baseUrl}/pub/tokenize`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${this.pk}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ provider, providerPmId }),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({})) as { detail?: string };
      throw new Error(err.detail ?? 'Tokenization failed');
    }
    const data = await res.json() as { gatewayToken: string };
    this.fire('token', { gatewayToken: data.gatewayToken, provider });
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private showError(msg: string | null): void {
    if (!this.errEl) return;
    this.errEl.textContent = msg ?? '';
    this.errEl.style.display = msg ? '' : 'none';
  }

  private fmt(amount: number, currency: string): string {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amount / 100);
  }

  private brandName(provider: string): string {
    return ({ STRIPE: 'Stripe', SQUARE: 'Square', ADYEN: 'Adyen', BRAINTREE: 'Braintree' } as Record<string, string>)[provider] ?? provider;
  }

  private loadScript(src: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = src;
      script.onload = () => resolve();
      script.onerror = () => reject(new Error(`Failed to load ${src}`));
      document.head.appendChild(script);
    });
  }
}
