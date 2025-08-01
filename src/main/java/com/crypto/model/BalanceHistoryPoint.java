package com.crypto.model;

import lombok.Value;

@Value
public class BalanceHistoryPoint implements Comparable<BalanceHistoryPoint> {
    long timestamp;
    double balance;

    @Override
    public int compareTo(BalanceHistoryPoint other) {
        return Long.compare(this.timestamp, other.timestamp);
    }
}