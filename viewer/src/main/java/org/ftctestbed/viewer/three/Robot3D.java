package org.ftctestbed.viewer.three;

import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

import org.ftctestbed.sim.Snapshot;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.RobotPieceHandling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleConsumer;

import static org.ftctestbed.viewer.three.Shapes.*;

/**
 * The robot in 3D. Without a CAD model it is generated from robot.jsonc: chassis, mecanum
 * wheels, motors, hubs, and whichever game-piece mechanisms are configured. With a CAD model
 * (see RobotModel) the model replaces the generated body and its bound parts move with the
 * simulated mechanisms. Robot frame: x forward, y left, z up, origin at the center on the floor.
 */
final class Robot3D {
    private static final int GOBILDA_YELLOW = 0xF6C343, ALUMINUM = 0xA9B1B8, DARK = 0x2B2E33;
    private static PhongMaterial shadowLook;

    final Group root = new Group();
    private final Translate position = new Translate();
    private final Rotate heading = new Rotate(0, Rotate.Z_AXIS);
    private final Group generated = new Group();
    private final Group cadHolder = new Group();
    private final RobotConfig cfg;

    private final Map<String, Rotate> wheelSpin = new HashMap<>();
    // Readable spins (see Spin): mecanum wheels repeat every 36 degrees, the flywheel's cross
    // every 90, the transfer wheel's spokes every 60; the roller's paddles are one row.
    private final Map<String, Spin> wheelShown = new HashMap<>();
    private final Spin intakeShown = new Spin(30), flywheelShown = new Spin(40), transferShown = new Spin(25);
    private final Map<String, Double> wheelSign = new HashMap<>();
    private Rotate intakeSpin, flywheelSpin, kickerTurn, transferSpin;
    private boolean transferIsServo;
    // Mechanisms drawn as simple stand-ins (arms, slides without a claw, other spinning motors):
    // name -> what to move, given the mechanism's position (in, deg or revolutions).
    private final Map<String, DoubleConsumer> mechanismMotion = new HashMap<>();
    private final Group hopperBalls = new Group();
    private List<Integer> lastHopper = List.of();
    private final List<Box> signs = new ArrayList<>();
    private final Group numbers = new Group();
    private String lastAlliance = "";
    private final Translate carriage = new Translate();
    private final Translate leftFinger = new Translate(), rightFinger = new Translate();
    private final Group liftRails = new Group();
    private RobotModel cad;
    private final Box outline;
    private final Group frontArrow = new Group();
    // The soft shadow under the robot (on the robot, so a CAD model gets it too).
    private final MeshView shadow;
    private final Translate shadowAt = new Translate(0, 0, Field3D.ROBOT_SHADOW_Z);

    Robot3D(RobotConfig cfg) {
        this.cfg = cfg;
        root.getTransforms().addAll(position, heading);
        if (shadowLook == null) shadowLook = Shapes.shade(Textures.softRect(0.62));
        shadow = quad(-0.5, -0.5, 0.5, 0.5, 0, 1, shadowLook);
        shadow.getTransforms().addAll(shadowAt, new Scale(cfg.length() + 10 * IN, cfg.width() + 10 * IN));
        shadow.setMouseTransparent(true);
        root.getChildren().add(shadow);
        // The robot's size from robot.jsonc and an arrow at its front, for lining up a CAD model.
        double fox = cfg.frameOffsetX(), foy = cfg.frameOffsetY(); // the frame's middle, from the robot's center
        outline = box(fox, foy, cfg.height() / 2, cfg.length(), cfg.width(), cfg.height(), mat(0x4FC3F7, 0.18));
        frontArrow.getChildren().addAll(
                cylinder(new double[]{fox, foy, cfg.height() + 1 * IN}, new double[]{fox + cfg.length() / 2 + 3 * IN, foy, cfg.height() + 1 * IN}, 0.35 * IN, mat(0x4FC3F7)),
                sphere(fox + cfg.length() / 2 + 3 * IN, foy, cfg.height() + 1 * IN, 0.9 * IN, mat(0x4FC3F7)));
        outline.setMouseTransparent(true);
        frontArrow.setMouseTransparent(true);
        outline.setVisible(false);
        frontArrow.setVisible(false);
        root.getChildren().addAll(generated, cadHolder, outline, frontArrow);
        buildGenerated();
        // Limelights where they're mounted (on the root, so a CAD model shows them too).
        for (RobotConfig.Limelight ll : cfg.limelights()) root.getChildren().add(limelight(ll));
        for (RobotConfig.Webcam w : cfg.webcams()) root.getChildren().add(webcam(w));
    }

