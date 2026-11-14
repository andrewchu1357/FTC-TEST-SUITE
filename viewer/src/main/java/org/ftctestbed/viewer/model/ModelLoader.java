package org.ftctestbed.viewer.model;

import org.ftctestbed.sim.config.Jsonc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads robot CAD exports: glTF 2.0 (.glb / .gltf, keeps the part tree and colors - the best
 * choice, e.g. Onshape's "Export → GLTF"), STL (binary or ASCII) and OBJ (+ .mtl colors,
 * "o"/"g" groups become parts). STEP isn't supported: convert it to GLB first.
 */
public final class ModelLoader {
    public static final List<String> EXTENSIONS = List.of("glb", "gltf", "stl", "obj");
    private static final float[] DEFAULT_COLOR = {0.72f, 0.74f, 0.76f, 1f};

    private ModelLoader() {}

    public static ModelData load(Path file) throws IOException {
        String ext = extension(file);
        ModelData model;
        try {
            model = switch (ext) {
                case "glb", "gltf" -> new Gltf(file).load();
                case "stl" -> loadStl(file);
                case "obj" -> loadObj(file);
                default -> throw new IOException("Unsupported model format ." + ext + " (use " + String.join(", ", EXTENSIONS)
                        + (ext.equals("step") || ext.equals("stp") ? "; convert STEP to GLB first, see the README" : "") + ")");
            };
        } catch (IOException e) {
            throw e;
        } catch (org.ftctestbed.sim.config.ConfigException e) {
            throw new IOException(file.getFileName() + " isn't a valid ." + ext + " file: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // Damaged or unusual files: report them instead of crashing.
            throw new IOException(file.getFileName() + " isn't a valid ." + ext + " file (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")", e);
        }
        validate(model, file);
        return model;
    }

    /** Every mesh has whole triangles whose indices point at real vertices, and there is something to draw. */
    private static void validate(ModelData model, Path file) throws IOException {
        int[] triangles = {0};
        IOException[] problem = {null};
        model.root.forEach(node -> {
            for (ModelData.Mesh m : node.meshes) {
                int vertices = m.positions.length / 3;
                if (m.positions.length % 3 != 0 || m.indices.length % 3 != 0
                        || (m.normals != null && m.normals.length != m.positions.length)) {
                    problem[0] = new IOException(file.getFileName() + " has a damaged mesh in \"" + node.name + "\"");
                    return;
                }
                for (int i : m.indices) {
                    if (i < 0 || i >= vertices) {
                        problem[0] = new IOException(file.getFileName() + ": a triangle in \"" + node.name + "\" uses vertex " + i
                                + " but the mesh has " + vertices);
                        return;
                    }
                }
                for (float f : m.positions) {
                    if (!Float.isFinite(f)) {
                        problem[0] = new IOException(file.getFileName() + " has invalid (NaN or infinite) coordinates in \"" + node.name + "\"");
                        return;
                    }
                }
                triangles[0] += m.triangles();
            }
        });
        if (problem[0] != null) throw problem[0];
        if (triangles[0] == 0) throw new IOException(file.getFileName() + " has no triangles to show");
    }

    public static String extension(Path file) {
        String n = file.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String baseName(Path file) {
        String n = file.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(0, dot);
    }

    /** Files a model needs besides itself (a .gltf's .bin and textures, an .obj's .mtl), to copy them along. */
    public static List<Path> companions(Path file) throws IOException {
        List<Path> out = new ArrayList<>();
        String ext = extension(file);
        Path dir = file.toAbsolutePath().getParent();
        if (ext.equals("gltf")) {
            Object json = Jsonc.parse(Files.readString(file), file.toString());
            for (String key : new String[]{"buffers", "images"}) {
                if (json instanceof Map<?, ?> m && m.get(key) instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> b && b.get("uri") instanceof String uri && !uri.startsWith("data:")) {
                            out.add(dir.resolve(java.net.URLDecoder.decode(uri, StandardCharsets.UTF_8)));
                        }
                    }
                }
            }
        } else if (ext.equals("obj")) {
            for (String line : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
                if (line.startsWith("mtllib ")) out.add(dir.resolve(line.substring(7).trim()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ STL

    static ModelData loadStl(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        ModelData.Node root = new ModelData.Node(baseName(file), null);
        boolean binary = data.length >= 84;
        if (binary) {
            long n = ByteBuffer.wrap(data, 80, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            binary = 84 + 50 * n == data.length;
        }
        if (binary) {
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int n = b.getInt(80);
            if (n <= 0) throw new IOException(file.getFileName() + " has no triangles");
            float[] pos = new float[n * 9];
            int[] idx = new int[n * 3];
            for (int t = 0; t < n; t++) {
                int o = 84 + t * 50 + 12; // skip the facet normal; faces are shaded flat
                for (int k = 0; k < 9; k++) pos[t * 9 + k] = b.getFloat(o + k * 4);
                idx[t * 3] = t * 3;
                idx[t * 3 + 1] = t * 3 + 1;
                idx[t * 3 + 2] = t * 3 + 2;
            }
            root.meshes.add(new ModelData.Mesh(pos, null, idx, DEFAULT_COLOR));
        } else {
            FloatList pos = new FloatList();
            for (String line : new String(data, StandardCharsets.ISO_8859_1).split("\n")) {
                String t = line.trim();
                if (t.startsWith("vertex")) {
                    String[] p = t.split("\\s+");
                    if (p.length < 4) continue;
                    pos.add(Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]));
                }
            }
            if (pos.size == 0) throw new IOException(file.getFileName() + " doesn't look like an STL file (no triangles found)");
            int[] idx = new int[pos.size / 3 / 3 * 3];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            root.meshes.add(new ModelData.Mesh(pos.toArray(), null, idx, DEFAULT_COLOR));
        }
        return new ModelData(root, "auto", file.getFileName().toString());
    }

    // ------------------------------------------------------------------ OBJ

    static ModelData loadObj(Path file) throws IOException {
        Map<String, float[]> materials = new HashMap<>();
        FloatList v = new FloatList(), vn = new FloatList();
        ModelData.Node root = new ModelData.Node(baseName(file), null);
        // Per part: one mesh per material.
        Map<String, Map<String, ObjMesh>> parts = new LinkedHashMap<>();
        String part = baseName(file), material = "";
        for (String raw : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+");
            switch (p[0]) {
                case "v" -> v.add(Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]));
                case "vn" -> vn.add(Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]));
                case "o", "g" -> part = p.length > 1 ? line.substring(p[0].length()).trim() : part;
                case "usemtl" -> material = p.length > 1 ? p[1] : "";
                case "mtllib" -> {
                    Path mtl = file.toAbsolutePath().getParent().resolve(line.substring(7).trim());
                    if (Files.exists(mtl)) readMtl(mtl, materials);
                }
                case "f" -> {
                    ObjMesh m = parts.computeIfAbsent(part, k -> new LinkedHashMap<>()).computeIfAbsent(material, k -> new ObjMesh());
                    int n = p.length - 1;
                    int[] vi = new int[n], ni = new int[n];
                    for (int k = 0; k < n; k++) {
                        String[] c = p[k + 1].split("/");
                        vi[k] = objIndex(c[0], v.size / 3);
                        ni[k] = c.length > 2 && !c[2].isEmpty() ? objIndex(c[2], vn.size / 3) : -1;
                        if (vi[k] < 0 || vi[k] >= v.size / 3 || ni[k] >= vn.size / 3 || ni[k] < -1) {
                            throw new IOException(file.getFileName() + ": face \"" + line + "\" refers to a vertex that doesn't exist");
                        }
                    }
                    for (int k = 1; k + 1 < n; k++) { // triangle fan
                        for (int c : new int[]{0, k, k + 1}) {
                            m.pos.add(v.data[vi[c] * 3], v.data[vi[c] * 3 + 1], v.data[vi[c] * 3 + 2]);
                        }
                    }
                }
                default -> { }
            }
        }
        for (Map.Entry<String, Map<String, ObjMesh>> e : parts.entrySet()) {
            ModelData.Node node = parts.size() == 1 ? root : new ModelData.Node(e.getKey(), null);
            for (Map.Entry<String, ObjMesh> me : e.getValue().entrySet()) {
                ObjMesh m = me.getValue();
                int[] idx = new int[m.pos.size / 3];
                for (int i = 0; i < idx.length; i++) idx[i] = i;
                node.meshes.add(new ModelData.Mesh(m.pos.toArray(), null, idx,
                        materials.getOrDefault(me.getKey(), DEFAULT_COLOR)));
            }
            if (node != root) root.children.add(node);
        }
        if (parts.isEmpty()) throw new IOException(file.getFileName() + " has no faces");
        return new ModelData(root, "auto", file.getFileName().toString());
    }

