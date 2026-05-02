package online.mafoverlay

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

class PlayerRepository {
    fun findByTelegramId(telegramId: Long): PlayerDto? = transaction {
        Player.find { Players.telegramId eq telegramId }
            .firstOrNull()
            ?.toDto()
    }

    fun savePlayer(playerDto: PlayerDto): PlayerDto = transaction {
        val existingPlayer = Player.find { Players.telegramId eq playerDto.telegramId }.firstOrNull()

        if (existingPlayer != null) {
            existingPlayer.apply {
                gomafiaProfileUrl = playerDto.gomafiaProfileUrl
                gomafiaId = playerDto.gomafiaId
                if (playerDto.polemicaProfileUrl != null) polemicaProfileUrl = playerDto.polemicaProfileUrl
                if (playerDto.polemicaId != null) polemicaId = playerDto.polemicaId
            }.toDto()
        } else {
            Player.new {
                telegramId = playerDto.telegramId
                gomafiaProfileUrl = playerDto.gomafiaProfileUrl
                gomafiaId = playerDto.gomafiaId
                polemicaProfileUrl = playerDto.polemicaProfileUrl
                polemicaId = playerDto.polemicaId
            }.toDto()
        }
    }

    fun savePolemicaPlayer(telegramId: Long, polemicaId: Long, profileUrl: String): PlayerDto = transaction {
        val existingPlayer = Player.find { Players.telegramId eq telegramId }.firstOrNull()

        if (existingPlayer != null) {
            existingPlayer.apply {
                this.polemicaProfileUrl = profileUrl
                this.polemicaId = polemicaId
            }.toDto()
        } else {
            Player.new {
                this.telegramId = telegramId
                this.gomafiaProfileUrl = ""
                this.gomafiaId = 0
                this.polemicaProfileUrl = profileUrl
                this.polemicaId = polemicaId
            }.toDto()
        }
    }

    fun getAllPlayers(): List<PlayerDto> = transaction {
        Player.all().map { it.toDto() }
    }

    fun getPlayersByGomafiaId(gomafiaId: Int): List<PlayerDto> = transaction {
        Player.find { Players.gomafiaId eq gomafiaId }.map { it.toDto() }
    }

    fun getPlayersByPolemicaId(polemicaId: Long): List<PlayerDto> = transaction {
        Player.find { Players.polemicaId eq polemicaId }.map { it.toDto() }
    }

    fun findByExternalId(source: TournamentSource, externalId: Long): List<PlayerDto> {
        return when (source) {
            TournamentSource.GOMAFIA -> getPlayersByGomafiaId(externalId.toInt())
            TournamentSource.POLEMICA -> getPlayersByPolemicaId(externalId)
        }
    }
}

class TournamentRepository {
    fun saveTournament(tournamentDto: TournamentDto): TournamentDto = transaction {
        val sourceStr = tournamentDto.source.name
        val tournament = TournamentEntity.find {
            (Tournaments.externalId eq tournamentDto.id) and (Tournaments.tournamentSource eq sourceStr)
        }.firstOrNull() ?: TournamentEntity.new {
            externalId = tournamentDto.id
            name = tournamentDto.name
            source = sourceStr
        }

        tournament.name = tournamentDto.name

        if (tournamentDto.tours.isNotEmpty()) {
            val firstTour = tournamentDto.tours.first()
            for (tableDto in firstTour.tables) {
                TournamentTable.find {
                    (TournamentTables.tournamentId eq tournament.id) and
                        (TournamentTables.number eq tableDto.number)
                }.firstOrNull() ?: TournamentTable.new {
                    tournamentId = tournament.id
                    number = tableDto.number
                    location = null
                }
            }
        }

        for (tourDto in tournamentDto.tours) {
            val tour = Tour.find {
                (Tours.tournamentId eq tournament.id) and (Tours.number eq tourDto.number)
            }.firstOrNull() ?: Tour.new {
                tournamentId = tournament.id
                number = tourDto.number
                startTime = tourDto.startTime
            }

            if (tour.startTime != tourDto.startTime && tourDto.startTime != null) {
                tour.startTime = tourDto.startTime
            }

            for (tableDto in tourDto.tables) {
                TourTablePlayers.deleteWhere {
                    (TourTablePlayers.tourId eq tour.id) and
                        (TourTablePlayers.tableNumber eq tableDto.number)
                }

                for (playerDto in tableDto.players) {
                    val tablePosition =
                        tourDto.tables.find { it.number == tableDto.number }
                            ?.players?.find { it.externalPlayerId == playerDto.externalPlayerId }?.position ?: 0

                    TourTablePlayers.insert {
                        it[tourId] = tour.id
                        it[tableNumber] = tableDto.number
                        it[externalPlayerId] = playerDto.externalPlayerId
                        it[position] = tablePosition
                    }
                }
            }
        }

        tournament.toDto(includeTours = true)
    }

    fun getTournament(externalId: Long, source: TournamentSource): TournamentDto? = transaction {
        TournamentEntity.find {
            (Tournaments.externalId eq externalId) and (Tournaments.tournamentSource eq source.name)
        }.firstOrNull()?.toDto(includeTours = true)
    }

    fun getAllTournaments(): List<TournamentDto> = transaction {
        TournamentEntity.all().map { it.toDto(true) }
    }

    fun getAllFutureRunningTournaments(): List<TournamentDto> = transaction {
        TournamentEntity.find { Tournaments.ended neq true }.map { it.toDto(true) }
    }

    fun updateTourStartTime(externalId: Long, source: TournamentSource, tourNumber: Int, startTime: String?): Boolean = transaction {
        val tournament = TournamentEntity.find {
            (Tournaments.externalId eq externalId) and (Tournaments.tournamentSource eq source.name)
        }.firstOrNull() ?: return@transaction false

        val tour = Tour.find {
            (Tours.tournamentId eq tournament.id) and (Tours.number eq tourNumber)
        }.firstOrNull() ?: return@transaction false

        tour.startTime = startTime
        true
    }

    fun updateTableLocation(externalId: Long, source: TournamentSource, tableNumber: Int, location: String?): Boolean = transaction {
        val tournament = TournamentEntity.find {
            (Tournaments.externalId eq externalId) and (Tournaments.tournamentSource eq source.name)
        }.firstOrNull() ?: return@transaction false

        val tournamentTable = TournamentTable.find {
            (TournamentTables.tournamentId eq tournament.id) and
                (TournamentTables.number eq tableNumber)
        }.firstOrNull()

        if (tournamentTable != null) {
            tournamentTable.location = location
            true
        } else {
            TournamentTable.new {
                this.tournamentId = tournament.id
                this.number = tableNumber
                this.location = location
            }
            true
        }
    }

    fun getTournamentTables(externalId: Long, source: TournamentSource): Map<Int, String?> = transaction {
        val tournament = TournamentEntity.find {
            (Tournaments.externalId eq externalId) and (Tournaments.tournamentSource eq source.name)
        }.firstOrNull() ?: return@transaction emptyMap()

        TournamentTable.find { TournamentTables.tournamentId eq tournament.id }
            .associate { it.number to it.location }
    }
}
