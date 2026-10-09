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

// Separate typed JNI getter for engine-neutral recipe discovery; no alias of the value fixture.
.p2align 2
.global Java_dev_modkit_nativefixture_GameActivity_getHealth
#if defined(__aarch64__)
.type Java_dev_modkit_nativefixture_GameActivity_getHealth, %function
Java_dev_modkit_nativefixture_GameActivity_getHealth:
    mov w0, #7
    ret
#elif defined(__arm__)
.type Java_dev_modkit_nativefixture_GameActivity_getHealth, %function
Java_dev_modkit_nativefixture_GameActivity_getHealth:
    mov r0, #7
    bx lr
#else
.type Java_dev_modkit_nativefixture_GameActivity_getHealth, @function
Java_dev_modkit_nativefixture_GameActivity_getHealth:
    mov $7, %eax
    ret
    nop
    nop
#endif
.size Java_dev_modkit_nativefixture_GameActivity_getHealth, .-Java_dev_modkit_nativefixture_GameActivity_getHealth
#if defined(__aarch64__)
// Wide typed getters exercise x0 and d0 independently of the int/float return ABI.
.p2align 2
.global Java_dev_modkit_nativefixture_GameActivity_getStamina
.type Java_dev_modkit_nativefixture_GameActivity_getStamina, %function
Java_dev_modkit_nativefixture_GameActivity_getStamina:
    mov x0, #11
    ret
.size Java_dev_modkit_nativefixture_GameActivity_getStamina, .-Java_dev_modkit_nativefixture_GameActivity_getStamina
.p2align 2
.global Java_dev_modkit_nativefixture_GameActivity_getMoveSpeed
.type Java_dev_modkit_nativefixture_GameActivity_getMoveSpeed, %function
Java_dev_modkit_nativefixture_GameActivity_getMoveSpeed:
    fmov d0, #1.0
    ret
.size Java_dev_modkit_nativefixture_GameActivity_getMoveSpeed, .-Java_dev_modkit_nativefixture_GameActivity_getMoveSpeed
#elif defined(__x86_64__)
// Deterministic 16-byte JNI bodies let the emulator check full-width returns.
.p2align 2
.global Java_dev_modkit_nativefixture_GameActivity_getStamina
.type Java_dev_modkit_nativefixture_GameActivity_getStamina, @function
Java_dev_modkit_nativefixture_GameActivity_getStamina:
    movabs $11, %rax
    ret
    .fill 5, 1, 0x90
.size Java_dev_modkit_nativefixture_GameActivity_getStamina, .-Java_dev_modkit_nativefixture_GameActivity_getStamina
.p2align 2
.global Java_dev_modkit_nativefixture_GameActivity_getMoveSpeed
.type Java_dev_modkit_nativefixture_GameActivity_getMoveSpeed, @function
Java_dev_modkit_nativefixture_GameActivity_getMoveSpeed:
    movabs $0x3ff0000000000000, %rax
    movq %rax, %xmm0
    ret
.size Java_dev_modkit_nativefixture_GameActivity_getMoveSpeed, .-Java_dev_modkit_nativefixture_GameActivity_getMoveSpeed
#endif
// Exact long JNI names for two overloads; the first primitive argument is x2/rdx.
#if defined(__aarch64__) || defined(__x86_64__)
.p2align 2
.global Java_dev_modkit_nativefixture_GameActivity_getAmmo__I
#if defined(__aarch64__)
.type Java_dev_modkit_nativefixture_GameActivity_getAmmo__I, %function
Java_dev_modkit_nativefixture_GameActivity_getAmmo__I:
    add w0, w2, #10
    ret
#else
.type Java_dev_modkit_nativefixture_GameActivity_getAmmo__I, @function
Java_dev_modkit_nativefixture_GameActivity_getAmmo__I:
    lea 10(%rdx), %eax
    ret
    .fill 4, 1, 0x90
#endif
.size Java_dev_modkit_nativefixture_GameActivity_getAmmo__I, .-Java_dev_modkit_nativefixture_GameActivity_getAmmo__I
.p2align 2
.global Java_dev_modkit_nativefixture_GameActivity_getAmmo__J
#if defined(__aarch64__)
.type Java_dev_modkit_nativefixture_GameActivity_getAmmo__J, %function
Java_dev_modkit_nativefixture_GameActivity_getAmmo__J:
    add w0, w2, #20
    ret
#else
.type Java_dev_modkit_nativefixture_GameActivity_getAmmo__J, @function
Java_dev_modkit_nativefixture_GameActivity_getAmmo__J:
    lea 20(%rdx), %eax
    ret
    .fill 4, 1, 0x90
#endif
.size Java_dev_modkit_nativefixture_GameActivity_getAmmo__J, .-Java_dev_modkit_nativefixture_GameActivity_getAmmo__J
#endif
// Narrow JNI results: signed byte/short and unsigned char, each its own body.
#if defined(__aarch64__) || defined(__x86_64__)
.p2align 2
.global Java_dev_modkit_nativefixture_GameActivity_getEnergy
#if defined(__aarch64__)
.type Java_dev_modkit_nativefixture_GameActivity_getEnergy, %function
Java_dev_modkit_nativefixture_GameActivity_getEnergy:
    mov w0, #-7
    ret
#else
.type Java_dev_modkit_nativefixture_GameActivity_getEnergy, @function
Java_dev_modkit_nativefixture_GameActivity_getEnergy:
    mov $-7, %eax
    ret
    nop
    nop
#endif
.size Java_dev_modkit_nativefixture_GameActivity_getEnergy, .-Java_dev_modkit_nativefixture_GameActivity_getEnergy
.p2align 2
.global Java_dev_modkit_nativefixture_GameActivity_getMaxHealth
#if defined(__aarch64__)
.type Java_dev_modkit_nativefixture_GameActivity_getMaxHealth, %function
Java_dev_modkit_nativefixture_GameActivity_getMaxHealth:
    mov w0, #-300
    ret
#else
.type Java_dev_modkit_nativefixture_GameActivity_getMaxHealth, @function
Java_dev_modkit_nativefixture_GameActivity_getMaxHealth:
    mov $-300, %eax
    ret
    nop
    nop
#endif
.size Java_dev_modkit_nativefixture_GameActivity_getMaxHealth, .-Java_dev_modkit_nativefixture_GameActivity_getMaxHealth
.p2align 2
.global Java_dev_modkit_nativefixture_GameActivity_getMagazineSize
#if defined(__aarch64__)
.type Java_dev_modkit_nativefixture_GameActivity_getMagazineSize, %function
Java_dev_modkit_nativefixture_GameActivity_getMagazineSize:
    mov w0, #50000
    ret
#else
.type Java_dev_modkit_nativefixture_GameActivity_getMagazineSize, @function
Java_dev_modkit_nativefixture_GameActivity_getMagazineSize:
    mov $50000, %eax
    ret
    nop
    nop
#endif
.size Java_dev_modkit_nativefixture_GameActivity_getMagazineSize, .-Java_dev_modkit_nativefixture_GameActivity_getMagazineSize
#endif
#if defined(__aarch64__) || defined(__arm__)
.section .note.GNU-stack,"",%progbits
#else
.section .note.GNU-stack,"",@progbits
#endif
