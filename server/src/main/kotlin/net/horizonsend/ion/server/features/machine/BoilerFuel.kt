package net.horizonsend.ion.server.features.machine

import net.horizonsend.ion.server.core.registration.keys.FluidTypeKeys
import net.horizonsend.ion.server.core.registration.registries.CustomItemRegistry.Companion.customItem
import net.horizonsend.ion.server.features.transport.fluids.FluidStack
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

private fun itemStack(material: Material): ItemStack = ItemStack(material, 1)

enum class BoilerFuel(
	private val item: ItemStack,
	val burnDurationTicks: Int,
	val heatOutputJoulesPerTick: Double,
	val pollutionResult: FluidStack
) {
	COAL(
		item = itemStack(Material.COAL),
		burnDurationTicks = 40,
		heatOutputJoulesPerTick = 200_000.0,
		pollutionResult = FluidStack(FluidTypeKeys.POLLUTION, 20.0)
	),
	COAL_BLOCK(
		item = itemStack(Material.COAL_BLOCK),
		burnDurationTicks = 360,
		heatOutputJoulesPerTick = 175_000.0,
		pollutionResult = FluidStack(FluidTypeKeys.POLLUTION, 20.0)
	),
	// TODO: coal dust
	DRIED_KELP_BLOCK(
		item = itemStack(Material.DRIED_KELP_BLOCK),
		burnDurationTicks = 5 * 9,
		heatOutputJoulesPerTick = 2_000.0,
		pollutionResult = FluidStack(FluidTypeKeys.POLLUTION, 20.0)
	),
	DRIED_KELP(
		item = itemStack(Material.DRIED_KELP),
		burnDurationTicks = 5,
		heatOutputJoulesPerTick = 2_000.0,
		pollutionResult = FluidStack(FluidTypeKeys.POLLUTION, 20.0)
	);

	companion object {
		private val itemMap: Map<String, BoilerFuel> = entries.associateBy { createKey(it.item) }

		operator fun get(item: ItemStack): BoilerFuel? = itemMap[createKey(item)]

		private fun createKey(item: ItemStack): String = item.customItem?.identifier ?: item.type.name
	}
}
