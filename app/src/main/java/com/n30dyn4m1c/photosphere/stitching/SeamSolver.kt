package com.n30dyn4m1c.photosphere.stitching

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The sparse per-pixel label data the seam solver works on.
 *
 * Every pixel of the reduced label grid lists the frames that cover it, their
 * colours as sampled from those frames, and the cost of assigning the pixel to
 * each of them. The arrays are packed per node so the whole thing stays flat:
 * node [n] owns `nodeLabelCount[n]` consecutive entries in [nodeLabels],
 * [nodeData] and (three per entry) [nodeColors], starting at
 * `offsets[n]`. [nodeMean] is the mean of the covering colours at each pixel,
 * which is what a frame *not* covering that pixel is said to have recorded
 * there — the fill value that keeps the seam cost a true metric (see
 * [SeamSolver]).
 */
internal class PackedGrid(
    val width: Int,
    val height: Int,
    val labelCount: Int,
    val nodeLabelCount: IntArray,
    val nodeLabels: IntArray,
    val nodeData: FloatArray,
    val nodeColors: FloatArray,
    val nodeMean: FloatArray,
) {
    val nodeCount: Int = width * height

    /** Where each node's packed entries begin; `offsets[nodeCount]` is the total. */
    val offsets: IntArray = IntArray(nodeCount + 1).also {
        for (n in 0 until nodeCount) it[n + 1] = it[n] + nodeLabelCount[n]
    }
}

/**
 * The multi-label seam solver: assigns every canvas pixel to exactly one frame.
 *
 * Where the renderer's cross-fade lets every overlapping frame contribute to a
 * pixel, seam carving asks which single frame should paint it and cuts there,
 * reserving the blend for a few pixels either side of the cut. The assignment
 * is the minimizer of an energy over the label grid:
 *
 * ```
 * E(f) = Σ_p D(p, f(p))  +  Σ_{edges (p,q)} λ · ( |I_fp(p) − I_fq(p)| + |I_fp(q) − I_fq(q)| )
 * ```
 *
 * The data term `D(p, i)` says how far frame i's sample sits from the mean of
 * every frame covering p — the frame closest to what the overlap actually
 * shows wins the interior. The edge term is the *seam cost*: cutting between
 * pixels p and q with different frames costs how differently the two frames
 * see p and q, so a cut through a region where the frames agree is cheap and a
 * cut through a ghost is expensive. `λ` scales the two against each other.
 *
 * The energy is minimized with α-expansion (Boykov–Veksler–Zabih): each label
 * in turn offers every pixel the move "keep your frame, or switch to this one",
 * and the binary subproblem is an s-t min-cut on the pixel grid. The smoothness
 * term is a metric — the colour difference at a pixel between any two labels is
 * a Euclidean distance, with a frame that does not cover the pixel filled by
 * that pixel's mean colour, and the sum over two endpoints of two metrics is a
 * metric — so every expansion move is submodular and its exact min-cut can
 * never raise the energy. A couple of passes over the labels converge to a
 * local minimum that is typically global for the small label sets a capture
 * produces.
 *
 * Everything here is plain Kotlin with no OpenCV, so the whole solver is
 * exercised by JVM unit tests against an exhaustive search on tiny grids.
 */
internal object SeamSolver {

    /** Residual edges at or below this carry no flow. */
    private const val EPS = 1e-9

    /** A label the current expansion move forbids: switching to it costs this. */
    private const val INF_UNARY = 1e15

    /** Solves the assignment, returning one frame index per grid pixel. */
    fun solve(
        grid: PackedGrid,
        smoothLambda: Double,
        maxPasses: Int = 2,
        onExpansion: () -> Unit = {},
        wrapLongitude: Boolean = false,
    ): IntArray {
        val labels = greedyLabels(grid)

        // Large frames first: an expansion of a frame that covers many pixels
        // does the most work, and solving it before the smaller ones keeps each
        // later move within the context the big frame already set.
        val order = labelOrder(grid)
        repeat(maxPasses) {
            var changed = false
            for (alpha in order) {
                if (expandTo(grid, smoothLambda, labels, alpha, wrapLongitude)) changed = true
                onExpansion()
            }
            if (!changed) return labels
        }
        return labels
    }

