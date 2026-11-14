package org.firstinspires.ftc.robotcore.internal.opmode;

/** How an OpMode shows on the Driver Station: name, group, and whether it's autonomous. */
public class OpModeMeta {
    public enum Flavor { AUTONOMOUS, TELEOP, UTILITY, SYSTEM }

    public enum Source { ANDROID_STUDIO, BLOCKLY, ONBOTJAVA, EXTERNAL_LIBRARY, BUILTIN }

    public static final String DefaultGroup = "$$$$$$$";

    public final Flavor flavor;
    public final String group;
    public final String name;
    public final String systemOpModeBaseDisplayName;
    public final String autoTransition;
    public final Source source;
    public final String description;

    OpModeMeta(Builder b) {
        this.flavor = b.flavor;
        this.group = b.group;
        this.name = b.name;
        this.systemOpModeBaseDisplayName = b.systemOpModeBaseDisplayName;
        this.autoTransition = b.autoTransition;
        this.source = b.source;
        this.description = b.description;
    }

    public static boolean nameIsLegalForOpMode(String name, boolean isSystemOpMode) {
        return name != null && !name.trim().isEmpty() && (isSystemOpMode || !isSystemName(name));
    }

    public static boolean isSystemName(String name) {
        return name != null && name.startsWith("$") && name.endsWith("$");
    }

    public String getDisplayName() {
        return systemOpModeBaseDisplayName != null ? systemOpModeBaseDisplayName : name;
    }

    @Override public String toString() { return name; }

    @Override
    public boolean equals(Object o) {
        return o instanceof OpModeMeta && ((OpModeMeta) o).name.equals(name);
    }

    @Override public int hashCode() { return name.hashCode(); }

    public static class Builder {
        public Flavor flavor = Flavor.TELEOP;
        public String group = DefaultGroup;
        public String name;
        public String systemOpModeBaseDisplayName;
        public String autoTransition;
        public Source source;
        public String description;

        public Builder() {}

        public Builder(OpModeMeta meta) {
            flavor = meta.flavor;
            group = meta.group;
            name = meta.name;
            systemOpModeBaseDisplayName = meta.systemOpModeBaseDisplayName;
            autoTransition = meta.autoTransition;
            source = meta.source;
            description = meta.description;
        }

        public Builder setFlavor(Flavor flavor) { this.flavor = flavor; return this; }
        public Builder setGroup(String group) { this.group = group; return this; }
        public Builder setName(String name) { this.name = name; return this; }
        public Builder setSystemOpModeBaseDisplayName(String n) { this.systemOpModeBaseDisplayName = n; return this; }
        public Builder setTransitionTarget(String autoTransition) { this.autoTransition = autoTransition; return this; }
        public Builder setSource(Source source) { this.source = source; return this; }
        public Builder setDescription(String description) { this.description = description; return this; }

        public OpModeMeta build() {
            if (name == null) throw new IllegalArgumentException("OpModeMeta needs a name");
            return new OpModeMeta(this);
        }

        public static Builder wrap(OpModeMeta meta) { return new Builder(meta); }
    }
}
