package com.qualcomm.robotcore.hardware;

import org.ftctestbed.shim.SimHooks;

import java.util.ArrayList;

/**
 * Gamepad state as seen by an OpMode. Field names and types match the real SDK
 * (libraries such as FTCLib read these fields directly).
 *
 * The *WasPressed()/*WasReleased() edge detectors return true once per press/release
 * since the previous call, like the SDK 10.x versions. Since SDK 11.1 the triggers can be read
 * as buttons too: left_trigger_pressed is true at or past the trigger threshold (0.5 unless
 * setTriggerThreshold() changes it), with its own edge detectors.
 */
public class Gamepad {
    public static final int ID_UNASSOCIATED = -1;
    public static final int ID_SYNTHETIC = -2;
    public static final int RUMBLE_DURATION_CONTINUOUS = -1;
    public static final int LED_DURATION_CONTINUOUS = -1;
    public static final float DEFAULT_TRIGGER_THRESHOLD = 0.5f;

    /** Controller model, as reported by the Driver Station. PS4 and PS5 controllers both show up as Sony. */
    public enum Type {
        UNKNOWN, LOGITECH_F310, XBOX_360, SONY_PS4, SONY_PS4_SUPPORTED_BY_KERNEL
    }

    /** A rumble pattern: a list of (large motor, small motor, duration) steps. */
    public static class RumbleEffect {
        public static class Step {
            public int large;
            public int small;
            public int duration;
        }

        public int user;
        public final ArrayList<Step> steps;

        private RumbleEffect(ArrayList<Step> steps) {
            this.steps = steps;
        }

        public static class Builder {
            private final ArrayList<Step> steps = new ArrayList<Step>();

            /** rumble1 drives the large (left) motor, rumble2 the small (right) one, both 0..1. */
            public Builder addStep(double rumble1, double rumble2, int durationMs) {
                Step step = new Step();
                step.large = (int) Math.round(Math.max(0, Math.min(1, rumble1)) * 255);
                step.small = (int) Math.round(Math.max(0, Math.min(1, rumble2)) * 255);
                step.duration = durationMs;
                steps.add(step);
                return this;
            }

            public RumbleEffect build() {
                return new RumbleEffect(new ArrayList<Step>(steps));
            }
        }
    }

    /** An LED pattern for PS4/PS5 controllers. */
    public static class LedEffect {
        public static class Step {
            public int r;
            public int g;
            public int b;
            public int duration;
        }

        public int user;
        public final ArrayList<Step> steps;
        public final boolean repeating;

        private LedEffect(ArrayList<Step> steps, boolean repeating) {
            this.steps = steps;
            this.repeating = repeating;
        }

        public static class Builder {
            private final ArrayList<Step> steps = new ArrayList<Step>();
            private boolean repeating;

            public Builder addStep(double r, double g, double b, int durationMs) {
                Step step = new Step();
                step.r = (int) Math.round(Math.max(0, Math.min(1, r)) * 255);
                step.g = (int) Math.round(Math.max(0, Math.min(1, g)) * 255);
                step.b = (int) Math.round(Math.max(0, Math.min(1, b)) * 255);
                step.duration = durationMs;
                steps.add(step);
                return this;
            }

            public Builder setRepeating(boolean repeating) {
                this.repeating = repeating;
                return this;
            }

            public LedEffect build() {
                return new LedEffect(new ArrayList<Step>(steps), repeating);
            }
        }
    }

    public volatile float left_stick_x = 0f;
    public volatile float left_stick_y = 0f;
    public volatile float right_stick_x = 0f;
    public volatile float right_stick_y = 0f;
    public volatile float left_trigger = 0f;
    public volatile float right_trigger = 0f;
    public volatile boolean left_trigger_pressed = false;
    public volatile boolean right_trigger_pressed = false;

