package com.crypto.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties; // Импортируем аннотацию
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

@Value // Lombok аннотация для создания неизменяемого класса
@JsonIgnoreProperties(ignoreUnknown = true) // Игнорировать неизвестные поля в JSON
public class Trade {
    long tradeId;
    String type;
    double price;
    double quantity;
    double amount;
    long date;
    String pair;

    @JsonCreator
    public Trade(
            @JsonProperty("trade_id") long tradeId,
            @JsonProperty("type") String type,
            @JsonProperty("price") String price,
            @JsonProperty("quantity") String quantity,
            @JsonProperty("amount") String amount,
            @JsonProperty("date") long date,
            @JsonProperty("pair") String pair) {
        this.tradeId = tradeId;
        this.type = type;
        this.price = Double.parseDouble(price);
        this.quantity = Double.parseDouble(quantity);
        this.amount = Double.parseDouble(amount);
        this.date = date;
        this.pair = pair;
    }
}
