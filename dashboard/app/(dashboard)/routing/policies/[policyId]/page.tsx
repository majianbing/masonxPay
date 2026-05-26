'use client';

import { useParams } from 'next/navigation';
import PolicyEditor from '../PolicyEditor';

export default function RoutingPolicyDetailPage() {
  const { policyId } = useParams<{ policyId: string }>();
  return <PolicyEditor policyId={policyId} />;
}
