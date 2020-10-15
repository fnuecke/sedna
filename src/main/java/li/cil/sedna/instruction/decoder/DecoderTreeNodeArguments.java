package li.cil.sedna.instruction.decoder;

import li.cil.sedna.instruction.argument.FieldInstructionArgument;

import java.util.ArrayList;
import java.util.HashMap;

public final class DecoderTreeNodeArguments {
    /**
     * The total number of leaf nodes taken into account in this information collection.
     * <p>
     * This is required to compute relative use-counts, i.e. how many leaf nodes may use
     * an argument in this information collection vs. how many do not.
     */
    public final int totalLeafCount;

    /**
     * The list of all unique field instruction arguments referenced by all leaf nodes
     * tracked by this information collection.
     */
    public final HashMap<FieldInstructionArgument, Entry> arguments;

    public DecoderTreeNodeArguments(final int totalLeafCount, final HashMap<FieldInstructionArgument, Entry> arguments) {
        this.totalLeafCount = totalLeafCount;
        this.arguments = arguments;
    }

    public static final class Entry {
        /**
         * Number of leaf nodes using this argument.
         */
        public final int count;

        /**
         * The names by which this argument is referenced, for debugging purposes.
         */
        public final ArrayList<String> names;

        public Entry(final int count, final ArrayList<String> names) {
            this.count = count;
            this.names = names;
        }
    }
}
