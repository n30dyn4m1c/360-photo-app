package com.n30dyn4m1c.photosphere.stitching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Covers the pure-Kotlin seam solver: the Dinic max-flow against an exhaustive
 * minimum cut, and the α-expansion assignment against an exhaustive search over
 * every labelling on tiny grids.
 */
class SeamSolverTest {

    // -- Max flow -----------------------------------------------------------

    @Test
    fun `max flow saturates the minimum cut on random small graphs`() {
        val nodeCount = 6
        val source = 0
        val sink = 5
        val random = Random(42)
        repeat(20) {
            val graph = SeamSolver.MaxFlow(nodeCount)
            val edges = ArrayList<IntArray>()
            for (u in 0 until nodeCount) {
                for (v in 0 until nodeCount) {
                    if (u == v) continue
                    val capacity = random.nextInt(20)
                    if (capacity > 0) {
                        edges += intArrayOf(u, v, capacity)
                        graph.addEdge(u, v, capacity.toDouble())
                    }
                }
            }
            val flow = graph.maxflow(source, sink)
            val cut = bruteForceMinCut(nodeCount, edges, source, sink)
            assertEquals("flow $flow must equal min cut $cut", cut, flow, 1e-6)
        }
    }

    @Test
    fun `an edge of zero capacity carries no flow`() {
        val graph = SeamSolver.MaxFlow(4)
        graph.addEdge(0, 1, 0.0)
        graph.addEdge(0, 2, 5.0)
        graph.addEdge(2, 3, 5.0)
        graph.addEdge(1, 3, 0.0)
        assertEquals(5.0, graph.maxflow(0, 3), 1e-9)
    }

    @Test
    fun `reachable nodes bound a min cut of the max flow value`() {
        val graph = SeamSolver.MaxFlow(4)
        graph.addEdge(0, 1, 3.0)
        graph.addEdge(1, 2, 3.0)
        graph.addEdge(2, 3, 3.0)
        val flow = graph.maxflow(0, 3)
        val reachable = graph.reachableFrom(0)
        // The reachable set is the source side of a min cut: source in, sink
        // out, and the graph edges it separates carry exactly the flow value.
        assertTrue(reachable[0])
        assertTrue(!reachable[3])
        val edges = listOf(
            intArrayOf(0, 1, 3),
            intArrayOf(1, 2, 3),
            intArrayOf(2, 3, 3),
        )
        var cutCapacity = 0.0
        for (edge in edges) {
            if (reachable[edge[0]] && !reachable[edge[1]]) cutCapacity += edge[2]
        }
        assertEquals(flow, cutCapacity, 1e-9)
    }

    // -- The assignment ------------------------------------------------------

    @Test
    fun `the solved labelling is locally optimal under single-pixel flips`() {
        // α-expansion converges to a labelling no single pixel can improve. If
        // the graph construction were wrong, the min-cuts would minimize the
        // wrong energy and this would fail — a stronger check than comparing
        // energies, since the solver's local optimum need not be the global one.
        val random = Random(7)
        repeat(10) {
            val grid = randomGrid(width = 3, height = 3, labelCount = 2, random = random)
            val lambda = 3.0
            val solved = SeamSolver.solve(grid, lambda, maxPasses = 4)
            val base = SeamSolver.energy(grid, solved, lambda)
            for (node in 0 until grid.nodeCount) {
                val current = solved[node]
                for (label in 0 until grid.labelCount) {
                    if (label == current) continue
                    val flipped = solved.copyOf()
                    flipped[node] = label
                    val energy = SeamSolver.energy(grid, flipped, lambda)
                    assertTrue(
                        "single flip at $node to $label improved $base to $energy",
                        energy >= base - 1e-6,
                    )
                }
            }
        }
    }

    @Test
    fun `the solved labelling never lands below the true optimum`() {
        val random = Random(11)
        repeat(6) {
            val grid = randomGrid(width = 2, height = 3, labelCount = 3, random = random)
            val lambda = 2.0
            val solved = SeamSolver.solve(grid, lambda, maxPasses = 4)
            val energy = SeamSolver.energy(grid, solved, lambda)
            val best = exhaustiveMinimum(grid, lambda)
            // A sanity floor: α-expansion can only approximate the global
            // optimum, but it must never do better than it.
            assertEquals("solver landed below the true minimum", best, energy, 1e-6)
        }
    }

