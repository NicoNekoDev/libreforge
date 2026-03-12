package com.willfp.libreforge.effects.templates

import com.nexomc.nexo.utils.applyIf
import com.willfp.eco.core.config.interfaces.Config
import com.willfp.eco.core.events.MultiBlockBreakEvent
import com.willfp.eco.core.events.MultiBlockDropItemEvent
import com.willfp.eco.util.direction
import com.willfp.eco.util.runExempted
import com.willfp.libreforge.applyDamage
import com.willfp.libreforge.effects.Effect
import com.willfp.libreforge.plugin
import com.willfp.libreforge.triggers.TriggerData
import com.willfp.libreforge.triggers.TriggerParameter
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

abstract class MineBlockEffect<T : Any>(id: String) : Effect<T>(id) {
    private val ignoreKey = "blockbreakevent-ignore"

    override val parameters = setOf(
        TriggerParameter.PLAYER
    )

    override fun shouldTrigger(config: Config, data: TriggerData, compileData: T): Boolean {
        val block = data.block ?: data.location?.block ?: return false
        return !block.hasMetadata(ignoreKey)
    }

    protected fun Player.breakBlocksSafely(blocks: Collection<Block>) {
        val item = this.inventory.itemInMainHand
        val useMultiBlocksEvents = plugin.configYml.getBool("effects.use-multiblock-events")

        if (plugin.configYml.getBool("effects.use-setblock-break")) {
            blocks.forEach { it.type = Material.AIR }
        } else {
            this.runExempted {
                val blockList = mutableMapOf<Block, MultiBlockDropItemEvent.BlockStateAndItems>()

                for (block in blocks) {
                    if (block.world != this.world) {
                        continue
                    }

                    var items = block.getDrops(item).map {
                        block.world.createEntity(
                            block.location.toCenterLocation(),
                            Item::class.java
                        ).apply { itemStack = it }
                    }

                    if (!useMultiBlocksEvents) {
                        val blockBreak = BlockBreakEvent(block, this)
                        Bukkit.getPluginManager().callEvent(blockBreak)
                        if (blockBreak.isCancelled)
                            continue

                        if (blockBreak.isDropItems) {
                            val blockDrop = BlockDropItemEvent(block, block.state, this, items)

                            Bukkit.getPluginManager().callEvent(blockDrop)
                            if (blockDrop.isCancelled)
                                continue

                            items = blockDrop.items
                        }
                    }

                    blockList[block] = MultiBlockDropItemEvent.BlockStateAndItems(block.state, items)
                }

                val multiBlockBreak = MultiBlockBreakEvent(this, blockList.keys)

                if (useMultiBlocksEvents) {
                    Bukkit.getPluginManager().callEvent(multiBlockBreak)
                    if (multiBlockBreak.isCancelled)
                        return@runExempted
                }

                // blockList is probably mutated by the event above, so we put it after
                val multiBlockDrop = MultiBlockDropItemEvent(this, blockList)

                if (useMultiBlocksEvents) {
                    Bukkit.getPluginManager().callEvent(multiBlockDrop)
                    if (multiBlockDrop.isCancelled)
                        return@runExempted
                }

                val damageToApply = blockList.size

                val iter = blockList.iterator()
                while (iter.hasNext()) {
                    val (block, entry) = iter.next()
                    block.setMetadata(ignoreKey, plugin.createMetadataValue(true))
                    block.type = Material.AIR
                    if (multiBlockBreak.isDropItems(block))
                        entry.items.forEach { it.spawnAt(block.location.toCenterLocation()) }
                    block.removeMetadata(ignoreKey, plugin)
                    iter.remove()
                }

                item.applyDamage(damageToApply, this) {
                    this.inventory.setItemInMainHand(item.withType(Material.AIR))
                }
            }
        }
    }

