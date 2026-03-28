// Provider brand assets — used by the pay page and any future dashboard components

export const PROVIDER_BRAND: Record<string, { name: string; icon: React.ReactNode; color: string }> = {
  STRIPE: {
    name: 'Stripe',
    icon: (
      <svg viewBox="0 0 28 28" className="size-5" aria-hidden="true">
        <rect width="28" height="28" rx="6" fill="#635BFF" />
        <path d="M13.2 10.4c0-.9.7-1.3 1.8-1.3 1.6 0 3.6.5 5 1.3V6.3A13.3 13.3 0 0 0 15 5.5c-3.3 0-5.5 1.7-5.5 4.6 0 4.5 6.2 3.8 6.2 5.7 0 1-.9 1.4-2.1 1.4-1.8 0-4-.7-5.6-1.7v4.2c1.5.7 3.1 1 5.6 1 3.4 0 5.7-1.7 5.7-4.6-.1-4.8-6.1-4-6.1-5.7Z" fill="#fff" />
      </svg>
    ),
    color: 'border-[#635BFF] bg-[#635BFF]/5 text-[#635BFF]',
  },
  SQUARE: {
    name: 'Square',
    icon: (
      <svg viewBox="0 0 28 28" className="size-5" aria-hidden="true">
        <rect width="28" height="28" rx="6" fill="#000" />
        <rect x="8" y="8" width="12" height="12" rx="2" fill="#fff" />
        <rect x="11" y="11" width="6" height="6" rx="1" fill="#000" />
      </svg>
    ),
    color: 'border-gray-900 bg-gray-900/5 text-gray-900',
  },
  ADYEN: {
    name: 'Adyen',
    icon: (
      <svg viewBox="0 0 28 28" className="size-5" aria-hidden="true">
        <rect width="28" height="28" rx="6" fill="#0ABF53" />
        <text x="5" y="20" fontSize="13" fontWeight="bold" fill="#fff">Ad</text>
      </svg>
    ),
    color: 'border-[#0ABF53] bg-[#0ABF53]/5 text-[#0ABF53]',
  },
  BRAINTREE: {
    name: 'Braintree',
    icon: (
      <svg viewBox="0 0 28 28" className="size-5" aria-hidden="true">
        <rect width="28" height="28" rx="6" fill="#009CDE" />
        <path d="M7 19V9h4.5c2.5 0 4 1.2 4 3.2 0 1.3-.7 2.3-1.8 2.8L16.5 19H14l-2.5-3.6H9.4V19H7Zm2.4-5.4h1.9c1.1 0 1.8-.5 1.8-1.5s-.7-1.4-1.8-1.4H9.4v2.9Z" fill="#fff" />
      </svg>
    ),
    color: 'border-[#009CDE] bg-[#009CDE]/5 text-[#009CDE]',
  },
};
