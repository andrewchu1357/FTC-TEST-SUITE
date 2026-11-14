package org.ftctestbed.viewer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Which controller drives which robot's gamepad, with and without a partner robot. */
class ControllerSlotsTest {
    @Test
    void withOneRobotTheSecondControllerIsGamepad2LikeTheDriverStation() {
        ControllerSlots s = new ControllerSlots();
        assertEquals(0, s.connected(7));
        assertEquals(1, s.connected(3));
        assertEquals(-1, s.connected(5), "only two gamepads");
        assertEquals("gamepad2", s.name(1));
        assertFalse(s.assign(5, 2), "Start+X means nothing without a partner");
        assertTrue(s.assign(5, 0), "Start+A");
        assertEquals(5, s.padIn(0));
        assertFalse(s.isAssigned(7), "the controller it replaced is unassigned");
    }

    @Test
    void withAPartnerTheSecondControllerDrivesRobot2() {
        ControllerSlots s = new ControllerSlots();
        s.setRobots(2);
        assertEquals(0, s.connected(1), "robot 1's gamepad1");
        assertEquals(2, s.connected(2), "robot 2's gamepad1");
        assertEquals(1, s.connected(3), "then robot 1's gamepad2");
        assertEquals(3, s.connected(4));
        assertEquals("Robot 2 gamepad1", s.name(2));

        // Start+Y: controller 1 becomes robot 2's gamepad2 and leaves robot 1's gamepad1 empty.
        assertTrue(s.assign(1, 3));
        assertEquals(1, s.padIn(3));
        assertEquals(-1, s.padIn(0));
        assertFalse(s.isAssigned(4));
        s.disconnected(2);
        assertEquals(-1, s.padIn(2));
        assertEquals(0, s.connected(9), "a new controller fills robot 1's gamepad1 first");
    }

    @Test
    void turningThePartnerOnGivesItTheSecondControllerAndOffGivesItBack() {
        ControllerSlots s = new ControllerSlots();
        s.connected(10);
        s.connected(11);
        assertEquals(11, s.padIn(1));
        s.setRobots(2);
        assertEquals(10, s.padIn(0));
        assertEquals(11, s.padIn(2), "the second controller now drives robot 2");
        assertEquals(-1, s.padIn(1));
        s.setRobots(1);
        assertEquals(10, s.padIn(0));
        assertEquals(11, s.padIn(1), "back to gamepad2");
        assertEquals(-1, s.padIn(2));
    }
}
