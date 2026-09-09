package net.horizonsend.ion.server.features.transport.manager.graph.fluid

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import net.horizonsend.ion.common.utils.miscellaneous.roundToHundredth
import net.horizonsend.ion.server.features.client.display.ClientDisplayEntities.sendText
import net.horizonsend.ion.server.features.multiblock.entity.type.fluids.FluidPortMetadata
import net.horizonsend.ion.server.features.transport.fluids.FluidStack
import net.horizonsend.ion.server.features.transport.inputs.IOPort.RegisteredMetaDataInput
import net.horizonsend.ion.server.features.transport.inputs.IOType
import net.horizonsend.ion.server.features.transport.manager.graph.FlowNode
import net.horizonsend.ion.server.features.transport.manager.graph.FlowTrackingTransportGraph
import net.horizonsend.ion.server.features.transport.manager.graph.NetworkManager
import net.horizonsend.ion.server.features.transport.manager.graph.TransportNetwork
import net.horizonsend.ion.server.features.transport.manager.graph.TransportNetwork.NodeRemovalResult
import net.horizonsend.ion.server.features.transport.manager.graph.fluid.FluidNode.FluidPort
import net.horizonsend.ion.server.features.transport.nodes.graph.GraphEdge
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.BlockKey
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.distance
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.getRelative
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.getPointsBetween
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.toVec3i
import net.horizonsend.ion.server.miscellaneous.utils.debugAudience
import net.kyori.adventure.text.Component
import org.bukkit.persistence.PersistentDataAdapterContext
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.concurrent.withLock
import kotlin.math.roundToInt
import kotlin.random.Random

@Suppress("UnstableApiUsage")
class FluidNetwork(uuid: UUID, override val manager: NetworkManager<FluidNode, TransportNetwork<FluidNode>>) : FlowTrackingTransportGraph<FluidNode, RegisteredMetaDataInput<FluidPortMetadata>>(uuid, manager, IOType.FLUID) {
	override fun createEdge(nodeOne: FluidNode, nodeTwo: FluidNode): GraphEdge = FluidGraphEdge(nodeOne, nodeTwo)

	/**
	 * The contents of the network, contained as a fluid stack
	 **/
	var networkContents: FluidStack = FluidStack.empty()

	/**
	 * The last calculated volume of the network
	 **/
	private var cachedVolume: Double? = null

	fun resetCachedVolume() {
		cachedVolume = null
	}

	@Synchronized
	fun getVolume(): Double {
		if (cachedVolume != null) return cachedVolume!!

		val new = getGraphNodes().sumOf { it.volume }
		cachedVolume = new
		return new
	}

	override fun onModified() {
		resetCachedVolume()
	}

	override fun preSave() {
		getGraphNodes().forEach(FluidNode::populateContents)
	}

	fun detachNodes(nodes: Collection<FluidNode>) {
		preSave()
		val detachedAmount = nodes.sumOf { it.contents.amount }
		val result = removeNodes(nodes)

		if (result is NodeRemovalResult.RemovedSingle) {
			networkContents.amount = maxOf(0.0, networkContents.amount - detachedAmount)
		}
	}

	private var lastStructureTick: Long = System.currentTimeMillis()
	private var lastDisplayTick: Long = System.currentTimeMillis()
	private var lastTransferTick: Long = System.currentTimeMillis()

	fun skipTransferTime(elapsedMillis: Long) {
		lastTransferTick = minOf(System.currentTimeMillis(), lastTransferTick + elapsedMillis)
	}