    public volatile boolean a = false;
    public volatile boolean b = false;
    public volatile boolean x = false;
    public volatile boolean y = false;
    public volatile boolean dpad_up = false;
    public volatile boolean dpad_down = false;
    public volatile boolean dpad_left = false;
    public volatile boolean dpad_right = false;
    public volatile boolean left_bumper = false;
    public volatile boolean right_bumper = false;
    public volatile boolean left_stick_button = false;
    public volatile boolean right_stick_button = false;
    public volatile boolean guide = false;
    public volatile boolean start = false;
    public volatile boolean back = false;
    public volatile boolean cross = false;
    public volatile boolean circle = false;
    public volatile boolean square = false;
    public volatile boolean triangle = false;
    public volatile boolean share = false;
    public volatile boolean options = false;
    public volatile boolean ps = false;
    public volatile boolean touchpad = false;

    public volatile boolean touchpad_finger_1 = false;
    public volatile boolean touchpad_finger_2 = false;
    public volatile float touchpad_finger_1_x = 0f;
    public volatile float touchpad_finger_1_y = 0f;
    public volatile float touchpad_finger_2_x = 0f;
    public volatile float touchpad_finger_2_y = 0f;

    public int id = ID_UNASSOCIATED;
    public long timestamp = 0;
    public Type type = Type.UNKNOWN;

    protected float joystickDeadzone = 0.2f;
    private volatile float triggerThreshold = DEFAULT_TRIGGER_THRESHOLD;

    private volatile RumbleEffect rumbleEffect;
    private volatile long rumbleStartNanos;
    private volatile boolean rumbleContinuous = false;
    private volatile LedEffect ledEffect;
    private volatile long ledStartNanos;

    private boolean aPressedLatch, aReleasedLatch;
    private boolean bPressedLatch, bReleasedLatch;
    private boolean xPressedLatch, xReleasedLatch;
    private boolean yPressedLatch, yReleasedLatch;
    private boolean dpadUpPressedLatch, dpadUpReleasedLatch;
    private boolean dpadDownPressedLatch, dpadDownReleasedLatch;
    private boolean dpadLeftPressedLatch, dpadLeftReleasedLatch;
    private boolean dpadRightPressedLatch, dpadRightReleasedLatch;
    private boolean leftBumperPressedLatch, leftBumperReleasedLatch;
    private boolean rightBumperPressedLatch, rightBumperReleasedLatch;
    private boolean leftStickButtonPressedLatch, leftStickButtonReleasedLatch;
    private boolean rightStickButtonPressedLatch, rightStickButtonReleasedLatch;
    private boolean guidePressedLatch, guideReleasedLatch;
    private boolean startPressedLatch, startReleasedLatch;
    private boolean backPressedLatch, backReleasedLatch;
    private boolean crossPressedLatch, crossReleasedLatch;
    private boolean circlePressedLatch, circleReleasedLatch;
    private boolean squarePressedLatch, squareReleasedLatch;
    private boolean trianglePressedLatch, triangleReleasedLatch;
    private boolean sharePressedLatch, shareReleasedLatch;
    private boolean optionsPressedLatch, optionsReleasedLatch;
    private boolean psPressedLatch, psReleasedLatch;
    private boolean touchpadPressedLatch, touchpadReleasedLatch;
    private boolean leftTriggerPressedLatch, leftTriggerReleasedLatch;
    private boolean rightTriggerPressedLatch, rightTriggerReleasedLatch;

    public Gamepad() {}

