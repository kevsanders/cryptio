package com.sandkev.cryptio.web.kraken;

import com.sandkev.cryptio.domain.KrakenTxEntity;
import com.sandkev.cryptio.domain.KrakenTxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InMemoryKrakenTransactionService implements KrakenTransactionService {

    private final KrakenTxMapper mapper;

    // Simple in-memory store
    private final Map<String, KrakenTxEntity> store = new ConcurrentHashMap<>();
    private final Set<String> txidIndex = ConcurrentHashMap.newKeySet();

    @Override
    public Page<KrakenTransactionController.KrakenTxView> search(KrakenTransactionController.Filters filters, Pageable pageable) {
        // Seed a few demo rows on first view so the page isn't empty
        if (store.isEmpty()) seedDemo();

        // Transform filter dates to Instants (UTC day bounds)
        Instant fromI = filters.getFrom() != null ? filters.getFrom().atStartOfDay(ZoneOffset.UTC).toInstant() : null;
        Instant toI   = filters.getTo() != null ? filters.getTo().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() : null;

        // Filter
        List<KrakenTxEntity> filtered = store.values().stream()
                .filter(e -> eqOrContains(filters.getPair(), e.getPair()))
                .filter(e -> enumMatchType(filters.getType(), e.getType()))
                .filter(e -> enumMatchStatus(filters.getStatus(), e.getStatus()))
                .filter(e -> fromI == null || (e.getTs() != null && !e.getTs().isBefore(fromI)))
                .filter(e -> toI == null || (e.getTs() != null && e.getTs().isBefore(toI)))
                .filter(e -> textMatch(filters.getQ(), e))
                .collect(Collectors.toList());

        // Sort
        Comparator<KrakenTxEntity> cmp = comparatorFrom(pageable.getSort());
        if (cmp == null) cmp = Comparator.comparing(KrakenTxEntity::getTs, Comparator.nullsLast(Comparator.naturalOrder())).reversed();
        filtered.sort(cmp);

        // Page
        int start = (int) Math.min((long) pageable.getPageNumber() * pageable.getPageSize(), filtered.size());
        int end   = Math.min(start + pageable.getPageSize(), filtered.size());
        List<KrakenTransactionController.KrakenTxView> content = filtered.subList(start, end).stream()
                .map(mapper::toView)
                .toList();

        return new PageImpl<>(content, pageable, filtered.size());
    }

    @Override
    public void startSync(LocalDate since) {
        // Demo: add a synthetic entry so user sees a change
        String id = UUID.randomUUID().toString();
        KrakenTxEntity e = KrakenTxEntity.builder()
                .id(id)
                .ts(Instant.now())
                .base("ETH")
                .quote("EUR")
                .pair("ETH/EUR")
                .type(KrakenTxEntity.Type.buy)
                .status(KrakenTxEntity.Status.NEW)
                .price(new BigDecimal("2500"))
                .amount(new BigDecimal("0.05"))
                .total(new BigDecimal("125"))
                .fee(new BigDecimal("0.25"))
                .txid("sync-" + System.currentTimeMillis())
                .notes(since != null ? "Synced since " + since : "Manual sync")
                .build();
        upsert(e);
    }

    @Override
    public void importCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String header = br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                KrakenTxEntity e = parseCsvLine(line);
                if (e != null) upsert(e);
            }
        } catch (Exception ex) {
            throw new RuntimeException("CSV import failed", ex);
        }
    }

    @Override
    public void reconcile(List<String> ids) {
        ids.forEach(id -> applyIfPresent(id, e -> e.setStatus(KrakenTxEntity.Status.RECONCILED)));
    }

    @Override
    public void delete(List<String> ids) {
        ids.forEach(id -> {
            KrakenTxEntity removed = store.remove(id);
            if (removed != null && removed.getTxid() != null) txidIndex.remove(removed.getTxid());
        });
    }

    @Override
    public void addTag(List<String> ids, String tag) {
        if (tag == null || tag.isBlank()) return;
        ids.forEach(id -> applyIfPresent(id, e -> e.addTag(tag)));
    }

    // ---------- Helpers ----------

    private KrakenTxEntity parseCsvLine(String line) {
        // naive CSV split; assumes prior export format with quoted fields
        // columns: id,ts,pair,type,price,amount,total,fee,status,tags,txid,notes
        List<String> cols = splitCsv(line);
        if (cols.size() < 12) return null;

        String id = blankToNull(cols.get(0));
        Instant ts = parseTs(cols.get(1));
        String pair = blankToNull(cols.get(2));
        String type = blankToNull(cols.get(3));
        BigDecimal price = parseBig(cols.get(4));
        BigDecimal amount = parseBig(cols.get(5));
        BigDecimal total = parseBig(cols.get(6));
        BigDecimal fee = parseBig(cols.get(7));
        String status = blankToNull(cols.get(8));
        String tagsStr = blankToNull(cols.get(9));
        String txid = blankToNull(cols.get(10));
        String notes = blankToNull(cols.get(11));

        String base = null, quote = null;
        if (pair != null && pair.contains("/")) {
            String[] pq = pair.split("/", 2);
            base = pq[0]; quote = pq[1];
        }

        KrakenTxEntity.Type etype = null;
        if (type != null) try { etype = KrakenTxEntity.Type.valueOf(type.toLowerCase()); } catch (IllegalArgumentException ignored) {}

        KrakenTxEntity.Status estatus = KrakenTxEntity.Status.NEW;
        if (status != null) {
            switch (status.toLowerCase()) {
                case "pending" -> estatus = KrakenTxEntity.Status.PENDING;
                case "reconciled" -> estatus = KrakenTxEntity.Status.RECONCILED;
                case "error" -> estatus = KrakenTxEntity.Status.ERROR;
                default -> estatus = KrakenTxEntity.Status.NEW;
            }
        }

        Set<String> tags = new LinkedHashSet<>();
        if (tagsStr != null) {
            for (String t : tagsStr.split("\\|")) {
                if (!t.isBlank()) tags.add(t.trim());
            }
        }

        return KrakenTxEntity.builder()
                .id(id != null ? id : UUID.randomUUID().toString())
                .ts(ts != null ? ts : Instant.now())
                .pair(pair)
                .base(base)
                .quote(quote)
                .type(etype != null ? etype : KrakenTxEntity.Type.buy)
                .status(estatus)
                .price(price)
                .amount(amount)
                .total(total)
                .fee(fee)
                .txid(txid)
                .notes(notes)
                .tags(tags)
                .build();
    }

    private void upsert(KrakenTxEntity e) {
        if (e.getTxid() != null && !txidIndex.add(e.getTxid())) {
            // duplicate txid: replace existing
            store.values().stream()
                    .filter(x -> Objects.equals(x.getTxid(), e.getTxid()))
                    .findFirst()
                    .ifPresent(x -> store.remove(x.getId()));
        }
        if (e.getId() == null) e.setId(UUID.randomUUID().toString());
        if (e.getPair() == null) e.setPairFromBaseQuote();
        if (e.getTs() == null) e.setTs(Instant.now());
        store.put(e.getId(), e);
    }

    private void applyIfPresent(String id, java.util.function.Consumer<KrakenTxEntity> fn) {
        KrakenTxEntity e = store.get(id);
        if (e != null) { fn.accept(e); store.put(id, e); }
    }

    private boolean eqOrContains(String filter, String val) {
        if (filter == null || filter.isBlank()) return true;
        if (val == null) return false;
        return val.equalsIgnoreCase(filter) || val.toLowerCase().contains(filter.toLowerCase());
    }

    private boolean enumMatchType(String filter, KrakenTxEntity.Type type) {
        if (filter == null || filter.isBlank()) return true;
        if (type == null) return false;
        return type.name().equalsIgnoreCase(filter);
    }

    private boolean enumMatchStatus(String filter, KrakenTxEntity.Status status) {
        if (filter == null || filter.isBlank()) return true;
        if (status == null) return false;
        return status.name().equalsIgnoreCase(filter);
    }

    private boolean textMatch(String q, KrakenTxEntity e) {
        if (q == null || q.isBlank()) return true;
        String s = q.toLowerCase(Locale.ROOT);
        return contains(e.getTxid(), s) || contains(e.getNotes(), s) ||
                contains(e.getPair(), s) || contains(e.getBase(), s) || contains(e.getQuote(), s);
    }

    private boolean contains(String hay, String needle) {
        return hay != null && hay.toLowerCase(Locale.ROOT).contains(needle);
    }

    private Comparator<KrakenTxEntity> comparatorFrom(Sort sort) {
        if (sort == null || sort.isUnsorted()) return null;
        Comparator<KrakenTxEntity> cmp = null;
        for (Sort.Order o : sort) {
            Comparator<KrakenTxEntity> next = switch (o.getProperty()) {
                case "ts" -> Comparator.comparing(KrakenTxEntity::getTs, Comparator.nullsLast(Comparator.naturalOrder()));
                case "pair" -> Comparator.comparing(KrakenTxEntity::getPair, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
                case "amount" -> Comparator.comparing(KrakenTxEntity::getAmount, Comparator.nullsLast(Comparator.naturalOrder()));
                default -> Comparator.comparing(KrakenTxEntity::getTs, Comparator.nullsLast(Comparator.naturalOrder()));
            };
            if (o.isDescending()) next = next.reversed();
            cmp = (cmp == null) ? next : cmp.thenComparing(next);
        }
        return cmp;
    }

    // ---- CSV helpers ----

    private List<String> splitCsv(String line) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '\"') {
                    cur.append('\"'); i++; // escaped quote
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString()); cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private Instant parseTs(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception ignored) {}
        try {
            // accept "yyyy-MM-dd HH:mm[:ss]" (assume UTC)
            String trimmed = s.trim();
            DateTimeFormatter f = trimmed.length() == 16
                    ? DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return LocalDateTime.parse(trimmed, f).toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {}
        try {
            // fallback date-only as start-of-day UTC
            LocalDate d = LocalDate.parse(s.trim());
            return d.atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception ignored) {}
        return null;
    }

    private BigDecimal parseBig(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new BigDecimal(s.trim()); } catch (Exception ex) { return null; }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private void seedDemo() {
        // 3 demo rows across types to exercise filters
        upsert(KrakenTxEntity.builder()
                .id(UUID.randomUUID().toString())
                .ts(Instant.now().minusSeconds(3600))
                .base("BTC").quote("EUR").pair("BTC/EUR")
                .type(KrakenTxEntity.Type.buy).status(KrakenTxEntity.Status.NEW)
                .price(new BigDecimal("60000")).amount(new BigDecimal("0.002"))
                .total(new BigDecimal("120")).fee(new BigDecimal("0.12"))
                .txid("demo-btc-1").notes("seed row").build());

        upsert(KrakenTxEntity.builder()
                .id(UUID.randomUUID().toString())
                .ts(Instant.now().minusSeconds(7200))
                .base("ETH").quote("EUR").pair("ETH/EUR")
                .type(KrakenTxEntity.Type.sell).status(KrakenTxEntity.Status.PENDING)
                .price(new BigDecimal("2500")).amount(new BigDecimal("0.05"))
                .total(new BigDecimal("125")).fee(new BigDecimal("0.25"))
                .txid("demo-eth-1").notes("seed row 2").build());

        upsert(KrakenTxEntity.builder()
                .id(UUID.randomUUID().toString())
                .ts(Instant.now().minusSeconds(10800))
                .base("USDT").quote("EUR").pair("USDT/EUR")
                .type(KrakenTxEntity.Type.deposit).status(KrakenTxEntity.Status.RECONCILED)
                .amount(new BigDecimal("100"))
                .total(new BigDecimal("100"))
                .txid("demo-usdt-1").notes("seed row 3").build());
    }
}
