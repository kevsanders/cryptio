package com.sandkev.cryptio.exchange.binance;

import com.sandkev.cryptio.balance.BinanceSignedClient;
import com.sandkev.cryptio.tx.TxUpserter;
import com.sandkev.cryptio.ingest.IngestCheckpointDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class BinanceTradeIngestFromListService {

    private final BinanceSignedClient client;
    private final TxUpserter tx;
    private final IngestCheckpointDao ckpt;

    /**
     * Classpath location of the pairs list; can be overridden, e.g.
     * spring.crypto.binance.pairs-path=exchange/binance/pairs.txt
     */
    @Value("${binance.pairs-path:exchange/binance/pairs.txt}")
    private String pairsPath;

    /**
     * Ingests trades for all symbols listed in the pairs file.
     *
     * @param accountRef     your logical account reference (e.g. "primary")
     * @param sinceInclusive optional starting time for ingestion window
     * @return number of transactions upserted
     */
    public int ingest(String accountRef, @Nullable Instant sinceInclusive) {
        List<String> symbols = loadPairsFromClasspath(pairsPath);
        return ingestSymbols(accountRef, symbols, sinceInclusive);
    }

    /**
     * Same ingestion logic but accepts an explicit list of symbols (already Binance market codes).
     */
    public int ingestSymbols(String accountRef, List<String> symbols, @Nullable Instant sinceInclusive) {
        if (symbols == null || symbols.isEmpty()) {
            log.warn("No symbols provided for ingestion (accountRef={})", accountRef);
            return 0;
        }

        // Normalise & de-dup while preserving order
        Set<String> uniq = new LinkedHashSet<>();
        for (String s : symbols) {
            if (s == null) continue;
            String sym = s.trim().toUpperCase();
            if (sym.isEmpty()) continue;
            uniq.add(sym);
        }

        int total = 0;
        long seed = sinceInclusive == null ? 0L : sinceInclusive.toEpochMilli();

        for (String sym : uniq) {
            try {
                int n = new BinanceSymbolTradesIngest(client, ckpt, tx, sym, seed).ingest(accountRef);
                total += n;
                log.info("Ingested {} trades for {}", n, sym);
            } catch (RuntimeException ex) {
                String msg = ex.getMessage();
                if (msg != null && msg.contains("\"code\":-1121")) {
                    // -1121: Invalid symbol
                    log.warn("Skipping invalid symbol {}", sym);
                    continue;
                }
                log.error("Trade ingest failed for {}: {}", sym, msg, ex);
            }
        }

        return total;
    }

    private List<String> loadPairsFromClasspath(String path) {
        Resource res = new ClassPathResource(path);
        if (!res.exists()) {
            log.warn("Pairs file not found on classpath: {}", path);
            return List.of();
        }

        List<String> lines = new ArrayList<>();
        try (var is = res.getInputStream();
             var rd = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = rd.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("#") || trimmed.startsWith("//")) continue; // allow comments
                lines.add(trimmed);
            }
        } catch (Exception e) {
            log.error("Failed to read pairs file {}: {}", path, e.getMessage(), e);
            return List.of();
        }
        return lines;
    }
}

