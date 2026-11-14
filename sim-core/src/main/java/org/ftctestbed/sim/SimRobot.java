package org.ftctestbed.sim;

import com.qualcomm.robotcore.hardware.CRServoImplEx;
import com.qualcomm.robotcore.hardware.DcMotorImplEx;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.ServoImplEx;

import org.dyn4j.dynamics.Body;
import org.dyn4j.geometry.Transform;
import org.dyn4j.geometry.Vector2;
import org.ftctestbed.sim.config.ConfigException;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.MotorSpec;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.Contact;
import org.ftctestbed.sim.game.FlightObstacle;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.game.GamePieces;
import org.ftctestbed.sim.game.Retrievable;
import org.ftctestbed.sim.game.RobotPieceHandling;
import org.ftctestbed.sim.game.RobotView;
import org.ftctestbed.sim.hardware.BlinkinColors;
import org.ftctestbed.sim.hardware.HubTiming;
import org.ftctestbed.sim.hardware.MotorPort;
import org.ftctestbed.sim.hardware.ServoPort;
import org.ftctestbed.sim.hardware.SimBno055;
import org.ftctestbed.sim.hardware.SimColorSensor;
import org.ftctestbed.sim.hardware.SimDigitalChannels;
import org.ftctestbed.sim.hardware.SimDistanceSensor;
import org.ftctestbed.sim.hardware.SimI2cDevice;
import org.ftctestbed.sim.hardware.SimImu;
import org.ftctestbed.sim.hardware.SimLimelight;
import org.ftctestbed.sim.hardware.SimWebcam;
import org.ftctestbed.sim.hardware.SimLynxModule;
import org.ftctestbed.sim.hardware.SimMotorController;
import org.ftctestbed.sim.hardware.SimOctoQuad;
import org.ftctestbed.sim.hardware.SimOtos;
import org.ftctestbed.sim.hardware.SimPinpoint;
import org.ftctestbed.sim.hardware.SimServoController;
import org.ftctestbed.sim.hardware.SimSwitch;
import org.ftctestbed.sim.hardware.SimVoltageSensor;
import org.ftctestbed.sim.physics.Battery;
import org.ftctestbed.sim.physics.FieldWorld;
import org.ftctestbed.sim.physics.MecanumDrivetrain;
import org.ftctestbed.sim.physics.Mechanism1D;
import org.ftctestbed.sim.physics.OdometryPodSim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One robot on the simulated field: its hubs, motors, servos, sensors, drivetrain, game-piece
 * mechanisms and battery. Its OpMode talks to it through its own hardwareMap. The Simulation
 * owns the field and steps every robot together; anything here that reads or changes the
 * robot's physics takes the Simulation's lock, as the Simulation's own methods do.
 */
public final class SimRobot {
    private static final double IN = 0.0254;

    /** 1 for the first robot, 2 for its partner, ... */
    public final int number;
    public final RobotConfig config;
    public final HardwareMap hardwareMap = new HardwareMap();
    /** How long robot code waits for each hardware call (attached to the physics runner). */
    public final HubTiming hubTiming;

    private final Simulation sim;
    private final FieldWorld field;
    final Body body;
    private final MecanumDrivetrain drive;
    private final List<Mechanism1D> mechanisms = new ArrayList<>();
    private final List<HubSlot> hubs = new ArrayList<>();
    private final Map<String, MotorPort> motorPorts = new LinkedHashMap<>();
    private final Map<String, DcMotorImplEx> motorDevices = new HashMap<>();
    private final List<ServoPort> servoPorts = new ArrayList<>();
    private final List<OdometryPodSim> pods = new ArrayList<>();
    private final List<SimPinpoint> pinpoints = new ArrayList<>();
    private final List<SimOtos> otosSensors = new ArrayList<>();
    private final List<SimDistanceSensor> distanceSensors = new ArrayList<>();
    private final List<SimOctoQuad> octoquads = new ArrayList<>();
    private final List<SimLimelight> limelights = new ArrayList<>();
    private final List<SimWebcam> webcams = new ArrayList<>();
    private final org.ftctestbed.sim.vision.VisionScene scene = new Scene();
    private List<org.ftctestbed.sim.vision.Fiducial> fieldMap;
    private List<org.ftctestbed.sim.vision.VisionScene.Box> fieldOccluders;
    private final List<LightBinding> lights = new ArrayList<>();
    private final List<SwitchBinding> switches = new ArrayList<>();
    private final SimImu imu;
    private final SimBno055 bno055;
    private final RobotPieceHandling handling;
    private volatile FieldConfig.StartPosition start;

    private final Battery battery;
    private volatile double batteryVolts;
    private volatile double totalCurrent;
    private volatile double cgHeight;
    private double stepVolts, stepCurrent;

    private static final class HubSlot {
        final SimLynxModule module;
        final SimMotorController motors;
        final SimServoController servos;
        final SimDigitalChannels digital;
        final boolean[] motorUsed = new boolean[4];
        final boolean[] servoUsed = new boolean[6];
        final boolean[] digitalUsed = new boolean[8];

        HubSlot(SimLynxModule module, HubTiming timing) {
            this.module = module;
            this.motors = new SimMotorController(module, timing);
            module.attachMotors(motors);
            this.servos = new SimServoController(module, timing);
            this.digital = new SimDigitalChannels(module, timing);
        }
    }

    /** A light robot code switches, and how to see what it shows now (0xRRGGBB per LED, 0 = off). */
    private record LightBinding(String name, String type, java.util.function.Supplier<int[]> colors) {}

    private record SwitchBinding(SimSwitch sw, Mechanism1D mechanism, double below, double above) {}