    /**
     * A webcam (sized like a Logitech C920: a 94 x 29 x 24 mm black bar with its lens in the
     * middle) at its mount, looking where the camera looks.
     */
    private static Group webcam(RobotConfig.Webcam def) {
        org.ftctestbed.sim.vision.Frame3 f = org.ftctestbed.sim.hardware.SimWebcam.mount(def);
        Group g = new Group();
        g.getChildren().addAll(
                box(0, 0, -0.012, 0.094, 0.029, 0.024, plastic(0x141516)),
                cylinder(new double[]{0, 0, -0.001}, new double[]{0, 0, 0.002}, 0.0085, glossy(0x050505)),
                box(0.034, -0.008, 0.0002, 0.003, 0.003, 0.002, mat(0xEDEDED)));
        double[] x = f.axis(0), y = f.axis(1), z = f.axis(2), o = f.origin();
        g.getTransforms().add(new javafx.scene.transform.Affine(
                x[0], y[0], z[0], o[0],
                x[1], y[1], z[1], o[1],
                x[2], y[2], z[2], o[2]));
        g.setMouseTransparent(true);
        return g;
    }

    /**
     * A Limelight 3A: a dark 72 x 48 x 17 mm case with its lens and green status light, at its
     * mount, looking where the camera looks.
     */
    private static Group limelight(RobotConfig.Limelight def) {
        org.ftctestbed.sim.vision.Frame3 f = org.ftctestbed.sim.hardware.SimLimelight.mount(def);
        Group g = new Group();
        // In the lens frame: x right, y down, z out of the lens; the case sits behind the lens.
        g.getChildren().addAll(
                box(0, 0, -0.0085, 0.072, 0.048, 0.017, plastic(0x1C1E21)),
                cylinder(new double[]{0, 0, -0.001}, new double[]{0, 0, 0.004}, 0.0065, glossy(0x0A0A0A)),
                box(0.026, -0.016, 0.0002, 0.004, 0.004, 0.002, mat(0x39FF6A)));
        double[] x = f.axis(0), y = f.axis(1), z = f.axis(2), o = f.origin();
        g.getTransforms().add(new javafx.scene.transform.Affine(
                x[0], y[0], z[0], o[0],
                x[1], y[1], z[1], o[1],
                x[2], y[2], z[2], o[2]));
        g.setMouseTransparent(true);
        return g;
    }

    void setOutlineVisible(boolean visible) {
        outline.setVisible(visible);
        frontArrow.setVisible(visible);
    }

    // Seven-segment digits: segments a b c d e f g (top, top right, bottom right, bottom, bottom
    // left, top left, middle) as {u, v, width, height} for a digit 1 wide and 2 tall, u to the right.
    private static final double[][] SEGMENTS = {
            {0, 0.88, 1, 0.24}, {0.38, 0.5, 0.24, 1}, {0.38, -0.5, 0.24, 1}, {0, -0.88, 1, 0.24},
            {-0.38, -0.5, 0.24, 1}, {-0.38, 0.5, 0.24, 1}, {0, 0, 1, 0.24}};
    private static final String[] DIGITS = {"abcdef", "bc", "abged", "abgcd", "fgbc", "afgcd", "afgedc", "abc", "abcdefg", "abcdfg"};

