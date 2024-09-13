package com.chinonso.wearos;

public class DataPoint {
    public String type;
    public Object value;
    public long timestamp;

    public DataPoint(String type, Object value, long timestamp) {
        this.type = type;
        this.value = value;
        this.timestamp = timestamp;
    }
}