    SimRobot(Simulation sim, int number, RobotConfig robotConfig, FieldWorld field, GamePieces pieces,
             Supplier<List<Retrievable>> retrievables) {
        this.sim = sim;
        this.number = number;
        this.config = robotConfig;
        this.field = field;
        this.body = field.addRobot(robotConfig);
        this.battery = new Battery(robotConfig.batteryVolts(), robotConfig.batteryCapacityAh(), robotConfig.batteryResistanceOhms());
        this.batteryVolts = battery.volts();
        this.cgHeight = robotConfig.cgHeight();
        this.hubTiming = new HubTiming(robotConfig.timing());
        double fieldSize = sim.fieldConfig.size();

        if (robotConfig.hubs().isEmpty()) {
            addHub("Control Hub", true, 173);
        } else {
            for (RobotConfig.Hub h : robotConfig.hubs()) addHub(h.name(), h.parent(), h.address());
        }

        for (RobotConfig.Motor m : robotConfig.allMotors()) addMotor(m.name(), m.spec(), m.hub(), m.port());

        RobotConfig.Drivetrain dt = robotConfig.drivetrain();
        drive = new MecanumDrivetrain(dt, body.getMass(),
                motorPorts.get(dt.frontLeft().name()), motorPorts.get(dt.frontRight().name()),
                motorPorts.get(dt.backLeft().name()), motorPorts.get(dt.backRight().name()));

        Map<String, RobotConfig.Motor> motorDefs = new HashMap<>();
        for (RobotConfig.Motor m : robotConfig.motors()) motorDefs.put(m.name(), m);
        Set<String> claimed = new HashSet<>();
        for (RobotConfig.Mechanism def : robotConfig.mechanisms()) {
            mechanisms.add(mechanismFor(def, motorDefs));
            claimed.addAll(def.motors());
        }
        // Motors not attached to a configured mechanism still spin (intakes, flywheels, ...).
        for (RobotConfig.Motor m : robotConfig.motors()) {
            if (claimed.contains(m.name())) continue;
            RobotConfig.Mechanism free = new RobotConfig.Mechanism(m.name(), "free", List.of(m.name()), 1.0,
                    0.02, 0, 0, false, 0, 0, 0, 0, 0.0005, 0.06);
            mechanisms.add(mechanismFor(free, motorDefs));
        }

        for (RobotConfig.OdometryPod def : robotConfig.odometryPods()) {
            OdometryPodSim pod = new OdometryPodSim(def, fieldSize);
            pods.add(pod);
            MotorPort port = motorPorts.get(def.name());
            if (port == null) {
                MotorSpec encoderOnly = new MotorSpec("encoder", "Encoder (dead wheel)", def.ticksPerRev(), 6000, 0.001, 0, 0);
                port = addMotor(def.name(), encoderOnly, null, -1);
            }
            port.attachEncoderSource(pod);
        }

        for (RobotConfig.Servo s : robotConfig.servos()) addServo(s);

        imu = new SimImu(robotConfig.imu(), sim.clock::nanos, hubTiming::i2c);
        // Robot code gets the IMU as the SDK class for its chip (all of them are an IMU).
        hardwareMap.put(robotConfig.imu().name(), switch (robotConfig.imu().chip()) {
            case "ANDYMARK" -> new com.qualcomm.hardware.andymark.AndyMarkIMU(imu);
            case "BNO055" -> new com.qualcomm.hardware.bosch.BNO055IMUNew(imu);
            default -> new com.qualcomm.hardware.bosch.BHI260IMU(imu);
        });
        bno055 = new SimBno055(imu);
        hardwareMap.put(robotConfig.imu().name(), bno055);

        // I2C odometry devices on Control Hub I2C ports: robot code gets the SDK's own drivers,
        // which talk to the simulated device's firmware register by register.
        SimLynxModule i2cHub = hubs.get(0).module;
        for (RobotConfig.Pinpoint def : robotConfig.pinpoints()) {
            OdometryPodSim xPod = new OdometryPodSim(def.xPod(), fieldSize);
            OdometryPodSim yPod = new OdometryPodSim(def.yPod(), fieldSize);
            pods.add(xPod);
            pods.add(yPod);
            SimPinpoint firmware = new SimPinpoint(def, xPod, yPod);
            pinpoints.add(firmware);
            SimI2cDevice port = new SimI2cDevice(firmware, i2cHub, def.bus(), hubTiming, sim.clock::nanos);
            hardwareMap.simPutI2cDevice(def.name(), port, firmware.xmlTag());
            hardwareMap.put(def.name(), new com.qualcomm.hardware.gobilda.GoBildaPinpointDriver(port, true));
        }
        for (RobotConfig.Otos def : robotConfig.otos()) {
            SimOtos firmware = new SimOtos(def);
            otosSensors.add(firmware);
            SimI2cDevice port = new SimI2cDevice(firmware, i2cHub, def.bus(), hubTiming, sim.clock::nanos);
            hardwareMap.simPutI2cDevice(def.name(), port, firmware.xmlTag());
            hardwareMap.put(def.name(), new com.qualcomm.hardware.sparkfun.SparkFunOTOS(port));
        }

        for (RobotConfig.DistanceSensor d : robotConfig.distanceSensors()) {
            SimDistanceSensor sensor = new SimDistanceSensor(d, hubTiming::i2c);
            distanceSensors.add(sensor);
            hardwareMap.put(d.name(), d.model().equals("AndyMark-Distance-Sensor")
                    ? new com.qualcomm.hardware.andymark.AndyMarkTOF(sensor)
                    : new com.qualcomm.hardware.rev.Rev2mDistanceSensor(sensor));
        }

        for (RobotConfig.Switch s : robotConfig.switches()) {
            Mechanism1D mech = mechanisms.stream().filter(m -> m.def.name().equals(s.mechanism())).findFirst().orElseThrow();
            // A digital input on the Control Hub: its own command, unless bulk reads are on (then it
            // comes with the bulk read the encoders already pay for).
            SimLynxModule controlHub = hubs.get(0).module;
            SimSwitch sw = new SimSwitch(s.name(), () -> {
                if (controlHub.getBulkCachingMode() == com.qualcomm.hardware.lynx.LynxModule.BulkCachingMode.OFF) {
                    hubTiming.command(controlHub);
                }
            });
            switches.add(new SwitchBinding(sw, mech, s.pressedBelow(), s.pressedAbove()));
            hardwareMap.put(s.name(), sw);
        }

        // The robot's game-piece mechanisms.
        Map<String, Mechanism1D> mechanismsByName = new HashMap<>();
        for (Mechanism1D m : mechanisms) mechanismsByName.put(m.def.name(), m);
        Map<String, ServoPort> servosByName = new HashMap<>();
        for (ServoPort sp : servoPorts) servosByName.put(sp.def.name(), sp);
        boolean hasHandling = robotConfig.intake() != null || robotConfig.hopper() != null
                || robotConfig.launcher() != null || !robotConfig.grippers().isEmpty();
        handling = hasHandling ? new RobotPieceHandling(robotConfig, body, pieces, motorPorts, servosByName,
                mechanismsByName, retrievables, this::alliance) : null;
        for (RobotConfig.ColorSensor cs : robotConfig.colorSensors()) {
            int slot = cs.hopperSlot();
            SimColorSensor sensor = new SimColorSensor(cs.name(), () -> handling.hopperSlotColor(slot), hubTiming::i2c);
            hardwareMap.put(cs.name(), cs.model().equals("AndyMark-Color-Sensor")
                    ? new com.qualcomm.hardware.andymark.AndyMarkColorSensor(sensor)
                    : new com.qualcomm.hardware.rev.RevColorSensorV3(sensor));
        }

        for (RobotConfig.Light l : robotConfig.lights()) addLight(l);

        // OctoQuads on Control Hub I2C ports, with the dead wheels plugged into them.
        for (RobotConfig.OctoQuad def : robotConfig.octoquads()) {
            SimOctoQuad oq = new SimOctoQuad(def, fieldSize, hubTiming::i2c);
            octoquads.add(oq);
            hardwareMap.put(def.name(), oq);
        }

        // Limelights: USB cameras that run their own pipelines (see SimLimelight).
        for (RobotConfig.Limelight def : robotConfig.limelights()) {
            SimLimelight ll = new SimLimelight(def, hubTiming::network);
            limelights.add(ll);
            hardwareMap.put(def.name(), ll);
        }

        // Webcams: USB cameras the code streams through a VisionPortal (see SimWebcam).
        for (RobotConfig.Webcam def : robotConfig.webcams()) {
            SimWebcam cam = new SimWebcam(def, fieldSize);
            webcams.add(cam);
            hardwareMap.put(def.name(), cam);
        }
    }

