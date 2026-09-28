# libqmesa64.so — `set_robust_list` seccomp patch

## Why

`app/src/main/jniLibs/arm64-v8a/libqmesa64.so` (QMESA_64 v2.30, Qualcomm) is a
**statically linked glibc** executable (`ELF 64-bit LSB executable, ARM aarch64,
statically linked, for GNU/Linux 3.7.0`), not a bionic/NDK build.

glibc calls `set_robust_list`(99) during startup and again on every
`pthread_create`. Android's **app** seccomp filter does not allow that syscall,
so the kernel kills the process immediately:

```
execve(".../libqmesa64.so", [...]) = 0
--- SIGSYS {si_code=SYS_SECCOMP, si_syscall=__NR_set_robust_list,
            si_arch=AUDIT_ARCH_AARCH64} ---
+++ killed by SIGSYS +++
```

`NativeProcessRunner` reports exit code 159 (128 + SIGSYS 31), which
`NativeToolTestActivity` surfaces as "Killed by OS (signal 31)".

The filter is installed by zygote with `NO_NEW_PRIVS` and uses
`SECCOMP_RET_KILL`, so an app can neither remove it nor catch the signal.
The same binary runs fine under `adb shell`, which has no app seccomp filter —
that asymmetry is the giveaway. The sibling `libmemtester.so` / `libiperf3.so`
are bionic builds and are unaffected.

## The patch

Each of the three `set_robust_list` call sites has its `svc #0` replaced with
`movn x0, #0` (i.e. `x0 = -1`). glibc's `INTERNAL_SYSCALL_ERROR_P` then treats
the call as failed and clears `__set_robust_list_avail`, which is exactly the
path glibc takes on a kernel without robust-futex support.

| file offset | before                | after                      |
|-------------|-----------------------|----------------------------|
| `0x22b88`   | `d4000001` `svc #0`   | `92800000` `movn x0, #0`   |
| `0x26078`   | `d4000001` `svc #0`   | `92800000` `movn x0, #0`   |
| `0x260e4`   | `d4000001` `svc #0`   | `92800000` `movn x0, #0`   |

sha1 before: `6ee24537e30ca5182f2e486934f278425fe16cdd`
sha1 after:  `409b79f7e4cc6de895e3ad4d33afb69309cb6f62`

The robust list is only the kernel-assisted cleanup mechanism for
`PTHREAD_MUTEX_ROBUST` mutexes held by a thread that dies. QMESA's memory
stress and error-check logic does not depend on it, so test coverage and
results are unaffected.

## Re-apply / revert

```python
import struct
p = 'app/src/main/jniLibs/arm64-v8a/libqmesa64.so'
b = bytearray(open(p, 'rb').read())
OFFS = (0x22b88, 0x26078, 0x260e4)
SVC, PATCHED = 0xd4000001, 0x92800000
frm, to = SVC, PATCHED          # swap these two to revert
for off in OFFS:
    assert struct.unpack_from('<I', b, off)[0] == frm, hex(off)
    struct.pack_into('<I', b, off, to)
open(p, 'wb').write(bytes(b))
```

To locate the sites again in a different QMESA build, scan for `mov x8, #99`
(`d2800c68`) and take the next `svc #0` (`d4000001`) within a few instructions.

## Alternatives considered

- **Ship a bionic/NDK build of QMESA.** Cleanest, but needs Qualcomm to supply one.
- **Run the app with a system UID** (`android.uid.system` + platform signature):
  zygote then installs the *system* seccomp filter instead of the app filter.
  Requires OEM platform signing, and was not verified on-device.
