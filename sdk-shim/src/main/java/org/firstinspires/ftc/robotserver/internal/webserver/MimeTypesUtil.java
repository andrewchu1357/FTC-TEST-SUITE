package org.firstinspires.ftc.robotserver.internal.webserver;

import java.net.URLConnection;

public class MimeTypesUtil {
    private MimeTypesUtil() {}

    public static String determineMimeType(String path) {
        String type = URLConnection.guessContentTypeFromName(path);
        return type != null ? type : "application/octet-stream";
    }
}
