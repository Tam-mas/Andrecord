package com.andrecord.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Session::class, TranscriptSegment::class], version = 2)
abstract class AndrecordDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun transcriptSegmentDao(): TranscriptSegmentDao

    companion object {
        /** Adds the two nullable transcript-processing-progress columns. Both are read as
         *  `null` for every pre-existing row (SQLite's `ADD COLUMN` always defaults to `NULL`
         *  with no explicit default), which is exactly the "no progress known yet" state these
         *  columns already use elsewhere -- no backfill needed. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN processingProgressPercent INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN processingEtaMillis INTEGER")
            }
        }

        fun build(context: Context): AndrecordDatabase =
            Room.databaseBuilder(context, AndrecordDatabase::class.java, "andrecord.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