	override fun handleTick() {
		val now = System.currentTimeMillis()

		if (now - lastStructureTick > STRUCTURE_INTERVAL) {
			lastStructureTick = now

			// Discover any structural changes and check integrity of the network
			discoverNetwork()

			// Determine the direction and capacity for flow through the network
			edmondsKarp()

			getGraphEdges().forEach {
				(it as FluidGraphEdge)
				if (it.netFlow == 0.0) return@forEach
				if (getFlow(it.nodeOne.location) > 0) return@forEach
				it.netFlow = maxOf(it.netFlow - 5, 0.0)
			}
		}

		val volume = getVolume()

		if (networkContents.amount > volume) {
			networkContents.amount = volume
		}

		// Prevent very small amounts that are annoying to deal with by just deleting them
		if (networkContents.amount.roundToHundredth() == 0.0) networkContents.amount = 0.0

		val (inputs, outputs) = trackIO()

		val delta = (now - lastTransferTick) / 1000.0
		lastTransferTick = now

		tickMultiblockOutputs(outputs, delta)

		if (now - lastDisplayTick > DISPLAY_INTERVAL) {
			lastDisplayTick = now

			displayFluid()
		}

		tickUnpairedPipes(delta)

		tickMultiblockInputs(inputs, delta)
	}

	/**
	 * Unpaired pipes will leak fluids out of the network
	 **/
	private fun tickUnpairedPipes(delta: Double) {
//		if (networkContents.isEmpty()) return

		val type = networkContents.type

		val leakingLocations = LongOpenHashSet()

		localLock.readLock().withLock {
			for (node in getGraphNodes()) {
				if (node !is FluidNode.LeakablePipe) continue

				val edges = getGraph().outEdges(node)

				if (edges.isEmpty()) continue

				if (edges.size >= 2) continue

				leakingLocations.add(node.location)

				if (networkContents.isEmpty()) continue

				val connectedEdge = edges.first()
				val localDirection = (connectedEdge as FluidGraphEdge).direction.oppositeFace
				val globalDirection = manager.transportManager.getGlobalDirection(localDirection)

				val removeAmount = (minOf(getFlow(node.location), node.leakRate, networkContents.amount) * delta)
				if (removeAmount <= 0) continue

				runCatching {
					type.getValue().playLeakEffects(manager.transportManager.getWorld(), node, globalDirection)
				}.onFailure { exception -> exception.printStackTrace() }

				if (networkContents.amount < 0) return@withLock
				networkContents.amount -= minOf(removeAmount, networkContents.amount)

				// Handle pollution
				val globalLocation = manager.transportManager.getGlobalCoordinate(toVec3i(node.location))
				type.getValue().onLeak(
					manager.transportManager.getWorld(),
					globalLocation.getRelative(globalDirection),
					removeAmount
				)
			}
		}

		leakingPipes = leakingLocations
	}

	private var leakingPipes = LongOpenHashSet()

	/**
	 * Returns a pair of a location map of inputs, and a location map of outputs
	 **/
	private fun trackIO(): Pair<Long2ObjectOpenHashMap<RegisteredMetaDataInput<FluidPortMetadata>>, Long2ObjectOpenHashMap<RegisteredMetaDataInput<FluidPortMetadata>>> {
		val inputs = Long2ObjectOpenHashMap<RegisteredMetaDataInput<FluidPortMetadata>>()
		val outputs = Long2ObjectOpenHashMap<RegisteredMetaDataInput<FluidPortMetadata>>()

		for (node in getGraphNodes()) {
			val ports: ObjectOpenHashSet<RegisteredMetaDataInput<FluidPortMetadata>> = manager.transportManager.getInputProvider().getPorts(IOType.FLUID, node.location)

			for (port in ports) {
				val metaData = port.metaData
				if (metaData.inputAllowed) inputs[node.location] = port
				if (metaData.outputAllowed) outputs[node.location] = port
			}
		}

		return inputs to outputs
	}

