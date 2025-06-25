package online.mafoverlay

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.select

// Определение таблиц
object Players : IntIdTable() {
    val telegramId = long("telegram_id").uniqueIndex()
    val gomafiaProfileUrl = text("gomafia_profile_url")
    val gomafiaId = integer("gomafia_id").index()
}

object Tournaments : IntIdTable() {
    val externalId = integer("external_id").uniqueIndex()
    val name = text("name")
    val ended = bool("ended").default(false)
}

object Tours : IntIdTable() {
    val tournamentId = reference("tournament_id", Tournaments)
    val number = integer("number")
    val startTime = text("start_time").nullable()

    init {
        uniqueIndex(tournamentId, number)
    }
}

// Таблица столов теперь связана с турниром, а не с туром
object TournamentTables : IntIdTable("tournament_tables") {
    val tournamentId = reference("tournament_id", Tournaments)
    val number = integer("number")
    val location = text("location").nullable()

    init {
        uniqueIndex(tournamentId, number)
    }
}

// Связь между туром и игроками за столами
object TourTablePlayers : IntIdTable("tour_table_players") {
    val tourId = reference("tour_id", Tours)
    val tableNumber = integer("table_number")
    val gomafiaId = integer("gomafia_id").nullable()
    val position = integer("position")

    init {
        index(true, tourId, tableNumber, position)
    }
}

// Сущности для DAO
class Player(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Player>(Players)

    var telegramId by Players.telegramId
    var gomafiaProfileUrl by Players.gomafiaProfileUrl
    var gomafiaId by Players.gomafiaId

    fun toDto() = PlayerDto(
        id = id.value,
        telegramId = telegramId,
        gomafiaProfileUrl = gomafiaProfileUrl,
        gomafiaId = gomafiaId
    )
}

class TournamentEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TournamentEntity>(Tournaments)

    var externalId by Tournaments.externalId
    var name by Tournaments.name
    val tours by Tour.referrersOn(Tours.tournamentId)
    val tables by TournamentTable.referrersOn(TournamentTables.tournamentId)

    fun toDto(includeTours: Boolean = false) = TournamentDto(
        id = externalId,
        name = name,
        tours = if (includeTours) tours.map { it.toDto(includeTables = true) } else emptyList()
    )
}

class Tour(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Tour>(Tours)

    var tournamentId by Tours.tournamentId
    var number by Tours.number
    var startTime by Tours.startTime

    // Получаем игроков для столов в этом туре
    fun getTablesWithPlayers(): Map<Int, List<PlayerGameDto>> {
        return TourTablePlayers.join(Players, JoinType.INNER, TourTablePlayers.gomafiaId, Players.gomafiaId)
            .select { TourTablePlayers.tourId eq this@Tour.id }
            .groupBy(
                { it[TourTablePlayers.tableNumber] },
                { PlayerGameDto(Player.wrapRow(it).gomafiaId, TourTablePlayer.wrapRow(it).position) }
            )
    }

    fun toDto(includeTables: Boolean = false): TourDto {
        val tournamentTables = TournamentEntity[tournamentId].tables.map { it.toDto() }
        val tourTablesWithPlayers = if (includeTables) getTablesWithPlayers() else emptyMap()

        // Формируем список столов с учетом местоположений из таблицы TournamentTables
        val tables = tournamentTables.map { tableDto ->
            val players = tourTablesWithPlayers[tableDto.number] ?: emptyList()

            TableDto(
                id = tableDto.id,
                tourId = id.value,
                number = tableDto.number,
                location = tableDto.location,
                players = players
            )
        }

        return TourDto(
            id = id.value,
            tournamentId = tournamentId.value,
            number = number,
            startTime = startTime,
            tables = tables
        )
    }
}

class TournamentTable(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TournamentTable>(TournamentTables)

    var tournamentId by TournamentTables.tournamentId
    var number by TournamentTables.number
    var location by TournamentTables.location

    fun toDto() = TableDto(
        id = id.value,
        tourId = null, // Стол не привязан к конкретному туру
        number = number,
        location = location,
        players = emptyList() // Заполняется на уровне тура
    )
}

class TourTablePlayer(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TourTablePlayer>(TourTablePlayers)

    var tourId by TourTablePlayers.tourId
    var tableNumber by TourTablePlayers.tableNumber
    var playerId by TourTablePlayers.gomafiaId
    var position by TourTablePlayers.position
}