    /**
     * The energy [solve] minimizes, evaluated for [labels] — kept for tests and
     * diagnostics to reason about a solved assignment.
     */
    fun energy(grid: PackedGrid, labels: IntArray, smoothLambda: Double): Double {
        var total = 0.0
        for (n in 0 until grid.nodeCount) {
            val off = grid.offsets[n]
            for (j in 0 until grid.nodeLabelCount[n]) {
                if (grid.nodeLabels[off + j] == labels[n]) {
                    total += grid.nodeData[off + j]
                    break
                }
            }
        }
        // Four separate scratch colours so an edge can never read a stale one.
        val fpAtN = FloatArray(3)
        val fqAtN = FloatArray(3)
        val fpAtM = FloatArray(3)
        val fqAtM = FloatArray(3)
        for (r in 0 until grid.height) {
            for (c in 0 until grid.width) {
                val n = r * grid.width + c
                val fp = labels[n]
                if (fp < 0) continue
                colorOf(grid, n, fp, fpAtN, 0)
                if (c + 1 < grid.width) {
                    val m = n + 1
                    val fq = labels[m]
                    if (fq >= 0) {
                        colorOf(grid, n, fq, fqAtN, 0)
                        colorOf(grid, m, fp, fpAtM, 0)
                        colorOf(grid, m, fq, fqAtM, 0)
                        total += smoothLambda * (dist(fpAtN, 0, fqAtN, 0) + dist(fpAtM, 0, fqAtM, 0))
                    }
                }
                if (r + 1 < grid.height) {
                    val m = n + grid.width
                    val fq = labels[m]
                    if (fq >= 0) {
                        colorOf(grid, n, fq, fqAtN, 0)
                        colorOf(grid, m, fp, fpAtM, 0)
                        colorOf(grid, m, fq, fqAtM, 0)
                        total += smoothLambda * (dist(fpAtN, 0, fqAtN, 0) + dist(fpAtM, 0, fqAtM, 0))
                    }
                }
            }
        }
        return total
    }

    /** Each node's cheapest label on the data term alone; also the solver's start. */
    internal fun greedyLabels(grid: PackedGrid): IntArray {
        val labels = IntArray(grid.nodeCount) { -1 }
        for (n in 0 until grid.nodeCount) {
            val count = grid.nodeLabelCount[n]
            if (count == 0) continue
            val off = grid.offsets[n]
            var best = grid.nodeLabels[off]
            var bestCost = grid.nodeData[off].toDouble()
            for (j in 1 until count) {
                val cost = grid.nodeData[off + j].toDouble()
                if (cost < bestCost) {
                    bestCost = cost
                    best = grid.nodeLabels[off + j]
                }
            }
            labels[n] = best
        }
        return labels
    }

    /** Labels that cover at least one pixel, largest coverage first. */
    private fun labelOrder(grid: PackedGrid): IntArray {
        val coverage = IntArray(grid.labelCount)
        for (n in 0 until grid.nodeCount) {
            val off = grid.offsets[n]
            for (j in 0 until grid.nodeLabelCount[n]) coverage[grid.nodeLabels[off + j]]++
        }
        return (0 until grid.labelCount)
            .filter { coverage[it] > 0 }
            .sortedByDescending { coverage[it] }
            .toIntArray()
    }

