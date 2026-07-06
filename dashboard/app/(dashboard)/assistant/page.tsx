'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Bot, Check, Copy, Loader2, SendHorizontal } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';

interface Citation {
  sourcePath: string;
  headingPath: string;
  sourceType: string;
  stability: string;
}

interface RagAnswerResponse {
  answer: string;
  citations: Citation[];
  refusalReason: string | null;
  confidence: 'none' | 'low' | 'medium' | 'high';
}

interface RagIndexStatus {
  indexVersion: string;
  gitCommit: string;
  lastIndexedAt: string;
  chunkCount: number;
  sourceCount: number;
  vectorBackend: string;
}

const examples = [
  'How do I run MasonXPay locally?',
  'How does TEST and LIVE mode isolation work?',
  'How do routing policies differ from routing rules?',
];

export default function AssistantPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const [question, setQuestion] = useState('');
  const [answer, setAnswer] = useState<RagAnswerResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copiedSource, setCopiedSource] = useState<string | null>(null);
  const { data: indexStatus } = useQuery<RagIndexStatus>({
    queryKey: ['assistant-index-status', activeMerchantId],
    enabled: Boolean(activeMerchantId),
    queryFn: () => apiFetch<RagIndexStatus>(`/api/v1/merchants/${activeMerchantId}/assistant/status`),
  });

  async function ask(nextQuestion = question) {
    if (!activeMerchantId || !nextQuestion.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const response = await apiFetch<RagAnswerResponse>(
        `/api/v1/merchants/${activeMerchantId}/assistant/questions`,
        {
          method: 'POST',
          body: JSON.stringify({
            question: nextQuestion.trim(),
            maxCitations: 4,
            correlationId: crypto.randomUUID(),
          }),
        },
      );
      setQuestion(nextQuestion);
      setAnswer(response);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Assistant request failed');
    } finally {
      setLoading(false);
    }
  }

  function copySource(sourcePath: string) {
    navigator.clipboard.writeText(sourcePath);
    setCopiedSource(sourcePath);
    setTimeout(() => setCopiedSource(null), 1500);
  }

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col gap-5 p-6">
      <div className="flex items-center gap-3">
        <div className="flex size-9 items-center justify-center rounded-lg bg-primary/10 text-primary">
          <Bot className="size-5" />
        </div>
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Assistant</h1>
          <p className="text-sm text-muted-foreground">Documentation-backed answers with citations.</p>
        </div>
      </div>

      {indexStatus && (
        <div className="grid grid-cols-2 gap-3 rounded-lg border bg-muted/30 px-4 py-3 text-sm md:grid-cols-4">
          <div>
            <div className="text-xs text-muted-foreground">Index</div>
            <div className="font-medium">{indexStatus.indexVersion}</div>
          </div>
          <div>
            <div className="text-xs text-muted-foreground">Sources</div>
            <div className="font-medium">{indexStatus.sourceCount} files / {indexStatus.chunkCount} chunks</div>
          </div>
          <div>
            <div className="text-xs text-muted-foreground">Backend</div>
            <div className="font-medium">{indexStatus.vectorBackend}</div>
          </div>
          <div>
            <div className="text-xs text-muted-foreground">Commit</div>
            <div className="font-mono text-xs">{indexStatus.gitCommit.slice(0, 12)}</div>
          </div>
          <div className="col-span-2 md:col-span-4">
            <div className="text-xs text-muted-foreground">Indexed</div>
            <div className="font-mono text-xs">{indexStatus.lastIndexedAt}</div>
          </div>
        </div>
      )}

      <Card className="rounded-lg">
        <CardHeader>
          <CardTitle>Ask a MasonXPay question</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <form
            className="flex gap-2"
            onSubmit={(event) => {
              event.preventDefault();
              ask();
            }}
          >
            <textarea
              className="min-h-24 flex-1 resize-none rounded-lg border border-input bg-transparent px-3 py-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              placeholder="Ask about setup, SDKs, connectors, routing, subscriptions, rails, ledger concepts, or AI boundaries."
            />
            <Button className="self-end" type="submit" disabled={loading || !question.trim()}>
              {loading ? <Loader2 className="size-4 animate-spin" /> : <SendHorizontal className="size-4" />}
              Ask
            </Button>
          </form>
          <div className="flex flex-wrap gap-2">
            {examples.map((example) => (
              <button
                key={example}
                className="rounded-md border px-2.5 py-1.5 text-xs text-muted-foreground hover:bg-muted hover:text-foreground"
                onClick={() => ask(example)}
                type="button"
              >
                {example}
              </button>
            ))}
          </div>
          {error && <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>}
        </CardContent>
      </Card>

      {answer && (
        <Card className="rounded-lg">
          <CardHeader>
            <CardTitle>{answer.refusalReason ? 'Unsupported request' : 'Answer'}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <p className="whitespace-pre-wrap text-sm leading-6">{answer.answer}</p>
            <div className="text-xs text-muted-foreground">Confidence: {answer.confidence}</div>
            {answer.citations.length > 0 && (
              <div className="space-y-2">
                <h2 className="text-sm font-medium">Sources</h2>
                <div className="grid gap-2">
                  {answer.citations.map((citation) => (
                    <div
                      key={`${citation.sourcePath}-${citation.headingPath}`}
                      className="flex items-start justify-between gap-3 rounded-md border px-3 py-2 text-sm"
                    >
                      <div className="min-w-0 space-y-1">
                        <div className="break-all font-mono text-xs">{citation.sourcePath}</div>
                        <div className="text-xs text-muted-foreground">{citation.headingPath}</div>
                        <div className="flex flex-wrap gap-1.5">
                          <span className="rounded border bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground">
                            {citation.sourceType}
                          </span>
                          <span className="rounded border bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground">
                            {citation.stability}
                          </span>
                        </div>
                      </div>
                      <button
                        className="rounded-md p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                        onClick={() => copySource(citation.sourcePath)}
                        type="button"
                        title="Copy source path"
                      >
                        {copiedSource === citation.sourcePath ? <Check className="size-4" /> : <Copy className="size-4" />}
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </main>
  );
}
