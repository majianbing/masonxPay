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

export interface ProviderAction {
  type: string;          // "stripe_sdk" | "redirect_url"
  actionUrl?: string;   // redirect URL for "redirect_url" type
  clientSecret?: string; // Stripe clientSecret for "stripe_sdk" type
}

export interface CheckoutResult {
  success: boolean;
  status: string;
  paymentIntentId: string;
  redirectUrl?: string;
  failureCode?: string;
  failureMessage?: string;
  providerAction?: ProviderAction;
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
.gw-submit { position: relative; overflow: hidden; width: 100%; padding: 12px; background: #0f172a; color: white; border: none; border-radius: 8px; font-size: 16px; font-weight: 600; cursor: pointer; transition: opacity 0.15s; }
.gw-submit:disabled { opacity: 0.5; cursor: not-allowed; }
.gw-submit:hover:not(:disabled) { opacity: 0.9; }
.gw-submit:not(:disabled)::after { content: ''; position: absolute; top: 0; left: -75%; width: 50%; height: 100%; background: linear-gradient(90deg, transparent, rgba(255,255,255,0.18), transparent); transform: skewX(-20deg); animation: gw-btn-sheen 3s ease-in-out infinite; }
@keyframes gw-btn-sheen { 0%, 65% { left: -75%; } 80% { left: 125%; } 100% { left: 125%; } }
.gw-card-input { border: 1px solid #e2e8f0; border-radius: 6px; padding: 12px; background: white; }
.gw-wallets { display: flex; flex-direction: column; gap: 8px; margin-bottom: 8px; }
@keyframes gw-shimmer { 0% { background-position: -200% 0; } 100% { background-position: 200% 0; } }
@keyframes gw-spin { to { transform: rotate(360deg); } }
.gw-btn-spinner { display: inline-block; width: 14px; height: 14px; border: 2px solid rgba(255,255,255,0.3); border-top-color: #fff; border-radius: 50%; animation: gw-spin 0.65s linear infinite; vertical-align: middle; margin-right: 8px; }
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
  private challengeOverlayEl: HTMLElement | null = null;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private challengeMessageListener: ((e: any) => void) | null = null;

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
    if (this.challengeOverlayEl) {
      this.challengeOverlayEl.remove();
      this.challengeOverlayEl = null;
    }
    if (this.challengeMessageListener) {
      window.removeEventListener('message', this.challengeMessageListener);
      this.challengeMessageListener = null;
    }
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
      else if (provider === 'MOLLIE') this.buildMollieForm(slot);
      else if (provider === 'SIMULATOR') this.buildSimulatorForm(slot);
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

    const originalText = this.submitBtn.textContent ?? 'Pay';
    this.submitBtn.disabled = true;
    this.submitBtn.innerHTML = '<span class="gw-btn-spinner"></span>Processing\u2026';

    try {
      if (this.selectedProvider === 'STRIPE') await this.submitStripe();
      else if (this.selectedProvider === 'SQUARE') await this.submitSquare();
      else if (this.selectedProvider === 'BRAINTREE') await this.submitBraintree();
      else if (this.selectedProvider === 'MOLLIE') await this.submitMollie();
      else if (this.selectedProvider === 'SIMULATOR') await this.submitSimulator();
    } catch (e) {
      const msg = (e as Error).message ?? 'Payment error';
      this.showError(msg);
      this.fire('error', { message: msg });
      this.checkoutOnError?.(e as Error);
    } finally {
      if (this.submitBtn) {
        this.submitBtn.disabled = false;
        this.submitBtn.textContent = originalText;
      }
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

  // ── Mollie ─────────────────────────────────────────────────────────────────
  // Mollie is a pure redirect flow — no client-side SDK.
  // The backend creates a Mollie payment and returns a checkout URL.
  // The browser SDK opens that URL in the existing iframe overlay (same as 3DS redirect_url).

  private buildMollieForm(container: HTMLElement): void {
    container.innerHTML = `
      <div style="display:flex;flex-direction:column;align-items:center;gap:12px;padding:20px 0;text-align:center;">
        <svg width="40" height="40" viewBox="0 0 28 28" aria-hidden="true">
          <rect width="28" height="28" rx="6" fill="#000"/>
          <path d="M5 20V10h3l3 6 3-6h3v10h-2.5v-6l-2.5 5h-1l-2.5-5v6H5Z" fill="#fff"/>
          <circle cx="23" cy="10" r="2" fill="#FF6640"/>
        </svg>
        <p style="font-size:14px;color:#374151;font-weight:500;margin:0;">Pay with Mollie</p>
        <p style="font-size:12px;color:#6b7280;margin:0;max-width:260px;line-height:1.5;">
          You'll be redirected to Mollie's secure checkout to complete your payment.
          Choose from iDEAL, credit card, Klarna, and more.
        </p>
      </div>`;
    if (this.submitBtn) this.submitBtn.disabled = false;
  }

  private async submitMollie(): Promise<void> {
    // Tokenize with empty providerPmId — Mollie doesn't use a client-side token
    await this.tokenizeAndSubmit('MOLLIE', '');
  }

  // ── Mason Simulator ────────────────────────────────────────────────────────

  private buildSimulatorForm(container: HTMLElement): void {
    container.innerHTML = `
      <div style="display:flex;flex-direction:column;align-items:center;gap:12px;padding:20px 0;text-align:center;">
        <svg width="40" height="40" viewBox="0 0 28 28" aria-hidden="true">
          <rect width="28" height="28" rx="6" fill="#312e81"/>
          <path d="M7 19V9h3l4 6 4-6h3v10h-2.5v-6l-3.5 5h-2l-3.5-5v6H7Z" fill="#fff"/>
        </svg>
        <p style="font-size:14px;color:#374151;font-weight:500;margin:0;">Mason Simulator</p>
        <p style="font-size:12px;color:#6b7280;margin:0;max-width:280px;line-height:1.5;">
          TEST-only provider. It charges a synthetic card token so routing, capability, retry,
          and hosted-checkout flows can be verified without an external PSP.
        </p>
      </div>`;
    if (this.submitBtn) this.submitBtn.disabled = false;
  }

  private async submitSimulator(): Promise<void> {
    await this.tokenizeAndSubmit('SIMULATOR', `sim_pm_${Date.now()}`);
  }

  private async tokenizeAndSubmit(provider: string, providerPmId: string): Promise<void> {
    if (this.isHostedMode && this.checkoutLinkToken) {
      const gwToken = await this.tokenizeHosted(provider, providerPmId);
      const result = await this.submitCheckout(gwToken);
      if (result.status === 'REQUIRES_ACTION' && result.providerAction) {
        // 3DS / SCA challenge — present inline overlay; resolves after auth completes
        await this.handleProviderAction(result.providerAction);
      } else if (result.success && result.redirectUrl) {
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

  // ── 3DS / SCA ──────────────────────────────────────────────────────────────

  /**
   * Dispatches the correct 3DS/SCA handler based on the action type returned by the backend.
   *
   * "stripe_sdk"   → delegate to Stripe.js handleNextAction() (inline challenge, no page leave)
   * "redirect_url" → open a centered iframe overlay; poll for final status after auth
   */
  private async handleProviderAction(action: ProviderAction): Promise<void> {
    if (action.type === 'stripe_sdk' && action.clientSecret) {
      await this.handle3dsStripeSdk(action.clientSecret);
    } else if (action.actionUrl) {
      await this.openChallengeOverlay(action.actionUrl);
    } else {
      throw new Error('Unsupported provider action — cannot complete authentication');
    }
  }

  /** Stripe 3DS2 — Stripe.js manages the challenge in-page; no iframe overlay needed */
  private async handle3dsStripeSdk(clientSecret: string): Promise<void> {
    if (!this.stripe) throw new Error('Stripe not initialized for 3DS handling');
    const { error } = await this.stripe.handleNextAction({ clientSecret });
    if (error) throw new Error(error.message ?? '3DS authentication failed');
    // Challenge complete — poll our backend for the final settled status
    await this.poll3dsStatus();
  }

  /**
   * Universal iframe overlay for redirect-based 3DS (3DS1 Stripe, and future providers).
   *
   * Layout:
   *   ┌──────────────────────────────┐
   *   │  Complete Authentication [X] │  ← header with cancel button
   *   │                              │
   *   │   <iframe src={actionUrl}>   │  ← bank / issuer 3DS page
   *   │                              │
   *   └──────────────────────────────┘
   *
   * The iframe navigates to the 3DS provider page. After the challenge the provider
   * redirects to our /pay/3ds-return page (set as returnUrl by the backend). That page
   * sends window.parent.postMessage({ type: 'gw:3ds_complete' }) — we tear down the
   * overlay and poll for the final settled status.
   *
   * Cancel button: calls POST /cancel-3ds (best-effort) then rejects the promise.
   * Page-close abandonment: handled server-side by StalePendingIntentJob (30-min threshold).
   */
  private openChallengeOverlay(actionUrl: string): Promise<void> {
    return new Promise((resolve, reject) => {
      // ── Backdrop ────────────────────────────────────────────────────────────
      const backdrop = document.createElement('div');
      backdrop.style.cssText = [
        'position:fixed;inset:0;z-index:9999;',
        'background:rgba(0,0,0,0.6);',
        'display:flex;align-items:center;justify-content:center;',
      ].join('');

      // ── Modal shell ─────────────────────────────────────────────────────────
      const modal = document.createElement('div');
      modal.style.cssText = [
        'background:#ffffff;border-radius:12px;overflow:hidden;',
        'width:min(500px,95vw);height:min(700px,90vh);',
        'display:flex;flex-direction:column;',
        'box-shadow:0 25px 50px -5px rgba(0,0,0,0.5);',
      ].join('');

      // ── Header ──────────────────────────────────────────────────────────────
      const header = document.createElement('div');
      header.style.cssText = [
        'padding:12px 16px;background:#f8fafc;',
        'border-bottom:1px solid #e2e8f0;',
        'display:flex;align-items:center;justify-content:space-between;',
        'flex-shrink:0;',
      ].join('');

      const title = document.createElement('span');
      title.textContent = 'Complete Authentication';
      title.style.cssText = 'font-size:14px;font-weight:600;color:#374151;font-family:system-ui,sans-serif;';

      const cancelBtn = document.createElement('button');
      cancelBtn.type = 'button';
      cancelBtn.textContent = 'Cancel';
      cancelBtn.style.cssText = [
        'font-size:13px;color:#6b7280;background:none;border:1px solid #d1d5db;',
        'cursor:pointer;padding:4px 12px;border-radius:6px;font-family:inherit;',
        'transition:background 0.15s;',
      ].join('');
      cancelBtn.addEventListener('mouseenter', () => { cancelBtn.style.background = '#f3f4f6'; });
      cancelBtn.addEventListener('mouseleave', () => { cancelBtn.style.background = 'none'; });
      cancelBtn.addEventListener('click', async () => {
        cleanup();
        await this.cancel3ds();
        reject(new Error('3DS authentication was canceled'));
      });

      header.appendChild(title);
      header.appendChild(cancelBtn);

      // ── iframe ──────────────────────────────────────────────────────────────
      const iframe = document.createElement('iframe');
      iframe.src = actionUrl;
      iframe.allow = 'payment';
      iframe.style.cssText = 'flex:1;border:none;width:100%;';

      modal.appendChild(header);
      modal.appendChild(iframe);
      backdrop.appendChild(modal);
      document.body.appendChild(backdrop);
      this.challengeOverlayEl = backdrop;

      // ── Cleanup helper ──────────────────────────────────────────────────────
      const cleanup = () => {
        backdrop.remove();
        this.challengeOverlayEl = null;
        window.removeEventListener('message', listener);
        this.challengeMessageListener = null;
      };

      // ── postMessage listener — fired by /pay/3ds-return after redirect ───────
      const listener = async (event: MessageEvent) => {
        if (!event.data || event.data.type !== 'gw:3ds_complete') return;
        cleanup();
        try {
          await this.poll3dsStatus();
          resolve();
        } catch (e) {
          reject(e);
        }
      };

      window.addEventListener('message', listener);
      this.challengeMessageListener = listener;
    });
  }

  /**
   * Polls GET /pub/pay/{token}/payment-status until the intent reaches a terminal state.
   * Handles success (fires onSuccess), redirect (navigates), and failure (throws).
   * Polls up to 30 times at 2-second intervals (60 seconds total).
   */
  private async poll3dsStatus(): Promise<void> {
    const MAX_POLLS = 30;
    const INTERVAL_MS = 2000;

    for (let i = 0; i < MAX_POLLS; i++) {
      await new Promise<void>(r => setTimeout(r, INTERVAL_MS));

      let data: CheckoutResult;
      try {
        const res = await fetch(
          `${this.baseUrl}/pub/pay/${encodeURIComponent(this.checkoutLinkToken!)}/payment-status`
        );
        if (!res.ok) continue; // transient error — keep polling
        data = await res.json() as CheckoutResult;
      } catch {
        continue; // network hiccup — keep polling
      }

      // Still authenticating or settling — keep polling
      if (data.status === 'REQUIRES_ACTION' || data.status === 'PROCESSING') continue;

      // Terminal state reached
      if (data.success && data.redirectUrl) {
        window.location.href = data.redirectUrl;
        return;
      }
      if (data.success) {
        this.checkoutOnSuccess?.(data);
        return;
      }
      throw new Error(data.failureMessage ?? '3DS authentication or payment failed');
    }

    throw new Error('3DS authentication timed out — please try again');
  }

  /** Signals the backend to cancel the parked REQUIRES_ACTION intent (best-effort). */
  private async cancel3ds(): Promise<void> {
    if (!this.checkoutLinkToken) return;
    try {
      await fetch(
        `${this.baseUrl}/pub/pay/${encodeURIComponent(this.checkoutLinkToken)}/cancel-3ds`,
        { method: 'POST' }
      );
    } catch {
      // best-effort — stale job will eventually clean up if this fails
    }
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
    return ({ STRIPE: 'Stripe', SQUARE: 'Square', ADYEN: 'Adyen', BRAINTREE: 'Braintree', MOLLIE: 'Mollie' } as Record<string, string>)[provider] ?? provider;
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
