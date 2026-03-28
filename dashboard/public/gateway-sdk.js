/**
 * MasonXPay Browser SDK  — gateway-sdk.js
 * Pattern B: collects card client-side, returns gw_tok_xxx to your server.
 *
 * Usage:
 *   <script src="/gateway-sdk.js"></script>
 *   const gw = new GatewayEmbedded('pk_test_xxx', { baseUrl: 'http://localhost:8080' });
 *   await gw.mount('#payment-form');
 *   gw.on('token', ({ gatewayToken }) => { yourServer.pay(gatewayToken, amount, currency); });
 */
(function (global) {
  'use strict';

  // ── Default styles injected once ────────────────────────────────────────────
  const STYLES = `
    .gw-picker { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 16px; }
    .gw-pill {
      display: flex; align-items: center; gap: 6px;
      padding: 8px 14px; border: 2px solid #e2e8f0; border-radius: 10px;
      background: #fff; font-size: 14px; font-weight: 600; cursor: pointer;
      transition: border-color .15s, background .15s; color: #64748b;
    }
    .gw-pill:hover { border-color: #94a3b8; }
    .gw-pill--active { border-color: #6366f1; background: #eef2ff; color: #4f46e5; }
    .gw-wallets { display: flex; flex-direction: column; gap: 8px; margin-bottom: 8px; }
    .gw-card-input {
      border: 1px solid #e2e8f0; border-radius: 8px; padding: 12px;
      background: #fff; min-height: 42px; margin-bottom: 12px;
    }
    .gw-error {
      color: #dc2626; font-size: 13px; margin-bottom: 10px;
      padding: 8px 12px; background: #fef2f2; border-radius: 6px; border: 1px solid #fca5a5;
    }
    .gw-submit {
      width: 100%; padding: 12px; background: #6366f1; color: #fff;
      border: none; border-radius: 8px; font-size: 15px; font-weight: 600;
      cursor: pointer; transition: background .15s;
    }
    .gw-submit:hover { background: #4f46e5; }
    .gw-submit:disabled { background: #a5b4fc; cursor: not-allowed; }
  `;

  let stylesInjected = false;
  function injectStyles() {
    if (stylesInjected) return;
    const el = document.createElement('style');
    el.textContent = STYLES;
    document.head.appendChild(el);
    stylesInjected = true;
  }

  // ── GatewayEmbedded class ────────────────────────────────────────────────────
  function GatewayEmbedded(publishableKey, options) {
    this.pk = publishableKey;
    this.baseUrl = ((options || {}).baseUrl || 'http://localhost:8080').replace(/\/$/, '');
    this._session = null;
    this._container = null;
    this._area = null;
    this._errEl = null;
    this._submitBtn = null;
    this._selectedProvider = null;
    this._stripe = null;
    this._stripeCard = null;
    this._squareCard = null;
    this._squareMethods = [];
    this._listeners = {};
  }

  GatewayEmbedded.prototype.on = function (event, handler) {
    this._listeners[event] = handler;
    return this;
  };

  GatewayEmbedded.prototype._fire = function (event, data) {
    var fn = this._listeners[event];
    if (fn) fn(data);
  };

  GatewayEmbedded.prototype.mount = async function (selector) {
    injectStyles();
    var el = typeof selector === 'string' ? document.querySelector(selector) : selector;
    if (!el) throw new Error('GatewayEmbedded: element not found: ' + selector);
    this._container = el;

    var res = await fetch(this.baseUrl + '/pub/checkout-session', {
      headers: { 'Authorization': 'Bearer ' + this.pk, 'Accept': 'application/json' },
    });
    if (!res.ok) {
      var err = await res.json().catch(function () { return {}; });
      throw new Error(err.detail || 'Failed to load checkout session (' + res.status + ')');
    }
    this._session = await res.json();
    this._render();
    this._fire('ready', null);
    return this;
  };

  GatewayEmbedded.prototype._render = function () {
    var self = this;
    var providers = (this._session && this._session.providers) || [];
    this._container.innerHTML = '';

    // Provider picker
    if (providers.length > 1) {
      var picker = document.createElement('div');
      picker.className = 'gw-picker';
      providers.forEach(function (p) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'gw-pill';
        btn.dataset.provider = p.provider;
        btn.textContent = self._brandName(p.provider);
        btn.addEventListener('click', function () { self._selectProvider(p.provider); });
        picker.appendChild(btn);
      });
      this._container.appendChild(picker);
    }

    var area = document.createElement('div');
    area.className = 'gw-payment-area';
    this._container.appendChild(area);
    this._area = area;

    var errEl = document.createElement('div');
    errEl.className = 'gw-error';
    errEl.style.display = 'none';
    this._container.appendChild(errEl);
    this._errEl = errEl;

    var amount = this._session && this._session.amount;
    var currency = (this._session && this._session.currency) || 'USD';

    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'gw-submit';
    btn.textContent = amount ? 'Pay ' + this._fmt(amount, currency) : 'Pay';
    btn.addEventListener('click', function () { self._submit(); });
    this._container.appendChild(btn);
    this._submitBtn = btn;

    if (providers.length > 0) this._selectProvider(providers[0].provider);
  };

  GatewayEmbedded.prototype._selectProvider = async function (provider) {
    var self = this;
    this._selectedProvider = provider;
    this._container.querySelectorAll('.gw-pill').forEach(function (b) {
      b.classList.toggle('gw-pill--active', b.dataset.provider === provider);
    });

    await this._destroyProviderForms();
    this._area.innerHTML = '';
    this._showError(null);

    var opt = (this._session.providers || []).find(function (p) { return p.provider === provider; });
    if (!opt) return;

    if (provider === 'STRIPE') await this._mountStripe(opt);
    else if (provider === 'SQUARE') await this._mountSquare(opt);
  };

  // ── Stripe ───────────────────────────────────────────────────────────────────
  GatewayEmbedded.prototype._mountStripe = async function (opt) {
    var self = this;
    if (!this._area || !this._submitBtn) return;
    this._submitBtn.disabled = true;

    if (!window.Stripe) await this._loadScript('https://js.stripe.com/v3/');
    if (!window.Stripe) { this._showError('Failed to load Stripe.js'); return; }

    this._stripe = window.Stripe(opt.clientKey);
    var elements = this._stripe.elements();
    var card = elements.create('card', {
      style: {
        base: { fontSize: '15px', fontFamily: 'inherit', color: '#0f172a', '::placeholder': { color: '#94a3b8' } },
        invalid: { color: '#ef4444' },
      },
    });

    var mountDiv = document.createElement('div');
    mountDiv.className = 'gw-card-input';
    this._area.appendChild(mountDiv);
    card.mount(mountDiv);

    card.on('ready', function () { if (self._submitBtn) self._submitBtn.disabled = false; });
    card.on('change', function (e) { self._showError(e.error ? e.error.message : null); });
    this._stripeCard = card;
  };

  // ── Square ────────────────────────────────────────────────────────────────────
  GatewayEmbedded.prototype._mountSquare = async function (opt) {
    var self = this;
    if (!this._area || !this._submitBtn || !this._session) return;
    this._submitBtn.disabled = true;

    var scriptUrl = this._session.mode === 'LIVE'
      ? 'https://web.squarecdn.com/v1/square.js'
      : 'https://sandbox.web.squarecdn.com/v1/square.js';
    if (!window.Square) await this._loadScript(scriptUrl);
    if (!window.Square) { this._showError('Failed to load Square SDK'); return; }

    var locationId = (opt.clientConfig && opt.clientConfig.locationId) || '';
    var payments = window.Square.payments(opt.clientKey, locationId);

    var paymentRequest = payments.paymentRequest({
      countryCode: 'US',
      currencyCode: ((this._session.currency) || 'USD').toUpperCase(),
      total: {
        amount: ((this._session.amount || 0) / 100).toFixed(2),
        label: this._session.title || 'Total',
      },
    });

    // Wallet buttons (best-effort)
    var walletsContainer = document.createElement('div');
    walletsContainer.className = 'gw-wallets';
    this._area.appendChild(walletsContainer);

    var walletFactories = [
      function () { return payments.googlePay(paymentRequest); },
      function () { return payments.applePay(paymentRequest); },
      function () { return payments.cashAppPay(paymentRequest); },
    ];

    for (var i = 0; i < walletFactories.length; i++) {
      try {
        var method = await walletFactories[i]();
        var wDiv = document.createElement('div');
        walletsContainer.appendChild(wDiv);
        wDiv.innerHTML = '';
        await method.attach(wDiv);
        this._squareMethods.push(method);
        (function (m) {
          m.addEventListener('ontokenization', async function (evt) {
            var tokenResult = evt.detail.tokenResult;
            if (tokenResult.status === 'OK') await self._tokenizeGateway('SQUARE', tokenResult.token);
          });
        })(method);
      } catch (e) { /* wallet unavailable — skip */ }
    }

    // Card form
    var card = await payments.card();
    var cardDiv = document.createElement('div');
    cardDiv.className = 'gw-card-input';
    this._area.appendChild(cardDiv);
    cardDiv.innerHTML = '';
    await card.attach(cardDiv);
    this._squareCard = card;

    if (this._submitBtn) this._submitBtn.disabled = false;
  };

  GatewayEmbedded.prototype._destroyProviderForms = async function () {
    if (this._stripeCard) { this._stripeCard.destroy(); this._stripeCard = null; }
    if (this._squareCard) { var c = this._squareCard; this._squareCard = null; c.destroy().catch(function () {}); }
    var methods = this._squareMethods.splice(0);
    methods.forEach(function (m) { m.destroy().catch(function () {}); });
    this._stripe = null;
  };

  // ── Submit ────────────────────────────────────────────────────────────────────
  GatewayEmbedded.prototype._submit = async function () {
    if (!this._submitBtn) return;
    this._showError(null);
    this._submitBtn.disabled = true;
    try {
      if (this._selectedProvider === 'STRIPE') await this._submitStripe();
      else if (this._selectedProvider === 'SQUARE') await this._submitSquare();
    } catch (e) {
      var msg = (e && e.message) || 'Payment error';
      this._showError(msg);
      this._fire('error', { message: msg });
    } finally {
      if (this._submitBtn) this._submitBtn.disabled = false;
    }
  };

  GatewayEmbedded.prototype._submitStripe = async function () {
    if (!this._stripe || !this._stripeCard) throw new Error('Stripe not ready');
    var result = await this._stripe.createPaymentMethod({ type: 'card', card: this._stripeCard });
    if (result.error || !result.paymentMethod) throw new Error((result.error && result.error.message) || 'Card error');
    await this._tokenizeGateway('STRIPE', result.paymentMethod.id);
  };

  GatewayEmbedded.prototype._submitSquare = async function () {
    if (!this._squareCard) throw new Error('Square not ready');
    var result = await this._squareCard.tokenize();
    if (result.status !== 'OK') {
      throw new Error((result.errors || []).map(function (e) { return e.message; }).join(', ') || 'Card error');
    }
    await this._tokenizeGateway('SQUARE', result.token);
  };

  GatewayEmbedded.prototype._tokenizeGateway = async function (provider, providerPmId) {
    var self = this;
    var res = await fetch(this.baseUrl + '/pub/tokenize', {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer ' + this.pk,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ provider: provider, providerPmId: providerPmId }),
    });
    if (!res.ok) {
      var err = await res.json().catch(function () { return {}; });
      throw new Error(err.detail || 'Tokenization failed');
    }
    var data = await res.json();
    this._fire('token', { gatewayToken: data.gatewayToken, provider: provider });
  };

  // ── Helpers ───────────────────────────────────────────────────────────────────
  GatewayEmbedded.prototype._showError = function (msg) {
    if (!this._errEl) return;
    this._errEl.textContent = msg || '';
    this._errEl.style.display = msg ? '' : 'none';
  };

  GatewayEmbedded.prototype._fmt = function (amount, currency) {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: currency }).format(amount / 100);
  };

  GatewayEmbedded.prototype._brandName = function (provider) {
    return { STRIPE: 'Stripe', SQUARE: 'Square', ADYEN: 'Adyen' }[provider] || provider;
  };

  GatewayEmbedded.prototype._loadScript = function (src) {
    return new Promise(function (resolve, reject) {
      var script = document.createElement('script');
      script.src = src;
      script.onload = resolve;
      script.onerror = function () { reject(new Error('Failed to load ' + src)); };
      document.head.appendChild(script);
    });
  };

  global.GatewayEmbedded = GatewayEmbedded;
})(window);
