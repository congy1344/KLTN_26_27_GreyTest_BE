package com.example.HMS.enums;

public enum AppointmentStatus {

    APPOINTMENT_SCHEDULED("Appointment scheduled"),
    APPOINTMENT_UPDATED("Appointment updated"),
    APPOINTMENT_COMPLETED("Appointment completed"),
    APPOINTMENT_CANCELLED("Appointment cancelled"),
    APPOINTMENT_RESCHEDULED("Appointment rescheduled"),
    APPOINTMENT_NO_SHOW("Patient did not show up");

    private final String description;

    AppointmentStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