    /** The field this robot is on. */
    public Simulation simulation() { return sim; }

    // ---------------------------------------------------------------- as the game sees it

    private final class View implements RobotView {
        @Override public double x() { return SimRobot.this.x(); }
        @Override public double y() { return SimRobot.this.y(); }
        @Override public double heading() { return SimRobot.this.heading(); }
        @Override public double length() { return config.length(); }
        @Override public double width() { return config.width(); }
        @Override public double frameOffsetX() { return config.frameOffsetX(); }
        @Override public double frameOffsetY() { return config.frameOffsetY(); }

        @Override
        public double height() {
            double h = config.height();
            if (handling != null) {
                for (RobotPieceHandling.ClawState c : handling.parts().claws()) h = Math.max(h, c.height() + 2 * IN);
            }
            return h;
        }

        @Override public String alliance() { return SimRobot.this.alliance(); }
        @Override public List<GamePiece> held() { return handling != null ? handling.held() : List.of(); }
        @Override public boolean preload(GamePiece p) { return handling != null && handling.preload(p); }
        @Override public int number() { return number; }
        @Override public double speed() { return body.getLinearVelocity().getMagnitude(); }

        @Override
        public java.util.Set<String> touching() {
            java.util.Set<String> out = new java.util.HashSet<>();
            for (Body b : field.world.getInContactBodies(body, false)) {
                if (b.getUserData() instanceof String label) out.add(label);
            }
            return out;
        }

        @Override
        public boolean touches(RobotView other) {
            return other instanceof View v && field.world.isInContact(body, v.robot().body);
        }

        @Override
        public boolean drivingHard() {
            double duty = 0;
            for (MecanumDrivetrain.Wheel w : drive.wheels()) duty = Math.max(duty, Math.abs(w.port.appliedDuty()));
            return duty > 0.3;
        }

        SimRobot robot() { return SimRobot.this; }

        @Override
        public boolean powered() {
            for (MotorPort p : motorPorts.values()) if (p.isEnergized() && Math.abs(p.appliedDuty()) > 0.01) return true;
            return false;
        }
    }

    private final RobotView view = new View();

    /** The robot as the referee (the season's rules) sees it. */
    public RobotView view() { return view; }

