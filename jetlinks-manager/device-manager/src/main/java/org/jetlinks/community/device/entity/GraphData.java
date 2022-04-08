package org.jetlinks.community.device.entity;

public class GraphData {
    public double value;
    public String timeString ;
    int timestamp;

    public GraphData(double value, String timeString, int timestamp) {
        this.value = value;
        this.timeString = timeString;
        this.timestamp = timestamp;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public String getTimeString() {
        return timeString;
    }

    public void setTimeString(String timeString) {
        this.timeString = timeString;
    }

    public int getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(int timestamp) {
        this.timestamp = timestamp;
    }
}