    /**
     * One α-expansion move: every pixel may keep its current frame or switch to
     * [alpha], solved exactly as an s-t min-cut. Returns whether any pixel moved.
     *
     * Only the pixels [alpha] covers can move; every other pixel is pinned, and
     * an edge that joins a moving pixel to a pinned one collapses into a unary
     * on the moving side rather than a graph node of its own. The graph is
     * therefore built over the moving pixels alone.
     */
    private fun expandTo(
        grid: PackedGrid,
        smoothLambda: Double,
        labels: IntArray,
        alpha: Int,
        wrapLongitude: Boolean = false,
    ): Boolean {
        // Nodes that may move: exactly those [alpha] covers.
        val active = BooleanArray(grid.nodeCount)
        var anyActive = false
        for (n in 0 until grid.nodeCount) {
            if (grid.nodeLabelCount[n] == 0) continue
            val off = grid.offsets[n]
            for (j in 0 until grid.nodeLabelCount[n]) {
                if (grid.nodeLabels[off + j] == alpha) {
                    active[n] = true
                    anyActive = true
                    break
                }
            }
        }
        if (!anyActive) return false

        val vertexOf = IntArray(grid.nodeCount) { -1 }
        var vertexCount = 0
        for (n in 0 until grid.nodeCount) if (active[n]) vertexOf[n] = vertexCount++

        val flow = MaxFlow(vertexCount + 2)
        val source = vertexCount
        val sink = vertexCount + 1

        // Per-vertex costs of keeping the current label (x = 0) or switching to
        // alpha (x = 1); the edge terms below accumulate into them.
        val u0 = DoubleArray(vertexCount)
        val u1 = DoubleArray(vertexCount)
        for (n in 0 until grid.nodeCount) {
            val v = vertexOf[n]
            if (v == -1) continue
            u0[v] = dataOf(grid, n, labels[n])
            u1[v] = dataOf(grid, n, alpha)
        }

        // The smoothness needs each pixel's colour under its current label and
        // under alpha (mean-filled where alpha is absent), computed once per
        // expansion and reused across all the edges that touch the pixel.
        val curColor = FloatArray(grid.nodeCount * 3)
        val alphaColor = FloatArray(grid.nodeCount * 3)
        for (n in 0 until grid.nodeCount) {
            if (grid.nodeLabelCount[n] == 0) continue
            colorOf(grid, n, labels[n], curColor, n * 3)
            colorOf(grid, n, alpha, alphaColor, n * 3)
        }

        // Each grid edge becomes, for the binary move, either a full pairwise
        // term (both endpoints can switch) or a unary on the switching endpoint
        // (the other is pinned). The pairwise term decomposes into a constant,
        // two unaries and one n-link; the n-link is exactly the submodularity
        // surplus (B + C − A) / 2, which the metric property keeps non-negative.
        val scratch = FloatArray(6)
        for (r in 0 until grid.height) {
            for (c in 0 until grid.width) {
                val n = r * grid.width + c
                if (grid.nodeLabelCount[n] == 0) continue
                if (c + 1 < grid.width) {
                    val m = n + 1
                    if (grid.nodeLabelCount[m] > 0) {
                        addEdgeTerm(
                            grid, smoothLambda, labels, active, vertexOf, flow,
                            n, m, curColor, alphaColor, scratch, u0, u1,
                        )
                    }
                }
                // A full-turn canvas is a cylinder in longitude: the first and
                // last columns sample the same world direction at the ±180°
                // meridian, so the seam must be allowed to cut *between* them
                // only at the same cost as anywhere else. Without this edge the
                // two sides of the meridian could pick different winning frames
                // with zero smoothness penalty and show a hard, unfeathered
                // seam when the panorama is viewed wrapped.
                if (wrapLongitude && c == 0 && grid.width > 1) {
                    val m = n + grid.width - 1
                    if (grid.nodeLabelCount[m] > 0) {
                        addEdgeTerm(
                            grid, smoothLambda, labels, active, vertexOf, flow,
                            n, m, curColor, alphaColor, scratch, u0, u1,
                        )
                    }
                }
                if (r + 1 < grid.height) {
                    val m = n + grid.width
                    if (grid.nodeLabelCount[m] > 0) {
                        addEdgeTerm(
                            grid, smoothLambda, labels, active, vertexOf, flow,
                            n, m, curColor, alphaColor, scratch, u0, u1,
                        )
                    }
                }
            }
        }

        // t-links encode the unaries: with x = 0 on the source side, cutting
        // p→sink pays "keep" and cutting source→p pays "switch". A common
        // offset keeps both capacities non-negative without changing the
        // minimizer.
        for (n in 0 until grid.nodeCount) {
            val v = vertexOf[n]
            if (v == -1) continue
            val offset = max(0.0, max(-u0[v], -u1[v]))
            if (u1[v] + offset > 0.0) flow.addEdge(source, v, u1[v] + offset)
            if (u0[v] + offset > 0.0) flow.addEdge(v, sink, u0[v] + offset)
        }

        flow.maxflow(source, sink)
        val keep = flow.reachableFrom(source)

        var changed = false
        for (n in 0 until grid.nodeCount) {
            val v = vertexOf[n]
            if (v == -1) continue
            // Not reachable from the source means the cut put the pixel on the
            // switch side.
            if (!keep[v] && labels[n] != alpha) {
                labels[n] = alpha
                changed = true
            }
        }
        return changed
    }

