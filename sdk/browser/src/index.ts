/**
 * @gateway/js — Browser SDK for the MasonXPay gateway
 *
 * Two modes:
 *
 * 1. Embedded SDK (Pattern B) — merchant hosts their own checkout page:
 *    const gw = new GatewayEmbedded('pk_test_xxx', { baseUrl: '...' });
 *    await gw.mount('#payment-form');
 *    gw.on('token', ({ gatewayToken }) => { // merchant confirms via their own server });
 *
 * 2. Hosted checkout (pay link / hosted page):
 *    const gw = new GatewayEmbedded('', { baseUrl: '...' });
 *    await gw.mountCheckout('#checkout-area', { linkToken, onSuccess, onError });
 */

export interface GatewayEmbeddedOptions {
  baseUrl?: string;
}

export interface TokenEvent {
  gatewayToken: string;
  provider: string;
}

export interface CheckoutResult {
  success: boolean;
  status: string;
  paymentIntentId: string;
  redirectUrl?: string;
  failureCode?: string;
  failureMessage?: string;
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

const STYLES = `
.gw-checkout { font-family: inherit; }
.gw-picker { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 16px; }
.gw-pill { padding: 8px 16px; border: 2px solid #e2e8f0; border-radius: 12px; font-size: 14px; font-weight: 600; cursor: pointer; background: white; transition: all 0.15s; color: #64748b; }
.gw-pill:hover { border-color: #cbd5e1; color: #334155; }
.gw-pill--active { border-color: #6366f1; background: #eef2ff; color: #4f46e5; }
.gw-payment-area { margin-bottom: 16px; min-height: 42px; }
.gw-error { color: #ef4444; font-size: 12px; margin-bottom: 8px; }
.gw-submit { width: 100%; padding: 12px; background: #0f172a; color: white; border: none; border-radius: 8px; font-size: 16px; font-weight: 600; cursor: pointer; transition: opacity 0.15s; }
.gw-submit:disabled { opacity: 0.5; cursor: not-allowed; }
.gw-submit:hover:not(:disabled) { opacity: 0.9; }
.gw-card-input { border: 1px solid #e2e8f0; border-radius: 6px; padding: 12px; background: white; }
.gw-wallets { display: flex; flex-direction: column; gap: 8px; margin-bottom: 8px; }
@keyframes gw-shimmer { 0% { background-position: -200% 0; } 100% { background-position: 200% 0; } }
.gw-skeleton-line {
  height: 40px; border-radius: 6px; margin-bottom: 10px;
  background: linear-gradient(90deg, #f1f5f9 25%, #e8edf5 50%, #f1f5f9 75%);
  background-size: 200% 100%;
  animation: gw-shimmer 1.4s ease-in-out infinite;
}
.gw-skeleton-row { display: flex; gap: 10px; }
.gw-skeleton-row .gw-skeleton-line { flex: 1; margin-bottom: 0; }
`;

let stylesInjected = false;
function injectStyles(): void {
  if (stylesInjected || typeof document === 'undefined') return;
  const style = document.createElement('style');
  style.textContent = STYLES;
  document.head.appendChild(style);
  stylesInjected = true;
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
  private skeletonEl: HTMLElement | null = null;
  private stripeClientSecret: string | null = null;  // cached per instance; cleared only on destroy()

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private stripe: any = null;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private stripeElements: any = null;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private stripeCard: any = null;        // legacy Card Element — used by mount() only
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private squareCard: any = null;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private squareMethods: any[] = [];
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private braintreeDropin: any = null;

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private listeners: Partial<{ [K in keyof EventMap]: Listener<any> }> = {};

  private checkoutLinkToken: string | null = null;
  private checkoutOnSuccess: ((r: CheckoutResult) => void) | null = null;
  private checkoutOnError: ((e: Error) => void) | null = null;
  private isHostedMode = false;

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

  // ── Hosted checkout mode ───────────────────────────────────────────────────

  async mountCheckout(
    selector: string | HTMLElement,
    options: {
      linkToken: string;
      onSuccess: (result: CheckoutResult) => void;
      onError?: (err: Error) => void;
    }
  ): Promise<this> {
    injectStyles();

    const el = typeof selector === 'string'
      ? document.querySelector<HTMLElement>(selector)
      : selector;
    if (!el) throw new Error(`GatewayEmbedded: element not found: ${selector}`);
    this.container = el;
    this.isHostedMode = true;
    this.checkoutLinkToken = options.linkToken;
    this.checkoutOnSuccess = options.onSuccess;
    this.checkoutOnError = options.onError ?? null;

    // Detect redirect return from Stripe (iDEAL, Amazon Pay, etc.)
    // Stripe appends ?payment_intent_client_secret=... to the return URL
    const urlParams = new URLSearchParams(window.location.search);
    const piClientSecret = urlParams.get('payment_intent_client_secret');
    if (piClientSecret) {
      this.renderReturnLoading();
      try {
        const resultRes = await fetch(
          `${this.baseUrl}/pub/pay/${encodeURIComponent(options.linkToken)}/stripe-result` +
          `?piClientSecret=${encodeURIComponent(piClientSecret)}`
        );
        const data = await resultRes.json() as CheckoutResult;
        if (!resultRes.ok) throw new Error((data as unknown as { detail?: string }).detail ?? 'Payment result check failed');
        if (data.success && data.redirectUrl) {
          window.location.href = data.redirectUrl;
        } else if (data.success) {
          this.checkoutOnSuccess?.(data);
        } else {
          this.checkoutOnError?.(new Error(data.failureMessage ?? 'Payment was not successful'));
        }
      } catch (e) {
        this.checkoutOnError?.(e as Error);
      }
      return this;
    }

    const res = await fetch(
      `${this.baseUrl}/pub/checkout-session?linkToken=${encodeURIComponent(options.linkToken)}`
    );
    if (!res.ok) {
      const err = await res.json().catch(() => ({})) as { detail?: string };
      throw new Error(err.detail ?? `Failed to load checkout session (${res.status})`);
    }
    this.session = await res.json() as CheckoutSession;

    this.render();
    this.fire('ready', undefined as unknown as void);
    return this;
  }

  // ── Embedded SDK mode ──────────────────────────────────────────────────────

  async mount(selector: string | HTMLElement): Promise<this> {
    injectStyles();

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

  destroy(): void {
    this.destroyProviderForms().catch(() => {});
    if (this.container) this.container.innerHTML = '';
    this.container = null;
    this.area = null;
    this.errEl = null;
    this.submitBtn = null;
    this.session = null;
    this.selectedProvider = null;
    this.stripeClientSecret = null;
  }

  // ── Rendering ──────────────────────────────────────────────────────────────

  private render(): void {
    if (!this.container || !this.session) return;
    const providers = this.session.providers ?? [];
    this.container.innerHTML = '';
    this.container.className = 'gw-checkout';

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
    this.showSkeleton();
    if (this.submitBtn) this.submitBtn.disabled = true;

    const opt = this.session.providers.find(p => p.provider === provider);
    if (!opt) return;

    // Pre-attach a hidden slot so provider SDKs can access a live DOM node
    // (Braintree dropin and Square card.attach require the container to be in the document)
    const slot = document.createElement('div');
    slot.hidden = true;
    this.area.appendChild(slot);

    try {
      if (provider === 'STRIPE') await this.buildStripeForm(opt, slot);
      else if (provider === 'SQUARE') await this.buildSquareForm(opt, slot);
      else if (provider === 'BRAINTREE') await this.buildBraintreeForm(slot);
    } catch (e) {
      slot.remove();
      this.clearSkeleton();
      this.showError((e as Error).message);
      return;
    }

    // ── Single place: skeleton cleared, form revealed — enforced for every provider ──
    this.clearSkeleton();
    slot.hidden = false;
  }

  // ── Stripe ─────────────────────────────────────────────────────────────────
  // Hosted mode  : Payment Element (multi-method, accordion layout)
  // Embedded mode: Card Element (backwards-compatible for existing integrations)

  private async buildStripeForm(opt: ProviderOption, container: HTMLElement): Promise<void> {
    if (!this.session) throw new Error('No session');
    if (!window.Stripe) await this.loadScript('https://js.stripe.com/v3/');
    if (!window.Stripe) throw new Error('Failed to load Stripe.js');

    this.stripe = window.Stripe(opt.clientKey);

    if (this.isHostedMode) {
      // Lazy-create the Stripe PaymentIntent — cached so switching providers and back reuses it
      if (!this.stripeClientSecret) {
        const prepRes = await fetch(
          `${this.baseUrl}/pub/pay/${encodeURIComponent(this.checkoutLinkToken!)}/prepare-stripe`,
          { method: 'POST' }
        );
        if (!prepRes.ok) {
          const err = await prepRes.json().catch(() => ({})) as { detail?: string };
          throw new Error(err.detail ?? 'Failed to prepare Stripe checkout');
        }
        const { clientSecret } = await prepRes.json() as { clientSecret: string };
        this.stripeClientSecret = clientSecret;
      }

      const elements = this.stripe.elements({
        clientSecret: this.stripeClientSecret,
        appearance: { theme: 'stripe' },
      });
      this.stripeElements = elements;

      const paymentElement = elements.create('payment', { layout: 'accordion' });
      paymentElement.mount(container);
      paymentElement.on('ready', () => { if (this.submitBtn) this.submitBtn.disabled = false; });
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      paymentElement.on('change', (e: any) => { if (e.complete) this.showError(null); });
    } else {
      container.className = 'gw-card-input';
      const elements = this.stripe.elements();
      const card = elements.create('card', {
        style: {
          base: { fontSize: '15px', fontFamily: 'inherit', color: '#0f172a', '::placeholder': { color: '#94a3b8' } },
          invalid: { color: '#ef4444' },
        },
      });
      card.mount(container);
      card.on('ready', () => { if (this.submitBtn) this.submitBtn.disabled = false; });
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      card.on('change', (e: any) => this.showError(e.error?.message ?? null));
      this.stripeCard = card;
    }
  }

  // ── Square ─────────────────────────────────────────────────────────────────

  private async buildSquareForm(opt: ProviderOption, container: HTMLElement): Promise<void> {
    if (!this.session) throw new Error('No session');

    const scriptUrl = this.session.mode === 'LIVE'
      ? 'https://web.squarecdn.com/v1/square.js'
      : 'https://sandbox.web.squarecdn.com/v1/square.js';
    if (!window.Square) await this.loadScript(scriptUrl);
    if (!window.Square) throw new Error('Failed to load Square SDK');

    const payments = window.Square.payments(opt.clientKey, opt.clientConfig?.['locationId'] ?? '');

    const paymentRequest = payments.paymentRequest({
      countryCode: 'US',
      currencyCode: (this.session.currency ?? 'USD').toUpperCase(),
      total: {
        amount: ((this.session.amount ?? 0) / 100).toFixed(2),
        label: this.session.title ?? 'Total',
      },
    });

    const walletsContainer = document.createElement('div');
    walletsContainer.className = 'gw-wallets';
    container.appendChild(walletsContainer);

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
        await method.attach(div);
        this.squareMethods.push(method);
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        method.addEventListener('ontokenization', async (evt: any) => {
          const { tokenResult } = evt.detail;
          if (tokenResult.status === 'OK') await this.tokenizeAndSubmit('SQUARE', tokenResult.token);
        });
      } catch {
        // wallet not available in this browser/env — skip
      }
    }

    const card = await payments.card();
    const cardDiv = document.createElement('div');
    cardDiv.className = 'gw-card-input';
    container.appendChild(cardDiv);
    await card.attach(cardDiv);
    this.squareCard = card;

    if (this.submitBtn) this.submitBtn.disabled = false;
  }

