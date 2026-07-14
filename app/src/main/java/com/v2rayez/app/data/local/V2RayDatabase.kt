package com.v2rayez.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.v2rayez.app.domain.model.Protocol
import com.v2rayez.app.domain.model.ServerGroup

class EnumConverters {
    @TypeConverter fun protocolToString(p: Protocol): String = p.name
    @TypeConverter fun stringToProtocol(s: String): Protocol =
        runCatching { Protocol.valueOf(s) }.getOrDefault(Protocol.VLESS)
    @TypeConverter fun groupToString(g: ServerGroup): String = g.name
    @TypeConverter fun stringToGroup(s: String): ServerGroup =
        runCatching { ServerGroup.valueOf(s) }.getOrDefault(ServerGroup.ALL)
}

/**
 * Project history can recover schemas from v3 onward. The supported non-destructive path is
 * therefore 3→8; older unknown versions intentionally fail instead of wiping user data.
 */
@Database(
    entities = [ServerEntity::class, SubscriptionEntity::class, SessionEntity::class, DailyTrafficEntity::class],
    version = 8,
    exportSchema = true
)
@TypeConverters(EnumConverters::class)
abstract class V2RayDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun sessionDao(): SessionDao
    abstract fun dailyTrafficDao(): DailyTrafficDao

    companion object {
        /** v4 adds user-defined custom groups without dropping existing servers. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers ADD COLUMN customGroup TEXT DEFAULT NULL")
            }
        }

        /** v5 adds the persistent per-day traffic table backing the history charts. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS daily_traffic (" +
                        "dateEpochDay INTEGER NOT NULL PRIMARY KEY, " +
                        "downBytes INTEGER NOT NULL, " +
                        "upBytes INTEGER NOT NULL)"
                )
            }
        }

        /** v6 adds per-server proxy core preference (SYSTEM / XRAY / SING_BOX / CLASH). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE servers ADD COLUMN preferredCore TEXT NOT NULL DEFAULT 'SYSTEM'"
                )
            }
        }

        /** v7 adds P7 protocol params (SSH / WireGuard / DNS-tunnel / Psiphon). */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val text = listOf(
                    "sshUser", "sshPrivateKey", "sshHostKey",
                    "wgPrivateKey", "wgPeerPublicKey", "wgPreSharedKey",
                    "wgLocalAddresses", "wgReserved",
                    "dnsTunnelDomain", "dnsTunnelPubKey", "dnsTunnelResolver", "psiphonConfig"
                )
                text.forEach { col ->
                    db.execSQL("ALTER TABLE servers ADD COLUMN $col TEXT NOT NULL DEFAULT ''")
                }
                db.execSQL("ALTER TABLE servers ADD COLUMN dnsTunnelMode TEXT NOT NULL DEFAULT 'doh'")
                db.execSQL("ALTER TABLE servers ADD COLUMN wgMtu INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v8 preserves SIP003 Shadowsocks plugins and WireGuard peer AllowedIPs. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers ADD COLUMN ssPlugin TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE servers ADD COLUMN ssPluginOptions TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "ALTER TABLE servers ADD COLUMN wgAllowedIps TEXT NOT NULL " +
                        "DEFAULT '0.0.0.0/0,::/0'"
                )
            }
        }
    }
}