	private fun tickMultiblockInputs(inputs: Long2ObjectOpenHashMap<RegisteredMetaDataInput<FluidPortMetadata>>, delta: Double) {
		if (networkContents.isEmpty()) return

		val candidates = mutableListOf<FluidTransferCandidate>()
		inputs.forEach { entry ->
			val location = entry.key
			val port = entry.value
			if (!port.metaData.inputAllowed) return@forEach

			val node = getNodeAtLocation(location) as? FluidPort ?: return@forEach
			val store = port.metaData.connectedStore
			if (!store.canAdd(networkContents)) return@forEach

			val limit = minOf(
				store.getRemainingRoom(),
				node.additionCapacity * delta,
				getSinkFlow(location) * delta
			)

			if (limit > 0.0) candidates.add(FluidTransferCandidate(location, port, limit))
		}

		val allocations = fairTransferAmounts(candidates.map(FluidTransferCandidate::limit), networkContents.amount)
		candidates.forEachIndexed { index, candidate ->
			addToMultiblocks(candidate.location, candidate.port, allocations[index])
		}
	}

	private fun tickMultiblockOutputs(outputs: Long2ObjectOpenHashMap<RegisteredMetaDataInput<FluidPortMetadata>>, delta: Double) {
		val remainingRoom = maxOf(0.0, getVolume() - networkContents.amount)
		if (remainingRoom <= 0.0) return

		val candidates = mutableListOf<FluidTransferCandidate>()
		outputs.forEach { entry ->
			val location = entry.key
			val port = entry.value
			if (!port.metaData.outputAllowed) return@forEach

			val node = getNodeAtLocation(location) as? FluidPort ?: return@forEach
			val contents = port.metaData.connectedStore.getContents()
			if (contents.isEmpty()) return@forEach
			if (!networkContents.isEmpty() && !networkContents.canCombine(contents)) return@forEach

			val limit = minOf(
				contents.amount,
				node.removalCapacity * delta,
				getSourceFlow(location) * delta
			)

			if (limit > 0.0) candidates.add(FluidTransferCandidate(location, port, limit))
		}

		val compatibleCandidates = if (networkContents.isNotEmpty()) candidates else {
			val selectedType = candidates
				.groupBy { it.port.metaData.connectedStore.getContents().type }
				.maxByOrNull { (_, transfers) -> transfers.sumOf(FluidTransferCandidate::limit) }
				?.key

			candidates.filter { it.port.metaData.connectedStore.getContents().type == selectedType }
		}

		val allocations = fairTransferAmounts(compatibleCandidates.map(FluidTransferCandidate::limit), remainingRoom)
		compatibleCandidates.forEachIndexed { index, candidate ->
			depositToNetwork(candidate.location, candidate.port, allocations[index])
		}
	}

	private data class FluidTransferCandidate(
		val location: BlockKey,
		val port: RegisteredMetaDataInput<FluidPortMetadata>,
		val limit: Double
	)

	private fun fairTransferAmounts(limits: List<Double>, available: Double): DoubleArray {
		val allocations = DoubleArray(limits.size)
		var amountLeft = minOf(available, limits.sum())
		val remaining = limits.indices.sortedBy(limits::get).toMutableList()

		while (remaining.isNotEmpty() && amountLeft > 0.0) {
			val equalShare = amountLeft / remaining.size
			val limitedIndex = remaining.first()

			if (limits[limitedIndex] <= equalShare) {
				allocations[limitedIndex] = limits[limitedIndex]
				amountLeft -= limits[limitedIndex]
				remaining.removeAt(0)
				continue
			}

			remaining.forEach { allocations[it] = equalShare }
			break
		}

		return allocations
	}

	private fun depositToNetwork(location: BlockKey, port: RegisteredMetaDataInput<FluidPortMetadata>, amount: Double) {
		if (amount <= 0.0) return
		if (!port.metaData.outputAllowed) return
		val node = getNodeAtLocation(location) as? FluidPort ?: return

		val remainingRoom = maxOf(0.0, getVolume() - networkContents.amount)
		if (remainingRoom <= 0.0) return

		val storage = port.metaData.connectedStore
		val storageContents = storage.getContents()

		if (storageContents.isEmpty()) return

		if (!networkContents.isEmpty() && storageContents.type != networkContents.type) return

		val toRemove = minOf(amount, remainingRoom, storage.getContents().amount)

		if (toRemove <= 0) return

		if (!storageContents.isEmpty()) networkContents.type = storageContents.type

		// Make a copy as the amount to be added, then combine with properties into the network
		val combined = storageContents.asAmount(toRemove)

		val notRemoved = storage.removeAmount(toRemove)
		combined.amount -= notRemoved

		val combinationLocation = node.getGlobalCenter().toLocation(manager.transportManager.getWorld())
		networkContents.combine(combined, combinationLocation)
	}

