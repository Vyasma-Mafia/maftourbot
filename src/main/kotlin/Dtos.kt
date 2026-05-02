package online.mafoverlay

enum class TournamentSource { GOMAFIA, POLEMICA }

data class PlayerDto(
    val id: Int? = null,
    val telegramId: Long,
    val gomafiaProfileUrl: String,
    val gomafiaId: Int,
    val polemicaProfileUrl: String? = null,
    val polemicaId: Long? = null
)

data class TournamentDto(
    val id: Long,
    val name: String,
    val source: TournamentSource = TournamentSource.GOMAFIA,
    val tours: List<TourDto> = emptyList()
)

data class TourDto(
    val id: Int? = null,
    val tournamentId: Long? = null,
    val number: Int,
    val startTime: String? = null,
    val tables: List<TableDto> = emptyList()
)

data class TableDto(
    val id: Int? = null,
    val tourId: Int? = null,
    val number: Int,
    val location: String? = null,
    val players: List<PlayerGameDto> = emptyList()
)

data class PlayerGameDto(
    val externalPlayerId: Long,
    val position: Int
)

data class PlayerArrangementDto(
    val playerGame: PlayerGameDto,
    val telegramId: Long
)
