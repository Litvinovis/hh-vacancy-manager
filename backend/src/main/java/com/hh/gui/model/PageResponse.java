package com.hh.gui.model;

import java.util.List;
import java.util.Map;

public class PageResponse<T> {
    private int total;
    private int page;
    private int perPage;
    private List<T> items;

    public PageResponse() {}

    public PageResponse(int total, int page, int perPage, List<T> items) {
        this.total = total;
        this.page = page;
        this.perPage = perPage;
        this.items = items;
    }

    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getPerPage() { return perPage; }
    public void setPerPage(int perPage) { this.perPage = perPage; }

    public List<T> getItems() { return items; }
    public void setItems(List<T> items) { this.items = items; }
}
