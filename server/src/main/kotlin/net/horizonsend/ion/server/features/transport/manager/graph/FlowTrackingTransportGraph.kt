package net.horizonsend.ion.server.features.transport.manager.graph

import com.google.common.graph.MutableValueGraph
import com.google.common.graph.ValueGraphBuilder
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import net.horizonsend.ion.server.features.transport.inputs.IOPort
import net.horizonsend.ion.server.features.transport.inputs.IOType
import net.horizonsend.ion.server.features.transport.manager.graph.fluid.FluidGraphEdge
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.BlockKey
import java.util.UUID
import kotlin.jvm.optionals.getOrDefault
import kotlin.jvm.optionals.getOrNull

@Suppress("UnstableApiUsage")
abstract class FlowTrackingTransportGraph<T : FlowNode, P : IOPort>(
	uuid: UUID,
	override val manager: NetworkManager<T, TransportNetwork<T>>,
	val ioType: IOType<P>
) : TransportNetwork<T>(uuid, manager) {
	/**
	 * A map of each node location to the total flow routed through that node.
	 **/
	protected var flowMap = Long2DoubleOpenHashMap(); private set

	private var sourceFlowMap = Long2DoubleOpenHashMap()
	private var sinkFlowMap = Long2DoubleOpenHashMap()

	// Array of unique paths that contribute flow
	protected var paths: Array<List<BlockKey>> = arrayOf(); private set
	// Multimap of nodes to indexes of paths that use that node
	protected var nodePathLookup = Long2ObjectOpenHashMap<IntArray>(); private set

	protected var lastSinks: ObjectOpenHashSet<T> = ObjectOpenHashSet(); private set
	protected var lastSources: ObjectOpenHashSet<T> = ObjectOpenHashSet(); private set

	fun getFlow(position: Long) = flowMap.getOrDefault(position, 0.0)
	fun getSourceFlow(position: Long) = sourceFlowMap.getOrDefault(position, 0.0)
	fun getSinkFlow(position: Long) = sinkFlowMap.getOrDefault(position, 0.0)

	companion object {
		private const val SUPER_SOURCE = Long.MAX_VALUE
		private const val SUPER_SINK = Long.MIN_VALUE
		private const val FLOW_EPSILON = 0.000_001
	}

	abstract fun isSink(node: FlowNode, ioData: P?): Boolean

	abstract fun isSource(node: FlowNode, ioData: P): Boolean

	open fun getFlowCapacity(node: T): Double = node.flowCapacity
	open fun getSourceCapacity(node: T): Double = getFlowCapacity(node)
	open fun getSinkCapacity(node: T): Double = getFlowCapacity(node)

	/**
	 * Calculates the maximum network throughput, then caps sources and sinks to max-min fair shares.
	 * Endpoints which cannot use an equal share are capped lower and their unused share is redistributed.
	 **/
	protected fun edmondsKarp() {
		val sources = ObjectOpenHashSet<T>()
		val sinks = ObjectOpenHashSet<T>()

		getGraphNodes().forEach { node ->
			if (manager.transportManager.getInputProvider().getPorts(ioType, node.location).plus(null).any { input -> isSink(node, input) }) sinks.add(node)
			if (manager.transportManager.getInputProvider().getPorts(ioType, node.location).any { input -> isSource(node, input) }) sources.add(node)
		}

		lastSinks = sinks
		lastSources = sources

		if (sinks.isEmpty()) {
			clearFlow()
			return
		}

		val sinkMaximums = sinks.associate { it.location to getSinkCapacity(it) }

		if (sources.isEmpty()) {
			val bufferedSinkFlows = Long2DoubleOpenHashMap()
			sinkMaximums.forEach { (location, capacity) -> bufferedSinkFlows[location] = capacity }

			flowMap = Long2DoubleOpenHashMap(bufferedSinkFlows)
			sourceFlowMap = Long2DoubleOpenHashMap()
			sinkFlowMap = bufferedSinkFlows
			paths = arrayOf()
			nodePathLookup = Long2ObjectOpenHashMap()
			getGraphEdges().filterIsInstance<FluidGraphEdge>().forEach { it.netFlow = 0.0 }
			return
		}

		val sourceMaximums = sources.associate { it.location to getSourceCapacity(it) }
		val unrestricted = calculateFlow(sourceMaximums, sinkMaximums)
		val maximumFlow = unrestricted.totalFlow

		if (maximumFlow <= FLOW_EPSILON) {
			applyFlowResult(unrestricted)
			return
		}

		var sourceCaps = if (sourceMaximums.size == 1) sourceMaximums else fairShares(maximumFlow, sourceMaximums)
		var sourceBalanced = if (sourceMaximums.size == 1) unrestricted else calculateFlow(sourceCaps, sinkMaximums)
		var sourceBalanceAttempts = 0

		while (sourceBalanced.totalFlow < maximumFlow - FLOW_EPSILON && sourceBalanceAttempts < sourceCaps.size) {
			val rebalanced = redistributeUnusedShare(maximumFlow, sourceMaximums, sourceCaps, sourceBalanced.sourceFlows)
			if (sameAllocations(sourceCaps, rebalanced)) break

			sourceCaps = rebalanced
			sourceBalanced = calculateFlow(sourceCaps, sinkMaximums)
			sourceBalanceAttempts++
		}

		if (sourceBalanced.totalFlow < maximumFlow - FLOW_EPSILON) {
			sourceCaps = sourceMaximums
			sourceBalanced = unrestricted
		}

		var sinkCaps = if (sinkMaximums.size == 1) sinkMaximums else fairShares(sourceBalanced.totalFlow, sinkMaximums)
		var balanced = if (sinkMaximums.size == 1) sourceBalanced else calculateFlow(sourceCaps, sinkCaps)
		var sinkBalanceAttempts = 0

		while (balanced.totalFlow < sourceBalanced.totalFlow - FLOW_EPSILON && sinkBalanceAttempts < sinkCaps.size) {
			val rebalanced = redistributeUnusedShare(sourceBalanced.totalFlow, sinkMaximums, sinkCaps, balanced.sinkFlows)
			if (sameAllocations(sinkCaps, rebalanced)) break

			sinkCaps = rebalanced
			balanced = calculateFlow(sourceCaps, sinkCaps)
			sinkBalanceAttempts++
		}

		applyFlowResult(
			if (balanced.totalFlow >= sourceBalanced.totalFlow - FLOW_EPSILON) balanced
			else sourceBalanced
		)
	}

	private fun clearFlow() {
		flowMap = Long2DoubleOpenHashMap()
		sourceFlowMap = Long2DoubleOpenHashMap()
		sinkFlowMap = Long2DoubleOpenHashMap()
		paths = arrayOf()
		nodePathLookup = Long2ObjectOpenHashMap()
		getGraphEdges().filterIsInstance<FluidGraphEdge>().forEach { it.netFlow = 0.0 }
	}

	private fun fairShares(total: Double, maximums: Map<BlockKey, Double>): Map<BlockKey, Double> {
		if (maximums.isEmpty() || total <= FLOW_EPSILON) return maximums.mapValues { 0.0 }

		val result = mutableMapOf<BlockKey, Double>()
		val remaining = maximums.entries.sortedBy { it.value }.toMutableList()
		var amountLeft = minOf(total, maximums.values.sum())

		while (remaining.isNotEmpty()) {
			val equalShare = amountLeft / remaining.size
			val limited = remaining.first()

			if (limited.value <= equalShare + FLOW_EPSILON) {
				result[limited.key] = limited.value
				amountLeft -= limited.value
				remaining.removeAt(0)
				continue
			}

			remaining.forEach { result[it.key] = equalShare }
			amountLeft = 0.0
			break
		}

		if (amountLeft > FLOW_EPSILON) {
			for ((location, maximum) in maximums) {
				val room = maximum - result.getOrDefault(location, 0.0)
				if (room <= FLOW_EPSILON) continue

				val addition = minOf(room, amountLeft)
				result[location] = result.getOrDefault(location, 0.0) + addition
				amountLeft -= addition
				if (amountLeft <= FLOW_EPSILON) break
			}
		}

		return result
	}

	private fun redistributeUnusedShare(
		total: Double,
		maximums: Map<BlockKey, Double>,
		targets: Map<BlockKey, Double>,
		achieved: Long2DoubleOpenHashMap
	): Map<BlockKey, Double> {
		val constrained = targets.keys.filterTo(mutableSetOf()) {
			achieved.getOrDefault(it, 0.0) < targets.getValue(it) - FLOW_EPSILON
		}

		if (constrained.isEmpty() || constrained.size == targets.size) return targets

		val constrainedBudget = constrained.sumOf { achieved.getOrDefault(it, 0.0) }
		val constrainedMaximums = maximums.filterKeys { it in constrained }
		val constrainedShares = fairShares(constrainedBudget, constrainedMaximums)

		val flexibleMaximums = maximums.filterKeys { it !in constrained }
		val flexibleShares = fairShares(total - constrainedShares.values.sum(), flexibleMaximums)

		return constrainedShares + flexibleShares
	}

	private fun sameAllocations(first: Map<BlockKey, Double>, second: Map<BlockKey, Double>): Boolean {
		return first.keys == second.keys && first.all { (location, amount) ->
			kotlin.math.abs(amount - second.getOrDefault(location, 0.0)) <= FLOW_EPSILON
		}
	}

	private data class FlowResult(
		val totalFlow: Double,
		val nodeFlows: Long2DoubleOpenHashMap,
		val sourceFlows: Long2DoubleOpenHashMap,
		val sinkFlows: Long2DoubleOpenHashMap,
		val paths: List<List<BlockKey>>,
		val pathLookup: Long2ObjectOpenHashMap<IntArray>,
		val edgeFlows: Map<Pair<BlockKey, BlockKey>, Double>
	)

	private fun calculateFlow(
		sourceCapacities: Map<BlockKey, Double>,
		sinkCapacities: Map<BlockKey, Double>
	): FlowResult {
		val valueGraph = getValueGraphRepresentation()

		sourceCapacities.forEach { (location, capacity) ->
			if (capacity > FLOW_EPSILON) valueGraph.putEdgeValue(SUPER_SOURCE, location, capacity)
		}

		sinkCapacities.forEach { (location, capacity) ->
			if (capacity > FLOW_EPSILON) valueGraph.putEdgeValue(location, SUPER_SINK, capacity)
		}

		val sourceFlows = Long2DoubleOpenHashMap()
		val sinkFlows = Long2DoubleOpenHashMap()
		val edgeFlows = mutableMapOf<Pair<BlockKey, BlockKey>, Double>()
		val calculatedPaths = mutableListOf<List<BlockKey>>()
		val mutablePathLookup = mutableMapOf<BlockKey, MutableList<Int>>()
		var totalFlow = 0.0

		while (true) {
			val parents = Long2LongOpenHashMap()
			if (!bfs(valueGraph, parents)) break

			val reversePath = mutableListOf<BlockKey>()
			var current = SUPER_SINK
			reversePath.add(current)

			while (current != SUPER_SOURCE) {
				current = parents.getOrDefault(current, null) ?: break
				reversePath.add(current)
			}

			if (reversePath.lastOrNull() != SUPER_SOURCE) break

			val path = reversePath.asReversed()
			var pathFlow = Double.MAX_VALUE

			for (index in 0 until path.lastIndex) {
				pathFlow = minOf(pathFlow, valueGraph.edgeValue(path[index], path[index + 1]).get())
			}

			if (!pathFlow.isFinite() || pathFlow <= FLOW_EPSILON) break

			for (index in 0 until path.lastIndex) {
				val from = path[index]
				val to = path[index + 1]
				val remaining = valueGraph.edgeValue(from, to).get() - pathFlow

				valueGraph.putEdgeValue(from, to, if (remaining <= FLOW_EPSILON) 0.0 else remaining)
				valueGraph.putEdgeValue(to, from, valueGraph.edgeValue(to, from).getOrDefault(0.0) + pathFlow)
			}

			totalFlow += pathFlow

			val source = path[1]
			val sink = path[path.lastIndex - 1]
			sourceFlows[source] = sourceFlows.getOrDefault(source, 0.0) + pathFlow
			sinkFlows[sink] = sinkFlows.getOrDefault(sink, 0.0) + pathFlow

			val networkPath = path.subList(1, path.lastIndex)
		val pathIndex = calculatedPaths.size

			networkPath.forEach { location ->
				mutablePathLookup.getOrPut(location) { mutableListOf() }.add(pathIndex)
			}

			for (index in 0 until networkPath.lastIndex) {
				val edge = networkPath[index] to networkPath[index + 1]
				edgeFlows[edge] = edgeFlows.getOrDefault(edge, 0.0) + pathFlow
			}

			calculatedPaths.add(networkPath)
		}

		val pathLookup = mutablePathLookup.entries.associateTo(Long2ObjectOpenHashMap()) {
			it.key to it.value.toIntArray()
		}
		val incomingFlows = Long2DoubleOpenHashMap()
		val outgoingFlows = Long2DoubleOpenHashMap()

		edgeFlows.forEach { (edge, forwardFlow) ->
			val reverseFlow = edgeFlows.getOrDefault(edge.second to edge.first, 0.0)
			val netFlow = forwardFlow - reverseFlow
			if (netFlow <= FLOW_EPSILON) return@forEach

			outgoingFlows[edge.first] = outgoingFlows.getOrDefault(edge.first, 0.0) + netFlow
			incomingFlows[edge.second] = incomingFlows.getOrDefault(edge.second, 0.0) + netFlow
		}

		val nodeFlows = Long2DoubleOpenHashMap()
		getGraphNodes().forEach { node ->
			nodeFlows[node.location] = maxOf(
				incomingFlows.getOrDefault(node.location, 0.0),
				outgoingFlows.getOrDefault(node.location, 0.0),
				sourceFlows.getOrDefault(node.location, 0.0),
				sinkFlows.getOrDefault(node.location, 0.0)
			)
		}

		return FlowResult(totalFlow, nodeFlows, sourceFlows, sinkFlows, calculatedPaths, pathLookup, edgeFlows)
	}

	private fun applyFlowResult(result: FlowResult) {
		flowMap = result.nodeFlows
		sourceFlowMap = result.sourceFlows
		sinkFlowMap = result.sinkFlows
		paths = result.paths.toTypedArray()
		nodePathLookup = result.pathLookup

		getGraphEdges().filterIsInstance<FluidGraphEdge>().forEach { edge ->
			val forward = result.edgeFlows.getOrDefault(edge.nodeOne.location to edge.nodeTwo.location, 0.0)
			val reverse = result.edgeFlows.getOrDefault(edge.nodeTwo.location to edge.nodeOne.location, 0.0)
			edge.netFlow = maxOf(forward - reverse, 0.0)
		}
	}

	fun getValueGraphRepresentation(): MutableValueGraph<BlockKey, Double> {
		val copied = ValueGraphBuilder
			.directed()
			.allowsSelfLoops(false)
			.expectedNodeCount(getGraphNodes().size)
			.build<BlockKey, Double>()

		for (node in getGraphNodes()) {
			copied.addNode(node.location)
		}

		for (edge in getGraphEdges()) {
			// Capacity is per directed connection so junctions can fan out without imposing a total-node limit.
			@Suppress("UNCHECKED_CAST") val capacity = getFlowCapacity(edge.nodeOne as T)
			copied.putEdgeValue(edge.nodeOne.location, edge.nodeTwo.location, capacity)
		}

		return copied
	}

	private fun bfs(valueGraphRepresentation: MutableValueGraph<BlockKey, Double>, parents: Long2LongOpenHashMap): Boolean {
		val visited = LongOpenHashSet()
		val queue = ArrayDeque<BlockKey>()

		queue.add(SUPER_SOURCE)
		visited.add(SUPER_SOURCE)

		while (queue.isNotEmpty()) {
			val parent = queue.removeFirstOrNull() ?: break

			for (successor in valueGraphRepresentation.successors(parent)) {
				if (visited.contains(successor)) continue

				val capacity = valueGraphRepresentation.edgeValue(parent, successor).getOrNull() ?: continue
				if (capacity <= FLOW_EPSILON) continue

				visited.add(successor)
				queue.addLast(successor)
				parents[successor] = parent
			}
		}

		return visited.contains(SUPER_SINK)
	}
}
