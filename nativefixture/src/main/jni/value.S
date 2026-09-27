// Exactly eight bytes, exported so the test resolves the actual linked ELF address.
.text
.p2align 2
.global modkit_fixture_value
#if defined(__aarch64__)
.type modkit_fixture_value, %function
modkit_fixture_value:
    mov w0, #7
    ret
#elif defined(__arm__)
.syntax unified
.arm
.type modkit_fixture_value, %function
modkit_fixture_value:
    mov r0, #7
    bx lr
#elif defined(__i386__) || defined(__x86_64__)
.type modkit_fixture_value, @function
modkit_fixture_value:
    mov $7, %eax
    ret
    nop
    nop
#else
#error Unsupported fixture architecture
#endif
.size modkit_fixture_value, .-modkit_fixture_value
#if defined(__aarch64__) || defined(__arm__)
.section .note.GNU-stack,"",%progbits
#else
.section .note.GNU-stack,"",@progbits
#endif