    /**
     * The robot's body as seen by flying game pieces: a box the size of the robot. A piece that
     * flies into it - or that the robot drives into - is pushed out through the nearest face and
     * bounces or rolls off (moving with the robot). Shots and claw drops leaving the robot pass
     * out freely until they are clear of it.
     */
    final class Obstacle implements FlightObstacle {
        @Override
        public void collide(GamePiece p, double x0, double y0, double z0) {
            double r = p.kind.isBall() ? p.kind.radius() : p.kind.tallness() / 2;
            double hx = config.length() / 2 + r, hy = config.width() / 2 + r, top = config.height() + r;
            double heading = heading(), c = Math.cos(heading), s = Math.sin(heading);
            double rx = x(), ry = y();
            // In the frame's own coordinates (its middle may be offset from the robot's center).
            double lx = c * (p.x() - rx) + s * (p.y() - ry) - config.frameOffsetX(), ly = -s * (p.x() - rx) + c * (p.y() - ry) - config.frameOffsetY();
            boolean inside = Math.abs(lx) < hx && Math.abs(ly) < hy && p.z() < top;
            if (!inside) {
                if (p.exitingFrom() == body) p.setExitingFrom(null);
                return;
            }
            if (p.exitingFrom() == body) return;
            p.touchedByRobot(number);

            // Leave through the top if it came from above, else through the nearest face.
            double dx = hx - Math.abs(lx), dy = hy - Math.abs(ly), dz = top - p.z();
            double[] n;
            double depth;
            if (z0 >= top || (dz <= dx && dz <= dy)) {
                n = new double[]{0, 0, 1};
                depth = dz;
            } else if (dx <= dy) {
                double sx = Math.signum(lx == 0 ? 1 : lx);
                n = new double[]{sx * c, sx * s, 0};
                depth = dx;
            } else {
                double sy = Math.signum(ly == 0 ? 1 : ly);
                n = new double[]{-sy * s, sy * c, 0};
                depth = dy;
            }
            Vector2 v = body.getLinearVelocity();
            double w = body.getAngularVelocity();
            double[] sv = {v.x - w * (p.y() - ry), v.y + w * (p.x() - rx), 0};
            Contact.resolve(p, n, depth, p.kind.restitution(), sv);
            if (n[2] > 0 && Math.hypot(p.vx() - sv[0], p.vy() - sv[1]) < 0.15) {
                // Resting on the robot's top: robots aren't flat shelves - it rolls off the nearer edge.
                boolean alongX = Math.abs(lx) / hx > Math.abs(ly) / hy;
                double ex = alongX ? Math.signum(lx == 0 ? 1 : lx) : 0, ey = alongX ? 0 : Math.signum(ly == 0 ? 1 : ly);
                p.setVelocity(p.vx() + 0.3 * (c * ex - s * ey), p.vy() + 0.3 * (s * ex + c * ey), p.vz());
            }
        }
    }

    /** True if a spot {x, y, r} overlaps the robot's footprint. */
    boolean covers(double[] spot) {
        double[] out = outside(spot);
        return out[0] != spot[0] || out[1] != spot[1];
    }

    /** Moves a landing spot {x, y, r} out of the robot's footprint, to the nearest side; returns {x, y}. */
    double[] outside(double[] spot) {
        double x = spot[0], y = spot[1], r = spot[2];
        double heading = heading(), c = Math.cos(heading), s = Math.sin(heading);
        double rx = x(), ry = y();
        double ox = config.frameOffsetX(), oy = config.frameOffsetY();
        double lx = c * (x - rx) + s * (y - ry) - ox, ly = -s * (x - rx) + c * (y - ry) - oy;
        double hx = config.length() / 2 + r + 0.002, hy = config.width() / 2 + r + 0.002;
        if (Math.abs(lx) >= hx || Math.abs(ly) >= hy) return new double[]{x, y};
        if (hx - Math.abs(lx) < hy - Math.abs(ly)) lx = Math.signum(lx == 0 ? 1 : lx) * hx;
        else ly = Math.signum(ly == 0 ? 1 : ly) * hy;
        return new double[]{rx + c * (lx + ox) - s * (ly + oy), ry + s * (lx + ox) + c * (ly + oy)};
    }

    // ---------------------------------------------------------------- where it starts

    public FieldConfig.StartPosition startPosition() { return start; }

    void setStart(FieldConfig.StartPosition sp) { start = sp; }

    /** "red", "blue" or null (from the start position). */
    public String alliance() {
        FieldConfig.StartPosition sp = start;
        return sp != null ? sp.alliance() : null;
    }

    /** Match setup: back to its start position, power-cycled, with a charged battery. */
    void resetForMatch() {
        FieldConfig.Pose p = start.pose();
        resetRobot(p.x(), p.y(), p.heading());
        // A charged battery goes in for every match.
        battery.reset();
        batteryVolts = battery.volts();
    }

    /** Put the robot back at its start pose and power-cycle it (encoders and IMU zeroed). */
    public void resetRobot() {
        synchronized (sim) {
            FieldConfig.Pose p = start != null ? start.pose() : sim.fieldConfig.robotStart();
            resetRobot(p.x(), p.y(), p.heading());
        }
    }

    public void resetRobot(double x, double y, double heading) {
        synchronized (sim) {
            place(body, x, y, heading);
            for (Mechanism1D m : mechanisms) m.reset();
            for (MotorPort p : motorPorts.values()) p.powerCycle();
            for (ServoPort s : servoPorts) s.resetForOpMode();
            imu.update(heading, 0, 0);
            imu.powerCycle();
            for (OdometryPodSim pod : pods) pod.powerCycle();
            for (SimDistanceSensor d : distanceSensors) d.powerCycle();
            for (SimPinpoint p : pinpoints) p.powerCycle();
            for (SimOtos o : otosSensors) o.powerCycle();
            for (SimOctoQuad oq : octoquads) {
                for (OdometryPodSim pod : oq.pods()) if (pod != null) pod.powerCycle();
                oq.powerCycle();
            }
            for (SimLimelight ll : limelights) ll.powerCycle();
            for (SimWebcam w : webcams) w.powerCycle();
            drive.resetLoads();
            drive.stopWheels();
        }
    }

    /** Pick the robot up and set it down somewhere else (encoders keep their counts). */
    public void moveRobot(double x, double y, double heading) {
        synchronized (sim) {
            place(body, x, y, heading);
            for (OdometryPodSim pod : pods) pod.resetMotion();
            drive.resetLoads();
        }
    }

    /** Back to its start without power-cycling anything (an OpMode keeps running). */
    void moveToStart() {
        FieldConfig.Pose p = start.pose();
        moveRobot(p.x(), p.y(), p.heading());
    }

    private static void place(Body b, double x, double y, double heading) {
        Transform t = b.getTransform();
        t.identity();
        t.rotate(heading);
        t.translate(x, y);
        b.setLinearVelocity(0, 0);
        b.setAngularVelocity(0);
        b.clearForce();
        b.clearTorque();
        b.clearAccumulatedForce();
        b.clearAccumulatedTorque();
        b.setAtRest(false);
    }

