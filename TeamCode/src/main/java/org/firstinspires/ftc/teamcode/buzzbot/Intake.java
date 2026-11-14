package org.firstinspires.ftc.teamcode.buzzbot;

import android.graphics.Color;

import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DistanceSensor;
import com.qualcomm.robotcore.hardware.NormalizedColorSensor;
import com.qualcomm.robotcore.hardware.NormalizedRGBA;
import com.qualcomm.robotcore.util.ElapsedTime;

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;

/**
 * Front roller intake, the hopper transfer, and the hopper color sensor. If the ball waiting
 * at the launcher is the opponent's NECTAR (rule G408: don't control it), the intake runs
 * backward to spit it out.
 */
public class Intake {
    public enum Ball { NONE, POLLEN, RED_NECTAR, BLUE_NECTAR }

    private final DcMotorEx roller;
    private final CRServo transfer;
    private final NormalizedColorSensor color;
    private Alliance alliance;
    private final ElapsedTime ejectTimer = new ElapsedTime();
    private boolean ejecting;
    private double requested;

    public Intake(DcMotorEx roller, CRServo transfer, NormalizedColorSensor color, Alliance alliance) {
        this.roller = roller;
        this.transfer = transfer;
        this.color = color;
        this.alliance = alliance;
        roller.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        color.setGain(3);
    }

    /** +1 = pull in, -1 = spit out, 0 = stop. */
    public void set(double power) {
        requested = power;
    }

    /** What the color sensor sees at the launcher. */
    public Ball staged() {
        if (((DistanceSensor) color).getDistance(DistanceUnit.CM) > 3) return Ball.NONE;
        NormalizedRGBA c = color.getNormalizedColors();
        float[] hsv = new float[3];
        Color.colorToHSV(c.toColor(), hsv);
        float hue = hsv[0];
        if (hue > 35 && hue < 75) return Ball.POLLEN;
        if (hue > 180 && hue < 260) return Ball.BLUE_NECTAR;
        if (hue < 20 || hue > 330) return Ball.RED_NECTAR;
        return Ball.NONE;
    }

    public boolean opponentNectarStaged() {
        Ball b = staged();
        return (alliance == Alliance.RED && b == Ball.BLUE_NECTAR) || (alliance == Alliance.BLUE && b == Ball.RED_NECTAR);
    }

    public void update() {
        if (!ejecting && opponentNectarStaged()) {
            ejecting = true;
            ejectTimer.reset();
        }
        if (ejecting && ejectTimer.seconds() > 1.2) ejecting = false;
        double power = ejecting ? -1 : requested;
        roller.setPower(power);
        // Keep the next ball moving up to the launcher, except while spitting balls out.
        transfer.setPower(power < 0 ? -1 : 1);
    }

    public void setAlliance(Alliance alliance) {
        this.alliance = alliance;
    }

    public boolean isEjecting() {
        return ejecting;
    }
}
