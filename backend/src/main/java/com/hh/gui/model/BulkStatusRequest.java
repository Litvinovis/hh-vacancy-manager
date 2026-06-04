package com.hh.gui.model;

import java.util.List;

public class BulkStatusRequest {
    private List<Long> ids;
    private String status;

    public List<Long> getIds() { return ids; }
    public void setIds(List<Long> ids) { this.ids = ids; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