    /**
     * The robot's number (1, 2...) in white on both side signs, so partners can tell their robots
     * apart; 0 takes it off. Each side's digit reads correctly from outside the robot.
     */
    void setNumber(int n) {
        numbers.getChildren().clear();
        // Each robot's shadow a hair above the one before, so where two overlap both show.
        shadowAt.setZ(Field3D.ROBOT_SHADOW_Z + 0.0002 * Math.max(0, n));
        if (n <= 0) return;
        String digits = Integer.toString(n);
        double h = 0.95 * IN, t = 0.06 * IN, axleZ = cfg.drivetrain().wheelRadius(), z = axleZ + 2.2 * IN;
        double gap = 1.3 * h, start = -(digits.length() - 1) * gap / 2;
        var white = plastic(0xFFFFFF);
        for (int side : new int[]{-1, 1}) {
            // Seen from the left (+y) the viewer's right is the robot's back (-x); from the right, its front.
            double right = side > 0 ? -1 : 1, y = side * (cfg.width() / 2 - 0.025 * IN + t / 2);
            for (int k = 0; k < digits.length(); k++) {
                double cu = start + k * gap;
                String segs = DIGITS[digits.charAt(k) - '0'];
                // A lone "1" uses the right-hand segments: shift it back to the middle.
                double shift = segs.equals("bc") ? -0.38 * h : 0;
                for (char c : segs.toCharArray()) {
                    double[] sg = SEGMENTS[c - 'a'];
                    double u = cu + shift + sg[0] * h;
                    numbers.getChildren().add(box(right * u, y, z + sg[1] * h, sg[2] * h, t, sg[3] * h, white));
                }
            }
        }
    }

    /** The number's bars (tests): each as {x, y, z, length along x, height} in the robot frame. */
    List<double[]> numberSegments() {
        List<double[]> out = new ArrayList<>();
        for (javafx.scene.Node n : numbers.getChildren()) {
            Box b = (Box) n;
            Translate tr = (Translate) n.getTransforms().get(0);
            out.add(new double[]{tr.getX(), tr.getY(), tr.getZ(), b.getWidth(), b.getDepth()});
        }
        return out;
    }

    // ------------------------------------------------------------------ generated model

