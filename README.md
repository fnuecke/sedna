# Sedna RISC-V Emulator

Sedna is a 64-bit RISC-V emulator written purely in Java. It implements all extensions necessary to be considered
"general purpose" plus supervisor mode, meaning it can boot Linux. At the time of writing (2020/12/06) Sedna passes all
tests in the [RISC-V test suite](https://github.com/riscv/riscv-tests). It also supports serializing and deserializing
machine state.

## Structure

The code layout is relatively flat, with different parts of the emulator living in their respective packages. Here are
some notable ones.

| Package                                                            | Description                                              |
|--------------------------------------------------------------------|----------------------------------------------------------|
| [li.cil.sedna.device](src/main/java/li/cil/sedna/device)           | Non-ISA specific device implementations.                 |
| [li.cil.sedna.devicetree](src/main/java/li/cil/sedna/devicetree)   | Utilities for constructing device trees.                 |
| [li.cil.sedna.elf](src/main/java/li/cil/sedna/elf)                 | An ELF loader, currently only used to load tests.        |
| [li.cil.sedna.fs](src/main/java/li/cil/sedna/fs)                   | Virtual file system layer for VirtIO filesystem device.  |
| [li.cil.sedna.instruction](src/main/java/li/cil/sedna/instruction) | Instruction loader and decoder generator.                |
| [li.cil.sedna.memory](src/main/java/li/cil/sedna/memory)           | Memory map implementation and utilities.                 |
| [li.cil.sedna.riscv](src/main/java/li/cil/sedna/riscv)             | RISC-V CPU and devices (CLINT, PLIC).                    |

## RISC-V Extensions

Sedna implements the `G` meta extension, i.e. the general purpose computing set of extensions: `rv64imacfd`
and `Zifencei`. For the uninitiated, this means:

- `i`: basic 64-bit integer ISA.
- `m`: integer multiplication, division, etc.
- `a`: atomic operations.
- `c`: compressed instructions.
- `f`: single precision (32-bit) floating-point operations.
- `d`: double precision (64-bit) floating-point operations.
- `Zifencei`: memory fence for instruction fetch.

This comes with a couple of caveats:

- The `FENCE` and `FENCE.I` instructions are no-ops and atomic operations do not lock underlying memory. Multi-core
  setups will behave incorrectly.
- Floating-point operations have been reimplemented in software for flag correctness. Meaning they're slow.

## Instructions and decoding

Sedna uses run-time byte-code generation to create the decoder switch used by the instruction interpreter. This makes it
very easy to add new instructions and to experiment with different switch layouts to improve performance. The
instruction loader and switch generator are technically general purpose, i.e. they have no direct dependencies on the
RISC-V part of this project. However, there are some assumptions on how instructions are defined and processed baked
into their design.

The current set of supported RISC-V instructions is declared [in this file](src/main/resources/riscv/instructions.txt).

Instruction implementations are defined in [the RISC-V CPU class](src/main/java/li/cil/sedna/riscv/R5CPUTemplate.java).

## Endianness

The emulator presents itself as a little-endian system to code running inside it. This should also work correctly on
big-endian host systems, but has not been tested.

## Tests

Sedna tests ISA conformity using the [RISC-V test suite](https://github.com/riscv/riscv-tests). The tests are run using
a simple JUnit [test runner](src/test/java/li/cil/sedna/riscv/ISATests.java). The compiled test binaries are included in
this repository and can be found [here](src/test/data/riscv-tests).