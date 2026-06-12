package app.grapheneos.goscompat.securespawn.shared;

import java.lang.reflect.Field;

public final class SecureSpawnHiddenApiCheck {
    private static final String OBJECT_SHADOW_KLASS_FIELD = "shadow$_klass_";
    private static final String OBJECT_SHADOW_MONITOR_FIELD = "shadow$_monitor_";

    private SecureSpawnHiddenApiCheck() {
    }

    public static HiddenApiEnforcement run(boolean execSpawned) {
        Field[] objectFields = Object.class.getDeclaredFields();
        return new HiddenApiEnforcement(
                execSpawned,
                objectFields.length,
                fieldNames(objectFields),
                hasField(objectFields, OBJECT_SHADOW_KLASS_FIELD),
                hasField(objectFields, OBJECT_SHADOW_MONITOR_FIELD));
    }

    private static boolean hasField(Field[] fields, String name) {
        for (Field field : fields) {
            if (field.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String fieldNames(Field[] fields) {
        if (fields.length == 0) {
            return "[]";
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(fields[i].getName());
        }
        return sb.append(']').toString();
    }

    public record HiddenApiEnforcement(
            boolean execSpawned,
            int objectDeclaredFieldCount,
            String objectDeclaredFieldNames,
            boolean objectShadowKlassVisible,
            boolean objectShadowMonitorVisible) {
        public boolean objectShadowFieldsHidden() {
            return !objectShadowKlassVisible()
                    && !objectShadowMonitorVisible();
        }

        @Override
        public String toString() {
            return "execSpawned=" + execSpawned()
                    + "\nobjectDeclaredFieldCount=" + objectDeclaredFieldCount()
                    + "\nobjectDeclaredFieldNames=" + objectDeclaredFieldNames()
                    + "\nobjectShadowKlassVisible=" + objectShadowKlassVisible()
                    + "\nobjectShadowMonitorVisible=" + objectShadowMonitorVisible()
                    + "\nobjectShadowFieldsHidden=" + objectShadowFieldsHidden();
        }
    }
}
