package io.github.mangi.eta.agent.terminal

/**
 * `edit_file` 的统一差异（Unified Diff）视图。
 *
 * 只对比模型本次给出的 old_text / new_text 片段，而不是整份文件：范围与模型自己的输入同阶，
 * 既能精确还原这次改了什么，也不会把大文件正文再复制一遍进上下文。
 *
 * 采用 Myers O(ND) 最短编辑脚本。超过行数、编辑距离或输出字符预算时直接放弃并返回 null，
 * 由调用方回退到纯文本摘要——宁可不展示差异，也不展示一个错误或误导性的差异。
 */
internal object UnifiedDiff {

    /** 差异结果；[diff] 是可直接渲染的 Unified Diff 文本。 */
    data class Result(
        val diff: String,
        /** 差异因超出输出预算被截断，只呈现了前若干个 hunk。 */
        val truncated: Boolean,
    )

    /** 编辑脚本中的一步；携带原文，渲染时无需再回溯两侧数组。 */
    private class Step(val op: Int, val text: String)

    fun compute(oldText: String, newText: String): Result? {
        if (oldText == newText) return null
        val oldLines = splitLines(oldText)
        val newLines = splitLines(newText)
        if (oldLines.size > MAX_SIDE_LINES || newLines.size > MAX_SIDE_LINES) return null
        val ops = myers(oldLines, newLines) ?: return null
        val steps = annotate(ops, oldLines, newLines)
        if (steps.none { it.op != OP_EQUAL }) return null

        val rendered = renderHunks(steps) ?: return null
        return Result(diff = rendered.first, truncated = rendered.second)
    }

    /**
     * 按 '\n' 切分而不是 `lines()`：后者会把 "a\n" 与 "a" 都切成 ["a"]，
     * 从而漏掉“删掉行尾换行”这类真实改动。空串视为零行，避免凭空多出一个空行。
     */
    private fun splitLines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.split('\n')

    private const val OP_EQUAL = 0
    private const val OP_DELETE = 1
    private const val OP_INSERT = 2

    private const val MAX_SIDE_LINES = 400
    private const val MAX_EDIT_DISTANCE = 400

    /**
     * 差异正文的字符预算。
     *
     * 载荷经工具消息的 `command` 通道传输，Runtime 侧按 [AgentRuntimeWire] 的
     * MAX_DRAIN_CONTENT_CHARS(16_000) 截断超长字符串；而载荷是 JSON，换行、引号与
     * 反斜杠都要转义，最坏情况下长度接近翻倍。这里留出一倍以上余量，确保编码后的
     * 载荷不会被中途截断成非法 JSON。
     */
    private const val MAX_DIFF_CHARS = 6_000
    private const val CONTEXT_LINES = 3

    /**
     * Myers 最短编辑脚本，返回按顺序的操作码序列。
     * 编辑距离超过 [MAX_EDIT_DISTANCE] 时返回 null，不在大改动上烧 CPU。
     */
    private fun myers(a: List<String>, b: List<String>): List<Int>? {
        val n = a.size
        val m = b.size
        val limit = minOf(MAX_EDIT_DISTANCE, n + m)
        val off = limit + 1
        val v = IntArray(2 * limit + 3) { -1 }
        v[off + 1] = 0
        // trace[d] 是第 d 步开始前的 V 快照，回溯时据此还原每一步的移动方向。
        val trace = ArrayList<IntArray>(limit + 1)

        var reached = false
        var distance = 0
        while (distance <= limit) {
            trace += v.copyOf()
            var k = -distance
            while (k <= distance) {
                val index = off + k
                var x = if (k == -distance || (k != distance && v[index - 1] < v[index + 1])) {
                    v[index + 1]
                } else {
                    v[index - 1] + 1
                }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[index] = x
                if (x >= n && y >= m) {
                    reached = true
                    break
                }
                k += 2
            }
            if (reached) break
            distance++
        }
        if (!reached) return null

        val ops = ArrayList<Int>(n + m)
        var x = n
        var y = m
        for (step in trace.indices.reversed()) {
            val snapshot = trace[step]
            val k = x - y
            val previousK = if (k == -step || (k != step && snapshot[off + k - 1] < snapshot[off + k + 1])) {
                k + 1
            } else {
                k - 1
            }
            val previousX = snapshot[off + previousK]
            val previousY = previousX - previousK
            while (x > previousX && y > previousY) {
                ops += OP_EQUAL
                x--
                y--
            }
            if (step > 0) {
                if (x == previousX) {
                    ops += OP_INSERT
                    y--
                } else {
                    ops += OP_DELETE
                    x--
                }
            }
        }
        ops.reverse()
        return ops
    }

