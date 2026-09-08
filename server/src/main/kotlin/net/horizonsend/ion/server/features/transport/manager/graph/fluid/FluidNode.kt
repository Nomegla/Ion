package net.horizonsend.ion.server.features.transport.manager.graph.fluid

import net.horizonsend.ion.server.core.registration.keys.CustomBlockKeys
import net.horizonsend.ion.server.core.registration.keys.TransportNetworkNodeTypeKeys
import net.horizonsend.ion.server.core.registration.registries.CustomBlockRegistry.Companion.customBlock
import net.horizonsend.ion.server.features.custom.blocks.pipe.FluidPipeBlock
import net.horizonsend.ion.server.features.custom.blocks.pipe.ReinforcedFluidPipeBlock
import net.horizonsend.ion.server.features.transport.fluids.FluidStack
import net.horizonsend.ion.server.features.transport.manager.graph.FlowNode
import net.horizonsend.ion.server.features.transport.manager.graph.TransportNetwork
import net.horizonsend.ion.server.features.transport.manager.graph.TransportNodeType
import net.horizonsend.ion.server.miscellaneous.utils.ADJACENT_BLOCK_FACES
import net.horizonsend.ion.server.miscellaneous.utils.axis
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.BlockKey
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.toVec3i
import net.horizonsend.ion.server.miscellaneous.utils.faces
import net.horizonsend.ion.server.miscellaneous.utils.getBlockIfLoaded
import org.bukkit.Axis
import org.bukkit.block.BlockFace
import org.bukkit.block.data.MultipleFacing
import org.bukkit.util.Vector

abstract class FluidNode(location: BlockKey, type: TransportNodeType<*>, val volume: Double) : FlowNode(location, type) {
	private lateinit var graph: FluidNetwork

	override fun getNetwork(): TransportNetwork<*> = graph
	override fun setNetworkOwner(graph: TransportNetwork<*>) {
		this.graph = graph as FluidNetwork
	}

	fun populateContents() {
		val networkVolume = graph.getVolume()
		if (networkVolume <= 0.0) {
			contents = FluidStack.empty()
			return
		}

		val contribution = volume / networkVolume
		contents = graph.networkContents.asAmount(graph.networkContents.amount * contribution)
	}

	var contents = FluidStack.empty(); private set

	fun loadContents(newContents: FluidStack) {
		this.contents = newContents
	}

	fun getGlobalCenter(): Vector {
		return getNetwork().manager.transportManager.getGlobalCoordinate(toVec3i(location)).toCenterVector()
	}

	override fun onLoadedIntoNetwork(network: TransportNetwork<*>) {
		network as FluidNetwork

		network.networkContents.combine(contents, getGlobalCenter().toLocation(network.manager.transportManager.getWorld()))
		contents = FluidStack.empty()
	}

	sealed interface LeakablePipe {
		val leakRate: Double
	}

	class RegularJunctionPipe(location: BlockKey) : FluidNode(location, TransportNetworkNodeTypeKeys.FLUID_JUNCTION_REGULAR.getValue(), 10.0) {
		override val flowCapacity: Double = 10.0

		override fun isIntact(): Boolean? {
			val world = getNetwork().manager.transportManager.getWorld()
			val globalVec3i = getNetwork().manager.transportManager.getGlobalCoordinate(toVec3i(location))
			val block = getBlockIfLoaded(world, globalVec3i.x, globalVec3i.y, globalVec3i.z) ?: return null

			return block.blockData.customBlock?.key == CustomBlockKeys.FLUID_PIPE_JUNCTION
		}

		override fun getPipableDirections(): Set<BlockFace> = ADJACENT_BLOCK_FACES
	}

	class RegularLinearPipe(location: BlockKey, val axis: Axis) : FluidNode(location, TransportNetworkNodeTypeKeys.FLUID_LINEAR_REGULAR.getValue(), 5.0), LeakablePipe {
		override val flowCapacity: Double = 5.0

		override val leakRate: Double = 1.0

		override fun isIntact(): Boolean? {
			val world = getNetwork().manager.transportManager.getWorld()
			val globalVec3i = getNetwork().manager.transportManager.getGlobalCoordinate(toVec3i(location))
			val block = getBlockIfLoaded(world, globalVec3i.x, globalVec3i.y, globalVec3i.z) ?: return null

			val blockAxis = (block.blockData.customBlock as? FluidPipeBlock)?.getAxis(block.blockData as MultipleFacing)
			val localAxis = blockAxis?.let { getNetwork().manager.transportManager.getLocalDirection(it.faces.first).axis }
			return block.blockData.customBlock?.key == CustomBlockKeys.FLUID_PIPE && localAxis == axis
		}

