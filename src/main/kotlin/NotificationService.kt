package online.mafoverlay

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import org.slf4j.LoggerFactory

class NotificationService(
    private val telegramBot: Bot,
    private val playerRepository: PlayerRepository,
    private val tournamentRepository: TournamentRepository,
    private val tournamentService: TournamentService
) {
    private val logger = LoggerFactory.getLogger(NotificationService::class.java)

    fun notifyPlayersAboutTour(tournamentId: Long, source: TournamentSource, tourNumber: Int) {
        val tournament = tournamentRepository.getTournament(tournamentId, source) ?: return
        val tour = tournament.tours.find { it.number == tourNumber } ?: return

        val startTime = tour.startTime ?: "Не указано"
        val tableLocations = tour.tables.associate { it.number to (it.location ?: "") }

        val tourPlayersInfo = tournamentService.getTourPlayersInfo(tournamentId, source, tourNumber)

        for ((tableNumber, players) in tourPlayersInfo) {
            for (playerArrangement in players) {
                try {
                    val message = buildString {
                        append("*Уведомление о начале тура $tourNumber*\n\n")
                        append("Турнир: ${tournament.name}\n")
                        append("Время начала: $startTime\n")
                        append("Ваш стол: $tableNumber\n")
                        append("Слот: ${playerArrangement.playerGame.position}\n")

                        val location = tableLocations[tableNumber]
                        if (!location.isNullOrBlank()) {
                            append("Местоположение: $location\n")
                        }
                    }
                    val result = telegramBot.sendMessage(
                        ChatId.fromId(playerArrangement.telegramId),
                        message,
                        parseMode = ParseMode.MARKDOWN
                    )
                    if (result == null) {
                        logger.error("sendMessage вернул null для пользователя {}", playerArrangement.telegramId)
                    }
                } catch (e: Exception) {
                    logger.error("Исключение при отправке уведомления пользователю {}: {}", playerArrangement.telegramId, e.message, e)
                }
            }
        }
    }

    fun broadcastMessage(tournamentId: Long, source: TournamentSource, message: String) {
        val tournament = tournamentRepository.getTournament(tournamentId, source) ?: return

        val formattedMessage = """
            *Сообщение от организаторов турнира "${tournament.name}"*

            $message
        """.trimIndent()

        val tourPlayersInfo = tournament.tours.flatMap { tour ->
            tournamentService.getTourPlayersInfo(tournamentId, source, tour.number).values.flatten()
        }.distinctBy { it.telegramId }

        for (playerArrangement in tourPlayersInfo) {
            try {
                val result = telegramBot.sendMessage(ChatId.fromId(playerArrangement.telegramId), formattedMessage)
                if (result == null) {
                    logger.error("sendMessage вернул null для пользователя {}", playerArrangement.telegramId)
                }
            } catch (e: Exception) {
                logger.error("Исключение при отправке broadcast пользователю {}: {}", playerArrangement.telegramId, e.message, e)
            }
        }
    }
}