    private void buildGenerated() {
        RobotConfig.Drivetrain dt = cfg.drivetrain();
        double L = cfg.length(), W = cfg.width(), r = dt.wheelRadius();
        double a = dt.wheelbase() / 2, b = dt.trackWidth() / 2, ww = 1.5 * IN;
        double rail = b - ww / 2 - 0.9 * IN, axleZ = r;
        var alu = metal(ALUMINUM);
        // Side channels, cross members, deck plate.
        generated.getChildren().addAll(
                box(0, rail, axleZ, L - 1 * IN, 1.5 * IN, 1.5 * IN, alu),
                box(0, -rail, axleZ, L - 1 * IN, 1.5 * IN, 1.5 * IN, alu),
                box(L / 2 - 1.25 * IN, 0, axleZ, 1.5 * IN, 2 * rail, 1.5 * IN, alu),
                box(-L / 2 + 1.25 * IN, 0, axleZ, 1.5 * IN, 2 * rail, 1.5 * IN, alu),
                box(0, 0, axleZ + 0.85 * IN, L - 3 * IN, 2 * rail - 1.5 * IN, 0.2 * IN, plastic(0x34404A)));
        // Robot signs in alliance color on both sides (rule: team number + alliance must be visible).
        for (int side : new int[]{-1, 1}) {
            Box sign = box(0, side * (W / 2 - 0.1 * IN), axleZ + 2.2 * IN, L * 0.62, 0.15 * IN, 2.4 * IN, plastic(0x9E9E9E));
            signs.add(sign);
            generated.getChildren().add(sign);
        }
        // On the robot itself, not the generated body, so a CAD model gets the numbers too.
        numbers.setMouseTransparent(true);
        root.getChildren().add(numbers);
        // Wheels + motors.
        Object[][] wheels = {
                {dt.frontLeft(), a, b, true}, {dt.frontRight(), a, -b, false},
                {dt.backLeft(), -a, b, false}, {dt.backRight(), -a, -b, true}};
        for (Object[] w : wheels) {
            RobotConfig.Motor m = (RobotConfig.Motor) w[0];
            double wx = (double) w[1], wy = (double) w[2];
            Rotate spin = new Rotate(0, Rotate.Y_AXIS);
            Group wheel = mecanumWheel(r, ww, (boolean) w[3]);
            wheel.getTransforms().addAll(new Translate(wx, wy, axleZ), spin);
            wheelSpin.put(m.name(), spin);
            wheelShown.put(m.name(), new Spin(15));
            wheelSign.put(m.name(), (m.mountedReversed() ? -1.0 : 1.0) / dt.gearRatio());
            generated.getChildren().add(wheel);
            // The motor: gearbox by the wheel, can, and the black encoder cap on the end.
            double inward = Math.signum(wy);
            generated.getChildren().addAll(
                    cylinderAlong('y', wx, wy - inward * (ww / 2 + 2.3 * IN), axleZ, 0.72 * IN, 3.6 * IN, plastic(GOBILDA_YELLOW)),
                    cylinderAlong('y', wx, wy - inward * (ww / 2 + 4.4 * IN), axleZ, 0.74 * IN, 0.6 * IN, plastic(0x1C1D1F)),
                    cylinderAlong('y', wx, wy - inward * (ww / 2 + 0.35 * IN), axleZ, 0.3 * IN, 0.7 * IN, metal(0x8D959C)));
        }
        // Control Hub and battery on the deck.
        double deckTop = axleZ + 0.95 * IN;
        generated.getChildren().addAll(
                box(-2 * IN, 2.2 * IN, deckTop + 0.55 * IN, 4.1 * IN, 3.9 * IN, 1.1 * IN, plastic(DARK)),
                box(-2 * IN, 2.2 * IN, deckTop + 1.12 * IN, 3.6 * IN, 3.4 * IN, 0.04 * IN, plastic(0xFF8F00)),
                box(-2.5 * IN, -3 * IN, deckTop + 0.8 * IN, 5.5 * IN, 3 * IN, 1.6 * IN, plastic(0x161718)),
                box(-2.5 * IN, -3 * IN, deckTop + 1.61 * IN, 4.2 * IN, 2.2 * IN, 0.03 * IN, matte(0x2E7D32)));

        if (cfg.intake() != null) {
            RobotConfig.Intake in = cfg.intake();
            double ix = (in.xMin() + in.xMax()) / 2 - 0.4 * IN, len = in.yMax() - in.yMin();
            intakeSpin = new Rotate(0, Rotate.Y_AXIS);
            Group roller = new Group(
                    cylinderAlong('y', 0, 0, 0, 0.25 * IN, len + 1 * IN, metal(0x8D959C)),
                    cylinderAlong('y', 0, 0, 0, in.rollerRadius(), len * 0.9, rubber(0x2E7D32)));
            for (int k = 0; k < 6; k++) {
                roller.getChildren().add(box(0, (k - 2.5) * len / 6, in.rollerRadius(), 0.3 * IN, len / 7, 0.25 * IN, rubber(0x5DAE61)));
            }
            roller.getTransforms().addAll(new Translate(ix, (in.yMin() + in.yMax()) / 2, 2.0 * IN), intakeSpin);
            generated.getChildren().add(roller);
            for (int side : new int[]{-1, 1}) {
                generated.getChildren().add(box(ix - 1.5 * IN, side * (len / 2 + 0.6 * IN), 2.5 * IN, 5 * IN, 0.25 * IN, 4 * IN, alu));
            }
        }
        if (cfg.hopper() != null) {
            generated.getChildren().add(box(0, 0, deckTop + 4 * IN, 6.5 * IN, 6.5 * IN, 0.15 * IN, plastic(0x9AA5AD)));
            // On the robot itself, so a CAD model shows what's in its hopper too.
            root.getChildren().add(hopperBalls);
        }
        if (cfg.launcher() != null) {
            RobotConfig.Launcher l = cfg.launcher();
            flywheelSpin = new Rotate(0, Rotate.Y_AXIS);
            Group fly = new Group(cylinderAlong('y', 0, 0, 0, l.wheelRadius(), 3.2 * IN, rubber(0x3A3B3E)));
            for (int k = 0; k < 4; k++) {
                double ang = k * Math.PI / 2;
                fly.getChildren().add(box(Math.cos(ang) * l.wheelRadius() * 0.6, 0, Math.sin(ang) * l.wheelRadius() * 0.6,
                        0.4 * IN, 3.3 * IN, 0.4 * IN, plastic(0xE53935)));
            }
            double fx = l.x() - l.wheelRadius() - 0.5 * IN;
            fly.getTransforms().addAll(new Translate(fx, l.y(), l.z() - 1 * IN), flywheelSpin);
            generated.getChildren().add(fly);
            for (int side : new int[]{-1, 1}) {
                generated.getChildren().add(box(fx, l.y() + side * 2.2 * IN, (l.z() + deckTop) / 2, 6 * IN, 0.25 * IN, l.z() - deckTop + 2 * IN, alu));
            }
            // Hood: a plate along the launch direction above the flywheel.
            Box hood = box(0, 0, 0, 4.5 * IN, 4.2 * IN, 0.15 * IN, plastic(0xC9D2D8));
            hood.getTransforms().addAll(new Translate(fx + 1.2 * IN, l.y(), l.z() + 1.6 * IN), new Rotate(-Math.toDegrees(l.angle()) + 90, Rotate.Y_AXIS));
            generated.getChildren().add(hood);
            // The kicker: a servo paddle behind the flywheel that flicks the staged ball into it.
            kickerTurn = new Rotate(0, Rotate.Y_AXIS);
            Group kicker = new Group(box(1.4 * IN, 0, 0, 2.8 * IN, 1.4 * IN, 0.35 * IN, plastic(0xFFB300)),
                    cylinderAlong('y', 0, 0, 0, 0.45 * IN, 1.8 * IN, plastic(DARK)));
            kicker.getTransforms().addAll(new Translate(fx - 3.4 * IN, l.y(), l.z() - 3.0 * IN), kickerTurn);
            generated.getChildren().add(kicker);
            // The transfer wheel that brings the next ball up from the hopper.
            if (cfg.hopper() != null && cfg.hopper().transfer() != null) {
                String t = cfg.hopper().transfer();
                transferIsServo = cfg.servos().stream().anyMatch(sv -> sv.name().equals(t));
                transferSpin = new Rotate(0, Rotate.Y_AXIS);
                Group wheel = new Group(cylinderAlong('y', 0, 0, 0, 1.0 * IN, 1.0 * IN, rubber(0x212121)));
                for (int k = 0; k < 3; k++) {
                    double ang = k * Math.PI / 3;
                    wheel.getChildren().add(box(0, 0, 0, 2.1 * IN * Math.abs(Math.cos(ang)) + 0.3 * IN, 1.1 * IN,
                            2.1 * IN * Math.abs(Math.sin(ang)) + 0.3 * IN, plastic(0x4CAF50)));
                }
                wheel.getTransforms().addAll(new Translate(fx / 2 - 1.0 * IN, l.y(), (deckTop + 5 * IN + l.z() - 1 * IN) / 2), transferSpin);
                generated.getChildren().add(wheel);
            }
        }
        for (RobotConfig.Gripper g : cfg.grippers()) {
            // Two lift rails from the deck up to the top of the robot, plus a moving carriage + claw.
            for (int side : new int[]{-1, 1}) {
                liftRails.getChildren().add(box(g.x() + 1.2 * IN, side * 3.0 * IN, cfg.height() / 2, 1.2 * IN, 1.2 * IN, cfg.height(), alu));
            }
            // The claw point (where it holds a piece) is the carriage's origin; the bar sits above it.
            Group claw = new Group(box(0, 0, 1.8 * IN, 1.6 * IN, 6.8 * IN, 1.4 * IN, plastic(0x4E5A63)));
            Box lf = box(0, 0, 0, 2.6 * IN, 0.35 * IN, 2.2 * IN, plastic(0x37474F));
            Box rf = box(0, 0, 0, 2.6 * IN, 0.35 * IN, 2.2 * IN, plastic(0x37474F));
            lf.getTransforms().setAll(leftFinger);
            rf.getTransforms().setAll(rightFinger);
            claw.getChildren().addAll(lf, rf);
            claw.getTransforms().add(carriage);
            generated.getChildren().addAll(liftRails, claw);
            break; // one claw drawn
        }
        buildOtherMechanisms(L, W, deckTop, alu);
        for (RobotConfig.OdometryPod p : cfg.odometryPods()) {
            char axis = p.lateral() ? 'x' : 'y';
            generated.getChildren().add(cylinderAlong(axis, p.x(), p.y(), p.wheelRadius(), p.wheelRadius(), 0.6 * IN, rubber(0x212121)));
        }
        for (RobotConfig.DistanceSensor d : cfg.distanceSensors()) {
            Box s = box(0, 0, 0, 0.5 * IN, 1.0 * IN, 0.8 * IN, plastic(0x111111));
            s.getTransforms().addAll(new Translate(d.x(), d.y(), 3 * IN), new Rotate(Math.toDegrees(d.heading()), Rotate.Z_AXIS));
            generated.getChildren().add(s);
        }
    }

