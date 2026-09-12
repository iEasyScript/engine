package org.projectx.core

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import world.gregs.voidps.type.Tile
import java.io.File
import java.math.BigInteger

object EnvVars {
    private val dotenv: Dotenv = dotenv {
        ignoreIfMissing = true
    }

    /**
     * The server `run` tasks pin CWD to the repo root, so a relative `DATA_PATH` (`./data`) resolves
     * there. Tests run from a module dir, so also accept the same relative path one level up — the
     * same two-candidate resolution the cache-loading tests use. Absolute paths are honoured as-is.
     */
    private fun resolveDataPath(configured: String): String {
        val direct = File(configured)
        if (direct.isAbsolute || direct.isDirectory) return configured
        val fromParent = File("..", configured.removePrefix("./"))
        return if (fromParent.isDirectory) fromParent.path else configured
    }

    val debug: Boolean = dotenv.get("DEBUG", "false").toBooleanStrict()
    val members: Boolean = dotenv.get("MEMBERS", "true").toBooleanStrict()
    val serverName: String = dotenv.get("SERVER_NAME", "Project X")
    val multiLogWhitelist: String = dotenv.get("MULTILOG_WHITELIST", "localhost,127.0.0.1,::1,0:0:0:0:0:0:0:1")
    val multiLogLimit: Int = dotenv.get("MULTILOG_LIMIT", "3").toInt()
    val js5ServerToken: String = dotenv.get("JS5_SERVER_TOKEN", "ev9+VAp5/tMKeNR/7MOuH6lKWS+rGkHK")
    val loginServerToken: String = dotenv.get("LOGIN_SERVER_TOKEN", "wwGlrZHF5gKN6D3mDdihco3oPeYN2KFybL9hUUFqOvk")
    val clientKey: String = dotenv.get("CLIENT_KEY", "29EDD9FDC775629058FBBF106C5B0E0A3A8028FE0037D1737B8EC3EA2F4E8B8FD6F54EF2F4E65862")
    val js5RsaModulus: String = dotenv.get("RSA_JS5_MODULUS", "607965588982248330233902754760888879244171161418961826903960418513412609766664967574451942600964209987808041308255722791500320489124153698940516919995471220002139601799785952158680888820166280067292720345233736731634993120775356851006057824891699866786027346773480858371704388164471753794304200128393573466002773067682508117814040767925677620462514901062196110607534068452908174613686563526508450752942067149657220307868117668884777486999733376497895071133451664509996931083306160454074974443686914884748192238672150072195674547942839469313907534033197375444776196145933319579262233136997562488850188874395834545929991334208906108182369743279293033798709279624769646397338646881066433314080604090555935009157445196380152335080031142782622168575293272850922070163635389108696373624243025153138996865315048565734441425749040313780033218000924617324891126545204145866635724377015424668843559454127185566450652722973164916810697337987323202282941562751439572720529752776985897334000998969994008572387377023861597284919891061469589489128290776090498500279846599015507628119581019718447034066717290620579835638341917462965858784957333271481729655108715179885916521695451903242151351621566668972400218027343367139044105431340870051476517853")
    val js5RsaExponent: String = dotenv.get("RSA_JS5_EXPONENT", "101484528618468211402358021596890826810983436274670608143073393478982950054089645223653976212962116303982834427910806771874141798844865654942773089636700738193972972653450850208705452698024231538919473540088358811204417596060274503117224180079328831311662765565484950796830081944890365268035451322986917242545490893192848592121338365975173287584948920287702517677323814415638527903780728189253716589476142928429170939515086748984272159442838903299553650677207956525676166544373385550566347279709537011313060348246237297287068841049448068792084165363682667623205065715358866485607122922325847753748246848935285883910418283827392694028864603091203670147432139992897297759704786170018662364670059046234296105138228847113162199489522404409830366341796786869874057956597446275314693491800892616448078866175834005951198379371069080975205033277213084429233685431010500721329791543584652930226230171730641530564591741618628256421424666435735966999944457901951965532918810076289238141854712085248631520518271594260322470243512999067445192413491202685543061699539967644748435535161033928886752305338104061215163782802022790771032515597900063260477936295403434742783268674644319659865790488269230395861986668804228797277777503572137561419619017")
    val loginRsaModulus: String = dotenv.get("RSA_LOGIN_MODULUS", "111451331001887558258925086433184522962620313279302629231414920307603218477441933165186052978804326289285676150340879072290510255271162395882754221854449961272233234909169893946939423821658569017156126420112007996521313178880507779240372698073063645756262828071730223699075458202798234856769948825293382469811")
    val loginRsaExponent: String = dotenv.get("RSA_LOGIN_EXPONENT", "34914739411321138572317789181068883851039224437147905469432224984900158361846900679226007502751600211259658163215414013964576743686348706072429725634278525427017564689901284874210013603050464461928938061809313977967058811863752711680183867996600121657804039275555182951457451665922088955464331374498986883785")
    val worldRsaModulus: String = dotenv.get("RSA_WORLD_MODULUS", loginRsaModulus)
    val worldRsaExponent: String = dotenv.get("RSA_WORLD_EXPONENT", loginRsaExponent)
    val loginRsaModulusHex: String get() = BigInteger(loginRsaModulus).toString(16)
    val js5RsaModulusHex: String get() = BigInteger(js5RsaModulus).toString(16)
    val lobbyPort: Int = dotenv.get("LOBBY_PORT", "43594").toInt()
    // RS3 dials every world on the SAME fixed game port (the lobby's port) and distinguishes worlds
    // only by HOST — the play-button CS2 computes the connect port itself and ignores any port we put
    // in the worldlist/login data. So lobby and world must share the game port and be split by bind
    // HOST: lobby on 127.0.0.1, world on 127.0.0.2 (127.0.0.0/8 all route to loopback, no hosts file
    // needed). The old separate WORLD_PORT 43595 was never actually dialled by the client.
    val lobbyHost: String = dotenv.get("LOBBY_HOST", "127.0.0.1")
    // Address advertised to the client in jav_config (codebase, JS5, game host). Defaults to the
    // lobby bind host so loopback dev is unchanged; set to the machine's LAN IP for external clients.
    val publicHost: String = dotenv.get("PUBLIC_HOST", lobbyHost)
    val worldPort: Int = dotenv.get("WORLD_PORT", lobbyPort.toString()).toInt()
    val dataPath: String = resolveDataPath(dotenv.get("DATA_PATH", "./data"))
    val cachePath: String = dotenv.get("CACHE_PATH", "$dataPath/cache")

