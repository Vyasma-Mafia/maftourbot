package online.mafoverlay

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.mafia.vyasma.polemica.library.client.PolemicaClient
import com.github.mafia.vyasma.polemica.library.client.PolemicaClient.PolemicaCompetitionGameId
import com.github.mafia.vyasma.polemica.library.client.PolemicaClientImpl
import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.mralex1810.gomafia.GomafiaRestClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.resources
import io.ktor.server.http.content.static
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.thymeleaf.respondTemplate
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import kotlinx.coroutines.runBlocking

fun main() {
    val logger = LoggerFactory.getLogger("mafoverlay")
    val config = HoconApplicationConfig(ConfigFactory.load())

    // Инициализируем базу данных
    initDatabase(config)

    // Создаем репозитории
    val playerRepository = PlayerRepository()
    val tournamentRepository = TournamentRepository()

    // HTTP клиент
    val httpClient = HttpClient(CIO) {
    }

    val gomafiaClient = GomafiaRestClient(httpClient)

    val objectMapper = ObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        registerModule(JavaTimeModule())
        registerKotlinModule()
    }
    val polemicaClient = PolemicaClientImpl(
        polemicaBaseUrl = config.property("polemica.baseUrl").getString(),
        polemicaUsername = config.property("polemica.username").getString(),
        polemicaPassword = config.property("polemica.password").getString(),
        objectMapper = objectMapper
    )

    val tournamentService = TournamentService(playerRepository, tournamentRepository)

    // Telegram бот
    val telegramBotToken = config.property("telegram.bot.token").getString()
    val telegramBot = bot {
        token = telegramBotToken
        val botLogger = LoggerFactory.getLogger("telegram.bot")

        dispatch {
            command("start") {
                val messageStr = """
                    Привет! Я бот для уведомлений о турнирах по спортивной мафии.
                    Чтобы получать уведомления, зарегистрируйтесь с помощью команды:
                    /register https://gomafia.pro/stats/YOUR_ID
                    или
                    /register https://polemicagame.com/user/YOUR_ID
                """.trimIndent()
                botLogger.debug("Команда /start от пользователя {}", message.chat.id)
                try {
                    runBlocking {
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = messageStr
                        )
                    }
                } catch (e: Exception) {
                    botLogger.error("Ошибка отправки сообщения пользователю {}: {}", message.chat.id, e.message, e)
                }
            }

            command("register") {
                val args = message.text?.split(" ")
                botLogger.debug("Команда /register от пользователя {}", message.chat.id)
                if (args != null && args.size > 1) {
                    val profileUrl = args[1]
                    when {
                        profileUrl.matches(Regex("https://gomafia\\.pro/stats/\\d+")) -> {
                            val gomafiaId = profileUrl.substringAfterLast("/").toInt()
                            playerRepository.savePlayer(
                                PlayerDto(
                                    gomafiaProfileUrl = profileUrl,
                                    telegramId = message.chat.id,
                                    gomafiaId = gomafiaId
                                )
                            )
                            try {
                                runBlocking {
                                    bot.sendMessage(
                                        chatId = ChatId.fromId(message.chat.id),
                                        text = "Регистрация через gomafia.pro успешна!"
                                    )
                                }
                            } catch (e: Exception) {
                                botLogger.error("Ошибка отправки сообщения пользователю {}: {}", message.chat.id, e.message, e)
                            }
                        }
                        profileUrl.matches(Regex("https://polemicagame\\.com/user/\\d+")) -> {
                            val polemicaId = profileUrl.substringAfterLast("/").toLong()
                            playerRepository.savePolemicaPlayer(
                                telegramId = message.chat.id,
                                polemicaId = polemicaId,
                                profileUrl = profileUrl
                            )
                            try {
                                runBlocking {
                                    bot.sendMessage(
                                        chatId = ChatId.fromId(message.chat.id),
                                        text = "Регистрация через polemicagame.com успешна!"
                                    )
                                }
                            } catch (e: Exception) {
                                botLogger.error("Ошибка отправки сообщения пользователю {}: {}", message.chat.id, e.message, e)
                            }
                        }
                        else -> {
                            try {
                                runBlocking {
                                    bot.sendMessage(
                                        chatId = ChatId.fromId(message.chat.id),
                                        text = "Неизвестный формат ссылки. Поддерживаются gomafia.pro и polemicagame.com"
                                    )
                                }
                            } catch (e: Exception) {
                                botLogger.error("Ошибка отправки сообщения пользователю {}: {}", message.chat.id, e.message, e)
                            }
                        }
                    }
                }
            }

            // В классе TelegramBot
            command("arrangement") {
                val telegramId = message.chat.id
                botLogger.info("Получена команда /arrangement от пользователя {}", telegramId)
                val player = playerRepository.findByTelegramId(telegramId)

                if (player == null) {
                    botLogger.debug("Пользователь {} не зарегистрирован", telegramId)
                    try {
                        runBlocking {
                            bot.sendMessage(
                                chatId = ChatId.fromId(telegramId),
                                text = "Вы не зарегистрированы. Используйте команду /register для регистрации."
                            )
                        }
                    } catch (e: Exception) {
                        botLogger.error("Ошибка отправки сообщения пользователю {}: {}", telegramId, e.message, e)
                    }
                    return@command
                }

                try {
                    botLogger.debug("Получаем рассадку для игрока {} (gomafiaId={}, polemicaId={})",
                        player.gomafiaProfileUrl, player.gomafiaId, player.polemicaId)
                    val arrangementMessage = tournamentService.getPlayerArrangement(player)

                    if (arrangementMessage.isEmpty()) {
                        botLogger.debug("Рассадка пуста для пользователя {}", telegramId)
                        try {
                            runBlocking {
                                bot.sendMessage(
                                    chatId = ChatId.fromId(telegramId),
                                    text = "Информация о вашей рассадке не найдена. Возможно, вы не участвуете ни в одном активном турнире."
                                )
                            }
                        } catch (e: Exception) {
                            botLogger.error("Ошибка отправки сообщения пользователю {}: {}", telegramId, e.message, e)
                        }
                    } else {
                        botLogger.info("Отправляем рассадку пользователю {} ({} символов)", telegramId, arrangementMessage.length)
                        try {
                            val plainMessage = arrangementMessage.replace("*", "").replace("_", "")
                            val result = runBlocking {
                                bot.sendMessage(
                                    chatId = ChatId.fromId(telegramId),
                                    text = plainMessage
                                )
                            }
                            if (result == null) {
                                botLogger.error("sendMessage вернул null для пользователя {}", telegramId)
                            } else {
                                botLogger.debug("Сообщение отправлено, result={}", result)
                            }
                        } catch (e: Exception) {
                            botLogger.error("Ошибка отправки сообщения пользователю {}: {} (text sample: {})", 
                                telegramId, e.message, arrangementMessage.take(100).replace("\n", "\\n"))
                        }
                    }
                } catch (e: Exception) {
                    botLogger.error("Ошибка при получении рассадки для пользователя {}: {}", telegramId, e.message, e)
                    try {
                        runBlocking {
                            bot.sendMessage(
                                chatId = ChatId.fromId(telegramId),
                                text = "Произошла ошибка при получении информации о вашей рассадке. Пожалуйста, попробуйте позже."
                            )
                        }
                    } catch (sendEx: Exception) {
                        botLogger.error("Ошибка отправки сообщения об ошибке пользователю {}: {}", telegramId, sendEx.message, sendEx)
                    }
                }
            }

            command("help") {
                try {
                    runBlocking {
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = """
                                Доступные команды:
                                /start - Начать работу с ботом
                                /register [ссылка] - Зарегистрироваться, указав ссылку на ваш профиль gomafia или polemica
                                /arrangement - Получить информацию о вашей рассадке во всех активных турнирах
                                /help - Показать эту справку
                            """.trimIndent()
                        )
                    }
                } catch (e: Exception) {
                    botLogger.error("Ошибка отправки сообщения пользователю {}: {}", message.chat.id, e.message, e)
                }
            }


        }
    }

    val notificationService = NotificationService(
        telegramBot,
        playerRepository,
        tournamentRepository,
        tournamentService
    )

    // Запуск бота
    telegramBot.startPolling()

    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            json()
        }

        configureTemplating()

        configureRouting(telegramBot, notificationService, gomafiaClient, polemicaClient, tournamentRepository)
    }.start(wait = true)
}

