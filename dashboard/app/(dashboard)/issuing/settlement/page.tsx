import IssuingPlaceholderPage from '@/components/issuing/IssuingPlaceholderPage';

export default function IssuingSettlementPage() {
  return (
    <IssuingPlaceholderPage
      title="Settlement"
      eyebrow="Settlement"
      summary="Issuer settlement reports, clearing matches, exception lines, and reconciliation summary deltas."
      primaryHref="/virtual-account"
      primaryLabel="Open treasury"
      related={[
        { href: '/issuing/programs', label: 'Programs' },
        { href: '/issuing/authorizations', label: 'Authorizations' },
        { href: '/virtual-account', label: 'Virtual Accounts' },
      ]}
    />
  );
}
