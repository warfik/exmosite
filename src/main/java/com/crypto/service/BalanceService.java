package com.crypto.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.json.JSONObject;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import com.crypto.model.Trade;

@Service
@RequiredArgsConstructor
public class BalanceService {

    private final ExmoApiClient exmoApiClient;
    private final TradeHistoryService tradeHistoryService;
    private final BalanceHistoryService balanceHistoryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Метод для получения основных данных: баланс, сделки, PnL
    public Map<String, Object> fetchBalanceAndTrades() {
        Map<String, Object> result = new HashMap<>();
        try {
            // 1. Получение балансов пользователя
            String userInfoJson = exmoApiClient.post("user_info", new HashMap<>());
            JSONObject userInfo = new JSONObject(userInfoJson);
            if (userInfo.has("error") && !userInfo.getString("error").isEmpty()) {
                throw new RuntimeException("EXMO API Error: " + userInfo.getString("error"));
            }

            JSONObject balances = userInfo.getJSONObject("balances");
            JSONObject reserved = userInfo.getJSONObject("reserved");

            // Объединяем все активы (доступные и зарезервированные)
            Set<String> allAssetNames = new HashSet<>();
            allAssetNames.addAll(balances.keySet());
            allAssetNames.addAll(reserved.keySet());

            // 2. Получение текущих рыночных цен для всех активов
            Map<String, Double> marketPrices = getMarketPrices(allAssetNames);

            // 3. Расчет текущей стоимости портфеля (Current Market Value)
            double currentMarketValue = 0.0;
            for (String asset : allAssetNames) {
                double available = Double.parseDouble(balances.optString(asset, "0"));
                double inOrders = Double.parseDouble(reserved.optString(asset, "0"));
                double totalAssetAmount = available + inOrders;

                if (totalAssetAmount > 0) {
                    double price = marketPrices.getOrDefault(asset.toUpperCase() + "_USDT", 0.0);
                    if (asset.equalsIgnoreCase("USDT") || asset.equalsIgnoreCase("USDC")) {
                        currentMarketValue += totalAssetAmount;
                    } else {
                        currentMarketValue += totalAssetAmount * price;
                    }
                }
            }
            result.put("totalUsd", currentMarketValue);
            result.put("totalBtc", currentMarketValue > 0 && marketPrices.getOrDefault("BTC_USDT", 0.0) > 0
                    ? currentMarketValue / marketPrices.get("BTC_USDT")
                    : 0);

            // 4. Получение всех сделок пользователя для расчета инвестиций и PnL
            List<Trade> allTrades = tradeHistoryService.readAllTrades();

            // 5. Расчет начальных инвестиций (общего PnL)
            // ВНИМАНИЕ: Если "Общая прибыль/убыток" показывает большие расхождения,
            // проверьте реализацию tradeHistoryService.calculateInitialInvestment().
            // Этот метод должен корректно вычислять чистый вложенный капитал,
            // учитывая все покупки, продажи, депозиты и выводы за всю историю.
            double initialInvestment = tradeHistoryService.calculateInitialInvestment(allTrades);
            result.put("initialInvestment", initialInvestment);

            // 6. Расчет общей прибыли/убытка (Profit/Loss)
            double pnlValue = currentMarketValue - initialInvestment;
            double pnlPercentage = (initialInvestment > 0) ? (pnlValue / initialInvestment) * 100 : 0;
            result.put("pnl", Map.of("value", pnlValue, "percentage", pnlPercentage));

            // 7. Получение последних сделок для отображения в таблице
            // Фильтруем сделки за последние 24 часа
            long twentyFourHoursAgo = Instant.now().minus(24, ChronoUnit.HOURS).getEpochSecond();
            List<Trade> recentTrades = allTrades.stream()
                    .filter(t -> t.getDate() > twentyFourHoursAgo)
                    .sorted(Comparator.comparing(Trade::getDate).reversed())
                    .collect(Collectors.toList());

            tradeHistoryService.logTrades(allTrades);
            result.put("transactions", recentTrades);

            // 8. Расчет количества сделок за последние 24 часа
            long buys24h = recentTrades.stream()
                    .filter(t -> t.getType().equalsIgnoreCase("buy"))
                    .count();
            long sells24h = recentTrades.stream()
                    .filter(t -> t.getType().equalsIgnoreCase("sell"))
                    .count();
            result.put("trades", Map.of("buys", buys24h, "sells", sells24h));

            // 9. Расчет прибыли/убытка за последние 24 часа (PnL 24h)
            double pnl24hValue = 0.0;
            double totalBuyCost24h = 0.0;
            double totalSellRevenue24h = 0.0;

            for (Trade trade : recentTrades) {
                // Предполагаем, что price в Trade уже указана в USDT или базовой валюте пары
                // и что amount - это количество базовой валюты
                double tradeValueInUsdt = trade.getAmount() * trade.getPrice();

                if (trade.getType().equalsIgnoreCase("buy")) {
                    totalBuyCost24h += tradeValueInUsdt;
                } else if (trade.getType().equalsIgnoreCase("sell")) {
                    totalSellRevenue24h += tradeValueInUsdt;
                }
            }
            pnl24hValue = totalSellRevenue24h - totalBuyCost24h; // Реализованный PnL за 24 часа

            double pnl24hPercentage = (totalBuyCost24h > 0) ? (pnl24hValue / totalBuyCost24h) * 100 : 0;
            result.put("pnl24h", Map.of("value", pnl24hValue, "percentage", pnl24hPercentage));


        } catch (Exception e) {
            System.err.println("Ошибка в BalanceService: " + e.getMessage());
            e.printStackTrace();
            result.put("error", "Ошибка сервиса: " + e.getMessage());
        }
        return result;
    }

    // Вспомогательный метод для получения цен всех активов
    private Map<String, Double> getMarketPrices(Set<String> assets) throws Exception {
        Map<String, Double> prices = new HashMap<>();
        String tickerJson = exmoApiClient.post("ticker", new HashMap<>());
        JSONObject tickerInfo = new JSONObject(tickerJson);

        for (String asset : assets) {
            String pair = asset.toUpperCase() + "_USDT";
            if (tickerInfo.has(pair)) {
                JSONObject pairTicker = tickerInfo.getJSONObject(pair);
                prices.put(pair, Double.parseDouble(pairTicker.optString("last_trade", "0")));
            }
        }
        // Убедимся, что цена BTC есть всегда, если он есть в портфеле или для конвертации
        if (!prices.containsKey("BTC_USDT") && tickerInfo.has("BTC_USDT")) {
            JSONObject pairTicker = tickerInfo.getJSONObject("BTC_USDT");
            prices.put("BTC_USDT", Double.parseDouble(pairTicker.optString("last_trade", "0")));
        }
        return prices;
    }

    // Фоновая задача для сохранения истории баланса каждый час
    @Scheduled(cron = "0 0 * * * ?") // Запускается в начале каждого часа
    public void recordHourlyBalance() {
        System.out.println("Сохранение часового среза баланса...");
        try {
            Map<String, Object> data = fetchBalanceAndTrades();
            if (!data.containsKey("error")) {
                double currentBalance = (double) data.get("totalUsd");
                balanceHistoryService.saveBalance(currentBalance);
            }
        } catch (Exception e) {
            System.err.println("Не удалось сохранить часовой баланс: " + e.getMessage());
        }
    }
}