    /**
     * Adds one grid edge's contribution to the expansion graph.
     *
     * [n] is the lower-left endpoint of a canonical (right or down) edge to [m];
     * [curColor]/[alphaColor] hold each node's colour under its current label
     * and under the expansion label, and [scratch] is a 6-float buffer for the
     * two cross colours the edge needs.
     */
    private fun addEdgeTerm(
        grid: PackedGrid,
        smoothLambda: Double,
        labels: IntArray,
        active: BooleanArray,
        vertexOf: IntArray,
        flow: MaxFlow,
        n: Int,
        m: Int,
        curColor: FloatArray,
        alphaColor: FloatArray,
        scratch: FloatArray,
        u0: DoubleArray,
        u1: DoubleArray,
    ) {
        val activeN = active[n]
        val activeM = active[m]
        if (!activeN && !activeM) return

        val fp = labels[n]
        val fq = labels[m]
        // scratch[0..2] = I_fq(p), scratch[3..5] = I_fp(q).
        colorOf(grid, n, fq, scratch, 0)
        colorOf(grid, m, fp, scratch, 3)

        val a = smoothLambda * (dist(curColor, n * 3, scratch, 0) + dist(scratch, 3, curColor, m * 3))
        val b = smoothLambda * (dist(curColor, n * 3, alphaColor, n * 3) + dist(scratch, 3, alphaColor, m * 3))
        val c = smoothLambda * (dist(alphaColor, n * 3, scratch, 0) + dist(alphaColor, m * 3, curColor, m * 3))

        when {
            activeN && activeM -> {
                val vN = vertexOf[n]
                val vM = vertexOf[m]
                val cutWeight = max(0.0, (b + c - a) / 2.0)
                u1[vN] += c - a - cutWeight
                u1[vM] += b - a - cutWeight
                if (cutWeight > 0.0) flow.addUndirectedEdge(vN, vM, cutWeight)
            }
            activeN -> {
                val vN = vertexOf[n]
                u0[vN] += a
                u1[vN] += c
            }
            else -> {
                val vM = vertexOf[m]
                u0[vM] += a
                u1[vM] += b
            }
        }
    }

    /** Cost of assigning [node] the label [label], from the packed data. */
    private fun dataOf(grid: PackedGrid, node: Int, label: Int): Double {
        val off = grid.offsets[node]
        for (j in 0 until grid.nodeLabelCount[node]) {
            if (grid.nodeLabels[off + j] == label) return grid.nodeData[off + j].toDouble()
        }
        return INF_UNARY
    }

    /**
     * The colour of [label] at [node], written into [out] at [outOffset].
     *
     * A label the node's pixel is not covered by reads that pixel's mean colour
     * — the fill that makes the pairwise seam cost a metric, and the whole
     * reason the expansion moves stay submodular.
     */
    private fun colorOf(
        grid: PackedGrid,
        node: Int,
        label: Int,
        out: FloatArray,
        outOffset: Int,
    ) {
        val off = grid.offsets[node]
        for (j in 0 until grid.nodeLabelCount[node]) {
            if (grid.nodeLabels[off + j] == label) {
                val base = (off + j) * 3
                out[outOffset] = grid.nodeColors[base]
                out[outOffset + 1] = grid.nodeColors[base + 1]
                out[outOffset + 2] = grid.nodeColors[base + 2]
                return
            }
        }
        val base = node * 3
        out[outOffset] = grid.nodeMean[base]
        out[outOffset + 1] = grid.nodeMean[base + 1]
        out[outOffset + 2] = grid.nodeMean[base + 2]
    }

