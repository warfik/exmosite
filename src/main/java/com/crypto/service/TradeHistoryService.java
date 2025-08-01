package com.crypto.service;

import com.crypto.model.Trade;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet; // Импортируем HashSet
import java.util.List;
import java.util.Set; // Импортируем Set
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class TradeHistoryService {

    private static final Path HISTORY_FILE = Paths.get("history.log");
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Набор для отслеживания ID сделок, которые уже были записаны, чтобы избежать дубликатов
    private final Set<Long> existingTradeIds = Collections.synchronizedSet(new HashSet<>()); // Используем synchronizedSet для потокобезопасности

    /**
     * Записывает список сделок в файл history.log, добавляя только новые, уникальные сделки.
     * Каждая сделка записывается как JSON в новой строке.
     * @param trades Список сделок для логирования.
     */
    public void logTrades(List<Trade> trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }

        List<String> linesToAppend = new ArrayList<>();
        for (Trade trade : trades) {
            // Добавляем сделку только если ее ID еще не содержится в нашем наборе существующих ID
            if (existingTradeIds.add(trade.getTradeId())) { // add() возвращает true, если элемент был добавлен (т.е. был новым)
                try {
                    linesToAppend.add(objectMapper.writeValueAsString(trade));
                } catch (IOException e) {
                    System.err.println("Ошибка сериализации сделки: " + e.getMessage());
                }
            }
        }

        if (!linesToAppend.isEmpty()) {
            try {
                Files.write(HISTORY_FILE, linesToAppend, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("Ошибка записи в лог истории сделок: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Читает все сделки из файла history.log и обновляет набор существующих ID.
     * @return Список всех сделок.
     */
    public List<Trade> readAllTrades() {
        // Очищаем существующие ID перед чтением, чтобы обеспечить актуальное состояние
        existingTradeIds.clear();

        if (!Files.exists(HISTORY_FILE)) {
            return new ArrayList<>();
        }
        try (Stream<String> stream = Files.lines(HISTORY_FILE)) {
            return stream
                    .map(line -> {
                        try {
                            Trade trade = objectMapper.readValue(line, Trade.class);
                            existingTradeIds.add(trade.getTradeId()); // Добавляем ID в набор
                            return trade;
                        } catch (IOException e) {
                            System.err.println("Ошибка парсинга строки сделки: " + line + " | " + e.getMessage());
                            return null;
                        }
                    })
                    .filter(trade -> trade != null)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            System.err.println("Ошибка чтения из лога истории сделок: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    /**
     * Рассчитывает общую сумму инвестиций на основе всех сделок "buy".
     * @param trades Список всех сделок.
     * @return Общая сумма, потраченная на покупку активов.
     */
    public double calculateInitialInvestment(List<Trade> trades) {
        return trades.stream()
                .filter(trade -> "buy".equalsIgnoreCase(trade.getType()))
                .mapToDouble(Trade::getAmount)
                .sum();
    }
}