	private fun addToMultiblocks(location: BlockKey, ioPort: RegisteredMetaDataInput<FluidPortMetadata>, amount: Double) {
		if (amount <= 0.0) return
		if (networkContents.isEmpty()) return
		if (!ioPort.metaData.inputAllowed) return

		val node = getNodeAtLocation(location) as? FluidPort ?: return

		val store = ioPort.metaData.connectedStore

		if (!store.canAdd(networkContents)) return

		if (!store.getContents().isEmpty() && store.getContents().type != networkContents.type) return

		val room = store.capacity - store.getContents().amount
		val availableToMove = networkContents.amount
		val toAdd = minOf(room, availableToMove, amount)

		if (toAdd <= 0) return

		val toCombine = networkContents.asAmount(toAdd)
		val notAdded = store.addFluid(toCombine, node.getGlobalCenter().toLocation(manager.transportManager.getWorld()))

		networkContents.amount -= toAdd - notAdded
	}

	fun displayFluid() {
		val contents = networkContents

		if (contents.isEmpty()) {
			return
		}

		val type = contents.type

		val world = manager.transportManager.getWorld()

		for (node in getGraphNodes()) {
			debugAudience.sendText(
				node.getGlobalCenter().toLocation(world).add(0.0, 0.5, 0.0),
				Component.text(getFlow(node.location)),
				20L
			)
		}

		// Each physical connection has two directed edges. Keep the one carrying net forward flow,
		// or just one stationary edge when neither direction has flow. Include every junction branch.
		val connections = mutableMapOf<Pair<BlockKey, BlockKey>, FluidGraphEdge>()
		localLock.readLock().withLock {
			for (edge in getGraphEdges().filterIsInstance<FluidGraphEdge>()) {
				val one = edge.nodeOne.location
				val two = edge.nodeTwo.location
				val key = minOf(one, two) to maxOf(one, two)
				val previous = connections[key]
				if (previous == null || edge.netFlow > previous.netFlow) connections[key] = edge
			}
		}

		for (edge in connections.values) {
			// applyFlowResult stores non-negative flow from nodeOne to nodeTwo. The legacy
			// edge.direction points the other way, so derive movement from the world coordinates.
			val sourceCenter = (edge.nodeOne as FluidNode).getGlobalCenter()
			val targetCenter = (edge.nodeTwo as FluidNode).getGlobalCenter()
			val moving = edge.netFlow > 0.000_001
			val direction = if (moving) targetCenter.clone().subtract(sourceCenter).normalize() else Vector()
			val pointCount = maxOf(1, distance(sourceCenter, targetCenter).roundToInt()) * 3

			getPointsBetween(sourceCenter, targetCenter, pointCount).forEach { origin ->
				origin.add(Vector(
					Random.nextDouble(-PIPE_INTERIOR_PADDING, PIPE_INTERIOR_PADDING),
					Random.nextDouble(-PIPE_INTERIOR_PADDING, PIPE_INTERIOR_PADDING),
					Random.nextDouble(-PIPE_INTERIOR_PADDING, PIPE_INTERIOR_PADDING)
				))

				// Clamp to the whole connection, not the source block: particles past the
				// midpoint must never be pulled backwards toward the source.
				val destination = origin.clone().add(direction).apply {
					x = x.coerceIn(minOf(sourceCenter.x, targetCenter.x) - PIPE_INTERIOR_PADDING, maxOf(sourceCenter.x, targetCenter.x) + PIPE_INTERIOR_PADDING)
					y = y.coerceIn(minOf(sourceCenter.y, targetCenter.y) - PIPE_INTERIOR_PADDING, maxOf(sourceCenter.y, targetCenter.y) + PIPE_INTERIOR_PADDING)
					z = z.coerceIn(minOf(sourceCenter.z, targetCenter.z) - PIPE_INTERIOR_PADDING, maxOf(sourceCenter.z, targetCenter.z) + PIPE_INTERIOR_PADDING)
				}

				type.getValue().displayInPipe(world, origin, destination)
			}
		}
	}