    /**
     * A cache source tree to serve from instead of the prepacked cache. Unset by default, so the
     * server serves [cachePath] as it always has; when set, `CacheSource.ensureBuilt` packs the tree
     * into [cacheBuildPath] on startup and the server serves that build.
     */
    val cacheSourcePath: String? = dotenv.get("CACHE_SOURCE_PATH")?.takeIf { it.isNotBlank() }?.let(::resolveDataPath)
    val cacheBuildPath: String = dotenv.get("CACHE_BUILD_PATH", "$dataPath/cache-build")

    /**
     * Base folder for the RE gameval id<->name dictionaries. An explicit `GAMEVAL_PATH` wins;
     * otherwise [ProjectPaths] locates the repo's `re-resources/gamevals` (works for the server AND
     * the injected engine), falling back to `~/.projectx/gamevals` when the working tree isn't
     * present. A folder always beats the copy packaged into the jar, so editing the working tree
     * still changes names without a rebuild - see `Gameval.Source`.
     */
    val gamevalPath: String = dotenv.get("GAMEVAL_PATH")?.let(::resolveDataPath)
        ?: ProjectPaths.resolve("re-resources/gamevals", "gamevals").path
    val memCache: Boolean = dotenv.get("MEM_CACHE", "false").toBooleanStrict()
    val majorVersion: Int = dotenv.get("MAJOR_VERSION", "950").toInt()
    val minorVersion: Int = dotenv.get("MINOR_VERSION", "1").toInt()
    val cacheThreadUsage: Double = dotenv.get("CACHE_THREAD_USAGE", "1.0").toDouble()
    val configHttpPort: Int = dotenv.get("CONFIG_HTTP_PORT", "8829").toInt()
    val clientBinaryPath: String = dotenv.get("CLIENT_BINARY_PATH", "./data/client/linux/rs2client")
    val logLevel: String = dotenv.get("LOG_LEVEL", "TRACE")
    val packetQueueCapacity: Int = dotenv.get("PACKET_QUEUE_CAPACITY", "100").toInt()
    val packetValidateSizes: Boolean = dotenv.get("PACKET_VALIDATE_SIZES", "true").toBooleanStrict()
    val mongoUri: String = dotenv.get("MONGO_URI", "mongodb://localhost:27017")
    val mongoDatabase: String = dotenv.get("MONGO_DATABASE", "projectx")
    /** When true, [org.projectx.core.mongo.MongoManager] uses a crazy-basic in-process store instead
     *  of connecting to MongoDB (ignores `mongoUri`). Dev convenience so `:lobby:run` works without
     *  any MongoDB install. Off by default to keep prod paths clean. Legacy alias: `EMBEDDED_MONGO`. */
    val mongoInMemory: Boolean =
        (dotenv.get("MONGO_IN_MEMORY") ?: dotenv.get("EMBEDDED_MONGO", "false")).toBooleanStrict()

