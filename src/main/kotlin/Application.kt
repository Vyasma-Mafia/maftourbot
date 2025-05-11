package online.mafoverlay

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
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

fun main() {
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

    // Telegram бот
    val telegramBotToken = config.property("telegram.bot.token").getString()
    val telegramBot = bot {
        token = telegramBotToken

        // Установка диспетчера для обработки сообщений
        dispatch {
            command("start") {
                val messageStr = """
                    Привет! Я бот для уведомлений о турнирах по спортивной мафии.
                    Чтобы получать уведомления, зарегистрируйтесь с помощью команды:
                    /register https://gomafia.pro/stats/YOUR_ID
                """.trimIndent()

                bot.sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = messageStr
                )
            }

            command("register") {
                val args = message.text?.split(" ")
                if (args != null && args.size > 1) {
                    val profileUrl = args[1]
                    if (profileUrl.matches(Regex("https://gomafia.pro/stats/\\d+"))) {
                        val gomafiaId = profileUrl.substringAfterLast("/").toInt()
                        playerRepository.savePlayer(
                            PlayerDto(
                                gomafiaProfileUrl = profileUrl,
                                telegramId = message.chat.id,
                                gomafiaId = gomafiaId
                            )
                        )
                    }
                }
            }
        }
    }

    // Сервис уведомлений
    val notificationService = NotificationService(
        telegramBot,
        playerRepository,
        gomafiaClient,
        tournamentRepository
    )

    // Запуск бота
    telegramBot.startPolling()

    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            json()
        }

        configureTemplating()

        configureRouting(telegramBot, notificationService, gomafiaClient, tournamentRepository)
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
}

fun Application.configureRouting(
    telegramBot: Bot,
    notificationService: NotificationService,
    gomafiaClient: GomafiaRestClient,
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

        get("/admin/tournament/{id}") {
            val tournamentId =
                call.parameters["id"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            // Получаем данные турнира
            tournamentRepository.saveTournament(gomafiaClient.getTournamentDto(tournamentId))
            val tournament = tournamentRepository.getTournament(tournamentId) ?: return@get call.respond(HttpStatusCode.NotFound)

            // Собираем уникальные номера столов из всех туров
            val tableNumbers = tournament.tours.flatMap { tour ->
                tour.tables.map { it.number }
            }.distinct().sorted()

            // Собираем информацию о местоположении столов
            val tableLocations = tournament.tours.flatMap { tour ->
                tour.tables.filter { it.location != null }
                    .map { it.number to it.location }
            }.distinctBy { it.first }.toMap()

            // Передаем все данные в шаблон
            call.respondTemplate(
                "admin_tournament.html", mapOf(
                    "tournament" to tournament,
                    "tableNumbers" to tableNumbers,
                    "tableLocations" to tableLocations
                )
            )
        }


        post("/admin/tournament/{id}/tour/{tourNumber}/update") {
            val tournamentId =
                call.parameters["id"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tourNumber =
                call.parameters["tourNumber"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)

            val formParameters = call.receiveParameters()
            val startTime = formParameters["startTime"]

            tournamentRepository.updateTourStartTime(tournamentId, tourNumber, startTime)
            call.respondRedirect("/admin/tournament/$tournamentId")
        }

        post("/admin/tournament/{id}/table/{tableNumber}/update") {
            val tournamentId =
                call.parameters["id"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tableNumber =
                call.parameters["tableNumber"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)

            val formParameters = call.receiveParameters()
            val location = formParameters["location"]

            tournamentRepository.updateTableLocation(tournamentId, tableNumber, location)
            call.respondRedirect("/admin/tournament/$tournamentId")
        }

        post("/admin/tournament/{id}/tour/{tourNumber}/notify") {
            val tournamentId =
                call.parameters["id"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tourNumber =
                call.parameters["tourNumber"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)

            val tournament = tournamentRepository.getTournament(tournamentId)
            val tour = tournament?.tours?.find { it.number == tourNumber }

            if (tournament != null && tour != null) {
                val tableLocations = tour.tables.associate { it.number to (it.location ?: "") }

                notificationService.notifyPlayersAboutTour(
                    tournamentId,
                    tourNumber,
                )
            }

            call.respondRedirect("/admin/tournament/$tournamentId")
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
        id = gomafiaData.id?.toIntOrNull() ?: tournamentId,
        name = gomafiaData.title ?: "Турнир #$tournamentId"
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
                        PlayerDto(
                            id = null, // Здесь нет информации об ID в нашей системе
                            telegramId = 0, // Здесь нет информации о Telegram ID
                            gomafiaProfileUrl = "https://gomafia.pro/stats/$playerId",
                            gomafiaId = playerId
                        )
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
