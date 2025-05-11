package online.mafoverlay

data class PlayerDto(
    val id: Int? = null,
    val telegramId: Long,
    val gomafiaProfileUrl: String,
    val gomafiaId: Int
)

data class TournamentDto(
    val id: Int,
    val name: String,
    val tours: List<TourDto> = emptyList()
)

data class TourDto(
    val id: Int? = null,
    val tournamentId: Int? = null,
    val number: Int,
    val startTime: String? = null,
    val tables: List<TableDto> = emptyList()
)

data class TableDto(
    val id: Int? = null,
    val tourId: Int? = null, // Может быть null для стола турнира
    val number: Int,
    val location: String? = null,
    val players: List<PlayerDto> = emptyList()
)
