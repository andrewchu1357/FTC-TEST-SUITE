package android.content.res;

/** The app's resources. The simulator has none: every lookup returns 0 ("not found"). */
public class Resources {
    public int getIdentifier(String name, String defType, String defPackage) {
        return 0;
    }
}
