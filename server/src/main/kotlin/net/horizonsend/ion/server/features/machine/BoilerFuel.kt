package net.horizonsend.ion.server.features.machine

import net.horizonsend.ion.server.core.registration.keys.FluidTypeKeys
import net.horizonsend.ion.server.core.registration.registries.CustomItemRegistry.Companion.customItem
import net.horizonsend.ion.server.features.transport.fluids.FluidStack
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

private fun itemStack(material: Material): ItemStack = ItemStack(material, 1)

enum class BoilerFuel(
	private val item: ItemStack,
	val burnDurationMillis: Long,
	val heatOutputJoulesPerSecond: Double,
	val pollutionResult: FluidStack
) {
	COAL(
		item = itemStack(Material.COAL),
		burnDurationMillis = 2000,
		heatOutputJoulesPerSecond = 4000000.0,
		pollutionResult = FluidStack(FluidTypeKeys.POLLUTION, 20.0)
	),
	COAL_BLOCK(
		item = itemStack(Material.COAL_BLOCK),
		burnDurationMillis = 18000,
		heatOutputJoulesPerSecond = 3500000.0,
		pollutionResult = FluidStack(FluidTypeKeys.POLLUTION, 20.0)
	),
	// TODO: coal dust
	DRIED_KELP_BLOCK(
		item = itemStack(Material.DRIED_KELP_BLOCK),
		burnDurationMillis = 250 * 9,
		heatOutputJoulesPerSecond = 40000.0,
		pollutionResult = FluidStack(FluidTypeKeys.POLLUTION, 20.0)
	),
	DRIED_KELP(
		item = itemStack(Material.DRIED_KELP),
		burnDurationMillis = 250,
		heatOutputJoulesPerSecond = 40000.0,
		pollutionResult = FluidStack(FluidTypeKeys.POLLUTION, 20.0)
	);

	companion object {
		private val itemMap: Map<String, BoilerFuel> = entries.associateBy { createKey(it.item) }

		operator fun get(item: ItemStack): BoilerFuel? = itemMap[createKey(item)]

		private fun createKey(item: ItemStack): String = item.customItem?.identifier ?: item.type.name
	}
}
