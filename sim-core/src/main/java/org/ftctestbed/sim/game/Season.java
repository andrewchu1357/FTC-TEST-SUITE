package org.ftctestbed.sim.game;

import java.util.List;

/**
 * One FTC season's game: its field elements, how pieces are staged, and how points are
 * scored. Selected by "season" in field.jsonc. All methods run on the physics thread.
 */
public interface Season {
    String name();

    /** Called once: add static field elements, flight targets and obstacles. */
    void attach(SeasonContext ctx);

    /** Match setup: re-stage every game piece, including the robot's pre-loads. */
    void reset();

    void step(double dt);

    /** Field elements robots can pull pieces out of. */
    List<Retrievable> retrievables();

    SeasonSnapshot snapshot();

    /** A human-player action requested from the UI, e.g. entering a NECTAR. */
    default void humanPlayerAction(String alliance) {}

    /** The AprilTags on the field where they are now (field elements can move them). */
    default List<org.ftctestbed.sim.vision.Fiducial> fiducials() { return List.of(); }

    /** Where the season's field map puts the AprilTags (as at the start of a match). */
    default List<org.ftctestbed.sim.vision.Fiducial> fieldMap() { return fiducials(); }

    /** Opaque field elements a camera can't see through. */
    default List<org.ftctestbed.sim.vision.VisionScene.Box> visionOccluders() { return List.of(); }
}
