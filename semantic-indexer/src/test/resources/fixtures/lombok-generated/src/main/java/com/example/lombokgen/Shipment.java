package com.example.lombokgen;

import lombok.Builder;

@Builder
public class Shipment {

    private final String carrier;

    private final String trackingId;
}
