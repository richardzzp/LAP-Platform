package org.jetlinks.community.device.entity;

import org.jetlinks.community.device.entity.GraphData;

import java.util.List;

public class GraphTotal {
    List<GraphRow> airTemperature;
    List<GraphRow> wetBulbTemperature;
    List<GraphRow> solarRadiation;

    public GraphTotal(List<GraphRow> airTemperature, List<GraphRow> wetBulbTemperature, List<GraphRow> solarRadiation) {
        this.airTemperature = airTemperature;
        this.wetBulbTemperature = wetBulbTemperature;
        this.solarRadiation = solarRadiation;
    }

    public List<GraphRow> getAirTemperature() {
        return airTemperature;
    }

    public void setAirTemperature(List<GraphRow> airTemperature) {
        this.airTemperature = airTemperature;
    }

    public List<GraphRow> getWetBulbTemperature() {
        return wetBulbTemperature;
    }

    public void setWetBulbTemperature(List<GraphRow> wetBulbTemperature) {
        this.wetBulbTemperature = wetBulbTemperature;
    }

    public List<GraphRow> getSolarRadiation() {
        return solarRadiation;
    }

    public void setSolarRadiation(List<GraphRow> solarRadiation) {
        this.solarRadiation = solarRadiation;
    }
}