    private static int objIndex(String s, int count) {
        int i = Integer.parseInt(s);
        if (i == 0) return Integer.MIN_VALUE; // OBJ indices start at 1
        return i < 0 ? count + i : i - 1;
    }

    private static void readMtl(Path mtl, Map<String, float[]> out) throws IOException {
        String name = null;
        for (String raw : Files.readAllLines(mtl, StandardCharsets.ISO_8859_1)) {
            String[] p = raw.trim().split("\\s+");
            if (p[0].equals("newmtl") && p.length > 1) {
                name = p[1];
                out.put(name, DEFAULT_COLOR.clone());
            } else if (name != null && p[0].equals("Kd") && p.length >= 4) {
                float[] c = out.get(name);
                c[0] = Float.parseFloat(p[1]);
                c[1] = Float.parseFloat(p[2]);
                c[2] = Float.parseFloat(p[3]);
            } else if (name != null && p[0].equals("d") && p.length >= 2) {
                out.get(name)[3] = Float.parseFloat(p[1]);
            }
        }
    }

    private static final class ObjMesh {
        final FloatList pos = new FloatList();
    }

    // ------------------------------------------------------------------ glTF 2.0

    private static final class Gltf {
        private final Path file;
        private Map<String, Object> json;
        private final List<ByteBuffer> buffers = new ArrayList<>();
        private ByteBuffer glbBin;
        private final Map<Integer, List<ModelData.Mesh>> meshCache = new HashMap<>();
        private final List<String> warnings = new ArrayList<>();