fun initDatabase(config: ApplicationConfig) {
    val dbUrl = config.property("database.url").getString()
    val dbUser = config.property("database.user").getString()
    val dbPassword = config.property("database.password").getString()

    val hikariConfig = HikariConfig().apply {
        jdbcUrl = dbUrl
        username = dbUser
        password = dbPassword
        maximumPoolSize = 10
    }

    val dataSource = HikariDataSource(hikariConfig)
    Database.connect(dataSource)

    // Создаем таблицы, если их нет
    transaction {
        SchemaUtils.create(Players, Tournaments, Tours, TournamentTables, TourTablePlayers)
    }

    // Миграции для поддержки polemica
    transaction {
        exec("ALTER TABLE players ADD COLUMN IF NOT EXISTS polemica_profile_url TEXT")
        exec("ALTER TABLE players ADD COLUMN IF NOT EXISTS polemica_id BIGINT")
        exec("CREATE INDEX IF NOT EXISTS players_polemica_id ON players(polemica_id)")

        exec("ALTER TABLE tournaments ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'GOMAFIA'")
        exec("ALTER TABLE tournaments ALTER COLUMN external_id TYPE BIGINT")
        exec("DROP INDEX IF EXISTS tournaments_external_id")
        exec("CREATE UNIQUE INDEX IF NOT EXISTS tournaments_external_id_source ON tournaments(external_id, source)")

        exec("""
            DO ${'$'}${'$'}
            BEGIN
                IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='tour_table_players' AND column_name='gomafia_id') THEN
                    ALTER TABLE tour_table_players RENAME COLUMN gomafia_id TO external_player_id;
                END IF;
            END
            ${'$'}${'$'}
        """.trimIndent())
        exec("ALTER TABLE tour_table_players ALTER COLUMN external_player_id TYPE BIGINT")
    }
}

