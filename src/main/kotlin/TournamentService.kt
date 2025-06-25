package online.mafoverlay

import io.github.mralex1810.gomafia.GomafiaRestClient
import io.github.mralex1810.gomafia.dto.GameDto
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

class TournamentService(
    private val playerRepository: PlayerRepository,
    private val gomafiaClient: GomafiaRestClient,
    private val tournamentRepository: TournamentRepository
) {

    /**
     * Получает информацию о рассадке игрока по всем сохраненным в БД турнирам
     */
    fun getPlayerArrangement(playerId: Int): String {
        try {
            // Получаем все турниры из базы данных
            val tournaments = tournamentRepository.getAllFutureRunningTournaments()

            if (tournaments.isEmpty()) {
                return "Нет доступных турниров."
            }

            val result = StringBuilder()

            // Проходим по каждому турниру
            for (tournament in tournaments) {
                val tourArrangements = mutableListOf<TourArrangementInfo>()

                // Получаем информацию о столах этого турнира (местоположения)
                val tableLocations = tournamentRepository.getTournamentTables(tournament.id)

                // Для каждого тура проверяем участие игрока
                for (tour in tournament.tours) {
                    // Получаем рассадку для данного тура
                    val tourPlayers = getTourPlayersInfo(tournament.id, tour.number)

                    // Ищем стол и слот игрока в текущем туре
                    val playerTable = tourPlayers.entries.find { (_, players) ->
                        players.any { it.playerGame.gomafiaId == playerId }
                    }

                    if (playerTable != null) {
                        val tableNumber = playerTable.key

                        // Определяем номер слота игрока за столом
                        val players = playerTable.value

                        // Находим местоположение стола
                        val tableLocation = tableLocations[tableNumber]

                        // Добавляем информацию в список
                        val player = players.find { it.playerGame.gomafiaId == playerId }
                        tourArrangements.add(
                            TourArrangementInfo(
                                tourNumber = tour.number,
                                tableNumber = tableNumber,
                                position = player?.playerGame?.position ?: 0,
                                tableLocation = tableLocation
                            )
                        )
                    }
                }

                // Если найдена информация о рассадке в этом турнире
                if (tourArrangements.isNotEmpty()) {
                    result.append("*${tournament.name}*\n")

                    // Сортируем туры по номеру
                    tourArrangements.sortedBy { it.tourNumber }.forEach { arrangement ->
                        val locationInfo = if (!arrangement.tableLocation.isNullOrBlank())
                            " (${arrangement.tableLocation})" else ""

                        result.append("Тур ${arrangement.tourNumber}: Стол ${arrangement.tableNumber}, ")
                        result.append("cлот ${arrangement.position}$locationInfo\n")
                    }

                    result.append("\n")
                }
            }

            return if (result.isEmpty()) "Информация о вашей рассадке не найдена." else result.toString().trim()
        } catch (e: Exception) {
            println("Ошибка при получении информации о рассадке игрока $playerId: ${e.message}")
            e.printStackTrace()
            return "Произошла ошибка при получении информации о рассадке."
        }
    }

    /**
     * Вспомогательный класс для хранения информации о рассадке в туре
     */
    private data class TourArrangementInfo(
        val tourNumber: Int,
        val tableNumber: Int,
        val position: Int,
        val tableLocation: String?
    )

    /**
     * Получает информацию о рассадке игроков за столами в определенном туре
     * @return Карта: Номер стола -> Список (игрок, слот) за этим столом
     */
    private fun getTourPlayersInfo(tournamentId: Int, tourNumber: Int): Map<Int, List<PlayerArrangementDto>> {
        return transaction {
            val tournament = TournamentEntity.find { Tournaments.externalId eq tournamentId }.firstOrNull()
                ?: return@transaction emptyMap()

            val tour = Tour.find {
                (Tours.tournamentId eq tournament.id) and (Tours.number eq tourNumber)
            }.firstOrNull() ?: return@transaction emptyMap()

            // Получаем данные о расположении игроков за столами
            val query = TourTablePlayers
                .join(Players, JoinType.INNER, TourTablePlayers.gomafiaId, Players.gomafiaId)
                .select { TourTablePlayers.tourId eq tour.id }

            // Группируем результаты по номеру стола
            val result = mutableMapOf<Int, MutableList<PlayerArrangementDto>>()

            query.forEach { row ->
                val tableNumber = row[TourTablePlayers.tableNumber]
                val player = Player.wrapRow(row)
                val tourTablePlayer = TourTablePlayer.wrapRow(row)

                if (!result.containsKey(tableNumber)) {
                    result[tableNumber] = mutableListOf()
                }

                result[tableNumber]?.add(
                    PlayerArrangementDto(
                        PlayerGameDto(player.gomafiaId, tourTablePlayer.position),
                        player.telegramId
                    )
                )
            }

            // Возвращаем карту Номер стола -> Список игроков
            result
        }
    }

    fun getPlayerTable(games: List<GameDto>, tourNumber: Int, gomafiaId: Int): Pair<Int, Int>? {
        return games.filter { it.gameNum == tourNumber }.firstNotNullOfOrNull { game ->
            game.table.find { it.id == gomafiaId }?.let { Pair(game.tableNum!!, it.place!!) }
        }
    }
}
