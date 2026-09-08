package net.horizonsend.ion.server.features.multiblock.type.fluid.turbine

import net.horizonsend.ion.common.utils.text.colors.HEColorScheme.Companion.HE_LIGHT_BLUE
import net.horizonsend.ion.common.utils.text.colors.HEColorScheme.Companion.HE_MEDIUM_GRAY
import net.horizonsend.ion.common.utils.text.ofChildren
import net.horizonsend.ion.server.core.registration.IonRegistryKey
import net.horizonsend.ion.server.core.registration.keys.FluidPropertyTypeKeys
import net.horizonsend.ion.server.core.registration.keys.FluidTypeKeys
import net.horizonsend.ion.server.features.client.display.modular.DisplayHandlers
import net.horizonsend.ion.server.features.client.display.modular.TextDisplayHandler
import net.horizonsend.ion.server.features.client.display.modular.display.MATCH_SIGN_FONT_SIZE
import net.horizonsend.ion.server.features.client.display.modular.display.PowerEntityDisplayModule
import net.horizonsend.ion.server.features.client.display.modular.display.fluid.ComplexFluidDisplayModule
import net.horizonsend.ion.server.features.client.display.modular.display.getLinePos
import net.horizonsend.ion.server.features.multiblock.Multiblock
import net.horizonsend.ion.server.features.multiblock.entity.PersistentMultiblockData
import net.horizonsend.ion.server.features.multiblock.entity.type.fluids.FluidPortMetadata
import net.horizonsend.ion.server.features.multiblock.entity.type.fluids.FluidStoringMultiblock
import net.horizonsend.ion.server.features.multiblock.entity.type.fluids.storage.FluidRestriction
import net.horizonsend.ion.server.features.multiblock.entity.type.fluids.storage.FluidStorageContainer
import net.horizonsend.ion.server.features.multiblock.entity.type.power.SimplePoweredEntity
import net.horizonsend.ion.server.features.multiblock.entity.type.ticked.AsyncTickingMultiblockEntity
import net.horizonsend.ion.server.features.multiblock.entity.type.ticked.TickedMultiblockEntityParent
import net.horizonsend.ion.server.features.multiblock.manager.MultiblockManager
import net.horizonsend.ion.server.features.multiblock.type.EntityMultiblock
import net.horizonsend.ion.server.features.multiblock.type.fluid.turbine.TurbineMultiblock.TurbineMultiblockEntity
import net.horizonsend.ion.server.features.transport.fluids.FluidType
import net.horizonsend.ion.server.features.transport.fluids.properties.FluidProperty
import net.horizonsend.ion.server.features.transport.fluids.types.Steam
import net.horizonsend.ion.server.features.transport.inputs.IOData
import net.horizonsend.ion.server.features.transport.inputs.IOPort
import net.horizonsend.ion.server.features.transport.inputs.IOType
import net.horizonsend.ion.server.miscellaneous.registrations.persistence.NamespacedKeys
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.RelativeFace
import net.kyori.adventure.text.Component
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.persistence.PersistentDataAdapterContext
import org.bukkit.persistence.PersistentDataType
import kotlin.math.floor

abstract class TurbineMultiblock : Multiblock(), EntityMultiblock<TurbineMultiblockEntity> {
	override val name: String = "turbine"

	abstract val maximumSteamConsumptionPerSecond: Double
	abstract val maximumPowerGenerationPerSecond: Map<IonRegistryKey<FluidType, out FluidType>, Double>
	abstract val steamInputCapacity: Double

	override val signText: Array<Component?> = createSignText(
		ofChildren(Component.text("Steam", HE_LIGHT_BLUE), Component.text(" Turbine", HE_MEDIUM_GRAY)),
		null,
		null,
		null
	)

	override fun createEntity(
		manager: MultiblockManager,
		data: PersistentMultiblockData,
		world: World,
		x: Int,
		y: Int,
		z: Int,
		structureDirection: BlockFace
	): TurbineMultiblockEntity {
		return TurbineMultiblockEntity(data, manager, this, world, x, y, z, structureDirection)
	}