    /**
     * Mechanisms nothing above draws (an arm, a slide without a claw, another spinning motor) as
     * simple stand-ins on the robot, so that everything that moves in the simulation moves here.
     * robot.jsonc doesn't say where they sit, so they're spread across the back half.
     */
    private void buildOtherMechanisms(double L, double W, double deckTop, javafx.scene.paint.PhongMaterial alu) {
        Set<String> drawn = new HashSet<>();
        if (cfg.intake() != null) drawn.add(cfg.intake().device());
        if (cfg.launcher() != null) drawn.add(cfg.launcher().flywheel());
        if (cfg.hopper() != null && cfg.hopper().transfer() != null) drawn.add(cfg.hopper().transfer());
        if (!cfg.grippers().isEmpty() && cfg.grippers().get(0).heightMechanism() != null) drawn.add(cfg.grippers().get(0).heightMechanism());
        List<RobotConfig.Mechanism> rest = cfg.mechanisms().stream().filter(m -> !drawn.contains(m.name())).toList();
        for (int i = 0; i < rest.size(); i++) {
            RobotConfig.Mechanism m = rest.get(i);
            double y = rest.size() == 1 ? 0 : (i / (rest.size() - 1.0) - 0.5) * W * 0.55;
            switch (m.type()) {
                case "arm" -> {
                    // Pivot at the back, arm pointing forward at 0 degrees, up for positive angles.
                    double len = Math.max(4 * IN, m.armLength());
                    Rotate turn = new Rotate(0, Rotate.Y_AXIS);
                    Group arm = new Group(box(len / 2, 0, 0, len, 1.0 * IN, 1.0 * IN, alu),
                            cylinderAlong('y', 0, 0, 0, 0.9 * IN, 1.8 * IN, plastic(DARK)),
                            box(len, 0, 0, 1.6 * IN, 2.4 * IN, 1.6 * IN, plastic(0x546E7A)));
                    arm.getTransforms().addAll(new Translate(-L / 2 + 2 * IN, y, Math.max(deckTop + 3 * IN, cfg.height() * 0.6)), turn);
                    generated.getChildren().add(arm);
                    mechanismMotion.put(m.name(), deg -> turn.setAngle(-deg));
                }
                case "slide" -> {
                    // A fixed stage and a moving one: up from the back (vertical) or out of the front.
                    double stage = m.vertical() ? Math.max(6 * IN, cfg.height() - deckTop) : Math.max(6 * IN, L * 0.6);
                    Translate ext = new Translate();
                    if (m.vertical()) {
                        double x = -L / 2 + 1.5 * IN, z0 = deckTop + stage / 2;
                        generated.getChildren().add(box(x, y, z0, 1.0 * IN, 2.6 * IN, stage, alu));
                        Group moving = new Group(box(x + 0.9 * IN, y, z0, 0.8 * IN, 2.0 * IN, stage, metal(0x8C98A0)),
                                box(x + 1.6 * IN, y, z0 + stage / 2, 2.2 * IN, 2.4 * IN, 0.6 * IN, plastic(0x546E7A)));
                        moving.getTransforms().add(ext);
                        generated.getChildren().add(moving);
                        mechanismMotion.put(m.name(), in -> ext.setZ(in * IN));
                    } else {
                        double z = deckTop + 1.2 * IN, x0 = L / 2 - stage / 2;
                        generated.getChildren().add(box(x0, y, z, stage, 2.6 * IN, 1.0 * IN, alu));
                        Group moving = new Group(box(x0, y, z + 0.9 * IN, stage, 2.0 * IN, 0.8 * IN, metal(0x8C98A0)),
                                box(x0 + stage / 2, y, z + 0.9 * IN, 0.6 * IN, 3.0 * IN, 2.0 * IN, plastic(0x546E7A)));
                        moving.getTransforms().add(ext);
                        generated.getChildren().add(moving);
                        mechanismMotion.put(m.name(), in -> ext.setX(in * IN));
                    }
                }
                default -> {
                    // Something spinning (a roller, a wheel): a disc with a stripe, so the turning shows.
                    Rotate spin = new Rotate(0, Rotate.Y_AXIS);
                    Spin shown = new Spin(40);
                    Group disc = new Group(cylinderAlong('y', 0, 0, 0, 1.4 * IN, 0.8 * IN, rubber(0x424242)),
                            box(0, 0, 0, 2.6 * IN, 0.9 * IN, 0.4 * IN, plastic(0xFFB300)));
                    disc.getTransforms().addAll(new Translate(-L / 4, y, deckTop + 2 * IN), spin);
                    generated.getChildren().add(disc);
                    mechanismMotion.put(m.name(), rev -> spin.setAngle(shown.next(rev * 360)));
                }
            }
        }
    }

