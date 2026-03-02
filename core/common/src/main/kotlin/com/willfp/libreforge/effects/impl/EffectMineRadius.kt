package com.willfp.libreforge.effects.impl

import com.nexomc.nexo.utils.applyIf
import com.willfp.eco.core.config.interfaces.Config
import com.willfp.eco.core.integrations.antigrief.AntigriefManager
import com.willfp.eco.util.direction
import com.willfp.libreforge.NoCompileData
import com.willfp.libreforge.arguments
import com.willfp.libreforge.effects.templates.MineBlockEffect
import com.willfp.libreforge.getIntFromExpression
import com.willfp.libreforge.plugin
import com.willfp.libreforge.triggers.TriggerData
import com.willfp.libreforge.triggers.TriggerParameter
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

object EffectMineRadius : MineBlockEffect<NoCompileData>("mine_radius") {
    override val parameters = setOf(
        TriggerParameter.PLAYER
    )

    override val arguments = arguments {
        require("radius", "You must specify the radius to break!")
    }

    override fun onTrigger(config: Config, data: TriggerData, compileData: NoCompileData): Boolean {
        val block = data.block ?: data.location?.block ?: return false
        val player = data.player ?: return false
        val world = block.world

        val radius = config.getIntFromExpression("radius", data)

        if (player.isSneaking && config.getBool("disable_on_sneak")) {
            return false
        }

        val whitelist = config.getStringsOrNull("whitelist")
            ?.mapNotNull { Material.matchMaterial(it.uppercase()) }?.toSet()

        val blacklist = config.getStringsOrNull("blacklisted_blocks")
            ?.mapNotNull { Material.matchMaterial(it.uppercase()) }?.toSet()

        val blocks = mutableListOf<Block>()

        val checkHardness = config.getBool("check_hardness")

        for (y in (-radius..radius)) {
            val endY = block.y + y
            if (endY !in world.minHeight..world.maxHeight) {
                continue
            }

            for (x in (-radius..radius)) {
                for (z in (-radius..radius)) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue
                    }

                    val toBreak = world.getBlockAt(block.x + x, block.y + y, block.z + z)

                    if (toBreak.type == Material.AIR) {
                        continue
                    }

                    if (toBreak.type.hardness < 0) {
                        continue
                    }

                    if (!AntigriefManager.canBreakBlock(player, toBreak)) {
                        continue
                    }

                    if (blacklist != null) {
                        if (toBreak.type in blacklist) {
                            continue
                        }
                    }

                    if (whitelist != null) {
                        if (toBreak.type !in whitelist) {
                            continue
                        }
                    }

                    if (checkHardness && toBreak.type.hardness > block.type.hardness) {
                        continue
                    }

                    blocks.add(toBreak)
                }
            }
        }
        val animation = config.getSubsectionOrNull("animation")

        if (animation == null)
            player.breakBlocksSafely(player.inventory.itemInMainHand, blocks)
        else {
            val type = animation.getString("type")
            val from = animation.getString("from")

            val checkTool = animation.getBoolOrNull("check-tool") ?: true
            val delay = max(1, animation.getIntOrNull("delay") ?: 1)
            val blocksPerTick = animation.getIntOrNull("blocks-per-tick") ?: 1
            val reverse = animation.getBoolOrNull("reversed") ?: false

            val tool = player.inventory.itemInMainHand

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
                            if (tool != player.inventory.itemInMainHand) {
                                task.cancelTask()
                                return@create
                            }
                        }

                        val currentDistance = sortedDistances[currentDistanceIndex]
                        val layerBlocks = blocksByDistance[currentDistance] ?: mutableListOf()

                        val endIndex = minOf(currentBlockInDistance + blocksPerTick, layerBlocks.size)
                        val blocksToBreak = layerBlocks.subList(currentBlockInDistance, endIndex)

                        if (blocksToBreak.isNotEmpty())
                            player.breakBlocksSafely(tool, blocksToBreak)

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
                            if (tool != player.inventory.itemInMainHand) {
                                task.cancelTask()
                                return@create
                            }
                        }

                        val currentDistance = sortedDistances[currentDistanceIndex]
                        val layerBlocks = blocksByDistance[currentDistance] ?: emptyList()

                        val endIndex = minOf(currentBlockInDistance + blocksPerTick, layerBlocks.size)
                        val blocksToBreak = layerBlocks.subList(currentBlockInDistance, endIndex)

                        if (blocksToBreak.isNotEmpty())
                            player.breakBlocksSafely(tool, blocksToBreak)

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
                            if (tool != player.inventory.itemInMainHand) {
                                task.cancelTask()
                                return@create
                            }
                        }

                        val currentHeight = sortedHeights[currentHeightIndex]
                        val layerBlocks = blocksByHeight[currentHeight] ?: emptyList()

                        val endIndex = minOf(currentBlockInHeight + blocksPerTick, layerBlocks.size)
                        val blocksToBreak = layerBlocks.subList(currentBlockInHeight, endIndex)

                        if (blocksToBreak.isNotEmpty())
                            player.breakBlocksSafely(tool, blocksToBreak)

                        currentBlockInHeight = endIndex

                        if (currentBlockInHeight >= layerBlocks.size) {
                            blocksByHeight.remove(currentHeight)
                            currentHeightIndex++
                            currentBlockInHeight = 0
                        }
                    }.runTaskTimer(block.location, 1, delay.toLong())
                } else if (from.equals("direction", ignoreCase = true)) {
                    val direction = player.direction.oppositeFace

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

                            else -> return false
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
                            if (tool != player.inventory.itemInMainHand) {
                                task.cancelTask()
                                return@create
                            }
                        }

                        val currentHeight = sortedHeights[currentHeightIndex]
                        val layerBlocks = blocksByHeight[currentHeight] ?: emptyList()

                        val endIndex = minOf(currentBlockInHeight + blocksPerTick, layerBlocks.size)
                        val blocksToBreak = layerBlocks.subList(currentBlockInHeight, endIndex)

                        if (blocksToBreak.isNotEmpty())
                            player.breakBlocksSafely(tool, blocksToBreak)

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
                            if (tool != player.inventory.itemInMainHand) {
                                task.cancelTask()
                                return@create
                            }
                        }

                        val currentDistance = sortedDistances[currentDistanceIndex]
                        val layerBlocks = blocksByDistance[currentDistance] ?: emptyList()

                        val endIndex = minOf(currentBlockInDistance + blocksPerTick, layerBlocks.size)
                        val blocksToBreak = layerBlocks.subList(currentBlockInDistance, endIndex)

                        if (blocksToBreak.isNotEmpty())
                            player.breakBlocksSafely(tool, blocksToBreak)

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
                            if (tool != player.inventory.itemInMainHand) {
                                task.cancelTask()
                                return@create
                            }
                        }

                        val currentDistance = sortedDistances[currentDistanceIndex]
                        val layerBlocks = blocksByDistance[currentDistance] ?: emptyList()

                        val endIndex = minOf(currentBlockInDistance + blocksPerTick, layerBlocks.size)
                        val blocksToBreak = layerBlocks.subList(currentBlockInDistance, endIndex)

                        if (blocksToBreak.isNotEmpty())
                            player.breakBlocksSafely(tool, blocksToBreak)

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
                            if (tool != player.inventory.itemInMainHand) {
                                task.cancelTask()
                                return@create
                            }
                        }

                        val currentHeight = sortedHeights[currentHeightIndex]
                        val layerBlocks = blocksByHeight[currentHeight] ?: emptyList()

                        val endIndex = minOf(currentBlockInHeight + blocksPerTick, layerBlocks.size)
                        val blocksToBreak = layerBlocks.subList(currentBlockInHeight, endIndex)

                        if (blocksToBreak.isNotEmpty())
                            player.breakBlocksSafely(tool, blocksToBreak)

                        currentBlockInHeight = endIndex

                        if (currentBlockInHeight >= layerBlocks.size) {
                            blocksByHeight.remove(currentHeight)
                            currentHeightIndex++
                            currentBlockInHeight = 0
                        }
                    }.runTaskTimer(block.location, 1, delay.toLong())
                } else if (from.equals("direction", ignoreCase = true)) {
                    val direction = player.direction.oppositeFace

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

                            else -> return false
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
                            if (tool != player.inventory.itemInMainHand) {
                                task.cancelTask()
                                return@create
                            }
                        }

                        val currentHeight = sortedHeights[currentHeightIndex]
                        val layerBlocks = blocksByHeight[currentHeight] ?: emptyList()

                        val endIndex = minOf(currentBlockInHeight + blocksPerTick, layerBlocks.size)
                        val blocksToBreak = layerBlocks.subList(currentBlockInHeight, endIndex)

                        if (blocksToBreak.isNotEmpty())
                            player.breakBlocksSafely(tool, blocksToBreak)

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
                            if (tool != player.inventory.itemInMainHand) {
                                task.cancelTask()
                                return@create
                            }
                        }

                        val currentDistance = sortedDistances[currentDistanceIndex]
                        val layerBlocks = blocksByDistance[currentDistance] ?: mutableListOf()

                        val endIndex = minOf(currentBlockInDistance + blocksPerTick, layerBlocks.size)
                        val blocksToBreak = layerBlocks.subList(currentBlockInDistance, endIndex)

                        if (blocksToBreak.isNotEmpty())
                            player.breakBlocksSafely(tool, blocksToBreak)

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
                            if (tool != player.inventory.itemInMainHand) {
                                task.cancelTask()
                                return@create
                            }
                        }

                        val currentDistance = sortedDistances[currentDistanceIndex]
                        val layerBlocks = blocksByDistance[currentDistance] ?: emptyList()

                        val endIndex = minOf(currentBlockInDistance + blocksPerTick, layerBlocks.size)
                        val blocksToBreak = layerBlocks.subList(currentBlockInDistance, endIndex)

                        if (blocksToBreak.isNotEmpty())
                            player.breakBlocksSafely(tool, blocksToBreak)

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
                            if (tool != player.inventory.itemInMainHand) {
                                task.cancelTask()
                                return@create
                            }
                        }

                        val currentHeight = sortedHeights[currentHeightIndex]
                        val layerBlocks = blocksByHeight[currentHeight] ?: emptyList()

                        val endIndex = minOf(currentBlockInHeight + blocksPerTick, layerBlocks.size)
                        val blocksToBreak = layerBlocks.subList(currentBlockInHeight, endIndex)

                        if (blocksToBreak.isNotEmpty())
                            player.breakBlocksSafely(tool, blocksToBreak)

                        currentBlockInHeight = endIndex

                        if (currentBlockInHeight >= layerBlocks.size) {
                            blocksByHeight.remove(currentHeight)
                            currentHeightIndex++
                            currentBlockInHeight = 0
                        }
                    }.runTaskTimer(block.location, 1, delay.toLong())
                } else if (from.equals("direction", ignoreCase = true)) {
                    val direction = player.direction.oppositeFace

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

                            else -> return false
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
                            if (tool != player.inventory.itemInMainHand) {
                                task.cancelTask()
                                return@create
                            }
                        }

                        val currentHeight = sortedHeights[currentHeightIndex]
                        val layerBlocks = blocksByHeight[currentHeight] ?: emptyList()

                        val endIndex = minOf(currentBlockInHeight + blocksPerTick, layerBlocks.size)
                        val blocksToBreak = layerBlocks.subList(currentBlockInHeight, endIndex)

                        if (blocksToBreak.isNotEmpty())
                            player.breakBlocksSafely(tool, blocksToBreak)

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
                            if (tool != player.inventory.itemInMainHand) {
                                task.cancelTask()
                                return@create
                            }
                        }

                        val endIndex = minOf(currentIndex + blocksPerTick, shuffledBlocks.size)
                        val blocksToBreak = shuffledBlocks.subList(currentIndex, endIndex)

                        if (blocksToBreak.isNotEmpty())
                            player.breakBlocksSafely(tool, blocksToBreak)

                        currentIndex = endIndex
                    }.runTaskTimer(block.location, 1, delay.toLong())
                }
            }
        }

        return true
    }

    fun generateSpiral(radius: Int): Map<Pair<Int, Int>, Int> {
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