        Gltf(Path file) {
            this.file = file;
        }

        @SuppressWarnings("unchecked")
        ModelData load() throws IOException {
            byte[] data = Files.readAllBytes(file);
            String text;
            if (data.length >= 12 && ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == 0x46546C67) {
                ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                int version = b.getInt(4);
                if (version != 2) throw new IOException("Only glTF 2.0 is supported (this file is version " + version + ")");
                int offset = 12;
                text = null;
                while (offset + 8 <= data.length) {
                    int len = b.getInt(offset), type = b.getInt(offset + 4);
                    if (len < 0 || (long) offset + 8 + len > data.length) {
                        throw new IOException(file.getFileName() + " is cut off (a GLB chunk runs past the end of the file)");
                    }
                    if (type == 0x4E4F534A) text = new String(data, offset + 8, len, StandardCharsets.UTF_8);
                    else if (type == 0x004E4942) glbBin = ByteBuffer.wrap(data, offset + 8, len).slice().order(ByteOrder.LITTLE_ENDIAN);
                    offset += 8 + len;
                }
                if (text == null) throw new IOException("GLB file has no JSON chunk");
            } else {
                text = new String(data, StandardCharsets.UTF_8);
            }
            Object parsed = Jsonc.parse(text, file.getFileName().toString());
            if (!(parsed instanceof Map)) throw new IOException("Not a glTF file");
            json = (Map<String, Object>) parsed;
            for (Object ext : list(json, "extensionsRequired")) {
                if (String.valueOf(ext).contains("draco") || String.valueOf(ext).contains("meshopt")) {
                    throw new IOException("This glTF uses " + ext + " compression, which isn't supported. Export it again without mesh compression.");
                }
            }
            for (Object b : list(json, "buffers")) buffers.add(readBuffer((Map<String, Object>) b));

            List<Object> nodes = list(json, "nodes");
            List<Integer> roots = new ArrayList<>();
            List<Object> scenes = list(json, "scenes");
            if (!scenes.isEmpty()) {
                int scene = json.get("scene") instanceof Double d ? d.intValue() : 0;
                for (Object n : list((Map<String, Object>) scenes.get(scene), "nodes")) roots.add(((Double) n).intValue());
            } else {
                Set<Integer> children = new HashSet<>();
                for (Object n : nodes) for (Object c : list((Map<String, Object>) n, "children")) children.add(((Double) c).intValue());
                for (int i = 0; i < nodes.size(); i++) if (!children.contains(i)) roots.add(i);
            }
            String name = file.getFileName().toString();
            ModelData.Node root = new ModelData.Node(name.substring(0, name.lastIndexOf('.')), null);
            for (int r : roots) root.children.add(node(r, nodes, 0));
            ModelData model = new ModelData(root, "m", file.getFileName().toString());
            model.warnings.addAll(warnings);
            return model;
        }