    // ---- pushing the robot by hand while an OpMode runs (tuning OpModes ask for it) ----

    /** {grab x, grab y (robot frame, m), target x, target y (field, m)}, or null. */
    private volatile double[] hand;
    private static final double HAND_STIFFNESS = 400, HAND_DAMPING = 60, HAND_MAX_FORCE = 80; // N/m, N s/m, N

    /** Push the robot by hand: the grabbed point is pulled toward the target like a person pushing it. */
    public void setHand(double grabX, double grabY, double targetX, double targetY) {
        hand = new double[]{grabX, grabY, targetX, targetY};
    }

    public void clearHand() {
        hand = null;
    }

    /** Where the hand holds the robot and pulls it to (field, m), or null. */
    public double[] handLine() {
        double[] h = hand;
        if (h == null) return null;
        double th = heading(), c = Math.cos(th), s = Math.sin(th);
        return new double[]{x() + c * h[0] - s * h[1], y() + s * h[0] + c * h[1], h[2], h[3]};
    }

    private void applyHand() {
        double[] h = hand;
        if (h == null) return;
        double th = body.getTransform().getRotationAngle(), c = Math.cos(th), s = Math.sin(th);
        double rx = c * h[0] - s * h[1], ry = s * h[0] + c * h[1];
        double px = body.getTransform().getTranslationX() + rx, py = body.getTransform().getTranslationY() + ry;
        Vector2 v = body.getLinearVelocity();
        double w = body.getAngularVelocity();
        double vx = v.x - w * ry, vy = v.y + w * rx;
        double fx = HAND_STIFFNESS * (h[2] - px) - HAND_DAMPING * vx, fy = HAND_STIFFNESS * (h[3] - py) - HAND_DAMPING * vy;
        double f = Math.hypot(fx, fy);
        if (f > HAND_MAX_FORCE) {
            fx *= HAND_MAX_FORCE / f;
            fy *= HAND_MAX_FORCE / f;
        }
        body.applyForce(new Vector2(fx, fy), new Vector2(px, py));
        body.setAtRest(false);
    }

    // ---------------------------------------------------------------- hardware

    private void addHub(String name, boolean parent, int address) {
        SimLynxModule module = new SimLynxModule(name, address, parent, () -> batteryVolts, () -> totalCurrent, hubTiming);
        HubSlot slot = new HubSlot(module, hubTiming);
        hubs.add(slot);
        hardwareMap.put(name, module);
        hardwareMap.put(name, slot.motors);
        hardwareMap.put(name, slot.servos);
        hardwareMap.put(name, new SimVoltageSensor(() -> batteryVolts, () -> hubTiming.command(module)));
    }

    private HubSlot hubFor(String hubName, boolean motor) {
        if (hubName != null) {
            for (HubSlot h : hubs) if (h.module.name().equals(hubName)) return h;
            throw new ConfigException("Unknown hub \"" + hubName + "\" (add it to \"hubs\" in robot.jsonc)");
        }
        for (HubSlot h : hubs) {
            boolean[] used = motor ? h.motorUsed : h.servoUsed;
            for (boolean u : used) if (!u) return h;
        }
        addHub("Expansion Hub " + (hubs.size() + 1), false, hubs.size() + 1);
        return hubs.get(hubs.size() - 1);
    }

    private static int freePort(boolean[] used, int requested, String what, String hub) {
        if (requested >= 0) {
            if (requested >= used.length) throw new ConfigException(what + ": port " + requested + " does not exist on " + hub);
            if (used[requested]) throw new ConfigException(what + ": port " + requested + " on " + hub + " is already used");
            return requested;
        }
        for (int i = 0; i < used.length; i++) if (!used[i]) return i;
        throw new ConfigException(what + ": no free port on " + hub);
    }

    private MotorPort addMotor(String name, MotorSpec spec, String hubName, int port) {
        HubSlot hub = hubFor(hubName, true);
        int p = freePort(hub.motorUsed, port, "Motor \"" + name + "\"", hub.module.name());
        hub.motorUsed[p] = true;
        MotorPort mp = new MotorPort(name, spec, hub.module.name(), p);
        hub.motors.setPort(p, mp);
        motorPorts.put(name, mp);
        DcMotorImplEx device = new DcMotorImplEx(hub.motors, p);
        motorDevices.put(name, device);
        hardwareMap.put(name, device);
        return mp;
    }

    private void addServo(RobotConfig.Servo def) {
        HubSlot hub = hubFor(def.hub(), false);
        int p = freePort(hub.servoUsed, def.port(), "Servo \"" + def.name() + "\"", hub.module.name());
        hub.servoUsed[p] = true;
        ServoPort sp = new ServoPort(def, hub.module.name(), p);
        hub.servos.setPort(p, sp);
        servoPorts.add(sp);
        HardwareDevice device = def.continuous() ? new CRServoImplEx(hub.servos, p) : new ServoImplEx(hub.servos, p);
        hardwareMap.put(def.name(), device);
    }