fun Application.configureRouting(
    telegramBot: Bot,
    notificationService: NotificationService,
    gomafiaClient: GomafiaRestClient,
    polemicaClient: PolemicaClient,
    tournamentRepository: TournamentRepository
) {
    routing {
        static("/static") {
            resources("static")
        }

        // Административный интерфейс
        get("/admin") {
            // Список всех турниров
            call.respondTemplate(
                "admin_tournaments.html",
                mapOf("tournaments" to tournamentRepository.getAllTournaments())
            )
        }

        get("/admin/tournament/{source}/{id}") {
            val source = call.parameters["source"]?.uppercase()?.let {
                try { TournamentSource.valueOf(it) } catch (_: Exception) { null }
            } ?: return@get call.respond(HttpStatusCode.BadRequest)
            val tournamentId =
                call.parameters["id"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)

            val tournamentDto = when (source) {
                TournamentSource.GOMAFIA -> gomafiaClient.getTournamentDto(tournamentId.toInt())
                TournamentSource.POLEMICA -> polemicaClient.getTournamentDto(tournamentId)
            }
            tournamentRepository.saveTournament(tournamentDto)
            val tournament = tournamentRepository.getTournament(tournamentId, source)
                ?: return@get call.respond(HttpStatusCode.NotFound)

            val tableNumbers = tournament.tours.flatMap { tour ->
                tour.tables.map { it.number }
            }.distinct().sorted()

            val tableLocations = tournament.tours.flatMap { tour ->
                tour.tables.filter { it.location != null }
                    .map { it.number to it.location }
            }.distinctBy { it.first }.toMap()

            call.respondTemplate(
                "admin_tournament.html", mapOf(
                    "tournament" to tournament,
                    "tableNumbers" to tableNumbers,
                    "tableLocations" to tableLocations,
                    "source" to source.name.lowercase()
                )
            )
        }

        post("/admin/tournament/{source}/{id}/tour/{tourNumber}/update") {
            val source = call.parameters["source"]?.uppercase()?.let {
                try { TournamentSource.valueOf(it) } catch (_: Exception) { null }
            } ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tournamentId =
                call.parameters["id"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tourNumber =
                call.parameters["tourNumber"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)

            val formParameters = call.receiveParameters()
            val startTime = formParameters["startTime"]

            tournamentRepository.updateTourStartTime(tournamentId, source, tourNumber, startTime)
            call.respondRedirect("/admin/tournament/${source.name.lowercase()}/$tournamentId")
        }

        post("/admin/tournament/{source}/{id}/table/{tableNumber}/update") {
            val source = call.parameters["source"]?.uppercase()?.let {
                try { TournamentSource.valueOf(it) } catch (_: Exception) { null }
            } ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tournamentId =
                call.parameters["id"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tableNumber =
                call.parameters["tableNumber"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)

            val formParameters = call.receiveParameters()
            val location = formParameters["location"]

            tournamentRepository.updateTableLocation(tournamentId, source, tableNumber, location)
            call.respondRedirect("/admin/tournament/${source.name.lowercase()}/$tournamentId")
        }

        post("/admin/tournament/{source}/{id}/tour/{tourNumber}/notify") {
            val source = call.parameters["source"]?.uppercase()?.let {
                try { TournamentSource.valueOf(it) } catch (_: Exception) { null }
            } ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tournamentId =
                call.parameters["id"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tourNumber =
                call.parameters["tourNumber"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)

            val tournament = tournamentRepository.getTournament(tournamentId, source)
            val tour = tournament?.tours?.find { it.number == tourNumber }

            if (tournament != null && tour != null) {
                notificationService.notifyPlayersAboutTour(tournamentId, source, tourNumber)
            }

            call.respondRedirect("/admin/tournament/${source.name.lowercase()}/$tournamentId")
        }

        post("/admin/tournament/{source}/{id}/broadcast") {
            val source = call.parameters["source"]?.uppercase()?.let {
                try { TournamentSource.valueOf(it) } catch (_: Exception) { null }
            } ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tournamentId =
                call.parameters["id"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)

            val formParameters = call.receiveParameters()
            val message = formParameters["message"] ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                "Сообщение не может быть пустым"
            )

            if (message.isBlank()) {
                call.respondRedirect("/admin/tournament/${source.name.lowercase()}/$tournamentId?error=Сообщение не может быть пустым")
                return@post
            }

            notificationService.broadcastMessage(tournamentId, source, message)

            call.respondRedirect("/admin/tournament/${source.name.lowercase()}/$tournamentId?success=Сообщение успешно отправлено")
        }

    }
}

fun Application.module() {
    configureMonitoring()
    configureSerialization()
    configureTemplating()
}

suspend fun GomafiaRestClient.getTournamentDto(tournamentId: Int): TournamentDto {
    // Получаем данные турнира из gomafia API
    val tournamentResponse = getTournament(tournamentId)
    val gomafiaData = tournamentResponse.tournamentDto
    val games = tournamentResponse.games

    // Создаем базовый DTO турнира
    val tournamentDto = TournamentDto(
        id = gomafiaData.id?.toLongOrNull() ?: tournamentId.toLong(),
        name = gomafiaData.title ?: "Турнир #$tournamentId",
        source = TournamentSource.GOMAFIA
    )

    // Если нет игр, возвращаем только базовую информацию
    if (games.isEmpty()) {
        return tournamentDto
    }

    // Группируем игры по турам
    val gamesByTour = games.groupBy { it.gameNum ?: 0 }

    // Создаем список туров
    val tours = gamesByTour.map { (tourNumber, tourGames) ->
        // Группируем игры в туре по столам
        val tablesByNumber = tourGames.groupBy { it.tableNum ?: 0 }

        // Создаем список столов для тура
        val tables = tablesByNumber.map { (tableNumber, tableGames) ->
            // Собираем уникальных игроков для стола
            val uniquePlayers = tableGames
                .flatMap { it.table }
                .distinctBy { it.id }
                .mapNotNull { playerDto ->
                    playerDto.id?.let { playerId ->
                        playerDto.place?.let {
                            PlayerGameDto(
                                externalPlayerId = playerId.toLong(),
                                position = it
                            )
                        }
                    }
                }

            // Создаем DTO стола
            TableDto(
                id = null, // ID генерируется в БД
                tourId = null, // ID тура еще неизвестен
                number = tableNumber,
                location = null, // Местоположение будет заполнено администратором
                players = uniquePlayers
            )
        }

        // Создаем DTO тура
        TourDto(
            id = null, // ID генерируется в БД
            tournamentId = tournamentDto.id,
            number = tourNumber,
            startTime = null, // Время старта будет заполнено администратором
            tables = tables
        )
    }.sortedBy { it.number } // Сортируем туры по номерам

    // Добавляем туры в итоговый DTO турнира
    return tournamentDto.copy(tours = tours)
}

fun PolemicaClient.getTournamentDto(competitionId: Long): TournamentDto {
    val competition = getCompetition(competitionId)
    val gameRefs = getGamesFromCompetition(competitionId)

    val tournamentDto = TournamentDto(
        id = competitionId,
        name = competition?.name ?: "Турнир Polemica #$competitionId",
        source = TournamentSource.POLEMICA
    )

    if (gameRefs.isEmpty()) {
        return tournamentDto
    }

    val gamesByTour = gameRefs.groupBy { it.num.toInt() }

    val tours = gamesByTour.map { (tourNumber, tourGameRefs) ->
        val tablesByNumber = tourGameRefs.groupBy { it.table.toInt() }

        val tables = tablesByNumber.map { (tableNumber, tableGameRefs) ->
            val players = tableGameRefs.flatMap { ref ->
                val game = getGameFromCompetition(
                    PolemicaCompetitionGameId(competitionId, ref.id, ref.version)
                )
                game.players?.mapNotNull { player ->
                    player.player?.let {
                        PlayerGameDto(
                            externalPlayerId = it.id,
                            position = player.position.value
                        )
                    }
                } ?: emptyList()
            }.distinctBy { it.externalPlayerId }

            TableDto(
                number = tableNumber,
                players = players
            )
        }

        TourDto(
            tournamentId = competitionId,
            number = tourNumber,
            tables = tables
        )
    }.sortedBy { it.number }

    return tournamentDto.copy(tours = tours)
}
