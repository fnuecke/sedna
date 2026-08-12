package li.cil.sedna.instruction.decoder;

public final class SourceBuilder {
    private static final String INDENT = "    ";

    private final StringBuilder sb = new StringBuilder();
    private int depth;

    public SourceBuilder() {
        this(0);
    }

    public SourceBuilder(final int depth) {
        this.depth = depth;
    }

    public void line(final String text) {
        sb.append(INDENT.repeat(depth)).append(text).append('\n');
    }

    public void blank() {
        sb.append('\n');
    }

    public void raw(final String text) {
        sb.append(text);
    }

    public void push() {
        depth++;
    }

    public void pop() {
        if (depth == 0) {
            throw new IllegalStateException("Unbalanced indentation.");
        }
        depth--;
    }

    public void indent(final Runnable body) {
        push();
        try {
            body.run();
        } finally {
            pop();
        }
    }

    public int depth() {
        return depth;
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