    private void addLight(RobotConfig.Light def) {
        String what = "Light \"" + def.name() + "\"";
        switch (def.type()) {
            case "led" -> {
                HubSlot hub = def.hub() != null ? hubFor(def.hub(), false) : hubs.get(0);
                int p = freePort(hub.digitalUsed, def.port(), what, hub.module.name());
                hub.digitalUsed[p] = true;
                SimDigitalChannels digital = hub.digital;
                hardwareMap.put(def.name(), new com.qualcomm.robotcore.hardware.LED(digital, p));
                lights.add(new LightBinding(def.name(), "led", () -> new int[]{digital.simLow(p) ? def.color() : 0}));
            }
            case "blinkin" -> {
                // On a servo port, which starts with the Blinkin's 500-2500 us range, like the SDK's Blinkin device type.
                RobotConfig.Servo port = new RobotConfig.Servo(def.name(), false, Math.toRadians(180), 0.001, 500, 2500, 0, def.hub(), def.port());
                HubSlot hub = hubFor(def.hub(), false);
                int p = freePort(hub.servoUsed, def.port(), what, hub.module.name());
                hub.servoUsed[p] = true;
                ServoPort sp = new ServoPort(port, hub.module.name(), p);
                sp.setDefaultRange(new com.qualcomm.robotcore.hardware.PwmControl.PwmRange(500, 2500));
                hub.servos.setPort(p, sp);
                hardwareMap.put(def.name(), new com.qualcomm.hardware.rev.RevBlinkinLedDriver(hub.servos, p));
                lights.add(new LightBinding(def.name(), "blinkin", () -> new int[]{
                        sp.isPwmEnabled() ? BlinkinColors.color((int) Math.round((sp.pulseMicros() - 1005) / 10.0)) : 0}));
            }
            default -> {
                com.qualcomm.hardware.sparkfun.SparkFunLEDStick stick = new com.qualcomm.hardware.sparkfun.SparkFunLEDStick(hubTiming::i2c);
                hardwareMap.put(def.name(), stick);
                lights.add(new LightBinding(def.name(), "ledStick", stick::simColors));
            }
        }
    }

    private Mechanism1D mechanismFor(RobotConfig.Mechanism def, Map<String, RobotConfig.Motor> motorDefs) {
        List<MotorPort> ports = new ArrayList<>();
        List<Boolean> reversed = new ArrayList<>();
        for (String name : def.motors()) {
            ports.add(motorPorts.get(name));
            reversed.add(motorDefs.get(name).mountedReversed());
        }
        return new Mechanism1D(def, ports, reversed);
    }

    /** All motor ports by device name (drive, mechanisms, encoder-only dead-wheel ports). */
    public Map<String, MotorPort> motorPorts() { return motorPorts; }

    public List<ServoPort> servoPorts() { return servoPorts; }

    /** The intake, hopper, launcher and claws, or null if the robot has none. */
    public RobotPieceHandling handling() { return handling; }

    public double batteryVolts() { return batteryVolts; }

    /** The battery: charge left and used, resting voltage. */
    public Battery battery() { return battery; }

    /** Center of gravity height above the tiles right now (m). */
    public double cgHeight() { return cgHeight; }

    /** Current drawn from the battery by all motors (A), as of the last step. */
    public double batteryCurrent() { return totalCurrent; }

    public double x() { return body.getTransform().getTranslationX(); }
    public double y() { return body.getTransform().getTranslationY(); }
    public double heading() { return body.getTransform().getRotationAngle(); }

    /** Turn rate, rad/s (counter-clockwise positive). */
    public double omega() { return body.getAngularVelocity(); }

    /** Speed over the field, m/s. */
    public double speed() { return body.getLinearVelocity().getMagnitude(); }

    // ---------------------------------------------------------------- stepping (Simulation lock held)

    /** Before the physics world steps: motor and servo control, then the forces on the robot. */
    void beforeWorld(double dt) {
        stepVolts = batteryVolts;
        for (MotorPort p : motorPorts.values()) p.control(dt);
        for (ServoPort s : servoPorts) s.step(dt);
        cgHeight = centerOfGravityHeight();
        drive.setCgHeight(cgHeight);
        drive.applyForces(body, stepVolts, dt);
        applyHand();
    }

    /** After the world stepped: encoders, mechanisms, sensors and the game-piece mechanisms. */
    void afterWorld(double dt) {
        double vb = stepVolts;
        drive.updateEncoders(body, dt);

        double current = drive.batteryCurrent();
        for (Mechanism1D m : mechanisms) {
            m.step(dt, vb);
            current += m.batteryCurrent();
        }
        for (OdometryPodSim pod : pods) pod.update(body, dt);
        for (SimPinpoint p : pinpoints) p.step(dt, body.getAngularVelocity());
        for (SimOtos o : otosSensors) o.step(dt, body, drive.accelX(), drive.accelY());
        for (SimOctoQuad oq : octoquads) {
            for (OdometryPodSim pod : oq.pods()) if (pod != null) pod.update(body, dt);
            oq.step(dt, body.getAngularVelocity());
        }
        imu.updateAcceleration(drive.accelX(), drive.accelY());
        bno055.step(dt);

        double heading = heading();
        imu.update(heading, body.getAngularVelocity(), dt);

        double rx = x(), ry = y();
        double c = Math.cos(heading), s = Math.sin(heading);
        for (SimDistanceSensor d : distanceSensors) {
            if (!d.measurementDue(dt)) continue;
            var def = d.def;
            double sx = rx + c * def.x() - s * def.y(), sy = ry + s * def.x() + c * def.y();
            // A sensor mounted higher than a game piece looks over it; it sees other robots.
            double dist = field.raycast(body, sx, sy, heading + def.heading(), def.maxRange(),
                    b -> !Double.isNaN(def.z()) && b.getUserData() instanceof GamePiece gp && gp.kind.tallness() < def.z());
            d.setMeasured(dist <= def.maxRange() ? dist : org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.infinity);
        }

        for (SwitchBinding b : switches) {
            double v = userUnits(b.mechanism());
            b.sw().setPressed(v <= b.below() || v >= b.above());
        }

        if (handling != null) handling.step(dt);
        stepCurrent = current;
    }

    /** The end of a step: the battery gives the current the motors drew. */
    void finishStep(double dt) {
        totalCurrent = stepCurrent;
        battery.step(stepCurrent, dt);
        batteryVolts = Math.max(6.0, battery.volts());
        if (!limelights.isEmpty()) {
            double now = sim.clock.seconds(), x = x(), y = y(), heading = heading();
            for (SimLimelight ll : limelights) ll.step(dt, now, x, y, heading, scene);
        }
        if (!webcams.isEmpty()) {
            Vector2 v = body.getLinearVelocity();
            long nanos = sim.clock.nanos();
            double x = x(), y = y(), heading = heading(), omega = body.getAngularVelocity();
            for (SimWebcam w : webcams) w.step(nanos, x, y, heading, v.x, v.y, omega, scene);
        }
    }

