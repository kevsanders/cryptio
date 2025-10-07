package com.sandkev.cryptio.web.kraken;

import org.springframework.data.domain.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.*;
import java.util.*;


public interface KrakenTransactionService {
    Page<KrakenTransactionController.KrakenTxView> search(KrakenTransactionController.Filters filters, Pageable pageable);
    void startSync(LocalDate since);
    void importCsv(MultipartFile file);
    void reconcile(List<String> ids);
    void delete(List<String> ids);
    void addTag(List<String> ids, String tag);
}