    /** Plain state copy, like the real SDK (used for "previous gamepad" edge detection patterns). */
    public synchronized void copy(Gamepad other) {
        left_stick_x = other.left_stick_x;
        left_stick_y = other.left_stick_y;
        right_stick_x = other.right_stick_x;
        right_stick_y = other.right_stick_y;
        left_trigger = other.left_trigger;
        right_trigger = other.right_trigger;
        left_trigger_pressed = other.left_trigger_pressed;
        right_trigger_pressed = other.right_trigger_pressed;
        a = other.a;
        b = other.b;
        x = other.x;
        y = other.y;
        dpad_up = other.dpad_up;
        dpad_down = other.dpad_down;
        dpad_left = other.dpad_left;
        dpad_right = other.dpad_right;
        left_bumper = other.left_bumper;
        right_bumper = other.right_bumper;
        left_stick_button = other.left_stick_button;
        right_stick_button = other.right_stick_button;
        guide = other.guide;
        start = other.start;
        back = other.back;
        cross = other.cross;
        circle = other.circle;
        square = other.square;
        triangle = other.triangle;
        share = other.share;
        options = other.options;
        ps = other.ps;
        touchpad = other.touchpad;
        touchpad_finger_1 = other.touchpad_finger_1;
        touchpad_finger_2 = other.touchpad_finger_2;
        touchpad_finger_1_x = other.touchpad_finger_1_x;
        touchpad_finger_1_y = other.touchpad_finger_1_y;
        touchpad_finger_2_x = other.touchpad_finger_2_x;
        touchpad_finger_2_y = other.touchpad_finger_2_y;
        id = other.id;
        timestamp = other.timestamp;
        type = other.type;
    }

    /**
     * Simulator entry point: applies a new input state, recording press/release edges and
     * keeping the Xbox/PlayStation aliases (a/cross, b/circle, ...) in sync like the SDK does.
     */
    public synchronized void simApply(Gamepad s) {
        // Each Xbox/PlayStation pair follows whichever of the two names changed.
        s.a = s.cross = alias(a, s.a, cross, s.cross);
        s.b = s.circle = alias(b, s.b, circle, s.circle);
        s.x = s.square = alias(x, s.x, square, s.square);
        s.y = s.triangle = alias(y, s.y, triangle, s.triangle);
        s.back = s.share = alias(back, s.back, share, s.share);
        s.start = s.options = alias(start, s.start, options, s.options);
        s.guide = s.ps = alias(guide, s.guide, ps, s.ps);
        // The SDK works out the trigger buttons on the robot, with this gamepad's threshold.
        s.left_trigger_pressed = s.left_trigger >= triggerThreshold;
        s.right_trigger_pressed = s.right_trigger >= triggerThreshold;
        if (s.left_trigger_pressed && !left_trigger_pressed) leftTriggerPressedLatch = true;
        if (!s.left_trigger_pressed && left_trigger_pressed) leftTriggerReleasedLatch = true;
        if (s.right_trigger_pressed && !right_trigger_pressed) rightTriggerPressedLatch = true;
        if (!s.right_trigger_pressed && right_trigger_pressed) rightTriggerReleasedLatch = true;
        if (s.a && !a) aPressedLatch = true;
        if (!s.a && a) aReleasedLatch = true;
        if (s.b && !b) bPressedLatch = true;
        if (!s.b && b) bReleasedLatch = true;
        if (s.x && !x) xPressedLatch = true;
        if (!s.x && x) xReleasedLatch = true;
        if (s.y && !y) yPressedLatch = true;
        if (!s.y && y) yReleasedLatch = true;
        if (s.dpad_up && !dpad_up) dpadUpPressedLatch = true;
        if (!s.dpad_up && dpad_up) dpadUpReleasedLatch = true;
        if (s.dpad_down && !dpad_down) dpadDownPressedLatch = true;
        if (!s.dpad_down && dpad_down) dpadDownReleasedLatch = true;
        if (s.dpad_left && !dpad_left) dpadLeftPressedLatch = true;
        if (!s.dpad_left && dpad_left) dpadLeftReleasedLatch = true;
        if (s.dpad_right && !dpad_right) dpadRightPressedLatch = true;
        if (!s.dpad_right && dpad_right) dpadRightReleasedLatch = true;
        if (s.left_bumper && !left_bumper) leftBumperPressedLatch = true;
        if (!s.left_bumper && left_bumper) leftBumperReleasedLatch = true;
        if (s.right_bumper && !right_bumper) rightBumperPressedLatch = true;
        if (!s.right_bumper && right_bumper) rightBumperReleasedLatch = true;
        if (s.left_stick_button && !left_stick_button) leftStickButtonPressedLatch = true;
        if (!s.left_stick_button && left_stick_button) leftStickButtonReleasedLatch = true;
        if (s.right_stick_button && !right_stick_button) rightStickButtonPressedLatch = true;
        if (!s.right_stick_button && right_stick_button) rightStickButtonReleasedLatch = true;
        if (s.guide && !guide) guidePressedLatch = true;
        if (!s.guide && guide) guideReleasedLatch = true;
        if (s.start && !start) startPressedLatch = true;
        if (!s.start && start) startReleasedLatch = true;
        if (s.back && !back) backPressedLatch = true;
        if (!s.back && back) backReleasedLatch = true;
        if (s.cross && !cross) crossPressedLatch = true;
        if (!s.cross && cross) crossReleasedLatch = true;
        if (s.circle && !circle) circlePressedLatch = true;
        if (!s.circle && circle) circleReleasedLatch = true;
        if (s.square && !square) squarePressedLatch = true;
        if (!s.square && square) squareReleasedLatch = true;
        if (s.triangle && !triangle) trianglePressedLatch = true;
        if (!s.triangle && triangle) triangleReleasedLatch = true;
        if (s.share && !share) sharePressedLatch = true;
        if (!s.share && share) shareReleasedLatch = true;
        if (s.options && !options) optionsPressedLatch = true;
        if (!s.options && options) optionsReleasedLatch = true;
        if (s.ps && !ps) psPressedLatch = true;
        if (!s.ps && ps) psReleasedLatch = true;
        if (s.touchpad && !touchpad) touchpadPressedLatch = true;
        if (!s.touchpad && touchpad) touchpadReleasedLatch = true;
        copy(s);
        timestamp = SimHooks.nanoTime();
    }

