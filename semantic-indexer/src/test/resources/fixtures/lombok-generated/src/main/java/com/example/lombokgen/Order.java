package com.example.lombokgen;

import lombok.Data;

@Data
public class Order {

    private long id;

    private double total;

    public String getManualNote() {
        return "manual note";
    }
}
