package com.trendpilot.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal

@Entity(tableName = "intents", indices = [Index("clientOrderId"), Index("mode", "status")])
data class IntentEntity(
    @PrimaryKey val id: String,
    val mode: String,
    val symbol: String,
    val kind: String,
    val side: String,
    val type: String,
    val quantity: BigDecimal,
    val price: BigDecimal?,
    val stopPrice: BigDecimal?,
    val clientOrderId: String,
    val status: String,
    val positionId: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val exchangeOrderId: Long?,
    val executedQty: BigDecimal,
    val cumulativeQuote: BigDecimal,
    val note: String,
)

@Entity(tableName = "positions", indices = [Index("mode", "status")])
data class PositionEntity(
    @PrimaryKey val id: String,
    val mode: String,
    val symbol: String,
    val baseAsset: String,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val entryFeesQuote: BigDecimal,
    val costBasisQuote: BigDecimal,
    val initialStop: BigDecimal,
    val currentStop: BigDecimal,
    val stopType: String,
    val stopLimitPrice: BigDecimal?,
    val stopClientOrderId: String?,
    val status: String,
    val openedAtMs: Long,
    val highestPrice: BigDecimal,
    val setup: String,
    val entryReasons: String,
    val dustQty: BigDecimal,
    val realizedQuote: BigDecimal,
    val realizedFeesQuote: BigDecimal,
    val closedAtMs: Long?,
    val exitReason: String?,
)

@Entity(tableName = "trades", indices = [Index("mode")])
data class TradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val positionId: String,
    val mode: String,
    val symbol: String,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal,
    val feesQuote: BigDecimal,
    val pnlQuote: BigDecimal,
    val rMultiple: Double,
    val openedAtMs: Long,
    val closedAtMs: Long,
    val setup: String,
    val exitReason: String,
)

@Entity(tableName = "events", indices = [Index("mode", "timestampMs")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val level: String,
    val mode: String,
    val title: String,
    val detail: String,
    val symbol: String?,
    val timestampMs: Long,
)

@Entity(tableName = "decisions", indices = [Index("mode", "timestampMs")])
data class DecisionEntity(
    @PrimaryKey val id: String,
    val mode: String,
    val timestampMs: Long,
    val kind: String,
    val symbol: String?,
    val summary: String,
    val details: String,
    val aiProvider: String?,
)

@Entity(tableName = "ai_usage", indices = [Index("provider", "timestampMs")])
data class AiUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
    val timestampMs: Long,
    val ok: Boolean,
    val error: String?,
)

@Entity(tableName = "equity", indices = [Index("mode", "timestampMs")])
data class EquityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String,
    val timestampMs: Long,
    val equity: Double,
    val btcPrice: Double,
)

@Entity(tableName = "kv")
data class KvEntity(@PrimaryKey val key: String, val value: String)

class Converters {
    @TypeConverter fun fromDecimal(v: BigDecimal?): String? = v?.toPlainString()
    @TypeConverter fun toDecimal(v: String?): BigDecimal? = v?.let(::BigDecimal)
}

@Dao
interface IntentDao {
    @Upsert suspend fun upsert(e: IntentEntity)
    @Query("SELECT * FROM intents WHERE clientOrderId = :cid ORDER BY updatedAtMs DESC LIMIT 1") suspend fun byClientOrderId(cid: String): IntentEntity?
    @Query("SELECT * FROM intents WHERE mode = :mode AND status IN ('PREPARED','UNKNOWN')") suspend fun unresolved(mode: String): List<IntentEntity>
    @Query("SELECT * FROM intents WHERE mode = :mode ORDER BY createdAtMs DESC LIMIT :limit") fun recent(mode: String, limit: Int = 200): Flow<List<IntentEntity>>
    @Query("SELECT * FROM intents WHERE mode = :mode ORDER BY createdAtMs DESC LIMIT :limit") suspend fun recentList(mode: String, limit: Int): List<IntentEntity>
}

@Dao
interface PositionDao {
    @Upsert suspend fun upsert(e: PositionEntity)
    @Query("SELECT * FROM positions WHERE id = :id") suspend fun get(id: String): PositionEntity?
    @Query("SELECT * FROM positions WHERE mode = :mode AND status != 'CLOSED'") suspend fun open(mode: String): List<PositionEntity>
    @Query("SELECT * FROM positions WHERE mode = :mode AND status != 'CLOSED' ORDER BY openedAtMs") fun openFlow(mode: String): Flow<List<PositionEntity>>
}

@Dao
interface TradeDao {
    @Insert suspend fun insert(e: TradeEntity)
    @Query("SELECT * FROM trades WHERE mode = :mode ORDER BY closedAtMs DESC") fun all(mode: String): Flow<List<TradeEntity>>
    @Query("SELECT * FROM trades WHERE mode = :mode ORDER BY closedAtMs DESC LIMIT :limit") suspend fun recentList(mode: String, limit: Int): List<TradeEntity>
}

@Dao
interface EventDao {
    @Insert suspend fun insert(e: EventEntity)
    @Query("SELECT * FROM events WHERE mode = :mode ORDER BY timestampMs DESC LIMIT :limit") fun recent(mode: String, limit: Int = 300): Flow<List<EventEntity>>
    @Query("SELECT * FROM events WHERE mode = :mode ORDER BY timestampMs DESC LIMIT :limit") suspend fun recentList(mode: String, limit: Int): List<EventEntity>
}

@Dao
interface DecisionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(e: DecisionEntity)
    @Query("SELECT * FROM decisions WHERE mode = :mode ORDER BY timestampMs DESC LIMIT :limit") fun recent(mode: String, limit: Int = 300): Flow<List<DecisionEntity>>
    @Query("SELECT * FROM decisions WHERE mode = :mode ORDER BY timestampMs DESC LIMIT :limit") suspend fun recentList(mode: String, limit: Int): List<DecisionEntity>
}

@Dao
interface AiUsageDao {
    @Insert suspend fun insert(e: AiUsageEntity)
    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM ai_usage WHERE provider = :provider AND timestampMs >= :fromMs") suspend fun spentSince(provider: String, fromMs: Long): Double
    @Query("SELECT * FROM ai_usage ORDER BY timestampMs DESC LIMIT 100") fun recent(): Flow<List<AiUsageEntity>>
    @Query("SELECT * FROM ai_usage ORDER BY timestampMs DESC LIMIT :limit") suspend fun recentList(limit: Int): List<AiUsageEntity>
}

@Dao
interface EquityDao {
    @Insert suspend fun insert(e: EquityEntity)
    @Query("SELECT * FROM equity WHERE mode = :mode ORDER BY timestampMs") fun all(mode: String): Flow<List<EquityEntity>>
    @Query("SELECT * FROM equity WHERE mode = :mode ORDER BY timestampMs LIMIT 1") suspend fun first(mode: String): EquityEntity?
}

@Dao
interface KvDao {
    @Upsert suspend fun put(e: KvEntity)
    @Query("SELECT value FROM kv WHERE `key` = :key") suspend fun get(key: String): String?
}

@Database(
    entities = [IntentEntity::class, PositionEntity::class, TradeEntity::class, EventEntity::class, DecisionEntity::class,
        AiUsageEntity::class, EquityEntity::class, KvEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun intents(): IntentDao
    abstract fun positions(): PositionDao
    abstract fun trades(): TradeDao
    abstract fun events(): EventDao
    abstract fun decisions(): DecisionDao
    abstract fun aiUsage(): AiUsageDao
    abstract fun equity(): EquityDao
    abstract fun kv(): KvDao
}
