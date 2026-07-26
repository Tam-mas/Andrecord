package com.andrecord.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Session::class, TranscriptSegment::class], version = 1)
abstract class AndrecordDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun transcriptSegmentDao(): TranscriptSegmentDao

    companion object {
        fun build(context: Context): AndrecordDatabase =
            Room.databaseBuilder(context, AndrecordDatabase::class.java, "andrecord.db").build()
    }
}
