package net.horizonsend.ion.server.features.multiblock.type.fluid

import net.horizonsend.ion.common.utils.text.colors.HEColorScheme.Companion.HE_LIGHT_BLUE
import net.horizonsend.ion.server.core.registration.keys.FluidTypeKeys
import net.horizonsend.ion.server.features.client.display.modular.DisplayHandlers
import net.horizonsend.ion.server.features.client.display.modular.TextDisplayHandler
import net.horizonsend.ion.server.features.client.display.modular.display.MATCH_SIGN_FONT_SIZE
import net.horizonsend.ion.server.features.client.display.modular.display.fluid.SplitFluidDisplayModule
import net.horizonsend.ion.server.features.client.display.modular.display.getLinePos
import net.horizonsend.ion.server.features.multiblock.Multiblock
import net.horizonsend.ion.server.features.multiblock.entity.MultiblockEntity
import net.horizonsend.ion.server.features.multiblock.entity.PersistentMultiblockData
import net.horizonsend.ion.server.features.multiblock.entity.type.DisplayMultiblockEntity
import net.horizonsend.ion.server.features.multiblock.entity.type.fluids.FluidPortMetadata
import net.horizonsend.ion.server.features.multiblock.entity.type.fluids.FluidStoringMultiblock
import net.horizonsend.ion.server.features.multiblock.entity.type.fluids.storage.FluidRestriction
import net.horizonsend.ion.server.features.multiblock.entity.type.fluids.storage.FluidStorageContainer
import net.horizonsend.ion.server.features.multiblock.entity.type.ticked.SyncTickingMultiblockEntity
import net.horizonsend.ion.server.features.multiblock.entity.type.ticked.TickedMultiblockEntityParent
import net.horizonsend.ion.server.features.multiblock.manager.MultiblockManager
import net.horizonsend.ion.server.features.multiblock.shape.MultiblockShape
import net.horizonsend.ion.server.features.multiblock.type.EntityMultiblock
import net.horizonsend.ion.server.features.multiblock.util.PrepackagedPreset
import net.horizonsend.ion.server.features.transport.inputs.IOData
import net.horizonsend.ion.server.features.transport.inputs.IOPort
import net.horizonsend.ion.server.features.transport.inputs.IOType
import net.horizonsend.ion.server.miscellaneous.registrations.persistence.NamespacedKeys
import net.horizonsend.ion.server.miscellaneous.utils.LIGHTNING_ROD_TYPES
import net.horizonsend.ion.server.miscellaneous.utils.Tasks
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.RelativeFace
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.type.Sapling
import org.bukkit.persistence.PersistentDataAdapterContext

object SprinklerMultiblock : Multiblock(), EntityMultiblock<SprinklerMultiblock.SprinklerMultiblockEntity> {
	override val name: String = "sprinkler"
	override val signText: Array<Component?> = createSignText(
		Component.text("Sprinkler", HE_LIGHT_BLUE),
		null,
		null,
		null
	)

	override fun MultiblockShape.buildStructure() {
		z(0) {
			y(0) {
				x(0).fluidPort()
			}
			y(1) {
				x(0).anyStairs()
			}
			y(2) {
				x(0).anyType(
					alias = "lightning rod",
					types = LIGHTNING_ROD_TYPES,
					PrepackagedPreset.simpleDirectional(
						RelativeFace.UP,
						example = Material.LIGHTNING_ROD.createBlockData()
					)
				)
			}
		}
	}

	override fun createEntity(
		manager: MultiblockManager,
		data: PersistentMultiblockData,
		world: World,
		x: Int,
		y: Int,
		z: Int,
		structureDirection: BlockFace
	): SprinklerMultiblockEntity {
		return SprinklerMultiblockEntity(data, manager, world, x, y, z, structureDirection)
	}

