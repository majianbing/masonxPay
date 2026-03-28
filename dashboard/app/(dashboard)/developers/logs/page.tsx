'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { format } from 'date-fns';
import { ChevronDown, ChevronUp } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { useAuthStore } from '@/store/auth';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
interface GatewayLog {
  id: string;
  type: string;
  method: string;
  path: string;
  responseStatus: number;
  durationMs: number;
  requestBody: string;
  responseBody: string;
  createdAt: string;
}

interface PageResponse {
  content: GatewayLog[];
  totalElements: number;
  totalPages: number;
  number: number;
}

export default function LogsPage() {
  const activeMerchantId = useAuthStore((s) => s.activeMerchantId);
  const mode = useAuthStore((s) => s.mode);
  const [page, setPage] = useState(0);
  const [typeFilter, setTypeFilter] = useState('ALL');
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const { data, isLoading } = useQuery<PageResponse>({
    queryKey: ['logs', activeMerchantId, page, typeFilter, mode],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: '30', mode });
      if (typeFilter !== 'ALL') params.set('type', typeFilter);
      return apiFetch<PageResponse>(`/api/v1/merchants/${activeMerchantId}/logs?${params}`);
    },
    enabled: !!activeMerchantId,
  });

  const statusColor = (status: number) => {
    if (status < 300) return 'text-green-600';
    if (status < 400) return 'text-yellow-600';
    return 'text-red-600';
  };

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Request Logs</h1>

      <Select value={typeFilter} onValueChange={(v: string | null) => { setTypeFilter(v ?? 'ALL'); setPage(0); }}>
        <SelectTrigger className="w-48">
          <SelectValue placeholder="All types" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="ALL">All types</SelectItem>
          <SelectItem value="API_REQUEST">API Request</SelectItem>
          <SelectItem value="PROVIDER_CALL">Provider Call</SelectItem>
          <SelectItem value="WEBHOOK_DELIVERY">Webhook Delivery</SelectItem>
          <SelectItem value="ROUTING_DECISION">Routing Decision</SelectItem>
        </SelectContent>
      </Select>

      <Card>
        <CardContent className="p-0">
          <table className="w-full text-sm">
            <thead className="border-b bg-gray-50">
              <tr>
                {['Time', 'Method', 'Path', 'Status', 'Duration', 'Type', ''].map((h) => (
                  <th key={h} className="px-4 py-3 text-left font-medium text-muted-foreground">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                <tr><td colSpan={7} className="py-8 text-center text-muted-foreground">Loading…</td></tr>
              ) : !data?.content?.length ? (
                <tr><td colSpan={7} className="py-8 text-center text-muted-foreground">No logs found</td></tr>
              ) : data.content.map((log) => (
                <>
                  <tr key={log.id} className="border-b hover:bg-gray-50">
                    <td className="px-4 py-2 text-muted-foreground text-xs whitespace-nowrap">
                      {format(new Date(log.createdAt), 'MMM d HH:mm:ss')}
                    </td>
                    <td className="px-4 py-2 font-mono text-xs">{log.method}</td>
                    <td className="px-4 py-2 font-mono text-xs max-w-xs truncate" title={log.path}>{log.path}</td>
                    <td className={`px-4 py-2 font-mono text-xs font-medium ${statusColor(log.responseStatus)}`}>
                      {log.responseStatus}
                    </td>
                    <td className="px-4 py-2 text-xs text-muted-foreground">{log.durationMs}ms</td>
                    <td className="px-4 py-2">
                      <span className="text-xs bg-gray-100 text-gray-600 px-2 py-0.5 rounded">{log.type}</span>
                    </td>
                    <td className="px-4 py-2">
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => setExpandedId(expandedId === log.id ? null : log.id)}
                      >
                        {expandedId === log.id ? <ChevronUp className="size-3" /> : <ChevronDown className="size-3" />}
                      </Button>
                    </td>
                  </tr>
                  {expandedId === log.id && (
                    <tr key={`${log.id}-expand`} className="bg-gray-50 border-b">
                      <td colSpan={7} className="px-4 py-3">
                        <div className="grid grid-cols-2 gap-4">
                          {log.requestBody && (
                            <div>
                              <p className="text-xs font-medium text-muted-foreground mb-1">Request Body</p>
                              <pre className="text-xs bg-white border rounded p-2 overflow-auto max-h-40">
                                {tryPretty(log.requestBody)}
                              </pre>
                            </div>
                          )}
                          {log.responseBody && (
                            <div>
                              <p className="text-xs font-medium text-muted-foreground mb-1">Response Body</p>
                              <pre className="text-xs bg-white border rounded p-2 overflow-auto max-h-40">
                                {tryPretty(log.responseBody)}
                              </pre>
                            </div>
                          )}
                        </div>
                      </td>
                    </tr>
                  )}
                </>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>

      <div className="flex items-center justify-between text-sm text-muted-foreground">
        <span>{data?.totalElements ?? 0} total logs</span>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</Button>
          <Button variant="outline" size="sm" disabled={page >= (data?.totalPages ?? 1) - 1} onClick={() => setPage((p) => p + 1)}>Next</Button>
        </div>
      </div>
    </div>
  );
}

function tryPretty(s: string): string {
  try { return JSON.stringify(JSON.parse(s), null, 2); } catch { return s; }
}
