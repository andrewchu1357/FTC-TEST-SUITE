package com.qualcomm.robotcore.hardware;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public class HardwareMap implements Iterable<HardwareDevice> {
    public DeviceMapping<DcMotorController> dcMotorController = new DeviceMapping<>(DcMotorController.class);
    public DeviceMapping<DcMotor> dcMotor = new DeviceMapping<>(DcMotor.class);
    public DeviceMapping<ServoController> servoController = new DeviceMapping<>(ServoController.class);
    public DeviceMapping<Servo> servo = new DeviceMapping<>(Servo.class);
    public DeviceMapping<CRServo> crservo = new DeviceMapping<>(CRServo.class);
    public DeviceMapping<TouchSensor> touchSensor = new DeviceMapping<>(TouchSensor.class);
    public DeviceMapping<ColorSensor> colorSensor = new DeviceMapping<>(ColorSensor.class);
    public DeviceMapping<AnalogInput> analogInput = new DeviceMapping<>(AnalogInput.class);
    public DeviceMapping<DigitalChannel> digitalChannel = new DeviceMapping<>(DigitalChannel.class);
    public DeviceMapping<VoltageSensor> voltageSensor = new DeviceMapping<>(VoltageSensor.class);

    public final List<DeviceMapping<? extends HardwareDevice>> allDeviceMappings;

    /** The Robot Controller app; in the simulator a stand-in with no resources (see android.content.Context). */
    public final Context appContext;

    protected final Map<String, List<HardwareDevice>> allDevicesMap = new LinkedHashMap<>();
    protected final Map<HardwareDevice, Set<String>> deviceNames = new HashMap<>();
    protected final Object lock = new Object();

    public HardwareMap(Context appContext) {
        this.appContext = appContext;
        this.allDeviceMappings = Arrays.<DeviceMapping<? extends HardwareDevice>>asList(
                dcMotorController, dcMotor, servoController, servo, crservo, touchSensor,
                colorSensor, analogInput, digitalChannel, voltageSensor);
    }

    public HardwareMap() {
        this(org.ftctestbed.shim.SimActivity.INSTANCE);
    }

    public <T> T get(Class<? extends T> classOrInterface, String deviceName) {
        T result = tryGet(classOrInterface, deviceName);
        if (result == null) {
            throw new IllegalArgumentException(String.format(
                    "Unable to find a hardware device with name \"%s\" and type %s",
                    deviceName, classOrInterface.getSimpleName()));
        }
        return result;
    }

    public <T> T tryGet(Class<? extends T> classOrInterface, String deviceName) {
        T result = null;
        synchronized (lock) {
            List<HardwareDevice> list = allDevicesMap.get(deviceName.trim());
            if (list != null) {
                for (HardwareDevice device : list) {
                    if (classOrInterface.isInstance(device)) {
                        result = classOrInterface.cast(device);
                        break;
                    }
                }
            }
        }
        if (result == null) result = createI2cDriver(classOrInterface, deviceName.trim());
        // As in the SDK, an I2C driver initializes when code first gets it (outside the lock:
        // initializing talks to the device, which takes time).
        if (result != null) I2cDeviceSynchDevice.initializeOnGet((HardwareDevice) result);
        return result;
    }

    public HardwareDevice get(String deviceName) {
        HardwareDevice result;
        synchronized (lock) {
            List<HardwareDevice> list = allDevicesMap.get(deviceName.trim());
            if (list == null || list.isEmpty()) {
                throw new IllegalArgumentException(String.format("Unable to find a hardware device with name \"%s\"", deviceName));
            }
            result = list.get(0);
        }
        I2cDeviceSynchDevice.initializeOnGet(result);
        return result;
    }

    public <T> List<T> getAll(Class<? extends T> classOrInterface) {
        List<T> result = new ArrayList<>();
        for (HardwareDevice device : this) {
            if (classOrInterface.isInstance(device)) {
                I2cDeviceSynchDevice.initializeOnGet(device);
                result.add(classOrInterface.cast(device));
            }
        }
        return result;
    }

    // ---- I2C devices: the simulated device behind a name, and drivers created on demand ----

    private final Map<String, I2cDeviceSynchSimple> i2cClients = new HashMap<>();
    private final Map<String, String> i2cXmlTags = new HashMap<>();

    /**
     * Simulator: the I2C device configured under this name (its xmlTag, e.g. "goBILDAPinpoint").
     * A driver class with that xmlTag in its @DeviceProperties that isn't in the map yet (a
     * team's own copy of a driver) is created on this device when code asks for it.
     */
    public void simPutI2cDevice(String deviceName, I2cDeviceSynchSimple client, String xmlTag) {
        synchronized (lock) {
            i2cClients.put(deviceName.trim(), client);
            i2cXmlTags.put(deviceName.trim(), xmlTag);
        }
    }

    private <T> T createI2cDriver(Class<? extends T> type, String deviceName) {
        I2cDeviceSynchSimple client;
        String xmlTag;
        synchronized (lock) {
            client = i2cClients.get(deviceName);
            xmlTag = i2cXmlTags.get(deviceName);
        }
        if (client == null || !I2cDeviceSynchDevice.class.isAssignableFrom(type)
                || java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
            return null;
        }
        com.qualcomm.robotcore.hardware.configuration.annotations.DeviceProperties props =
                type.getAnnotation(com.qualcomm.robotcore.hardware.configuration.annotations.DeviceProperties.class);
        if (props == null || !matchesTag(props, xmlTag)) return null;
        Object driver = null;
        for (java.lang.reflect.Constructor<?> c : type.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            try {
                if (p.length == 2 && p[0].isInstance(client) && p[1] == boolean.class) driver = c.newInstance(client, true);
                else if (p.length == 1 && p[0].isInstance(client)) driver = c.newInstance(client);
            } catch (ReflectiveOperationException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new RuntimeException("Couldn't create " + type.getSimpleName() + " \"" + deviceName + "\": " + cause, cause);
            }
            if (driver != null) break;
        }
        if (driver == null) return null;
        synchronized (lock) {
            List<HardwareDevice> list = allDevicesMap.get(deviceName);
            if (list != null) {
                for (HardwareDevice device : list) {
                    if (type.isInstance(device)) return type.cast(device); // another thread made one first
                }
            }
            put(deviceName, (HardwareDevice) driver);
        }
        return type.cast(driver);
    }

    private static boolean matchesTag(com.qualcomm.robotcore.hardware.configuration.annotations.DeviceProperties props, String xmlTag) {
        if (props.xmlTag().equals(xmlTag)) return true;
        for (String alias : props.xmlTagAliases()) if (alias.equals(xmlTag)) return true;
        return false;
    }

    public void put(String deviceName, HardwareDevice device) {
        synchronized (lock) {
            deviceName = deviceName.trim();
            List<HardwareDevice> list = allDevicesMap.get(deviceName);
            if (list == null) {
                list = new ArrayList<>();
                allDevicesMap.put(deviceName, list);
            }
            if (!list.contains(device)) list.add(device);
            Set<String> names = deviceNames.get(device);
            if (names == null) {
                names = new LinkedHashSet<>();
                deviceNames.put(device, names);
            }
            names.add(deviceName);
            for (DeviceMapping<? extends HardwareDevice> mapping : allDeviceMappings) {
                mapping.putIfCompatible(deviceName, device);
            }
        }
    }

    public boolean remove(String deviceName, HardwareDevice device) {
        synchronized (lock) {
            deviceName = deviceName.trim();
            List<HardwareDevice> list = allDevicesMap.get(deviceName);
            boolean removed = list != null && list.remove(device);
            if (list != null && list.isEmpty()) allDevicesMap.remove(deviceName);
            Set<String> names = deviceNames.get(device);
            if (names != null) {
                names.remove(deviceName);
                if (names.isEmpty()) deviceNames.remove(device);
            }
            for (DeviceMapping<? extends HardwareDevice> mapping : allDeviceMappings) {
                mapping.removeIfSame(deviceName, device);
            }
            return removed;
        }
    }

    public Set<String> getNamesOf(HardwareDevice device) {
        synchronized (lock) {
            Set<String> names = deviceNames.get(device);
            return names == null ? new LinkedHashSet<String>() : new LinkedHashSet<>(names);
        }
    }

    public SortedSet<String> getAllNames(Class<? extends HardwareDevice> classOrInterface) {
        SortedSet<String> result = new TreeSet<>();
        synchronized (lock) {
            for (Map.Entry<String, List<HardwareDevice>> e : allDevicesMap.entrySet()) {
                for (HardwareDevice device : e.getValue()) {
                    if (classOrInterface.isInstance(device)) result.add(e.getKey());
                }
            }
        }
        return result;
    }

    public int size() {
        synchronized (lock) {
            return deviceNames.size();
        }
    }

    @Override
    public Iterator<HardwareDevice> iterator() {
        synchronized (lock) {
            Set<HardwareDevice> unique = new LinkedHashSet<>();
            for (List<HardwareDevice> list : allDevicesMap.values()) unique.addAll(list);
            return new ArrayList<>(unique).iterator();
        }
    }

    public class DeviceMapping<DEVICE_TYPE extends HardwareDevice> implements Iterable<DEVICE_TYPE> {
        private final Map<String, DEVICE_TYPE> map = new LinkedHashMap<>();
        private final Class<DEVICE_TYPE> deviceTypeClass;

        public DeviceMapping(Class<DEVICE_TYPE> deviceTypeClass) {
            this.deviceTypeClass = deviceTypeClass;
        }

        public Class<DEVICE_TYPE> getDeviceTypeClass() { return deviceTypeClass; }

        public DEVICE_TYPE cast(Object obj) { return deviceTypeClass.cast(obj); }

        public DEVICE_TYPE get(String deviceName) {
            synchronized (lock) {
                DEVICE_TYPE device = map.get(deviceName.trim());
                if (device == null) {
                    throw new IllegalArgumentException(String.format("Unable to find a hardware device with the name \"%s\"", deviceName));
                }
                return device;
            }
        }

        public void put(String deviceName, DEVICE_TYPE device) {
            HardwareMap.this.put(deviceName, device);
        }

        void putIfCompatible(String deviceName, HardwareDevice device) {
            if (deviceTypeClass.isInstance(device)) map.put(deviceName, deviceTypeClass.cast(device));
        }

        void removeIfSame(String deviceName, HardwareDevice device) {
            if (map.get(deviceName) == device) map.remove(deviceName);
        }

        public boolean contains(String deviceName) {
            synchronized (lock) {
                return map.containsKey(deviceName.trim());
            }
        }

        public boolean remove(String deviceName) {
            synchronized (lock) {
                DEVICE_TYPE device = map.get(deviceName.trim());
                return device != null && HardwareMap.this.remove(deviceName, device);
            }
        }

        public Set<Map.Entry<String, DEVICE_TYPE>> entrySet() {
            synchronized (lock) {
                return new LinkedHashMap<>(map).entrySet();
            }
        }

        @Override
        public Iterator<DEVICE_TYPE> iterator() {
            synchronized (lock) {
                return new ArrayList<>(map.values()).iterator();
            }
        }

        public int size() {
            synchronized (lock) {
                return map.size();
            }
        }
    }
}
