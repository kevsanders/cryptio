import React, { useEffect, useMemo, useRef, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Checkbox } from "@/components/ui/checkbox";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { CalendarIcon, RefreshCw, Upload, Download, Search, Loader2, Trash2, Tags, Reconcile as ReconcileIcon } from "lucide-react";
import { format } from "date-fns";
import { cn } from "@/lib/utils";

/**
 * Kraken Transactions Management Page
 *
 * Production-ready React (with Tailwind + shadcn/ui) for managing Kraken transactions in the cryptio app.
 *
 * Features
 * - Filter by date range, asset/pair, side/type, status, and free-text search
 * - Paginated, sortable table with multi-select + bulk actions (tag, delete, reconcile)
 * - "Sync from Kraken" action (POST /api/kraken/transactions/sync)
 * - Import/Export CSV (client export, server import)
 * - Lightweight metrics header (counts, volume)
 * - Optimistic UI patterns and loading states
 *
 * API CONTRACT (expected backend endpoints)
 * - GET  /api/kraken/transactions?cursor=&limit=&from=&to=&pair=&type=&status=&q=&sort=
 *   -> { items: KrakenTransaction[], nextCursor?: string, metrics: { count:number, buyVolume:number, sellVolume:number, fees:number } }
 * - POST /api/kraken/transactions/sync { since?: ISODate, pairs?: string[] }
 *   -> { started: boolean, jobId?: string }
 * - POST /api/kraken/transactions/reconcile { ids: string[] }
 *   -> { updated: number }
 * - POST /api/kraken/transactions/tag { ids: string[], tag: string }
 *   -> { updated: number }
 * - DELETE /api/kraken/transactions { ids: string[] }
 *   -> { deleted: number }
 * - POST /api/kraken/transactions/import (multipart/form-data file)
 *   -> { imported:number, duplicates:number }
 *
 * Data Model (example)
 * export type KrakenTransaction = {
 *   id: string;
 *   ts: string;            // ISO date
 *   pair: string;          // e.g. "ETH/EUR"
 *   base: string;          // e.g. "ETH"
 *   quote: string;         // e.g. "EUR"
 *   type: "buy"|"sell"|"deposit"|"withdrawal"|"staking"|"fee";
 *   side?: "long"|"short"|null; // for derivatives if used
 *   price?: number;        // trade price
 *   amount?: number;       // base amount
 *   total?: number;        // quote total
 *   fee?: number;          // quote fee
 *   status: "new"|"reconciled"|"pending"|"error";
 *   tags?: string[];
 *   notes?: string;
 *   txid?: string;         // exchange transaction id
 * };
 */

// ---- Utilities ----
function useDebounced<T>(value: T, delay = 250) {
  const [v, setV] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setV(value), delay);
    return () => clearTimeout(id);
  }, [value, delay]);
  return v;
}

function toISODate(d: Date | undefined | null) {
  return d ? d.toISOString() : undefined;
}

// ---- Types ----
export type KrakenTransaction = {
  id: string;
  ts: string;
  pair: string;
  base: string;
  quote: string;
  type: "buy" | "sell" | "deposit" | "withdrawal" | "staking" | "fee";
  side?: "long" | "short" | null;
  price?: number;
  amount?: number;
  total?: number;
  fee?: number;
  status: "new" | "reconciled" | "pending" | "error";
  tags?: string[];
  notes?: string;
  txid?: string;
};

