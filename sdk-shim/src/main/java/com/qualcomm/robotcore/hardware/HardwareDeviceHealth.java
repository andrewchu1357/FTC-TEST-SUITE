package com.qualcomm.robotcore.hardware;

public interface HardwareDeviceHealth {
    enum HealthStatus { UNKNOWN, HEALTHY, UNHEALTHY, CLOSED }

    void setHealthStatus(HealthStatus status);

    HealthStatus getHealthStatus();
}
