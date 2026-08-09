package li.cil.sedna.fs;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class PathTests {
    @Test
    public void resolveRejectsAbsoluteComponents() {
        assertThrows(IllegalArgumentException.class, () -> new Path().resolve("/etc"));
        assertThrows(IllegalArgumentException.class, () -> new Path().resolve("/"));
        assertThrows(IllegalArgumentException.class, () -> new Path().resolve("\\Windows"));
    }

    @Test
    public void resolveRejectsComponentsContainingSeparators() {
        assertThrows(IllegalArgumentException.class, () -> new Path().resolve("a/b"));
        assertThrows(IllegalArgumentException.class, () -> new Path().resolve("../.."));
        assertThrows(IllegalArgumentException.class, () -> new Path().resolve("a/../../.."));
        assertThrows(IllegalArgumentException.class, () -> new Path().resolve("a\\b"));
        assertThrows(IllegalArgumentException.class, () -> new Path().resolve("..\\.."));
    }

    @Test
    public void resolveRejectsEmptyComponents() {
        assertThrows(IllegalArgumentException.class, () -> new Path().resolve(""));
    }

    @Test
    public void resolveAppendsOrdinaryComponents() {
        assertArrayEquals(new String[]{"a", "b"}, new Path().resolve("a").resolve("b").getParts());
    }

    @Test
    public void resolveAllowsNamesThatOnlyLookLikeTraversal() {
        assertArrayEquals(new String[]{"..."}, new Path().resolve("...").getParts());
        assertArrayEquals(new String[]{".hidden"}, new Path().resolve(".hidden").getParts());
        assertArrayEquals(new String[]{"..a"}, new Path().resolve("..a").getParts());
        assertArrayEquals(new String[]{"a.."}, new Path().resolve("a..").getParts());
    }

    @Test
    public void resolveHandlesDot() {
        final Path path = new Path(Arrays.asList("a", "b"));
        assertArrayEquals(new String[]{"a", "b"}, path.resolve(".").getParts());
    }

    @Test
    public void resolveHandlesDotDot() {
        final Path path = new Path(Arrays.asList("a", "b"));
        assertArrayEquals(new String[]{"a"}, path.resolve("..").getParts());
    }

    @Test
    public void resolveDotDotClampsAtRoot() {
        assertArrayEquals(new String[0], new Path().resolve("..").getParts());
        assertArrayEquals(new String[0], new Path().resolve("..").resolve("..").getParts());
        assertArrayEquals(new String[]{"a"}, new Path().resolve("..").resolve("a").getParts());
    }
}
