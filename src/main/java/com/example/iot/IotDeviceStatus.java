package com.example.iot;

import java.util.Random;

public class IotDeviceStatus {
    private static final Random random = new Random();
    private int DeviceTemperature;
    private int DeviceHumidity;

    private enum Status {
        ONLINE("online"),
        OFFLINE("offline"),
        MAINTENANCE("maintenance"),
        WARNING("warning");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    private Status DeviceStatus;

    public IotDeviceStatus() {
        // initialize this instance's fields (do NOT create a new IotDeviceStatus here)
        this.DeviceTemperature = setRandomTemperature();
        this.DeviceHumidity = setRandomHumidity();
        if (this.DeviceTemperature > 30 || this.DeviceHumidity > 70) {
            this.DeviceStatus = Status.WARNING;
        } else {
            this.DeviceStatus = Status.ONLINE;
        }
    }

    public int getDeviceTemperature() {
        return DeviceTemperature;
    }

    public int getDeviceHumidity() {
        return DeviceHumidity;
    }

    public String getDeviceStatus() {
        return DeviceStatus.toString();
    }

    private static double getRandomValue(double mean, double stdDev) {
        return mean + stdDev * random.nextGaussian();
    }

    private static int setRandomTemperature() {
        return (int) Math.round(getRandomValue(25.0, 5.0));
    }

    private static int setRandomHumidity() {
        return (int) Math.round(getRandomValue(50.0, 10.0));
    }

    public static void main(String[] args) {
        IotDeviceStatus deviceStatus = new IotDeviceStatus();
        System.out.println("Temperature: " + deviceStatus.getDeviceTemperature());
        System.out.println("Humidity: " + deviceStatus.getDeviceHumidity());
        System.out.println("Status: " + deviceStatus.getDeviceStatus());
    }
}
