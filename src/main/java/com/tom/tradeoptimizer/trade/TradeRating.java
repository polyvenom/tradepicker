package com.tom.tradeoptimizer.trade;

public enum TradeRating {
    GREAT, GOOD, FAIR, BAD, UNKNOWN;

    public int color() {
        return switch (this) {
            case GREAT -> 0xFF55FF55;
            case GOOD  -> 0xFFAAFF66;
            case FAIR  -> 0xFFFFFF55;
            case BAD   -> 0xFFFF5555;
            case UNKNOWN -> 0xFFAAAAAA;
        };
    }

    public String label() {
        return switch (this) {
            case GREAT -> "Great";
            case GOOD  -> "Good";
            case FAIR  -> "Fair";
            case BAD   -> "Bad";
            case UNKNOWN -> "?";
        };
    }
}