  // ── Braintree ──────────────────────────────────────────────────────────────

  private async buildBraintreeForm(container: HTMLElement): Promise<void> {
    const tokenUrl = this.checkoutLinkToken
      ? `${this.baseUrl}/pub/braintree-client-token?linkToken=${encodeURIComponent(this.checkoutLinkToken)}`
      : `${this.baseUrl}/pub/braintree-client-token`;
    const headers: Record<string, string> = this.checkoutLinkToken
      ? {}
      : { 'Authorization': `Bearer ${this.pk}` };

    const res = await fetch(tokenUrl, { headers });
    if (!res.ok) throw new Error('Failed to get Braintree client token');
    const { clientToken } = await res.json() as { clientToken: string };

    if (!window.braintree?.dropin) {
      await this.loadScript('https://js.braintreegateway.com/web/dropin/1.43.0/js/dropin.min.js');
    }
    if (!window.braintree?.dropin) throw new Error('Failed to load Braintree SDK');

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    this.braintreeDropin = await (window.braintree as any).dropin.create({
      authorization: clientToken,
      container,
    });

    if (this.submitBtn) this.submitBtn.disabled = false;
  }

  private async destroyProviderForms(): Promise<void> {
    if (this.stripeCard) { this.stripeCard.destroy(); this.stripeCard = null; }
    this.stripeElements = null;
    if (this.squareCard) { const c = this.squareCard; this.squareCard = null; c.destroy().catch(() => {}); }
    const methods = this.squareMethods.splice(0);
    methods.forEach(m => m.destroy().catch(() => {}));
    if (this.braintreeDropin) { const d = this.braintreeDropin; this.braintreeDropin = null; d.teardown().catch(() => {}); }
    this.stripe = null;
  }