	class SprinklerMultiblockEntity(
		data: PersistentMultiblockData,
		manager: MultiblockManager,
		world: World,
		x: Int,
		y: Int,
		z: Int,
		structureDirection: BlockFace
	) : MultiblockEntity(manager, SprinklerMultiblock, world, x, y, z, structureDirection),
		DisplayMultiblockEntity,
		FluidStoringMultiblock,
		SyncTickingMultiblockEntity {
		override val tickingManager = TickedMultiblockEntityParent.TickingManager(WATERING_INTERVAL_TICKS)

		val fluidStorage = FluidStorageContainer(
			data,
			"fluid_storage",
			Component.text("Sprinkler Fluid"),
			WATER_STORAGE_KEY,
			WATER_CAPACITY_LITERS,
			FluidRestriction.FluidTypeWhitelist(ACCEPTED_FLUIDS)
		)

		override val ioData: IOData = IOData.builder(this)
			.addPort(IOType.FLUID, 0, 0, 0) {
				IOPort.RegisteredMetaDataInput(this, FluidPortMetadata(
					connectedStore = fluidStorage,
					inputAllowed = true,
					outputAllowed = false
				))
			}
			.build()

		override val displayHandler: TextDisplayHandler = DisplayHandlers.newMultiblockSignOverlay(
			this,
			{
				SplitFluidDisplayModule(
					handler = it,
					storage = fluidStorage,
					offsetLeft = 0.0,
					offsetUp = getLinePos(4),
					offsetBack = 0.0,
					scale = MATCH_SIGN_FONT_SIZE
				)
			}
		)

		override fun getStores(): List<FluidStorageContainer> = listOf(fluidStorage)

		override fun storeAdditionalData(
			store: PersistentMultiblockData,
			adapterContext: PersistentDataAdapterContext
		) {
			saveStorageData(store)
		}

		override fun tick() {
			val fluid = fluidStorage.getContents()
			if (fluid.isEmpty() || fluid.type !in ACCEPTED_FLUIDS) return

			val useFertilizer = fluid.type == FluidTypeKeys.FERTILIZER
			for ((rowIndex, right) in (-WATERING_RADIUS..WATERING_RADIUS).withIndex()) {
				if (rowIndex == 0) {
					waterRow(right, useFertilizer)
					continue
				}

				Tasks.syncDelay((rowIndex * ROW_DELAY_TICKS).toLong()) {
					if (!isAlive || !isIntact()) return@syncDelay
					waterRow(right, useFertilizer)
				}
			}
		}

		private fun waterRow(right: Int, useFertilizer: Boolean) {
			val expectedFluidType = if (useFertilizer) FluidTypeKeys.FERTILIZER else FluidTypeKeys.WATER
			val fluid = fluidStorage.getContents()
			if (fluid.isEmpty() || fluid.type != expectedFluidType) return

			for (forward in -WATERING_RADIUS..WATERING_RADIUS) {
				if (fluidStorage.getContents().amount < FLUID_PER_GROWTH_LITERS) return

				val block = getBlockRelative(right, SPRINKLER_HEAD_HEIGHT, forward)
				val grew = if (useFertilizer) fullyGrow(block) else block.applyBoneMeal(BlockFace.DOWN)
				if (!grew) continue

				fluidStorage.removeAmount(FLUID_PER_GROWTH_LITERS)
			}
		}

		private fun fullyGrow(block: Block): Boolean {
			var grew = false

			repeat(MAXIMUM_FERTILIZER_GROWTH_ATTEMPTS) {
				val blockData = block.blockData
				val canRequireMultipleApplications = blockData is Ageable ||
					blockData is Sapling ||
					block.type in FERTILIZER_RETRY_TYPES

				if (!canRequireMultipleApplications) {
					if (grew) return true
					return block.applyBoneMeal(BlockFace.DOWN)
				}

				if (blockData is Ageable &&
					blockData.age >= blockData.maximumAge &&
					block.type !in FERTILIZER_RETRY_TYPES
				) return grew

				grew = block.applyBoneMeal(BlockFace.DOWN) || grew
			}

			return grew
		}

		companion object {
			private const val WATERING_INTERVAL_TICKS = 400
			private const val ROW_DELAY_TICKS = 2
			private const val WATERING_RADIUS = 4
			private const val SPRINKLER_HEAD_HEIGHT = 2
			private const val WATER_CAPACITY_LITERS = 1_000.0
			private const val FLUID_PER_GROWTH_LITERS = 1.0
			private const val MAXIMUM_FERTILIZER_GROWTH_ATTEMPTS = 64

			private val WATER_STORAGE_KEY = NamespacedKeys.key("sprinkler_water_storage")
			private val ACCEPTED_FLUIDS = setOf(FluidTypeKeys.WATER, FluidTypeKeys.FERTILIZER)
			private val FERTILIZER_RETRY_TYPES = setOf(
				Material.AZALEA,
				Material.FLOWERING_AZALEA,
				Material.BAMBOO,
				Material.BAMBOO_SAPLING,
				Material.BROWN_MUSHROOM,
				Material.RED_MUSHROOM,
				Material.CRIMSON_FUNGUS,
				Material.WARPED_FUNGUS,
				Material.MANGROVE_PROPAGULE
			)
		}
	}
}
