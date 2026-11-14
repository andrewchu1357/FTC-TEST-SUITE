package org.ftctestbed.shim;

/** Thrown by OpMode.terminateOpModeNow(); the simulator's OpMode runner treats it as a clean stop. */
public class OpModeStopRequest extends RuntimeException {
    public OpModeStopRequest() {
        super("OpMode terminated by terminateOpModeNow()");
    }
}
