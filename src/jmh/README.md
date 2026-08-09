# Benchmarks

JMH benchmarks for the emulator. They are not part of `test` or `build`; run them explicitly:

```bash
./gradlew jmh                                  # everything, takes a while
./gradlew jmh -Pjmh.include=DispatchBenchmark  # one class
./gradlew jmh -Pjmh.include='Memory.*' -Pjmh.iterations=10
```

Options: `-Pjmh.include`, `-Pjmh.iterations`, `-Pjmh.warmupIterations`, `-Pjmh.fork`.
Results also land in `build/results/jmh/results.txt`.

## What is being measured

| Benchmark           | Cost centre                                   | Why it matters                                                                   |
| ------------------- | --------------------------------------------- | -------------------------------------------------------------------------------- |
| `IdleBenchmark`     | `step` while parked (WFI)                     | VMs are expected to idle a lot.                                                  |
| `DispatchBenchmark` | decoder dispatch and the trace loop           | The upper bound on throughput, and the baseline everything else is read against. |
| `MemoryBenchmark`   | TLB hit vs miss, and the Sv39 resolve on miss | Verifies TLB works/helps.                                                        |
| `MmioBenchmark`     | device register access                        | Device access doesn't go through TLB, e.g. virtio stuff.                         |
| `TrapBenchmark`     | trap delivery                                 | Used for paging, copy on write and syscalls.                                     |

## Writing a benchmark

`Vm` builds the machine and `R5Assembler` hand-assembles instruction streams, so benchmarks need no
cross toolchain. `Vm.bare(size)` gives a machine in machine mode with translation off, which
isolates dispatch; `Vm.paged(size)` maps RAM through a three level Sv39 hierarchy of 4KiB pages and
`enterSupervisor(pc)` drops into supervisor mode (matching our buildroot Linux).

**Rewind the program counter each benchmark iteration.** This is not automatic.
