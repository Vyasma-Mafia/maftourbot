package online.mafoverlay

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import io.github.mralex1810.gomafia.GomafiaRestClient

class NotificationService(
    private val telegramBot: Bot,
    private val playerRepository: PlayerRepository,
    private val gomafiaClient: GomafiaRestClient,
    private val tournamentRepository: TournamentRepository,
    private val tournamentService: TournamentService
) {
    suspend fun notifyPlayersAboutTour(tournamentId: Int, tourNumber: Int) {
        val tournament = tournamentRepository.getTournament(tournamentId)
            ?: return

        val tour = tournament.tours.find { it.number == tourNumber }
            ?: return

        val startTime = tour.startTime ?: "Не указано"

        // Получаем местоположения столов
        val tableLocations = tour.tables.associate { it.number to (it.location ?: "") }

        val games = gomafiaClient.getTournament(tournamentId).games

        for (gomafiaPlayerId in games.flatMap { it.table }.mapNotNull { it.id }.distinct()) {
            try {
                val playerDto = tournamentService.getPlayerTable(games, tourNumber, gomafiaPlayerId)

                if (playerDto != null) {
                    val message = buildString {
                        append("🎲 *Уведомление о начале тура $tourNumber* 🎲\n\n")
                        append("Турнир: ${tournament.name}\n")
                        append("Время начала: $startTime\n")
                        append("Ваш стол: ${playerDto.first}\n")
                        append("Слот: ${playerDto.second}\n")

                        val location = tableLocations[playerDto.first]
                        if (!location.isNullOrBlank()) {
                            append("Местоположение: $location\n")
                        }

                        append("\nУдачной игры! 🃏")
                    }
                    for (player in playerRepository.getPlayersByGomafiaId(gomafiaPlayerId)) {
                        telegramBot.sendMessage(
                            ChatId.fromId(player.telegramId),
                            message,
                            parseMode = ParseMode.MARKDOWN
                        )
                    }
                }
            } catch (e: Exception) {
                println("Ошибка при отправке уведомления игроку ${gomafiaPlayerId}: ${e.message}")
            }
        }
    }

    /**
     * Отправляет произвольное сообщение всем зарегистрированным участникам турнира
     */
    suspend fun broadcastMessage(tournamentId: Int, message: String) {
        val tournament = tournamentRepository.getTournament(tournamentId)
            ?: return

        // Получаем всех зарегистрированных игроков
        val players = playerRepository.getAllPlayers()

        // Формируем сообщение с заголовком
        val formattedMessage = """
            📢 *Сообщение от организаторов турнира "${tournament.name}"*
            
            $message
        """.trimIndent()

        // Отправляем сообщение каждому участнику
        for (player in players) {
            try {
                // Проверяем, что игрок участвует в турнире (опционально)
                val isParticipant = checkIfPlayerIsParticipant(tournamentId, player.gomafiaId)

                if (isParticipant) {
                    telegramBot.sendMessage(ChatId.fromId(player.telegramId), formattedMessage)
                }
            } catch (e: Exception) {
                println("Ошибка при отправке сообщения игроку ${player.gomafiaId}: ${e.message}")
            }
        }
    }

    /**
     * Проверяет, является ли игрок участником турнира
     */
    private fun checkIfPlayerIsParticipant(tournamentId: Int, playerId: Int): Boolean {
        try {
            // Здесь можно реализовать проверку, участвует ли игрок в турнире
            // Например, проверить, назначен ли он хотя бы на один стол в любом туре

            // Для упрощения сейчас возвращаем true для всех игроков
            return true
        } catch (e: Exception) {
            println("Ошибка при проверке участия игрока $playerId в турнире $tournamentId: ${e.message}")
            return false
        }
    }
}

