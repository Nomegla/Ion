package net.horizonsend.ion.server.features.transport.fluids.types

import net.horizonsend.ion.common.utils.text.ofChildren
import net.horizonsend.ion.server.core.registration.IonRegistryKey
import net.horizonsend.ion.server.core.registration.keys.FluidPropertyTypeKeys
import net.horizonsend.ion.server.features.multiblock.entity.type.fluids.storage.FluidStorageContainer
import net.horizonsend.ion.server.features.transport.fluids.DisplayProperties
import net.horizonsend.ion.server.features.transport.fluids.FluidStack
import net.horizonsend.ion.server.features.transport.fluids.FluidType
import net.horizonsend.ion.server.features.transport.fluids.FluidType.HeatingResult.Companion.HEATING_RATE_MULTIPLIER
import net.horizonsend.ion.server.features.transport.fluids.FluidUtils
import net.horizonsend.ion.server.features.transport.fluids.FluidUtils.getFluidWeight
import net.horizonsend.ion.server.features.transport.fluids.properties.FluidCategory
import net.horizonsend.ion.server.features.transport.fluids.properties.FluidProperty
import net.horizonsend.ion.server.miscellaneous.utils.centimetersCubedToLiters
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location

class Steam(
	key: IonRegistryKey<FluidType, out FluidType>,
	val prefix: Component,
	color: Color,
	heatCapacity: Double,
	pressureBars: Double = 1.0,
	val conversionResult: IonRegistryKey<FluidType, out FluidType>,
	val turbineResult: IonRegistryKey<FluidType, out FluidType>,
	val conversionCost: Double,
	val conversionTemperature: Double
) : GasFluid(key, DisplayProperties(color, "transparent_gas"), heatCapacity, 18.01528, pressureBars) {
	override val categories: Array<FluidCategory> = arrayOf(FluidCategory.GAS, FluidCategory.STEAM)

	override fun getDisplayName(stack: FluidStack): Component {
		return ofChildren(prefix, Component.text(" Steam"))
	}

	override fun getHeatingResult(
		stack: FluidStack,
		resultContainer: FluidStorageContainer,
		appliedEnergyJoules: Double,
		maximumTemperature: Double,
		location: Location?
	): HeatingResult {
		val currentTemperature = stack.getDataOrDefault(FluidPropertyTypeKeys.TEMPERATURE, location).value

		val effectiveEnergyJoules = appliedEnergyJoules * HEATING_RATE_MULTIPLIER
		val newTemperature = FluidUtils.getNewTemperature(stack, effectiveEnergyJoules, maximumTemperature, location)

		if (newTemperature.value < conversionTemperature) {
			return HeatingResult.TemperatureIncreaseInPlace(newTemperature)
		}

		val convertTemperature = FluidProperty.Temperature(conversionTemperature)

		val deltaTemperature = maxOf(conversionTemperature - currentTemperature, 0.0)
		val heatingJoules = getFluidWeight(stack, location) * getIsobaricHeatCapacity(stack) * deltaTemperature

		val spareJoules = maxOf(effectiveEnergyJoules - heatingJoules, 0.0)
		val convertedGrams = spareJoules / conversionCost

		val stackDensity = getDensity(stack, location)

		val convertedVolume = centimetersCubedToLiters(convertedGrams / stackDensity)

		// Create a temp stack to get the density of the result
		val tempStack = FluidStack(conversionResult, 1.0)
			.setData(FluidPropertyTypeKeys.TEMPERATURE, convertTemperature.clone())
		val resultDensity = conversionResult.getValue().getDensity(tempStack, location)

		// The density ratio gives the contraction factor for the higher-pressure result.
		val contractionFactor = stackDensity / resultDensity

		// Consume no more steam than the stack contains.
		val consumed = minOf(convertedVolume, stack.amount)
		val steamVolume = consumed * contractionFactor

		val steamStack = FluidStack(conversionResult, steamVolume)
			.setData(FluidPropertyTypeKeys.TEMPERATURE, convertTemperature.clone())

		return HeatingResult.Boiling(convertTemperature, steamStack, consumed)
	}
}
