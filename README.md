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

Sedna generates the decoder switch used by the instruction interpreter as Java source, which is checked into the
repository ([R5CPUImpl](src/main/java/li/cil/sedna/riscv/R5CPUImpl.java)). This makes it very easy to add new
instructions and to experiment with different switch layouts to improve performance, while keeping the code that
actually runs readable, debuggable and visible to profilers. After changing instruction declarations or definitions,
regenerate it with `./gradlew generateDecoder`; a test fails if the checked-in file is out of date. The instruction
loader and switch generator are technically general purpose, i.e. they have no direct dependencies on the RISC-V part
of this project. However, there are some assumptions on how instructions are defined and processed baked into their
design.

The current set of supported RISC-V instructions is declared in
[instructions64.txt](src/main/resources/riscv/instructions64.txt) and
[instructions32.txt](src/main/resources/riscv/instructions32.txt).

Instruction implementations are defined in [the RISC-V CPU class](src/main/java/li/cil/sedna/riscv/R5CPUBase.java).

## Endianness

The emulator presents itself as a little-endian system to code running inside it. This should also work correctly on
big-endian host systems, but has not been tested.

## Tests

Sedna tests ISA conformity using the [RISC-V test suite](https://github.com/riscv/riscv-tests). The tests are run using
a simple JUnit [test runner](src/test/java/li/cil/sedna/riscv/ISATests.java). The compiled test binaries are included in
this repository and can be found [here](src/test/data/riscv-tests).

Note that an additional tests may be included from this fork: https://github.com/fnuecke/riscv-tests

- A test for page misaligned access (e.g. loads spanning multiple pages) has been contributed by @ja2142 on
  branch [page_misaligned_access_test](https://github.com/fnuecke/riscv-tests/tree/page_misaligned_access_test).

## Benchmarks

There's also a decent number of benchmarks for different usecases now; pure memory throughput, instruction fetch, specific instructions, mmio...

They use the [Java Microbenchmark Harness](https://github.com/openjdk/jmh) and can be run via `./gradlew jmh`.

## Maven

Sedna is published to Maven Central.

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("li.cil.sedna:sedna:2.0.10")
}
```

The Linux images Sedna boots are built separately and published as
[sedna-buildroot](https://github.com/fnuecke/buildroot).
