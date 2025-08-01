package com.crypto.service;

import com.crypto.model.BalanceHistoryPoint;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class BalanceHistoryService {

    private static final Path HISTORY_FILE = Paths.get("balance_history.log");

    /**
     * Сохраняет текущее значение баланса с меткой времени.
     * @param totalUsd Текущий баланс в USD.
     */
    public void saveBalance(double totalUsd) {
        try {
            String line = Instant.now().toEpochMilli() + "," + String.format("%.2f", totalUsd) + System.lineSeparator();
            Files.writeString(HISTORY_FILE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("Баланс " + totalUsd + " USD сохранен в историю.");
        } catch (IOException e) {
            System.err.println("Ошибка сохранения истории баланса: " + e.getMessage());
        }
    }

    /**
     * Возвращает историю баланса, агрегированную по часам.
     * Для каждого часа берется последнее доступное значение.
     * @return Список точек для графика.
     */
    public List<BalanceHistoryPoint> getHourlyBalanceHistory() {
        if (!Files.exists(HISTORY_FILE)) {
            return Collections.emptyList();
        }

        try (Stream<String> stream = Files.lines(HISTORY_FILE)) {
            Map<LocalDateTime, BalanceHistoryPoint> lastPointPerHour = stream
                    .map(line -> {
                        try {
                            String[] parts = line.split(",");
                            long timestamp = Long.parseLong(parts[0]);
                            double balance = Double.parseDouble(parts[1]);
                            return new BalanceHistoryPoint(timestamp, balance);
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(point -> point != null)
                    .collect(Collectors.toMap(
                            point -> LocalDateTime.ofInstant(Instant.ofEpochMilli(point.getTimestamp()), ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS),
                            point -> point,
                            (existing, replacement) -> replacement // Берем последнее значение для каждого часа
                    ));

            return lastPointPerHour.values().stream()
                    .sorted(BalanceHistoryPoint::compareTo)
                    .collect(Collectors.toList());

        } catch (IOException e) {
            System.err.println("Ошибка чтения истории баланса: " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