    /**
     * Resolve a shared secret from the environment. The well-known dev default is only
     * acceptable when DEBUG=true — in production a guessable token would let anyone
     * impersonate a world server / forge login tokens, so fail fast instead.
     */
    private fun requireSecret(name: String, devDefault: String): String {
        val value = dotenv.get(name)
        if (value != null && value.isNotBlank()) return value
        if (debug) return devDefault
        error(
            "Missing required secret $name: set it in the environment or .env. " +
                "The built-in dev default is only used when DEBUG=true."
        )
    }

    // Social gateway (world↔lobby WebSocket communication)
    val socialGatewayUrl: String = dotenv.get("SOCIAL_GATEWAY_URL", "ws://localhost:$configHttpPort/social/ws")
    val socialGatewayToken: String by lazy { requireSecret("SOCIAL_GATEWAY_TOKEN", "projectx-gateway-dev-token") }
    val lobbyApiPort: Int = configHttpPort  // WebSocket gateway runs on the config HTTP port

    // Login token (lobby issues, world verifies)
    val worldLoginTokenSecret: String by lazy { requireSecret("WORLD_LOGIN_TOKEN_SECRET", "projectx-world-login-dev-secret") }
    val worldLoginTokenTtlMs: Long = dotenv.get("WORLD_LOGIN_TOKEN_TTL_MS", "1800000").toLong()  // 30 minutes

    // World server identity (used by world module to register with lobby)
    val worldId: Int = dotenv.get("WORLD_ID", "300").toInt()
    val worldName: String = dotenv.get("WORLD_NAME", "Project X")
    // The world's host: BOTH the bind address (WorldServer binds here) AND the host advertised to the
    // client (worldlist + login data). Must differ from the lobby's host (127.0.0.1) since they share
    // the game port — see [lobbyHost]/[worldPort]. The client dials this host on the fixed game port.
    val worldHost: String = dotenv.get("WORLD_HOST", "127.0.0.2")
    val worldPublicHost: String = dotenv.get("WORLD_PUBLIC_HOST", worldHost)
    val worldActivity: String = dotenv.get("WORLD_ACTIVITY", "")
    val worldMembers: Boolean = dotenv.get("WORLD_MEMBERS", "true").toBooleanStrict()
    val worldQuickChat: Boolean = dotenv.get("WORLD_QUICKCHAT", "false").toBooleanStrict()
    val worldPvp: Boolean = dotenv.get("WORLD_PVP", "false").toBooleanStrict()
    val worldLootShare: Boolean = dotenv.get("WORLD_LOOTSHARE", "true").toBooleanStrict()
    val worldCountry: String = dotenv.get("WORLD_COUNTRY", "USA")
    val worldHighlighted: Boolean = dotenv.get("WORLD_HIGHLIGHTED", "false").toBooleanStrict()

