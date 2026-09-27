package VLADMIX90.motorcyclePlugin;

public enum ZoneType {
    NO_RIDE,
    CHARGE;

    public static ZoneType fromString(String value) {
        return switch (value.toLowerCase()) {
            case "no_ride", "noride", "no-ride", "запрет" -> NO_RIDE;
            case "charge", "charging", "зарядка" -> CHARGE;
            default -> null;
        };
    }
}
