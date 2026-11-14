package org.firstinspires.ftc.teamcode.buzzbot;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;

@Autonomous(name = "BuzzBot Auto Blue", group = "BuzzBot", preselectTeleOp = "BuzzBot TeleOp")
public class BuzzBotAutoBlue extends BuzzBotAuto {
    @Override
    protected Alliance alliance() {
        return Alliance.BLUE;
    }
}
