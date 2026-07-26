import IssuingPlaceholderPage from '@/components/issuing/IssuingPlaceholderPage';

export default function IssuingAuthorizationsPage() {
  return (
    <IssuingPlaceholderPage
      title="Authorizations"
      eyebrow="Authorizations"
      summary="Issuer authorization decisions, control declines, holds, reversals, and expiry state."
      primaryHref="/issuing/settlement"
      primaryLabel="View settlement"
      related={[
        { href: '/issuing/cards', label: 'Cards' },
        { href: '/issuing/programs', label: 'Programs' },
        { href: '/issuing/settlement', label: 'Settlement' },
      ]}
    />
  );
}
