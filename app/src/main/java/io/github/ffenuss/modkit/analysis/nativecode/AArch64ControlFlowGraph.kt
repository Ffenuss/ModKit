package io.github.ffenuss.modkit.analysis.nativecode

enum class AArch64ControlFlowEdgeKind {
    FALLTHROUGH,
    BRANCH,
    CONDITIONAL_TRUE,
    CONDITIONAL_FALSE,
    EXTERNAL_BRANCH,
}

data class AArch64ControlFlowEdge(
    val fromBlockStart: Long,
    val toAddress: Long,
    val kind: AArch64ControlFlowEdgeKind,
)

data class AArch64BasicBlock(
    val startAddress: Long,
    val endExclusive: Long,
    val instructionCount: Int,
    val terminalMnemonic: String,
)

data class AArch64ControlFlowGraph(
    val blocks: List<AArch64BasicBlock>,
    val edges: List<AArch64ControlFlowEdge>,
    val externalTargets: Set<Long>,
    val hasIndirectBranch: Boolean,
) {
    val completeInsideWindow: Boolean
        get() =
            externalTargets.isEmpty() &&
                !hasIndirectBranch
}

/**
 * Builds a bounded control-flow graph from already decoded AArch64
 * instructions. It never follows memory or indirect branches.
 */
object AArch64ControlFlowGraphBuilder {
    fun build(
        disassembly: AArch64Disassembly,
    ): AArch64ControlFlowGraph {
        val instructions =
            disassembly.instructions
        if (instructions.isEmpty()) {
            return AArch64ControlFlowGraph(
                blocks = emptyList(),
                edges = emptyList(),
                externalTargets =
                    emptySet(),
                hasIndirectBranch = false,
            )
        }

        val byAddress =
            instructions.associateBy {
                it.address
            }
        val leaders =
            linkedSetOf(
                instructions.first().address,
            )
        var hasIndirect = false

        instructions.forEachIndexed {
                index,
                instruction,
            ->
            val next =
                instructions.getOrNull(
                    index + 1,
                )
            when {
                isConditional(instruction) -> {
                    directTarget(instruction)
                        ?.takeIf {
                            it in byAddress
                        }
                        ?.let(leaders::add)
                    next?.address
                        ?.let(leaders::add)
                }
                instruction.mnemonic == "b" -> {
                    directTarget(instruction)
                        ?.takeIf {
                            it in byAddress
                        }
                        ?.let(leaders::add)
                    next?.address
                        ?.let(leaders::add)
                }
                instruction.mnemonic == "ret" -> {
                    next?.address
                        ?.let(leaders::add)
                }
                instruction.mnemonic == "br" -> {
                    hasIndirect = true
                    next?.address
                        ?.let(leaders::add)
                }
            }
        }

        val sortedLeaders =
            leaders
                .filter {
                    it in byAddress
                }
                .sorted()
        val blocks =
            mutableListOf<AArch64BasicBlock>()
        val blockInstructions =
            linkedMapOf<
                Long,
                List<AArch64DecodedInstruction>,
            >()

        sortedLeaders.forEachIndexed {
                index,
                start,
            ->
            val end =
                sortedLeaders
                    .getOrNull(index + 1)
                    ?: (
                        instructions.last()
                            .address + 4
                        )
            val rows =
                instructions.filter {
                    it.address in start until end
                }
            if (rows.isEmpty()) {
                return@forEachIndexed
            }
            blockInstructions[start] = rows
            blocks +=
                AArch64BasicBlock(
                    startAddress = start,
                    endExclusive =
                        rows.last().address + 4,
                    instructionCount =
                        rows.size,
                    terminalMnemonic =
                        rows.last().mnemonic,
                )
        }

        val blockStarts =
            blocks.mapTo(
                linkedSetOf(),
            ) {
                it.startAddress
            }
        val edges =
            mutableListOf<AArch64ControlFlowEdge>()
        val external =
            linkedSetOf<Long>()

        blocks.forEachIndexed {
                index,
                block,
            ->
            val rows =
                blockInstructions
                    .getValue(
                        block.startAddress,
                    )
            val last =
                rows.last()
            val nextBlock =
                blocks.getOrNull(
                    index + 1,
                )
            when {
                isConditional(last) -> {
                    directTarget(last)
                        ?.let {
                            target ->
                            edges +=
                                AArch64ControlFlowEdge(
                                    fromBlockStart =
                                        block.startAddress,
                                    toAddress =
                                        target,
                                    kind =
                                        if (
                                            target in
                                            blockStarts
                                        ) {
                                            AArch64ControlFlowEdgeKind
                                                .CONDITIONAL_TRUE
                                        } else {
                                            external +=
                                                target
                                            AArch64ControlFlowEdgeKind
                                                .EXTERNAL_BRANCH
                                        },
                                )
                        }
                    nextBlock
                        ?.let {
                            edges +=
                                AArch64ControlFlowEdge(
                                    fromBlockStart =
                                        block.startAddress,
                                    toAddress =
                                        it.startAddress,
                                    kind =
                                        AArch64ControlFlowEdgeKind
                                            .CONDITIONAL_FALSE,
                                )
                        }
                }
                last.mnemonic == "b" -> {
                    directTarget(last)
                        ?.let {
                            target ->
                            edges +=
                                AArch64ControlFlowEdge(
                                    fromBlockStart =
                                        block.startAddress,
                                    toAddress =
                                        target,
                                    kind =
                                        if (
                                            target in
                                            blockStarts
                                        ) {
                                            AArch64ControlFlowEdgeKind
                                                .BRANCH
                                        } else {
                                            external +=
                                                target
                                            AArch64ControlFlowEdgeKind
                                                .EXTERNAL_BRANCH
                                        },
                                )
                        }
                }
                last.mnemonic == "ret" ||
                    last.mnemonic == "br" ->
                    Unit
                else ->
                    nextBlock
                        ?.let {
                            edges +=
                                AArch64ControlFlowEdge(
                                    fromBlockStart =
                                        block.startAddress,
                                    toAddress =
                                        it.startAddress,
                                    kind =
                                        AArch64ControlFlowEdgeKind
                                            .FALLTHROUGH,
                                )
                        }
            }
        }

        return AArch64ControlFlowGraph(
            blocks = blocks,
            edges = edges,
            externalTargets =
                external,
            hasIndirectBranch =
                hasIndirect,
        )
    }

    private fun isConditional(
        instruction:
            AArch64DecodedInstruction,
    ): Boolean =
        instruction.mnemonic
            .startsWith("b.") ||
            instruction.mnemonic == "cbz" ||
            instruction.mnemonic == "cbnz" ||
            instruction.mnemonic == "tbz" ||
            instruction.mnemonic == "tbnz"

    private fun directTarget(
        instruction:
            AArch64DecodedInstruction,
    ): Long? {
        val token =
            instruction.operands
                .substringAfterLast(',')
                .trim()
        if (!token.startsWith("0x")) {
            return null
        }
        return token
            .removePrefix("0x")
            .toLongOrNull(16)
    }
}
