package com.example.lombokgen;

public class OrderFlow {

    public double readTotal(Order order) {
        return order.getTotal();
    }

    public void writeTotal(Order order, double total) {
        order.setTotal(total);
    }

    public String readManualNote(Order order) {
        return order.getManualNote();
    }

    public String readCustomerName(Customer customer) {
        return customer.getName();
    }

    public Shipment buildShipment(String carrier) {
        return Shipment.builder().carrier(carrier).build();
    }

    public Invoice createBlankInvoice() {
        return new Invoice();
    }

    public Invoice createFullInvoice(String number, double amount) {
        return new Invoice(number, amount);
    }
}
