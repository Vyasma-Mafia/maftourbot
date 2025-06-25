package online.mafoverlay

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

class PlayerRepository {
    // Методы PlayerRepository остаются без изменений, т.к. структура таблицы Players не менялась
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
            }.toDto()
        } else {
            Player.new {
                telegramId = playerDto.telegramId
                gomafiaProfileUrl = playerDto.gomafiaProfileUrl
                gomafiaId = playerDto.gomafiaId
            }.toDto()
        }
    }

    fun getAllPlayers(): List<PlayerDto> = transaction {
        Player.all().map { it.toDto() }
    }

    fun getPlayersByGomafiaId(gomafiaId: Int): List<PlayerDto> = transaction {
        Player.find { Players.gomafiaId eq gomafiaId }.map { it.toDto() }
    }
}

class TournamentRepository {
    fun saveTournament(tournamentDto: TournamentDto): TournamentDto = transaction {
        // Находим или создаем турнир
        val tournament = TournamentEntity.find { Tournaments.externalId eq tournamentDto.id }.firstOrNull()
            ?: TournamentEntity.new {
                externalId = tournamentDto.id
                name = tournamentDto.name
            }

        // Обновляем имя турнира
        tournament.name = tournamentDto.name

        // Сначала создаем все столы для турнира, используя информацию из первого тура
        if (tournamentDto.tours.isNotEmpty()) {
            val firstTour = tournamentDto.tours.first()
            for (tableDto in firstTour.tables) {
                // Создаем или находим стол для турнира
                val tournamentTable = TournamentTable.find {
                    (TournamentTables.tournamentId eq tournament.id) and
                        (TournamentTables.number eq tableDto.number)
                }.firstOrNull() ?: TournamentTable.new {
                    tournamentId = tournament.id
                    number = tableDto.number
                    location = null // Изначально локация не задана
                }
            }
        }

        // Обрабатываем туры
        for (tourDto in tournamentDto.tours) {
            val tour = Tour.find {
                (Tours.tournamentId eq tournament.id) and (Tours.number eq tourDto.number)
            }.firstOrNull() ?: Tour.new {
                tournamentId = tournament.id
                number = tourDto.number
                startTime = tourDto.startTime
            }

            // Если время начала тура обновилось, сохраняем его
            if (tour.startTime != tourDto.startTime && tourDto.startTime != null) {
                tour.startTime = tourDto.startTime
            }

            // Обрабатываем игроков за столами для конкретного тура
            for (tableDto in tourDto.tables) {
                // Очищаем старые данные о рассадке для этого стола в туре
                TourTablePlayers.deleteWhere {
                    (TourTablePlayers.tourId eq tour.id) and
                        (TourTablePlayers.tableNumber eq tableDto.number)
                }

                // Добавляем новых игроков за стол
                for (playerDto in tableDto.players) {
                    // Находим или создаем игрока
                    val tablePosition =
                        tourDto.tables.find { it.number == tableDto.number }
                            ?.players?.find { it.gomafiaId == playerDto.gomafiaId }?.position ?: 0

                    TourTablePlayers.insert {
                        it[tourId] = tour.id
                        it[tableNumber] = tableDto.number
                        it[gomafiaId] = playerDto.gomafiaId
                        it[position] = tablePosition
                    }
                }
            }
        }

        tournament.toDto(includeTours = true)
    }

    fun getTournament(externalId: Int): TournamentDto? = transaction {
        TournamentEntity.find { Tournaments.externalId eq externalId }
            .firstOrNull()
            ?.toDto(includeTours = true)
    }

    fun getAllTournaments(): List<TournamentDto> = transaction {
        TournamentEntity.all().map { it.toDto(true) }
    }

    fun getAllFutureRunningTournaments(): List<TournamentDto> = transaction {
        TournamentEntity.find { Tournaments.ended neq true }.map { it.toDto(true) }
    }

    fun updateTourStartTime(tournamentId: Int, tourNumber: Int, startTime: String?): Boolean = transaction {
        val tournament = TournamentEntity.find { Tournaments.externalId eq tournamentId }.firstOrNull()
            ?: return@transaction false

        val tour = Tour.find {
            (Tours.tournamentId eq tournament.id) and (Tours.number eq tourNumber)
        }.firstOrNull() ?: return@transaction false

        tour.startTime = startTime
        true
    }

    // Метод обновляет местоположение стола для всего турнира (вместо конкретного тура)
    fun updateTableLocation(tournamentId: Int, tableNumber: Int, location: String?): Boolean = transaction {
        val tournament = TournamentEntity.find { Tournaments.externalId eq tournamentId }.firstOrNull()
            ?: return@transaction false

        // Находим стол турнира
        val tournamentTable = TournamentTable.find {
            (TournamentTables.tournamentId eq tournament.id) and
                (TournamentTables.number eq tableNumber)
        }.firstOrNull()

        if (tournamentTable != null) {
            // Обновляем местоположение существующего стола
            tournamentTable.location = location
            true
        } else {
            // Создаем новый стол, если он не существует
            TournamentTable.new {
                this.tournamentId = tournament.id
                this.number = tableNumber
                this.location = location
            }
            true
        }
    }

    // Метод для обновления всех данных о рассадке игроков в туре
    fun updateTourTablePlayers(tournamentId: Int, tourNumber: Int, tablePlayers: Map<Int, List<Int>>): Boolean = transaction {
        val tournament = TournamentEntity.find { Tournaments.externalId eq tournamentId }.firstOrNull()
            ?: return@transaction false

        val tour = Tour.find {
            (Tours.tournamentId eq tournament.id) and (Tours.number eq tourNumber)
        }.firstOrNull() ?: return@transaction false

        // Удаляем все существующие записи о рассадке для этого тура
        TourTablePlayers.deleteWhere { TourTablePlayers.tourId eq tour.id }

        // Добавляем новые записи о рассадке
        for ((itTableNumber, playerIds) in tablePlayers) {
            for (playerId in playerIds) {
                val player = Player.find { Players.gomafiaId eq playerId }.firstOrNull()
                if (player != null) {
                    TourTablePlayers.insert {
                        it[tourId] = tour.id
                        it[tableNumber] = itTableNumber
                        it[id] = player.id
                    }
                }
            }
        }

        true
    }

    // Метод для получения всех столов турнира с их местоположениями
    fun getTournamentTables(tournamentId: Int): Map<Int, String?> = transaction {
        val tournament = TournamentEntity.find { Tournaments.externalId eq tournamentId }.firstOrNull()
            ?: return@transaction emptyMap()

        TournamentTable.find { TournamentTables.tournamentId eq tournament.id }
            .associate { it.number to it.location }
    }
}