    private static boolean alias(boolean oldXbox, boolean newXbox, boolean oldPs, boolean newPs) {
        if (newXbox != oldXbox) return newXbox;
        if (newPs != oldPs) return newPs;
        return newXbox || newPs;
    }

    public synchronized void reset() {
        left_stick_x = left_stick_y = right_stick_x = right_stick_y = 0f;
        left_trigger = right_trigger = 0f;
        left_trigger_pressed = right_trigger_pressed = false;
        a = false;
        b = false;
        x = false;
        y = false;
        dpad_up = false;
        dpad_down = false;
        dpad_left = false;
        dpad_right = false;
        left_bumper = false;
        right_bumper = false;
        left_stick_button = false;
        right_stick_button = false;
        guide = false;
        start = false;
        back = false;
        cross = false;
        circle = false;
        square = false;
        triangle = false;
        share = false;
        options = false;
        ps = false;
        touchpad = false;
    }

    public void setJoystickDeadzone(float deadzone) {
        if (deadzone < 0 || deadzone > 1) throw new IllegalArgumentException("deadzone cannot be greater than max joystick value");
        joystickDeadzone = deadzone;
    }

    public boolean atRest() {
        return Math.abs(left_stick_x) < joystickDeadzone && Math.abs(left_stick_y) < joystickDeadzone
                && Math.abs(right_stick_x) < joystickDeadzone && Math.abs(right_stick_y) < joystickDeadzone
                && left_trigger == 0f && right_trigger == 0f;
    }

    public int getGamepadId() { return id; }

    /** How far a trigger must be pulled to count as pressed (SDK 11.1). */
    public void setTriggerThreshold(float threshold) { triggerThreshold = threshold; }

    public float getTriggerThreshold() { return triggerThreshold; }

    public Type type() { return type; }

    /** The joystick deadzone the OpMode set (simulator use: applied to physical controllers). */
    public float simJoystickDeadzone() { return joystickDeadzone; }

    // ---- Rumble & LED (recorded so the viewer can show them and drive a real controller) ----

    public void rumble(int durationMs) { rumble(1.0, 1.0, durationMs); }

    public void rumble(double rumble1, double rumble2, int durationMs) {
        startRumble(new RumbleEffect.Builder().addStep(rumble1, rumble2, Math.max(0, durationMs)).build(),
                durationMs == RUMBLE_DURATION_CONTINUOUS);
    }