  // ── Submit ─────────────────────────────────────────────────────────────────

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
      this.checkoutOnError?.(e as Error);
    } finally {
      if (this.submitBtn) this.submitBtn.disabled = false;
    }
  }

  private async submitStripe(): Promise<void> {
    if (!this.stripe) throw new Error('Stripe not ready');

    if (this.isHostedMode && this.stripeElements) {
      // confirmPayment handles all method types:
      // - Card/wallets: completes in-place (redirect: 'if_required' stays on page)
      // - Redirect methods (iDEAL, Amazon Pay, etc.): navigates to provider, returns via return_url
      const { error } = await this.stripe.confirmPayment({
        elements: this.stripeElements,
        confirmParams: { return_url: window.location.href },
        redirect: 'if_required',
      });
      if (error) throw new Error(error.message ?? 'Payment failed');

      // No redirect — payment confirmed in-place (card, Apple Pay, etc.)
      // Call stripe-result to create our DB record and get the CheckoutResult
      const res = await fetch(
        `${this.baseUrl}/pub/pay/${encodeURIComponent(this.checkoutLinkToken!)}/stripe-result` +
        `?piClientSecret=${encodeURIComponent(this.stripeClientSecret!)}`
      );
      const data = await res.json() as CheckoutResult;
      if (!res.ok) throw new Error((data as unknown as { detail?: string }).detail ?? 'Payment failed');
      if (data.success && data.redirectUrl) {
        window.location.href = data.redirectUrl;
      } else {
        this.checkoutOnSuccess?.(data);
      }
    } else {
      if (!this.stripeCard) throw new Error('Stripe card not ready');
      const { paymentMethod, error } = await this.stripe.createPaymentMethod({ type: 'card', card: this.stripeCard });
      if (error || !paymentMethod) throw new Error(error?.message ?? 'Card error');
      await this.tokenizeGateway('STRIPE', paymentMethod.id);
    }
  }

  private async submitSquare(): Promise<void> {
    if (!this.squareCard) throw new Error('Square not ready');
    const result = await this.squareCard.tokenize();
    if (result.status !== 'OK') {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      throw new Error(result.errors?.map((e: any) => e.message).join(', ') ?? 'Card error');
    }
    await this.tokenizeAndSubmit('SQUARE', result.token);
  }

  private async submitBraintree(): Promise<void> {
    if (!this.braintreeDropin) throw new Error('Braintree not ready');
    const { nonce } = await this.braintreeDropin.requestPaymentMethod();
    await this.tokenizeAndSubmit('BRAINTREE', nonce);
  }

  private async tokenizeAndSubmit(provider: string, providerPmId: string): Promise<void> {
    if (this.isHostedMode && this.checkoutLinkToken) {
      const gwToken = await this.tokenizeHosted(provider, providerPmId);
      const result = await this.submitCheckout(gwToken);
      if (result.success && result.redirectUrl) {
        window.location.href = result.redirectUrl;
      } else {
        this.checkoutOnSuccess?.(result);
      }
    } else {
      await this.tokenizeGateway(provider, providerPmId);
    }
  }

  private async tokenizeHosted(provider: string, providerPmId: string): Promise<string> {
    const res = await fetch(`${this.baseUrl}/pub/tokenize`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider, providerPmId, linkToken: this.checkoutLinkToken }),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({})) as { detail?: string };
      throw new Error(err.detail ?? 'Tokenization failed');
    }
    return ((await res.json()) as { gatewayToken: string }).gatewayToken;
  }

  private async submitCheckout(gatewayToken: string): Promise<CheckoutResult> {
    const res = await fetch(`${this.baseUrl}/pub/pay/${this.checkoutLinkToken}/checkout`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ gatewayToken }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error((data as { detail?: string }).detail ?? 'Payment failed');
    return data as CheckoutResult;
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

  // ── Helpers ────────────────────────────────────────────────────────────────

  /** Shows a loading skeleton while checking a Stripe redirect return */
  private renderReturnLoading(): void {
    if (!this.container) return;
    this.container.innerHTML = '';
    this.container.className = 'gw-checkout';
    const el = document.createElement('div');
    el.className = 'gw-skeleton';
    el.style.padding = '16px 0';
    el.innerHTML = `
      <div class="gw-skeleton-line"></div>
      <div class="gw-skeleton-row">
        <div class="gw-skeleton-line"></div>
        <div class="gw-skeleton-line"></div>
      </div>`;
    this.container.appendChild(el);
  }

  private showError(msg: string | null): void {
    if (!this.errEl) return;
    this.errEl.textContent = msg ?? '';
    this.errEl.style.display = msg ? '' : 'none';
  }

  private showSkeleton(): void {
    if (!this.area) return;
    const el = document.createElement('div');
    el.className = 'gw-skeleton';
    el.innerHTML = `
      <div class="gw-skeleton-line"></div>
      <div class="gw-skeleton-row">
        <div class="gw-skeleton-line"></div>
        <div class="gw-skeleton-line"></div>
      </div>`;
    this.area.appendChild(el);
    this.skeletonEl = el;
  }

  private clearSkeleton(): void {
    this.skeletonEl?.remove();
    this.skeletonEl = null;
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