        private ByteBuffer readBuffer(Map<String, Object> b) throws IOException {
            Object uri = b.get("uri");
            if (uri == null) {
                if (glbBin == null) throw new IOException("Buffer without data");
                return glbBin;
            }
            String u = (String) uri;
            byte[] bytes;
            if (u.startsWith("data:")) {
                bytes = Base64.getDecoder().decode(u.substring(u.indexOf(',') + 1));
            } else {
                Path p = file.toAbsolutePath().getParent().resolve(java.net.URLDecoder.decode(u, StandardCharsets.UTF_8));
                if (!Files.exists(p)) throw new IOException("Missing " + p.getFileName() + " (keep it next to " + file.getFileName() + ")");
                bytes = Files.readAllBytes(p);
            }
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }

        @SuppressWarnings("unchecked")
        private ModelData.Node node(int index, List<Object> nodes, int depth) throws IOException {
            if (depth > 256) throw new IOException("glTF node tree is too deep (cycle?)");
            Map<String, Object> n = (Map<String, Object>) nodes.get(index);
            ModelData.Node out = new ModelData.Node((String) n.getOrDefault("name", "node " + index), matrix(n));
            if (n.get("mesh") instanceof Double m) out.meshes.addAll(mesh(m.intValue()));
            for (Object c : list(n, "children")) out.children.add(node(((Double) c).intValue(), nodes, depth + 1));
            return out;
        }

        private static double[] matrix(Map<String, Object> n) {
            if (n.get("matrix") instanceof List<?> m && m.size() == 16) {
                double[] r = new double[16];
                for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) r[i * 4 + j] = ((Double) m.get(j * 4 + i)); // column-major
                return r;
            }
            double[] t = vec(n.get("translation"), new double[]{0, 0, 0});
            double[] q = vec(n.get("rotation"), new double[]{0, 0, 0, 1});
            double[] s = vec(n.get("scale"), new double[]{1, 1, 1});
            double x = q[0], y = q[1], z = q[2], w = q[3];
            return new double[]{
                    (1 - 2 * (y * y + z * z)) * s[0], 2 * (x * y - z * w) * s[1], 2 * (x * z + y * w) * s[2], t[0],
                    2 * (x * y + z * w) * s[0], (1 - 2 * (x * x + z * z)) * s[1], 2 * (y * z - x * w) * s[2], t[1],
                    2 * (x * z - y * w) * s[0], 2 * (y * z + x * w) * s[1], (1 - 2 * (x * x + y * y)) * s[2], t[2],
                    0, 0, 0, 1};
        }

        private static double[] vec(Object o, double[] def) {
            if (!(o instanceof List<?> l) || l.size() != def.length) return def;
            double[] r = new double[def.length];
            for (int i = 0; i < r.length; i++) r[i] = (Double) l.get(i);
            return r;
        }

