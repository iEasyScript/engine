package com.projectx.script.impl.trent.aiomining

import com.projectx.script.api.varps

private val ORE_TO_VARBIT = mapOf(
    436 to 43188,
    438 to 43190,
    440 to 43192,
    453 to 43194,
    442 to 43196,
    447 to 43198,
    449 to 43200,
    44820 to 43202,
    444 to 43204,
    451 to 43206,
    44822 to 43208,
    44824 to 43210,
    44826 to 43212,
    44828 to 43214,
    21778 to 43216,
    44830 to 43218,
    44832 to 43220,
    57175 to 55880,
    57177 to 55883,
    57179 to 55886,
    57181 to 55889,
    57183 to 55892,
    57185 to 55895,
    57187 to 55898,
    57189 to 55901,
    57191 to 55904,
    57193 to 55907
)

fun getOreboxCount(oreId: Int) = ORE_TO_VARBIT[oreId]?.let { varps.getVarBit(it) } ?: 0