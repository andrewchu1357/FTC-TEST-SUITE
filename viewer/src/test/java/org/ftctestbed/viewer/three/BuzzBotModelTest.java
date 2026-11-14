package org.ftctestbed.viewer.three;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.viewer.model.ModelConfig;
import org.ftctestbed.viewer.model.ModelData;
import org.ftctestbed.viewer.model.ModelLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** BuzzBot's 3D model (config/robot.glb from tools/BuzzBotCad.java) against its robot.jsonc. */
class BuzzBotModelTest {
    static final double IN = 0.0254;
    static final Path ROBOT = Path.of("config/robot.jsonc");

    static RobotModel load() throws Exception {
        RobotConfig robot = RobotConfig.load(ROBOT);
        ModelConfig mc = ModelConfig.load(ModelConfig.fileFor(ROBOT));
        return new RobotModel(ModelLoader.load(ROBOT.resolveSibling(mc.file)), mc, robot);
    }

    @Test
    void everyMovingPartIsBoundToItsDevice() throws Exception {
        RobotModel model = load();
        assertEquals(List.of(), model.problems());
        ModelConfig mc = ModelConfig.load(ModelConfig.fileFor(ROBOT));
        List<String> nodes = new ArrayList<>();
        model.data().root.forEach(n -> nodes.add(n.name));
        for (String part : List.of("Wheel FL", "Wheel FR", "Wheel BL", "Wheel BR", "Intake roller", "Flywheel", "Kicker arm",
                "Transfer wheels", "Lift stage 1", "Lift carriage", "Claw finger L", "Claw finger R")) {
            assertTrue(nodes.contains(part), part);
            assertTrue(mc.bindings.stream().anyMatch(b -> b.node.equals(part)), part + " moves");
        }
        assertTrue(model.data().triangles() < 100_000, "light enough for the 3D view: " + model.data().triangles());
    }

    @Test
    void itIsTheSizeRobotJsoncSays() throws Exception {
        RobotConfig robot = RobotConfig.load(ROBOT);
        double[] b = load().boundsMeters();
        assertEquals(robot.width(), b[4] - b[1], 0.1 * IN, "over the wheels");
        assertEquals(robot.height(), b[5], 0.1 * IN, "top of the launcher plates");
        assertEquals(0, b[2], 0.002, "on the floor");
        assertTrue(b[3] <= robot.length() / 2 + 0.01 * IN, "nothing ahead of the intake roller: " + b[3] / IN);
        // Only the claw (robot.jsonc's grab point is 9.6 in back) reaches past the back.
        assertTrue(b[0] > -12 * IN, "the claw: " + b[0] / IN);
        RobotConfig.Drivetrain dt = robot.drivetrain();
        assertEquals(16.29, dt.trackWidth() / IN, 0.01);
        assertEquals(13.23, dt.wheelbase() / IN, 0.01);
        assertEquals(52, dt.wheelRadius() * 1000, 1e-9, "104 mm GripForce wheels");
    }

    @Test
    void materialsKeepTheirFinish() throws Exception {
        ModelData data = load().data();
        List<ModelData.Mesh> meshes = new ArrayList<>();
        data.root.forEach(n -> meshes.addAll(n.meshes));
        assertTrue(meshes.stream().allMatch(m -> !Float.isNaN(m.metallic) && !Float.isNaN(m.roughness)));
        ModelData.Mesh shiny = meshes.stream().filter(m -> m.metallic > 0.8 && m.roughness < 0.45).findFirst().orElseThrow();
        ModelData.Mesh rubber = meshes.stream().filter(m -> m.roughness > 0.9).findFirst().orElseThrow();
        PhongMaterial metal = RobotModel.material(shiny), tire = RobotModel.material(rubber);
        assertTrue(metal.getSpecularColor().getBrightness() > 2 * tire.getSpecularColor().getBrightness(), "metal shines, rubber doesn't");
        assertTrue(metal.getSpecularPower() > tire.getSpecularPower());
    }

    @Test
    void theRobotSignsTakeTheAllianceColor() throws Exception {
        RobotModel model = load();
        Robot3D r3 = new Robot3D(RobotConfig.load(ROBOT));
        r3.setCadModel(model);
        model.setAllianceColor(Color.web("#1E88E5"));
        List<MeshView> signs = new ArrayList<>();
        collect(model.root, signs);
        assertEquals(2, signs.size(), "one on each side");
        for (MeshView s : signs) assertEquals(Color.web("#1E88E5"), ((PhongMaterial) s.getMaterial()).getDiffuseColor());
    }

    private static void collect(Node n, List<MeshView> out) {
        if (n instanceof MeshView mv && mv.getUserData() instanceof ModelData.Node part && part.name.startsWith("Alliance sign")) out.add(mv);
        if (n instanceof Group g) for (Node c : g.getChildren()) collect(c, out);
    }
}
