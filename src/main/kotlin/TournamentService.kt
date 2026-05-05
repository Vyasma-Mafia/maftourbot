package online.mafoverlay

import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

class TournamentService(
    private val playerRepository: PlayerRepository,
    private val tournamentRepository: TournamentRepository
) {
    private val logger = LoggerFactory.getLogger(TournamentService::class.java)

    fun getPlayerArrangement(player: PlayerDto): String {
        try {
            val tournaments = tournamentRepository.getAllFutureRunningTournaments()

            if (tournaments.isEmpty()) {
                return "Нет доступных турниров."
            }

            val result = StringBuilder()

            for (tournament in tournaments) {
                val externalPlayerId = when (tournament.source) {
                    TournamentSource.GOMAFIA -> player.gomafiaId.toLong().takeIf { it != 0L }
                    TournamentSource.POLEMICA -> player.polemicaId
                } ?: continue

                val tourArrangements = mutableListOf<TourArrangementInfo>()
                val tableLocations = tournamentRepository.getTournamentTables(tournament.id, tournament.source)

                for (tour in tournament.tours) {
                    val tourPlayers = getTourPlayersInfo(tournament.id, tournament.source, tour.number)

                    val playerTable = tourPlayers.entries.find { (_, players) ->
                        players.any { it.playerGame.externalPlayerId == externalPlayerId }
                    }

                    if (playerTable != null) {
                        val tableNumber = playerTable.key
                        val players = playerTable.value
                        val tableLocation = tableLocations[tableNumber]
                        val foundPlayer = players.find { it.playerGame.externalPlayerId == externalPlayerId }
                        tourArrangements.add(
                            TourArrangementInfo(
                                tourNumber = tour.number,
                                tableNumber = tableNumber,
                                position = foundPlayer?.playerGame?.position ?: 0,
                                tableLocation = tableLocation
                            )
                        )
                    }
                }

                if (tourArrangements.isNotEmpty()) {
                    result.append("*${tournament.name}*\n")

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
            logger.error("Ошибка при получении информации о рассадке игрока {}: {}", player.gomafiaProfileUrl, e.message, e)
            return "Произошла ошибка при получении информации о рассадке."
        }
    }

    private data class TourArrangementInfo(
        val tourNumber: Int,
        val tableNumber: Int,
        val position: Int,
        val tableLocation: String?
    )

    fun getTourPlayersInfo(
        tournamentId: Long,
        source: TournamentSource,
        tourNumber: Int
    ): Map<Int, List<PlayerArrangementDto>> {
        return transaction {
            val tournament = TournamentEntity.find {
                (Tournaments.externalId eq tournamentId) and (Tournaments.tournamentSource eq source.name)
            }.firstOrNull() ?: return@transaction emptyMap()

            val tour = Tour.find {
                (Tours.tournamentId eq tournament.id) and (Tours.number eq tourNumber)
            }.firstOrNull() ?: return@transaction emptyMap()

            val playerColumn = when (source) {
                TournamentSource.GOMAFIA -> Players.gomafiaId
                TournamentSource.POLEMICA -> Players.polemicaId
            }

            val query = TourTablePlayers
                .join(Players, JoinType.INNER) { TourTablePlayers.externalPlayerId eq playerColumn }
                .select { TourTablePlayers.tourId eq tour.id }

            val result = mutableMapOf<Int, MutableList<PlayerArrangementDto>>()

            query.forEach { row ->
                val tableNumber = row[TourTablePlayers.tableNumber]
                val player = Player.wrapRow(row)
                val position = row[TourTablePlayers.position]

                result.getOrPut(tableNumber) { mutableListOf() }.add(
                    PlayerArrangementDto(
                        PlayerGameDto(row[TourTablePlayers.externalPlayerId] ?: 0L, position),
                        player.telegramId
                    )
                )
            }

            result
        }
    }
}