    /** A mecanum wheel along y: aluminum side plates (with lightening holes, so it shows turning) and 10 angled rubber rollers. */
    private static Group mecanumWheel(double r, double width, boolean slash) {
        Group g = new Group();
        g.getChildren().add(cylinderAlong('y', 0, 0, 0, r * 0.28, width * 1.12, metal(0x5E666D)));
        for (int side : new int[]{-1, 1}) {
            double y = side * width * 0.5;
            g.getChildren().add(cylinderAlong('y', 0, y, 0, r * 0.74, 0.1 * IN, metal(0xBAC1C7)));
            for (int k = 0; k < 5; k++) {
                double a = 2 * Math.PI * k / 5;
                g.getChildren().add(cylinderAlong('y', Math.sin(a) * r * 0.5, y + side * 0.01 * IN, Math.cos(a) * r * 0.5, r * 0.12, 0.11 * IN, matte(0x1A1B1D)));
            }
        }
        int n = 10;
        for (int k = 0; k < n; k++) {
            double phi = 2 * Math.PI * k / n;
            double px = r * 0.84 * Math.sin(phi), pz = r * 0.84 * Math.cos(phi);
            double tx = Math.cos(phi), tz = -Math.sin(phi);
            double s = slash ? 1 : -1;
            double dx = (tx) * 0.7071, dy = s * 0.7071, dz = tz * 0.7071;
            double h = width * 0.62;
            g.getChildren().add(cylinder(new double[]{px - dx * h, -dy * h, pz - dz * h}, new double[]{px + dx * h, dy * h, pz + dz * h},
                    r * 0.17, rubber(0x2A2D31)));
        }
        return g;
    }