    /** 给操作码配上原文：保留与删除取自旧文本，新增取自新文本。 */
    private fun annotate(ops: List<Int>, oldLines: List<String>, newLines: List<String>): List<Step> {
        var ai = 0
        var bi = 0
        return ops.map { op ->
            when (op) {
                OP_DELETE -> Step(op, oldLines[ai++])
                OP_INSERT -> Step(op, newLines[bi++])
                else -> {
                    val text = oldLines[ai++]
                    bi++
                    Step(op, text)
                }
            }
        }
    }

    /**
     * 把编辑脚本折叠成带上下文的 hunk 列表。相邻改动之间的未变行不超过 2×上下文时合并为
     * 同一个 hunk，与 `diff -u` 的行为一致。返回 (文本, 是否因超出预算被截断)。
     */
    private fun renderHunks(steps: List<Step>): Pair<String, Boolean>? {
        val changed = steps.indices.filter { steps[it].op != OP_EQUAL }
        if (changed.isEmpty()) return null

        val groups = ArrayList<IntRange>()
        var start = changed.first()
        var previous = changed.first()
        for (index in changed.drop(1)) {
            if (index - previous - 1 <= CONTEXT_LINES * 2) {
                previous = index
            } else {
                groups += start..previous
                start = index
                previous = index
            }
        }
        groups += start..previous

        val output = StringBuilder()
        var truncated = false
        for (group in groups) {
            val from = maxOf(0, group.first - CONTEXT_LINES)
            val to = minOf(steps.lastIndex, group.last + CONTEXT_LINES)
            val slice = steps.subList(from, to + 1)
            val header = hunkHeader(steps, from, slice)
            val body = buildString {
                slice.forEach { step ->
                    append(
                        when (step.op) {
                            OP_DELETE -> '-'
                            OP_INSERT -> '+'
                            else -> ' '
                        },
                    )
                    append(step.text)
                    append('\n')
                }
            }
            if (output.length + header.length + body.length + 1 > MAX_DIFF_CHARS) {
                truncated = true
                break
            }
            if (output.isNotEmpty()) output.append('\n')
            output.append(header).append('\n').append(body.trimEnd('\n'))
        }
        if (output.isEmpty()) return null
        return output.toString() to truncated
    }

    /** `@@ -旧起始,旧行数 +新起始,新行数 @@`；某一侧为空时起始行按惯例写 0。 */
    private fun hunkHeader(steps: List<Step>, from: Int, slice: List<Step>): String {
        var oldLine = 1
        var newLine = 1
        for (index in 0 until from) {
            when (steps[index].op) {
                OP_EQUAL -> { oldLine++; newLine++ }
                OP_DELETE -> oldLine++
                else -> newLine++
            }
        }
        var oldCount = 0
        var newCount = 0
        slice.forEach { step ->
            when (step.op) {
                OP_EQUAL -> { oldCount++; newCount++ }
                OP_DELETE -> oldCount++
                else -> newCount++
            }
        }
        return "@@ -${if (oldCount == 0) 0 else oldLine},$oldCount " +
            "+${if (newCount == 0) 0 else newLine},$newCount @@"
    }
}
