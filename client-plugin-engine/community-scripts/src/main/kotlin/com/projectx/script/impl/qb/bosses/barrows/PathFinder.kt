package com.projectx.script.impl.qb.bosses.barrows

class PathFinder(graph: Graph) {
    private val barrowsGraph: Graph = graph
    private val doorMap: MutableMap<String?, MutableList<String?>?> = HashMap()

    init {
        this.initializeDoorMap()
    }

    private fun initializeDoorMap() {
        this.doorMap["NE-NW"] = mutableListOf<String?>("NE_N_DOOR", "NW_N_DOOR")
        this.doorMap["NW-NE"] = mutableListOf<String?>("NW_N_DOOR", "NE_N_DOOR")
        this.doorMap["SE-SW"] = mutableListOf<String?>("SE_S_DOOR", "SW_S_DOOR")
        this.doorMap["SW-SE"] = mutableListOf<String?>("SW_S_DOOR", "SE_S_DOOR")
        this.doorMap["SE-NE"] = mutableListOf<String?>("SE_E_DOOR", "NE_E_DOOR")
        this.doorMap["NE-SE"] = mutableListOf<String?>("NE_E_DOOR", "SE_E_DOOR")
        this.doorMap["SW-NW"] = mutableListOf<String?>("SW_W_DOOR", "NW_W_DOOR")
        this.doorMap["NW-SW"] = mutableListOf<String?>("NW_W_DOOR", "SW_W_DOOR")
        this.doorMap["C-N"] = mutableListOf<String?>("C_N_DOOR", "N_S_DOOR")
        this.doorMap["N-C"] = mutableListOf<String?>("N_S_DOOR", "C_N_DOOR")
        this.doorMap["C-E"] = mutableListOf<String?>("C_E_DOOR", "E_W_DOOR")
        this.doorMap["E-C"] = mutableListOf<String?>("E_W_DOOR", "C_E_DOOR")
        this.doorMap["C-S"] = mutableListOf<String?>("C_S_DOOR", "S_N_DOOR")
        this.doorMap["S-C"] = mutableListOf<String?>("S_N_DOOR", "C_S_DOOR")
        this.doorMap["C-W"] = mutableListOf<String?>("C_W_DOOR", "W_E_DOOR")
        this.doorMap["W-C"] = mutableListOf<String?>("W_E_DOOR", "C_W_DOOR")
        this.doorMap["N-NE"] = mutableListOf<String?>("N_E_DOOR", "NE_W_DOOR")
        this.doorMap["NE-N"] = mutableListOf<String?>("NE_W_DOOR", "N_E_DOOR")
        this.doorMap["NE-E"] = mutableListOf<String?>("NE_S_DOOR", "E_N_DOOR")
        this.doorMap["E-NE"] = mutableListOf<String?>("E_N_DOOR", "NE_S_DOOR")
        this.doorMap["N-NW"] = mutableListOf<String?>("N_W_DOOR", "NW_E_DOOR")
        this.doorMap["NW-N"] = mutableListOf<String?>("NW_E_DOOR", "N_W_DOOR")
        this.doorMap["S-SE"] = mutableListOf<String?>("S_E_DOOR", "SE_W_DOOR")
        this.doorMap["SE-S"] = mutableListOf<String?>("SE_W_DOOR", "S_E_DOOR")
        this.doorMap["S-SW"] = mutableListOf<String?>("S_W_DOOR", "SW_E_DOOR")
        this.doorMap["SW-S"] = mutableListOf<String?>("SW_E_DOOR", "S_W_DOOR")
        this.doorMap["W-NW"] = mutableListOf<String?>("W_N_DOOR", "NW_S_DOOR")
        this.doorMap["NW-W"] = mutableListOf<String?>("NW_S_DOOR", "W_N_DOOR")
        this.doorMap["SW-W"] = mutableListOf<String?>("SW_N_DOOR", "W_S_DOOR")
        this.doorMap["W-SW"] = mutableListOf<String?>("W_S_DOOR", "SW_N_DOOR")
        this.doorMap["SE-E"] = mutableListOf<String?>("SE_N_DOOR", "E_S_DOOR")
        this.doorMap["E-SE"] = mutableListOf<String?>("E_S_DOOR", "SE_N_DOOR")
    }

    fun findDoorsForPath(start: String?, end: String?): MutableList<String?> {
        val nodesPath = this.barrowsGraph.findShortestPath(start, end)
        val doorsPath: MutableList<String?> = ArrayList()

        for (i in 0..<nodesPath.size - 1) {
            val fromNode = nodesPath[i]
            val toNode = nodesPath[i + 1]
            val key = "$fromNode-$toNode"
            val doors: MutableList<String?> = this.doorMap.getOrDefault(key, mutableListOf<String?>()) as MutableList<String?>
            doorsPath.addAll(doors)
        }

        return doorsPath
    }
}