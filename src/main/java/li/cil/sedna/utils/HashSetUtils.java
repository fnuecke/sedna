package li.cil.sedna.utils;

import java.util.HashSet;

public final class HashSetUtils {
    public static <E> HashSet<E> hashSetForSize(int size) {
        final float loadFactor = (2.0f/3);
        final int initCapacity = (int) Math.ceil(Math.max(size, 12) / loadFactor);
        return new HashSet<>(initCapacity, loadFactor);
    }
}
