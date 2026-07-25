"use strict";
(() => {
  // src/index.ts
  var STYLES = `
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
  var stylesInjected = false;
  function injectStyles() {
    if (stylesInjected || typeof document === "undefined")
      return;
    const style = document.createElement("style");
    style.textContent = STYLES;
    document.head.appendChild(style);
    stylesInjected = true;
  }
  var GatewayEmbedded = class _GatewayEmbedded {
    constructor(publishableKey, options = {}) {
      this.session = null;
      this.container = null;
      this.area = null;
      this.errEl = null;
      this.submitBtn = null;
      this.selectedProvider = null;
      this.skeletonEl = null;
      this.stripeClientSecret = null;
      // cached per instance; cleared only on destroy()
      this.challengeOverlayEl = null;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      this.challengeMessageListener = null;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      this.stripe = null;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      this.stripeElements = null;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      this.stripeCard = null;
      // legacy Card Element — used by mount() only
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      this.squareCard = null;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      this.squareMethods = [];
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      this.braintreeDropin = null;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      this.listeners = {};
      this.checkoutLinkToken = null;
      this.checkoutOnSuccess = null;
      this.checkoutOnError = null;
      this.isHostedMode = false;
      this.isSubscriptionMode = false;
      this.pendingPiId = null;
      // ── Mason Simulator ────────────────────────────────────────────────────────
      this.simulatorSelectedPan = "4111111111111111";
      this.pk = publishableKey;
      this.baseUrl = (options.baseUrl ?? "http://localhost:8080").replace(/\/$/, "");
    }
    on(event, handler) {
      this.listeners[event] = handler;
      return this;
    }
    fire(event, data) {
      this.listeners[event]?.(data);
    }
    // ── Hosted checkout mode ───────────────────────────────────────────────────
    async mountCheckout(selector, options) {
      injectStyles();
      const el = typeof selector === "string" ? document.querySelector(selector) : selector;
      if (!el)
        throw new Error(`GatewayEmbedded: element not found: ${selector}`);
      this.container = el;
      this.isHostedMode = true;
      this.checkoutLinkToken = options.linkToken;
      this.checkoutOnSuccess = options.onSuccess;
      this.checkoutOnError = options.onError ?? null;
      const urlParams = new URLSearchParams(window.location.search);
      const piClientSecret = urlParams.get("payment_intent_client_secret");
      if (piClientSecret) {
        this.renderReturnLoading();
        try {
          const resultRes = await fetch(
            `${this.baseUrl}/pub/pay/${encodeURIComponent(options.linkToken)}/stripe-result?piClientSecret=${encodeURIComponent(piClientSecret)}`
          );
          const data = await resultRes.json();
          if (!resultRes.ok)
            throw new Error(data.detail ?? "Payment result check failed");
          if (data.success && data.redirectUrl) {
            window.location.href = data.redirectUrl;
          } else if (data.success) {
            this.checkoutOnSuccess?.(data);
          } else {
            this.checkoutOnError?.(new Error(data.failureMessage ?? "Payment was not successful"));
          }
        } catch (e) {
          this.checkoutOnError?.(e);
        }
        return this;
      }
      const res = await fetch(
        `${this.baseUrl}/pub/checkout-session?linkToken=${encodeURIComponent(options.linkToken)}`
      );
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.detail ?? `Failed to load checkout session (${res.status})`);
      }
      this.session = await res.json();
      this.render();
      this.fire("ready", void 0);
      return this;
    }
    /**
     * Mounts a subscription payment-method checkout (billing checkout link).
     * Saves a reusable payment method via /pub/subscription-checkout/{token}/checkout.
     * If the subscription is INCOMPLETE, this also runs the first charge and may return
     * a 3DS/SCA challenge, handled the same way as the payment-link flow.
     */
    async mountSubscriptionCheckout(selector, options) {
      injectStyles();
      const el = typeof selector === "string" ? document.querySelector(selector) : selector;
      if (!el)
        throw new Error(`GatewayEmbedded: element not found: ${selector}`);
      this.container = el;
      this.isHostedMode = true;
      this.isSubscriptionMode = true;
      this.checkoutLinkToken = options.token;
      this.checkoutOnSuccess = options.onSuccess;
      this.checkoutOnError = options.onError ?? null;
      const res = await fetch(
        `${this.baseUrl}/pub/subscription-checkout/${encodeURIComponent(options.token)}`
      );
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.detail ?? `Failed to load subscription checkout (${res.status})`);
      }
      const info = await res.json();
      const amount = info.items.reduce((sum, item) => sum + item.amount * item.quantity, 0);
      this.session = {
        merchantName: info.merchantName,
        mode: info.mode,
        providers: info.connectors.map((c) => ({
          provider: c.provider,
          clientKey: c.clientKey,
          clientConfig: c.clientConfig,
          accountId: c.accountId
        })),
        amount,
        currency: info.currency,
        title: info.merchantName,
        submitLabel: info.status === "INCOMPLETE" ? void 0 : "Save payment method"
      };
      this.render();
      this.fire("ready", void 0);
      return this;
    }
    // ── Embedded SDK mode ──────────────────────────────────────────────────────
    async mount(selector) {
      injectStyles();
      const el = typeof selector === "string" ? document.querySelector(selector) : selector;
      if (!el)
        throw new Error(`GatewayEmbedded: element not found: ${selector}`);
      this.container = el;
      const res = await fetch(`${this.baseUrl}/pub/checkout-session`, {
        headers: { "Authorization": `Bearer ${this.pk}`, "Accept": "application/json" }
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.detail ?? `Failed to load checkout session (${res.status})`);
      }
      this.session = await res.json();
      this.render();
      this.fire("ready", void 0);
      return this;
    }
    destroy() {
      this.destroyProviderForms().catch(() => {
      });
      if (this.challengeOverlayEl) {
        this.challengeOverlayEl.remove();
        this.challengeOverlayEl = null;
      }
      if (this.challengeMessageListener) {
        window.removeEventListener("message", this.challengeMessageListener);
        this.challengeMessageListener = null;
      }
      if (this.container)
        this.container.innerHTML = "";
      this.container = null;
      this.area = null;
      this.errEl = null;
      this.submitBtn = null;
      this.session = null;
      this.selectedProvider = null;
      this.stripeClientSecret = null;
      this.isSubscriptionMode = false;
      this.pendingPiId = null;
    }
    // ── Rendering ──────────────────────────────────────────────────────────────
    render() {
      if (!this.container || !this.session)
        return;
      const providers = this.session.providers ?? [];
      this.container.innerHTML = "";
      this.container.className = "gw-checkout";
      if (providers.length > 1) {
        const picker = document.createElement("div");
        picker.className = "gw-picker";
        providers.forEach((p) => {
          const btn2 = document.createElement("button");
          btn2.type = "button";
          btn2.className = "gw-pill";
          btn2.dataset["provider"] = p.provider;
          btn2.textContent = this.brandName(p.provider);
          btn2.addEventListener("click", () => this.selectProvider(p.provider));
          picker.appendChild(btn2);
        });
        this.container.appendChild(picker);
      }
      const area = document.createElement("div");
      area.className = "gw-payment-area";
      this.container.appendChild(area);
      this.area = area;
      const errEl = document.createElement("div");
      errEl.className = "gw-error";
      errEl.style.display = "none";
      this.container.appendChild(errEl);
      this.errEl = errEl;
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "gw-submit";
      btn.textContent = this.session.submitLabel ?? (this.session.amount ? `Pay ${this.fmt(this.session.amount, this.session.currency ?? "USD")}` : "Pay");
      btn.addEventListener("click", () => this.submit());
      this.container.appendChild(btn);
      this.submitBtn = btn;
      if (providers.length > 0)
        this.selectProvider(providers[0].provider);
    }
    async selectProvider(provider) {
      if (!this.area || !this.session)
        return;
      this.selectedProvider = provider;
      this.container?.querySelectorAll(".gw-pill").forEach((b) => {
        b.classList.toggle("gw-pill--active", b.dataset["provider"] === provider);
      });
      await this.destroyProviderForms();
      this.area.innerHTML = "";
      this.showError(null);
      this.showSkeleton();
      if (this.submitBtn)
        this.submitBtn.disabled = true;
      const opt = this.session.providers.find((p) => p.provider === provider);
      if (!opt)
        return;
      const slot = document.createElement("div");
      slot.hidden = true;
      this.area.appendChild(slot);
      try {
        if (provider === "STRIPE")
          await this.buildStripeForm(opt, slot);
        else if (provider === "SQUARE")
          await this.buildSquareForm(opt, slot);
        else if (provider === "BRAINTREE")
          await this.buildBraintreeForm(slot, opt);
        else if (provider === "MOLLIE")
          this.buildMollieForm(slot);
        else if (provider === "FLUTTERWAVE")
          this.buildFlutterwaveForm(slot);
        else if (provider === "SIMULATOR")
          this.buildSimulatorForm(slot);
      } catch (e) {
        slot.remove();
        this.clearSkeleton();
        this.showError(e.message);
        return;
      }
      this.clearSkeleton();
      slot.hidden = false;
    }
    // ── Stripe ─────────────────────────────────────────────────────────────────
    // Hosted mode  : Payment Element (multi-method, accordion layout)
    // Embedded mode: Card Element (backwards-compatible for existing integrations)
    async buildStripeForm(opt, container) {
      if (!this.session)
        throw new Error("No session");
      if (!window.Stripe)
        await this.loadScript("https://js.stripe.com/v3/");
      if (!window.Stripe)
        throw new Error("Failed to load Stripe.js");
      this.stripe = window.Stripe(opt.clientKey);
      if (this.isHostedMode && !this.isSubscriptionMode) {
        if (!this.stripeClientSecret) {
          const prepRes = await fetch(
            `${this.baseUrl}/pub/pay/${encodeURIComponent(this.checkoutLinkToken)}/prepare-stripe`,
            { method: "POST" }
          );
          if (!prepRes.ok) {
            const err = await prepRes.json().catch(() => ({}));
            throw new Error(err.detail ?? "Failed to prepare Stripe checkout");
          }
          const { clientSecret } = await prepRes.json();
          this.stripeClientSecret = clientSecret;
        }
        const elements = this.stripe.elements({
          clientSecret: this.stripeClientSecret,
          appearance: { theme: "stripe" }
        });
        this.stripeElements = elements;
        const paymentElement = elements.create("payment", { layout: "accordion" });
        paymentElement.mount(container);
        paymentElement.on("ready", () => {
          if (this.submitBtn)
            this.submitBtn.disabled = false;
        });
        paymentElement.on("change", (e) => {
          if (e.complete)
            this.showError(null);
        });
      } else {
        container.className = "gw-card-input";
        const elements = this.stripe.elements();
        const card = elements.create("card", {
          style: {
            base: { fontSize: "15px", fontFamily: "inherit", color: "#0f172a", "::placeholder": { color: "#94a3b8" } },
            invalid: { color: "#ef4444" }
          }
        });
        card.mount(container);
        card.on("ready", () => {
          if (this.submitBtn)
            this.submitBtn.disabled = false;
        });
        card.on("change", (e) => this.showError(e.error?.message ?? null));
        this.stripeCard = card;
      }
    }
    // ── Square ─────────────────────────────────────────────────────────────────
    async buildSquareForm(opt, container) {
      if (!this.session)
        throw new Error("No session");
      const scriptUrl = this.session.mode === "LIVE" ? "https://web.squarecdn.com/v1/square.js" : "https://sandbox.web.squarecdn.com/v1/square.js";
      if (!window.Square)
        await this.loadScript(scriptUrl);
      if (!window.Square)
        throw new Error("Failed to load Square SDK");
      const payments = window.Square.payments(opt.clientKey, opt.clientConfig?.["locationId"] ?? "");
      const paymentRequest = payments.paymentRequest({
        countryCode: "US",
        currencyCode: (this.session.currency ?? "USD").toUpperCase(),
        total: {
          amount: ((this.session.amount ?? 0) / 100).toFixed(2),
          label: this.session.title ?? "Total"
        }
      });
      const walletsContainer = document.createElement("div");
      walletsContainer.className = "gw-wallets";
      container.appendChild(walletsContainer);
      const walletFactories = [
        () => payments.googlePay(paymentRequest),
        () => payments.applePay(paymentRequest),
        () => payments.cashAppPay(paymentRequest)
      ];
      for (const factory of walletFactories) {
        try {
          const method = await factory();
          const div = document.createElement("div");
          walletsContainer.appendChild(div);
          await method.attach(div);
          this.squareMethods.push(method);
          method.addEventListener("ontokenization", async (evt) => {
            const { tokenResult } = evt.detail;
            if (tokenResult.status === "OK")
              await this.tokenizeAndSubmit("SQUARE", tokenResult.token);
          });
        } catch {
        }
      }
      const card = await payments.card();
      const cardDiv = document.createElement("div");
      cardDiv.className = "gw-card-input";
      container.appendChild(cardDiv);
      await card.attach(cardDiv);
      this.squareCard = card;
      if (this.submitBtn)
        this.submitBtn.disabled = false;
    }
    // ── Braintree ──────────────────────────────────────────────────────────────
    async buildBraintreeForm(container, opt) {
      let tokenUrl;
      let headers;
      if (this.isSubscriptionMode) {
        tokenUrl = `${this.baseUrl}/pub/subscription-checkout/braintree-client-token?accountId=${encodeURIComponent(opt?.accountId ?? "")}&subscriptionToken=${encodeURIComponent(this.checkoutLinkToken ?? "")}`;
        headers = {};
      } else if (this.checkoutLinkToken) {
        tokenUrl = `${this.baseUrl}/pub/braintree-client-token?linkToken=${encodeURIComponent(this.checkoutLinkToken)}`;
        headers = {};
      } else {
        tokenUrl = `${this.baseUrl}/pub/braintree-client-token`;
        headers = { "Authorization": `Bearer ${this.pk}` };
      }
      const res = await fetch(tokenUrl, { headers });
      if (!res.ok)
        throw new Error("Failed to get Braintree client token");
      const { clientToken } = await res.json();
      if (!window.braintree?.dropin) {
        await this.loadScript("https://js.braintreegateway.com/web/dropin/1.43.0/js/dropin.min.js");
      }
      if (!window.braintree?.dropin)
        throw new Error("Failed to load Braintree SDK");
      this.braintreeDropin = await window.braintree.dropin.create({
        authorization: clientToken,
        container
      });
      if (this.submitBtn)
        this.submitBtn.disabled = false;
    }
    async destroyProviderForms() {
      if (this.stripeCard) {
        this.stripeCard.destroy();
        this.stripeCard = null;
      }
      this.stripeElements = null;
      if (this.squareCard) {
        const c = this.squareCard;
        this.squareCard = null;
        c.destroy().catch(() => {
        });
      }
      const methods = this.squareMethods.splice(0);
      methods.forEach((m) => m.destroy().catch(() => {
      }));
      if (this.braintreeDropin) {
        const d = this.braintreeDropin;
        this.braintreeDropin = null;
        d.teardown().catch(() => {
        });
      }
      this.stripe = null;
    }
    // ── Submit ─────────────────────────────────────────────────────────────────
    async submit() {
      if (!this.submitBtn)
        return;
      this.showError(null);
      const originalText = this.submitBtn.textContent ?? "Pay";
      this.submitBtn.disabled = true;
      this.submitBtn.innerHTML = '<span class="gw-btn-spinner"></span>Processing\u2026';
      try {
        if (this.selectedProvider === "STRIPE")
          await this.submitStripe();
        else if (this.selectedProvider === "SQUARE")
          await this.submitSquare();
        else if (this.selectedProvider === "BRAINTREE")
          await this.submitBraintree();
        else if (this.selectedProvider === "MOLLIE")
          await this.submitMollie();
        else if (this.selectedProvider === "FLUTTERWAVE")
          await this.submitFlutterwave();
        else if (this.selectedProvider === "SIMULATOR")
          await this.submitSimulator();
      } catch (e) {
        const msg = e.message ?? "Payment error";
        this.showError(msg);
        this.fire("error", { message: msg });
        this.checkoutOnError?.(e);
      } finally {
        if (this.submitBtn) {
          this.submitBtn.disabled = false;
          this.submitBtn.textContent = originalText;
        }
      }
    }
    async submitStripe() {
      if (!this.stripe)
        throw new Error("Stripe not ready");
      if (this.isHostedMode && !this.isSubscriptionMode && this.stripeElements) {
        const { error } = await this.stripe.confirmPayment({
          elements: this.stripeElements,
          confirmParams: { return_url: window.location.href },
          redirect: "if_required"
        });
        if (error)
          throw new Error(error.message ?? "Payment failed");
        const res = await fetch(
          `${this.baseUrl}/pub/pay/${encodeURIComponent(this.checkoutLinkToken)}/stripe-result?piClientSecret=${encodeURIComponent(this.stripeClientSecret)}`
        );
        const data = await res.json();
        if (!res.ok)
          throw new Error(data.detail ?? "Payment failed");
        if (data.success && data.redirectUrl) {
          window.location.href = data.redirectUrl;
        } else {
          this.checkoutOnSuccess?.(data);
        }
      } else {
        if (!this.stripeCard)
          throw new Error("Stripe card not ready");
        const { paymentMethod, error } = await this.stripe.createPaymentMethod({ type: "card", card: this.stripeCard });
        if (error || !paymentMethod)
          throw new Error(error?.message ?? "Card error");
        if (this.isSubscriptionMode) {
          await this.tokenizeAndSubmit("STRIPE", paymentMethod.id);
        } else {
          await this.tokenizeGateway("STRIPE", paymentMethod.id);
        }
      }
    }
    async submitSquare() {
      if (!this.squareCard)
        throw new Error("Square not ready");
      const result = await this.squareCard.tokenize();
      if (result.status !== "OK") {
        throw new Error(result.errors?.map((e) => e.message).join(", ") ?? "Card error");
      }
      await this.tokenizeAndSubmit("SQUARE", result.token);
    }
    async submitBraintree() {
      if (!this.braintreeDropin)
        throw new Error("Braintree not ready");
      const { nonce } = await this.braintreeDropin.requestPaymentMethod();
      await this.tokenizeAndSubmit("BRAINTREE", nonce);
    }
    // ── Mollie ─────────────────────────────────────────────────────────────────
    // Mollie is a pure redirect flow — no client-side SDK.
    // The backend creates a Mollie payment and returns a checkout URL.
    // The browser SDK opens that URL in the existing iframe overlay (same as 3DS redirect_url).
    buildMollieForm(container) {
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
      if (this.submitBtn)
        this.submitBtn.disabled = false;
    }
    async submitMollie() {
      await this.tokenizeAndSubmit("MOLLIE", "");
    }
    // ── Flutterwave ────────────────────────────────────────────────────────────
    // Hosted redirect flow. Card data is collected by Flutterwave, not MasonXPay.
    buildFlutterwaveForm(container) {
      container.innerHTML = `
      <div style="display:flex;flex-direction:column;align-items:center;gap:12px;padding:20px 0;text-align:center;">
        <svg width="40" height="40" viewBox="0 0 28 28" aria-hidden="true">
          <rect width="28" height="28" rx="6" fill="#F5A623"/>
          <path d="M7 8h4.3l1.7 7.4L15.4 8H19l-4.1 12h-3.6L9.7 13l-2.2 7H4l3-12Z" fill="#111827"/>
          <path d="M18.5 12.5 24 8l-2.1 7.2L25 20h-4.2l-2.3-7.5Z" fill="#111827"/>
        </svg>
        <p style="font-size:14px;color:#374151;font-weight:500;margin:0;">Pay with Flutterwave</p>
        <p style="font-size:12px;color:#6b7280;margin:0;max-width:260px;line-height:1.5;">
          You'll be redirected to Flutterwave's secure checkout to complete your payment.
        </p>
      </div>`;
      if (this.submitBtn)
        this.submitBtn.disabled = false;
    }
    async submitFlutterwave() {
      await this.tokenizeAndSubmit("FLUTTERWAVE", "");
    }
    static {
      this.SIMULATOR_PANS = [
        { label: "Approve", pan: "4111111111111111", desc: "Standard approve (last-4 = 1111)" },
        { label: "Insufficient funds", pan: "4111111111110001", desc: "Issuer decline \u2014 insufficient funds" },
        { label: "Do not honor", pan: "4111111111110002", desc: "Hard decline" },
        { label: "Timeout \u2192 UNKNOWN", pan: "4111111111110003", desc: "Auth times out; status stays PROCESSING ~60s" }
      ];
    }
    buildSimulatorForm(container) {
      this.simulatorSelectedPan = _GatewayEmbedded.SIMULATOR_PANS[0].pan;
      const wrap = document.createElement("div");
      wrap.style.cssText = "display:flex;flex-direction:column;gap:4px;";
      const label = document.createElement("p");
      label.style.cssText = "font-size:11px;font-weight:600;color:#6b7280;text-transform:uppercase;letter-spacing:.05em;margin:0 0 6px;";
      label.textContent = "Rail test scenario";
      wrap.appendChild(label);
      _GatewayEmbedded.SIMULATOR_PANS.forEach((p, i) => {
        const row = document.createElement("label");
        row.style.cssText = [
          "display:flex;align-items:flex-start;gap:10px;padding:9px 11px;",
          "border:1px solid #e2e8f0;border-radius:7px;cursor:pointer;",
          "transition:background 0.1s,border-color 0.1s;",
          i === 0 ? "border-color:#6366f1;background:#eef2ff;" : ""
        ].join("");
        const radio = document.createElement("input");
        radio.type = "radio";
        radio.name = "gw-sim-pan";
        radio.value = p.pan;
        radio.checked = i === 0;
        radio.style.cssText = "margin-top:2px;flex-shrink:0;accent-color:#6366f1;";
        const text = document.createElement("div");
        text.innerHTML = `
        <span style="font-size:13px;font-weight:500;color:#1e293b;">${p.label}</span><br>
        <span style="font-size:11px;color:#94a3b8;">${p.desc}</span>`;
        row.appendChild(radio);
        row.appendChild(text);
        radio.addEventListener("change", () => {
          this.simulatorSelectedPan = p.pan;
          wrap.querySelectorAll("label").forEach((l, j) => {
            l.style.borderColor = j === i ? "#6366f1" : "#e2e8f0";
            l.style.background = j === i ? "#eef2ff" : "";
          });
        });
        wrap.appendChild(row);
      });
      container.appendChild(wrap);
      if (this.submitBtn)
        this.submitBtn.disabled = false;
    }
    async submitSimulator() {
      await this.tokenizeAndSubmit("SIMULATOR", this.simulatorSelectedPan);
    }
    async tokenizeAndSubmit(provider, providerPmId) {
      if (this.isHostedMode && this.checkoutLinkToken) {
        const gwToken = await this.tokenizeHosted(provider, providerPmId);
        const result = await this.submitCheckout(gwToken);
        if (result.status === "REQUIRES_ACTION" && result.providerAction) {
          this.pendingPiId = result.paymentIntentId;
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
    async tokenizeHosted(provider, providerPmId) {
      const res = await fetch(`${this.baseUrl}/pub/tokenize`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          provider,
          providerPmId,
          ...this.isSubscriptionMode ? { subscriptionToken: this.checkoutLinkToken } : { linkToken: this.checkoutLinkToken }
        })
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.detail ?? "Tokenization failed");
      }
      return (await res.json()).gatewayToken;
    }
    async submitCheckout(gatewayToken) {
      const path = this.isSubscriptionMode ? "subscription-checkout" : "pay";
      const res = await fetch(`${this.baseUrl}/pub/${path}/${this.checkoutLinkToken}/checkout`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ gatewayToken })
      });
      const data = await res.json();
      if (!res.ok)
        throw new Error(data.detail ?? "Payment failed");
      return data;
    }
    // ── 3DS / SCA ──────────────────────────────────────────────────────────────
    /**
     * Dispatches the correct 3DS/SCA handler based on the action type returned by the backend.
     *
     * "stripe_sdk"   → delegate to Stripe.js handleNextAction() (inline challenge, no page leave)
     * "redirect_url" → open a centered iframe overlay; poll for final status after auth
     */
    async handleProviderAction(action) {
      if (action.type === "stripe_sdk" && action.clientSecret) {
        await this.handle3dsStripeSdk(action.clientSecret);
      } else if (action.actionUrl) {
        await this.openChallengeOverlay(action.actionUrl);
      } else {
        throw new Error("Unsupported provider action \u2014 cannot complete authentication");
      }
    }
    /** Stripe 3DS2 — Stripe.js manages the challenge in-page; no iframe overlay needed */
    async handle3dsStripeSdk(clientSecret) {
      if (!this.stripe)
        throw new Error("Stripe not initialized for 3DS handling");
      const { error } = await this.stripe.handleNextAction({ clientSecret });
      if (error)
        throw new Error(error.message ?? "3DS authentication failed");
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
    openChallengeOverlay(actionUrl) {
      return new Promise((resolve, reject) => {
        const backdrop = document.createElement("div");
        backdrop.style.cssText = [
          "position:fixed;inset:0;z-index:9999;",
          "background:rgba(0,0,0,0.6);",
          "display:flex;align-items:center;justify-content:center;"
        ].join("");
        const modal = document.createElement("div");
        modal.style.cssText = [
          "background:#ffffff;border-radius:12px;overflow:hidden;",
          "width:min(500px,95vw);height:min(700px,90vh);",
          "display:flex;flex-direction:column;",
          "box-shadow:0 25px 50px -5px rgba(0,0,0,0.5);"
        ].join("");
        const header = document.createElement("div");
        header.style.cssText = [
          "padding:12px 16px;background:#f8fafc;",
          "border-bottom:1px solid #e2e8f0;",
          "display:flex;align-items:center;justify-content:space-between;",
          "flex-shrink:0;"
        ].join("");
        const title = document.createElement("span");
        title.textContent = "Complete Authentication";
        title.style.cssText = "font-size:14px;font-weight:600;color:#374151;font-family:system-ui,sans-serif;";
        const cancelBtn = document.createElement("button");
        cancelBtn.type = "button";
        cancelBtn.textContent = "Cancel";
        cancelBtn.style.cssText = [
          "font-size:13px;color:#6b7280;background:none;border:1px solid #d1d5db;",
          "cursor:pointer;padding:4px 12px;border-radius:6px;font-family:inherit;",
          "transition:background 0.15s;"
        ].join("");
        cancelBtn.addEventListener("mouseenter", () => {
          cancelBtn.style.background = "#f3f4f6";
        });
        cancelBtn.addEventListener("mouseleave", () => {
          cancelBtn.style.background = "none";
        });
        cancelBtn.addEventListener("click", async () => {
          cleanup();
          await this.cancel3ds();
          reject(new Error("3DS authentication was canceled"));
        });
        header.appendChild(title);
        header.appendChild(cancelBtn);
        const iframe = document.createElement("iframe");
        iframe.src = actionUrl;
        iframe.allow = "payment";
        iframe.style.cssText = "flex:1;border:none;width:100%;";
        modal.appendChild(header);
        modal.appendChild(iframe);
        backdrop.appendChild(modal);
        document.body.appendChild(backdrop);
        this.challengeOverlayEl = backdrop;
        const cleanup = () => {
          backdrop.remove();
          this.challengeOverlayEl = null;
          window.removeEventListener("message", listener);
          this.challengeMessageListener = null;
        };
        const listener = async (event) => {
          if (!event.data || event.data.type !== "gw:3ds_complete")
            return;
          cleanup();
          try {
            await this.poll3dsStatus();
            resolve();
          } catch (e) {
            reject(e);
          }
        };
        window.addEventListener("message", listener);
        this.challengeMessageListener = listener;
      });
    }
    /**
     * Polls GET /pub/pay/{token}/payment-status until the intent reaches a terminal state.
     * Handles success (fires onSuccess), redirect (navigates), and failure (throws).
     * Polls up to 30 times at 2-second intervals (60 seconds total).
     */
    async poll3dsStatus() {
      const MAX_POLLS = 30;
      const INTERVAL_MS = 2e3;
      const path = this.isSubscriptionMode ? "subscription-checkout" : "pay";
      for (let i = 0; i < MAX_POLLS; i++) {
        await new Promise((r) => setTimeout(r, INTERVAL_MS));
        let data;
        try {
          const res = await fetch(
            `${this.baseUrl}/pub/${path}/${encodeURIComponent(this.checkoutLinkToken)}/payment-status?piId=${encodeURIComponent(this.pendingPiId)}`
          );
          if (!res.ok)
            continue;
          data = await res.json();
        } catch {
          continue;
        }
        if (data.status === "REQUIRES_ACTION" || data.status === "PROCESSING")
          continue;
        this.pendingPiId = null;
        if (data.success && data.redirectUrl) {
          window.location.href = data.redirectUrl;
          return;
        }
        if (data.success) {
          this.checkoutOnSuccess?.(data);
          return;
        }
        throw new Error(data.failureMessage ?? "3DS authentication or payment failed");
      }
      throw new Error("3DS authentication timed out \u2014 please try again");
    }
    /** Signals the backend to cancel the parked REQUIRES_ACTION intent (best-effort). */
    async cancel3ds() {
      if (!this.checkoutLinkToken || !this.pendingPiId)
        return;
      const path = this.isSubscriptionMode ? "subscription-checkout" : "pay";
      try {
        await fetch(
          `${this.baseUrl}/pub/${path}/${encodeURIComponent(this.checkoutLinkToken)}/cancel-3ds?piId=${encodeURIComponent(this.pendingPiId)}`,
          { method: "POST" }
        );
      } catch {
      } finally {
        this.pendingPiId = null;
      }
    }
    async tokenizeGateway(provider, providerPmId) {
      const res = await fetch(`${this.baseUrl}/pub/tokenize`, {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${this.pk}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({ provider, providerPmId })
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.detail ?? "Tokenization failed");
      }
      const data = await res.json();
      this.fire("token", { gatewayToken: data.gatewayToken, provider });
    }
    // ── Helpers ────────────────────────────────────────────────────────────────
    /** Shows a loading skeleton while checking a Stripe redirect return */
    renderReturnLoading() {
      if (!this.container)
        return;
      this.container.innerHTML = "";
      this.container.className = "gw-checkout";
      const el = document.createElement("div");
      el.className = "gw-skeleton";
      el.style.padding = "16px 0";
      el.innerHTML = `
      <div class="gw-skeleton-line"></div>
      <div class="gw-skeleton-row">
        <div class="gw-skeleton-line"></div>
        <div class="gw-skeleton-line"></div>
      </div>`;
      this.container.appendChild(el);
    }
    showError(msg) {
      if (!this.errEl)
        return;
      this.errEl.textContent = msg ?? "";
      this.errEl.style.display = msg ? "" : "none";
    }
    showSkeleton() {
      if (!this.area)
        return;
      const el = document.createElement("div");
      el.className = "gw-skeleton";
      el.innerHTML = `
      <div class="gw-skeleton-line"></div>
      <div class="gw-skeleton-row">
        <div class="gw-skeleton-line"></div>
        <div class="gw-skeleton-line"></div>
      </div>`;
      this.area.appendChild(el);
      this.skeletonEl = el;
    }
    clearSkeleton() {
      this.skeletonEl?.remove();
      this.skeletonEl = null;
    }
    fmt(amount, currency) {
      return new Intl.NumberFormat("en-US", { style: "currency", currency }).format(amount / 100);
    }
    brandName(provider) {
      return { STRIPE: "Stripe", SQUARE: "Square", ADYEN: "Adyen", BRAINTREE: "Braintree", MOLLIE: "Mollie", FLUTTERWAVE: "Flutterwave", SIMULATOR: "Mason Simulator" }[provider] ?? provider;
    }
    loadScript(src) {
      return new Promise((resolve, reject) => {
        const script = document.createElement("script");
        script.src = src;
        script.onload = () => resolve();
        script.onerror = () => reject(new Error(`Failed to load ${src}`));
        document.head.appendChild(script);
      });
    }
  };

  // src/bundle-entry.ts
  globalThis.GatewayEmbedded = GatewayEmbedded;
})();
