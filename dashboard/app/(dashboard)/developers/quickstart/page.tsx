'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Copy, Check, Download, ExternalLink, AlertCircle } from 'lucide-react';
import { cn } from '@/lib/utils';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Button } from '@/components/ui/button';

// ─── Types ────────────────────────────────────────────────────────────────────

interface ApiKey {
  id: string;
  name: string;
  mode: string;
  type: string;
  prefix: string;
  status: string;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function CodeBlock({ code, language = 'bash', vars }: { code: string; language?: string; vars?: Record<string, string> }) {
  const [copied, setCopied] = useState(false);

  const resolved = vars
    ? Object.entries(vars).reduce((s, [k, v]) => s.replaceAll(`{{${k}}}`, v), code)
    : code;

  function copy() {
    navigator.clipboard.writeText(resolved);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  return (
    <div className="relative rounded-lg bg-gray-950 text-gray-100 text-sm overflow-hidden">
      <div className="flex items-center justify-between px-4 py-2 border-b border-gray-800">
        <span className="text-xs text-gray-400 font-mono">{language}</span>
        <button onClick={copy} className="flex items-center gap-1.5 text-xs text-gray-400 hover:text-white transition-colors">
          {copied ? <Check className="size-3.5" /> : <Copy className="size-3.5" />}
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <pre className="overflow-x-auto p-4 leading-relaxed"><code>{resolved}</code></pre>
    </div>
  );
}

function Section({ step, title, description, children }: {
  step: number; title: string; description: string; children: React.ReactNode;
}) {
  return (
    <div className="flex gap-6">
      <div className="flex flex-col items-center">
        <div className="size-9 rounded-full bg-primary text-primary-foreground flex items-center justify-center text-sm font-semibold shrink-0">
          {step}
        </div>
        <div className="w-px flex-1 bg-border mt-3" />
      </div>
      <div className="pb-10 flex-1 min-w-0">
        <h2 className="font-semibold text-base mb-1">{title}</h2>
        <p className="text-sm text-muted-foreground mb-4">{description}</p>
        {children}
      </div>
    </div>
  );
}

function Tabs<T extends string>({ options, value, onChange }: {
  options: { value: T; label: string }[]; value: T; onChange: (v: T) => void;
}) {
  return (
    <div className="flex gap-1 p-1 bg-gray-100 rounded-lg w-fit mb-3">
      {options.map((o) => (
        <button key={o.value} onClick={() => onChange(o.value)}
          className={cn('px-3 py-1 text-sm rounded-md transition-colors',
            value === o.value ? 'bg-white shadow-sm font-medium' : 'text-muted-foreground hover:text-foreground')}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

// ─── Code templates ───────────────────────────────────────────────────────────

type Lang = 'curl' | 'node' | 'python';

const CREATE_LINK: Record<Lang, string> = {
  curl: `curl -X POST {{API_URL}}/api/v1/merchants/{{MERCHANT_ID}}/payment-links?mode={{MODE}} \\
  -H "Authorization: Bearer {{SECRET_KEY}}" \\
  -H "Content-Type: application/json" \\
  -d '{
    "title": "Consulting session — 1 hour",
    "amount": 15000,
    "currency": "usd",
    "redirectUrl": "https://yourshop.com/thank-you"
  }'`,
  node: `const res = await fetch(
  \`{{API_URL}}/api/v1/merchants/{{MERCHANT_ID}}/payment-links?mode={{MODE}}\`,
  {
    method: 'POST',
    headers: {
      'Authorization': 'Bearer {{SECRET_KEY}}',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      title: 'Consulting session — 1 hour',
      amount: 15000,          // in cents
      currency: 'usd',
      redirectUrl: 'https://yourshop.com/thank-you',
    }),
  }
);
const link = await res.json();
console.log(link.payUrl);   // share this with your customer`,
  python: `import httpx

res = httpx.post(
    '{{API_URL}}/api/v1/merchants/{{MERCHANT_ID}}/payment-links',
    params={'mode': '{{MODE}}'},
    headers={'Authorization': 'Bearer {{SECRET_KEY}}'},
    json={
        'title': 'Consulting session — 1 hour',
        'amount': 15000,
        'currency': 'usd',
        'redirect_url': 'https://yourshop.com/thank-you',
    },
)
link = res.json()
print(link['payUrl'])   # share this with your customer`,
};

const EMBED_CHECKOUT = `<!-- After creating a payment link on your server, embed it on your page -->
<iframe
  src="{{PAY_URL}}"
  width="460"
  height="520"
  style="border:none; border-radius:12px"
  allow="payment"
></iframe>

<!-- The iframe handles card input. Your server is notified via webhook.  -->
<!-- Card details never touch your server — you stay out of PCI scope.   -->`;

const CREATE_PAYMENT: Record<Lang, string> = {
  curl: `# Step 1: Create payment intent
curl -X POST {{API_URL}}/api/v1/merchants/{{MERCHANT_ID}}/payment-intents?mode={{MODE}} \\
  -H "Authorization: Bearer {{SECRET_KEY}}" \\
  -H "Content-Type: application/json" \\
  -d '{"amount": 4200, "currency": "usd", "paymentMethodId": "pm_card_visa"}'

# Step 2: Confirm it
curl -X POST {{API_URL}}/api/v1/merchants/{{MERCHANT_ID}}/payment-intents/{id}/confirm \\
  -H "Authorization: Bearer {{SECRET_KEY}}" \\
  -H "Content-Type: application/json" \\
  -d '{"paymentMethodId": "pm_card_visa"}'`,
  node: `// Step 1: create a payment intent
const pi = await fetch(
  \`{{API_URL}}/api/v1/merchants/{{MERCHANT_ID}}/payment-intents?mode={{MODE}}\`,
  {
    method: 'POST',
    headers: {
      'Authorization': 'Bearer {{SECRET_KEY}}',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ amount: 4200, currency: 'usd', paymentMethodId }),
  }
).then(r => r.json());

// Step 2: confirm it
const result = await fetch(
  \`{{API_URL}}/api/v1/merchants/{{MERCHANT_ID}}/payment-intents/\${pi.id}/confirm\`,
  {
    method: 'POST',
    headers: { 'Authorization': 'Bearer {{SECRET_KEY}}' },
    body: JSON.stringify({ paymentMethodId }),
  }
).then(r => r.json());

console.log(result.status); // SUCCEEDED`,
  python: `import httpx

# Step 1: create a payment intent
pi = httpx.post(
    '{{API_URL}}/api/v1/merchants/{{MERCHANT_ID}}/payment-intents',
    params={'mode': '{{MODE}}'},
    headers={'Authorization': 'Bearer {{SECRET_KEY}}'},
    json={'amount': 4200, 'currency': 'usd', 'payment_method_id': payment_method_id},
).json()

# Step 2: confirm it
result = httpx.post(
    f'{{API_URL}}/api/v1/merchants/{{MERCHANT_ID}}/payment-intents/{pi["id"]}/confirm',
    headers={'Authorization': 'Bearer {{SECRET_KEY}}'},
    json={'payment_method_id': payment_method_id},
).json()

print(result['status'])  # SUCCEEDED`,
};

const REFUND_CODE: Record<Lang, string> = {
  curl: `curl -X POST {{API_URL}}/api/v1/merchants/{{MERCHANT_ID}}/payment-intents/{paymentId}/refund \\
  -H "Authorization: Bearer {{SECRET_KEY}}" \\
  -H "Content-Type: application/json" \\
  -d '{"amount": 4200, "reason": "customer_request"}'`,
  node: `const refund = await fetch(
  \`{{API_URL}}/api/v1/merchants/{{MERCHANT_ID}}/payment-intents/\${paymentId}/refund\`,
  {
    method: 'POST',
    headers: { 'Authorization': 'Bearer {{SECRET_KEY}}', 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount: 4200, reason: 'customer_request' }),
  }
).then(r => r.json());

console.log(refund.status); // SUCCEEDED`,
  python: `refund = httpx.post(
    f'{{API_URL}}/api/v1/merchants/{{MERCHANT_ID}}/payment-intents/{payment_id}/refund',
    headers={'Authorization': 'Bearer {{SECRET_KEY}}'},
    json={'amount': 4200, 'reason': 'customer_request'},
).json()
print(refund['status'])`,
};

const WEBHOOK_CODE = `// Express.js — register your endpoint in Dashboard → Developers → Webhooks
app.post('/webhooks/gateway', express.json(), (req, res) => {
  const { event, data } = req.body;

  switch (event) {
    case 'payment.succeeded':
      fulfillOrder(data.id, data.amount, data.currency);
      break;
    case 'payment.failed':
      notifyCustomer(data.id, data.failureCode);
      break;
    case 'refund.succeeded':
      issueRefundConfirmation(data.id);
      break;
  }

  res.json({ received: true });
});`;

const TEST_CARDS = [
  { pan: '4242 4242 4242 4242', outcome: 'Success', network: 'Visa', pmId: 'pm_card_visa' },
  { pan: '5555 5555 5555 4444', outcome: 'Success', network: 'Mastercard', pmId: 'pm_card_mastercard' },
  { pan: '4000 0000 0000 0002', outcome: 'Declined', network: 'Visa', pmId: 'pm_card_chargeDeclined' },
  { pan: '4000 0000 0000 9995', outcome: 'Insufficient Funds', network: 'Visa', pmId: 'pm_card_chargeDeclinedInsufficientFunds' },
];

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function QuickstartPage() {
  const [lang, setLang] = useState<Lang>('node');
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);

  const langOptions: { value: Lang; label: string }[] = [
    { value: 'curl', label: 'cURL' },
    { value: 'node', label: 'Node.js' },
    { value: 'python', label: 'Python' },
  ];

  // Load existing API keys so we can show the merchant their key prefix
  const { data: apiKeys = [] } = useQuery<ApiKey[]>({
    queryKey: ['api-keys', activeMerchantId],
    queryFn: () => apiFetch<ApiKey[]>(`/api/v1/merchants/${activeMerchantId}/api-keys`),
    enabled: !!activeMerchantId,
  });

  const modeKeys = apiKeys.filter((k) => k.mode === mode && k.status === 'ACTIVE' && k.type === 'SECRET');
  const activeKey = modeKeys[0];

  const vars = {
    SECRET_KEY: activeKey ? `${activeKey.prefix}${'x'.repeat(20)}` : `sk_${mode.toLowerCase()}_xxxxxxxxxxxxxxxxxxxx`,
    MERCHANT_ID: activeMerchantId ?? 'YOUR_MERCHANT_ID',
    MODE: mode,
    API_URL: 'http://localhost:8080',
    PAY_URL: 'https://pay.yourgateway.com/pay/TOKEN',
  };

  return (
    <div className="max-w-3xl space-y-0">
      {/* Header */}
      <div className="mb-8 flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">Quickstart</h1>
          <p className="text-sm text-muted-foreground mt-1">
            Accept your first payment in minutes. No provider SDK needed — our hosted checkout handles everything.
          </p>
        </div>
        <a href="/demo.html" download="masonxpay-demo.html">
          <Button variant="outline" size="sm">
            <Download className="size-4 mr-1.5" /> Download Demo
          </Button>
        </a>
      </div>

      {/* API Key callout */}
      {activeKey ? (
        <div className="mb-6 rounded-lg border bg-green-50 border-green-200 p-4 flex items-start gap-3">
          <Check className="size-4 text-green-600 mt-0.5 shrink-0" />
          <div className="text-sm">
            <p className="font-medium text-green-800">
              You have an active {mode} key: <code className="bg-green-100 px-1 rounded font-mono">{activeKey.prefix}…</code>
            </p>
            <p className="text-green-700 mt-0.5">
              The full key was shown once when you created it. If you lost it,{' '}
              <a href="/developers/api-keys" className="underline font-medium">revoke it and create a new one</a>.
            </p>
          </div>
        </div>
      ) : (
        <div className="mb-6 rounded-lg border bg-yellow-50 border-yellow-200 p-4 flex items-start gap-3">
          <AlertCircle className="size-4 text-yellow-600 mt-0.5 shrink-0" />
          <div className="text-sm">
            <p className="font-medium text-yellow-800">No active {mode} API key found.</p>
            <p className="text-yellow-700 mt-0.5">
              <a href="/developers/api-keys" className="underline font-medium">Create one in API Keys</a>{' '}
              — the full key is shown only once at creation. Copy it into your server environment as{' '}
              <code className="bg-yellow-100 px-1 rounded font-mono">
                {mode === 'TEST' ? 'GATEWAY_TEST_KEY' : 'GATEWAY_LIVE_KEY'}
              </code>.
            </p>
          </div>
        </div>
      )}

      {/* Language selector */}
      <div className="mb-6">
        <Tabs options={langOptions} value={lang} onChange={setLang} />
      </div>

      {/* Step 1 */}
      <Section step={1} title="Get your API key"
        description="Your secret key authenticates all server-side requests. Keep it out of client-side code.">
        <div className="rounded-lg border p-4 bg-gray-50 space-y-2 text-sm">
          <div className="flex justify-between items-center">
            <span className="font-medium">Where to find it</span>
            <a href="/developers/api-keys" className="text-primary text-xs flex items-center gap-1 hover:underline">
              Open API Keys <ExternalLink className="size-3" />
            </a>
          </div>
          <ol className="space-y-1.5 text-muted-foreground list-decimal list-inside">
            <li>Go to <strong>Developers → API Keys</strong></li>
            <li>Click <strong>New Key</strong> — make sure mode matches ({mode})</li>
            <li>Copy the key immediately — it is only shown once</li>
            <li>Store it as an environment variable on your server</li>
          </ol>
          <div className="mt-3 font-mono text-xs bg-white border rounded px-3 py-2 text-muted-foreground">
            {mode === 'TEST' ? 'GATEWAY_API_KEY' : 'GATEWAY_LIVE_KEY'}={vars.SECRET_KEY}
          </div>
        </div>
      </Section>

      {/* Step 2 — Hosted checkout (our SDK) */}
      <Section step={2} title="Option A — Hosted checkout (recommended)"
        description="Create a payment link from your server. The customer opens it (or you embed it as an iframe) — our hosted page collects the card, runs it through our provider network, and redirects back.">
        <div className="space-y-3">
          <p className="text-sm font-medium">1. Create the payment link (server-side)</p>
          <CodeBlock code={CREATE_LINK[lang]} language={lang === 'curl' ? 'bash' : lang} vars={vars} />
          <p className="text-sm font-medium mt-4">2. Embed the checkout on your page</p>
          <CodeBlock code={EMBED_CHECKOUT} language="html" vars={vars} />
          <div className="rounded-lg border bg-blue-50 border-blue-200 p-3 text-xs text-blue-700 mt-2">
            <strong>Why this keeps you out of PCI scope:</strong> card details are entered inside our hosted iframe.
            Your server only ever sees a payment status — never a card number or provider token.
          </div>
        </div>
      </Section>

      {/* Step 3 — Direct API */}
      <Section step={3} title="Option B — Direct API"
        description="For full control over the checkout UX. Your frontend tokenizes the card using the provider's JS SDK embedded in our hosted fields, gets back a gateway token, then your server calls our API.">
        <CodeBlock code={CREATE_PAYMENT[lang]} language={lang === 'curl' ? 'bash' : lang} vars={vars} />
        <div className="mt-3 rounded-lg border overflow-hidden">
          <div className="bg-gray-50 px-4 py-2 border-b text-xs font-medium text-muted-foreground">Response</div>
          <CodeBlock language="json" code={`{
  "id": "pi_01J9...",
  "status": "SUCCEEDED",
  "amount": 4200,
  "currency": "usd",
  "provider": "STRIPE",
  "providerPaymentId": "pi_3P...",
  "createdAt": "2026-03-15T10:00:00Z"
}`} />
        </div>
      </Section>

      {/* Step 4 — Test cards */}
      <Section step={4} title="Test cards"
        description="Use these in TEST mode. Any future expiry and any 3-digit CVC work.">
        <div className="rounded-lg border overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b">
              <tr>
                {['Card number', 'PM ID', 'Network', 'Outcome'].map((h) => (
                  <th key={h} className="px-4 py-2.5 text-left text-xs font-medium text-muted-foreground">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {TEST_CARDS.map((c) => (
                <tr key={c.pan} className="border-b last:border-0">
                  <td className="px-4 py-2.5 font-mono text-xs">{c.pan}</td>
                  <td className="px-4 py-2.5 font-mono text-xs text-muted-foreground">{c.pmId}</td>
                  <td className="px-4 py-2.5 text-xs text-muted-foreground">{c.network}</td>
                  <td className="px-4 py-2.5 text-xs">
                    <span className={cn('px-2 py-0.5 rounded-full text-xs font-medium',
                      c.outcome === 'Success' ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700')}>
                      {c.outcome}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>

      {/* Step 5 — Refunds */}
      <Section step={5} title="Issue a refund"
        description="Full and partial refunds. Use the payment intent ID from step 3.">
        <CodeBlock code={REFUND_CODE[lang]} language={lang === 'curl' ? 'bash' : lang} vars={vars} />
      </Section>

      {/* Step 6 — Webhooks */}
      <Section step={6} title="Listen for webhooks"
        description="Register an HTTPS endpoint under Developers → Webhooks. We POST events when payment status changes.">
        <div className="mb-3 grid grid-cols-2 gap-1">
          {['payment.succeeded','payment.failed','payment.pending',
            'refund.succeeded','refund.failed','chargeback.created'].map((e) => (
            <code key={e} className="text-xs bg-gray-100 px-2 py-1 rounded font-mono">{e}</code>
          ))}
        </div>
        <CodeBlock code={WEBHOOK_CODE} language="javascript" />
      </Section>

      {/* Go-live */}
      <div className="rounded-xl border bg-green-50 border-green-200 p-5 space-y-3">
        <h3 className="font-semibold text-green-800">Go-live checklist</h3>
        <ul className="space-y-2 text-sm text-green-800">
          {[
            'Create a LIVE API key and set it on your production server',
            'Add a LIVE connector in Connectors (your real provider secret key)',
            'Switch the dashboard mode toggle to LIVE',
            'Register a production webhook endpoint',
            'Run one real small-amount payment end-to-end',
          ].map((item) => (
            <li key={item} className="flex items-start gap-2">
              <span className="mt-0.5 size-4 rounded-full bg-green-200 text-green-800 flex items-center justify-center text-xs shrink-0">✓</span>
              {item}
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