    public void rumbleBlips(int count) {
        RumbleEffect.Builder b = new RumbleEffect.Builder();
        for (int i = 0; i < count; i++) b.addStep(0, 1.0, 75).addStep(0, 0, 100);
        runRumbleEffect(b.build());
    }

    public void runRumbleEffect(RumbleEffect effect) { startRumble(effect, false); }

    private void startRumble(RumbleEffect effect, boolean continuous) {
        rumbleContinuous = continuous;
        rumbleEffect = effect;
        rumbleStartNanos = SimHooks.nanoTime();
    }

    public void stopRumble() {
        rumbleContinuous = false;
        rumbleEffect = null;
    }

    public boolean isRumbling() {
        double[] r = simRumble();
        return r[0] > 0 || r[1] > 0;
    }

    /** Current rumble motor levels {large, small}, 0..1. */
    public double[] simRumble() {
        RumbleEffect e = rumbleEffect;
        if (e == null || e.steps.isEmpty()) return new double[] {0, 0};
        if (rumbleContinuous) {
            RumbleEffect.Step s = e.steps.get(0);
            return new double[] {s.large / 255.0, s.small / 255.0};
        }
        long ms = (SimHooks.nanoTime() - rumbleStartNanos) / 1_000_000L;
        for (RumbleEffect.Step s : e.steps) {
            if (ms < s.duration) return new double[] {s.large / 255.0, s.small / 255.0};
            ms -= s.duration;
        }
        return new double[] {0, 0};
    }

    public void setLedColor(double r, double g, double b, int durationMs) {
        runLedEffect(new LedEffect.Builder().addStep(r, g, b, durationMs).build());
    }

    public void runLedEffect(LedEffect effect) {
        ledEffect = effect;
        ledStartNanos = SimHooks.nanoTime();
    }

    /** Current LED color {r, g, b}, 0..1. The Driver Station's default is blue. */
    public double[] simGetLedColor() {
        LedEffect e = ledEffect;
        if (e == null || e.steps.isEmpty()) return new double[] {0, 0, 1};
        long total = 0;
        for (LedEffect.Step s : e.steps) {
            if (s.duration == LED_DURATION_CONTINUOUS) { total = -1; break; }
            total += s.duration;
        }
        long ms = (SimHooks.nanoTime() - ledStartNanos) / 1_000_000L;
        if (e.repeating && total > 0) ms %= total;
        LedEffect.Step current = e.steps.get(e.steps.size() - 1);
        for (LedEffect.Step s : e.steps) {
            if (s.duration == LED_DURATION_CONTINUOUS || ms < s.duration) { current = s; break; }
            ms -= s.duration;
        }
        return new double[] {current.r / 255.0, current.g / 255.0, current.b / 255.0};
    }

    // ---- Edge detection ----

    public synchronized boolean aWasPressed() { boolean v = aPressedLatch; aPressedLatch = false; return v; }
    public synchronized boolean aWasReleased() { boolean v = aReleasedLatch; aReleasedLatch = false; return v; }

    public synchronized boolean bWasPressed() { boolean v = bPressedLatch; bPressedLatch = false; return v; }
    public synchronized boolean bWasReleased() { boolean v = bReleasedLatch; bReleasedLatch = false; return v; }

    public synchronized boolean xWasPressed() { boolean v = xPressedLatch; xPressedLatch = false; return v; }
    public synchronized boolean xWasReleased() { boolean v = xReleasedLatch; xReleasedLatch = false; return v; }

    public synchronized boolean yWasPressed() { boolean v = yPressedLatch; yPressedLatch = false; return v; }
    public synchronized boolean yWasReleased() { boolean v = yReleasedLatch; yReleasedLatch = false; return v; }

    public synchronized boolean dpadUpWasPressed() { boolean v = dpadUpPressedLatch; dpadUpPressedLatch = false; return v; }
    public synchronized boolean dpadUpWasReleased() { boolean v = dpadUpReleasedLatch; dpadUpReleasedLatch = false; return v; }