    val spawnTile = Tile(
        dotenv.get("SPAWN_X", "3235").toInt(),
        dotenv.get("SPAWN_Y", "3216").toInt(),
        dotenv.get("SPAWN_PLANE", "0").toInt(),
    )
    val worldBoundsSouthWest = Tile(
        dotenv.get("WORLD_BOUNDS_SW_X", "1664").toInt(),
        dotenv.get("WORLD_BOUNDS_SW_Y", "2368").toInt(),
        dotenv.get("WORLD_BOUNDS_SW_PLANE", "0").toInt(),
    )
    val worldBoundsNorthEast = Tile(
        dotenv.get("WORLD_BOUNDS_NE_X", "4664").toInt(),
        dotenv.get("WORLD_BOUNDS_NE_Y", "9144").toInt(),
        dotenv.get("WORLD_BOUNDS_NE_PLANE", "0").toInt(),
    )
    val worldAreaTypeFallback: Int = dotenv.get("WORLD_AREA_TYPE_FALLBACK", "1").toInt()

    // ISAAC delta: added to XTEA key ints to derive server->client cipher seed
    const val ISAAC_DELTA = 50

    const val JAGEX_LOGIN_RSA_MODULUS_HEX = "aad4a7804c34bb788d52dbd5f70e5721528d7f01c6aa1a93b7322ea0127f40682f2d766a26728f0758ce47c9cde8003a170381352a143320ae3cc884a9116008ec09104ecdbafbcd0f537dfba67c7340ea3ca30caf91c20f8d98ac9b9a613b25cd23f586d3fae88823f1ea48aeeb31c9897c17c45aeb0771521a5c2df3cb0799"
    const val JAGEX_LOGIN_RSA_EXPONENT = 65537

    const val JAGEX_JS5_RSA_MODULUS_HEX = "a6400fbcbd9dd09f48045caf3f543dd6b1c4da6ac89e13e17df3627ddb8a23bf7726849525ee28f7cacca19433a774e859bfbdbb3ee26cf5cf8006bb0eec29e2addc66031ff5fc7a05408772047c5f40bc967539e2423d27dbb655f4f9e94266ec7a9d0386930c001e6a81cf0a0e2881a6bdf3c5c135f8339ce6a012093c98643c1727d18960c4b64aae59364fd0b981ea3899a39bbf5e1c6c2b489537aa4df42800f52be33b73bcdc8379948b0f3a85a3d143aca429e562abe5dd7ef2822c7d90aa23082e2ef901abc92cc80e5bbe40d29894ea8c8c97819debcd219234ad4aa670c23000a443533664126f4861d460ffe3a396237b77fe7ef291b7e21f53526448b0b9483fd703e0465748fc97c7d9fcd9617ea2f8228fa4c2170312705c6a556bdfefd009dedc059e00ca8073cd2bb58d69ab4b84253ed7f78a5384e1b8019b46955d1fa5ec4e41cc43402253a78469b3b30d973e089f186eaa6e691a38c5336c8cee64f1733dcd37e3a6bd962905b68b57d3a9c24a7839bb9aec5a47a8a778616d777f1f99e19e30f825b1177e00d970f3a2ba2c2b3b166bcbb2fbd14ea1bb3b012989ed30e574c7c53bf0cf82d59b9cfaa9e5a0d46591cddb9eedc8e276804c1063f06f377e388363984ed85b2eb500973b943ee4ff68e7de028eb3e10641803acf95a6b358e7fa0175bfda660e492e6a5b901efe99bf55561743dc8299"
    const val JAGEX_JS5_RSA_EXPONENT = 65537
}
