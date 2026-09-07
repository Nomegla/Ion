package net.horizonsend.ion.server.features.transport.manager

import net.horizonsend.ion.server.features.multiblock.manager.MultiblockManager
import net.horizonsend.ion.server.features.starship.Starship
import net.horizonsend.ion.server.features.starship.movement.StarshipMovement
import net.horizonsend.ion.server.features.transport.NewTransport
import net.horizonsend.ion.server.features.transport.filters.manager.ShipFilterCache
import net.horizonsend.ion.server.features.transport.inputs.IOManager
import net.horizonsend.ion.server.features.transport.inputs.ShipIOManager
import net.horizonsend.ion.server.features.transport.manager.extractors.ShipExtractorManager
import net.horizonsend.ion.server.features.transport.manager.extractors.data.AdvancedExtractorData
import net.horizonsend.ion.server.features.transport.manager.graph.FluidNetworkManager
import net.horizonsend.ion.server.features.transport.manager.holders.ShipCacheHolder
import net.horizonsend.ion.server.features.transport.nodes.cache.ItemTransportCache
import net.horizonsend.ion.server.features.transport.nodes.cache.PowerTransportCache
import net.horizonsend.ion.server.features.transport.nodes.cache.SolarPanelCache
import net.horizonsend.ion.server.features.world.IonWorld.Companion.ion
import net.horizonsend.ion.server.miscellaneous.utils.ADJACENT_BLOCK_FACES
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.BlockKey
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.Vec3i
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.toBlockKey
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.toVec3i
import org.bukkit.World
import org.bukkit.block.BlockFace

class ShipTransportManager(val starship: Starship) : TransportManager<ShipCacheHolder<*>>() {
	override val extractorManager: ShipExtractorManager = ShipExtractorManager(this)
	override val filterCache: ShipFilterCache = ShipFilterCache(this)
	val ioManager = ShipIOManager(this)

	override fun getWorld(): World = starship.world

	override val powerNodeManager = ShipCacheHolder(this) { PowerTransportCache(it) }
	override val solarPanelManager = ShipCacheHolder(this) { SolarPanelCache(it) }
	override val itemPipeManager = ShipCacheHolder(this) { ItemTransportCache(it) }
	private val fluidGraphManager = FluidNetworkManager(this)
	private var claimedWorld: World? = null
	private var claimedPositions: Collection<BlockKey> = emptySet()
	private var pausedWorldManagers = emptySet<FluidNetworkManager>()
	private var movementStartedAt = 0L

	override fun getMultiblockmanager(globalVec3i: Vec3i): MultiblockManager {
		return starship.multiblockManager
	}

	override val cacheHolders: Array<ShipCacheHolder<*>> = arrayOf(
		powerNodeManager,
		solarPanelManager,
		itemPipeManager,
	)

	override val tickedHolders: Array<ShipCacheHolder<*>> = arrayOf(
		powerNodeManager,
		itemPipeManager,
		solarPanelManager,
	)

	fun processLoad() {
		cacheHolders.forEach { it.handleLoad() }
		filterCache
		extractorManager.loadExtractors()

		val globalPositions = getShipBlockPositions()
		val worldFluidManager = getWorld().ion.transportManager.fluidGraphManager
		worldFluidManager.pause()
		try {
			worldFluidManager.transferPositionsTo(fluidGraphManager, globalPositions)
			cacheFluidNodes(globalPositions)
			updateFluidClaim(getWorld(), getFluidBlockPositions())
		} finally {
			worldFluidManager.resume()
		}

		NewTransport.registerTransportManager(this)
	}

	fun onDestroy() {
		NewTransport.removeTransportManager(this)
		fluidGraphManager.pause()

		try {
			cacheFluidNodes(getShipBlockPositions())
			updateFluidClaim(getWorld(), getFluidBlockPositions())

			fluidGraphManager.transferPositionsTo(
				getWorld().ion.transportManager.fluidGraphManager,
				fluidGraphManager.allLocations().toList()
			)
			fluidGraphManager.clear()
		} finally {
			releaseFluidClaim()
			cacheHolders.forEach { it.release() }
			extractorManager.releaseExtractors()
		}
	}

	private fun getShipBlockPositions(): List<BlockKey> = buildList {
		starship.iterateBlocks { x, y, z -> add(toBlockKey(x, y, z)) }
	}

