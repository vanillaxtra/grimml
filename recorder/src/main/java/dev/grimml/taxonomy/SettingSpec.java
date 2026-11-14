package dev.grimml.taxonomy;

public final class SettingSpec {

    public enum Kind { ENUM, INT, FLOAT, STRING }

    public final String name;
    public final Kind kind;
    public final String[] enums;

    public SettingSpec(String name, Kind kind, String[] enums) {
        this.name = name;
        this.kind = kind;
        this.enums = enums == null ? new String[0] : enums;
    }

    public String validate(String raw) {
        if (raw == null || raw.isEmpty()) {
            return name + " empty";
        }
        return switch (kind) {
            case ENUM -> {
                for (String e : enums) {
                    if (e.equalsIgnoreCase(raw)) {
                        yield null;
                    }
                }
                yield name + " not in enum";
            }
            case INT -> {
                try {
                    Integer.parseInt(raw);
                    yield null;
                } catch (NumberFormatException e) {
                    yield name + " not int";
                }
            }
            case FLOAT -> {
                try {
                    Float.parseFloat(raw);
                    yield null;
                } catch (NumberFormatException e) {
                    yield name + " not float";
                }
            }
            case STRING -> null;
        };
    }
}
