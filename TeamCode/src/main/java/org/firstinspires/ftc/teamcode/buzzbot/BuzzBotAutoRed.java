package org.firstinspires.ftc.teamcode.buzzbot;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;

@Autonomous(name = "BuzzBot Auto Red", group = "BuzzBot", preselectTeleOp = "BuzzBot TeleOp")
public class BuzzBotAutoRed extends BuzzBotAuto {
    @Override
    protected Alliance alliance() {
        return Alliance.RED;
    }
}
