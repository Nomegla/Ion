package net.horizonsend.ion.server.features.transport.manager

import net.horizonsend.ion.server.features.multiblock.manager.MultiblockManager
import net.horizonsend.ion.server.features.transport.NewTransport
import net.horizonsend.ion.server.features.transport.NewTransport.registerTransportManager
import net.horizonsend.ion.server.features.transport.inputs.IOManager
import net.horizonsend.ion.server.features.transport.manager.graph.FluidNetworkManager
import net.horizonsend.ion.server.features.world.IonWorld
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.BlockKey
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.Vec3i
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.toBlockKey
import org.bukkit.World
import org.bukkit.persistence.PersistentDataContainer
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

class WorldTransportManager(val world: IonWorld) : TransportHolder {
	val fluidGraphManager = FluidNetworkManager(this)
	private val shipFluidOwners = ConcurrentHashMap<BlockKey, FluidNetworkManager>()

	override fun getInputProvider(): IOManager {
		return world.inputManager
	}

	override fun getMultiblockmanager(globalVec3i: Vec3i): MultiblockManager? {
		return world.getChunkFromWorldcoordinates(globalVec3i.x, globalVec3i.z)?.multiblockManager
	}

	override fun getWorld(): World {
		return world.world
	}

	override fun isLocalCoordinate(localVec3i: Vec3i): Boolean {
		return !shipFluidOwners.containsKey(toBlockKey(localVec3i))
	}

	fun claimFluidPositions(owner: FluidNetworkManager, positions: Collection<BlockKey>) {
		positions.forEach { position -> shipFluidOwners[position] = owner }
	}

	fun releaseFluidPositions(owner: FluidNetworkManager, positions: Collection<BlockKey>) {
		positions.forEach { position -> shipFluidOwners.remove(position, owner) }
	}

	fun getFluidManager(position: BlockKey): FluidNetworkManager {
		return shipFluidOwners[position] ?: fluidGraphManager
	}

	fun load() {
		registerTransportManager(this)
	}

	fun unload() {
		// Stop ticking then save
		NewTransport.removeTransportManager(this)
		save()
	}

	override fun tickExtractors() {}

	override fun tickGraphs() {
		try {
			fluidGraphManager.tick()
		} catch (e: Throwable) {
			e.printStackTrace()
		}
	}

	override fun storePersistentData(storeConsumer: Consumer<PersistentDataContainer>) {
		storeConsumer.accept(world.world.persistentDataContainer)
	}

	fun save() {
		fluidGraphManager.save(world.world.persistentDataContainer.adapterContext)
	}
}
