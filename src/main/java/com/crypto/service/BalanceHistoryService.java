package com.crypto.service;

import com.crypto.model.BalanceHistoryPoint;
import org.springframework.scheduling.annotation.Scheduled;
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
import java.util.*;
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
            // Используем Locale.US для точки в качестве десятичного разделителя
            String line = Instant.now().toEpochMilli() + "," + String.format(java.util.Locale.US, "%.2f", totalUsd) + System.lineSeparator();
            Files.writeString(HISTORY_FILE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("Баланс " + totalUsd + " USD сохранен в историю.");
        } catch (IOException e) {
            System.err.println("Ошибка сохранения истории баланса: " + e.getMessage());
        }
    }

    /**
     * Возвращает историю баланса за последние 24 часа, агрегированную по часам.
     * @return Список точек для графика.
     */
    public List<BalanceHistoryPoint> getHourlyBalanceHistoryForLast24h() {
        if (!Files.exists(HISTORY_FILE)) {
            return Collections.emptyList();
        }
        long twentyFourHoursAgo = Instant.now().minus(24, ChronoUnit.HOURS).toEpochMilli();

        try (Stream<String> stream = Files.lines(HISTORY_FILE)) {
            Map<LocalDateTime, BalanceHistoryPoint> lastPointPerHour = stream
                    .map(this::parseLine)
                    .filter(point -> point != null && point.getTimestamp() >= twentyFourHoursAgo)
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

    /**
     * Находит последнее значение баланса, записанное ПЕРЕД или В момент "24 часа назад".
     * @return Optional с точкой баланса или empty, если история недоступна.
     */
    public Optional<BalanceHistoryPoint> getBalance24HoursAgo() {
        if (!Files.exists(HISTORY_FILE)) {
            return Optional.empty();
        }
        long targetTimestamp = Instant.now().minus(24, ChronoUnit.HOURS).toEpochMilli();

        try (Stream<String> stream = Files.lines(HISTORY_FILE)) {
            return stream
                    .map(this::parseLine)
                    .filter(point -> point != null && point.getTimestamp() <= targetTimestamp)
                    .max(BalanceHistoryPoint::compareTo); // Находим последнюю точку до целевого времени
        } catch (IOException e) {
            System.err.println("Ошибка чтения истории баланса для PnL: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * НОВЫЙ МЕТОД
     * Находит самую первую (самую старую) запись о балансе в пределах последних 24 часов.
     * Используется как fallback, если приложение работает менее 24 часов.
     * @return Optional с самой первой точкой баланса или empty, если история за этот период пуста.
     */
    public Optional<BalanceHistoryPoint> getEarliestBalanceWithin24h() {
        if (!Files.exists(HISTORY_FILE)) {
            return Optional.empty();
        }
        long twentyFourHoursAgo = Instant.now().minus(24, ChronoUnit.HOURS).toEpochMilli();

        try (Stream<String> stream = Files.lines(HISTORY_FILE)) {
            return stream
                    .map(this::parseLine)
                    .filter(point -> point != null && point.getTimestamp() >= twentyFourHoursAgo)
                    .min(BalanceHistoryPoint::compareTo); // Находим самую раннюю точку (с минимальным timestamp) за последние 24ч
        } catch (IOException e) {
            System.err.println("Ошибка чтения самой ранней записи за 24ч: " + e.getMessage());
            return Optional.empty();
        }
    }


    /**
     * Запускает очистку старых записей в файле истории каждый день в 2 часа ночи.
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupOldHistory() {
        if (!Files.exists(HISTORY_FILE)) {
            return;
        }
        System.out.println("Запуск очистки старой истории баланса...");
        // Храним данные за последние 48 часов для надежности расчетов
        long retentionTimestamp = Instant.now().minus(48, ChronoUnit.HOURS).toEpochMilli();
        try {
            List<String> recentLines = Files.lines(HISTORY_FILE)
                    .filter(line -> {
                        try {
                            return Long.parseLong(line.split(",")[0]) >= retentionTimestamp;
                        } catch (Exception e) {
                            return false; // Игнорируем поврежденные строки
                        }
                    })
                    .collect(Collectors.toList());
            Files.write(HISTORY_FILE, recentLines, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            System.out.println("Очистка истории баланса завершена. Сохранено " + recentLines.size() + " записей.");
        } catch (IOException e) {
            System.err.println("Ошибка во время очистки файла истории баланса: " + e.getMessage());
        }
    }

    /**
     * Вспомогательный метод для парсинга строки из файла истории.
     * @param line Строка из лог-файла.
     * @return Объект BalanceHistoryPoint или null в случае ошибки.
     */
    private BalanceHistoryPoint parseLine(String line) {
        try {
            String[] parts = line.split(",");
            if (parts.length < 2) return null; // Проверка на корректность строки
            long timestamp = Long.parseLong(parts[0]);
            double balance = Double.parseDouble(parts[1]);
            return new BalanceHistoryPoint(timestamp, balance);
        } catch (NumberFormatException e) {
            // Игнорируем поврежденные строки, чтобы не засорять лог
            return null;
        }
    }
}
