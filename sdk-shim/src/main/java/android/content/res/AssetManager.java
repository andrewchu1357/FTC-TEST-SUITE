package android.content.res;

import java.io.IOException;
import java.io.InputStream;

/** An Android app's bundled files. The simulator has none. */
public abstract class AssetManager {
    public abstract String[] list(String path) throws IOException;

    public abstract InputStream open(String fileName) throws IOException;
}
