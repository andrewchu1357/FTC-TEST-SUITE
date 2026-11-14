package com.qualcomm.robotcore.util;

import org.firstinspires.ftc.robotcore.internal.webserver.WebHandler;
import org.firstinspires.ftc.robotcore.internal.webserver.WebObserver;

/** The Robot Controller's web server routes. There is no Robot Controller web server in the simulator. */
public interface WebHandlerManager {
    WebServer getWebServer();

    void register(String command, WebHandler webHandler);

    WebHandler getRegistered(String command);

    void registerObserver(String key, WebObserver webObserver);
}
