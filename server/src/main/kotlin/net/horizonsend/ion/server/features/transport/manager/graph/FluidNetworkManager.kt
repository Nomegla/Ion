package net.horizonsend.ion.server.features.transport.manager.graph

import net.horizonsend.ion.server.core.registration.keys.CustomBlockKeys
import net.horizonsend.ion.server.core.registration.registries.CustomBlockRegistry.Companion.customBlock
import net.horizonsend.ion.server.features.custom.blocks.pipe.FluidPipeBlock
import net.horizonsend.ion.server.features.custom.blocks.pipe.ReinforcedFluidPipeBlock
import net.horizonsend.ion.server.features.transport.manager.TransportHolder
import net.horizonsend.ion.server.features.transport.manager.graph.fluid.FluidNetwork
import net.horizonsend.ion.server.features.transport.manager.graph.fluid.FluidNode
import net.horizonsend.ion.server.features.transport.manager.graph.fluid.FluidNode.FluidPort
import net.horizonsend.ion.server.features.transport.manager.graph.fluid.FluidNode.FluidValve
import net.horizonsend.ion.server.features.transport.nodes.util.BlockBasedCacheFactory
import net.horizonsend.ion.server.miscellaneous.registrations.persistence.NamespacedKeys
import net.horizonsend.ion.server.miscellaneous.utils.axis
import net.horizonsend.ion.server.miscellaneous.utils.faces
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.BlockKey
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.toBlockKey
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.toVec3i
import org.bukkit.Axis
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.MultipleFacing
import java.util.UUID

class FluidNetworkManager(manager: TransportHolder) : NetworkManager<FluidNode, TransportNetwork<FluidNode>>(manager) {
	override val namespacedKey: NamespacedKey = key

	override val cacheFactory = cache
	override fun networkProvider(): FluidNetwork {
		return FluidNetwork(UUID.randomUUID(), this)
	}

	fun registerPositionIfAbsent(position: BlockKey) = withExclusiveAccess {
		if (getByLocation(position) == null) registerNewPosition(position)
	}

	fun resumeAfterMovement(elapsedMillis: Long) {
		withExclusiveAccess {
			for (network in getAllGraphs()) {
				(network as FluidNetwork).skipTransferTime(elapsedMillis)
			}
			resume()
		}
	}

	fun invalidatePosition(globalPosition: BlockKey, newData: BlockData) {
		withExclusiveAccess {
			val localPosition = toBlockKey(transportManager.getLocalCoordinate(toVec3i(globalPosition)))
			val network = getByLocation(localPosition)
			val node = network?.getNodeAtLocation(localPosition)
			if (node != null) network.removeNode(node)

			if (transportManager.isLocalCoordinate(toVec3i(localPosition))) {
				createNode(localPosition, newData)?.let(::registerNewNode)
			}
		}
	}

	fun transferPositionsTo(destination: FluidNetworkManager, sourcePositions: Collection<BlockKey>) {
		if (destination === this || sourcePositions.isEmpty()) return

		synchronized(transferLock) {
			withExclusiveAccess {
				destination.withExclusiveAccess {
					val preparedNodes = sourcePositions.mapNotNull { sourcePosition ->
						val sourceNetwork = getByLocation(sourcePosition) as? FluidNetwork ?: return@mapNotNull null
						val sourceNode = sourceNetwork.getNodeAtLocation(sourcePosition) ?: return@mapNotNull null
						val globalPosition = transportManager.getGlobalCoordinate(toVec3i(sourcePosition))
						val destinationPosition = toBlockKey(destination.transportManager.getLocalCoordinate(globalPosition))
						val destinationNode = destination.createNodeUnchecked(destinationPosition) ?: return@mapNotNull null

						PreparedNode(sourceNetwork, sourceNode, destinationNode)
					}

					// Detach together so every node gets its share of the original network
					preparedNodes.groupBy(PreparedNode::sourceNetwork).forEach { (network, prepared) ->
						network.detachNodes(prepared.map(PreparedNode::sourceNode))
					}

					for ((_, sourceNode, destinationNode) in preparedNodes) {
						destinationNode.loadContents(sourceNode.contents.clone())
						destination.acceptTransferredNode(destinationNode)
					}
				}
			}
		}
	}

	private fun acceptTransferredNode(node: FluidNode) {
		val existing = getByLocation(node.location) as? FluidNetwork
		if (existing == null) {
			registerNewNode(node)
			return
		}

		val position = transportManager.getGlobalCoordinate(toVec3i(node.location))
		existing.networkContents.combine(node.contents, position.toCenterVector().toLocation(transportManager.getWorld()))
	}

	private data class PreparedNode(
		val sourceNetwork: FluidNetwork,
		val sourceNode: FluidNode,
		val destinationNode: FluidNode
	)

	private companion object {
		private val key = NamespacedKeys.key("fluid_transport")
		private val transferLock = Any()

		@JvmStatic
		val cache: BlockBasedCacheFactory<FluidNode, NetworkManager<FluidNode, TransportNetwork<FluidNode>>> = BlockBasedCacheFactory.builder<FluidNode, NetworkManager<FluidNode, TransportNetwork<FluidNode>>>()
			.addDataHandler<MultipleFacing>(CustomBlockKeys.FLUID_PIPE_JUNCTION, Material.CHORUS_PLANT) { _, pos, holder ->
				FluidNode.RegularJunctionPipe(pos)
			}
			.addDataHandler<MultipleFacing>(CustomBlockKeys.FLUID_PIPE, Material.CHORUS_PLANT) { data, pos, holder ->
				val axis = holder.localAxis((data.customBlock as FluidPipeBlock).getAxis(data))
				FluidNode.RegularLinearPipe(pos, axis)
			}
			.addDataHandler<MultipleFacing>(CustomBlockKeys.REINFORCED_FLUID_PIPE_JUNCTION, Material.CHORUS_PLANT) { _, pos, holder ->
				FluidNode.ReinforcedJunctionPipe(pos)
			}
			.addDataHandler<MultipleFacing>(CustomBlockKeys.REINFORCED_FLUID_PIPE, Material.CHORUS_PLANT) { data, pos, holder ->
				val axis = holder.localAxis((data.customBlock as ReinforcedFluidPipeBlock).getAxis(data))
				FluidNode.ReinforcedLinearPipe(pos, axis)
			}
			.addDataHandler<MultipleFacing>(CustomBlockKeys.FLUID_PORT, Material.BROWN_MUSHROOM_BLOCK) { _, pos, holder -> FluidPort(pos) }
			.addDataHandler<MultipleFacing>(CustomBlockKeys.FLUID_VALVE, Material.BROWN_MUSHROOM_BLOCK) { _, pos, holder -> FluidValve(pos) }
			.build()
	}
}

private fun NetworkManager<FluidNode, TransportNetwork<FluidNode>>.localAxis(globalAxis: Axis): Axis {
	return transportManager.getLocalDirection(globalAxis.faces.first).axis
}
