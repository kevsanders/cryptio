// src/main/java/com/sandkev/cryptio/kraken/KrakenTradeIngestService.java
package com.sandkev.cryptio.kraken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import com.sandkev.cryptio.trades.KrakenSignedClient;
import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import com.sandkev.cryptio.tx.TxWriter;

import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class KrakenTradeIngestService {
    private static final Logger log = LoggerFactory.getLogger(KrakenTradeIngestService.class);
    private static final List<String> PREFERRED_QUOTES = List.of("USDT","USD","EUR","GBP");

    private final KrakenAssetUniverseDao assetDao;
    private final KrakenSymbolMapper mapper;
    private final KrakenSignedClient client;
    private final IngestCheckpointDao ckpt;
    private final TxWriter tx;

    // TODO: inject your KrakenSignedClient (or http client), TxWriter, CheckpointDao, etc.
    public KrakenTradeIngestService(KrakenAssetUniverseDao assetDao,
                                    KrakenSymbolMapper mapper,
                                    KrakenSignedClient client,
                                    IngestCheckpointDao ckpt,
                                    TxWriter tx) {
        this.assetDao = assetDao;
        this.mapper = mapper;
        this.client = client;
        this.ckpt = ckpt;
        this.tx = tx;
    }

    /** Ingest trades for ALL discovered assets; tries candidate pairs per asset until one works. */
    public int ingestAllAssets(String accountRef, @Nullable Instant sinceInclusive) {
        String exchange = "kraken";
        Set<String> assets = assetDao.assetsForAccount(accountRef);
        if (assets.isEmpty()) {
            log.info("[{}] No assets discovered for account={} – nothing to ingest.", exchange, accountRef);
            return 0;
        }

        int total = 0;
        for (String rawAsset : assets) {
            String base = mapper.toKrakenBase(rawAsset);
            if (base == null) continue;

            List<String> pairs = mapper.candidatePairs(base);
            boolean done = false;

            for (String pair : pairs) {
                try {
                    int n = fetchAndUpsertTradesForPair(accountRef, pair, sinceInclusive);
                    log.debug("[{}] Ingested {} trades for {}", exchange, n, pair);
                    total += n;
                    done = true;
                    break; // use the first viable pair
                } catch (RuntimeException | NoSuchAlgorithmException | InvalidKeyException ex) {
                    String msg = ex.getMessage();
                    // If the error indicates "Invalid pair", try the next candidate
                    if (msg != null && (msg.contains("EQuery:Unknown asset pair")
                            || msg.contains("EGeneral:Invalid arguments"))) {
                        log.warn("[{}] Skipping unsupported pair {} for asset {}: {}", exchange, pair, rawAsset, msg);
                        continue;
                    }
                    // Other errors: log and continue to next asset (or rethrow if you prefer hard fail)
                    log.error("[{}] Trade ingest failed for {}: {}", exchange, pair, msg, ex);
                    break;
                }
            }

            if (!done) {
                log.info("[{}] No supported trading pair found for asset {} (normalized base={}).", exchange, rawAsset, base);
            }
        }
        return total;
    }

    private int fetchAndUpsertTradesForPair(String accountRef, String pairAltName, @Nullable Instant sinceInclusive) throws InvalidKeyException, NoSuchAlgorithmException {
        int inserted = 0;

        // Resume from checkpoint if sinceInclusive is null
        long startSec = (sinceInclusive != null ? sinceInclusive.getEpochSecond()
                : ckpt.get("kraken", accountRef, "trades:" + pairAltName)
                .map(Instant::getEpochSecond).orElse(0L));

        int ofs = 0;          // Kraken pagination offset
        long maxSeenSec = startSec;
        for (int page = 0; page < 200; page++) {
            var params = new java.util.LinkedHashMap<String,String>();
            // Kraken's TradesHistory doesn't officially support 'pair' filter reliably. We'll fetch window and client-filter.
            params.put("start", String.valueOf(startSec)); // epoch seconds
            params.put("ofs", String.valueOf(ofs));
            params.put("type", "all");
            params.put("trades", "true");

            String path = client.signedPostPath("/0/private/TradesHistory", params); // signed full path+query (or body)
            @SuppressWarnings("unchecked")
            java.util.Map<String,Object> resp = client.postJson(path, java.util.Map.class, "Kraken TradesHistory error");

            if (resp == null || resp.get("result") == null) break;
            @SuppressWarnings("unchecked")
            java.util.Map<String,Object> result = (java.util.Map<String,Object>) resp.get("result");
            @SuppressWarnings("unchecked")
            java.util.Map<String,Object> trades = (java.util.Map<String,Object>) result.getOrDefault("trades", java.util.Map.of());

            if (trades.isEmpty()) {
                // No more rows in this window; stop
                break;
            }

            // Process each trade
            for (var e : trades.entrySet()) {
                String txid = e.getKey();
                @SuppressWarnings("unchecked")
                java.util.Map<String,Object> t = (java.util.Map<String,Object>) e.getValue();

                String krakenPair = String.valueOf(t.get("pair"));   // e.g. "XBTUSDT"
                if (!pairAltName.equals(krakenPair)) {
                    // Not our target pair → skip (TradesHistory returns many pairs)
                    continue;
                }

                // Parse time (epoch seconds possibly as double)
                long timeSec;
                Object timeObj = t.get("time");
                if (timeObj instanceof Number n) {
                    timeSec = (long) Math.floor(n.doubleValue());
                } else {
                    timeSec = Long.parseLong(String.valueOf(timeObj));
                }
                java.time.Instant ts = java.time.Instant.ofEpochSecond(timeSec);

                // Base / Quote from pair altname
                var bq = splitBaseQuote(krakenPair);
                if (bq == null) {
                    // fallback: try first 3-4 chars heuristics, but best is to know quotes list
                    continue;
                }
                String base = bq.base();
                String quote = bq.quote();

                String side = String.valueOf(t.get("type")); // "buy" or "sell"
                java.math.BigDecimal price = new java.math.BigDecimal(String.valueOf(t.get("price")));
                java.math.BigDecimal qty   = new java.math.BigDecimal(String.valueOf(t.get("vol")));
                java.math.BigDecimal fee   = new java.math.BigDecimal(String.valueOf(t.getOrDefault("fee","0")));

                // Fee asset: Kraken doesn't always return fee currency. Use quote by default.
                String feeAsset = quote;

                // Map to our tx type
                String type = "buy".equalsIgnoreCase(side) ? "BUY" : "SELL";

                // Upsert idempotently
                tx.upsert(
                        "kraken", accountRef,
                        base, quote, type,
                        qty, price, fee, feeAsset,
                        ts,
                        "kraken:trade:" + txid
                );

                inserted++;
                if (timeSec > maxSeenSec) maxSeenSec = timeSec;
            }

            // Kraken pagination: result includes "count" (total). We used 'ofs' as 0, 50, 100... typically.
            int count = ((Number) result.getOrDefault("count", trades.size())).intValue();

            // Move offset by number we actually paged; Kraken default page size is ~50
            ofs += trades.size();

            // Stop when we've read all available records
            if (ofs >= count) break;
        }

        // Save checkpoint at max seen time (plus 1s to be safe)
        if (maxSeenSec > startSec) {
            ckpt.put("kraken", accountRef, "trades:" + pairAltName, java.time.Instant.ofEpochSecond(maxSeenSec), null);
        }
        return inserted;
    }

    /** Split base/quote from a Kraken pair altname using a preferred quote list. */
    private static BaseQuote splitBaseQuote(String pairAlt) {
        String p = pairAlt == null ? "" : pairAlt.trim().toUpperCase();
        for (String q : PREFERRED_QUOTES) {
            if (p.endsWith(q)) {
                String base = p.substring(0, p.length() - q.length());
                if (!base.isEmpty()) return new BaseQuote(base, q);
            }
        }
        return null;
    }

    private record BaseQuote(String base, String quote) {}
}
