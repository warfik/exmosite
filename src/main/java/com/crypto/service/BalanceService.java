package com.crypto.service;

import com.crypto.model.Trade;
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
            JSONObject reserved = userInfo.getJSONObject("reserved"); // Получаем зарезервированные средства

            // Объединяем все активы (доступные и зарезервированные)
            Map<String, Double> allAssetsCombined = new HashMap<>();
            for (String asset : balances.keySet()) {
                double available = Double.parseDouble(balances.optString(asset, "0"));
                allAssetsCombined.put(asset, allAssetsCombined.getOrDefault(asset, 0.0) + available);
            }
            for (String asset : reserved.keySet()) {
                double inOrders = Double.parseDouble(reserved.optString(asset, "0"));
                allAssetsCombined.put(asset, allAssetsCombined.getOrDefault(asset, 0.0) + inOrders);
            }

            // 2. Получение текущих рыночных цен для всех активов
            Map<String, Double> marketPrices = getMarketPrices(allAssetsCombined.keySet());

            // 3. Расчет текущей стоимости портфеля (Current Market Value)
            double currentMarketValue = 0.0;
            for (Map.Entry<String, Double> entry : allAssetsCombined.entrySet()) {
                String asset = entry.getKey();
                double totalAmount = entry.getValue();

                if (totalAmount > 0) {
                    double price = marketPrices.getOrDefault(asset.toUpperCase() + "_USDT", 0.0);
                    if (asset.equalsIgnoreCase("USDT") || asset.equalsIgnoreCase("USDC")) {
                        currentMarketValue += totalAmount; // Для стейблкоинов просто добавляем сумму
                    } else {
                        currentMarketValue += totalAmount * price; // Для других активов умножаем на цену
                    }
                }
            }
            result.put("totalUsd", currentMarketValue);
            result.put("totalBtc", currentMarketValue > 0 && marketPrices.getOrDefault("BTC_USDT", 0.0) > 0
                    ? currentMarketValue / marketPrices.get("BTC_USDT")
                    : 0);

            // 4. Получение всех сделок пользователя для расчета инвестиций и PnL
            List<Trade> allTrades = tradeHistoryService.readAllTrades();

            // 5. Расчет начальных инвестиций
            // Пересчитываем initialInvestment, чтобы он учитывал только реальные "покупки" в USD
            // Для этого фильтруем только те покупки, где пара заканчивается на _USDT
            double initialInvestment = allTrades.stream()
                    .filter(trade -> "buy".equalsIgnoreCase(trade.getType()))
                    .filter(trade -> trade.getPair().toUpperCase().endsWith("_USDT")) // Добавляем фильтр по паре
                    .mapToDouble(Trade::getAmount) // 'amount' уже в USD для пар типа XXX_USDT
                    .sum();
            result.put("initialInvestment", initialInvestment);

            // 6. Расчет прибыли/убытка (Profit/Loss)
            double pnlValue = currentMarketValue - initialInvestment;
            double pnlPercentage = (initialInvestment > 0) ? (pnlValue / initialInvestment) * 100 : 0;
            result.put("pnl", Map.of("value", pnlValue, "percentage", pnlPercentage));

            // 7. Получение последних сделок для отображения в таблице (можно использовать API)
            // Фильтруем сделки за последние 24 часа
            long twentyFourHoursAgo = Instant.now().minus(24, ChronoUnit.HOURS).getEpochSecond();
            List<Trade> recentTrades = allTrades.stream()
                    .filter(t -> t.getDate() > twentyFourHoursAgo)
                    .sorted(Comparator.comparing(Trade::getDate).reversed()) // Сортируем по дате убывания
                    .collect(Collectors.toList());

            tradeHistoryService.logTrades(allTrades); // Логируем все сделки, если были новые
            result.put("transactions", recentTrades); // Теперь отправляем только сделки за последние 24 часа


            // 8. Расчет количества сделок за последние 24 часа
            long buys24h = recentTrades.stream() // Используем уже отфильтрованный список
                    .filter(t -> t.getType().equalsIgnoreCase("buy"))
                    .count();
            long sells24h = recentTrades.stream() // Используем уже отфильтрованный список
                    .filter(t -> t.getType().equalsIgnoreCase("sell"))
                    .count();
            result.put("trades", Map.of("buys", buys24h, "sells", sells24h));


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
