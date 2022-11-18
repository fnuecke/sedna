package li.cil.sedna.utils;

import javax.annotation.Nonnull;
import java.util.Objects;

public final class Interval implements Comparable<Interval> {
    private final long start;
    private final long end;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Interval interval = (Interval) o;
        return start == interval.start && end == interval.end;
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end);
    }

    public Interval(final long start, final long length) {
        this.start = start;
        this.end = start + (length - 1);
    }

    @Nonnull
    public static Interval fromEndpoint(final long start, final long end) {
        if(end < start) throw new IllegalArgumentException("End must be >= start");
        return new Interval(start, end - start + 1);
    }

    @Override
    public int compareTo(Interval other) {
        int cmp = Long.compareUnsigned(start, other.start);
        if(cmp == 0) {
            cmp = Long.compareUnsigned(end, other.end);
        }
        return cmp;
    }

    public boolean intersects(Interval other) {
        return Long.compareUnsigned(start, other.end) <= 0 && Long.compareUnsigned(end, other.start) >= 0;
    }

    public long start() {
        return start;
    }

    public long length() {
        return end - start + 1;
    }

    public long end() {
        return end;
    }
}