	private fun cacheFluidNodes(globalPositions: Collection<BlockKey>) {
		for (position in globalPositions) {
			fluidGraphManager.registerPositionIfAbsent(toBlockKey(getLocalCoordinate(toVec3i(position))))
		}
	}

	private fun getFluidBlockPositions(): List<BlockKey> {
		return fluidGraphManager.allLocations().map { localPosition ->
			toBlockKey(getGlobalCoordinate(toVec3i(localPosition)))
		}
	}

	private fun updateFluidClaim(world: World, positions: Collection<BlockKey>) {
		val newPositions = positions.toSet()
		world.ion.transportManager.claimFluidPositions(fluidGraphManager, newPositions)

		val oldWorld = claimedWorld
		if (oldWorld != null) {
			val noLongerClaimed = if (oldWorld == world) claimedPositions - newPositions else claimedPositions
			oldWorld.ion.transportManager.releaseFluidPositions(fluidGraphManager, noLongerClaimed)
		}

		claimedWorld = world
		claimedPositions = newPositions
	}

	private fun releaseFluidClaim() {
		claimedWorld?.ion?.transportManager?.releaseFluidPositions(fluidGraphManager, claimedPositions)
		claimedWorld = null
		claimedPositions = emptySet()
	}

	fun displace(movement: StarshipMovement) {
		cacheHolders.forEach { it.displace(movement) }
	}

	override fun getInputProvider(): IOManager {
		return ioManager
	}

	override fun getGlobalCoordinate(localVec3i: Vec3i): Vec3i {
		return starship.getGlobalCoordinate(localVec3i)
	}

	override fun getLocalCoordinate(globalVec3i: Vec3i): Vec3i {
		return starship.getLocalCoordinate(globalVec3i)
	}

	override fun getGlobalDirection(localDirection: BlockFace): BlockFace {
		return adjustDirection(localDirection, false)
	}

	override fun getLocalDirection(globalDirection: BlockFace): BlockFace {
		return adjustDirection(globalDirection, true)
	}

	private fun adjustDirection(direction: BlockFace, opposite: Boolean): BlockFace {
		if (direction == BlockFace.SELF) return direction
		val adjusted = starship.getAdjusted(Vec3i(direction.modX, direction.modY, direction.modZ), opposite)
		return ADJACENT_BLOCK_FACES.first { face ->
			face.modX == adjusted.x && face.modY == adjusted.y && face.modZ == adjusted.z
		}
	}

	override fun isLocalCoordinate(localVec3i: Vec3i): Boolean {
		val global = getGlobalCoordinate(localVec3i)
		return starship.contains(global.x, global.y, global.z)
	}

	fun pauseFluidNetworks(destinationWorld: World) {
		fluidGraphManager.pause()
		movementStartedAt = System.currentTimeMillis()
		pausedWorldManagers = setOf(
			getWorld().ion.transportManager.fluidGraphManager,
			destinationWorld.ion.transportManager.fluidGraphManager
		)
		pausedWorldManagers.forEach(FluidNetworkManager::pause)
	}

	fun resumeFluidNetworks() {
		try {
			updateFluidClaim(getWorld(), getFluidBlockPositions())
		} finally {
			pausedWorldManagers.forEach(FluidNetworkManager::resume)
			pausedWorldManagers = emptySet()
			fluidGraphManager.resumeAfterMovement(maxOf(0L, System.currentTimeMillis() - movementStartedAt))
		}
	}

	fun clearData() {
		extractorManager.extractors.clear()
		filterCache.filters.clear()
	}

	override fun tickExtractors() {
		tickNumber++

		val extractors = extractorManager.getExtractors()
		val extractorCount = extractors.size

		for ((index, extractor) in extractors.withIndex()) {
			if (!starship.isTeleporting && !extractorManager.verifyExtractor(getWorld(), extractor.pos)) {
				continue
			}

			val delta = extractor.markTicked()

			for (network in tickedHolders) {
				network.cache.tickExtractor(extractor.pos, delta, (extractor as? AdvancedExtractorData<*>)?.metaData, index, extractorCount)
			}
		}

		solarPanelManager.cache.tickSolarPanels()
	}

	override fun tickGraphs() {
		fluidGraphManager.tick()
	}

	override fun getGraphTransportManager(): FluidNetworkManager {
		return fluidGraphManager
	}
}