        @SuppressWarnings("unchecked")
        private List<ModelData.Mesh> mesh(int index) throws IOException {
            List<ModelData.Mesh> cached = meshCache.get(index);
            if (cached != null) return cached; // shared by every instance (screws, wheels...)
            Map<String, Object> mesh = (Map<String, Object>) list(json, "meshes").get(index);
            List<ModelData.Mesh> out = new ArrayList<>();
            for (Object po : list(mesh, "primitives")) {
                Map<String, Object> prim = (Map<String, Object>) po;
                int mode = prim.get("mode") instanceof Double d ? d.intValue() : 4;
                if (mode != 4 && mode != 5 && mode != 6) continue; // points and lines aren't drawn
                Map<String, Object> attrs = (Map<String, Object>) prim.get("attributes");
                if (attrs == null || !(attrs.get("POSITION") instanceof Double posAcc)) continue;
                float[] pos = readFloats(posAcc.intValue(), 3);
                int[] idx;
                if (prim.get("indices") instanceof Double ia) idx = readInts(ia.intValue());
                else {
                    idx = new int[pos.length / 3];
                    for (int i = 0; i < idx.length; i++) idx[i] = i;
                }
                if (mode == 5) idx = stripToTriangles(idx);
                else if (mode == 6) idx = fanToTriangles(idx);
                ModelData.Mesh m = new ModelData.Mesh(pos, null, idx, color(prim.get("material"))); // the 3D view computes normals
                finish(m, prim.get("material"));
                out.add(m);
            }
            meshCache.put(index, out);
            return out;
        }

        @SuppressWarnings("unchecked")
        private float[] color(Object materialIndex) {
            if (!(materialIndex instanceof Double mi)) return DEFAULT_COLOR;
            Map<String, Object> mat = (Map<String, Object>) list(json, "materials").get(mi.intValue());
            Object pbr = mat.get("pbrMetallicRoughness");
            if (pbr instanceof Map<?, ?> p && p.get("baseColorFactor") instanceof List<?> c && c.size() == 4) {
                float[] rgba = new float[4];
                for (int i = 0; i < 4; i++) rgba[i] = ((Double) c.get(i)).floatValue();
                if ("OPAQUE".equals(mat.getOrDefault("alphaMode", "OPAQUE"))) rgba[3] = 1f;
                // glTF colors are linear; convert to sRGB for display.
                for (int i = 0; i < 3; i++) rgba[i] = (float) Math.pow(rgba[i], 1 / 2.2);
                return rgba;
            }
            return DEFAULT_COLOR;
        }

        /** The material's metallic and roughness factors (glTF defaults: 1 and 1 when a material leaves them out). */
        @SuppressWarnings("unchecked")
        private void finish(ModelData.Mesh m, Object materialIndex) {
            if (!(materialIndex instanceof Double mi)) return;
            Map<String, Object> mat = (Map<String, Object>) list(json, "materials").get(mi.intValue());
            if (mat.get("pbrMetallicRoughness") instanceof Map<?, ?> p) {
                m.metallic = p.get("metallicFactor") instanceof Double d ? d.floatValue() : 1f;
                m.roughness = p.get("roughnessFactor") instanceof Double d ? d.floatValue() : 1f;
            }
        }