	class TurbineMultiblockEntity(
		data: PersistentMultiblockData,
		manager: MultiblockManager,
		override val multiblock: TurbineMultiblock,
		world: World,
		x: Int,
		y: Int,
		z: Int,
		structureDirection: BlockFace
	) : SimplePoweredEntity(
		data,
		multiblock,
		manager,
		x,
		y,
		z,
		world,
		structureDirection,
		MAXIMUM_POWER_STORAGE
	), AsyncTickingMultiblockEntity, FluidStoringMultiblock {
		override val tickingManager: TickedMultiblockEntityParent.TickingManager =
			TickedMultiblockEntityParent.TickingManager(2)

		val steamInput = FluidStorageContainer(
			data,
			"steam_input",
			Component.text("Steam Input"),
			STEAM_INPUT_KEY,
			multiblock.steamInputCapacity,
			FluidRestriction.FluidTypeWhitelist(INPUT_STEAM_TYPES)
		)

		val steamOutput = FluidStorageContainer(
			data,
			"steam_output",
			Component.text("Steam Output"),
			STEAM_OUTPUT_KEY,
			multiblock.steamInputCapacity,
			FluidRestriction.FluidTypeWhitelist(OUTPUT_STEAM_TYPES)
		)

		private var generatedPowerRemainder =
			data.getAdditionalDataOrDefault(POWER_REMAINDER_KEY, PersistentDataType.DOUBLE, 0.0)

		override val ioData: IOData = IOData.builder(this)
			.addPort(IOType.FLUID, -1, 0, 0) {
				IOPort.RegisteredMetaDataInput(this, FluidPortMetadata(
					connectedStore = steamInput,
					inputAllowed = true,
					outputAllowed = false
				))
			}
			.addPort(IOType.FLUID, 1, 0, 0) {
				IOPort.RegisteredMetaDataInput(this, FluidPortMetadata(
					connectedStore = steamOutput,
					inputAllowed = false,
					outputAllowed = true
				))
			}
			.addPowerInput(0, -1, 0)
			.build()

		override val displayHandler: TextDisplayHandler = DisplayHandlers.newMultiblockSignOverlay(
			this,
			{
				ComplexFluidDisplayModule(
					handler = it,
					container = steamInput,
					title = Component.text("Input"),
					offsetLeft = 1.5,
					offsetUp = 0.15,
					offsetBack = -2.0 + 0.39,
					scale = 0.7f,
					RelativeFace.RIGHT
				)
			},
			{
				ComplexFluidDisplayModule(
					handler = it,
					container = steamOutput,
					title = Component.text("Output"),
					offsetLeft = -1.5,
					offsetUp = 0.15,
					offsetBack = -2.0 + 0.39,
					scale = 0.7f,
					RelativeFace.LEFT
				)
			},
			{
				PowerEntityDisplayModule(
					handler = it,
					multiblockEntity = this,
					offsetLeft = 0.0,
					offsetUp = getLinePos(2),
					offsetBack = 0.0,
					scale = MATCH_SIGN_FONT_SIZE
				)
			}
		)

		override fun getStores(): List<FluidStorageContainer> = listOf(steamInput, steamOutput)

		override fun storeAdditionalData(
			store: PersistentMultiblockData,
			adapterContext: PersistentDataAdapterContext
		) {
			savePowerData(store)
			saveStorageData(store)
			store.addAdditionalData(POWER_REMAINDER_KEY, PersistentDataType.DOUBLE, generatedPowerRemainder)
		}

		override fun tickAsync() {
			bootstrapFluidNetwork()

			val deltaSeconds = minOf(deltaTMS, MAXIMUM_DELTA_MILLIS).toDouble() / 1000.0
			generatePower(deltaSeconds)
		}

		private fun generatePower(deltaSeconds: Double) {
			if (powerStorage.isFull()) return

			val steam = steamInput.getContents()
			if (steam.isEmpty()) return

			val steamType = steam.type.getValue() as? Steam ?: return
			val maximumPowerGeneration = multiblock.maximumPowerGenerationPerSecond[steam.type] ?: return
			if (!steamOutput.canAdd(steamType.turbineResult)) return

			val powerPerLiter =
				maximumPowerGeneration / multiblock.maximumSteamConsumptionPerSecond
			val remainingPowerCapacity = powerStorage.getRemainingCapacity().toDouble() - generatedPowerRemainder
			if (remainingPowerCapacity <= EPSILON) return

			val steamAllowedByRate = multiblock.maximumSteamConsumptionPerSecond * deltaSeconds
			val steamAllowedByPowerStorage = remainingPowerCapacity / powerPerLiter
			val steamToConsume = minOf(
				steam.amount,
				steamAllowedByRate,
				steamAllowedByPowerStorage,
				steamOutput.getRemainingRoom()
			)
			if (steamToConsume <= EPSILON) return

			val output = steam.asAmount(steamToConsume)
			output.type = steamType.turbineResult

			val defaultTemperature = FluidProperty.Temperature.DEFAULT_TEMPERATURE
			val inputTemperature = steam.getDataOrDefault(FluidPropertyTypeKeys.TEMPERATURE, location).value
			val outputTemperature = defaultTemperature +
				((inputTemperature - defaultTemperature) * STEAM_TEMPERATURE_RETENTION)
			output.setData(FluidPropertyTypeKeys.TEMPERATURE, FluidProperty.Temperature(outputTemperature))

			steamOutput.addFluid(output, location)
			steamInput.removeAmount(steamToConsume)

			val exactGeneratedPower = generatedPowerRemainder + (steamToConsume * powerPerLiter)
			val wholeGeneratedPower = floor(exactGeneratedPower).toInt()
			generatedPowerRemainder = exactGeneratedPower - wholeGeneratedPower

			if (wholeGeneratedPower > 0) powerStorage.addPower(wholeGeneratedPower)
		}

		companion object {
			private const val MAXIMUM_POWER_STORAGE = 500_000
			private const val MAXIMUM_DELTA_MILLIS = 1_000L
			private const val EPSILON = 0.000_001
			private const val STEAM_TEMPERATURE_RETENTION = 0.75

			private val STEAM_INPUT_KEY = NamespacedKeys.key("turbine_steam_input")
			private val STEAM_OUTPUT_KEY = NamespacedKeys.key("turbine_steam_output")
			private val POWER_REMAINDER_KEY = NamespacedKeys.key("turbine_power_remainder")

			private val INPUT_STEAM_TYPES = setOf(
				FluidTypeKeys.DENSE_STEAM,
				FluidTypeKeys.SUPER_DENSE_STEAM,
				FluidTypeKeys.ULTRA_DENSE_STEAM
			)

			private val OUTPUT_STEAM_TYPES = setOf(
				FluidTypeKeys.LOW_PRESSURE_STEAM,
				FluidTypeKeys.DENSE_STEAM,
				FluidTypeKeys.SUPER_DENSE_STEAM
			)
		}
	}
}
