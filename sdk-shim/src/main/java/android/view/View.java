package android.view;

/**
 * A view in the Robot Controller app's screen. The simulator has no screen: code that changes
 * one (the SensorColor sample turns the background to the color it sees) runs and does nothing.
 */
public class View {
    public boolean post(Runnable action) {
        return true;
    }

    public void setBackgroundColor(int color) {}

    public void setVisibility(int visibility) {}

    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;
}
