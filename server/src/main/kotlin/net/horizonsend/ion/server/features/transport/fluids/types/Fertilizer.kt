package net.horizonsend.ion.server.features.transport.fluids.types

import net.horizonsend.ion.common.utils.text.colors.HEColorScheme.Companion.HE_LIGHT_ORANGE
import net.horizonsend.ion.server.core.registration.keys.FluidTypeKeys
import net.horizonsend.ion.server.features.transport.fluids.DisplayProperties
import net.horizonsend.ion.server.features.transport.fluids.FluidStack
import net.horizonsend.ion.server.features.transport.fluids.FluidType
import net.horizonsend.ion.server.features.transport.fluids.properties.FluidCategory
import net.horizonsend.ion.server.features.transport.manager.graph.fluid.FluidNetwork.Companion.PIPE_INTERIOR_PADDING
import net.horizonsend.ion.server.features.transport.manager.graph.fluid.FluidNode
import net.horizonsend.ion.server.miscellaneous.utils.axis
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import org.bukkit.Axis
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Particle.Trail
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.util.Vector
import kotlin.random.Random

object Fertilizer : FluidType(FluidTypeKeys.FERTILIZER) {
	private val LIGHT_BROWN = Color.fromRGB(242, 174, 103)
	private val LIGHT_BLUE = Color.fromRGB(184, 215, 215)
	private val PARTICLE_COLORS = listOf(LIGHT_BROWN, LIGHT_BLUE)

	override val displayProperties = DisplayProperties(LIGHT_BROWN, "transparent_liquid")
	override val categories: Array<FluidCategory> = arrayOf()

	override fun displayInPipe(world: World, origin: Vector, destination: Vector) {
		val trailOptions = Trail(
			destination.toLocation(world),
			PARTICLE_COLORS.random(),
			20
		)

		world.spawnParticle(Particle.TRAIL, origin.toLocation(world), 1, 0.0, 0.0, 0.0, 0.0, trailOptions, false)
	}

	override fun playLeakEffects(world: World, leakingNode: FluidNode, leakingDirection: BlockFace) {
		val faceCenter = leakingNode.getGlobalCenter()
			.add(leakingDirection.direction.multiply(0.5))
			.toLocation(world)

		when (leakingDirection.axis) {
			Axis.Y -> faceCenter.add(Vector(
				Random.nextDouble(-PIPE_INTERIOR_PADDING, PIPE_INTERIOR_PADDING),
				0.0,
				Random.nextDouble(-PIPE_INTERIOR_PADDING, PIPE_INTERIOR_PADDING)
			))
			else -> faceCenter.add(Vector(
				(Random.nextDouble(-PIPE_INTERIOR_PADDING, PIPE_INTERIOR_PADDING) * leakingDirection.modZ) +
					(leakingDirection.modX * 0.05),
				Random.nextDouble(-PIPE_INTERIOR_PADDING, PIPE_INTERIOR_PADDING / 2),
				(Random.nextDouble(-PIPE_INTERIOR_PADDING, PIPE_INTERIOR_PADDING) * leakingDirection.modX) +
					(leakingDirection.modZ * 0.05)
			))
		}

		val dripOptions = Trail(
			faceCenter.clone().add(0.0, -1.0, 0.0),
			PARTICLE_COLORS.random(),
			20
		)

		world.spawnParticle(Particle.TRAIL, faceCenter, 1, 0.0, 0.0, 0.0, 0.0, dripOptions, false)
	}

	override fun getDisplayName(stack: FluidStack): Component = text("Fertilizer", HE_LIGHT_ORANGE)

	override fun getIsobaricHeatCapacity(stack: FluidStack): Double = 4.181

	override fun getMolarMass(): Double = 18.01528

	override fun getDensity(stack: FluidStack, location: Location?): Double = 1.0
}
