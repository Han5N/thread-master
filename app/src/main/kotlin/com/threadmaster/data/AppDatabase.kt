package com.threadmaster.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SessionEntity::class, SysSampleEntity::class, ThreadSampleEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun sampleDao(): SampleDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v2 新增了 GPU 型号、刷新率、load、zram、进程 IO 等字段。
         * 全部走 ADD COLUMN 增量迁移，老会话的数据完整保留。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sys_samples ADD COLUMN gpuModel TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sys_samples ADD COLUMN gpuMaxMhz INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sys_samples ADD COLUMN refreshHz REAL NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE sys_samples ADD COLUMN load1 REAL NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE sys_samples ADD COLUMN zramUsedMb INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sys_samples ADD COLUMN batteryPowerMw REAL NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE sys_samples ADD COLUMN ctxtPerSec INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sys_samples ADD COLUMN targetIoReadKb INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sys_samples ADD COLUMN targetIoWriteKb INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "threadmaster.db",
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
