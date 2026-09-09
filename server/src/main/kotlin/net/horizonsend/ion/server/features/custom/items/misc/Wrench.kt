package net.horizonsend.ion.server.features.custom.items.misc

import io.papermc.paper.adventure.PaperAdventure
import net.horizonsend.ion.common.extensions.information
import net.horizonsend.ion.common.utils.miscellaneous.roundToHundredth
import net.horizonsend.ion.common.utils.text.colors.HEColorScheme.Companion.HE_DARK_GRAY
import net.horizonsend.ion.common.utils.text.colors.HEColorScheme.Companion.HE_MEDIUM_GRAY
import net.horizonsend.ion.common.utils.text.ofChildren
import net.horizonsend.ion.common.utils.text.template
import net.horizonsend.ion.server.command.misc.MultiblockCommand
import net.horizonsend.ion.server.command.qol.FixExtractorsCommand
import net.horizonsend.ion.server.core.registration.keys.CustomItemKeys
import net.horizonsend.ion.server.core.registration.registries.CustomBlockRegistry.Companion.customBlock
import net.horizonsend.ion.server.core.registration.registries.CustomItemRegistry.Companion.customItem
import net.horizonsend.ion.server.features.client.display.ClientDisplayEntities
import net.horizonsend.ion.server.features.client.display.ClientDisplayEntities.rotateToFaceVector2d
import net.horizonsend.ion.server.features.client.display.ClientDisplayEntities.sendText
import net.horizonsend.ion.server.features.client.display.HudIcons.FLUID_INFO_ID
import net.horizonsend.ion.server.features.client.display.teleportDuration
import net.horizonsend.ion.server.features.custom.blocks.CustomBlock
import net.horizonsend.ion.server.features.custom.blocks.CustomBlockListeners
import net.horizonsend.ion.server.features.custom.blocks.misc.WrenchRemovable
import net.horizonsend.ion.server.features.custom.items.CustomItem
import net.horizonsend.ion.server.features.custom.items.component.CustomComponentTypes
import net.horizonsend.ion.server.features.custom.items.component.CustomItemComponentManager
import net.horizonsend.ion.server.features.custom.items.component.Listener.Companion.leftClickListener
import net.horizonsend.ion.server.features.custom.items.component.Listener.Companion.rightClickListener
import net.horizonsend.ion.server.features.custom.items.component.TickReceiverModule
import net.horizonsend.ion.server.features.custom.items.type.tool.HandheldTank
import net.horizonsend.ion.server.features.custom.items.util.ItemFactory
import net.horizonsend.ion.server.features.multiblock.MultiblockAccess
import net.horizonsend.ion.server.features.multiblock.PrePackaged
import net.horizonsend.ion.server.features.transport.fluids.FluidUtils
import net.horizonsend.ion.server.features.transport.manager.graph.fluid.FluidGraphEdge
import net.horizonsend.ion.server.features.transport.manager.graph.fluid.FluidNetwork
import net.horizonsend.ion.server.features.world.IonWorld.Companion.ion
import net.horizonsend.ion.server.miscellaneous.utils.Tasks
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.BlockKey
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.getRelative
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.toBlockKey
import net.horizonsend.ion.server.miscellaneous.utils.coordinates.toVec3i
import net.horizonsend.ion.server.miscellaneous.utils.debugAudience
import net.horizonsend.ion.server.miscellaneous.utils.isWallSign
import net.horizonsend.ion.server.miscellaneous.utils.minecraft
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.newline
import net.kyori.adventure.text.Component.text
import net.minecraft.world.entity.Display
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.util.RayTraceResult
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object Wrench : CustomItem(
	CustomItemKeys.WRENCH,
	text("Wrench"),
	ItemFactory.builder(ItemFactory.unStackableCustomItem)
		.setCustomModel("tool/wrench")
		.build()
) {
	const val WRENCH_DISPLAY_TICK_INTERVAL = 2
	private const val HUD_EXPIRY_TICKS = 10L
	private val pendingTips = ConcurrentHashMap.newKeySet<UUID>()
	private val fluidHuds = mutableMapOf<UUID, FluidHud>()

	private class FluidHud(val entity: Display.TextDisplay, val worldId: UUID) {
		var refreshedAt = Bukkit.getCurrentTick()
	}

	override val customComponents: CustomItemComponentManager = CustomItemComponentManager(serializationManager).apply {
		addComponent(CustomComponentTypes.LISTENER_PLAYER_INTERACT, rightClickListener(this@Wrench) { event, _, _ ->
			handleRightClick(event.player, event)
		})

		addComponent(CustomComponentTypes.LISTENER_PLAYER_INTERACT, leftClickListener(this@Wrench) { event, _, _ ->
			checkStructure(event.player, event)
		})

		addComponent(CustomComponentTypes.TICK_RECEIVER, TickReceiverModule(WRENCH_DISPLAY_TICK_INTERVAL) { entity, _, _, _ ->
			requestFluidTips(entity as? Player ?: return@TickReceiverModule)
		})
	}

	private fun checkStructure(player: Player, event: PlayerInteractEvent) {
		val hitBlock = event.clickedBlock
		if (hitBlock?.type?.isWallSign != true) return

		val sign = hitBlock.state as? Sign ?: return
		val multiblock = MultiblockAccess.getFast(sign) ?: return

		if (multiblock.signMatchesStructure(sign, loadChunks = false, particles =  false)) {
			player.information("Multiblock structure is correct.")
			return
		}

		MultiblockCommand.onCheck(player, multiblock, sign.x, sign.y, sign.z)
		FixExtractorsCommand.tryFixMultiblock(player, sign)
	}

	private fun handleRightClick(player: Player, event: PlayerInteractEvent?) {
		val clickedBlock = event?.clickedBlock ?: return
		val state = clickedBlock.state
		val customBlock = clickedBlock.blockData.customBlock

		if (player.isSneaking && state is Sign) return tryPickUpMultiblock(player, state)
		if (player.isSneaking && customBlock is WrenchRemovable) return tryPickUpBlock(player, clickedBlock, customBlock)
	}

	private fun tryPickUpMultiblock(player: Player, sign: Sign) {
		PrePackaged.pickUpStructure(player, sign)
	}

	private fun tryPickUpBlock(player: Player, block: Block, customBlock: WrenchRemovable) {
		val event = BlockBreakEvent(block, player)
		CustomBlockListeners.noDropEvents.add(event)

		if (!event.callEvent()) return

		val item = (customBlock as CustomBlock).customItem.constructItemStack()
		customBlock.decorateItem(item, block)

		block.type = Material.AIR
		block.world.dropItem(
			block.location.toCenterLocation(),
			item
		)
	}

	/** Coalesce asynchronous item ticks; all targeting and HUD mutations run on the server thread. */
	fun requestFluidTips(player: Player) {
		if (!pendingTips.add(player.uniqueId)) return
		Tasks.sync {
			try {
				if (!player.isOnline || player.isDead) return@sync removeEntity(player)
				when (heldFluidTool(player)) {
					CustomItemKeys.WRENCH -> giveFluidTips(player)
					CustomItemKeys.HANDHELD_TANK -> HandheldTank.giveTips(player)
					else -> removeEntity(player)
				}
			} finally {
				pendingTips.remove(player.uniqueId)
			}
		}
	}

	private fun heldFluidTool(player: Player) = listOf(
		player.inventory.itemInMainHand.customItem?.key,
		player.inventory.itemInOffHand.customItem?.key
	).firstOrNull { it == CustomItemKeys.WRENCH || it == CustomItemKeys.HANDHELD_TANK }

	private fun giveFluidTips(player: Player) {
		val hitResult: RayTraceResult? = player.rayTraceBlocks(7.0, FluidCollisionMode.NEVER)
		val targeted = hitResult?.hitBlock ?: return removeEntity(player)
		val targetedLocation = hitResult.hitPosition

		val key = toBlockKey(targeted.x, targeted.y, targeted.z)

		val (network, localKey) = getFluidNetwork(player, key) ?: return removeEntity(player)

		val fluid = network.networkContents

		val text = ofChildren(
			FluidUtils.formatFluidInfo(fluid),
			Component.newline(),
			text(" • ", HE_MEDIUM_GRAY),
			text("Flow Rate"),
			text(": ", HE_DARK_GRAY),
			text(network.getFlow(localKey).roundToHundredth()), text(" L/s", HE_MEDIUM_GRAY)
		)

		@Suppress("OverrideOnly")
		if (debugAudience.audiences().contains(player)) {
			val holder = network.manager.transportManager

			network.getNode(localKey)?.let {
				@Suppress("UnstableApiUsage")
				for (edge in network.getGraph().outEdges(it).filterIsInstance<FluidGraphEdge>()) {
					val adjacent = getRelative(localKey, edge.direction)
					val globalAdjacent = holder.getGlobalCoordinate(toVec3i(adjacent))
					val displayLocation = globalAdjacent.toCenterVector().toLocation(player.world).add(0.0, 0.75, 0.0)
					val globalDirection = holder.getGlobalDirection(edge.direction)

					val text = template(ofChildren(
						text("{0}"), newline(),
						text("{1} L/s")
					), useQuotesAroundObjects = false, globalDirection, edge.netFlow)

					player.sendText(displayLocation, text, WRENCH_DISPLAY_TICK_INTERVAL.toLong() + 1)
				}
			}
		}

		val projectedLocation = targetedLocation.add(player.location.direction.clone().multiply(-1)).toLocation(player.world).add(0.0, 0.3, 0.0)
		val scale = maxOf(player.eyeLocation.distance(projectedLocation).roundToInt() * 0.2f, 0.5f)

		if (ClientDisplayEntities[player.uniqueId]?.get(FLUID_INFO_ID) == null)
			createHudEntity(player, projectedLocation, text, scale)
		else updateHudEntity(player, projectedLocation, text, scale)
	}

	private fun getFluidNetwork(player: Player, globalPosition: BlockKey): Pair<FluidNetwork, BlockKey>? {
		val manager = player.world.ion.transportManager.getFluidManager(globalPosition)
		val localPosition = toBlockKey(manager.transportManager.getLocalCoordinate(toVec3i(globalPosition)))
		val network = manager.getByLocation(localPosition) as? FluidNetwork ?: return null

		return network to localPosition
	}

	fun removeEntity(player: Player) {
		val hud = fluidHuds.remove(player.uniqueId)
		val entity = ClientDisplayEntities[player.uniqueId]?.remove(FLUID_INFO_ID)
		if (!player.isOnline) return
		if (entity != null) ClientDisplayEntities.deleteDisplayEntityPacket(player.minecraft, entity)
		if (hud != null && hud.entity !== entity) ClientDisplayEntities.deleteDisplayEntityPacket(player.minecraft, hud.entity)
	}

	/** Keep a reference to this exact entity so even an overwritten map entry can be cleaned up. */
	private fun scheduleHudExpiry(player: Player, hud: FluidHud) {
		val remainingTicks = maxOf(1L, HUD_EXPIRY_TICKS - (Bukkit.getCurrentTick() - hud.refreshedAt))
		Tasks.syncDelay(remainingTicks) {
			val current = fluidHuds[player.uniqueId]
			val expired = current !== hud || ClientDisplayEntities[player.uniqueId]?.get(FLUID_INFO_ID) !== hud.entity ||
				!player.isOnline || player.isDead || player.world.uid != hud.worldId || heldFluidTool(player) == null ||
				Bukkit.getCurrentTick() - hud.refreshedAt >= HUD_EXPIRY_TICKS

			if (expired) {
				if (current === hud) removeEntity(player)
				else if (player.isOnline) ClientDisplayEntities.deleteDisplayEntityPacket(player.minecraft, hud.entity)
				return@syncDelay
			}

			scheduleHudExpiry(player, hud)
		}
	}

	fun createHudEntity(player: Player, location: Location, info: Component, scale: Float) {
		removeEntity(player)
		if (!player.isOnline) return
		val displays = ClientDisplayEntities[player.uniqueId] ?: return
		val entity = ClientDisplayEntities.createTextEntity(
			location,
			info,
			WRENCH_DISPLAY_TICK_INTERVAL.toLong() + 1,
			scale = scale,
			backgroundColor = Color.fromARGB(255, 0, 0, 0),
			seeThrough = true,
		)

		val hud = FluidHud(entity, player.world.uid)
		fluidHuds[player.uniqueId] = hud
		displays[FLUID_INFO_ID] = entity
		scheduleHudExpiry(player, hud)
		ClientDisplayEntities.sendEntityPacket(player, entity)
	}

	fun updateHudEntity(player: Player, location: Location, info: Component, scale: Float) {
		val hud = fluidHuds[player.uniqueId]
		if (hud == null || hud.worldId != player.world.uid || ClientDisplayEntities[player.uniqueId]?.get(FLUID_INFO_ID) !== hud.entity) {
			return createHudEntity(player, location, info, scale)
		}
		val nmsEntity = hud.entity

		nmsEntity.text = PaperAdventure.asVanilla(info)

		val transformation = com.mojang.math.Transformation(
			Vector3f(),
			rotateToFaceVector2d(Vector3f()),
			Vector3f(scale),
			Quaternionf()
		)

		nmsEntity.transformationInterpolationDuration = WRENCH_DISPLAY_TICK_INTERVAL
		nmsEntity.teleportDuration = WRENCH_DISPLAY_TICK_INTERVAL

		ClientDisplayEntities.moveDisplayEntityPacket(player.minecraft, nmsEntity, location.x, location.y, location.z)
		ClientDisplayEntities.transformDisplayEntityPacket(player, nmsEntity, transformation)
		hud.refreshedAt = Bukkit.getCurrentTick()
	}
}