		override fun getPipableDirections(): Set<BlockFace> = setOf(axis.faces.first, axis.faces.second)
	}

	class ReinforcedJunctionPipe(location: BlockKey) : FluidNode(location, TransportNetworkNodeTypeKeys.FLUID_JUNCTION_REINFORCED.getValue(), 10.0) {
		override val flowCapacity: Double = 35.0

		override fun isIntact(): Boolean? {
			val world = getNetwork().manager.transportManager.getWorld()
			val globalVec3i = getNetwork().manager.transportManager.getGlobalCoordinate(toVec3i(location))
			val block = getBlockIfLoaded(world, globalVec3i.x, globalVec3i.y, globalVec3i.z) ?: return null

			return block.blockData.customBlock?.key == CustomBlockKeys.REINFORCED_FLUID_PIPE_JUNCTION
		}

		override fun getPipableDirections(): Set<BlockFace> = ADJACENT_BLOCK_FACES
	}

	class ReinforcedLinearPipe(location: BlockKey, val axis: Axis) : FluidNode(location, TransportNetworkNodeTypeKeys.FLUID_LINEAR_REINFORCED.getValue(), 5.0), LeakablePipe {
		override val flowCapacity: Double = 35.0

		override val leakRate: Double = 1.0

		override fun isIntact(): Boolean? {
			val world = getNetwork().manager.transportManager.getWorld()
			val globalVec3i = getNetwork().manager.transportManager.getGlobalCoordinate(toVec3i(location))
			val block = getBlockIfLoaded(world, globalVec3i.x, globalVec3i.y, globalVec3i.z) ?: return null

			val blockAxis = (block.blockData.customBlock as? ReinforcedFluidPipeBlock)?.getAxis(block.blockData as MultipleFacing)
			val localAxis = blockAxis?.let { getNetwork().manager.transportManager.getLocalDirection(it.faces.first).axis }
			return block.blockData.customBlock?.key == CustomBlockKeys.REINFORCED_FLUID_PIPE && localAxis == axis
		}

		override fun getPipableDirections(): Set<BlockFace> = setOf(axis.faces.first, axis.faces.second)
	}

	class FluidPort(location: BlockKey) : FluidNode(location, TransportNetworkNodeTypeKeys.FLUID_PORT.getValue(), 0.0) {
		override val flowCapacity = 50.0

		val removalCapacity: Double get() = 50.0
		val additionCapacity: Double get() = 30.0

		override fun isIntact(): Boolean? {
			val world = getNetwork().manager.transportManager.getWorld()
			val globalVec3i = getNetwork().manager.transportManager.getGlobalCoordinate(toVec3i(location))
			val block = getBlockIfLoaded(world, globalVec3i.x, globalVec3i.y, globalVec3i.z) ?: return null

			return block.blockData.customBlock?.key == CustomBlockKeys.FLUID_PORT
		}

		override fun getPipableDirections(): Set<BlockFace> = ADJACENT_BLOCK_FACES
	}

	class FluidValve(location: BlockKey) : FluidNode(location, TransportNetworkNodeTypeKeys.FLUID_VALVE.getValue(), 0.0) {
		override val flowCapacity: Double get() {
			val manager = getNetwork().manager.transportManager
			val global = manager.getGlobalCoordinate(toVec3i(location))
			val block = getBlockIfLoaded(manager.getWorld(), global.x, global.y, global.z) ?: return 0.0
			return if (block.isBlockPowered) Double.MAX_VALUE else 0.0
		}

		override fun isIntact(): Boolean? {
			val world = getNetwork().manager.transportManager.getWorld()
			val globalVec3i = getNetwork().manager.transportManager.getGlobalCoordinate(toVec3i(location))
			val block = getBlockIfLoaded(world, globalVec3i.x, globalVec3i.y, globalVec3i.z) ?: return null

			return block.blockData.customBlock?.key == CustomBlockKeys.FLUID_VALVE
		}

		override fun getPipableDirections(): Set<BlockFace> = ADJACENT_BLOCK_FACES
	}
}
