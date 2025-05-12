package online.mafoverlay

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import io.github.mralex1810.gomafia.GomafiaRestClient
import io.github.mralex1810.gomafia.dto.GameDto

class NotificationService(
    private val telegramBot: Bot,
    private val playerRepository: PlayerRepository,
    private val gomafiaClient: GomafiaRestClient,
    private val tournamentRepository: TournamentRepository
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
                val playerDto = getPlayerTable(games, tourNumber, gomafiaPlayerId)

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

    private fun getPlayerTable(games: List<GameDto>, tourNumber: Int, gomafiaId: Int): Pair<Int, Int>? {
        return games.filter { it.gameNum == tourNumber }.firstNotNullOfOrNull { game ->
            game.table.find { it.id == gomafiaId }?.let { Pair(game.tableNum!!, it.place!!) }
        }
    }
}