	override fun save(adapterContext: PersistentDataAdapterContext): PersistentDataContainer {
		val pdc = adapterContext.newPersistentDataContainer()

		return pdc
	}

	override fun canMergeWith(other: TransportNetwork<FluidNode>): Boolean {
		other as? FluidNetwork ?: return false
		return networkContents.isEmpty() || other.networkContents.isEmpty() || networkContents.canCombine(other.networkContents)
	}

	override fun onMergedInto(other: TransportNetwork<FluidNode>) {
		if (networkContents.isEmpty()) return
		other as FluidNetwork

		val otherContents = other.networkContents
		if (!otherContents.isEmpty() && otherContents.type != networkContents.type) return

		// Grab a node to use as a location for default params
		val node = getGraphNodes().firstOrNull() ?: other.getGraphNodes().firstOrNull()
		val location = node?.getGlobalCenter()?.toLocation(manager.transportManager.getWorld())

		// Merge amounts if same type
		otherContents.combine(networkContents, location)
	}

	override fun onSplit(children: Collection<TransportNetwork<FluidNode>>) {
		val contents = networkContents.clone()
		val volume = getVolume()
		if (volume <= 0.0 || contents.isEmpty()) return

		for (child in children) {
			val share = (child as FluidNetwork).getVolume() / volume
			val amount = minOf(contents.amount * share, networkContents.amount)
			child.networkContents.combine(contents.asAmount(amount), null)
			networkContents.amount -= amount
		}
	}

	override fun isSink(node: FlowNode, ioData: RegisteredMetaDataInput<FluidPortMetadata>?): Boolean {
		if (leakingPipes.contains(node.location)) {
			return true
		}

		if (ioData == null) return false

		val container = ioData.metaData.connectedStore

		// If the port can have input, has a fluid that can be combined with the network, and has room for more fluid, add to sinks.
		return ioData.metaData.inputAllowed
			&& (container.getContents().canCombine(networkContents) || container.getContents().isEmpty() || networkContents.isEmpty())
			&& container.getRemainingRoom() > 0.0
	}

	override fun isSource(node: FlowNode, ioData: RegisteredMetaDataInput<FluidPortMetadata>): Boolean {
		val container = ioData.metaData.connectedStore

		// If the port can output, has a fluid that can be combined, and is not empty, add to sources.
		return ioData.metaData.outputAllowed
			&& (networkContents.canCombine(container.getContents()) || networkContents.isEmpty())
			&& container.getContents().amount > 0.0
	}

	override fun getSourceCapacity(node: FluidNode): Double {
		return (node as? FluidPort)?.removalCapacity ?: super.getSourceCapacity(node)
	}

	override fun getSinkCapacity(node: FluidNode): Double {
		if (node is FluidNode.LeakablePipe && leakingPipes.contains(node.location)) return node.leakRate
		return (node as? FluidPort)?.additionCapacity ?: super.getSinkCapacity(node)
	}

	override fun getFlowCapacity(node: FluidNode): Double {
		if (!leakingPipes.contains(node.location)) return super.getFlowCapacity(node)
		return 5.0
	}

	companion object {
		const val PIPE_INTERIOR_PADDING = 0.215

		private const val STRUCTURE_INTERVAL = 1000L
		private const val DISPLAY_INTERVAL = 250L
	}

	override fun toString(): String {
		return "FluidNetwork{id=$uuid,size=${getGraphNodes().size},contents=$networkContents}"
	}
}
