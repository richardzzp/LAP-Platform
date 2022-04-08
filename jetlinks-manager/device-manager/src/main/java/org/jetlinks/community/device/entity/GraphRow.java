package org.jetlinks.community.device.entity;

import java.util.List;

public class GraphRow {
    public String group="sameDay";
    public GraphData data;

    public GraphRow(GraphData data) {
        this.data = data;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public GraphData getData() {
        return data;
    }

    public void setData(GraphData data) {
        this.data = data;
    }
}
