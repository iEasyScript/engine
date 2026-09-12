package org.projectx.core.game.combat

import java.lang.Integer.bitCount

object EffectVarTables {
    private const val VENGEFUL_GHOST_STRUCT = 48337

    private const val SKILL_ATTACK = 0
    private const val SKILL_DEFENSE = 1
    private const val SKILL_STRENGTH = 2
    private const val SKILL_CONSTITUTION = 3
    private const val SKILL_RANGED = 4
    private const val SKILL_MAGIC = 6
    private const val SKILL_WOODCUTTING = 8
    private const val SKILL_FISHING = 10
    private const val SKILL_MINING = 14
    private const val SKILL_HUNTER = 21
    private const val SKILL_NECROMANCY = 28

    private fun opponentPrayerOverheadFlag(index: Int, ctx: CombatContext): Int {
        return if (ctx.varcs.getVar(4168) == 0) {
            when (index) {
                0 -> ctx.varps.getVarBit(1967)
                1 -> ctx.varps.getVarBit(1966)
                2 -> ctx.varps.getVarBit(1965)
                3 -> ctx.varps.getVarBit(2016)
                4 -> ctx.varps.getVarBit(2015)
                5 -> ctx.varps.getVarBit(2007)
                6 -> ctx.varps.getVarBit(2006)
                7 -> ctx.varps.getVarBit(2005)
                8 -> ctx.varps.getVarBit(53230)
                9 -> ctx.varps.getVarBit(53241)
                else -> 0
            }
        } else
            (ctx.varcs.getVar(4168) shr index) and 1
    }
    fun active(structId: Int, ctx: CombatContext): Boolean {
        if (timeRemainingMs(structId, ctx) > 0) return true
        if (stacks(structId, ctx) > 0) return true
        return when (structId) {
            1000 -> timeRemainingMs(VENGEFUL_GHOST_STRUCT, ctx) > 0 && ctx.varps.getVar(11021) != 0
            14541, 14542, 14543, 14540 -> ctx.varps.getVarBit(16739) != 0
            14545, 14546, 14547, 14544 -> ctx.varps.getVarBit(16740) != 0
            14549, 14550, 14551, 14548 -> ctx.varps.getVarBit(16741) != 0
            14572 -> ctx.varps.getVarBit(16742) != 0
            14573 -> ctx.varps.getVarBit(16743) != 0
            14575 -> ctx.varps.getVarBit(16744) != 0
            14576 -> ctx.varps.getVarBit(16745) != 0
            14577 -> ctx.varps.getVarBit(16746) != 0
            14578 -> ctx.varps.getVarBit(16747) != 0
            14580 -> ctx.varps.getVarBit(16748) != 0
            14581 -> ctx.varps.getVarBit(16749) != 0
            14582 -> ctx.varps.getVarBit(16750) != 0
            14553, 14554, 14555, 14552 -> ctx.varps.getVarBit(16751) != 0
            14561, 14562, 14563, 14560 -> ctx.varps.getVarBit(16752) != 0
            14557, 14558, 14559, 14556 -> ctx.varps.getVarBit(16753) != 0
            14565, 14566, 14567, 14564 -> ctx.varps.getVarBit(16754) != 0
            14579 -> ctx.varps.getVarBit(16755) != 0
            14568 -> ctx.varps.getVarBit(16756) != 0
            14569 -> ctx.varps.getVarBit(16757) != 0
            14574 -> ctx.varps.getVarBit(16758) != 0
            14570 -> ctx.varps.getVarBit(16759) != 0
            14571 -> ctx.varps.getVarBit(16760) != 0
            48361, 48362, 48363, 48360 -> ctx.varps.getVarBit(53271) != 0
            48365, 48366, 48367, 48364 -> ctx.varps.getVarBit(53272) != 0
            48368 -> ctx.varps.getVarBit(53273) != 0
            48369 -> ctx.varps.getVarBit(53274) != 0
            50077 -> ctx.varps.getVarBit(55729) != 0
            50228 -> ctx.varps.getVarBit(55986) != 0
            14583 -> ctx.varps.getVarBit(16761) != 0
            14584 -> ctx.varps.getVarBit(16762) != 0
            14585 -> ctx.varps.getVarBit(16763) != 0
            14586 -> ctx.varps.getVarBit(16786) != 0
            14587 -> ctx.varps.getVarBit(16764) != 0
            14588 -> ctx.varps.getVarBit(16785) != 0
            14591 -> ctx.varps.getVarBit(16787) != 0
            14590 -> ctx.varps.getVarBit(16788) != 0
            14589 -> ctx.varps.getVarBit(16765) != 0
            14592 -> ctx.varps.getVarBit(16766) != 0
            14593 -> ctx.varps.getVarBit(16767) != 0
            14594 -> ctx.varps.getVarBit(16768) != 0
            14595 -> ctx.varps.getVarBit(16769) != 0
            14596 -> ctx.varps.getVarBit(16770) != 0
            14597 -> ctx.varps.getVarBit(16771) != 0
            14598 -> ctx.varps.getVarBit(16772) != 0
            14599 -> ctx.varps.getVarBit(16781) != 0
            14600 -> ctx.varps.getVarBit(16773) != 0
            14601 -> ctx.varps.getVarBit(16782) != 0
            14602 -> ctx.varps.getVarBit(16774) != 0
            14603 -> ctx.varps.getVarBit(16775) != 0
            14604 -> ctx.varps.getVarBit(16776) != 0
            14605 -> ctx.varps.getVarBit(16777) != 0
            14606 -> ctx.varps.getVarBit(16778) != 0
            14607 -> ctx.varps.getVarBit(16779) != 0
            14608 -> ctx.varps.getVarBit(16780) != 0
            14609 -> ctx.varps.getVarBit(16784) != 0
            14610 -> ctx.varps.getVarBit(16783) != 0
            32272 -> ctx.varps.getVarBit(29065) != 0
            32273 -> ctx.varps.getVarBit(29066) != 0
            32274 -> ctx.varps.getVarBit(29067) != 0
            32275 -> ctx.varps.getVarBit(29068) != 0
            32276 -> ctx.varps.getVarBit(29069) != 0
            29241 -> ctx.varps.getVarBit(49330) != 0
            32278 -> ctx.varps.getVarBit(29071) != 0
            35360 -> ctx.varps.getVarBit(34866) != 0
            35361 -> ctx.varps.getVarBit(34867) != 0
            35362 -> ctx.varps.getVarBit(34868) != 0
            48370 -> ctx.varps.getVarBit(53275) != 0
            48371 -> ctx.varps.getVarBit(53276) != 0
            48373 -> ctx.varps.getVarBit(53277) != 0
            48374 -> ctx.varps.getVarBit(53278) != 0
            48375 -> ctx.varps.getVarBit(53279) != 0
            48376 -> ctx.varps.getVarBit(53280) != 0
            48378 -> ctx.varps.getVarBit(53281) != 0
            51666 -> ctx.varps.getVarBit(58052) != 0
            51665 -> ctx.varps.getVarBit(58053) != 0
            21176 -> opponentPrayerOverheadFlag(2, ctx) != 0
            21177 -> opponentPrayerOverheadFlag(1, ctx) != 0
            36801 -> opponentPrayerOverheadFlag(0, ctx) != 0
            44226 -> {
                when {
                    ctx.varps.getVarBit(1984) == 1 || ctx.varps.getVarBit(1985) == 1 ||
                            ctx.varps.getVarBit(2017) == 1 || ctx.varps.getVarBit(34863) == 1 -> false
                    ctx.varps.getVarBit(2038) == 1 -> true
                    ctx.varps.getVarBit(2039) == 1 -> true
                    ctx.varps.getVarBit(1999) == 1 -> true
                    ctx.varps.getVarBit(2008) == 1 -> true
                    ctx.varps.getVarBit(1959) == 1 -> true
                    ctx.varps.getVarBit(1960) == 1 -> true
                    ctx.varps.getVarBit(1961) == 1 -> true
                    else -> false
                }
            }
            44229 -> {
                when {
                    ctx.varps.getVarBit(1988) == 1 || ctx.varps.getVarBit(1997) == 1 || ctx.varps.getVarBit(34864) == 1 -> false
                    ctx.varps.getVarBit(2042) == 1 -> true
                    ctx.varps.getVarBit(2043) == 1 -> true
                    ctx.varps.getVarBit(2000) == 1 -> true
                    ctx.varps.getVarBit(2009) == 1 -> true
                    ctx.varps.getVarBit(1971) == 1 -> true
                    ctx.varps.getVarBit(1972) == 1 -> true
                    ctx.varps.getVarBit(1973) == 1 -> true
                    else -> false
                }
            }
            44231 -> {
                when {
                    ctx.varps.getVarBit(1987) == 1 || ctx.varps.getVarBit(1996) == 1 || ctx.varps.getVarBit(34865) == 1 -> false
                    ctx.varps.getVarBit(2046) == 1 -> true
                    ctx.varps.getVarBit(2047) == 1 -> true
                    ctx.varps.getVarBit(2001) == 1 -> true
                    ctx.varps.getVarBit(2010) == 1 -> true
                    ctx.varps.getVarBit(1974) == 1 -> true
                    ctx.varps.getVarBit(1975) == 1 -> true
                    ctx.varps.getVarBit(1976) == 1 -> true
                    else -> false
                }
            }
            44228 -> {
                when {
                    ctx.varps.getVarBit(1984) == 1 || ctx.varps.getVarBit(1985) == 1 ||
                            ctx.varps.getVarBit(1988) == 1 || ctx.varps.getVarBit(1987) == 1 ||
                            ctx.varps.getVarBit(2017) == 1 || ctx.varps.getVarBit(1997) == 1 ||
                            ctx.varps.getVarBit(1996) == 1 || ctx.varps.getVarBit(34865) == 1 ||
                            ctx.varps.getVarBit(34863) == 1 || ctx.varps.getVarBit(34864) == 1 -> false
                    ctx.varps.getVarBit(2050) == 1 -> true
                    ctx.varps.getVarBit(2051) == 1 -> true
                    ctx.varps.getVarBit(1992) == 1 -> true
                    ctx.varps.getVarBit(2011) == 1 -> true
                    ctx.varps.getVarBit(1953) == 1 -> true
                    ctx.varps.getVarBit(1954) == 1 -> true
                    ctx.varps.getVarBit(1955) == 1 -> true
                    else -> false
                }
            }
            44227 -> {
                when {
                    ctx.varps.getVarBit(1984) == 1 || ctx.varps.getVarBit(1985) == 1 ||
                            ctx.varps.getVarBit(2017) == 1 || ctx.varps.getVarBit(34863) == 1 -> false
                    ctx.varps.getVarBit(2040) == 1 -> true
                    ctx.varps.getVarBit(2041) == 1 -> true
                    ctx.varps.getVarBit(1989) == 1 -> true
                    ctx.varps.getVarBit(1993) == 1 -> true
                    ctx.varps.getVarBit(1956) == 1 -> true
                    ctx.varps.getVarBit(1957) == 1 -> true
                    ctx.varps.getVarBit(1958) == 1 -> true
                    else -> false
                }
            }
            44230 -> {
                when {
                    ctx.varps.getVarBit(1988) == 1 || ctx.varps.getVarBit(1997) == 1 || ctx.varps.getVarBit(34864) == 1 -> false
                    ctx.varps.getVarBit(2044) == 1 -> true
                    ctx.varps.getVarBit(2045) == 1 -> true
                    ctx.varps.getVarBit(1990) == 1 -> true
                    ctx.varps.getVarBit(1994) == 1 -> true
                    ctx.varps.getVarBit(1977) == 1 -> true
                    ctx.varps.getVarBit(1978) == 1 -> true
                    ctx.varps.getVarBit(1979) == 1 -> true
                    else -> false
                }
            }
            44232 -> {
                when {
                    ctx.varps.getVarBit(1987) == 1 || ctx.varps.getVarBit(1996) == 1 || ctx.varps.getVarBit(34865) == 1 -> false
                    ctx.varps.getVarBit(2048) == 1 -> true
                    ctx.varps.getVarBit(2049) == 1 -> true
                    ctx.varps.getVarBit(1991) == 1 -> true
                    ctx.varps.getVarBit(1995) == 1 -> true
                    ctx.varps.getVarBit(1980) == 1 -> true
                    ctx.varps.getVarBit(1981) == 1 -> true
                    ctx.varps.getVarBit(1982) == 1 -> true
                    else -> false
                }
            }
            36802 -> opponentPrayerOverheadFlag(7, ctx) != 0
            36861 -> opponentPrayerOverheadFlag(6, ctx) != 0
            36862 -> opponentPrayerOverheadFlag(5, ctx) != 0
            14885 -> ctx.varps.getVarBit(2018) == 1
            14886 -> ctx.varps.getVarBit(2019) == 1
            14887 -> ctx.varps.getVarBit(2020) == 1
            14888 -> ctx.varps.getVarBit(2021) == 1
            14889 -> ctx.varps.getVarBit(2022) == 1
            14890 -> ctx.varps.getVarBit(2023) == 1
            14891 -> ctx.varps.getVarBit(2024) == 1
            14892 -> ctx.varps.getVarBit(2025) == 1
            14895 -> ctx.varps.getVarBit(2026) == 1
            14896 -> ctx.varps.getVarBit(2027) == 1
            14897 -> ctx.varps.getVarBit(2028) == 1
            14898 -> ctx.varps.getVarBit(2029) == 1
            14893 -> ctx.varps.getVarBit(2030) == 1
            14894 -> ctx.varps.getVarBit(2031) == 1
            14899 -> ctx.varps.getVarBit(2032) == 1
            14900 -> ctx.varps.getVarBit(2033) == 1
            14903 -> ctx.varps.getVarBit(2034) == 1
            14904 -> ctx.varps.getVarBit(2035) == 1
            14905 -> ctx.varps.getVarBit(2037) != 0
            14884 -> ctx.varps.getVarBit(1911) != 0
            14693 -> ctx.varps.getVarBit(2052) == 2
            14694 -> ctx.varps.getVarBit(2052) == 3
            14695 -> ctx.varps.getVarBit(2052) == 4
            14901 -> ctx.varps.getVarBit(2054) != 0
            14920 -> ctx.varps.getVarBit(2055) != 0
            19828 -> ctx.varps.getVarBit(18549) != 0
            23129 -> ctx.varps.getVarBit(20383) == 1
            28178 -> ctx.varps.getVarBit(22458) != 0
            28180 -> ctx.varps.getVarBit(22457) != 0
            24374 -> ctx.varps.getVarBit(23303) != 0
            29605 -> ctx.varps.getVarBit(25842) != 0
            29606 -> ctx.varps.getVarBit(25843) != 0
            29607 -> ctx.varps.getVarBit(25844) != 0
            14865 -> ctx.varps.getVarBit(26431) != 0
            31986 -> ctx.varps.getVarBit(28637) != 0
            31985 -> ctx.varps.getVarBit(28638) != 0
            31982 -> ctx.varps.getVarBit(28639) != 0
            30956 -> ctx.varps.getVarBit(29797) != 0
            33650 -> ctx.varps.getVarBit(32613) != 0
            33658 -> ctx.varps.getVarBit(32614) != 0
            34984 -> ctx.varps.getVarBit(34308) != 0
            34985 -> ctx.varps.getVarBit(34309) != 0
            34986 -> ctx.varps.getVarBit(34310) != 0
            35798 -> ctx.varps.getVarBit(35308) != 0
            37401 -> ctx.varps.getVarBit(35397) != 0
            37403 -> ctx.varps.getVarBit(35399) != 0
            37402 -> ctx.varps.getVarBit(35398) != 0
            1489 -> minOf(1, ctx.varps.getVarBit(55117)) != 0
            37659 -> ctx.varps.getVarBit(36804) != 0
            39040 -> ctx.varps.getVarBit(38913) != 0
            39041 -> ctx.varps.getVarBit(38914) != 0
            39140 -> ctx.varps.getVarBit(38966) > 0
            39242 -> ctx.varps.getVarBit(39314) != 0
            39243 -> ctx.varps.getVarBit(39315) != 0
            39244 -> ctx.varps.getVarBit(39316) != 0
            39784 -> ctx.varps.getVarBit(40076) != 0
            40936 -> ctx.varps.getVarBit(41441) != 0
            41143 -> ctx.varps.getVarBit(41573) != 0
            41144 -> ctx.varps.getVarBit(41574) != 0
            4550 -> ctx.varps.getVarBit(43412) > 0
            6860 -> ctx.varps.getVarBit(44123) != 0
            30521 -> ctx.varps.getVarBit(44230) != 0
            35992 -> ctx.varps.getVarBit(46014) != 0
            35993 -> ctx.varps.getVarBit(46015) != 0
            36000 -> ctx.varps.getVarBit(46016) != 0
            36001 -> ctx.varps.getVarBit(46017) != 0
            35898 -> ctx.varps.getVarBit(46018) != 0
            44875 -> ctx.varps.getVarBit(48028) != 0
            45047 -> ctx.varps.getVarBit(48686) != 0
            45045 -> ctx.varps.getVarBit(48685) != 0
            44912 -> ctx.varps.getVarBit(49448) != 0
            44946 -> ctx.varps.getVarBit(49449) != 0
            45400 -> ctx.varps.getVarBit(49723) != 0
            45605 -> ctx.varps.getVarBit(41541) != 0
            45117 -> ctx.varps.getVarBit(21556) != 0
            45797 -> ctx.varps.getVarBit(50328) != 0
            51672 -> ctx.varps.getVarBit(58068) != 0 || ctx.varps.getVarBit(58067) != 0
            45567 -> ctx.varps.getVarBit(50329) != 0
            3694 -> ctx.varps.getVarBit(51063) != 0
            43673 -> ctx.varps.getVarBit(4332) > 0
            41807 -> ctx.varps.getVarBit(51434) != 0
            46211 -> ctx.varps.getVarBit(51435) != 0
            41808 -> ctx.varps.getVarBit(51436) != 0
            46279 -> ctx.varps.getVarBit(51431) != 0
            46272 -> ctx.varps.getVarBit(51432) != 0
            41810 -> ctx.varps.getVarBit(51437) != 0
            41811 -> ctx.varps.getVarBit(51438) != 0
            46377 -> ctx.varps.getVarBit(51704) != 0
            47202 -> ctx.varps.getVarBit(52819) != 0
            48286 -> {
                when {
                    ctx.varps.getVarBit(53229) == 1 || ctx.varps.getVarBit(53239) == 1 || ctx.varps.getVarBit(53240) == 1 -> false
                    ctx.varps.getVarBit(53232) == 1 -> true
                    ctx.varps.getVarBit(53231) == 1 -> true
                    ctx.varps.getVarBit(53233) == 1 -> true
                    ctx.varps.getVarBit(53234) == 1 -> true
                    ctx.varps.getVarBit(53223) == 1 -> true
                    ctx.varps.getVarBit(53224) == 1 -> true
                    ctx.varps.getVarBit(53225) == 1 -> true
                    else -> false
                }
            }
            48287 -> {
                when {
                    ctx.varps.getVarBit(53229) == 1 || ctx.varps.getVarBit(53239) == 1 || ctx.varps.getVarBit(53240) == 1 -> false
                    ctx.varps.getVarBit(53236) == 1 -> true
                    ctx.varps.getVarBit(53235) == 1 -> true
                    ctx.varps.getVarBit(53237) == 1 -> true
                    ctx.varps.getVarBit(53238) == 1 -> true
                    ctx.varps.getVarBit(53226) == 1 -> true
                    ctx.varps.getVarBit(53227) == 1 -> true
                    ctx.varps.getVarBit(53228) == 1 -> true
                    else -> false
                }
            }
            48377 -> opponentPrayerOverheadFlag(8, ctx) != 0
            48288 -> ctx.varps.getVarBit(53244) != 0
            48289 -> ctx.varps.getVarBit(53242) == 1
            48290 -> ctx.varps.getVarBit(53243) == 1
            48338 -> ctx.varps.getVarBit(53245) != 0
            48344 -> ctx.varps.getVarBit(53246) != 0
            48345 -> ctx.varps.getVarBit(53247) != 0
            48346 -> ctx.varps.getVarBit(53248) != 0
            48283 -> ctx.varps.getVarBit(53249) != 0
            48284 -> ctx.varps.getVarBit(53250) != 0
            48285 -> ctx.varps.getVarBit(53251) != 0
            49074 -> ctx.varps.getVarBit(54672) != 0
            49071 -> minOf(1, ctx.varps.getVar(11534)) != 0
            49552 -> ctx.varps.getVarBit(55118) != 0
            50067 -> ctx.varps.getVarBit(55726) != 0
            50069 -> ctx.varps.getVarBit(55727) != 0
            50068 -> ctx.varps.getVarBit(55728) != 0
            50696 -> ctx.varps.getVarBit(56288) != 0
            else -> false
        }
    }
    private fun expiryCycle(structId: Int, ctx: CombatContext): Int {
        return when (structId) {
            14710 -> ctx.varcs.getVar(2142)
            14711 -> ctx.varcs.getVar(3733)
            14713 -> ctx.varcs.getVar(3741)
            14714 -> ctx.varcs.getVar(3740)
            14716 -> ctx.varcs.getVar(3742)
            14717 -> ctx.varcs.getVar(5977)
            12009 -> ctx.varcs.getVar(6675)
            14718 -> ctx.varcs.getVar(5965)
            14719 -> ctx.varcs.getVar(3735)
            14720 -> ctx.varcs.getVar(3737)
            14721 -> ctx.varcs.getVar(3734)
            45045 -> ctx.varcs.getVar(6868)
            45340 -> ctx.varcs.getVar(6953)
            14701, 14706, 14708, 14731, 14673, 14704, 28180, 19343, 44238, 45450, 47055 -> ctx.varcs.getVar(3746)
            14707 -> ctx.varcs.getVar(3731)
            46279 -> ctx.varcs.getVar(7061)
            14734 -> ctx.varcs.getVar(3743)
            14709 -> ctx.varcs.getVar(6643)
            31326 -> ctx.varcs.getVar(4788)
            31330 -> ctx.varcs.getVar(4790)
            31331 -> ctx.varcs.getVar(4789)
            31333 -> ctx.varcs.getVar(4791)
            31328 -> ctx.varcs.getVar(4792)
            31332 -> ctx.varcs.getVar(4793)
            45323 -> ctx.varcs.getVar(6921)
            45324 -> ctx.varcs.getVar(6922)
            45325 -> ctx.varcs.getVar(6923)
            45326 -> ctx.varcs.getVar(6924)
            45327 -> ctx.varcs.getVar(6925)
            45328 -> ctx.varcs.getVar(6926)
            45329 -> ctx.varcs.getVar(6927)
            45330 -> ctx.varcs.getVar(6928)
            45331 -> ctx.varcs.getVar(6929)
            31989 -> ctx.varcs.getVar(4982)
            31990 -> ctx.varcs.getVar(4983)
            31991 -> ctx.varcs.getVar(4984)
            31992 -> ctx.varcs.getVar(4985)
            32116 -> ctx.varcs.getVar(4989)
            32257 -> ctx.varcs.getVar(4996)
            32403 -> ctx.varcs.getVar(5094)
            30869 -> ctx.varcs.getVar(5112)
            33047 -> ctx.varcs.getVar(5127)
            39803 -> ctx.varcs.getVar(7049)
            21215 -> ctx.varcs.getVar(5152)
            33215 -> ctx.varcs.getVar(5184)
            33291 -> ctx.varcs.getVar(5186)
            33383 -> ctx.varcs.getVar(5189)
            33984 -> ctx.varcs.getVar(5837)
            28179 -> ctx.varcs.getVar(4184)
            28639 -> ctx.varcs.getVar(4243)
            28638 -> ctx.varcs.getVar(4244)
            28785 -> ctx.varcs.getVar(4261)
            28786 -> ctx.varcs.getVar(4262)
            24334 -> ctx.varcs.getVar(4490)
            29171 -> ctx.varcs.getVar(4621)
            29172 -> ctx.varcs.getVar(4623)
            29173 -> ctx.varcs.getVar(4622)
            29174, 29170 -> ctx.varcs.getVar(4620)
            29594 -> ctx.varcs.getVar(4627)
            29595 -> ctx.varcs.getVar(4628)
            29596 -> ctx.varcs.getVar(4629)
            29597 -> ctx.varcs.getVar(4630)
            29598 -> ctx.varcs.getVar(4631)
            29599 -> ctx.varcs.getVar(4632)
            29600 -> ctx.varcs.getVar(4633)
            29601 -> ctx.varcs.getVar(4634)
            29602 -> ctx.varcs.getVar(4635)
            29608 -> ctx.varcs.getVar(4636)
            29609 -> ctx.varcs.getVar(4637)
            29610 -> ctx.varcs.getVar(4638)
            29611 -> ctx.varcs.getVar(4639)
            29612 -> ctx.varcs.getVar(4640)
            29613 -> ctx.varcs.getVar(4641)
            29614 -> ctx.varcs.getVar(4642)
            29615 -> ctx.varcs.getVar(4643)
            29616 -> ctx.varcs.getVar(4644)
            29340 -> ctx.varcs.getVar(4625)
            30471 -> ctx.varcs.getVar(4720)
            30472 -> ctx.varcs.getVar(4721)
            30925 -> ctx.varcs.getVar(4744)
            14684, 40936, 14666, 14670 -> ctx.varcs.getVar(3746)
            39030 -> ctx.varcs.getVar(6353)
            14883 -> ctx.varcs.getVar(3748)
            35750 -> ctx.varcs.getVar(5968)
            35751 -> ctx.varcs.getVar(5969)
            35806 -> ctx.varcs.getVar(5970)
            35807 -> ctx.varcs.getVar(5971)
            33544, 33782 -> ctx.varcs.getVar(5972)
            35809 -> ctx.varcs.getVar(5973)
            35810 -> ctx.varcs.getVar(5974)
            35811 -> ctx.varcs.getVar(5975)
            35812 -> ctx.varcs.getVar(5976)
            35815 -> ctx.varcs.getVar(5978)
            35822 -> ctx.varcs.getVar(5979)
            35818 -> ctx.varcs.getVar(5980)
            35819 -> ctx.varcs.getVar(5981)
            35820 -> ctx.varcs.getVar(5982)
            35821 -> ctx.varcs.getVar(5983)
            35823 -> ctx.varcs.getVar(5984)
            35824 -> ctx.varcs.getVar(5985)
            35825 -> ctx.varcs.getVar(5986)
            35827 -> ctx.varcs.getVar(5987)
            35828 -> ctx.varcs.getVar(5988)
            1416 -> ctx.varcs.getVar(6028)
            1417 -> ctx.varcs.getVar(6029)
            1418 -> ctx.varcs.getVar(6030)
            37132 -> ctx.varcs.getVar(6031)
            1400 -> ctx.varcs.getVar(6034)
            33384 -> ctx.varcs.getVar(5190)
            33490 -> ctx.varcs.getVar(5210)
            33491 -> ctx.varcs.getVar(5211)
            33650 -> ctx.varcs.getVar(5492)
            33658 -> ctx.varcs.getVar(5493)
            33689 -> ctx.varcs.getVar(5494)
            33795 -> ctx.varcs.getVar(5832)
            33907 -> ctx.varcs.getVar(5835)
            33985 -> ctx.varcs.getVar(5838)
            33986 -> ctx.varcs.getVar(5839)
            34188 -> ctx.varcs.getVar(5868)
            34189 -> ctx.varcs.getVar(5869)
            34169 -> ctx.varcs.getVar(5870)
            34878 -> ctx.varcs.getVar(5878)
            34984 -> ctx.varcs.getVar(5883)
            34985 -> ctx.varcs.getVar(5884)
            34986 -> ctx.varcs.getVar(5885)
            35079 -> ctx.varcs.getVar(5908)
            35080 -> ctx.varcs.getVar(5909)
            34747 -> ctx.varcs.getVar(5918)
            35314 -> ctx.varcs.getVar(5919)
            34527 -> ctx.varcs.getVar(5945)
            35781 -> ctx.varcs.getVar(5946)
            35748 -> ctx.varcs.getVar(5966)
            35749 -> ctx.varcs.getVar(5967)
            1408 -> ctx.varcs.getVar(6033)
            37133 -> ctx.varcs.getVar(6032)
            23173 -> ctx.varcs.getVar(6067)
            37208 -> ctx.varcs.getVar(6613)
            37214 -> ctx.varcs.getVar(6614)
            37215 -> ctx.varcs.getVar(6615)
            1625 -> ctx.varcs.getVar(6071)
            1626 -> ctx.varcs.getVar(6076)
            37420 -> ctx.varcs.getVar(6072)
            37421 -> ctx.varcs.getVar(6073)
            37423 -> ctx.varcs.getVar(6075)
            37424 -> ctx.varcs.getVar(6077)
            37659 -> ctx.varcs.getVar(6256)
            6899 -> ctx.varcs.getVar(6295)
            47460 -> ctx.varcs.getVar(7155)
            39027 -> ctx.varcs.getVar(6350)
            39028 -> ctx.varcs.getVar(6351)
            39031 -> ctx.varcs.getVar(6354)
            39032 -> ctx.varcs.getVar(6355)
            39033 -> ctx.varcs.getVar(6356)
            39034 -> ctx.varcs.getVar(6357)
            39035 -> ctx.varcs.getVar(6358)
            39036 -> ctx.varcs.getVar(6359)
            39039 -> ctx.varcs.getVar(6360)
            14899, 14900 -> ctx.varcs.getVar(4681)
            29603, 14903 -> ctx.varcs.getVar(3750)
            14904 -> ctx.varcs.getVar(3751)
            14905, 29604 -> ctx.varcs.getVar(3747)
            44875 -> ctx.varcs.getVar(6824)
            14884 -> ctx.varcs.getVar(3749)
            14901 -> ctx.varcs.getVar(4191)
            19253 -> ctx.varcs.getVar(3739)
            19252 -> ctx.varcs.getVar(3738)
            19251, 46276 -> ctx.varcs.getVar(3745)
            19254, 46275 -> ctx.varcs.getVar(3744)
            23129, 29606, 36922 -> ctx.varcs.getVar(3732)
            24188 -> ctx.varcs.getVar(3902)
            25028 -> ctx.varcs.getVar(3894)
            24189 -> ctx.varcs.getVar(3903)
            24190 -> ctx.varcs.getVar(3904)
            25687 -> ctx.varcs.getVar(3944)
            25688 -> ctx.varcs.getVar(3945)
            920 -> ctx.varcs.getVar(8258)
            40606 -> ctx.varcs.getVar(6514)
            37216 -> ctx.varcs.getVar(6616)
            37217 -> ctx.varcs.getVar(6617)
            37218 -> ctx.varcs.getVar(6618)
            37219 -> ctx.varcs.getVar(6619)
            4549 -> ctx.varcs.getVar(6528)
            39391 -> ctx.varcs.getVar(6377)
            39392 -> ctx.varcs.getVar(6378)
            39437 -> ctx.varcs.getVar(6380)
            39438 -> ctx.varcs.getVar(6381)
            39439 -> ctx.varcs.getVar(6382)
            39440 -> ctx.varcs.getVar(6383)
            40032 -> ctx.varcs.getVar(6411)
            40033 -> ctx.varcs.getVar(6412)
            40034 -> ctx.varcs.getVar(6413)
            40035 -> ctx.varcs.getVar(6414)
            40036 -> ctx.varcs.getVar(6415)
            40237 -> ctx.varcs.getVar(6435)
            40899 -> ctx.varcs.getVar(6482)
            49534 -> ctx.varcs.getVar(7402)
            40939 -> ctx.varcs.getVar(6486)
            41145 -> ctx.varcs.getVar(6494)
            36919 -> ctx.varcs.getVar(6729)
            41146 -> ctx.varcs.getVar(6495)
            41147 -> ctx.varcs.getVar(6496)
            41148 -> ctx.varcs.getVar(6497)
            41887 -> ctx.varcs.getVar(6509)
            41888 -> ctx.varcs.getVar(6510)
            40604 -> ctx.varcs.getVar(6512)
            40605 -> ctx.varcs.getVar(6513)
            27609 -> ctx.varcs.getVar(4117)
            6938 -> ctx.varcs.getVar(6543)
            6939 -> ctx.varcs.getVar(6544)
            6940 -> ctx.varcs.getVar(6545)
            6941 -> ctx.varcs.getVar(6546)
            6942 -> ctx.varcs.getVar(6547)
            6943 -> ctx.varcs.getVar(6548)
            6944 -> ctx.varcs.getVar(6549)
            6945 -> ctx.varcs.getVar(6550)
            6946 -> ctx.varcs.getVar(6551)
            6947 -> ctx.varcs.getVar(6552)
            6948 -> ctx.varcs.getVar(6553)
            6949 -> ctx.varcs.getVar(6554)
            6950 -> ctx.varcs.getVar(6555)
            6951 -> ctx.varcs.getVar(6556)
            6952 -> ctx.varcs.getVar(6557)
            6953 -> ctx.varcs.getVar(6558)
            6954 -> ctx.varcs.getVar(6559)
            6955 -> ctx.varcs.getVar(6560)
            6957 -> ctx.varcs.getVar(6561)
            4286 -> ctx.varcs.getVar(6566)
            24374 -> ctx.varcs.getVar(6644)
            39029 -> ctx.varcs.getVar(6352)
            43721 -> ctx.varcs.getVar(6645)
            14667 -> ctx.varcs.getVar(6646)
            14674 -> ctx.varcs.getVar(6647)
            11652 -> ctx.varcs.getVar(6573)
            30522 -> ctx.varcs.getVar(6581)
            30758 -> ctx.varcs.getVar(6582)
            30759 -> ctx.varcs.getVar(6583)
            30821 -> ctx.varcs.getVar(6584)
            30828 -> ctx.varcs.getVar(6585)
            30964 -> ctx.varcs.getVar(6586)
            31386 -> ctx.varcs.getVar(6587)
            31562 -> ctx.varcs.getVar(6588)
            31918 -> ctx.varcs.getVar(6589)
            31919 -> ctx.varcs.getVar(6590)
            37206 -> ctx.varcs.getVar(6594)
            37207 -> ctx.varcs.getVar(6596)
            34338 -> ctx.varcs.getVar(6725)
            36920 -> ctx.varcs.getVar(6730)
            38070 -> ctx.varcs.getVar(6732)
            38071 -> ctx.varcs.getVar(6733)
            38072 -> ctx.varcs.getVar(6734)
            11602 -> ctx.varcs.getVar(6795)
            38074 -> ctx.varcs.getVar(6736)
            38075 -> ctx.varcs.getVar(6737)
            44892 -> ctx.varcs.getVar(6825)
            36921 -> ctx.varcs.getVar(6731)
            36923 -> ctx.varcs.getVar(6738)
            35946 -> ctx.varcs.getVar(6777)
            35947 -> ctx.varcs.getVar(6778)
            35950 -> ctx.varcs.getVar(6779)
            35951 -> ctx.varcs.getVar(6780)
            29046 -> ctx.varcs.getVar(8260)
            29047 -> ctx.varcs.getVar(8261)
            29048 -> ctx.varcs.getVar(8262)
            29049 -> ctx.varcs.getVar(8263)
            35968 -> ctx.varcs.getVar(6781)
            35990 -> ctx.varcs.getVar(6782)
            11599 -> ctx.varcs.getVar(6794)
            44596 -> ctx.varcs.getVar(6797)
            44340 -> ctx.varcs.getVar(6802)
            44341 -> ctx.varcs.getVar(6803)
            44342 -> ctx.varcs.getVar(6804)
            44343 -> ctx.varcs.getVar(6805)
            44344 -> ctx.varcs.getVar(6806)
            44428 -> ctx.varcs.getVar(6807)
            44429 -> ctx.varcs.getVar(6808)
            44430 -> ctx.varcs.getVar(6809)
            44431 -> ctx.varcs.getVar(6810)
            44432 -> ctx.varcs.getVar(6811)
            44433 -> ctx.varcs.getVar(6812)
            44434 -> ctx.varcs.getVar(6813)
            44698 -> ctx.varcs.getVar(6814)
            44705 -> ctx.varcs.getVar(6815)
            44853 -> ctx.varcs.getVar(6819)
            44854 -> ctx.varcs.getVar(6820)
            44877 -> ctx.varcs.getVar(6828)
            44878 -> ctx.varcs.getVar(6827)
            44879 -> ctx.varcs.getVar(6826)
            44793 -> ctx.varcs.getVar(6862)
            44794 -> ctx.varcs.getVar(6863)
            45036 -> ctx.varcs.getVar(6869)
            45168, 44062, 625 -> ctx.varcs.getVar(6872)
            45167 -> ctx.varcs.getVar(6871)
            44996 -> ctx.varcs.getVar(6898)
            44997 -> ctx.varcs.getVar(6899)
            44998 -> ctx.varcs.getVar(6900)
            44999 -> ctx.varcs.getVar(6901)
            32590 -> ctx.varcs.getVar(6902)
            29217 -> ctx.varcs.getVar(6905)
            4574 -> ctx.varcs.getVar(6904)
            45116 -> ctx.varcs.getVar(6907)
            44040 -> ctx.varcs.getVar(6912)
            46309, 46308 -> ctx.varcs.getVar(7122)
            44066 -> ctx.varcs.getVar(6913)
            44068 -> ctx.varcs.getVar(6914)
            44820 -> ctx.varcs.getVar(6915)
            44898 -> ctx.varcs.getVar(6916)
            45399 -> ctx.varcs.getVar(6956)
            45447 -> ctx.varcs.getVar(6958)
            45401 -> ctx.varcs.getVar(6959)
            45402 -> ctx.varcs.getVar(6960)
            45448 -> ctx.varcs.getVar(6973)
            45275 -> ctx.varcs.getVar(6978)
            45357 -> ctx.varcs.getVar(6977)
            45383 -> ctx.varcs.getVar(6979)
            45539 -> ctx.varcs.getVar(6980)
            45692 -> ctx.varcs.getVar(6981)
            45635 -> ctx.varcs.getVar(6982)
            45693 -> ctx.varcs.getVar(6983)
            44876 -> ctx.varcs.getVar(6984)
            35940 -> ctx.varcs.getVar(6985)
            45559 -> ctx.varcs.getVar(6993)
            45800, 28927 -> ctx.varcs.getVar(6992)
            45797 -> ctx.varcs.getVar(6994)
            46029 -> ctx.varcs.getVar(7041)
            46030 -> ctx.varcs.getVar(7042)
            46031 -> ctx.varcs.getVar(7043)
            46032 -> ctx.varcs.getVar(7044)
            46033 -> ctx.varcs.getVar(7045)
            46034 -> ctx.varcs.getVar(7046)
            43673 -> ctx.varcs.getVar(7052)
            43678 -> ctx.varcs.getVar(7053)
            43682 -> ctx.varcs.getVar(7054)
            46210 -> ctx.varcs.getVar(7062)
            46196 -> ctx.varcs.getVar(7063)
            46272 -> ctx.varcs.getVar(7066)
            46273 -> ctx.varcs.getVar(7067)
            41800 -> ctx.varcs.getVar(7064)
            41805 -> ctx.varcs.getVar(7065)
            46213 -> ctx.varcs.getVar(7068)
            49551 -> ctx.varcs.getVar(7069)
            47053 -> ctx.varcs.getVar(7091)
            47054 -> ctx.varcs.getVar(7092)
            47182 -> ctx.varcs.getVar(7112)
            46302 -> ctx.varcs.getVar(7117)
            47454 -> ctx.varcs.getVar(7120)
            47455 -> ctx.varcs.getVar(7121)
            47203 -> ctx.varcs.getVar(7123)
            47831, 47832 -> ctx.varcs.getVar(7124)
            49096 -> ctx.varcs.getVar(7352)
            49097 -> ctx.varcs.getVar(7351)
            48335 -> ctx.varcs.getVar(7231)
            48336 -> ctx.varcs.getVar(7236)
            48337 -> ctx.varcs.getVar(7241)
            32349 -> ctx.varcs.getVar(7795)
            48309 -> ctx.varcs.getVar(3746)
            48334 -> ctx.varcs.getVar(7246)
            48339 -> ctx.varcs.getVar(7265)
            48341 -> ctx.varcs.getVar(7274)
            48342 -> ctx.varcs.getVar(7277)
            48343 -> ctx.varcs.getVar(7280)
            48347 -> ctx.varcs.getVar(7283)
            49073 -> ctx.varcs.getVar(7349)
            48351 -> ctx.varcs.getVar(7288)
            39622 -> ctx.varcs.getVar(7338)
            48348 -> ctx.varcs.getVar(7285)
            48349 -> ctx.varcs.getVar(7287)
            48291 -> ctx.varps.getVar(11309)
            48283 -> ctx.varcs.getVar(7289)
            48284 -> ctx.varcs.getVar(7290)
            48285 -> ctx.varcs.getVar(7291)
            48844 -> ctx.varcs.getVar(7341)
            48845 -> ctx.varcs.getVar(7342)
            48846 -> ctx.varcs.getVar(7343)
            48847 -> ctx.varcs.getVar(7344)
            48848 -> ctx.varcs.getVar(7345)
            48849 -> ctx.varcs.getVar(7346)
            49070 -> ctx.varcs.getVar(7350)
            48878 -> ctx.varcs.getVar(7357)
            49535 -> ctx.varcs.getVar(7406)
            49536 -> ctx.varcs.getVar(7407)
            49537 -> ctx.varcs.getVar(7408)
            51667 -> ctx.varcs.getVar(8301)
            49540 -> ctx.varcs.getVar(7409)
            49538 -> ctx.varcs.getVar(7410)
            49541 -> ctx.varcs.getVar(7412)
            49563 -> ctx.varcs.getVar(7403)
            49562 -> ctx.varcs.getVar(7404)
            49913 -> ctx.varcs.getVar(7771)
            50084 -> ctx.varcs.getVar(7783)
            50085 -> ctx.varcs.getVar(7785)
            50069 -> ctx.varcs.getVar(7784)
            50070 -> ctx.varcs.getVar(7782)
            50065 -> ctx.varcs.getVar(7786)
            50064 -> ctx.varcs.getVar(7787)
            50067 -> ctx.varcs.getVar(7788)
            50068 -> ctx.varcs.getVar(7789)
            50228 -> ctx.varcs.getVar(7797)
            50241 -> ctx.varcs.getVar(7798)
            50246 -> ctx.varcs.getVar(7799)
            50247 -> ctx.varcs.getVar(7800)
            50248 -> ctx.varcs.getVar(7801)
            29054 -> ctx.varcs.getVar(8259)
            51129 -> ctx.varcs.getVar(8282)
            51130 -> ctx.varcs.getVar(8283)
            51272 -> ctx.varcs.getVar(8294)
            51665 -> ctx.varcs.getVar(8303)
            51666 -> ctx.varcs.getVar(8302)
            52782, 52785, 52790 -> ctx.varcs.getVar(3746)
            51841 -> ctx.varcs.getVar(8316)
            51848 -> ctx.varcs.getVar(8317)
            51842 -> ctx.varcs.getVar(8318)
            51843 -> ctx.varcs.getVar(8319)
            51844 -> ctx.varcs.getVar(8320)
            51845 -> ctx.varcs.getVar(8321)
            51856 -> ctx.varcs.getVar(8322)
            51846 -> ctx.varcs.getVar(8323)
            52061 -> ctx.varcs.getVar(8324)
            52062 -> ctx.varcs.getVar(8325)
            52065 -> ctx.varcs.getVar(8326)
            52063 -> ctx.varcs.getVar(8327)
            50083 -> ctx.varcs.getVar(8328)
            52080 -> ctx.varcs.getVar(8330)
            52238 -> ctx.varcs.getVar(8341)
            52239 -> ctx.varcs.getVar(8342)
            52240 -> ctx.varcs.getVar(8343)
            52319 -> ctx.varcs.getVar(8344)
            52318 -> ctx.varcs.getVar(8345)
            52658 -> ctx.varcs.getVar(8370)
            52792 -> ctx.varcs.getVar(8386)
            52793 -> ctx.varcs.getVar(8387)
            52802 -> ctx.varcs.getVar(8388)
            52801 -> ctx.varcs.getVar(8389)
            52778 -> ctx.varcs.getVar(8394)
            52779 -> ctx.varcs.getVar(8395)
            52776 -> ctx.varcs.getVar(8396)
            14662 -> ctx.varcs.getVar(8399)
            53002 -> ctx.varcs.getVar(8400)
            53033, 53034, 53035, 53036, 53037, 53038 -> ctx.varcs.getVar(8401)
            53039 -> ctx.varcs.getVar(8402)
            53040 -> ctx.varcs.getVar(8403)
            53041 -> ctx.varcs.getVar(8404)
            53003 -> ctx.varcs.getVar(8405)
            45563 -> ctx.varcs.getVar(8406)
            53077 -> ctx.varcs.getVar(8407)
            53078 -> ctx.varcs.getVar(8408)
            2907 -> ctx.varcs.getVar(8410)
            else -> 0
        }
    }
    fun activeOnOpponent(structId: Int, ctx: CombatContext): Boolean {
        return when (structId) {
            51666 -> ctx.varps.getVarBit(58052) == 1
            14683 -> ctx.varps.getVarBit(1913) == 1
            14687 -> ctx.varps.getVarBit(1914) == 1
            14690 -> ctx.varps.getVarBit(1915) == 1
            14710 -> ctx.varps.getVarBit(1916) == 1
            14711 -> ctx.varps.getVarBit(1917) == 1
            14713 -> ctx.varps.getVarBit(1919) == 1
            14714 -> ctx.varps.getVarBit(1920) == 1
            14716 -> ctx.varps.getVarBit(1921) == 1
            14717 -> ctx.varps.getVarBit(1922) == 1
            14718 -> ctx.varps.getVarBit(1923) == 1
            14719 -> ctx.varps.getVarBit(1924) == 1
            14720 -> ctx.varps.getVarBit(1925) == 1
            14721 -> ctx.varps.getVarBit(1926) == 1
            44244 -> ctx.varps.getVarBit(1927) == 1
            14706 -> ctx.varps.getVarBit(1929) == 1
            14707 -> ctx.varps.getVarBit(1930) == 1
            14708 -> ctx.varps.getVarBit(1931) == 1
            51665 -> ctx.varps.getVarBit(58053) == 1
            14729 -> ctx.varps.getVarBit(1933) == 1
            14732 -> ctx.varps.getVarBit(1934) == 1
            14734 -> ctx.varps.getVarBit(1935) == 1
            14731 -> ctx.varps.getVarBit(2036) == 1
            14739 -> ctx.varps.getVarBit(1936) == 1
            14745 -> ctx.varps.getVarBit(1937) == 1
            14749 -> ctx.varps.getVarBit(1938) == 1
            14784 -> ctx.varps.getVarBit(1939) == 1
            14787 -> ctx.varps.getVarBit(1940) == 1
            14792 -> ctx.varps.getVarBit(1941) == 1
            14667 -> ctx.varps.getVarBit(1943) == 1
            14672 -> ctx.varps.getVarBit(1944) == 1
            14673 -> ctx.varps.getVarBit(1945) == 1
            14674 -> ctx.varps.getVarBit(1946) == 1
            14712 -> ctx.varps.getVarBit(1918) == 1
            14704 -> ctx.varps.getVarBit(1947) == 1
            14666 -> ctx.varps.getVarBit(1949) == 1
            14670 -> ctx.varps.getVarBit(1950) == 1
            39030 -> ctx.varps.getVarBit(1951) == 1
            14883 -> ctx.varps.getVarBit(1910) == 1
            14691 -> ctx.varps.getVarBit(1952) == 1
            14572 -> ctx.varps.getVarBit(1962) == 1
            14573 -> ctx.varps.getVarBit(1963) == 1
            14575 -> ctx.varps.getVarBit(1964) == 1
            14580 -> ctx.varps.getVarBit(1968) == 1
            14581 -> ctx.varps.getVarBit(1969) == 1
            14582 -> ctx.varps.getVarBit(1970) == 1
            14579 -> ctx.varps.getVarBit(1983) == 1
            14568 -> ctx.varps.getVarBit(1984) == 1
            14569 -> ctx.varps.getVarBit(1985) == 1
            14574 -> ctx.varps.getVarBit(1986) == 1
            14570 -> ctx.varps.getVarBit(1987) == 1
            14571 -> ctx.varps.getVarBit(1988) == 1
            14608 -> ctx.varps.getVarBit(2017) == 1
            14609 -> ctx.varps.getVarBit(1996) == 1
            14610 -> ctx.varps.getVarBit(1997) == 1
            14583 -> ctx.varps.getVarBit(1998) == 1
            14589 -> ctx.varps.getVarBit(2002) == 1
            14592 -> ctx.varps.getVarBit(2003) == 1
            14593 -> ctx.varps.getVarBit(2004) == 1
            14604 -> ctx.varps.getVarBit(2013) == 1
            14605 -> ctx.varps.getVarBit(2014) == 1
            14885 -> ctx.varps.getVarBit(2018) == 1
            14886 -> ctx.varps.getVarBit(2019) == 1
            14887 -> ctx.varps.getVarBit(2020) == 1
            14888 -> ctx.varps.getVarBit(2021) == 1
            14889 -> ctx.varps.getVarBit(2022) == 1
            14890 -> ctx.varps.getVarBit(2023) == 1
            14891 -> ctx.varps.getVarBit(2024) == 1
            14892 -> ctx.varps.getVarBit(2025) == 1
            14895 -> ctx.varps.getVarBit(2026) == 1
            14896 -> ctx.varps.getVarBit(2027) == 1
            14897 -> ctx.varps.getVarBit(2028) == 1
            14898 -> ctx.varps.getVarBit(2029) == 1
            14893 -> ctx.varps.getVarBit(2030) == 1
            14894 -> ctx.varps.getVarBit(2031) == 1
            14899 -> ctx.varps.getVarBit(2032) == 1
            14900 -> ctx.varps.getVarBit(2033) == 1
            14903 -> ctx.varps.getVarBit(2034) == 1
            14904 -> ctx.varps.getVarBit(2035) == 1
            14905 -> ctx.varps.getVarBit(2037) == 1
            14884 -> ctx.varps.getVarBit(1911) == 1
            14693 -> ctx.varps.getVarBit(2052) == 2
            14694 -> ctx.varps.getVarBit(2052) == 3
            14695 -> ctx.varps.getVarBit(2052) == 4
            14901 -> ctx.varps.getVarBit(2054) == 1
            14920 -> ctx.varps.getVarBit(2055) == 1
            19828 -> ctx.varps.getVarBit(18549) == 1
            23129 -> ctx.varps.getVarBit(20383) == 1
            28178 -> ctx.varps.getVarBit(22458) == 1
            28180 -> ctx.varps.getVarBit(22457) == 1
            24374 -> ctx.varps.getVarBit(23303) == 1
            29605 -> ctx.varps.getVarBit(25842) == 1
            29606 -> ctx.varps.getVarBit(25843) == 1
            29607 -> ctx.varps.getVarBit(25844) == 1
            14865 -> ctx.varps.getVarBit(26431) == 1
            31986 -> ctx.varps.getVarBit(28637) == 1
            31985 -> ctx.varps.getVarBit(28638) == 1
            31982 -> ctx.varps.getVarBit(28639) == 1
            30956 -> ctx.varps.getVarBit(29797) == 1
            33650 -> ctx.varps.getVarBit(32613) == 1
            33658 -> ctx.varps.getVarBit(32614) == 1
            34984 -> ctx.varps.getVarBit(34308) == 1
            34985 -> ctx.varps.getVarBit(34309) == 1
            34986 -> ctx.varps.getVarBit(34310) == 1
            35360 -> ctx.varps.getVarBit(34863) == 1
            35362 -> ctx.varps.getVarBit(34865) == 1
            35361 -> ctx.varps.getVarBit(34864) == 1
            35798 -> ctx.varps.getVarBit(35308) == 1
            37401 -> ctx.varps.getVarBit(35397) == 1
            37403 -> ctx.varps.getVarBit(35399) == 1
            37402 -> ctx.varps.getVarBit(35398) == 1
            1489 -> ctx.varps.getVarBit(55117) >= 1
            37659 -> ctx.varps.getVarBit(36804) == 1
            39040 -> ctx.varps.getVarBit(38913) == 1
            39041 -> ctx.varps.getVarBit(38914) == 1
            39140 -> ctx.varps.getVarBit(38966) > 0
            39242 -> ctx.varps.getVarBit(39314) == 1
            39243 -> ctx.varps.getVarBit(39315) == 1
            39244 -> ctx.varps.getVarBit(39316) == 1
            39784 -> ctx.varps.getVarBit(40076) == 1
            14684 -> ctx.varps.getVarBit(41440) == 1
            40936 -> ctx.varps.getVarBit(41441) == 1
            14701 -> ctx.varps.getVarBit(41445) == 1
            41143 -> ctx.varps.getVarBit(41573) == 1
            41144 -> ctx.varps.getVarBit(41574) == 1
            4550 -> ctx.varps.getVarBit(43412) > 0
            30521 -> ctx.varps.getVarBit(44230) == 1
            35992 -> ctx.varps.getVarBit(46014) == 1
            35993 -> ctx.varps.getVarBit(46015) == 1
            36000 -> ctx.varps.getVarBit(46016) == 1
            36001 -> ctx.varps.getVarBit(46017) == 1
            35898 -> ctx.varps.getVarBit(46018) == 1
            44875 -> ctx.varps.getVarBit(48028) == 1
            45047 -> ctx.varps.getVarBit(48686) == 1
            45045 -> ctx.varps.getVarBit(48685) == 1
            44912 -> ctx.varps.getVarBit(49448) == 1
            44946 -> ctx.varps.getVarBit(49449) == 1
            45400 -> ctx.varps.getVarBit(49723) == 1
            45605 -> ctx.varps.getVarBit(41541) == 1
            45117 -> ctx.varps.getVarBit(21556) == 1
            45797 -> ctx.varps.getVarBit(50328) == 1
            51672 -> ctx.varps.getVarBit(58068) == 1
            45567 -> ctx.varps.getVarBit(50329) == 1
            3694 -> ctx.varps.getVarBit(51063) == 1
            43673 -> ctx.varps.getVarBit(4332) > 0
            41807 -> ctx.varps.getVarBit(51434) == 1
            46211 -> ctx.varps.getVarBit(51435) == 1
            41808 -> ctx.varps.getVarBit(51436) == 1
            46279 -> ctx.varps.getVarBit(51431) == 1
            46272 -> ctx.varps.getVarBit(51432) == 1
            41810 -> ctx.varps.getVarBit(51437) == 1
            41811 -> ctx.varps.getVarBit(51438) == 1
            46377 -> ctx.varps.getVarBit(51704) == 1
            47202 -> ctx.varps.getVarBit(52819) == 1
            48368 -> ctx.varps.getVarBit(53229) == 1
            48375 -> ctx.varps.getVarBit(53239) == 1
            48376 -> ctx.varps.getVarBit(53240) == 1
            48288 -> ctx.varps.getVarBit(53244) == 1
            48289 -> ctx.varps.getVarBit(53242) == 1
            48290 -> ctx.varps.getVarBit(53243) == 1
            48338 -> ctx.varps.getVarBit(53245) == 1
            48344 -> ctx.varps.getVarBit(53246) == 1
            48345 -> ctx.varps.getVarBit(53247) == 1
            48346 -> ctx.varps.getVarBit(53248) == 1
            48283 -> ctx.varps.getVarBit(53249) == 1
            48284 -> ctx.varps.getVarBit(53250) == 1
            48285 -> ctx.varps.getVarBit(53251) == 1
            49074 -> ctx.varps.getVarBit(54672) == 1
            49071 -> ctx.varps.getVar(11534) >= 1
            49552 -> ctx.varps.getVarBit(55118) == 1
            50077 -> ctx.varps.getVarBit(55725) == 1
            50067 -> ctx.varps.getVarBit(55726) == 1
            50069 -> ctx.varps.getVarBit(55727) == 1
            50068 -> ctx.varps.getVarBit(55728) == 1
            50228 -> ctx.varps.getVarBit(55985) == 1
            50696 -> ctx.varps.getVarBit(56288) == 1
            else -> false
        }
    }
    fun timeRemainingMs(structId: Int, ctx: CombatContext): Long {
        val buffTimeClientCycles = expiryCycle(structId, ctx)
        if (buffTimeClientCycles == 0) return 0
        val remainingCycles = buffTimeClientCycles - ctx.clock.now()
        if (remainingCycles <= 0) return 0
        val buffTimeMilliseconds = (remainingCycles + 1) * 20L
        return buffTimeMilliseconds
    }
    fun stacks(structId: Int, ctx: CombatContext): Int {
        return when (structId) {
            14718 -> ctx.varps.getVarBit(1898)
            24333 -> ctx.varps.getVar(4678)
            31982 -> ctx.varps.getVarBit(28640)
            14675 -> ctx.varps.getVarBit(28708)
            32253 -> ctx.varps.getVarBit(29113)
            32249 -> ctx.varps.getVarBit(29115)
            32248 -> ctx.varps.getVarBit(29114)
            33651 -> ctx.varps.getVarBit(32623)
            33655, 33656, 33657, 28853 -> ctx.varps.getVarBit(32637)
            34984 -> ctx.varps.getVarBit(34314)
            32631 -> scale(ctx.varps.getVarBit(29799), 255, 100)
            28501 -> ctx.varps.getVarBit(1895)
            24189 -> scale(ctx.varps.getVar(6500), maxOf(1, transfigureValue()), 100)
            35799 -> ctx.varps.getVarBit(28719)
            35800 -> ctx.varps.getVarBit(28720)
            35801 -> ctx.varps.getVarBit(28721)
            35802, 35803 -> ctx.varps.getVarBit(28722)
            35804 -> scale(ctx.varps.getVarBit(30984), 50000, 100)
            40797 -> ctx.varps.getVarBit(41298)
            35816 -> scale(ctx.varps.getVarBit(1897) * 20, 100, 100)
            35817 -> ctx.varps.getVarBit(34871)
            35826 -> scale(ctx.varps.getVar(6091), 500000, 100)
            1392 -> ctx.varps.getVarBit(35735) * 10
            1408 -> ctx.varps.getVarBit(35737)
            1489 -> ctx.varps.getVar(12679)
            23174 -> ctx.varps.getVarBit(36217)
            1624 -> (ctx.wornObjVar(2, 30214)) + (ctx.wornObjVar(17, 20171))
            1626 -> ctx.varps.getVarBit(36378)
            37424 -> ctx.varps.getVarBit(36042) / 5
            37425 -> 5
            31984 -> ctx.varps.getVarBit(38922)
            39037 -> ctx.varps.getVar(2735)
            39038 -> scale(ctx.varps.getVarBit(521) - ctx.varps.getVar(183), maxOf(1, ctx.varps.getVarBit(521)), 50 / 10)
            39140 -> ctx.varps.getVarBit(38967)
            39440 -> ctx.varps.getVarBit(39880)
            39571 -> minOf(4, ctx.varps.getVar(7845))
            39572 -> -1 * maxOf(-4, ctx.varps.getVar(7845))
            40124 -> ctx.varps.getVarBit(40601)
            40525 -> ctx.varps.getVarBit(41009)
            40798 -> ctx.varps.getVarBit(41299)
            680 -> ctx.varps.getVarBit(42160) / 10
            6938 -> ctx.varps.getVarBit(43703)
            6939 -> ctx.varps.getVarBit(43704)
            6940 -> ctx.varps.getVarBit(43692)
            6941 -> ctx.varps.getVarBit(43693)
            6942 -> ctx.varps.getVarBit(43694)
            6943 -> ctx.varps.getVarBit(43705)
            6944 -> ctx.varps.getVarBit(43695)
            6945 -> ctx.varps.getVarBit(43696)
            6946 -> ctx.varps.getVarBit(43706)
            6947 -> ctx.varps.getVarBit(43697)
            6948 -> ctx.varps.getVarBit(43707)
            6949 -> ctx.varps.getVarBit(43708)
            6950 -> ctx.varps.getVarBit(43698)
            6951 -> ctx.varps.getVarBit(43709)
            6952 -> ctx.varps.getVarBit(43699)
            6953 -> ctx.varps.getVarBit(43700)
            6954 -> ctx.varps.getVarBit(43701)
            6955 -> ctx.varps.getVarBit(43702)
            37130 -> ctx.varps.getVar(4574)
            35990 -> ctx.varps.getVarBit(47360)
            35991 -> ctx.varps.getVar(9307)
            44938 -> ctx.varps.getVar(9588)
            44939 -> ctx.varps.getVar(9589)
            44879 -> ctx.varps.getVarBit(48179)
            45035 -> ctx.varps.getVar(9667)
            45036 -> ctx.varps.getVar(9668)
            44996 -> ctx.varps.getVarBit(49291)
            44040 -> ctx.varps.getVar(9911)
            44042 -> ctx.varps.getVarBit(49527)
            44066 -> ctx.varps.getVar(9912)
            44820 -> ctx.varps.getVar(9913)
            45584 -> ctx.varps.getVarBit(50198)
            45275 -> ctx.varps.getVarBit(21565)
            45635 -> ctx.varps.getVar(10224)
            45557 -> ctx.varps.getVar(10254)
            45558 -> ctx.varps.getVar(10255)
            45559 -> ctx.varps.getVar(10256)
            45560 -> ctx.varps.getVar(10259)
            45561 -> ctx.varps.getVar(10257)
            516 -> ctx.varps.getVarBit(30947)
            517 -> ctx.varps.getVarBit(30948)
            32755 -> ctx.varps.getVarBit(30949)
            33226 -> ctx.varps.getVarBit(34893)
            33227 -> ctx.varcs.getVar(4276)
            34499 -> ctx.varcs.getVar(4277)
            46026 -> ctx.varps.getVar(10326)
            46027 -> ctx.varps.getVarBit(50812)
            3694 -> ctx.varps.getVarBit(51103)
            19620 -> 4 * bitCount(ctx.varps.getVarBit(51205))
            43673 -> ctx.varps.getVar(10434) * 3
            43678 -> ctx.varps.getVar(10432)
            43682 -> ctx.varps.getVar(10433)
            41787 -> ctx.varps.getVarBit(51494)
            46210 -> ctx.varps.getVarBit(51508)
            41806 -> ctx.varps.getVarBit(51509)
            46198 -> ctx.varps.getVar(10540)
            46199 -> ctx.varps.getVar(10541)
            46201 -> ctx.varps.getVar(10543)
            46203 -> ctx.varps.getVar(10545)
            46193 -> ctx.varps.getVar(10534)
            46194 -> ctx.varps.getVar(10537)
            46195 -> ctx.varps.getVar(10538)
            46197 -> ctx.varps.getVar(10539)
            29050 -> ctx.varps.getVarBit(56961)
            47182 -> ctx.varcs.getVar(7112)
            34898 -> ctx.varps.getVarBit(52528)
            48333 -> ctx.varps.getVar(10986)
            48334 -> ctx.varps.getVar(11035)
            48335 -> ctx.varps.getVar(10997)
            32349 -> ctx.varps.getVar(11823)
            48340 -> boneShieldLevel(ctx.varps.getVar(11065), ctx)
            48338 -> ctx.varps.getVar(11044)
            47806 -> ctx.varps.getVar(10951)
            47807 -> ctx.varps.getVar(10952)
            48350 -> ctx.varps.getVar(11085) % 5
            47801 -> ctx.varps.getVar(10936) % 6
            48850 -> ctx.varps.getVarBit(54609)
            49071 -> ctx.varps.getVar(11534)
            49074 -> ctx.varps.getVar(11545)
            49132 -> ctx.varps.getVar(8422)
            49133 -> ctx.varps.getVar(8423)
            49555 -> ctx.varps.getVarBit(27010)
            49562 -> ctx.varps.getVar(11612)
            49912 -> ctx.varps.getVar(11750)
            49998 -> ctx.varps.getVar(11770)
            50083 -> ctx.varps.getVar(11776)
            49999 -> ctx.varps.getVar(11771)
            50063 -> ctx.varps.getVar(11774)
            50207, 51496 -> ctx.varps.getVar(11834)
            50212 -> ctx.varps.getVarBit(55992)
            19673 -> ctx.varps.getVarBit(56861) * 10
            1869 -> ctx.varps.getVarBit(60643)
            6850 -> ctx.wornObjVar(2, 30214)
            14885 -> ctx.statLevel(SKILL_ATTACK, false) - ctx.statLevel(SKILL_ATTACK, true)
            14886 -> ctx.statLevel(SKILL_ATTACK, true) - ctx.statLevel(SKILL_ATTACK, false)
            14887 -> ctx.statLevel(SKILL_STRENGTH, false) - ctx.statLevel(SKILL_STRENGTH, true)
            14888 -> ctx.statLevel(SKILL_STRENGTH, true) - ctx.statLevel(SKILL_STRENGTH, false)
            14889 -> ctx.statLevel(SKILL_DEFENSE, false) - ctx.statLevel(SKILL_DEFENSE, true)
            14890 -> ctx.statLevel(SKILL_DEFENSE, true) - ctx.statLevel(SKILL_DEFENSE, false)
            14891 -> ctx.statLevel(SKILL_RANGED, false) - ctx.statLevel(SKILL_RANGED, true)
            14892 -> ctx.statLevel(SKILL_RANGED, true) - ctx.statLevel(SKILL_RANGED, false)
            14895 -> ctx.statLevel(SKILL_MAGIC, false) - ctx.statLevel(SKILL_MAGIC, true)
            14896 -> ctx.statLevel(SKILL_MAGIC, true) - ctx.statLevel(SKILL_MAGIC, false)
            23175 -> ctx.statLevel(SKILL_CONSTITUTION, true) - ctx.statLevel(SKILL_CONSTITUTION, false)
            39959 -> ctx.statLevel(SKILL_WOODCUTTING, true) - ctx.statLevel(SKILL_WOODCUTTING, false)
            40253 -> ctx.statLevel(SKILL_MINING, true) - ctx.statLevel(SKILL_MINING, false)
            40381 -> ctx.statLevel(SKILL_FISHING, true) - ctx.statLevel(SKILL_FISHING, false)
            40414 -> ctx.statLevel(SKILL_HUNTER, true) - ctx.statLevel(SKILL_HUNTER, false)
            48289 -> ctx.statLevel(SKILL_NECROMANCY, false) - ctx.statLevel(SKILL_NECROMANCY, true)
            48290 -> ctx.statLevel(SKILL_NECROMANCY, true) - ctx.statLevel(SKILL_NECROMANCY, false)
            51841 -> ctx.varps.getVar(12264)
            51848 -> ctx.varps.getVar(12268)
            51850 -> ctx.varps.getVar(12277)
            52064 -> ctx.varps.getVar(12291) % 4
            52333 -> ctx.varps.getVar(12437)
            52342 -> ctx.varps.getVar(2735)
            52791 -> ctx.varps.getVar(12655)
            53000 -> ctx.varps.getVarBit(60830)
            53001 -> ctx.varps.getVarBit(60831)
            else -> 0
        }
    }
    private fun scale(value: Int, max: Int, targetMax: Int) = (value * targetMax) / max
    private fun transfigureValue(): Int {
        return 1
    }
    private fun boneShieldLevel(structId: Int, ctx: CombatContext): Int {
        val int1 = when (structId) {
            48326 -> 25
            48327 -> 50
            else -> 0
        }
        val int2 = if (ctx.wornParam(13, 8928) == 49089 && ctx.varps.getVarBit(54731) == 2) 15 else 0
        return scale(ctx.statLevel(SKILL_NECROMANCY, true), 100, int1) + int2
    }
}