    @Test
    fun `the solver improves on the data-only start`() {
        val random = Random(3)
        repeat(6) {
            val grid = randomGrid(width = 3, height = 2, labelCount = 3, random = random)
            val lambda = 4.0
            val greedy = SeamSolver.energy(grid, SeamSolver.greedyLabels(grid), lambda)
            val solved = SeamSolver.solve(grid, lambda, maxPasses = 4)
            val energy = SeamSolver.energy(grid, solved, lambda)
            assertTrue("solver did not improve on the greedy start", energy <= greedy + 1e-6)
        }
    }

    @Test
    fun `the seam follows the place where the frames agree`() {
        // A strip of four pixels and two frames. Both frames see grey in the
        // middle; frame 0 owns the left two pixels, frame 1 the right two, and
        // the best cut is between pixels 1 and 2, where the frames agree
        // exactly. The seam must land there rather than at either end.
        val width = 4
        val height = 1
        val labelCount = 2
        val grey = floatArrayOf(100f, 100f, 100f)
        val bright = floatArrayOf(200f, 200f, 200f)
        val data = FloatArray(width * height * labelCount)
        val colors = FloatArray(width * height * labelCount * 3)
        for (n in 0 until width * height) {
            // Frame 0: left territory (data 0), the border on the right.
            data[n * 2] = if (n <= 1) 0f else 10_000f
            colors[n * 6] = grey[0]; colors[n * 6 + 1] = grey[1]; colors[n * 6 + 2] = grey[2]
            // Frame 1: right territory, grey where it agrees with frame 0.
            data[n * 2 + 1] = if (n >= 2) 0f else 10_000f
            val color = if (n >= 2) grey else bright
            val base = (n * 2 + 1) * 3
            colors[base] = color[0]; colors[base + 1] = color[1]; colors[base + 2] = color[2]
        }
        val nodeLabelCount = IntArray(width * height) { 2 }
        val nodeLabels = IntArray(width * height * 2) { it % 2 }
        val nodeMean = FloatArray(width * height * 3) { grey[0] }
        val grid = PackedGrid(width, height, labelCount, nodeLabelCount, nodeLabels, data, colors, nodeMean)

        val solved = SeamSolver.solve(grid, smoothLambda = 1.0, maxPasses = 4)
        assertArrayEquals(intArrayOf(0, 0, 1, 1), solved)
        assertEquals(
            exhaustiveMinimum(grid, 1.0),
            SeamSolver.energy(grid, solved, 1.0),
            1e-6,
        )
    }

    @Test
    fun `a single feasible label is respected everywhere`() {
        // Only label 1 has finite cost at every pixel: everything must take it.
        val width = 3
        val height = 2
        val data = FloatArray(width * height * 2)
        val colors = FloatArray(width * height * 2 * 3)
        for (n in 0 until width * height) {
            data[n * 2] = 1_000_000f
            data[n * 2 + 1] = 1f
            colors[n * 6 + 2] = 200f
            val base = (n * 2 + 1) * 3
            colors[base] = 80f; colors[base + 1] = 120f; colors[base + 2] = 160f
        }
        val grid = PackedGrid(
            width, height, labelCount = 2,
            nodeLabelCount = IntArray(width * height) { 2 },
            nodeLabels = IntArray(width * height * 2) { it % 2 },
            nodeData = data,
            nodeColors = colors,
            nodeMean = FloatArray(width * height * 3) { 100f },
        )
        val solved = SeamSolver.solve(grid, smoothLambda = 5.0, maxPasses = 4)
        assertTrue(solved.all { it == 1 })
    }

    // -- Helpers ------------------------------------------------------------

