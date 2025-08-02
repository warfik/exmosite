package com.crypto.service;

import com.crypto.model.BalanceHistoryPoint;
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

    /**
     * Загружает все сделки пользователя со всех валютных пар через API EXMO.
     * @return Список сделок.
     */
    private List<Trade> fetchAllUserTradesFromApi() {
        List<Trade> allApiTrades = new ArrayList<>();
        try {
            // 1. Получаем все доступные валютные пары
            String pairSettingsJson = exmoApiClient.post("pair_settings", new HashMap<>());
            Map<String, Object> pairSettings = objectMapper.readValue(pairSettingsJson, new TypeReference<>() {});

            // 2. Формируем запрос для получения сделок по всем парам
            String pairsToRequest = String.join(",", pairSettings.keySet());
            Map<String, String> params = new HashMap<>();
            params.put("pair", pairsToRequest);
            params.put("limit", "1000"); // Загружаем до 1000 последних сделок для каждой пары

            // 3. Выполняем запрос к API
            String userTradesJson = exmoApiClient.post("user_trades", params);
            Map<String, List<Trade>> tradesByPair = objectMapper.readValue(userTradesJson, new TypeReference<>() {});

            // 4. Собираем все сделки из разных пар в один список
            tradesByPair.values().forEach(allApiTrades::addAll);
            System.out.println("Загружено " + allApiTrades.size() + " новых сделок из API.");

        } catch (Exception e) {
            System.err.println("Не удалось загрузить сделки пользователя с EXMO API: " + e.getMessage());
            // В случае ошибки возвращаем пустой список, чтобы приложение продолжило работу с локальной историей
        }
        return allApiTrades;
    }

    /**
     * Основной метод для сбора всех данных для панели управления.
     * @return Карта с данными для фронтенда.
     */
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

            Set<String> allAssetNames = new HashSet<>();
            allAssetNames.addAll(balances.keySet());
            allAssetNames.addAll(reserved.keySet());

            // 2. Получение текущих рыночных цен
            Map<String, Double> marketPrices = getMarketPrices(allAssetNames);

            // 3. Расчет текущей стоимости портфеля
            double currentMarketValue = 0.0;
            for (String asset : allAssetNames) {
                double totalAssetAmount = Double.parseDouble(balances.optString(asset, "0")) + Double.parseDouble(reserved.optString(asset, "0"));
                if (totalAssetAmount > 0) {
                    if (asset.equalsIgnoreCase("USDT") || asset.equalsIgnoreCase("USDC") || asset.equalsIgnoreCase("USD")) {
                        currentMarketValue += totalAssetAmount;
                    } else {
                        double price = marketPrices.getOrDefault(asset.toUpperCase() + "_USDT", 0.0);
                        currentMarketValue += totalAssetAmount * price;
                    }
                }
            }
            result.put("totalUsd", currentMarketValue);
            result.put("totalBtc", currentMarketValue > 0 && marketPrices.getOrDefault("BTC_USDT", 0.0) > 0
                    ? currentMarketValue / marketPrices.get("BTC_USDT") : 0);

            // 4. Загрузка последних сделок из API и их сохранение в локальный лог
            List<Trade> newApiTrades = fetchAllUserTradesFromApi();
            if (!newApiTrades.isEmpty()) {
                tradeHistoryService.logTrades(newApiTrades);
            }

            // 5. Чтение полной, обновленной истории сделок из локального лога
            List<Trade> allTrades = tradeHistoryService.readAllTrades();

            // 6. Расчет прибыли/убытка за последние 24 часа (PnL 24h)
            double balance24hAgo = balanceHistoryService.getBalance24HoursAgo()
                    .map(BalanceHistoryPoint::getBalance)
                    .orElse(currentMarketValue); // Если истории нет, считаем, что изменений не было

            double pnl24hValue = currentMarketValue - balance24hAgo;
            double pnl24hPercentage = (balance24hAgo > 0 && balance24hAgo != currentMarketValue) ? (pnl24hValue / balance24hAgo) * 100 : 0;
            result.put("pnl24h", Map.of("value", pnl24hValue, "percentage", pnl24hPercentage));

            // 7. Фильтрация сделок за последние 24 часа для таблицы транзакций
            long twentyFourHoursAgo = Instant.now().minus(24, ChronoUnit.HOURS).getEpochSecond();
            List<Trade> recentTrades = allTrades.stream()
                    .filter(t -> t.getDate() >= twentyFourHoursAgo)
                    .sorted(Comparator.comparing(Trade::getDate).reversed())
                    .collect(Collectors.toList());
            result.put("transactions", recentTrades);

            // 8. Подсчет количества сделок "buy" и "sell" за последние 24 часа
            long buys24h = recentTrades.stream().filter(t -> "buy".equalsIgnoreCase(t.getType())).count();
            long sells24h = recentTrades.stream().filter(t -> "sell".equalsIgnoreCase(t.getType())).count();
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
                prices.put(pair, Double.parseDouble(tickerInfo.getJSONObject(pair).optString("last_trade", "0")));
            }
        }
        if (!prices.containsKey("BTC_USDT") && tickerInfo.has("BTC_USDT")) {
            prices.put("BTC_USDT", Double.parseDouble(tickerInfo.getJSONObject("BTC_USDT").optString("last_trade", "0")));
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