// ---- Page Component ----
export default function KrakenTransactionsPage() {
  // Filters
  const [from, setFrom] = useState<Date | undefined>(undefined);
  const [to, setTo] = useState<Date | undefined>(undefined);
  const [pair, setPair] = useState<string>("");
  const [type, setType] = useState<string>("");
  const [status, setStatus] = useState<string>("");
  const [query, setQuery] = useState<string>("");
  const debouncedQuery = useDebounced(query, 300);

  // Data
  const [items, setItems] = useState<KrakenTransaction[]>([]);
  const [nextCursor, setNextCursor] = useState<string | undefined>(undefined);
  const [loading, setLoading] = useState(false);
  const [metrics, setMetrics] = useState<{ count: number; buyVolume: number; sellVolume: number; fees: number } | null>(null);
  const [sort, setSort] = useState<string>("-ts");

  // Selection
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const allSelected = items.length > 0 && selected.size === items.length;

  // Effects: initial load + filters/sort changes
  useEffect(() => {
    fetchPage(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedQuery, pair, type, status, from, to, sort]);

  async function fetchPage(reset = false) {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (from) params.set("from", toISODate(from)!);
      if (to) params.set("to", toISODate(to)!);
      if (pair) params.set("pair", pair);
      if (type) params.set("type", type);
      if (status) params.set("status", status);
      if (debouncedQuery) params.set("q", debouncedQuery);
      if (sort) params.set("sort", sort);
      if (!reset && nextCursor) params.set("cursor", nextCursor);
      params.set("limit", "100");

      const res = await fetch(`/api/kraken/transactions?${params.toString()}`);
      if (!res.ok) throw new Error(`Failed to fetch: ${res.status}`);
      const json = (await res.json()) as { items: KrakenTransaction[]; nextCursor?: string; metrics?: any };

      setItems((prev) => (reset ? json.items : [...prev, ...json.items]));
      setNextCursor(json.nextCursor);
      if (json.metrics) setMetrics(json.metrics);
      if (reset) setSelected(new Set());
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  }

  function toggleAll(checked: boolean) {
    setSelected(checked ? new Set(items.map((i) => i.id)) : new Set());
  }

  function toggleOne(id: string, checked: boolean) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (checked) next.add(id); else next.delete(id);
      return next;
    });
  }

  // Actions
  async function syncFromKraken() {
    setLoading(true);
    try {
      const body: any = {};
      if (from) body.since = toISODate(from);
      const res = await fetch("/api/kraken/transactions/sync", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
      if (!res.ok) throw new Error("Sync start failed");
      // After kicking off, immediately refresh data
      await fetchPage(true);
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  }

  async function reconcileSelected() {
    if (selected.size === 0) return;
    const ids = [...selected];
    const prev = items;
    // Optimistic UI
    setItems((it) => it.map((t) => (ids.includes(t.id) ? { ...t, status: "reconciled" } : t)));
    try {
      const res = await fetch("/api/kraken/transactions/reconcile", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ ids }) });
      if (!res.ok) throw new Error("Reconcile failed");
      setSelected(new Set());
    } catch (e) {
      console.error(e);
      setItems(prev); // rollback
    }
  }

  async function bulkTag(tag: string) {
    if (!tag || selected.size === 0) return;
    const ids = [...selected];
    const prev = items;
    setItems((it) => it.map((t) => (ids.includes(t.id) ? { ...t, tags: Array.from(new Set([...(t.tags || []), tag])) } : t)));
    try {
      const res = await fetch("/api/kraken/transactions/tag", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ ids, tag }) });
      if (!res.ok) throw new Error("Tagging failed");
      setSelected(new Set());
    } catch (e) {
      console.error(e);
      setItems(prev);
    }
  }

  async function bulkDelete() {
    if (selected.size === 0) return;
    if (!confirm(`Delete ${selected.size} transactions? This cannot be undone.`)) return;
    const ids = [...selected];
    const prev = items;
    setItems((it) => it.filter((t) => !ids.includes(t.id)));
    try {
      const res = await fetch("/api/kraken/transactions", { method: "DELETE", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ ids }) });
      if (!res.ok) throw new Error("Delete failed");
      setSelected(new Set());
    } catch (e) {
      console.error(e);
      setItems(prev);
    }
  }

  function exportCSV() {
    const header = ["id","ts","pair","type","price","amount","total","fee","status","tags","notes","txid"].join(",");
    const rows = items.map((t) => [t.id, t.ts, t.pair, t.type, t.price ?? "", t.amount ?? "", t.total ?? "", t.fee ?? "", t.status, (t.tags||[]).join("|"), JSON.stringify(t.notes||""), t.txid||""].join(","));
    const csv = [header, ...rows].join("\n");
    const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `kraken-transactions-${Date.now()}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  const counts = useMemo(() => ({
    total: metrics?.count ?? items.length,
    buys: items.filter((i) => i.type === "buy").length,
    sells: items.filter((i) => i.type === "sell").length,
    pending: items.filter((i) => i.status === "pending").length,
    reconciled: items.filter((i) => i.status === "reconciled").length,
  }), [items, metrics]);

  return (
    <div className="p-6 space-y-6">
      {/* Header + Actions */}
      <div className="flex flex-col md:flex-row md:items-end gap-3 justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Kraken Transactions</h1>
          <p className="text-sm text-muted-foreground">Ingest, filter, reconcile and tag Kraken account activity.</p>
        </div>
        <div className="flex gap-2">
          <Button onClick={syncFromKraken} disabled={loading} className="gap-2">
            {loading ? <Loader2 className="h-4 w-4 animate-spin"/> : <RefreshCw className="h-4 w-4"/>}
            Sync from Kraken
          </Button>
          <label className="inline-flex items-center">
            <input type="file" accept=".csv" className="hidden" onChange={async (e) => {
              const file = e.target.files?.[0];
              if (!file) return;
              const fd = new FormData();
              fd.set("file", file);
              setLoading(true);
              try {
                const res = await fetch("/api/kraken/transactions/import", { method: "POST", body: fd });
                if (!res.ok) throw new Error("Import failed");
                await fetchPage(true);
              } catch (err) { console.error(err); } finally { setLoading(false); }
            }}/>
            <Button asChild variant="outline" className="gap-2">
              <span><Upload className="h-4 w-4"/> Import CSV</span>
            </Button>
          </label>
          <Button variant="outline" onClick={exportCSV} className="gap-2">
            <Download className="h-4 w-4"/> Export CSV
          </Button>
        </div>
      </div>

      {/* Filters */}
      <Card className="border-dashed">
        <CardContent className="pt-6 grid grid-cols-1 md:grid-cols-6 gap-3">
          <div className="col-span-2">
            <label className="text-sm text-muted-foreground">Search</label>
            <div className="relative">
              <Search className="h-4 w-4 absolute left-2 top-1/2 -translate-y-1/2"/>
              <Input className="pl-8" placeholder="txid, notes, base, quote…" value={query} onChange={(e) => setQuery(e.target.value)} />
            </div>
          </div>

          <div>
            <label className="text-sm text-muted-foreground">Pair</label>
            <Input placeholder="e.g. ETH/EUR" value={pair} onChange={(e) => setPair(e.target.value.toUpperCase())}/>
          </div>

          <div>
            <label className="text-sm text-muted-foreground">Type</label>
            <Select value={type} onValueChange={setType}>
              <SelectTrigger><SelectValue placeholder="Any"/></SelectTrigger>
              <SelectContent>
                <SelectItem value="">Any</SelectItem>
                <SelectItem value="buy">Buy</SelectItem>
                <SelectItem value="sell">Sell</SelectItem>
                <SelectItem value="deposit">Deposit</SelectItem>
                <SelectItem value="withdrawal">Withdrawal</SelectItem>
                <SelectItem value="staking">Staking</SelectItem>
                <SelectItem value="fee">Fee</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div>
            <label className="text-sm text-muted-foreground">Status</label>
            <Select value={status} onValueChange={setStatus}>
              <SelectTrigger><SelectValue placeholder="Any"/></SelectTrigger>
              <SelectContent>
                <SelectItem value="">Any</SelectItem>
                <SelectItem value="new">New</SelectItem>
                <SelectItem value="pending">Pending</SelectItem>
                <SelectItem value="reconciled">Reconciled</SelectItem>
                <SelectItem value="error">Error</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="text-sm text-muted-foreground">From</label>
              <Input type="date" value={from ? format(from, "yyyy-MM-dd") : ""} onChange={(e) => setFrom(e.target.value ? new Date(e.target.value) : undefined)}/>
            </div>
            <div>
              <label className="text-sm text-muted-foreground">To</label>
              <Input type="date" value={to ? format(to, "yyyy-MM-dd") : ""} onChange={(e) => setTo(e.target.value ? new Date(e.target.value) : undefined)}/>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Metrics */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <StatCard title="Total" value={counts.total.toLocaleString()} />
        <StatCard title="Buys" value={counts.buys.toLocaleString()} />
        <StatCard title="Sells" value={counts.sells.toLocaleString()} />
        <StatCard title="Reconciled" value={counts.reconciled.toLocaleString()} />
      </div>

      {/* Bulk actions */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Checkbox id="select-all" checked={allSelected} onCheckedChange={(v) => toggleAll(Boolean(v))} />
          <label htmlFor="select-all" className="text-sm">Select all</label>
          <Button size="sm" variant="outline" className="gap-2" onClick={() => bulkTag(prompt("Add tag:") || "") } disabled={selected.size===0}><Tags className="h-4 w-4"/> Tag</Button>
          <Button size="sm" variant="outline" className="gap-2" onClick={reconcileSelected} disabled={selected.size===0}><ReconcileIcon className="h-4 w-4"/> Reconcile</Button>
          <Button size="sm" variant="destructive" className="gap-2" onClick={bulkDelete} disabled={selected.size===0}><Trash2 className="h-4 w-4"/> Delete</Button>
          {selected.size>0 && <span className="text-sm text-muted-foreground">{selected.size} selected</span>}
        </div>
        <div className="text-sm text-muted-foreground">Sort:
          <Select value={sort} onValueChange={setSort}>
            <SelectTrigger className="ml-2 w-[200px]"><SelectValue/></SelectTrigger>
            <SelectContent>
              <SelectItem value="-ts">Newest first</SelectItem>
              <SelectItem value="ts">Oldest first</SelectItem>
              <SelectItem value="pair">Pair A→Z</SelectItem>
              <SelectItem value="-pair">Pair Z→A</SelectItem>
              <SelectItem value="-amount">Amount desc</SelectItem>
              <SelectItem value="amount">Amount asc</SelectItem>
            </SelectContent>
          </Select>
        </div>
      </div>

      {/* Table */}
      <div className="overflow-x-auto rounded-2xl border">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-muted/40">
              <Th className="w-10">{/* checkbox */}</Th>
              <Th>Date</Th>
              <Th>Pair</Th>
              <Th>Type</Th>
              <Th className="text-right">Price</Th>
              <Th className="text-right">Amount</Th>
              <Th className="text-right">Total</Th>
              <Th className="text-right">Fee</Th>
              <Th>Status</Th>
              <Th>Tags</Th>
              <Th>TxID</Th>
            </tr>
          </thead>
          <tbody>
            {items.map((t) => (
              <tr key={t.id} className="border-t hover:bg-muted/20">
                <Td>
                  <Checkbox checked={selected.has(t.id)} onCheckedChange={(v) => toggleOne(t.id, Boolean(v))} />
                </Td>
                <Td>{format(new Date(t.ts), "yyyy-MM-dd HH:mm")}</Td>
                <Td className="font-medium">{t.pair}</Td>
                <Td>
                  <Badge variant={badgeByType(t.type)} className="capitalize">{t.type}</Badge>
                </Td>
                <Td className="text-right">{fmtNum(t.price)}</Td>
                <Td className="text-right">{fmtNum(t.amount)}</Td>
                <Td className="text-right">{fmtNum(t.total)}</Td>
                <Td className="text-right">{fmtNum(t.fee)}</Td>
                <Td>
                  <Badge variant={badgeByStatus(t.status)} className="capitalize">{t.status}</Badge>
                </Td>
                <Td className="max-w-[220px] truncate">
                  {t.tags?.map((tag) => (
                    <Badge key={tag} variant="secondary" className="mr-1 mb-1">{tag}</Badge>
                  ))}
                </Td>
                <Td className="max-w-[220px] truncate" title={t.txid || ""}>{t.txid || ""}</Td>
              </tr>
            ))}
            {items.length === 0 && !loading && (
              <tr>
                <Td colSpan={11} className="text-center text-muted-foreground py-8">No transactions match your filters.</Td>
              </tr>
            )}
          </tbody>
        </table>
        {loading && (
          <div className="py-4 flex items-center justify-center text-sm text-muted-foreground gap-2">
            <Loader2 className="h-4 w-4 animate-spin"/> Loading…
          </div>
        )}
      </div>

      {/* Pagination */}
      <div className="flex justify-center">
        <Button variant="outline" onClick={() => fetchPage(false)} disabled={!nextCursor || loading}>
          {nextCursor ? "Load more" : "No more results"}
        </Button>
      </div>
    </div>
  );
}

// ---- Presentational helpers ----
function StatCard({ title, value }: { title: string; value: string }) {
  return (
    <Card className="rounded-2xl shadow-sm">
      <CardHeader className="pb-2">
        <CardTitle className="text-sm text-muted-foreground">{title}</CardTitle>
      </CardHeader>
      <CardContent>
        <div className="text-2xl font-semibold">{value}</div>
      </CardContent>
    </Card>
  );
}

function Th({ children, className }: React.HTMLAttributes<HTMLTableCellElement>) {
  return <th className={cn("text-left font-medium p-3", className)}>{children}</th>;
}
function Td({ children, className, colSpan }: React.HTMLAttributes<HTMLTableCellElement> & { colSpan?: number }) {
  return <td colSpan={colSpan} className={cn("p-3 align-middle", className)}>{children}</td>;
}

function fmtNum(n?: number) {
  if (n === undefined || n === null) return "";
  return new Intl.NumberFormat(undefined, { maximumFractionDigits: 10 }).format(n);
}

function badgeByType(type: KrakenTransaction["type"]) : "default"|"secondary"|"destructive"|"outline" {
  switch(type){
    case "buy": return "default";
    case "sell": return "destructive";
    case "deposit": return "secondary";
    case "withdrawal": return "outline";
    case "staking": return "secondary";
    case "fee": return "outline";
    default: return "default";
  }
}
function badgeByStatus(status: KrakenTransaction["status"]) : "default"|"secondary"|"destructive"|"outline" {
  switch(status){
    case "new": return "default";
    case "pending": return "outline";
    case "reconciled": return "secondary";
    case "error": return "destructive";
    default: return "default";
  }
}
