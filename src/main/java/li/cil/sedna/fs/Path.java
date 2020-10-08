package li.cil.sedna.fs;

import java.util.ArrayList;
import java.util.List;

public final class Path {
    private final ArrayList<String> parts = new ArrayList<>();

    public Path() {
    }

    public Path(final List<String> parts) {
        this.parts.addAll(parts);
    }

    public Path(final List<String> parts, final String part) {
        this.parts.addAll(parts);
        this.parts.add(part);
    }

    public Path resolve(final String part) {
        if (part.equals(".")) {
            return new Path(parts);
        }

        if (!part.equals("..")) {
            return new Path(parts, part);
        } else if (!parts.isEmpty()) {
            final Path result = new Path(parts);
            result.parts.remove(result.parts.size() - 1);
            return result;
        } else {
            return this;
        }
    }

    public String[] getParts() {
        return parts.toArray(new String[0]);
    }

    @Override
    public String toString() {
        return toString("/");
    }

    public String toString(final String pathSeparator) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(pathSeparator);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