    protected fun Player.breakBlocksSafelyWithAnimation(
        radius: Int,
        block: Block,
        blocks: MutableList<Block>,
        config: Config
    ) {
        val type = config.getString("type")
        val from = config.getString("from")

        val checkTool = config.getBoolOrNull("check-tool") ?: true
        val delay = max(1, config.getIntOrNull("delay") ?: 1)
        val blocksPerTick = config.getIntOrNull("blocks-per-tick") ?: 1
        val reverse = config.getBoolOrNull("reversed") ?: false

        val tool = this.inventory.itemInMainHand

        if (type.equals("layers", ignoreCase = true)) {
            if (from.equals("center", ignoreCase = true)) {
                val sortedBlocks = blocks.sortedWith(compareBy { b ->
                    val dx = b.x - block.x
                    val dy = b.y - block.y
                    val dz = b.z - block.z
                    sqrt((dx * dx + dy * dy + dz * dz).toDouble())
                }).applyIf(reverse) { reversed() }

                val blocksByDistance = mutableMapOf<Int, MutableList<Block>>()

                for (b in sortedBlocks) {
                    val distance = maxOf(
                        abs(b.x - block.x),
                        abs(b.y - block.y),
                        abs(b.z - block.z)
                    )
                    blocksByDistance.getOrPut(distance) { mutableListOf() }.add(b)
                }

                blocks.clear()

                val sortedDistances = blocksByDistance.keys.sorted()
                var currentDistanceIndex = 0
                var currentBlockInDistance = 0

                plugin.runnableFactory.create { task ->
                    if (currentDistanceIndex >= sortedDistances.size) {
                        task.cancelTask()
                        return@create
                    }

                    if (checkTool) {
                        if (tool != this.inventory.itemInMainHand) {
                            task.cancelTask()
                            return@create
                        }
                    }

                    val currentDistance = sortedDistances[currentDistanceIndex]
                    val layerBlocks = blocksByDistance[currentDistance] ?: mutableListOf()

                    val endIndex = minOf(currentBlockInDistance + blocksPerTick, layerBlocks.size)
                    val blocksToBreak = layerBlocks.subList(currentBlockInDistance, endIndex)

                    if (blocksToBreak.isNotEmpty())
                        this.breakBlocksSafely(blocksToBreak)

                    currentBlockInDistance = endIndex

                    if (currentBlockInDistance >= layerBlocks.size) {
                        blocksByDistance.remove(currentDistance)
                        currentDistanceIndex++
                        currentBlockInDistance = 0
                    }
                }.runTaskTimer(block.location, 1, delay.toLong())
            } else if (from.equals("outside", ignoreCase = true)) {
                val sortedBlocks = blocks.sortedWith(compareBy { b ->
                    val dx = b.x - block.x
                    val dy = b.y - block.y
                    val dz = b.z - block.z
                    sqrt((dx * dx + dy * dy + dz * dz).toDouble())
                }).applyIf(!reverse) { reversed() }

                val blocksByDistance = mutableMapOf<Int, MutableList<Block>>()

                for (b in sortedBlocks) {
                    val distance = maxOf(
                        abs(b.x - block.x),
                        abs(b.y - block.y),
                        abs(b.z - block.z)
                    )
                    blocksByDistance.getOrPut(distance) { mutableListOf() }.add(b)
                }

                blocks.clear()

                val sortedDistances = blocksByDistance.keys.sortedDescending()
                var currentDistanceIndex = 0
                var currentBlockInDistance = 0

                plugin.runnableFactory.create { task ->
                    if (currentDistanceIndex >= sortedDistances.size) {
                        task.cancelTask()
                        return@create
                    }

                    if (checkTool) {
                        if (tool != this.inventory.itemInMainHand) {
                            task.cancelTask()
                            return@create
                        }
                    }

                    val currentDistance = sortedDistances[currentDistanceIndex]
                    val layerBlocks = blocksByDistance[currentDistance] ?: emptyList()

                    val endIndex = minOf(currentBlockInDistance + blocksPerTick, layerBlocks.size)
                    val blocksToBreak = layerBlocks.subList(currentBlockInDistance, endIndex)

                    if (blocksToBreak.isNotEmpty())
                        this.breakBlocksSafely(blocksToBreak)

                    currentBlockInDistance = endIndex

                    if (currentBlockInDistance >= layerBlocks.size) {
                        blocksByDistance.remove(currentDistance)
                        currentDistanceIndex++
                        currentBlockInDistance = 0
                    }
                }.runTaskTimer(block.location, 1, delay.toLong())
            } else if (
                from.equals("south", ignoreCase = true) ||
                from.equals("north", ignoreCase = true) ||
                from.equals("east", ignoreCase = true) ||
                from.equals("west", ignoreCase = true) ||
                from.equals("above", ignoreCase = true) ||
                from.equals("below", ignoreCase = true)
            ) {
                val blocksByHeight = mutableMapOf<Int, MutableList<Block>>()

                for (b in blocks) {
                    if (
                        from.equals("south", ignoreCase = true) ||
                        from.equals("north", ignoreCase = true)
                    )
                        blocksByHeight.getOrPut(b.z) { mutableListOf() }.add(b)
                    else if (
                        from.equals("east", ignoreCase = true) ||
                        from.equals("west", ignoreCase = true)
                    )
                        blocksByHeight.getOrPut(b.x) { mutableListOf() }.add(b)
                    else if (
                        from.equals("above", ignoreCase = true) ||
                        from.equals("below", ignoreCase = true)
                    )
                        blocksByHeight.getOrPut(b.y) { mutableListOf() }.add(b)
                }

                blocks.clear()

                val sortedHeights = blocksByHeight.keys.toList()
                    .applyIf(
                        from.equals("south", ignoreCase = true) ||
                                from.equals("east", ignoreCase = true) ||
                                from.equals("above", ignoreCase = true)
                    ) { sortedDescending() }
                    .applyIf(
                        from.equals("north", ignoreCase = true) ||
                                from.equals("west", ignoreCase = true) ||
                                from.equals("below", ignoreCase = true)
                    ) { sorted() }

                var currentHeightIndex = 0
                var currentBlockInHeight = 0

                plugin.runnableFactory.create { task ->
                    if (currentHeightIndex >= sortedHeights.size) {
                        task.cancelTask()
                        return@create
                    }

                    if (checkTool) {
                        if (tool != this.inventory.itemInMainHand) {
                            task.cancelTask()
                            return@create
                        }
                    }

                    val currentHeight = sortedHeights[currentHeightIndex]
                    val layerBlocks = blocksByHeight[currentHeight] ?: emptyList()

                    val endIndex = minOf(currentBlockInHeight + blocksPerTick, layerBlocks.size)
                    val blocksToBreak = layerBlocks.subList(currentBlockInHeight, endIndex)

                    if (blocksToBreak.isNotEmpty())
                        this.breakBlocksSafely(blocksToBreak)

                    currentBlockInHeight = endIndex

                    if (currentBlockInHeight >= layerBlocks.size) {
                        blocksByHeight.remove(currentHeight)
                        currentHeightIndex++
                        currentBlockInHeight = 0
                    }
                }.runTaskTimer(block.location, 1, delay.toLong())
            } else if (from.equals("direction", ignoreCase = true)) {
                val direction = this.direction.oppositeFace

                val blocksByHeight = mutableMapOf<Int, MutableList<Block>>()

                for (b in blocks) {
                    when (direction) {
                        BlockFace.SOUTH, BlockFace.NORTH
                            -> blocksByHeight.getOrPut(b.z) { mutableListOf() }
                            .add(b)

                        BlockFace.EAST, BlockFace.WEST
                            -> blocksByHeight.getOrPut(b.x) { mutableListOf() }
                            .add(b)

                        BlockFace.UP, BlockFace.DOWN
                            -> blocksByHeight.getOrPut(b.y) { mutableListOf() }.add(b)

                        else -> return
                    }
                }

                blocks.clear()

                val sortedHeights = blocksByHeight.keys.toList()
                    .applyIf(
                        direction == BlockFace.SOUTH ||
                                direction == BlockFace.EAST ||
                                direction == BlockFace.UP
                    ) { sortedDescending() }
                    .applyIf(
                        direction == BlockFace.NORTH ||
                                direction == BlockFace.WEST ||
                                direction == BlockFace.DOWN
                    ) { sorted() }

                var currentHeightIndex = 0
                var currentBlockInHeight = 0

                plugin.runnableFactory.create { task ->
                    if (currentHeightIndex >= sortedHeights.size) {
                        task.cancelTask()
                        return@create
                    }

                    if (checkTool) {
                        if (tool != this.inventory.itemInMainHand) {
                            task.cancelTask()
                            return@create
                        }
                    }

                    val currentHeight = sortedHeights[currentHeightIndex]
                    val layerBlocks = blocksByHeight[currentHeight] ?: emptyList()

                    val endIndex = minOf(currentBlockInHeight + blocksPerTick, layerBlocks.size)
                    val blocksToBreak = layerBlocks.subList(currentBlockInHeight, endIndex)

                    if (blocksToBreak.isNotEmpty())
                        this.breakBlocksSafely(blocksToBreak)

                    currentBlockInHeight = endIndex

                    if (currentBlockInHeight >= layerBlocks.size) {
                        blocksByHeight.remove(currentHeight)
                        currentHeightIndex++
                        currentBlockInHeight = 0
                    }
                }.runTaskTimer(block.location, 1, delay.toLong())
            }
        } else if (type.equals("spiral", ignoreCase = true)) {
            val spiral = generateSpiral(radius)

            if (from.equals("center", ignoreCase = true)) {
                val sortedBlocks = blocks.sortedWith(compareBy { b ->
                    val dx = b.x - block.x
                    val dy = b.y - block.y
                    val dz = b.z - block.z
                    spiral[Pair(dx, dz)]!! *
                            spiral[Pair(dx, dy)]!! *
                            spiral[Pair(dz, dy)]!!
                }).applyIf(reverse) { reversed() }

                val blocksByDistance = mutableMapOf<Int, MutableList<Block>>()

                for (b in sortedBlocks) {
                    val distance = maxOf(
                        abs(b.x - block.x),
                        abs(b.y - block.y),
                        abs(b.z - block.z)
                    )
                    blocksByDistance.getOrPut(distance) { mutableListOf() }.add(b)
                }

                blocks.clear()

                val sortedDistances = blocksByDistance.keys.sorted()
                var currentDistanceIndex = 0
                var currentBlockInDistance = 0

                plugin.runnableFactory.create { task ->
                    if (currentDistanceIndex >= sortedDistances.size) {
                        task.cancelTask()
                        return@create
                    }

                    if (checkTool) {
                        if (tool != this.inventory.itemInMainHand) {
                            task.cancelTask()
                            return@create
                        }
                    }

                    val currentDistance = sortedDistances[currentDistanceIndex]
                    val layerBlocks = blocksByDistance[currentDistance] ?: emptyList()

                    val endIndex = minOf(currentBlockInDistance + blocksPerTick, layerBlocks.size)
                    val blocksToBreak = layerBlocks.subList(currentBlockInDistance, endIndex)

                    if (blocksToBreak.isNotEmpty())
                        this.breakBlocksSafely(blocksToBreak)

                    currentBlockInDistance = endIndex

                    if (currentBlockInDistance >= layerBlocks.size) {
                        blocksByDistance.remove(currentDistance)
                        currentDistanceIndex++
                        currentBlockInDistance = 0
                    }
                }.runTaskTimer(block.location, 1, delay.toLong())
            } else if (from.equals("outside", ignoreCase = true)) {
                val sortedBlocks = blocks.sortedWith(compareBy { b ->
                    val dx = b.x - block.x
                    val dy = b.y - block.y
                    val dz = b.z - block.z
                    spiral[Pair(dx, dz)]!! *
                            spiral[Pair(dx, dy)]!! *
                            spiral[Pair(dz, dy)]!!
                }).applyIf(!reverse) { reversed() }

                val blocksByDistance = mutableMapOf<Int, MutableList<Block>>()

                for (b in sortedBlocks) {
                    // Primary: Chebyshev distance from center
                    val distance = maxOf(
                        abs(b.x - block.x),
                        abs(b.y - block.y),
                        abs(b.z - block.z)
                    )
                    blocksByDistance.getOrPut(distance) { mutableListOf() }.add(b)
                }

                blocks.clear()

                val sortedDistances = blocksByDistance.keys.sortedDescending()
                var currentDistanceIndex = 0
                var currentBlockInDistance = 0

                plugin.runnableFactory.create { task ->
                    if (currentDistanceIndex >= sortedDistances.size) {
                        task.cancelTask()
                        return@create
                    }

                    if (checkTool) {
                        if (tool != this.inventory.itemInMainHand) {
                            task.cancelTask()
                            return@create
                        }
                    }

                    val currentDistance = sortedDistances[currentDistanceIndex]
                    val layerBlocks = blocksByDistance[currentDistance] ?: emptyList()

                    val endIndex = minOf(currentBlockInDistance + blocksPerTick, layerBlocks.size)
                    val blocksToBreak = layerBlocks.subList(currentBlockInDistance, endIndex)

                    if (blocksToBreak.isNotEmpty())
                        this.breakBlocksSafely(blocksToBreak)

                    currentBlockInDistance = endIndex

                    if (currentBlockInDistance >= layerBlocks.size) {
                        blocksByDistance.remove(currentDistance)
                        currentDistanceIndex++
                        currentBlockInDistance = 0
                    }
                }.runTaskTimer(block.location, 1, delay.toLong())
            } else if (
                from.equals("south", ignoreCase = true) ||
                from.equals("north", ignoreCase = true) ||
                from.equals("east", ignoreCase = true) ||
                from.equals("west", ignoreCase = true) ||
                from.equals("above", ignoreCase = true) ||
                from.equals("below", ignoreCase = true)
            ) {
                val sortedBlocks = blocks.toList()
                    .applyIf(
                        from.equals("south", ignoreCase = true) ||
                                from.equals("north", ignoreCase = true)
                    ) {
                        sortedWith(compareBy { b ->
                            val dx = b.x - block.x
                            val dy = b.y - block.y
                            spiral[Pair(dx, dy)]!!
                        })
                    }
                    .applyIf(
                        from.equals("east", ignoreCase = true) ||
                                from.equals("west", ignoreCase = true)
                    ) {
                        sortedWith(compareBy { b ->
                            val dz = b.z - block.z
                            val dy = b.y - block.y
                            spiral[Pair(dz, dy)]!!
                        })
                    }
                    .applyIf(
                        from.equals("above", ignoreCase = true) ||
                                from.equals("below", ignoreCase = true)
                    ) {
                        sortedWith(compareBy { b ->
                            val dx = b.x - block.x
                            val dz = b.z - block.z
                            spiral[Pair(dx, dz)]!!
                        })
                    }
                    .applyIf(reverse) { reversed() }

                val blocksByHeight = mutableMapOf<Int, MutableList<Block>>()

                for (b in sortedBlocks) {
                    if (
                        from.equals("south", ignoreCase = true) ||
                        from.equals("north", ignoreCase = true)
                    )
                        blocksByHeight.getOrPut(b.z) { mutableListOf() }.add(b)
                    else if (
                        from.equals("east", ignoreCase = true) ||
                        from.equals("west", ignoreCase = true)
                    )
                        blocksByHeight.getOrPut(b.x) { mutableListOf() }.add(b)
                    else if (
                        from.equals("above", ignoreCase = true) ||
                        from.equals("below", ignoreCase = true)
                    )
                        blocksByHeight.getOrPut(b.y) { mutableListOf() }.add(b)
                }

                blocks.clear()

                val sortedHeights = blocksByHeight.keys.toList()
                    .applyIf(
                        from.equals("south", ignoreCase = true) ||
                                from.equals("east", ignoreCase = true) ||
                                from.equals("above", ignoreCase = true)
                    ) { sortedDescending() }
                    .applyIf(
                        from.equals("north", ignoreCase = true) ||
                                from.equals("west", ignoreCase = true) ||
                                from.equals("below", ignoreCase = true)
                    ) { sorted() }

                var currentHeightIndex = 0
                var currentBlockInHeight = 0

                plugin.runnableFactory.create { task ->
                    if (currentHeightIndex >= sortedHeights.size) {
                        task.cancelTask()
                        return@create
                    }

                    if (checkTool) {
                        if (tool != this.inventory.itemInMainHand) {
                            task.cancelTask()
                            return@create
                        }
                    }

                    val currentHeight = sortedHeights[currentHeightIndex]
                    val layerBlocks = blocksByHeight[currentHeight] ?: emptyList()

                    val endIndex = minOf(currentBlockInHeight + blocksPerTick, layerBlocks.size)
                    val blocksToBreak = layerBlocks.subList(currentBlockInHeight, endIndex)

                    if (blocksToBreak.isNotEmpty())
                        this.breakBlocksSafely(blocksToBreak)

                    currentBlockInHeight = endIndex

                    if (currentBlockInHeight >= layerBlocks.size) {
                        blocksByHeight.remove(currentHeight)
                        currentHeightIndex++
                        currentBlockInHeight = 0
                    }
                }.runTaskTimer(block.location, 1, delay.toLong())
            } else if (from.equals("direction", ignoreCase = true)) {
                val direction = this.direction.oppositeFace

                val sortedBlocks = blocks.toList()
                    .applyIf(
                        direction == BlockFace.SOUTH ||
                                direction == BlockFace.NORTH
                    ) {
                        sortedWith(compareBy { b ->
                            val dx = b.x - block.x
                            val dy = b.y - block.y
                            spiral[Pair(dx, dy)]!!
                        })
                    }
                    .applyIf(
                        direction == BlockFace.EAST ||
                                direction == BlockFace.WEST
                    ) {
                        sortedWith(compareBy { b ->
                            val dz = b.z - block.z
                            val dy = b.y - block.y
                            spiral[Pair(dz, dy)]!!
                        })
                    }
                    .applyIf(
                        direction == BlockFace.UP ||
                                direction == BlockFace.DOWN
                    ) {
                        sortedWith(compareBy { b ->
                            val dx = b.x - block.x
                            val dz = b.z - block.z
                            spiral[Pair(dx, dz)]!!
                        })
                    }
                    .applyIf(reverse) { reversed() }

                val blocksByHeight = mutableMapOf<Int, MutableList<Block>>()

                for (b in sortedBlocks) {
                    when (direction) {
                        BlockFace.SOUTH, BlockFace.NORTH
                            -> blocksByHeight.getOrPut(b.z) { mutableListOf() }
                            .add(b)

                        BlockFace.EAST, BlockFace.WEST
                            -> blocksByHeight.getOrPut(b.x) { mutableListOf() }
                            .add(b)

                        BlockFace.UP, BlockFace.DOWN
                            -> blocksByHeight.getOrPut(b.y) { mutableListOf() }.add(b)

                        else -> return
                    }
                }

                blocks.clear()

                val sortedHeights = blocksByHeight.keys.toList()
                    .applyIf(
                        direction == BlockFace.SOUTH ||
                                direction == BlockFace.EAST ||
                                direction == BlockFace.UP
                    ) { sortedDescending() }
                    .applyIf(
                        direction == BlockFace.NORTH ||
                                direction == BlockFace.WEST ||
                                direction == BlockFace.DOWN
                    ) { sorted() }

                var currentHeightIndex = 0
                var currentBlockInHeight = 0

                plugin.runnableFactory.create { task ->
                    if (currentHeightIndex >= sortedHeights.size) {
                        task.cancelTask()
                        return@create
                    }

                    if (checkTool) {
                        if (tool != this.inventory.itemInMainHand) {
                            task.cancelTask()
                            return@create
                        }
                    }

                    val currentHeight = sortedHeights[currentHeightIndex]
                    val layerBlocks = blocksByHeight[currentHeight] ?: emptyList()

                    val endIndex = minOf(currentBlockInHeight + blocksPerTick, layerBlocks.size)
                    val blocksToBreak = layerBlocks.subList(currentBlockInHeight, endIndex)

                    if (blocksToBreak.isNotEmpty())
                        this.breakBlocksSafely(blocksToBreak)

                    currentBlockInHeight = endIndex

                    if (currentBlockInHeight >= layerBlocks.size) {
                        blocksByHeight.remove(currentHeight)
                        currentHeightIndex++
                        currentBlockInHeight = 0
                    }
                }.runTaskTimer(block.location, 1, delay.toLong())
            }
        } else if (type.equals("decay", ignoreCase = true)) {
            val shuffledBlocks = blocks.shuffled()

            if (from.equals("center", ignoreCase = true)) {
                val blocksByDistance = mutableMapOf<Int, MutableList<Block>>()

                for (b in shuffledBlocks) {
                    val distance = maxOf(
                        abs(b.x - block.x),
                        abs(b.y - block.y),
                        abs(b.z - block.z)
                    )
                    blocksByDistance.getOrPut(distance) { mutableListOf() }.add(b)
                }

                blocks.clear()

                val sortedDistances = blocksByDistance.keys.sorted()
                var currentDistanceIndex = 0
                var currentBlockInDistance = 0

                plugin.runnableFactory.create { task ->
                    if (currentDistanceIndex >= sortedDistances.size) {
                        task.cancelTask()
                        return@create
                    }

                    if (checkTool) {
                        if (tool != this.inventory.itemInMainHand) {
                            task.cancelTask()
                            return@create
                        }
                    }

                    val currentDistance = sortedDistances[currentDistanceIndex]
                    val layerBlocks = blocksByDistance[currentDistance] ?: mutableListOf()

                    val endIndex = minOf(currentBlockInDistance + blocksPerTick, layerBlocks.size)
                    val blocksToBreak = layerBlocks.subList(currentBlockInDistance, endIndex)

                    if (blocksToBreak.isNotEmpty())
                        this.breakBlocksSafely(blocksToBreak)

                    currentBlockInDistance = endIndex

                    if (currentBlockInDistance >= layerBlocks.size) {
                        blocksByDistance.remove(currentDistance)
                        currentDistanceIndex++
                        currentBlockInDistance = 0
                    }
                }.runTaskTimer(block.location, 1, delay.toLong())
            } else if (from.equals("outside", ignoreCase = true)) {
                val blocksByDistance = mutableMapOf<Int, MutableList<Block>>()

                for (b in shuffledBlocks) {
                    val distance = maxOf(
                        abs(b.x - block.x),
                        abs(b.y - block.y),
                        abs(b.z - block.z)
                    )
                    blocksByDistance.getOrPut(distance) { mutableListOf() }.add(b)
                }

                blocks.clear()

                val sortedDistances = blocksByDistance.keys.sortedDescending()
                var currentDistanceIndex = 0
                var currentBlockInDistance = 0

                plugin.runnableFactory.create { task ->
                    if (currentDistanceIndex >= sortedDistances.size) {
                        task.cancelTask()
                        return@create
                    }

                    if (checkTool) {
                        if (tool != this.inventory.itemInMainHand) {
                            task.cancelTask()
                            return@create
                        }
                    }

                    val currentDistance = sortedDistances[currentDistanceIndex]
                    val layerBlocks = blocksByDistance[currentDistance] ?: emptyList()

                    val endIndex = minOf(currentBlockInDistance + blocksPerTick, layerBlocks.size)
                    val blocksToBreak = layerBlocks.subList(currentBlockInDistance, endIndex)

                    if (blocksToBreak.isNotEmpty())
                        this.breakBlocksSafely(blocksToBreak)

                    currentBlockInDistance = endIndex

                    if (currentBlockInDistance >= layerBlocks.size) {
                        blocksByDistance.remove(currentDistance)
                        currentDistanceIndex++
                        currentBlockInDistance = 0
                    }
                }.runTaskTimer(block.location, 1, delay.toLong())
            } else if (
                from.equals("south", ignoreCase = true) ||
                from.equals("north", ignoreCase = true) ||
                from.equals("east", ignoreCase = true) ||
                from.equals("west", ignoreCase = true) ||
                from.equals("above", ignoreCase = true) ||
                from.equals("below", ignoreCase = true)
            ) {
                val blocksByHeight = mutableMapOf<Int, MutableList<Block>>()

                for (b in shuffledBlocks) {
                    if (
                        from.equals("south", ignoreCase = true) ||
                        from.equals("north", ignoreCase = true)
                    )
                        blocksByHeight.getOrPut(b.z) { mutableListOf() }.add(b)
                    else if (
                        from.equals("east", ignoreCase = true) ||
                        from.equals("west", ignoreCase = true)
                    )
                        blocksByHeight.getOrPut(b.x) { mutableListOf() }.add(b)
                    else if (
                        from.equals("above", ignoreCase = true) ||
                        from.equals("below", ignoreCase = true)
                    )
                        blocksByHeight.getOrPut(b.y) { mutableListOf() }.add(b)
                }

                blocks.clear()

                val sortedHeights = blocksByHeight.keys.toList()
                    .applyIf(
                        from.equals("south", ignoreCase = true) ||
                                from.equals("east", ignoreCase = true) ||
                                from.equals("above", ignoreCase = true)
                    ) { sortedDescending() }
                    .applyIf(
                        from.equals("north", ignoreCase = true) ||
                                from.equals("west", ignoreCase = true) ||
                                from.equals("below", ignoreCase = true)
                    ) { sorted() }

                var currentHeightIndex = 0
                var currentBlockInHeight = 0

                plugin.runnableFactory.create { task ->
                    if (currentHeightIndex >= sortedHeights.size) {
                        task.cancelTask()
                        return@create
                    }

                    if (checkTool) {
                        if (tool != this.inventory.itemInMainHand) {
                            task.cancelTask()
                            return@create
                        }
                    }

                    val currentHeight = sortedHeights[currentHeightIndex]
                    val layerBlocks = blocksByHeight[currentHeight] ?: emptyList()

                    val endIndex = minOf(currentBlockInHeight + blocksPerTick, layerBlocks.size)
                    val blocksToBreak = layerBlocks.subList(currentBlockInHeight, endIndex)

                    if (blocksToBreak.isNotEmpty())
                        this.breakBlocksSafely(blocksToBreak)

                    currentBlockInHeight = endIndex

                    if (currentBlockInHeight >= layerBlocks.size) {
                        blocksByHeight.remove(currentHeight)
                        currentHeightIndex++
                        currentBlockInHeight = 0
                    }
                }.runTaskTimer(block.location, 1, delay.toLong())
            } else if (from.equals("direction", ignoreCase = true)) {
                val direction = this.direction.oppositeFace

                val blocksByHeight = mutableMapOf<Int, MutableList<Block>>()

                for (b in shuffledBlocks) {
                    when (direction) {
                        BlockFace.SOUTH, BlockFace.NORTH
                            -> blocksByHeight.getOrPut(b.z) { mutableListOf() }
                            .add(b)

                        BlockFace.EAST, BlockFace.WEST
                            -> blocksByHeight.getOrPut(b.x) { mutableListOf() }
                            .add(b)

                        BlockFace.UP, BlockFace.DOWN
                            -> blocksByHeight.getOrPut(b.y) { mutableListOf() }.add(b)

                        else -> return
                    }
                }

                blocks.clear()

                val sortedHeights = blocksByHeight.keys.toList()
                    .applyIf(
                        direction == BlockFace.SOUTH ||
                                direction == BlockFace.EAST ||
                                direction == BlockFace.UP
                    ) { sortedDescending() }
                    .applyIf(
                        direction == BlockFace.NORTH ||
                                direction == BlockFace.WEST ||
                                direction == BlockFace.DOWN
                    ) { sorted() }

                var currentHeightIndex = 0
                var currentBlockInHeight = 0

                plugin.runnableFactory.create { task ->
                    if (currentHeightIndex >= sortedHeights.size) {
                        task.cancelTask()
                        return@create
                    }

                    if (checkTool) {
                        if (tool != this.inventory.itemInMainHand) {
                            task.cancelTask()
                            return@create
                        }
                    }

                    val currentHeight = sortedHeights[currentHeightIndex]
                    val layerBlocks = blocksByHeight[currentHeight] ?: emptyList()

                    val endIndex = minOf(currentBlockInHeight + blocksPerTick, layerBlocks.size)
                    val blocksToBreak = layerBlocks.subList(currentBlockInHeight, endIndex)

                    if (blocksToBreak.isNotEmpty())
                        this.breakBlocksSafely(blocksToBreak)

                    currentBlockInHeight = endIndex

                    if (currentBlockInHeight >= layerBlocks.size) {
                        blocksByHeight.remove(currentHeight)
                        currentHeightIndex++
                        currentBlockInHeight = 0
                    }
                }.runTaskTimer(block.location, 1, delay.toLong())
            } else if (from.equals("all", ignoreCase = true)) {
                var currentIndex = 0

                plugin.runnableFactory.create { task ->
                    if (currentIndex >= shuffledBlocks.size) {
                        task.cancelTask()
                        return@create
                    }

                    if (checkTool) {
                        if (tool != this.inventory.itemInMainHand) {
                            task.cancelTask()
                            return@create
                        }
                    }

                    val endIndex = minOf(currentIndex + blocksPerTick, shuffledBlocks.size)
                    val blocksToBreak = shuffledBlocks.subList(currentIndex, endIndex)

                    if (blocksToBreak.isNotEmpty())
                        this.breakBlocksSafely(blocksToBreak)

                    currentIndex = endIndex
                }.runTaskTimer(block.location, 1, delay.toLong())
            }
        }
    }

    private fun generateSpiral(radius: Int): Map<Pair<Int, Int>, Int> {
        val spiral = mutableMapOf<Pair<Int, Int>, Int>()

        var x = 0
        var z = 0
        var dx = 0
        var dz = -1

        val n = radius * 2 + 1
        val m = radius * 2 + 1

        for (i in 0 until n * m) {
            if ((abs(x) == abs(z) && !(dx == 1 && dz == 0)) ||
                (x > 0 && z == 1 - x)
            ) {
                // Change direction at corners
                val temp = dx
                dx = -dz
                dz = temp
            }

            if (abs(x) > n / 2 || abs(z) > m / 2) {
                // Change direction for non-square
                val temp = dx
                dx = -dz
                dz = temp
                x = -z + dx
                z = x + dz
            }

            spiral[Pair(x, z)] = i
            x += dx
            z += dz
        }

        return spiral
    }
}