    public synchronized boolean dpadDownWasPressed() { boolean v = dpadDownPressedLatch; dpadDownPressedLatch = false; return v; }
    public synchronized boolean dpadDownWasReleased() { boolean v = dpadDownReleasedLatch; dpadDownReleasedLatch = false; return v; }

    public synchronized boolean dpadLeftWasPressed() { boolean v = dpadLeftPressedLatch; dpadLeftPressedLatch = false; return v; }
    public synchronized boolean dpadLeftWasReleased() { boolean v = dpadLeftReleasedLatch; dpadLeftReleasedLatch = false; return v; }

    public synchronized boolean dpadRightWasPressed() { boolean v = dpadRightPressedLatch; dpadRightPressedLatch = false; return v; }
    public synchronized boolean dpadRightWasReleased() { boolean v = dpadRightReleasedLatch; dpadRightReleasedLatch = false; return v; }

    public synchronized boolean leftBumperWasPressed() { boolean v = leftBumperPressedLatch; leftBumperPressedLatch = false; return v; }
    public synchronized boolean leftBumperWasReleased() { boolean v = leftBumperReleasedLatch; leftBumperReleasedLatch = false; return v; }

    public synchronized boolean rightBumperWasPressed() { boolean v = rightBumperPressedLatch; rightBumperPressedLatch = false; return v; }
    public synchronized boolean rightBumperWasReleased() { boolean v = rightBumperReleasedLatch; rightBumperReleasedLatch = false; return v; }

    public synchronized boolean leftStickButtonWasPressed() { boolean v = leftStickButtonPressedLatch; leftStickButtonPressedLatch = false; return v; }
    public synchronized boolean leftStickButtonWasReleased() { boolean v = leftStickButtonReleasedLatch; leftStickButtonReleasedLatch = false; return v; }

    public synchronized boolean rightStickButtonWasPressed() { boolean v = rightStickButtonPressedLatch; rightStickButtonPressedLatch = false; return v; }
    public synchronized boolean rightStickButtonWasReleased() { boolean v = rightStickButtonReleasedLatch; rightStickButtonReleasedLatch = false; return v; }

    public synchronized boolean guideWasPressed() { boolean v = guidePressedLatch; guidePressedLatch = false; return v; }
    public synchronized boolean guideWasReleased() { boolean v = guideReleasedLatch; guideReleasedLatch = false; return v; }

    public synchronized boolean startWasPressed() { boolean v = startPressedLatch; startPressedLatch = false; return v; }
    public synchronized boolean startWasReleased() { boolean v = startReleasedLatch; startReleasedLatch = false; return v; }

    public synchronized boolean backWasPressed() { boolean v = backPressedLatch; backPressedLatch = false; return v; }
    public synchronized boolean backWasReleased() { boolean v = backReleasedLatch; backReleasedLatch = false; return v; }

    public synchronized boolean crossWasPressed() { boolean v = crossPressedLatch; crossPressedLatch = false; return v; }
    public synchronized boolean crossWasReleased() { boolean v = crossReleasedLatch; crossReleasedLatch = false; return v; }

    public synchronized boolean circleWasPressed() { boolean v = circlePressedLatch; circlePressedLatch = false; return v; }
    public synchronized boolean circleWasReleased() { boolean v = circleReleasedLatch; circleReleasedLatch = false; return v; }

    public synchronized boolean squareWasPressed() { boolean v = squarePressedLatch; squarePressedLatch = false; return v; }
    public synchronized boolean squareWasReleased() { boolean v = squareReleasedLatch; squareReleasedLatch = false; return v; }

    public synchronized boolean triangleWasPressed() { boolean v = trianglePressedLatch; trianglePressedLatch = false; return v; }
    public synchronized boolean triangleWasReleased() { boolean v = triangleReleasedLatch; triangleReleasedLatch = false; return v; }

    public synchronized boolean shareWasPressed() { boolean v = sharePressedLatch; sharePressedLatch = false; return v; }
    public synchronized boolean shareWasReleased() { boolean v = shareReleasedLatch; shareReleasedLatch = false; return v; }