    /** A dense grid (every label allowed everywhere) with random costs/colours. */
    private fun randomGrid(width: Int, height: Int, labelCount: Int, random: Random): PackedGrid {
        val nodeCount = width * height
        val nodeLabelCount = IntArray(nodeCount) { labelCount }
        val nodeLabels = IntArray(nodeCount * labelCount)
        val nodeData = FloatArray(nodeCount * labelCount)
        val nodeColors = FloatArray(nodeCount * labelCount * 3)
        val nodeMean = FloatArray(nodeCount * 3)
        for (n in 0 until nodeCount) {
            for (label in 0 until labelCount) {
                nodeLabels[n * labelCount + label] = label
                nodeData[n * labelCount + label] = random.nextInt(10_000).toFloat()
                val base = (n * labelCount + label) * 3
                nodeColors[base] = random.nextInt(256).toFloat()
                nodeColors[base + 1] = random.nextInt(256).toFloat()
                nodeColors[base + 2] = random.nextInt(256).toFloat()
                nodeMean[n * 3] += nodeColors[base]
                nodeMean[n * 3 + 1] += nodeColors[base + 1]
                nodeMean[n * 3 + 2] += nodeColors[base + 2]
            }
            nodeMean[n * 3] /= labelCount
            nodeMean[n * 3 + 1] /= labelCount
            nodeMean[n * 3 + 2] /= labelCount
        }
        return PackedGrid(width, height, labelCount, nodeLabelCount, nodeLabels, nodeData, nodeColors, nodeMean)
    }

    private fun bruteForceMinCut(nodeCount: Int, edges: List<IntArray>, source: Int, sink: Int): Double {
        val movable = (0 until nodeCount).filter { it != source && it != sink }
        val side = BooleanArray(nodeCount)
        side[source] = true
        side[sink] = false
        var best = Double.MAX_VALUE
        for (mask in 0 until (1 shl movable.size)) {
            for (i in movable.indices) side[movable[i]] = (mask shr i and 1) == 1
            var cut = 0.0
            for (edge in edges) {
                if (side[edge[0]] && !side[edge[1]]) cut += edge[2]
            }
            if (cut < best) best = cut
        }
        return best
    }

    /** The energy of every possible labelling of a dense grid, minimized. */
    private fun exhaustiveMinimum(grid: PackedGrid, lambda: Double): Double {
        var best = Double.MAX_VALUE
        val labels = IntArray(grid.nodeCount)
        fun recurse(position: Int) {
            if (position == grid.nodeCount) {
                val energy = bruteForceEnergy(grid, labels, lambda)
                if (energy < best) best = energy
                return
            }
            for (label in 0 until grid.labelCount) {
                labels[position] = label
                recurse(position + 1)
            }
        }
        recurse(0)
        return best
    }

    private fun bruteForceEnergy(grid: PackedGrid, labels: IntArray, lambda: Double): Double {
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
        for (r in 0 until grid.height) {
            for (c in 0 until grid.width) {
                val n = r * grid.width + c
                val fp = labels[n]
                if (c + 1 < grid.width) {
                    val m = n + 1
                    total += lambda * (differenceAt(grid, n, fp, labels[m]) + differenceAt(grid, m, fp, labels[m]))
                }
                if (r + 1 < grid.height) {
                    val m = n + grid.width
                    total += lambda * (differenceAt(grid, n, fp, labels[m]) + differenceAt(grid, m, fp, labels[m]))
                }
            }
        }
        return total
    }

    /** |I_fp(node) − I_fq(node)| for a dense grid. */
    private fun differenceAt(grid: PackedGrid, node: Int, fp: Int, fq: Int): Double {
        val off = grid.offsets[node]
        var cp = DoubleArray(3)
        var cq = DoubleArray(3)
        for (j in 0 until grid.nodeLabelCount[node]) {
            val label = grid.nodeLabels[off + j]
            val base = (off + j) * 3
            if (label == fp) {
                cp = doubleArrayOf(
                    grid.nodeColors[base].toDouble(),
                    grid.nodeColors[base + 1].toDouble(),
                    grid.nodeColors[base + 2].toDouble(),
                )
            }
            if (label == fq) {
                cq = doubleArrayOf(
                    grid.nodeColors[base].toDouble(),
                    grid.nodeColors[base + 1].toDouble(),
                    grid.nodeColors[base + 2].toDouble(),
                )
            }
        }
        val d0 = cp[0] - cq[0]
        val d1 = cp[1] - cq[1]
        val d2 = cp[2] - cq[2]
        return kotlin.math.sqrt(d0 * d0 + d1 * d1 + d2 * d2)
    }
}