    /** What this robot's cameras see: the game pieces, the season's AprilTags, and what's opaque. */
    private final class Scene implements org.ftctestbed.sim.vision.VisionScene {
        @Override
        public List<Ball> balls() {
            List<Ball> out = new ArrayList<>();
            for (GamePiece p : sim.pieces().all()) {
                GamePiece.State st = p.state();
                if (st != GamePiece.State.FLOOR && st != GamePiece.State.AIRBORNE && st != GamePiece.State.CONTAINED) continue;
                double r = p.kind.isBall() ? p.kind.radius() : p.kind.tallness() / 2;
                double z = st == GamePiece.State.FLOOR ? Math.max(p.z(), r) : p.z();
                out.add(new Ball(p.x(), p.y(), z, r, p.kind.color(), p.kind.id(), p.kind.alliance()));
            }
            return out;
        }

        @Override
        public List<org.ftctestbed.sim.vision.Fiducial> fiducials() {
            return sim.season() != null ? sim.season().fiducials() : List.of();
        }

        @Override
        public List<org.ftctestbed.sim.vision.Fiducial> fieldMap() {
            if (fieldMap == null) fieldMap = sim.season() != null ? sim.season().fieldMap() : List.of();
            return fieldMap;
        }

        @Override
        public List<Box> occluders() {
            if (fieldOccluders == null) fieldOccluders = sim.season() != null ? sim.season().visionOccluders() : List.of();
            List<Box> out = new ArrayList<>(fieldOccluders);
            for (SimRobot r : sim.robots()) {
                if (r == SimRobot.this) continue;
                // Bumpers in the alliance's color (a webcam's color blobs can find them), the robot above them dark.
                String a = r.alliance();
                int bumper = "red".equals(a) ? 0xC8102E : "blue".equals(a) ? 0x0033A0 : 0x45474B;
                double bumperTop = Math.min(r.config.height(), 5 * IN);
                double hc = Math.cos(r.heading()), hs = Math.sin(r.heading());
                double fx = r.x() + hc * r.config.frameOffsetX() - hs * r.config.frameOffsetY();
                double fy = r.y() + hs * r.config.frameOffsetX() + hc * r.config.frameOffsetY();
                out.add(new Box(fx, fy, r.config.length() / 2, r.config.width() / 2, r.heading(), 0, bumperTop, bumper));
                if (r.config.height() > bumperTop) {
                    out.add(new Box(fx, fy, r.config.length() / 2 - 0.75 * IN, r.config.width() / 2 - 0.75 * IN, r.heading(),
                            bumperTop, r.config.height(), 0x3A3C40));
                }
            }
            return out;
        }
    }

    /** The robot's cameras (for tests and the camera window). */
    public List<SimLimelight> limelights() {
        return limelights;
    }

    public List<SimWebcam> webcams() {
        return webcams;
    }

    /** What this robot's cameras see (game pieces, AprilTags, what's in the way). */
    public org.ftctestbed.sim.vision.VisionScene visionScene() {
        return scene;
    }

    /**
     * The robot's center of gravity rises as mechanisms lift their mass: a vertical slide carries
     * its mass up by its extension, an arm by armLength * sin(angle) (from where it starts).
     */
    private double centerOfGravityHeight() {
        double m = config.mass();
        double h = config.cgHeight();
        for (Mechanism1D mech : mechanisms) {
            RobotConfig.Mechanism d = mech.def;
            switch (d.type()) {
                case "slide" -> { if (d.vertical()) h += d.mass() * Math.max(0, mech.position()) / m; }
                case "arm" -> h += d.mass() * d.armLength() * (Math.sin(mech.position()) - Math.sin(d.startAngle())) / m;
                default -> { }
            }
        }
        return Math.max(0, Math.min(config.height() * 2, h));
    }

    /** Mechanism position in the units used in robot.jsonc: inches (slide), degrees (arm), revolutions (free). */
    private static double userUnits(Mechanism1D m) {
        return switch (m.def.type()) {
            case "slide" -> m.position() / IN;
            case "arm" -> Math.toDegrees(m.position());
            default -> m.position() / (2 * Math.PI);
        };
    }

    // ---------------------------------------------------------------- snapshot

    /** The robot's own state; the field's (pieces, flights, game) is shared by every robot's snapshot. */
    public Snapshot snapshot() {
        synchronized (sim) {
            return sim.snapshotOf(this);
        }
    }