    // ------------------------------------------------------------------ CAD model

    void setCadModel(RobotModel model) {
        cad = model;
        cadHolder.getChildren().clear();
        if (model != null) cadHolder.getChildren().add(model.root);
        generated.setVisible(model == null || model.showGenerated);
        if (model != null) model.setAllianceColor(Color.web(String.format("#%06X", allianceColor(lastAlliance))));
    }

    /** The robot signs' color: red, blue, or gray before the robot has an alliance. */
    private static int allianceColor(String alliance) {
        return alliance.equals("red") ? 0xE53935 : alliance.equals("blue") ? 0x1E88E5 : 0x9E9E9E;
    }

    RobotModel cadModel() {
        return cad;
    }

    // ------------------------------------------------------------------ per frame

    void update(Snapshot s) {
        position.setX(s.x());
        position.setY(s.y());
        heading.setAngle(Math.toDegrees(s.heading()));
        // The overhead light throws the shadow a little to one side of the field, whichever way the robot faces.
        double lift = cfg.height() * 0.35, ox = lift * Shapes.SUN[0] / -Shapes.SUN[2], oy = lift * Shapes.SUN[1] / -Shapes.SUN[2];
        double cos = Math.cos(s.heading()), sin = Math.sin(s.heading());
        shadowAt.setX(cfg.frameOffsetX() + cos * ox + sin * oy);
        shadowAt.setY(cfg.frameOffsetY() - sin * ox + cos * oy);

        String alliance = s.alliance() == null ? "" : s.alliance();
        if (!alliance.equals(lastAlliance)) {
            lastAlliance = alliance;
            int color = allianceColor(alliance);
            for (Box b : signs) b.setMaterial(plastic(color));
            if (cad != null) cad.setAllianceColor(Color.web(String.format("#%06X", color)));
        }
        for (Snapshot.Motor m : s.motors()) {
            Rotate spin = wheelSpin.get(m.name());
            if (spin != null) spin.setAngle(wheelShown.get(m.name()).next(m.rawTicks() / m.ticksPerRev() * 360 * wheelSign.get(m.name())));
        }
        for (Snapshot.Mechanism m : s.mechanisms()) {
            if (m.type().equals("free")) {
                if (intakeSpin != null && cfg.intake() != null && m.name().equals(cfg.intake().device())) intakeSpin.setAngle(intakeShown.next(m.position() * 360));
                if (flywheelSpin != null && cfg.launcher() != null && m.name().equals(cfg.launcher().flywheel())) flywheelSpin.setAngle(flywheelShown.next(-m.position() * 360));
            }
            DoubleConsumer move = mechanismMotion.get(m.name());
            if (move != null) move.accept(m.position());
        }
        double clawPosition = Double.NaN;
        RobotConfig.Gripper gripper = cfg.grippers().isEmpty() ? null : cfg.grippers().get(0);
        for (Snapshot.Servo sv : s.servos()) {
            double deg = Math.toDegrees(sv.angle());
            if (kickerTurn != null && sv.name().equals(cfg.launcher().feederServo())) kickerTurn.setAngle(-deg * 0.5);
            if (transferSpin != null && transferIsServo && sv.name().equals(cfg.hopper().transfer())) transferSpin.setAngle(transferShown.next(deg));
            if (gripper != null && sv.name().equals(gripper.servo())) clawPosition = servoPosition(sv);
        }
        if (transferSpin != null && !transferIsServo) {
            for (Snapshot.Motor m : s.motors()) {
                if (m.name().equals(cfg.hopper().transfer())) transferSpin.setAngle(transferShown.next(m.rawTicks() / m.ticksPerRev() * 360));
            }
        }
        RobotPieceHandling.Parts parts = s.parts();
        if (parts != null) {
            if (!parts.hopperColors().equals(lastHopper)) {
                lastHopper = new ArrayList<>(parts.hopperColors());
                hopperBalls.getChildren().clear();
                double z = cfg.drivetrain().wheelRadius() + 0.95 * IN + 4 * IN + 1.5 * IN;
                for (int i = 0; i < lastHopper.size(); i++) {
                    double x = (i < 2 ? 1 : -1) * 1.6 * IN, y = (i % 2 == 0 ? 1 : -1) * 1.6 * IN;
                    Sphere ball = sphere(x, y, z, 1.4 * IN, glossy(lastHopper.get(i)));
                    hopperBalls.getChildren().add(ball);
                }
            }
            if (!parts.claws().isEmpty()) {
                RobotPieceHandling.ClawState c = parts.claws().get(0);
                carriage.setX(c.x());
                carriage.setY(c.y());
                carriage.setZ(Math.max(1 * IN, c.height()));
                // The fingers follow the servo as it travels: shut at closedBelow, wide open 0.3 past it.
                double open = Double.isNaN(clawPosition) ? (c.closed() ? 0 : 1)
                        : Math.max(0, Math.min(1, (clawPosition - gripper.closedBelow()) / 0.3));
                double gap = 1.7 * IN + 0.9 * IN * open;
                leftFinger.setX(-0.9 * IN);
                leftFinger.setY(gap);
                leftFinger.setZ(0);
                rightFinger.setX(-0.9 * IN);
                rightFinger.setY(-gap);
                rightFinger.setZ(0);
            }
        }
        if (cad != null) cad.update(s);
    }

    /** A positional servo's actual position (0..1) from its angle. */
    private double servoPosition(Snapshot.Servo sv) {
        for (RobotConfig.Servo def : cfg.servos()) {
            if (def.name().equals(sv.name()) && def.rangeRad() > 0) return sv.angle() / def.rangeRad() + 0.5;
        }
        return Double.NaN;
    }
}
