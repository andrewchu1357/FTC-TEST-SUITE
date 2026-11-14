package org.ftctestbed.sim;

import com.acmerobotics.dashboard.FtcDashboard;
import org.ftctestbed.sim.code.HardwareScanner;
import org.ftctestbed.sim.code.RobotConfigGenerator;
import org.ftctestbed.sim.code.SourceFinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Team code written for FTC Dashboard compiles and runs, and its packets reach the simulator. */
class DashboardStubTest {
    @Test
    void dashboardCodeRuns(@TempDir Path dir) throws Exception {
        Path pkg = RobotCodeTest.makePackage(dir, RobotCodeTest.DRIVE_FORWARD);
        Path src = pkg.resolve("code/TeamCode/src/main/java/org/firstinspires/ftc/teamcode");
        Files.writeString(src.resolve("DashTest.java"), """
                package org.firstinspires.ftc.teamcode;

                import com.acmerobotics.dashboard.FtcDashboard;
                import com.acmerobotics.dashboard.config.Config;
                import com.acmerobotics.dashboard.telemetry.MultipleTelemetry;
                import com.acmerobotics.dashboard.telemetry.TelemetryPacket;
                import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
                import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

                @Config
                @TeleOp(name = "Dash Test")
                public class DashTest extends LinearOpMode {
                    public static double TARGET = 42;

                    @Override
                    public void runOpMode() {
                        FtcDashboard dashboard = FtcDashboard.getInstance();
                        telemetry = new MultipleTelemetry(telemetry, dashboard.getTelemetry());
                        waitForStart();
                        while (opModeIsActive()) {
                            TelemetryPacket packet = new TelemetryPacket();
                            packet.put("target", TARGET);
                            packet.fieldOverlay().setStroke("#3F51B5").strokeCircle(0, 0, 9);
                            dashboard.sendTelemetryPacket(packet);
                            telemetry.addData("loop", "running");
                            telemetry.update();
                            sleep(20);
                        }
                    }
                }
                """);
        Path robotFile = pkg.resolve("robot.jsonc");
        Files.writeString(robotFile, RobotConfigGenerator.generate("Demo", HardwareScanner.scan(SourceFinder.roots(pkg.resolve("code")))));
        try (SimHarness sim = SimHarness.load(robotFile, RobotCodeTest.FIELD)) {
            sim.init("Dash Test").start().runFor(0.5);
            assertNull(sim.error());
            assertTrue(sim.telemetry().contains("loop : running"), sim.telemetry().toString());
            var packet = FtcDashboard.getInstance().simLastPacket();
            assertNotNull(packet);
            boolean fromLoop = "42.0".equals(packet.simData().get("target")) || "running".equals(packet.simData().get("loop"));
            assertTrue(fromLoop, packet.simData().toString());
        }
    }
}
