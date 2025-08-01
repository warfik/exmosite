package com.crypto.controller;

import com.crypto.model.BalanceHistoryPoint;
import com.crypto.service.BalanceHistoryService;
import com.crypto.service.BalanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BalanceController {

    private final BalanceService balanceService;
    private final BalanceHistoryService balanceHistoryService;

    @GetMapping("/balance")
    public ResponseEntity<Map<String, Object>> getBalance() {
        Map<String, Object> data = balanceService.fetchBalanceAndTrades();
        if (data.containsKey("error")) {
            return ResponseEntity.status(500).body(data);
        }
        return ResponseEntity.ok(data);
    }

    @GetMapping("/balance/history")
    public ResponseEntity<List<BalanceHistoryPoint>> getBalanceHistory() {
        List<BalanceHistoryPoint> history = balanceHistoryService.getHourlyBalanceHistory();
        return ResponseEntity.ok(history);
    }
}