        @SuppressWarnings("unchecked")
        private float[] readFloats(int accessorIndex, int components) throws IOException {
            Map<String, Object> acc = (Map<String, Object>) list(json, "accessors").get(accessorIndex);
            int count = ((Double) acc.get("count")).intValue();
            int ctype = ((Double) acc.get("componentType")).intValue();
            boolean normalized = Boolean.TRUE.equals(acc.get("normalized"));
            if (count < 0) throw new IOException("negative accessor count");
            if (!(acc.get("bufferView") instanceof Double)) {
                if ((long) count * components > 50_000_000L) throw new IOException("accessor is too large");
                return new float[count * components]; // all zeros (sparse-only accessors aren't supported)
            }
            View v = view(acc, components, ctype); // checks the data really is in the file before allocating
            float[] out = new float[count * components];
            for (int i = 0; i < count; i++) {
                for (int c = 0; c < components; c++) {
                    int at = v.start + i * v.stride + c * size(ctype);
                    out[i * components + c] = (float) component(v.buf, at, ctype, normalized);
                }
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        private int[] readInts(int accessorIndex) throws IOException {
            Map<String, Object> acc = (Map<String, Object>) list(json, "accessors").get(accessorIndex);
            int count = ((Double) acc.get("count")).intValue();
            int ctype = ((Double) acc.get("componentType")).intValue();
            if (count < 0) throw new IOException("negative accessor count");
            View v = view(acc, 1, ctype);
            int[] out = new int[count];
            for (int i = 0; i < count; i++) {
                int at = v.start + i * v.stride;
                out[i] = switch (ctype) {
                    case 5121 -> v.buf.get(at) & 0xFF;
                    case 5123 -> v.buf.getShort(at) & 0xFFFF;
                    default -> v.buf.getInt(at);
                };
            }
            return out;
        }

        private record View(ByteBuffer buf, int start, int stride) {}

        @SuppressWarnings("unchecked")
        private View view(Map<String, Object> acc, int components, int ctype) throws IOException {
            Map<String, Object> bv = (Map<String, Object>) list(json, "bufferViews").get(((Double) acc.get("bufferView")).intValue());
            ByteBuffer buf = buffers.get(((Double) bv.get("buffer")).intValue());
            int start = num(bv, "byteOffset") + num(acc, "byteOffset");
            int stride = num(bv, "byteStride");
            if (stride == 0) stride = components * size(ctype);
            int count = ((Double) acc.get("count")).intValue();
            if (count > 0 && start + (long) (count - 1) * stride + (long) components * size(ctype) > buf.limit()) {
                throw new IOException("glTF accessor reads past the end of its buffer (corrupt file?)");
            }
            return new View(buf, start, stride);
        }

        private static int size(int ctype) {
            return switch (ctype) {
                case 5120, 5121 -> 1;
                case 5122, 5123 -> 2;
                default -> 4;
            };
        }

        private static double component(ByteBuffer b, int at, int ctype, boolean normalized) {
            return switch (ctype) {
                case 5126 -> b.getFloat(at);
                case 5120 -> normalized ? Math.max(b.get(at) / 127.0, -1) : b.get(at);
                case 5121 -> normalized ? (b.get(at) & 0xFF) / 255.0 : b.get(at) & 0xFF;
                case 5122 -> normalized ? Math.max(b.getShort(at) / 32767.0, -1) : b.getShort(at);
                case 5123 -> normalized ? (b.getShort(at) & 0xFFFF) / 65535.0 : b.getShort(at) & 0xFFFF;
                default -> b.getInt(at);
            };
        }

        private static int num(Map<String, Object> m, String key) {
            return m.get(key) instanceof Double d ? d.intValue() : 0;
        }

        @SuppressWarnings("unchecked")
        private static List<Object> list(Map<String, Object> m, String key) {
            return m.get(key) instanceof List<?> l ? (List<Object>) l : List.of();
        }

        private static int[] stripToTriangles(int[] s) {
            int[] out = new int[Math.max(0, s.length - 2) * 3];
            for (int i = 0; i + 2 < s.length; i++) {
                boolean even = i % 2 == 0;
                out[i * 3] = s[i];
                out[i * 3 + 1] = even ? s[i + 1] : s[i + 2];
                out[i * 3 + 2] = even ? s[i + 2] : s[i + 1];
            }
            return out;
        }

        private static int[] fanToTriangles(int[] f) {
            int[] out = new int[Math.max(0, f.length - 2) * 3];
            for (int i = 1; i + 1 < f.length; i++) {
                out[(i - 1) * 3] = f[0];
                out[(i - 1) * 3 + 1] = f[i];
                out[(i - 1) * 3 + 2] = f[i + 1];
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ util

    static final class FloatList {
        float[] data = new float[1024];
        int size;

        void add(float a, float b, float c) {
            if (size + 3 > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            data[size++] = a;
            data[size++] = b;
            data[size++] = c;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }
}