    Snapshot snapshot(Simulation.FieldState f) {
        Vector2 v = body.getLinearVelocity();
        double heading = heading();
        double rx = x(), ry = y();
        double c = Math.cos(heading), s = Math.sin(heading);

        List<Snapshot.Wheel> wheels = new ArrayList<>();
        for (MecanumDrivetrain.Wheel w : drive.wheels()) {
            wheels.add(new Snapshot.Wheel(w.label, w.x, w.y, w.port.appliedDuty() * w.mountSign, w.isSlipping(),
                    w.load(), w.isLifting()));
        }

        List<Snapshot.Ray> rays = new ArrayList<>();
        for (SimDistanceSensor d : distanceSensors) {
            var def = d.def;
            double sx = rx + c * def.x() - s * def.y(), sy = ry + s * def.x() + c * def.y();
            double m = d.measuredMeters();
            boolean hit = m != org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.infinity;
            double len = hit ? m : def.maxRange();
            double a = heading + def.heading();
            rays.add(new Snapshot.Ray(def.name(), sx, sy, sx + Math.cos(a) * len, sy + Math.sin(a) * len, hit));
        }

        List<Snapshot.Motor> motors = new ArrayList<>();
        for (MotorPort p : motorPorts.values()) {
            // Report values the way robot code sees them (after setDirection).
            int sign = motorDevices.get(p.name).getDirection() == com.qualcomm.robotcore.hardware.DcMotorSimple.Direction.REVERSE ? -1 : 1;
            motors.add(new Snapshot.Motor(p.name, p.hubName, p.portNumber, p.getMode().name(), sign * p.getPower() + 0.0,
                    sign * p.appliedDuty() + 0.0, sign * p.livePosition(), sign * p.reportedVelocityTps() + 0.0, p.getCurrentAmps(),
                    p.shaftTicks(), p.spec.ticksPerRev()));
        }

        List<Snapshot.Servo> servos = new ArrayList<>();
        for (ServoPort sp : servoPorts) {
            servos.add(new Snapshot.Servo(sp.def.name(), sp.def.continuous(), sp.getCommanded(), sp.angle(), sp.isPwmEnabled()));
        }

        List<Snapshot.Mechanism> mechs = new ArrayList<>();
        for (Mechanism1D m : mechanisms) {
            double min = m.min(), max = m.max();
            switch (m.def.type()) {
                case "slide" -> { min /= IN; max /= IN; }
                case "arm" -> { min = Math.toDegrees(min); max = Math.toDegrees(max); }
                default -> { }
            }
            mechs.add(new Snapshot.Mechanism(m.def.name(), m.def.type(), userUnits(m), min, max));
        }

        return new Snapshot(f.now(), rx, ry, heading, v.x, v.y, body.getAngularVelocity(), batteryVolts,
                hubs.get(0).module.simGetLedColor(), wheels, f.pieces(), rays, motors, servos, mechs,
                handling != null ? handling.parts() : null, f.flights(), f.season(),
                alliance(), f.phase(), f.clock(),
                new Snapshot.Physics(battery.charge(), battery.usedMah(), battery.restingVolts(), totalCurrent,
                        cgHeight, drive.accelX(), drive.accelY(), drive.liftingWheels()),
                odometry(), lights(), cameras());
    }

    private List<Snapshot.Camera> cameras() {
        List<Snapshot.Camera> out = new ArrayList<>();
        for (SimLimelight ll : limelights) {
            SimLimelight.Frame f = ll.lastFrame();
            List<Snapshot.CameraTarget> targets = new ArrayList<>();
            org.ftctestbed.sim.vision.Frame3 cam;
            if (f != null) {
                for (SimLimelight.Target t : f.targets()) targets.add(new Snapshot.CameraTarget(t.label(), t.corners(), t.primary()));
                cam = f.camera();
            } else {
                cam = org.ftctestbed.sim.vision.Frame3.planar(x(), y(), 0, heading()).compose(ll.mount());
            }
            var model = ll.camera;
            out.add(new Snapshot.Camera(ll.def.name(), "Limelight 3A", cam.origin(), cam.axis(0), cam.axis(1), cam.axis(2),
                    model.hfov(), model.vfov(), model.width(), model.height(), f != null ? f.pipeline() : ll.activePipeline(),
                    f != null ? String.valueOf(f.json().get("pipelineType")) : "", targets));
        }
        for (SimWebcam w : webcams) {
            SimWebcam.Capture c = w.lastCapture();
            List<Snapshot.CameraTarget> targets = new ArrayList<>();
            org.ftctestbed.sim.vision.Frame3 cam;
            int width = 640, height = 480;
            double fx;
            if (c != null) {
                for (org.ftctestbed.shim.vision.SimFrame.Tag t : c.tags()) {
                    // The library's corner order is bottom-left first; the window wants top-left first.
                    double[][] k = t.corners;
                    targets.add(new Snapshot.CameraTarget(String.valueOf(t.id), new double[][]{k[3], k[2], k[1], k[0]}, false));
                }
                cam = c.camera();
                width = c.width();
                height = c.height();
                fx = c.fx();
            } else {
                cam = org.ftctestbed.sim.vision.Frame3.planar(x(), y(), 0, heading()).compose(w.mount());
                fx = w.model.lens(width, height).fx();
            }
            out.add(new Snapshot.Camera(w.def.name(), w.model.id() + " webcam", cam.origin(), cam.axis(0), cam.axis(1), cam.axis(2),
                    2 * Math.atan(width / 2.0 / fx), 2 * Math.atan(height / 2.0 / fx), width, height, -1,
                    c != null ? "webcam" : "webcam (not streaming)", targets));
        }
        return out;
    }

    private List<Snapshot.Light> lights() {
        List<Snapshot.Light> out = new ArrayList<>();
        for (LightBinding l : lights) out.add(new Snapshot.Light(l.name(), l.type(), l.colors().get()));
        return out;
    }

    private List<Snapshot.Odometry> odometry() {
        List<Snapshot.Odometry> out = new ArrayList<>();
        for (SimPinpoint p : pinpoints) {
            SimPinpoint.Estimate e = p.estimate();
            out.add(new Snapshot.Odometry(p.def.name(), "Pinpoint", e.xMm() / 1000, e.yMm() / 1000, e.heading(),
                    e.calibrating() ? "CALIBRATING" : "READY"));
        }
        for (SimOtos o : otosSensors) {
            SimOtos.Estimate e = o.estimate();
            out.add(new Snapshot.Odometry(o.def.name(), "OTOS", e.x(), e.y(), e.heading(), e.tracking() ? "TRACKING" : "LOST SURFACE"));
        }
        for (SimOctoQuad q : octoquads) {
            SimOctoQuad.Estimate e = q.estimate();
            out.add(new Snapshot.Odometry(q.def.name(), "OctoQuad", e.x(), e.y(), e.heading(), e.status().name()));
        }
        return out;
    }

    @Override
    public String toString() {
        return "Robot " + number;
    }
}