    /** Euclidean distance between two RGB triples, at the given array offsets. */
    private fun dist(a: FloatArray, aOffset: Int, b: FloatArray, bOffset: Int): Double {
        val d0 = a[aOffset] - b[bOffset]
        val d1 = a[aOffset + 1] - b[bOffset + 1]
        val d2 = a[aOffset + 2] - b[bOffset + 2]
        return sqrt((d0 * d0 + d1 * d1 + d2 * d2).toDouble())
    }

    /**
     * Dinic's max-flow over a directed graph of real capacities.
     *
     * Every expansion builds a fresh graph and the buffers are grown to fit, so
     * a long stitch churns a fixed pool rather than a new array per move. The
     * level graph stays shallow here — the source reaches every moving pixel
     * directly — so the recursive blocking-flow DFS is well within the stack.
     */
    internal class MaxFlow(private val vertexCount: Int) {

        private val head = IntArray(vertexCount) { -1 }
        private var to = IntArray(16)
        private var cap = DoubleArray(16)
        private var next = IntArray(16)
        private var edgeCount = 0

        private val level = IntArray(vertexCount)
        private val queue = IntArray(vertexCount)
        private val iter = IntArray(vertexCount)

        private fun addArc(u: Int, v: Int, c: Double) {
            if (edgeCount >= to.size) {
                val grown = to.size * 2
                to = to.copyOf(grown)
                cap = cap.copyOf(grown)
                next = next.copyOf(grown)
            }
            to[edgeCount] = v
            cap[edgeCount] = c
            next[edgeCount] = head[u]
            head[u] = edgeCount
            edgeCount++
        }

        /** Directed edge u→v of capacity [c], plus its zero-capacity reverse. */
        fun addEdge(u: Int, v: Int, c: Double) {
            if (c <= 0.0) return
            addArc(u, v, c)
            addArc(v, u, 0.0)
        }

        /**
         * Undirected edge of capacity [c]: cut for a fixed amount whichever way
         * the two endpoints split, which is what a `[x_p ≠ x_q]` penalty means.
         */
        fun addUndirectedEdge(u: Int, v: Int, c: Double) {
            if (c <= 0.0) return
            addArc(u, v, c)
            addArc(v, u, c)
        }

        private fun bfs(source: Int, sink: Int): Boolean {
            level.fill(-1)
            var read = 0
            var write = 0
            level[source] = 0
            queue[write++] = source
            while (read < write) {
                val u = queue[read++]
                var e = head[u]
                while (e != -1) {
                    val v = to[e]
                    if (cap[e] > EPS && level[v] < 0) {
                        level[v] = level[u] + 1
                        queue[write++] = v
                    }
                    e = next[e]
                }
            }
            return level[sink] >= 0
        }

        private fun dfs(u: Int, sink: Int, pushed: Double): Double {
            if (u == sink) return pushed
            var e = iter[u]
            while (e != -1) {
                val v = to[e]
                if (cap[e] > EPS && level[v] == level[u] + 1) {
                    val flow = dfs(v, sink, min(pushed, cap[e]))
                    if (flow > EPS) {
                        cap[e] -= flow
                        cap[e xor 1] += flow
                        return flow
                    }
                }
                e = next[e]
                iter[u] = e
            }
            return 0.0
        }

        fun maxflow(source: Int, sink: Int): Double {
            var total = 0.0
            while (bfs(source, sink)) {
                System.arraycopy(head, 0, iter, 0, vertexCount)
                while (true) {
                    val flow = dfs(source, sink, Double.MAX_VALUE / 4)
                    if (flow <= EPS) break
                    total += flow
                }
            }
            return total
        }

        /** Nodes reachable from [source] through positive residual capacity. */
        fun reachableFrom(source: Int): BooleanArray {
            val seen = BooleanArray(vertexCount)
            var read = 0
            var write = 0
            queue[write++] = source
            seen[source] = true
            while (read < write) {
                val u = queue[read++]
                var e = head[u]
                while (e != -1) {
                    val v = to[e]
                    if (cap[e] > EPS && !seen[v]) {
                        seen[v] = true
                        queue[write++] = v
                    }
                    e = next[e]
                }
            }
            return seen
        }
    }
}
