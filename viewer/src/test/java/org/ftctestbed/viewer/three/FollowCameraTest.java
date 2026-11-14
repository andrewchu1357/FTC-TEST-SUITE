package org.ftctestbed.viewer.three;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Follow robot keeps the robot in the picture whatever the mouse does. */
class FollowCameraTest {
    double t, x, y, h;
    OrbitCamera cam;

    /** The robot drives a wide loop; the camera gets a frame every 1/60 s. */
    void drive(double seconds) {
        for (int i = 0; i < Math.round(seconds * 60); i++) {
            t += 1 / 60.0;
            h = 0.7 * t;
            x = -0.8 + 0.9 * Math.cos(0.7 * t - Math.PI / 2);
            y = 0.9 * Math.sin(0.7 * t - Math.PI / 2);
            cam.track(x, y, h);
        }
    }

    /** The angle (deg) between where the camera looks and where the robot is. */
    double offCenter() {
        double[] e = cam.eye(), f = cam.forward();
        double dx = x - e[0], dy = y - e[1], dz = 0.23 - e[2], n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Math.toDegrees(Math.acos((dx * f[0] + dy * f[1] + dz * f[2]) / n));
    }

    void assertInPicture(String after) {
        assertTrue(cam.following(), "still following after " + after);
        double half = cam.fieldOfView() / 2;
        assertTrue(offCenter() < 0.8 * half, "robot in the picture after " + after + ": " + offCenter() + " deg off center");
    }

    @Test
    void theCameraStaysOnTheRobotWhateverTheMouseDoes() {
        cam = new OrbitCamera();
        cam.preset(OrbitCamera.Preset.FOLLOW);
        drive(2);
        assertInPicture("starting");

        cam.zoom(-3000); // all the way out
        assertInPicture("zooming out");
        drive(2);
        assertInPicture("driving, zoomed out");

        cam.pan(4000, -3000); // drag the view a long way
        assertInPicture("panning far");
        drive(2);
        assertInPicture("driving after panning");

        cam.rotate(300, -120); // swing around the robot and look from higher up
        assertInPicture("rotating");
        drive(3);
        assertInPicture("driving after rotating");

        cam.zoom(2500); // back in close
        assertInPicture("zooming in");
        drive(2);
        assertInPicture("driving, zoomed in");

        cam.preset(OrbitCamera.Preset.RED_DRIVER);
        assertFalse(cam.following(), "choosing another camera stops following");
    }
}
