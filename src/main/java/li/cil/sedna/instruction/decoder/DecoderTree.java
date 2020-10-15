package li.cil.sedna.instruction.decoder;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import li.cil.sedna.instruction.InstructionDeclaration;
import li.cil.sedna.instruction.decoder.tree.AbstractDecoderTreeNode;
import li.cil.sedna.instruction.decoder.tree.DecoderTreeBranchNode;
import li.cil.sedna.instruction.decoder.tree.DecoderTreeLeafNode;
import li.cil.sedna.instruction.decoder.tree.DecoderTreeSwitchNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

public final class DecoderTree {
    public static AbstractDecoderTreeNode create(final ArrayList<InstructionDeclaration> declarations) {
        return postProcess(create(declarations, 0xFFFFFFFF));
    }

    static AbstractDecoderTreeNode create(final ArrayList<InstructionDeclaration> declarations, final int groupMask) {
        if (declarations.size() == 1) {
            final InstructionDeclaration declaration = declarations.get(0);
            if (declaration.patternMask != groupMask) {
                return new DecoderTreeBranchNode(new DecoderTreeLeafNode[]{new DecoderTreeLeafNode(declaration)});
            } else {
                return new DecoderTreeLeafNode(declaration);
            }
        } else {
            int maskIntersect = 0xFFFFFFFF;
            for (final InstructionDeclaration declaration : declarations) {
                maskIntersect &= declaration.patternMask;
            }

            final Int2ObjectArrayMap<ArrayList<InstructionDeclaration>> groups = new Int2ObjectArrayMap<>();
            for (final InstructionDeclaration declaration : declarations) {
                final int maskedPattern = declaration.pattern & maskIntersect;
                groups.computeIfAbsent(maskedPattern, i -> new ArrayList<>()).add(declaration);
            }

            if (groups.size() == 1) {
                // We must enforce that declarations that fall into one such group have unambiguous
                // bit patterns. Specifically, there must be a way to order them from most specific
                // to least specific, where less specific means that the less specific mask can be
                // fully contained in a more specific mask. Under this constraint we can establish
                // the order by comparing the number of set bits in the mask.
                declarations.sort(Comparator.comparingInt(o -> -Integer.bitCount(o.patternMask)));

                // Then we can compare each mask with the masks required to be less specific.
                for (int i = 0; i < declarations.size(); i++) {
                    final InstructionDeclaration moreSpecific = declarations.get(i);
                    final int maskMoreSpecific = moreSpecific.patternMask;
                    for (int j = i + 1; j < declarations.size(); j++) {
                        final InstructionDeclaration lessSpecific = declarations.get(j);
                        final int maskLessSpecific = lessSpecific.patternMask;
                        if ((maskLessSpecific & maskMoreSpecific) != maskLessSpecific) {
                            // Ambiguous case. However, we can still save this if there is a more
                            // specific mask that covers the ambiguous case. For example:
                            //    C: 0000
                            //    A: 00.0
                            //    B: 0.00
                            // Here A and B are ambiguous -- however, C covers the ambiguous case.
                            // Therefore: if the parts of the masks that are ambiguous are matched
                            // by a more specific declaration we are good.
                            final int localMaskA = maskMoreSpecific & ~maskIntersect;
                            final int localMaskB = maskLessSpecific & ~maskIntersect;

                            final int overlapMask = localMaskA | localMaskB;
                            assert (moreSpecific.pattern & overlapMask) == (lessSpecific.pattern & overlapMask) : "Expected overlapping patterns to match or have landed in different groups otherwise.";

                            final int patternA = localMaskA & moreSpecific.pattern;
                            final int patternB = localMaskB & lessSpecific.pattern;
                            final int patternAB = patternA | patternB;

                            boolean ambiguousCaseHandledByMoreSpecificDeclaration = false;
                            for (int k = i - 1; k >= 0; k--) {
                                final InstructionDeclaration mostSpecific = declarations.get(k);
                                assert (mostSpecific.patternMask & overlapMask) == overlapMask : "Expecting more specific patterns to fully contain less specific patterns.";
                                final int mostSpecificPattern = mostSpecific.pattern & overlapMask;
                                if (mostSpecificPattern == patternAB) {
                                    ambiguousCaseHandledByMoreSpecificDeclaration = true;
                                    break;
                                }
                            }

                            if (!ambiguousCaseHandledByMoreSpecificDeclaration) {
                                throw new IllegalArgumentException(String.format("Instructions [%s] (line %d) and [%s] (line %d) have ambiguous bit pattern.",
                                        moreSpecific.displayName, moreSpecific.lineNumber,
                                        lessSpecific.displayName, lessSpecific.lineNumber));
                            }
                        }
                    }
                }

                return new DecoderTreeBranchNode(declarations.stream().map(DecoderTreeLeafNode::new).toArray(DecoderTreeLeafNode[]::new));
            } else {
                final int[] groupPatterns = groups.keySet().toIntArray();
                sortUnsigned(groupPatterns);

                final AbstractDecoderTreeNode[] children = new AbstractDecoderTreeNode[groupPatterns.length];
                for (int i = 0; i < groupPatterns.length; i++) {
                    children[i] = DecoderTree.create(groups.get(groupPatterns[i]), maskIntersect);
                }

                return new DecoderTreeSwitchNode(children);
            }
        }
    }

    private static void sortUnsigned(final int[] values) {
        final int length = values.length;
        for (int i = 0; i < length; i++) {
            values[i] = values[i] ^ Integer.MIN_VALUE;
        }
        Arrays.sort(values);
        for (int i = 0; i < length; i++) {
            values[i] = values[i] ^ Integer.MIN_VALUE;
        }
    }

    private static AbstractDecoderTreeNode postProcess(final AbstractDecoderTreeNode node) {
        if (node instanceof DecoderTreeSwitchNode) {
            final DecoderTreeSwitchNode switchNode = (DecoderTreeSwitchNode) node;
            if (switchNode.children.length < 3) {
                return new DecoderTreeBranchNode(switchNode.children);
            } else {
                for (int i = 0; i < switchNode.children.length; i++) {
                    switchNode.children[i] = postProcess(switchNode.children[i]);
                }
            }
        } else if (node instanceof DecoderTreeBranchNode) {
            final DecoderTreeBranchNode branchNode = (DecoderTreeBranchNode) node;
            for (int i = 0; i < branchNode.children.length; i++) {
                branchNode.children[i] = postProcess(branchNode.children[i]);
            }
        }
        return node;
    }
}
