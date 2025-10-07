package com.sandkev.cryptio.web.kraken;

import lombok.*;
import org.springframework.data.domain.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;


import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;


@Controller
@RequestMapping("/kraken/transactions")
@RequiredArgsConstructor
public class KrakenTransactionController {


    private final KrakenTransactionService service; // implement against your existing ingest layer

    @GetMapping
    public String list(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestParam(value = "sort", defaultValue = "-ts") String sort,
            Filters filters,
            Model model
    ) {
        Pageable pageable = buildPageable(page, size, sort);
        Page<KrakenTxView> p = service.search(filters, pageable);


        Map<String, Object> metrics = new HashMap<>();
        metrics.put("total", p.getTotalElements());
        metrics.put("buys", p.getContent().stream().filter(t -> "buy".equals(t.getType())).count());
        metrics.put("sells", p.getContent().stream().filter(t -> "sell".equals(t.getType())).count());
        metrics.put("reconciled", p.getContent().stream().filter(t -> "reconciled".equals(t.getStatus())).count());


        model.addAttribute("items", p.getContent());
        model.addAttribute("page", p);
        model.addAttribute("filters", filters.withSort(sort));
        model.addAttribute("metrics", metrics);
        return "kraken/transactions"; // templates/kraken/transactions.html
    }


    @PostMapping("/sync")
    public String sync(@RequestParam(value = "since", required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {
        service.startSync(since);
        return "redirect:/kraken/transactions";
    }


    @PostMapping("/import")
    public String importCsv(@RequestParam("file") MultipartFile file) {
        service.importCsv(file);
        return "redirect:/kraken/transactions";
    }


    @PostMapping("/bulk")
    public String bulk(
            @RequestParam(value = "action") String action,
            @RequestParam(value = "ids", required = false) List<String> ids,
            @RequestParam(value = "tag", required = false) String tag
    ) {
        if (ids == null || ids.isEmpty()) return "redirect:/kraken/transactions";
        switch (action) {
            case "reconcile" -> service.reconcile(ids);
            case "delete" -> service.delete(ids);
            case "tag" -> {
                if (tag != null && !tag.isBlank()) service.addTag(ids, tag);
            }
        }
        return "redirect:/kraken/transactions";
    }


    private Pageable buildPageable(int page, int size, String sort) {
        Sort s;
        if (sort == null || sort.isBlank()) sort = "-ts";
        boolean desc = sort.startsWith("-");
        String field = desc ? sort.substring(1) : sort;
        s = Sort.by(desc ? Sort.Direction.DESC : Sort.Direction.ASC, mapSortField(field));
        return PageRequest.of(page, Math.max(1, Math.min(size, 500)), s);
    }


    private String mapSortField(String uiField) {
        return switch (uiField) {
            case "pair" -> "pair";
            case "amount" -> "amount";
            case "ts" -> "ts";
            default -> "ts";
        };
    }


    // ---- DTOs ----
    @Data
    public static class Filters {
        private String q;
        private String pair;
        private String type;
        private String status;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate from;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate to;
        private String sort = "-ts";

        public Filters withSort(String s) {
            this.sort = s;
            return this;
        }
    }


    @Value
    public static class KrakenTxView {
        String id;
        ZonedDateTime ts;
        String pair;
        String base;
        String quote;
        String type; // buy/sell/deposit/withdrawal/staking/fee
        String status; // new/reconciled/pending/error
        BigDecimal price;
        BigDecimal amount;
        BigDecimal total;
        BigDecimal fee;
        List<String> tags;
        String txid;
        String notes;
    }
}