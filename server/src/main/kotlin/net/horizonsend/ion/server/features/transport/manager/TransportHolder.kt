package net.horizonsend.ion.server.features.transport.manager

import net.horizonsend.ion.server.features.multiblock.manager.MultiblockManager
import net.horizonsend.ion.server.features.transport.inputs.IOManager
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.Vec3i
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.persistence.PersistentDataContainer
import java.util.function.Consumer

interface TransportHolder {
	fun getInputProvider(): IOManager
	fun getMultiblockmanager(globalVec3i: Vec3i): MultiblockManager?

	fun getWorld(): World

	fun getGlobalCoordinate(localVec3i: Vec3i): Vec3i = localVec3i
	fun getLocalCoordinate(globalVec3i: Vec3i): Vec3i = globalVec3i
	fun getGlobalDirection(localDirection: BlockFace): BlockFace = localDirection
	fun getLocalDirection(globalDirection: BlockFace): BlockFace = globalDirection
	fun isLocalCoordinate(localVec3i: Vec3i): Boolean = true

	fun tickExtractors()

	fun tickGraphs()

	fun storePersistentData(storeConsumer: Consumer<PersistentDataContainer>) {}
}
