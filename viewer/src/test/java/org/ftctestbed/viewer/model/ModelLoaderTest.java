package org.ftctestbed.viewer.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelLoaderTest {
    /** 12 triangles of an axis-aligned box from (0,0,0) to (sx,sy,sz). */
    static float[][] boxTriangles(float sx, float sy, float sz) {
        float[][] c = new float[8][];
        for (int i = 0; i < 8; i++) c[i] = new float[]{(i & 1) * sx, ((i >> 1) & 1) * sy, ((i >> 2) & 1) * sz};
        int[][] faces = {{0, 2, 3, 1}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 4, 6, 2}, {1, 3, 7, 5}};
        List<float[]> tris = new ArrayList<>();
        for (int[] f : faces) {
            tris.add(new float[]{c[f[0]][0], c[f[0]][1], c[f[0]][2], c[f[1]][0], c[f[1]][1], c[f[1]][2], c[f[2]][0], c[f[2]][1], c[f[2]][2]});
            tris.add(new float[]{c[f[0]][0], c[f[0]][1], c[f[0]][2], c[f[2]][0], c[f[2]][1], c[f[2]][2], c[f[3]][0], c[f[3]][1], c[f[3]][2]});
        }
        return tris.toArray(new float[0][]);
    }

    @Test
    void asciiAndBinaryStl(@TempDir Path dir) throws Exception {
        float[][] tris = boxTriangles(457, 457, 300); // an 18 in robot exported in mm
        StringBuilder a = new StringBuilder("solid robot\n");
        for (float[] t : tris) {
            a.append(" facet normal 0 0 0\n  outer loop\n");
            for (int v = 0; v < 3; v++) a.append("   vertex ").append(t[v * 3]).append(' ').append(t[v * 3 + 1]).append(' ').append(t[v * 3 + 2]).append('\n');
            a.append("  endloop\n endfacet\n");
        }
        a.append("endsolid robot\n");
        Path ascii = dir.resolve("robot.stl");
        Files.writeString(ascii, a);
        ModelData m = ModelLoader.load(ascii);
        assertEquals(12, m.triangles());
        assertEquals("mm", ModelConfig.detectUnits(m));

        ByteBuffer b = ByteBuffer.allocate(84 + 50 * tris.length).order(ByteOrder.LITTLE_ENDIAN);
        b.position(80);
        b.putInt(tris.length);
        for (float[] t : tris) {
            b.putFloat(0).putFloat(0).putFloat(0);
            for (float f : t) b.putFloat(f);
            b.putShort((short) 0);
        }
        Path bin = dir.resolve("robot-binary.stl");
        Files.write(bin, b.array());
        ModelData mb = ModelLoader.load(bin);
        assertEquals(12, mb.triangles());
        assertArrayEquals(new double[]{0, 0, 0, 457, 457, 300}, ModelData.bounds(mb.root, ModelData.identity()), 1e-3);
    }

    @Test
    void objGroupsAndMaterials(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("robot.mtl"), "newmtl red\nKd 1 0 0\nnewmtl gray\nKd 0.5 0.5 0.5\n");
        Files.writeString(dir.resolve("robot.obj"), """
                mtllib robot.mtl
                v 0 0 0
                v 18 0 0
                v 18 18 0
                v 0 18 0
                v 0 0 4
                o Chassis
                usemtl gray
                f 1 2 3 4
                o Arm
                usemtl red
                f 1 2 5
                """);
        ModelData m = ModelLoader.load(dir.resolve("robot.obj"));
        assertEquals(2, m.root.children.size());
        assertEquals("Chassis", m.root.children.get(0).name);
        assertEquals(2, m.root.children.get(0).meshes.get(0).triangles(), "quad split into two triangles");
        assertEquals(1f, m.root.children.get(1).meshes.get(0).rgba[0]);
        assertEquals("in", ModelConfig.detectUnits(m));
        assertEquals(List.of(dir.resolve("robot.mtl")), ModelLoader.companions(dir.resolve("robot.obj")));
    }

    /** A glTF with a parent node (translated) and a child node (rotated 90° about z) holding one triangle. */
    static String gltfJson(String bufferUri, int byteLength) {
        return """
                {
                  "asset": { "version": "2.0" },
                  "scene": 0,
                  "scenes": [ { "nodes": [0] } ],
                  "nodes": [
                    { "name": "Robot", "translation": [1, 0, 0], "children": [1] },
                    { "name": "Claw", "rotation": [0, 0, 0.7071068, 0.7071068], "mesh": 0 }
                  ],
                  "meshes": [ { "primitives": [ { "attributes": { "POSITION": 0 }, "indices": 1, "material": 0 } ] } ],
                  "materials": [ { "pbrMetallicRoughness": { "baseColorFactor": [1, 0, 0, 1] } } ],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3" },
                    { "bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR" }
                  ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": 0, "byteLength": 36 },
                    { "buffer": 0, "byteOffset": 36, "byteLength": 6 }
                  ],
                  "buffers": [ { %s"byteLength": %d } ]
                }
                """.formatted(bufferUri, byteLength);
    }

    static byte[] gltfBuffer() {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : new float[]{0, 0, 0, 0.1f, 0, 0, 0, 0.2f, 0}) b.putFloat(f);
        b.putShort((short) 0).putShort((short) 1).putShort((short) 2);
        return b.array();
    }

    static void checkGltf(ModelData m) {
        assertEquals("m", m.units);
        ModelData.Node robot = m.root.children.get(0), claw = robot.children.get(0);
        assertEquals("Claw", claw.name);
        assertEquals(1, m.triangles());
        assertEquals(1f, claw.meshes.get(0).rgba[0], 1e-6);
        // (0.1, 0, 0) rotated 90° about z → (0, 0.1, 0), then moved +1 in x.
        double[] world = m.worldMatrices(ModelData.identity()).get(claw);
        double[] p = ModelData.transformPoint(world, 0.1, 0, 0);
        assertArrayEquals(new double[]{1, 0.1, 0}, p, 1e-6);
    }

    @Test
    void gltfWithEmbeddedBuffer(@TempDir Path dir) throws Exception {
        String uri = "\"uri\": \"data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(gltfBuffer()) + "\", ";
        Path f = dir.resolve("robot.gltf");
        Files.writeString(f, gltfJson(uri, 44));
        checkGltf(ModelLoader.load(f));
    }

    @Test
    void gltfWithSeparateBin(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("robot.bin"), gltfBuffer());
        Path f = dir.resolve("robot.gltf");
        Files.writeString(f, gltfJson("\"uri\": \"robot.bin\", ", 44));
        checkGltf(ModelLoader.load(f));
        assertEquals(List.of(dir.resolve("robot.bin")), ModelLoader.companions(f));
    }

    @Test
    void glb(@TempDir Path dir) throws Exception {
        byte[] json = gltfJson("", 44).getBytes(StandardCharsets.UTF_8);
        int jsonLen = (json.length + 3) / 4 * 4;
        byte[] bin = gltfBuffer();
        ByteBuffer b = ByteBuffer.allocate(12 + 8 + jsonLen + 8 + bin.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546C67).putInt(2).putInt(b.capacity());
        b.putInt(jsonLen).putInt(0x4E4F534A).put(json);
        for (int i = json.length; i < jsonLen; i++) b.put((byte) ' ');
        b.putInt(bin.length).putInt(0x004E4942).put(bin);
        Path f = dir.resolve("robot.glb");
        Files.write(f, b.array());
        checkGltf(ModelLoader.load(f));
    }

    @Test
    void dracoCompressionIsExplained(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("robot.gltf");
        Files.writeString(f, "{ \"asset\": {\"version\": \"2.0\"}, \"extensionsRequired\": [\"KHR_draco_mesh_compression\"] }");
        Exception e = assertThrows(java.io.IOException.class, () -> ModelLoader.load(f));
        assertTrue(e.getMessage().contains("without mesh compression"));
    }

    @Test
    void stepFilesPointToConversion(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("robot.step"), "ISO-10303-21;");
        Exception e = assertThrows(java.io.IOException.class, () -> ModelLoader.load(f));
        assertTrue(e.getMessage().contains("convert STEP to GLB"));
    }

    @Test
    void damagedFilesGiveReadableErrors(@TempDir Path dir) throws Exception {
        byte[] glb = Files.readAllBytes(Path.of("robots/ExampleBot/robot.glb"));
        Path cut = dir.resolve("cut.glb");
        Files.write(cut, java.util.Arrays.copyOf(glb, glb.length / 2));
        Exception e = assertThrows(java.io.IOException.class, () -> ModelLoader.load(cut));
        assertTrue(e.getMessage().contains("cut off"), e.getMessage());

        Path obj = dir.resolve("bad.obj");
        Files.writeString(obj, "v 0 0 0\nv 1 0 0\nf 1 2 9\n");
        e = assertThrows(java.io.IOException.class, () -> ModelLoader.load(obj));
        assertTrue(e.getMessage().contains("refers to a vertex that doesn't exist"), e.getMessage());

        Path stl = dir.resolve("bad.stl");
        Files.writeString(stl, "solid x\n vertex a b c\nendsolid\n");
        e = assertThrows(java.io.IOException.class, () -> ModelLoader.load(stl));
        assertTrue(e.getMessage().startsWith("bad.stl isn't a valid .stl file"), e.getMessage());

        // An accessor claiming 2 billion vertices in a 12-byte buffer must not allocate gigabytes.
        Path huge = dir.resolve("huge.gltf");
        Files.writeString(huge, """
                {"asset":{"version":"2.0"},"nodes":[{"mesh":0}],"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":2000000000,"type":"VEC3"}],
                 "bufferViews":[{"buffer":0,"byteLength":12}],
                 "buffers":[{"uri":"data:application/octet-stream;base64,AAAAAAAAAAAAAAAA","byteLength":12}]}
                """);
        assertThrows(java.io.IOException.class, () -> ModelLoader.load(huge));

        Path noTriangles = dir.resolve("empty.gltf");
        Files.writeString(noTriangles, "{\"asset\":{\"version\":\"2.0\"},\"nodes\":[{\"name\":\"a\"}]}");
        e = assertThrows(java.io.IOException.class, () -> ModelLoader.load(noTriangles));
        assertTrue(e.getMessage().contains("no triangles"), e.getMessage());
    }

    @Test
    void configRoundTripAndAlignment(@TempDir Path dir) throws Exception {
        ModelConfig c = new ModelConfig();
        c.file = "robot.stl";
        c.rotations.add(new ModelConfig.Rotation('x', 90));
        ModelConfig.Binding b = new ModelConfig.Binding();
        b.node = "Lift \"stage\" 2";
        b.source = "mechanism";
        b.device = "lift";
        b.axis = new double[]{0, 0, 1};
        b.scale = 0.5;
        c.bindings.add(b);
        Path f = dir.resolve("robot.model.jsonc");
        c.save(f);
        ModelConfig r = ModelConfig.load(f);
        assertEquals("robot.stl", r.file);
        assertEquals(new ModelConfig.Rotation('x', 90), r.rotations.get(0));
        assertEquals("Lift \"stage\" 2", r.bindings.get(0).node);
        assertEquals(0.5, r.bindings.get(0).scale);
        assertArrayEquals(new double[]{0, 0, 1}, r.bindings.get(0).axis);

        // A Y-up box in mm, rotated so +y becomes +z, ends up centered on the robot and on the floor.
        ModelData.Node root = new ModelData.Node("box", null);
        float[][] tris = boxTriangles(400, 300, 200);
        float[] pos = new float[tris.length * 9];
        int[] idx = new int[tris.length * 3];
        for (int i = 0; i < tris.length; i++) System.arraycopy(tris[i], 0, pos, i * 9, 9);
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        root.meshes.add(new ModelData.Mesh(pos, null, idx, new float[]{1, 1, 1, 1}));
        ModelData model = new ModelData(root, "auto", "box");
        double[] bb = ModelData.bounds(model.root, r.alignment(model));
        assertArrayEquals(new double[]{-0.2, -0.1, 0, 0.2, 0.1, 0.3}, bb, 1e-9);
        assertEquals(Path.of("robots/x/robot.model.jsonc"), ModelConfig.fileFor(Path.of("robots/x/robot.jsonc")));
    }
}