    public synchronized boolean optionsWasPressed() { boolean v = optionsPressedLatch; optionsPressedLatch = false; return v; }
    public synchronized boolean optionsWasReleased() { boolean v = optionsReleasedLatch; optionsReleasedLatch = false; return v; }

    public synchronized boolean psWasPressed() { boolean v = psPressedLatch; psPressedLatch = false; return v; }
    public synchronized boolean psWasReleased() { boolean v = psReleasedLatch; psReleasedLatch = false; return v; }

    public synchronized boolean touchpadWasPressed() { boolean v = touchpadPressedLatch; touchpadPressedLatch = false; return v; }
    public synchronized boolean touchpadWasReleased() { boolean v = touchpadReleasedLatch; touchpadReleasedLatch = false; return v; }

    public synchronized boolean leftTriggerWasPressed() { boolean v = leftTriggerPressedLatch; leftTriggerPressedLatch = false; return v; }
    public synchronized boolean leftTriggerWasReleased() { boolean v = leftTriggerReleasedLatch; leftTriggerReleasedLatch = false; return v; }

    public synchronized boolean rightTriggerWasPressed() { boolean v = rightTriggerPressedLatch; rightTriggerPressedLatch = false; return v; }
    public synchronized boolean rightTriggerWasReleased() { boolean v = rightTriggerReleasedLatch; rightTriggerReleasedLatch = false; return v; }

    /** Forgets every press and release so far, so the *WasPressed()/*WasReleased() calls start fresh. */
    public synchronized void resetEdgeDetection() {
        aPressedLatch = aReleasedLatch = bPressedLatch = bReleasedLatch = false;
        xPressedLatch = xReleasedLatch = yPressedLatch = yReleasedLatch = false;
        dpadUpPressedLatch = dpadUpReleasedLatch = dpadDownPressedLatch = dpadDownReleasedLatch = false;
        dpadLeftPressedLatch = dpadLeftReleasedLatch = dpadRightPressedLatch = dpadRightReleasedLatch = false;
        leftBumperPressedLatch = leftBumperReleasedLatch = rightBumperPressedLatch = rightBumperReleasedLatch = false;
        leftStickButtonPressedLatch = leftStickButtonReleasedLatch = false;
        rightStickButtonPressedLatch = rightStickButtonReleasedLatch = false;
        guidePressedLatch = guideReleasedLatch = startPressedLatch = startReleasedLatch = false;
        backPressedLatch = backReleasedLatch = crossPressedLatch = crossReleasedLatch = false;
        circlePressedLatch = circleReleasedLatch = squarePressedLatch = squareReleasedLatch = false;
        trianglePressedLatch = triangleReleasedLatch = sharePressedLatch = shareReleasedLatch = false;
        optionsPressedLatch = optionsReleasedLatch = psPressedLatch = psReleasedLatch = false;
        touchpadPressedLatch = touchpadReleasedLatch = false;
        leftTriggerPressedLatch = leftTriggerReleasedLatch = rightTriggerPressedLatch = rightTriggerReleasedLatch = false;
    }

    @Override
    public String toString() {
        return String.format("ID: %d user: lx: %.2f ly: %.2f rx: %.2f ry: %.2f lt: %.2f rt: %.2f%s%s%s%s%s%s%s%s%s%s%s%s%s%s%s",
                id, left_stick_x, left_stick_y, right_stick_x, right_stick_y, left_trigger, right_trigger,
                a ? " a" : "", b ? " b" : "", x ? " x" : "", y ? " y" : "",
                dpad_up ? " dpad_up" : "", dpad_down ? " dpad_down" : "", dpad_left ? " dpad_left" : "", dpad_right ? " dpad_right" : "",
                left_bumper ? " lb" : "", right_bumper ? " rb" : "", left_stick_button ? " lsb" : "", right_stick_button ? " rsb" : "",
                start ? " start" : "", back ? " back" : "", guide ? " guide" : "");
    }
}
