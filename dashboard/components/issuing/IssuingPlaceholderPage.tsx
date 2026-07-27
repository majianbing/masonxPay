'use client';

import Link from 'next/link';
import { ArrowRight, WalletCards } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { cn } from '@/lib/utils';

interface RelatedLink {
  href: string;
  label: string;
}

interface Props {
  title: string;
  eyebrow: string;
  summary: string;
  primaryHref?: string;
  primaryLabel?: string;
  related?: RelatedLink[];
}

export default function IssuingPlaceholderPage({
  title,
  eyebrow,
  summary,
  primaryHref,
  primaryLabel,
  related = [],
}: Props) {
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <WalletCards className="size-5 text-primary" />
            <h1 className="text-2xl font-semibold tracking-normal">{title}</h1>
          </div>
          <p className="mt-1 text-sm text-muted-foreground">{summary}</p>
        </div>
        {primaryHref && primaryLabel && (
          <ActionLink href={primaryHref} label={primaryLabel} />
        )}
      </div>

      <section className="rounded-md border bg-white">
        <div className="border-b px-4 py-3">
          <p className="text-xs font-medium uppercase text-muted-foreground">{eyebrow}</p>
          <h2 className="mt-1 text-sm font-semibold">PPC8 dashboard surface</h2>
        </div>
        <div className="grid gap-4 p-4 md:grid-cols-3">
          {related.map((item) => (
            <Card key={item.href}>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">{item.label}</CardTitle>
              </CardHeader>
              <CardContent>
                <ActionLink href={item.href} label="Open" variant="outline" />
              </CardContent>
            </Card>
          ))}
        </div>
      </section>
    </div>
  );
}

function ActionLink({ href, label, variant }: { href: string; label: string; variant?: 'outline' }) {
  return (
    <Link
      href={href}
      className={cn(
        'inline-flex h-8 items-center justify-center gap-1.5 rounded-lg px-2.5 text-sm font-medium transition-colors',
        variant === 'outline'
          ? 'border border-border bg-background hover:bg-muted'
          : 'bg-primary text-primary-foreground hover:bg-primary/90',
      )}
    >
      {label}
      <ArrowRight className="size-4" />
    </Link>
  );
}
